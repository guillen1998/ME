package dev.apkforge.engine

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File

/**
 * TEXTOS CIFRADOS: muchas apps guardan URLs, claves y mensajes cifrados y los descifran al
 * ejecutarse con un método propio, p. ej. a.b("x9F2kq…"). La app y la IA los descifran juntas:
 *
 *  1. La APP detecta los métodos descifradores (estáticos, devuelven String, llamados muchas veces
 *     con constantes raras) y los EJECUTA en un intérprete aislado (SmaliVM) con esas constantes.
 *  2. Si no se pueden ejecutar, prueba los cifrados típicos (Base64, XOR, ROT, invertir, hex, AES/RC4
 *     con claves encontradas en la propia clase).
 *  3. Si tampoco, la IA lee el descifrador y dice el algoritmo como una receta de pasos; la app la
 *     aplica a TODOS los textos y solo la acepta si el resultado es legible.
 *
 *  Los textos descifrados se ven en el Java (como comentario), los usa la IA y el escáner de URLs.
 *  El smali no se toca.
 */
object StringDecryptor {

    data class Site(val file: String, val line: Int, val inMethod: String, val args: List<Any?>)
    data class Decryptor(val cls: String, val name: String, val desc: String, val sites: List<Site>) {
        val id get() = "$cls->$name$desc"
        val label get() = cls.removePrefix("L").removeSuffix(";").substringAfterLast('/') + "." + name + "()"
    }
    data class Decrypted(val file: String, val line: Int, val inMethod: String, val decryptor: String, val input: String, val output: String, val how: String)
    data class Report(val decryptors: Int, val decrypted: Int, val pending: List<String>)

    private val gson = Gson()
    fun file(project: File) = File(project, "$META_DIR/strings/decrypted.json")
    fun load(project: File): List<Decrypted> = runCatching { gson.fromJson(file(project).readText(), Array<Decrypted>::class.java).toList() }.getOrDefault(emptyList())
    private fun save(project: File, list: List<Decrypted>) = file(project).apply { parentFile?.mkdirs(); writeText(gson.toJson(list)) }

    // ───────────── 1) detectar ─────────────

    private val SUPPORTED_PARAMS = setOf("Ljava/lang/String;", "I", "J", "C", "S", "B", "Z")

    fun findDecryptors(project: File): List<Decryptor> {
        val sites = HashMap<String, MutableList<Site>>()
        File(project, "smali").walkTopDown().filter { it.isFile && it.extension == "smali" && ProjectReader.LIB.none { l -> it.path.contains(l) } }.forEach { f ->
            val lines = runCatching { f.readLines() }.getOrNull() ?: return@forEach
            if (lines.none { it.contains("invoke-static") && it.endsWith(")Ljava/lang/String;") }) return@forEach
            var method = ""; var methodStart = 0
            lines.forEachIndexed { i, raw ->
                val l = raw.trim()
                if (l.startsWith(".method")) { method = l.split(' ').last(); methodStart = i }
                if (!l.startsWith("invoke-static") || !l.endsWith(")Ljava/lang/String;")) return@forEachIndexed
                val target = l.substringAfterLast("}, ")
                val desc = "(" + target.substringAfter('(')
                val params = SmaliVM.parseParams(desc)
                if (params.isEmpty() || params.any { it !in SUPPORTED_PARAMS }) return@forEachIndexed
                val owner = target.substringBefore("->")
                if (owner.startsWith("Ljava/") || owner.startsWith("Landroid/") || owner.startsWith("Lkotlin/")) return@forEachIndexed
                val regs = SmaliVM.regList(l.substringAfter(' '))
                val args = resolveConstants(lines, i, methodStart, regs, params) ?: return@forEachIndexed
                sites.getOrPut(target) { mutableListOf() } += Site(f.relativeTo(project).path, i + 1, method, args)
            }
        }
        return sites.mapNotNull { (target, list) ->
            val owner = target.substringBefore("->"); val name = target.substringAfter("->").substringBefore('(')
            val strArgs = list.flatMap { it.args.filterIsInstance<String>() }
            val weird = if (strArgs.isEmpty()) 1.0 else strArgs.count { looksEncrypted(it) }.toDouble() / strArgs.size
            val obfName = Deobfuscator.isObfuscated(name) || Deobfuscator.isObfuscated(owner.removeSuffix(";").substringAfterLast('/'))
            if (list.size >= 2 && (weird >= 0.5 || (strArgs.isEmpty() && obfName && list.size >= 3)) || (list.size == 1 && weird == 1.0 && obfName))
                Decryptor(owner, name, "(" + target.substringAfter('('), list) else null
        }.sortedByDescending { it.sites.size }
    }

