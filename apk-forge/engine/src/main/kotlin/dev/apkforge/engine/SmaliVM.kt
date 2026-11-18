package dev.apkforge.engine

import java.io.File

/**
 * Intérprete de smali AISLADO para ejecutar métodos "puros" de la app (p. ej. el que descifra
 * textos). Solo permite: aritmética, arrays, clases del propio proyecto (en memoria) y una lista
 * blanca de clases de Java seguras (String, StringBuilder, Base64, Cipher, MessageDigest…).
 * No puede abrir archivos, red, procesos ni nada del sistema Android: si el código lo intenta,
 * se detiene. Además tiene un límite de instrucciones para no colgarse.
 */
class SmaliVM(private val project: File, private val maxSteps: Long = 3_000_000) {

    class Unsupported(msg: String) : Exception(msg)

    /** Objeto de una clase del proyecto, en memoria. */
    class VmObject(val cls: String) { val fields = HashMap<String, Any?>() ; override fun toString() = "VmObject($cls)" }
    /** new-instance de una clase Java pendiente de su constructor. */
    private class Pending(val cls: String)

    private class Method(val cls: String, val name: String, val desc: String, val isStatic: Boolean, val locals: Int,
                         val insns: List<String>, val labels: Map<String, Int>, val payloads: Map<String, List<String>>) {
        val params: List<String> = parseParams(desc)
    }
    private class ClassDef(val name: String, val sup: String, val methods: Map<String, Method>)

    private val classes = HashMap<String, ClassDef?>()
    private val statics = HashMap<String, Any?>()
    private val initialized = HashSet<String>()
    private var steps = 0L
    private var smaliIndex: Map<String, File>? = null

    private val ALLOWED = setOf(
        "java.lang.String", "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.Character", "java.lang.Integer",
        "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Math", "java.lang.CharSequence", "java.lang.Boolean",
        "java.util.Arrays", "java.util.Base64", "java.util.Base64\$Decoder", "java.util.Base64\$Encoder",
        "java.nio.charset.Charset", "java.nio.charset.StandardCharsets", "java.math.BigInteger",
        "javax.crypto.Cipher", "javax.crypto.spec.SecretKeySpec", "javax.crypto.spec.IvParameterSpec", "javax.crypto.spec.GCMParameterSpec",
        "javax.crypto.SecretKeyFactory", "javax.crypto.spec.PBEKeySpec", "javax.crypto.SecretKey", "java.security.Key",
        "java.security.MessageDigest", "java.security.spec.KeySpec", "java.lang.Object",
    )

    // ───────────── API ─────────────

    /** Ejecuta un método estático del proyecto con esos argumentos y devuelve el resultado. */
    fun callStatic(cls: String, name: String, desc: String, args: List<Any?>): Any? {
        steps = 0
        val m = method(cls, name, desc) ?: throw Unsupported("No existe $cls->$name$desc")
        return run(m, args)
    }

    // ───────────── carga de smali ─────────────

    private fun index(): Map<String, File> = smaliIndex ?: HashMap<String, File>().also { idx ->
        File(project, "smali").walkTopDown().filter { it.isFile && it.extension == "smali" }.forEach { f ->
            val rel = f.path.substringAfter("/smali/").substringAfter('/').removeSuffix(".smali")
            idx.putIfAbsent("L$rel;", f)
        }
        smaliIndex = idx
    }

    private fun classDef(name: String): ClassDef? = classes.getOrPut(name) {
        val f = index()[name] ?: return@getOrPut null
        parseClass(f.readText())
    }