    /** Valor constante de cada registro justo antes de la llamada (o null si no es constante). */
    private fun resolveConstants(lines: List<String>, at: Int, methodStart: Int, regs: List<String>, params: List<String>): List<Any?>? {
        val out = ArrayList<Any?>()
        var k = 0
        for (t in params) {
            val reg = regs.getOrNull(k) ?: return null
            k += if (t == "J") 2 else 1
            var value: Any? = null; var found = false
            for (j in at - 1 downTo maxOf(methodStart, at - 40)) {
                val l = lines[j].trim()
                if (l.startsWith(":") || l.startsWith(".method")) break
                Regex("""^const-string(?:/jumbo)? ${Regex.escape(reg)}, "(.*)"$""").find(l)?.let { value = SmaliVM.unescape(it.groupValues[1]); found = true }
                    ?: Regex("""^const(?:/4|/16|/high16)? ${Regex.escape(reg)}, (-?0x[0-9a-fA-F]+|-?\d+)$""").find(l)?.let { m ->
                        val s = m.groupValues[1]; val neg = s.startsWith("-"); val v = s.removePrefix("-")
                        val n = if (v.startsWith("0x")) java.lang.Long.parseUnsignedLong(v.drop(2), 16) else v.toLong()
                        value = (if (neg) -n else n).toInt(); found = true
                    }
                    ?: Regex("""^const-wide(?:/16|/32|/high16)? ${Regex.escape(reg)}, (-?0x[0-9a-fA-F]+L?|-?\d+L?)$""").find(l)?.let { m ->
                        val s = m.groupValues[1].removeSuffix("L"); val neg = s.startsWith("-"); val v = s.removePrefix("-")
                        val n = if (v.startsWith("0x")) java.lang.Long.parseUnsignedLong(v.drop(2), 16) else v.toLong()
                        value = if (neg) -n else n; found = true
                    }
                if (found) break
                // cualquier otra escritura en el registro → no es constante
                if (Regex("""^[a-z/\-]+ ${Regex.escape(reg)}(,|$)""").containsMatchIn(l) && !l.startsWith("invoke") && !l.startsWith("if-") && !l.startsWith("aput") && !l.startsWith("iput") && !l.startsWith("sput")) return null
            }
            if (!found) return null
            out += value
        }
        return out
    }

    fun looksEncrypted(s: String): Boolean {
        if (s.length < 4) return s.any { it.code < 32 || it.code > 126 }
        val nonPrint = s.count { it.code < 32 || (it.code in 127..159) }
        if (nonPrint > 0) return true
        val words = s.split(' ').size
        val letters = s.count { it.isLetter() }
        val upperLower = s.count { it.isUpperCase() } > 2 && s.count { it.isLowerCase() } > 2
        val digits = s.count { it.isDigit() }
        val base64ish = Regex("^[A-Za-z0-9+/=_-]{8,}$").matches(s) && upperLower && digits > 0
        val vowels = s.count { it.lowercaseChar() in "aeiou" }
        return base64ish || (words == 1 && s.length >= 10 && letters > 0 && vowels.toDouble() / letters < 0.2) ||
            (s.any { it.code > 0x2000 } && s.count { it.code > 0x2000 } > s.length / 3)
    }

    /**
     * ¿Parece texto real? (URL, palabras, identificadores…) — estricto para no aceptar basura:
     * pocos símbolos raros, palabras con mayúsculas coherentes y proporción normal de vocales.
     */
    fun readable(s: String?): Boolean {
        if (s.isNullOrEmpty() || s.length > 4000) return false
        if (s.any { it.code < 32 && it != '\n' && it != '\t' } || s.any { it.code in 127..159 || it == '\uFFFD' }) return false
        val isUrl = Regex("^(https?|ftp|wss?)://[\\w.-]+(:\\d+)?(/[\\w./%?=&#~+:@!,;-]*)?$").matches(s)
        if (isUrl) return true
        val weirdSym = s.count { it in "\\<>`^|{}~" }
        if (weirdSym > 0 && s.length < 40) return false
        val symbols = s.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        if (symbols.toDouble() / s.length > 0.25) return false
        val words = Regex("\\p{L}+").findAll(s).map { it.value }.toList()
        if (words.isEmpty()) return s.all { it.isDigit() || it in ".,:-+ " } && s.isNotBlank()
        val coherent = words.count { w -> w.all { it.isLowerCase() } || w.all { it.isUpperCase() } || (w[0].isUpperCase() && w.drop(1).all { it.isLowerCase() }) ||
            Regex("^[a-z]+([A-Z][a-z]+){1,3}$").matches(w) || Regex("^([A-Z][a-z]+){2,4}$").matches(w) || w.length <= 2 }
        if (coherent.toDouble() / words.size < 0.85) return false
        val letters = words.joinToString("")
        val vowels = letters.count { it.lowercaseChar() in "aeiouáéíóúü" }
        val ratio = vowels.toDouble() / letters.length
        return letters.length < 4 || ratio in 0.2..0.75
    }

    // ───────────── 2) descifrar ─────────────

    fun run(project: File, ai: AiTextClient? = null, listener: ProgressListener = ProgressListener.NONE, maxSites: Int = 4000): Report {
        listener.onProgress("🔐 Buscando métodos que descifran textos…")
        val decs = findDecryptors(project)
        if (decs.isEmpty()) { if (!file(project).isFile) save(project, emptyList()); listener.onProgress("No se encontraron textos cifrados con un método descifrador"); return Report(0, 0, emptyList()) }
        listener.onProgress("🔐 ${decs.size} descifrador(es) con ${decs.sumOf { it.sites.size }} textos")
        val results = LinkedHashMap<String, Decrypted>()
        load(project).forEach { results["${it.file}:${it.line}"] = it }
        val pending = ArrayList<Decryptor>()
        decs.forEachIndexed { di, d ->
            listener.onPercent(di * 100 / decs.size)
            val sites = d.sites.take(maxSites)
            // a) ejecutar el método aislado
            val vm = SmaliVM(project)
            var ok = 0; var firstError: String? = null
            val viaVm = sites.mapNotNull { s ->
                runCatching { vm.callStatic(d.cls, d.name, d.desc, s.args) as? String }
                    .onFailure { if (firstError == null) firstError = it.message }.getOrNull()
                    ?.takeIf { readable(it) }?.also { ok++ }?.let { s to it }
            }
            if (ok >= maxOf(1, sites.size * 6 / 10)) {
                viaVm.forEach { (s, out) -> results["${s.file}:${s.line}"] = Decrypted(s.file, s.line, s.inMethod, d.id, s.args.joinToString(", ") { a -> a.toString() }, out, "ejecutado (intérprete aislado)") }
                listener.onProgress("  ✔ ${d.label}: $ok textos ejecutando el método")
                return@forEachIndexed
            }
            // b) cifrados típicos (solo con un parámetro String)
            if (d.desc.startsWith("(Ljava/lang/String;)")) {
                val keys = classStrings(project, d.cls)
                val recipe = guessRecipe(sites.mapNotNull { it.args.firstOrNull() as? String }, keys)
                if (recipe != null) {
                    sites.forEach { s -> apply(recipe, s.args[0] as String)?.takeIf { readable(it) }?.let { out ->
                        results["${s.file}:${s.line}"] = Decrypted(s.file, s.line, s.inMethod, d.id, s.args[0] as String, out, "cifrado típico: ${describe(recipe)}") } }
                    listener.onProgress("  ✔ ${d.label}: ${describe(recipe)}")
                    return@forEachIndexed
                }
            }
            // c) la IA deduce el algoritmo
            if (ai != null && d.desc.startsWith("(Ljava/lang/String;)")) {
                listener.onProgress("  🤖 La IA estudia ${d.label}…")
                val recipe = aiRecipe(project, d, ai, firstError)
                if (recipe != null) {
                    sites.forEach { s -> apply(recipe, s.args[0] as String)?.takeIf { readable(it) }?.let { out ->
                        results["${s.file}:${s.line}"] = Decrypted(s.file, s.line, s.inMethod, d.id, s.args[0] as String, out, "IA: ${describe(recipe)}") } }
                    listener.onProgress("  ✔ La IA dedujo: ${describe(recipe)}")
                    return@forEachIndexed
                }
            }
            pending += d
            listener.onProgress("  ✖ ${d.label}: no se pudo (${firstError?.take(80) ?: "algoritmo desconocido"})")
        }
        save(project, results.values.toList())
        listener.onPercent(100)
        return Report(decs.size, results.size, pending.map { it.id })
    }