    private fun parseClass(text: String): ClassDef {
        val lines = text.lines()
        val name = lines.first { it.startsWith(".class") }.substringAfterLast(' ')
        val sup = lines.firstOrNull { it.startsWith(".super") }?.substringAfterLast(' ') ?: "Ljava/lang/Object;"
        val methods = HashMap<String, Method>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.startsWith(".method")) {
                val sig = l.split(' ').last()
                val isStatic = l.split(' ').contains("static")
                var locals = 0
                val insns = ArrayList<String>(); val labels = HashMap<String, Int>(); val payloads = HashMap<String, List<String>>()
                i++
                var pendingLabel: String? = null
                while (i < lines.size && !lines[i].trim().startsWith(".end method")) {
                    val t = lines[i].trim()
                    when {
                        t.startsWith(".locals") -> locals = t.substringAfter(".locals").trim().toInt()
                        t.startsWith(".registers") -> locals = -t.substringAfter(".registers").trim().toInt()   // negativo = total
                        t.startsWith(":") -> { labels[t] = insns.size; pendingLabel = t }
                        t.startsWith(".array-data") || t.startsWith(".packed-switch") || t.startsWith(".sparse-switch") -> {
                            val body = ArrayList<String>(); body += t; i++
                            while (i < lines.size && !lines[i].trim().startsWith(".end")) { lines[i].trim().takeIf { it.isNotEmpty() }?.let { body += it }; i++ }
                            pendingLabel?.let { payloads[it] = body }
                        }
                        t.isEmpty() || t.startsWith(".") || t.startsWith("#") -> {}
                        else -> { insns += t; pendingLabel = null }
                    }
                    i++
                }
                val mName = sig.substringBefore('('); val desc = "(" + sig.substringAfter('(')
                methods["$mName$desc"] = Method(name, mName, desc, isStatic, locals, insns, labels, payloads)
            }
            i++
        }
        return ClassDef(name, sup, methods)
    }

    private fun method(cls: String, name: String, desc: String): Method? {
        var c: String? = cls
        var depth = 0
        while (c != null && depth++ < 12) {
            val def = classDef(c) ?: return null
            def.methods["$name$desc"]?.let { return it }
            c = def.sup
        }
        return null
    }

    private fun ensureInit(cls: String) {
        if (!initialized.add(cls)) return
        classDef(cls)?.methods?.get("<clinit>()V")?.let { run(it, emptyList()) }
    }

    // ───────────── ejecución ─────────────

    private fun run(m: Method, args: List<Any?>): Any? {
        val paramRegs: Int = m.params.map { if (it == "J" || it == "D") 2 else 1 }.sum() + (if (m.isStatic) 0 else 1)
        val total = if (m.locals < 0) -m.locals else m.locals + paramRegs
        val firstParam = total - paramRegs
        val r = arrayOfNulls<Any?>(maxOf(total, 1) + 2)
        var p = firstParam
        var ai = 0
        if (!m.isStatic) { r[p++] = args[ai++] }
        m.params.forEach { t -> r[p] = args.getOrNull(ai++); p += if (t == "J" || t == "D") 2 else 1 }

        fun reg(s: String): Int { val n = s.trim().drop(1).toInt(); return if (s.trim()[0] == 'p') firstParam + n else n }
        fun I(s: String) = (r[reg(s)] as? Number)?.toInt() ?: (r[reg(s)] as? Char)?.code ?: 0
        fun L(s: String) = (r[reg(s)] as? Number)?.toLong() ?: 0L
        fun lit(s: String): Long { val t = s.trim(); val neg = t.startsWith("-"); val v = t.removePrefix("-").removeSuffix("L").removeSuffix("t").removeSuffix("s");
            val n = if (v.startsWith("0x")) java.lang.Long.parseUnsignedLong(v.drop(2), 16) else v.toLong(); return if (neg) -n else n }

        var pc = 0
        var result: Any? = null
        while (true) {
            if (++steps > maxSteps) throw Unsupported("Demasiadas instrucciones (¿bucle?)")
            if (pc >= m.insns.size) throw Unsupported("Fin inesperado de ${m.name}")
            val ins = m.insns[pc]
            val op = ins.substringBefore(' ')
            val rest = ins.substringAfter(' ', "")
            val a = splitArgs(rest)
            var next = pc + 1
            when {
                op == "nop" || op.startsWith("monitor-") || op == "check-cast" -> {}
                op.startsWith("move-result") -> r[reg(a[0])] = result
                op == "move-exception" -> throw Unsupported("excepciones")
                op.startsWith("move") -> r[reg(a[0])] = r[reg(a[1])]
                op == "return-void" -> return null
                op.startsWith("return") -> return r[reg(a[0])]
                op == "const-string" || op == "const-string/jumbo" -> r[reg(a[0])] = unescape(rest.substringAfter('"').substringBeforeLast('"'))
                op == "const-class" -> r[reg(a[0])] = a[1]
                op.startsWith("const-wide") -> r[reg(a[0])] = if (op.endsWith("high16")) lit(a[1]) else lit(a[1])
                op.startsWith("const") -> r[reg(a[0])] = lit(a[1]).toInt()
                op == "array-length" -> r[reg(a[0])] = java.lang.reflect.Array.getLength(r[reg(a[1])] ?: throw Unsupported("array nulo"))
                op == "new-array" -> r[reg(a[0])] = newArray(a[2], I(a[1]))
                op == "filled-new-array" || op == "filled-new-array/range" -> {
                    val regs = regList(rest)
                    val type = rest.substringAfterLast(", ").trim()
                    val arr = newArray(type, regs.size)
                    regs.forEachIndexed { i, s -> aput(arr, i, r[reg(s)]) }
                    result = arr
                }
                op == "fill-array-data" -> fillArray(r[reg(a[0])]!!, m.payloads[a[1]] ?: throw Unsupported("sin datos"))
                op.startsWith("aget") -> r[reg(a[0])] = aget(r[reg(a[1])] ?: throw Unsupported("array nulo"), I(a[2]))
                op.startsWith("aput") -> aput(r[reg(a[1])] ?: throw Unsupported("array nulo"), I(a[2]), r[reg(a[0])])
                op.startsWith("if-") -> {
                    val z = op.endsWith("z")
                    val x: Long; val y: Long
                    if (z) { val v = r[reg(a[0])]; x = when (v) { null -> 0; is Number -> v.toLong(); is Char -> v.code.toLong(); else -> 1 }; y = 0 }
                    else { val v1 = r[reg(a[0])]; val v2 = r[reg(a[1])]
                        if (v1 !is Number && v2 !is Number && (op.startsWith("if-eq") || op.startsWith("if-ne"))) { x = if (v1 === v2) 0 else 1; y = 0 }
                        else { x = (v1 as? Number)?.toLong() ?: 0; y = (v2 as? Number)?.toLong() ?: 0 } }
                    val cond = when (op.removeSuffix("z")) { "if-eq" -> x == y; "if-ne" -> x != y; "if-lt" -> x < y; "if-ge" -> x >= y; "if-gt" -> x > y; "if-le" -> x <= y; else -> throw Unsupported(op) }
                    if (cond) next = m.labels[a.last()] ?: throw Unsupported("etiqueta ${a.last()}")
                }
                op.startsWith("goto") -> next = m.labels[a[0]] ?: throw Unsupported("etiqueta")
                op == "packed-switch" || op == "sparse-switch" -> {
                    val pl = m.payloads[a[1]] ?: throw Unsupported("switch")
                    val v = I(a[0])
                    if (op == "packed-switch") {
                        val first = lit(pl[0].substringAfter(".packed-switch").trim()).toInt()
                        pl.drop(1).getOrNull(v - first)?.let { next = m.labels[it] ?: next }
                    } else pl.drop(1).firstOrNull { lit(it.substringBefore("->").trim()).toInt() == v }?.let { next = m.labels[it.substringAfter("->").trim()] ?: next }
                }
                op == "cmp-long" -> r[reg(a[0])] = L(a[1]).compareTo(L(a[2])).coerceIn(-1, 1)
                op.startsWith("cmp") -> throw Unsupported("coma flotante")
                op.startsWith("neg-") || op.startsWith("not-") || op.contains("-to-") -> r[reg(a[0])] = unop(op, r[reg(a[1])])
                op == "rsub-int" || op == "rsub-int/lit8" -> r[reg(a[0])] = lit(a[2]).toInt() - I(a[1])
                op.matches(Regex("(add|sub|mul|div|rem|and|or|xor|shl|shr|ushr)-(int|long).*")) -> {
                    val kind = op.substringBefore('-'); val long = op.contains("-long")
                    val (dst, x, y) = when {
                        op.endsWith("/2addr") -> Triple(a[0], r[reg(a[0])], r[reg(a[1])])
                        op.contains("/lit") -> Triple(a[0], r[reg(a[1])], lit(a[2]))
                        else -> Triple(a[0], r[reg(a[1])], r[reg(a[2])])
                    }
                    r[reg(dst)] = binop(kind, long, x, y)
                }
                op.startsWith("sget") -> { val f = a[1]; r[reg(a[0])] = sget(f) }
                op.startsWith("sput") -> { val f = a[1]; ensureInit(f.substringBefore("->")); statics[f] = r[reg(a[0])] }
                op.startsWith("iget") -> { val o = r[reg(a[1])] as? VmObject ?: throw Unsupported("iget en objeto no soportado"); r[reg(a[0])] = o.fields[a[2].substringAfter("->")] ?: defaultFor(a[2].substringAfterLast(':')) }
                op.startsWith("iput") -> { val o = r[reg(a[1])] as? VmObject ?: throw Unsupported("iput en objeto no soportado"); o.fields[a[2].substringAfter("->")] = r[reg(a[0])] }
                op == "new-instance" -> {
                    val c = a[1]
                    r[reg(a[0])] = if (classDef(c) != null) { ensureInit(c); VmObject(c) } else if (javaName(c) in ALLOWED) Pending(c) else throw Unsupported("new $c")
                }
                op == "instance-of" -> r[reg(a[0])] = if (r[reg(a[1])] != null) 1 else 0
                op.startsWith("invoke-") -> {
                    val regs = regList(rest)
                    val target = rest.substringAfterLast("}, ").trim()
                    val owner = target.substringBefore("->")
                    val name = target.substringAfter("->").substringBefore('(')
                    val desc = "(" + target.substringAfter('(')
                    val isStatic = op.startsWith("invoke-static")
                    val params = parseParams(desc)
                    // valores (los wide ocupan dos registros)
                    val vals = ArrayList<Any?>()
                    var k = 0
                    if (!isStatic) vals += r[reg(regs[k++])]
                    params.forEach { t -> vals += r[reg(regs[k])]; k += if (t == "J" || t == "D") 2 else 1 }
                    result = invoke(op, owner, name, desc, vals, r, regs)
                }
                op == "throw" -> throw Unsupported("la app lanzó una excepción")
                else -> throw Unsupported("instrucción $op")
            }
            pc = next
        }
    }

    private fun invoke(op: String, owner: String, name: String, desc: String, vals: List<Any?>, r: Array<Any?>, regs: List<String>): Any? {
        val recv = if (op.startsWith("invoke-static")) null else vals.first()
        // Objetos de clases del proyecto: virtual → su clase real; direct/super → la clase indicada
        if (recv is VmObject) {
            val start = if (op.startsWith("invoke-virtual") || op.startsWith("invoke-interface")) recv.cls else owner
            val m = method(start, name, desc) ?: method(owner, name, desc)
            if (m != null) return run(m, vals)
            if (name == "<init>") return null          // constructor de Object/clase Java base
            throw Unsupported("método $owner->$name")
        }
        // Métodos estáticos (o directos) de clases del proyecto
        if (classDef(owner) != null && recv !is Pending) {
            if (op.startsWith("invoke-static")) ensureInit(owner)
            method(owner, name, desc)?.let { return run(it, vals) }
            throw Unsupported("método $owner->$name")
        }
        // android.util.Base64 → equivalente Java
        if (owner == "Landroid/util/Base64;") return androidBase64(name, vals)
        if (owner == "Landroid/text/TextUtils;" && name == "isEmpty") return if ((vals[0] as? CharSequence).isNullOrEmpty()) 1 else 0
        val jn = javaName(owner)
        if (jn !in ALLOWED && !(recv != null && javaName(recv.javaClass.name.let { "L" + it.replace('.', '/') + ";" }) in ALLOWED))
            throw Unsupported("llamada no permitida: $jn.$name")
        return reflectCall(op, owner, name, desc, vals, r, regs)
    }

    private fun reflectCall(op: String, owner: String, name: String, desc: String, vals: List<Any?>, r: Array<Any?>, regs: List<String>): Any? {
        val params = parseParams(desc)
        val pTypes = params.map { classOf(it) }.toTypedArray()
        val ret = desc.substringAfterLast(')')
        val args = params.mapIndexed { i, t -> coerce(vals[i + if (op.startsWith("invoke-static")) 0 else 1], t) }.toTypedArray()
        val cls = Class.forName(javaName(owner))
        if (name == "<init>") {
            val pending = vals[0] as? Pending ?: throw Unsupported("constructor")
            val obj = cls.getConstructor(*pTypes).newInstance(*args)
            // el objeto real sustituye al pendiente en todos los registros
            for (i in r.indices) if (r[i] === pending) r[i] = obj
            return null
        }
        val m = cls.getMethod(name, *pTypes)
        val res = if (java.lang.reflect.Modifier.isStatic(m.modifiers)) m.invoke(null, *args) else m.invoke(vals[0], *args)
        return fromJava(res, ret)
    }

    private fun androidBase64(name: String, vals: List<Any?>): Any? {
        val flags = (vals.lastOrNull() as? Number)?.toInt() ?: 0
        val url = flags and 8 != 0
        return when (name) {
            "decode" -> {
                val input = when (val v = vals[0]) { is String -> v; is ByteArray -> String(v, Charsets.ISO_8859_1); else -> throw Unsupported("Base64") }
                val clean = input.filterNot { it.isWhitespace() }
                (if (url) java.util.Base64.getUrlDecoder() else java.util.Base64.getMimeDecoder()).decode(clean.padEnd((clean.length + 3) / 4 * 4, '='))
            }
            "encodeToString" -> (if (url) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()).let { e ->
                val s = e.encodeToString(vals[0] as ByteArray); if (flags and 1 != 0) s.trimEnd('=') else s }
            "encode" -> java.util.Base64.getEncoder().encode(vals[0] as ByteArray)
            else -> throw Unsupported("Base64.$name")
        }
    }

    private fun sget(f: String): Any? {
        val owner = f.substringBefore("->")
        if (classDef(owner) != null) { ensureInit(owner); return statics[f] ?: defaultFor(f.substringAfterLast(':')) }
        val jn = javaName(owner)
        if (jn !in ALLOWED) throw Unsupported("campo no permitido $jn")
        val field = Class.forName(jn).getField(f.substringAfter("->").substringBefore(':'))
        return fromJava(field.get(null), f.substringAfterLast(':'))
    }

    // ───────────── utilidades ─────────────

    private fun defaultFor(t: String): Any? = when (t) { "I", "B", "S", "C", "Z" -> 0; "J" -> 0L; else -> null }

    private fun coerce(v: Any?, t: String): Any? = when (t) {
        "I" -> (v as? Number)?.toInt() ?: 0
        "J" -> (v as? Number)?.toLong() ?: 0L
        "C" -> ((v as? Number)?.toInt() ?: 0).toChar()
        "B" -> ((v as? Number)?.toInt() ?: 0).toByte()
        "S" -> ((v as? Number)?.toInt() ?: 0).toShort()
        "Z" -> ((v as? Number)?.toInt() ?: 0) != 0
        "F" -> java.lang.Float.intBitsToFloat((v as? Number)?.toInt() ?: 0)
        "D" -> java.lang.Double.longBitsToDouble((v as? Number)?.toLong() ?: 0)
        else -> if (v is Pending) throw Unsupported("objeto sin construir") else v
    }

    private fun fromJava(v: Any?, t: String): Any? = when (v) {
        is Char -> v.code; is Boolean -> if (v) 1 else 0; is Byte -> v.toInt(); is Short -> v.toInt()
        is Float -> java.lang.Float.floatToIntBits(v); is Double -> java.lang.Double.doubleToLongBits(v)
        else -> v
    }

    private fun classOf(t: String): Class<*> = when (t) {
        "I" -> Int::class.javaPrimitiveType!!; "J" -> Long::class.javaPrimitiveType!!; "C" -> Char::class.javaPrimitiveType!!
        "B" -> Byte::class.javaPrimitiveType!!; "S" -> Short::class.javaPrimitiveType!!; "Z" -> Boolean::class.javaPrimitiveType!!
        "F" -> Float::class.javaPrimitiveType!!; "D" -> Double::class.javaPrimitiveType!!; "V" -> Void.TYPE
        else -> if (t.startsWith("[")) Class.forName(t.replace('/', '.')) else Class.forName(javaName(t))
    }

    private fun newArray(type: String, n: Int): Any = when (type.trim()) {
        "[B" -> ByteArray(n); "[C" -> CharArray(n); "[I" -> IntArray(n); "[S" -> ShortArray(n); "[J" -> LongArray(n); "[Z" -> BooleanArray(n)
        else -> arrayOfNulls<Any?>(n)
    }

    private fun aget(arr: Any, i: Int): Any? = when (arr) {
        is ByteArray -> arr[i].toInt(); is CharArray -> arr[i].code; is IntArray -> arr[i]; is ShortArray -> arr[i].toInt()
        is LongArray -> arr[i]; is BooleanArray -> if (arr[i]) 1 else 0; is Array<*> -> arr[i]; else -> throw Unsupported("aget")
    }

    private fun aput(arr: Any, i: Int, v: Any?) {
        when (arr) {
            is ByteArray -> arr[i] = ((v as? Number)?.toInt() ?: 0).toByte(); is CharArray -> arr[i] = ((v as? Number)?.toInt() ?: 0).toChar()
            is IntArray -> arr[i] = (v as? Number)?.toInt() ?: 0; is ShortArray -> arr[i] = ((v as? Number)?.toInt() ?: 0).toShort()
            is LongArray -> arr[i] = (v as? Number)?.toLong() ?: 0; is BooleanArray -> arr[i] = ((v as? Number)?.toInt() ?: 0) != 0
            is Array<*> -> @Suppress("UNCHECKED_CAST") (arr as Array<Any?>).set(i, v)
            else -> throw Unsupported("aput")
        }
    }

    private fun fillArray(arr: Any, payload: List<String>) {
        val values = payload.drop(1).map { v -> val t = v.trim(); val neg = t.startsWith("-"); val s = t.removePrefix("-").removeSuffix("t").removeSuffix("s").removeSuffix("L")
            val n = if (s.startsWith("0x")) java.lang.Long.parseUnsignedLong(s.drop(2), 16) else s.toLong(); if (neg) -n else n }
        values.forEachIndexed { i, n -> aput(arr, i, if (arr is LongArray) n else n.toInt()) }
    }

    private fun unop(op: String, v: Any?): Any? {
        val i = (v as? Number)?.toInt() ?: 0; val l = (v as? Number)?.toLong() ?: 0L
        return when (op) {
            "neg-int" -> -i; "not-int" -> i.inv(); "neg-long" -> -l; "not-long" -> l.inv()
            "int-to-long" -> i.toLong(); "long-to-int" -> l.toInt()
            "int-to-char" -> i and 0xFFFF; "int-to-byte" -> i.toByte().toInt(); "int-to-short" -> i.toShort().toInt()
            else -> throw Unsupported(op)
        }
    }

    private fun binop(kind: String, long: Boolean, x: Any?, y: Any?): Any {
        if (long) {
            val a = (x as? Number)?.toLong() ?: 0; val b = (y as? Number)?.toLong() ?: 0
            return when (kind) { "add" -> a + b; "sub" -> a - b; "mul" -> a * b; "div" -> if (b == 0L) throw Unsupported("/0") else a / b
                "rem" -> if (b == 0L) throw Unsupported("/0") else a % b; "and" -> a and b; "or" -> a or b; "xor" -> a xor b
                "shl" -> a shl b.toInt(); "shr" -> a shr b.toInt(); "ushr" -> a ushr b.toInt(); else -> throw Unsupported(kind) }
        }
        val a = (x as? Number)?.toInt() ?: (x as? Char)?.code ?: 0; val b = (y as? Number)?.toInt() ?: (y as? Char)?.code ?: 0
        return when (kind) { "add" -> a + b; "sub" -> a - b; "mul" -> a * b; "div" -> if (b == 0) throw Unsupported("/0") else a / b
            "rem" -> if (b == 0) throw Unsupported("/0") else a % b; "and" -> a and b; "or" -> a or b; "xor" -> a xor b
            "shl" -> a shl b; "shr" -> a shr b; "ushr" -> a ushr b; else -> throw Unsupported(kind) }
    }

    companion object {
        fun parseParams(desc: String): List<String> {
            val s = desc.substringAfter('(').substringBefore(')')
            val out = ArrayList<String>(); var i = 0
            while (i < s.length) {
                val start = i
                while (s[i] == '[') i++
                if (s[i] == 'L') i = s.indexOf(';', i)
                i++
                out += s.substring(start, i)
            }
            return out
        }
        fun javaName(desc: String) = desc.removePrefix("L").removeSuffix(";").replace('/', '.')

        private fun splitArgs(rest: String): List<String> {
            if (rest.startsWith("{")) return emptyList()
            // respeta cadenas con comas
            val out = ArrayList<String>(); val sb = StringBuilder(); var inStr = false; var esc = false
            for (c in rest) {
                if (inStr) { sb.append(c); if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
                when (c) { '"' -> { inStr = true; sb.append(c) }; ',' -> { out += sb.toString().trim(); sb.clear() }; else -> sb.append(c) }
            }
            if (sb.isNotBlank()) out += sb.toString().trim()
            return out
        }

        fun regList(rest: String): List<String> {
            val inside = rest.substringAfter('{').substringBefore('}').trim()
            if (inside.isEmpty()) return emptyList()
            if (inside.contains("..")) {
                val (a, b) = inside.split("..").map { it.trim() }
                val prefix = a[0]; val from = a.drop(1).toInt(); val to = b.drop(1).toInt()
                return (from..to).map { "$prefix$it" }
            }
            return inside.split(',').map { it.trim() }
        }

        /** Escapes de smali: \n \t \" \\ \uXXXX \' */
        fun unescape(s: String): String {
            val sb = StringBuilder(); var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> { sb.append('\n'); i += 2 }; 't' -> { sb.append('\t'); i += 2 }; 'r' -> { sb.append('\r'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }; 'f' -> { sb.append('\u000c'); i += 2 }; '0' -> { sb.append('\u0000'); i += 2 }
                        'u' -> { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                        else -> { sb.append(n); i += 2 }
                    }
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
    }
}