    /** Textos constantes de la clase del descifrador (posibles claves). */
    private fun classStrings(project: File, cls: String): List<String> = runCatching {
        val f = File(project, "smali").walkTopDown().first { it.isFile && it.path.endsWith(cls.removePrefix("L").removeSuffix(";") + ".smali") }
        Regex("""const-string(?:/jumbo)? [vp]\d+, "([^"]{1,64})"""").findAll(f.readText()).map { SmaliVM.unescape(it.groupValues[1]) }.distinct().take(20).toList()
    }.getOrDefault(emptyList())

    // ───────────── recetas (pasos de descifrado) ─────────────

    data class Step(val op: String, val key: String? = null, val n: Int = 0, val mode: String? = null, val iv: String? = null)

    private fun bytesOf(k: String): ByteArray = when {
        k.startsWith("hex:") -> CryptoTools.hex(k.drop(4)) ?: k.toByteArray()
        k.startsWith("b64:") -> CryptoTools.base64(k.drop(4)) ?: k.toByteArray()
        else -> k.toByteArray()
    }

    fun apply(recipe: List<Step>, input: String): String? = runCatching {
        var b: ByteArray = input.toByteArray(Charsets.UTF_8)
        var asText: String? = input
        for (s in recipe) {
            when (s.op) {
                "base64" -> { b = CryptoTools.base64(asText ?: String(b, Charsets.ISO_8859_1)) ?: return null; asText = null }
                "hex" -> { b = CryptoTools.hex(asText ?: String(b)) ?: return null; asText = null }
                "xor" -> { b = CryptoTools.xor(b, if (s.key != null) bytesOf(s.key) else byteArrayOf(s.n.toByte())); asText = null }
                "xorchars" -> { val t = asText ?: String(b, Charsets.UTF_8); val k = s.key ?: ""; asText = String(CharArray(t.length) { i -> (t[i].code xor (if (k.isEmpty()) s.n else k[i % k.length].code)).toChar() }); b = asText.toByteArray() }
                "reverse" -> { asText = (asText ?: String(b, Charsets.UTF_8)).reversed(); b = asText.toByteArray() }
                "rot" -> { asText = (asText ?: String(b)).map { c -> when (c) { in 'a'..'z' -> 'a' + (c - 'a' + s.n).mod(26); in 'A'..'Z' -> 'A' + (c - 'A' + s.n).mod(26); else -> c } }.joinToString(""); b = asText.toByteArray() }
                "add" -> { asText = (asText ?: String(b)).map { (it.code + s.n).toChar() }.joinToString(""); b = asText.toByteArray() }
                "aes" -> { b = CryptoTools.aes(b, bytesOf(s.key ?: return null), s.iv?.let { bytesOf(it) }, mode = s.mode ?: "CBC", decrypt = true); asText = null }
                "rc4" -> { b = CryptoTools.rc4(b, bytesOf(s.key ?: return null)); asText = null }
                "utf8" -> { asText = String(b, Charsets.UTF_8) }
                else -> return null
            }
        }
        asText ?: String(b, Charsets.UTF_8)
    }.getOrNull()

    fun describe(r: List<Step>) = r.joinToString(" → ") { s -> s.op + (s.key?.let { "(\"${it.take(24)}\")" } ?: if (s.n != 0) "(${s.n})" else "") + (s.mode?.let { " $it" } ?: "") }

    /** Prueba cifrados típicos con unas muestras y devuelve la receta que deja casi todo legible. */
    fun guessRecipe(samples: List<String>, keys: List<String>): List<Step>? {
        val sample = samples.take(12)
        if (sample.isEmpty()) return null
        fun good(r: List<Step>) = sample.count { readable(apply(r, it)) } >= maxOf(1, (sample.size * 8 + 9) / 10)
        val cands = ArrayList<List<Step>>()
        cands += listOf(Step("base64")); cands += listOf(Step("reverse")); cands += listOf(Step("rot", n = 13)); cands += listOf(Step("hex"))
        cands += listOf(Step("reverse"), Step("base64")); cands += listOf(Step("base64"), Step("reverse"))
        for (n in -10..10) if (n != 0) cands += listOf(Step("add", n = n))
        for (n in 1..255) { cands += listOf(Step("base64"), Step("xor", n = n)); cands += listOf(Step("xorchars", n = n)) }
        keys.filter { it.length in 1..64 }.forEach { k ->
            cands += listOf(Step("base64"), Step("xor", key = k)); cands += listOf(Step("xorchars", key = k)); cands += listOf(Step("hex"), Step("xor", key = k))
            if (k.length in listOf(16, 24, 32)) { cands += listOf(Step("base64"), Step("aes", key = k, mode = "ECB")); keys.filter { it.length == 16 }.forEach { iv -> cands += listOf(Step("base64"), Step("aes", key = k, mode = "CBC", iv = iv)) } }
            cands += listOf(Step("base64"), Step("rc4", key = k))
        }
        return cands.firstOrNull { good(it) }
    }

    /** La IA lee el descifrador y propone la receta; la app la verifica con todas las muestras. */
    fun aiRecipe(project: File, d: Decryptor, ai: AiTextClient, vmError: String?): List<Step>? {
        val f = File(project, "smali").walkTopDown().firstOrNull { it.isFile && it.path.endsWith(d.cls.removePrefix("L").removeSuffix(";") + ".smali") } ?: return null
        val text = f.readText()
        val method = text.substringAfter(".method", "").let { _ ->
            Regex("""\.method[^\n]*\b${Regex.escape(d.name)}\(${Regex.escape(d.desc.substringAfter('(').substringBefore(')'))}\)[\s\S]*?\.end method""").find(text)?.value ?: ""
        }.take(9000)
        val clinit = Regex("""\.method static constructor <clinit>\(\)V[\s\S]*?\.end method""").find(text)?.value?.take(3000).orEmpty()
        val samples = d.sites.mapNotNull { it.args.firstOrNull() as? String }.distinct().take(6)
        var feedback = ""
        repeat(2) {
            val answer = runCatching {
                ai.complete(
                    "Eres experto en ingeniería inversa de Android. Te doy un método smali que DESCIFRA textos y ejemplos de entradas. " +
                        "Deduce el algoritmo y responde SOLO un JSON {\"steps\":[...]} con pasos de esta lista (en orden): " +
                        "{\"op\":\"base64\"}, {\"op\":\"hex\"}, {\"op\":\"reverse\"}, {\"op\":\"rot\",\"n\":13}, {\"op\":\"add\",\"n\":-3} (suma n a cada carácter), " +
                        "{\"op\":\"xor\",\"key\":\"clave\"} (sobre bytes; clave en texto, o \"hex:..\" / \"b64:..\"), {\"op\":\"xor\",\"n\":42} (un byte), " +
                        "{\"op\":\"xorchars\",\"key\":\"clave\"} (XOR carácter a carácter sobre el texto), {\"op\":\"aes\",\"key\":\"..\",\"mode\":\"ECB|CBC|GCM\",\"iv\":\"..\"}, {\"op\":\"rc4\",\"key\":\"..\"}. " +
                        "Saca las claves de las constantes del código.",
                    "MÉTODO:\n$method\n\n<clinit> de la clase:\n$clinit\n\nENTRADAS DE EJEMPLO:\n" + samples.joinToString("\n") { "\"$it\"" } +
                        (vmError?.let { "\n\n(La app intentó ejecutarlo y se detuvo en: $it)" } ?: "") + feedback,
                )
            }.getOrNull() ?: return null
            val recipe = runCatching {
                val json = JsonParser.parseString(answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1)).asJsonObject
                json.getAsJsonArray("steps").map { e -> val o = e.asJsonObject
                    Step(o["op"].asString, o["key"]?.asString, o["n"]?.asInt ?: 0, o["mode"]?.asString, o["iv"]?.asString) }
            }.getOrNull()
            if (recipe != null && samples.count { readable(apply(recipe, it)) } >= maxOf(1, samples.size * 2 / 3)) return recipe
            feedback = "\n\nTu receta anterior ${recipe?.let { describe(it) } ?: "(inválida)"} NO dio texto legible: " +
                samples.take(2).joinToString { "\"${recipe?.let { r -> apply(r, it) }?.take(40)}\"" } + ". Revisa claves y orden."
        }
        return null
    }

    /** La IA (desde el chat) propone una receta para un descifrador; la app la verifica y aplica. */
    fun applyRecipeFromAi(project: File, decryptorId: String, stepsJson: String): String {
        val d = findDecryptors(project).firstOrNull { it.id == decryptorId || it.id.endsWith(decryptorId) } ?: return "No encuentro ese descifrador"
        val recipe = runCatching {
            JsonParser.parseString(stepsJson.substring(stepsJson.indexOf('['), stepsJson.lastIndexOf(']') + 1)).asJsonArray.map { e -> val o = e.asJsonObject
                Step(o["op"].asString, o["key"]?.asString, o["n"]?.asInt ?: 0, o["mode"]?.asString, o["iv"]?.asString) }
        }.getOrElse { return "Receta inválida: ${it.message}" }
        val out = d.sites.mapNotNull { s -> (s.args.firstOrNull() as? String)?.let { inp -> apply(recipe, inp)?.takeIf { readable(it) }?.let { s to (inp to it) } } }
        if (out.size < maxOf(1, d.sites.size / 2)) return "La receta solo dejó legibles ${out.size} de ${d.sites.size}. Ejemplos: " +
            d.sites.take(3).joinToString { s -> "\"${(s.args.firstOrNull() as? String)?.let { apply(recipe, it) }?.take(40)}\"" }
        val all = load(project).associateBy { "${it.file}:${it.line}" }.toMutableMap()
        out.forEach { (s, io) -> all["${s.file}:${s.line}"] = Decrypted(s.file, s.line, s.inMethod, d.id, io.first, io.second, "IA (chat): ${describe(recipe)}") }
        save(project, all.values.toList())
        return "✔ Descifrados ${out.size} textos. Ejemplos:\n" + out.take(8).joinToString("\n") { "  ${it.first.file.substringAfterLast('/')}:${it.first.line} → \"${it.second.second.take(80)}\"" }
    }

    // ───────────── 3) usar los resultados ─────────────

    /** Para la IA: textos descifrados de los archivos relacionados con el pedido (o los más útiles). */
    fun contextFor(project: File, paths: List<String>): String {
        val all = load(project)
        if (all.isEmpty()) return ""
        val set = paths.toSet()
        val pick = (all.filter { it.file in set } + all.filter { it.output.contains("http") || it.output.contains("key", true) }).distinct().take(25)
        return if (pick.isEmpty()) "" else "TEXTOS DESCIFRADOS POR LA APP (${all.size} en total):\n" +
            pick.joinToString("\n") { "  ${it.file.substringAfterLast('/')}:${it.line} → \"${it.output.take(120)}\"" }
    }

    /** Comentarios para el Java (jadx): en cada método, sus textos descifrados. */
    fun jadxComments(project: File): List<jadx.api.data.ICodeComment> = load(project).groupBy { it.file to it.inMethod }.mapNotNull { (k, list) ->
        val (file, method) = k
        val cls = file.substringAfter("smali/").substringAfter('/').removeSuffix(".smali").replace('/', '.')
        runCatching {
            jadx.api.data.impl.JadxCodeComment(
                jadx.api.data.impl.JadxNodeRef(jadx.api.data.IJavaNodeRef.RefType.METHOD, cls, method),
                "Textos descifrados por ApkForge:\n" + list.sortedBy { it.line }.take(40).joinToString("\n") { "  \"${it.output.take(150)}\"" },
            )
        }.getOrNull()
    }
}
