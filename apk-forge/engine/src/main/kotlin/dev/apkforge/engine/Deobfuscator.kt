package dev.apkforge.engine

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File

/**
 * DESOFUSCADOR: la app y la IA trabajan juntas para recuperar nombres reales.
 *
 *  1. La APP lee las pistas que la ofuscación no borra (en segundos, sin internet):
 *     - toString() de Kotlin/data classes: "User(id=…, name=…)" → clase User, campos id y name;
 *     - etiquetas de log (TAG), archivo de origen (.source "Foo.kt");
 *     - qué layout infla cada clase y qué es (Activity, Fragment, Adapter, ViewHolder…):
 *       activity_main → MainActivity, item_channel en un Adapter → ChannelAdapter;
 *     - findViewById(R.id.channel_name) guardado en un campo → campo channelName;
 *     - getters/setters, tipos de los campos (un campo de tipo ChannelAdapter → channelAdapter), URLs.
 *  2. La IA recibe solo lo que la app NO pudo resolver, ya resumido (cadenas, APIs, superclase, lo
 *     deducido), y propone nombres; también puede renombrar desde el chat con rename_symbol.
 *  3. Los nombres se aplican en el Java legible (jadx), en la búsqueda por significado y en el
 *     contexto que recibe la IA. El smali no se toca, así que exportar sigue siendo seguro.
 */
object Deobfuscator {

    data class Rename(
        val kind: String,          // class | method | field
        val cls: String,           // clase propietaria (descriptor smali, p. ej. "Lcom/a/b;")
        val id: String,            // class: ""; method: "a(I)V"; field: "a:Ljava/lang/String;"
        val obf: String,           // nombre ofuscado
        val name: String,          // nombre propuesto
        val conf: Float,           // confianza 0..1
        val evidence: String,      // por qué
        val source: String,        // App | IA | Tú
    ) { val key get() = "$kind|$cls|$id" }

    data class Report(val classes: Int, val obfuscated: Int, val renamed: Int, val methods: Int, val fields: Int)

    private val gson = Gson()
    fun mappingFile(project: File) = File(project, "$META_DIR/deobf/mapping.json")

    fun load(project: File): List<Rename> = runCatching {
        gson.fromJson(mappingFile(project).readText(), Array<Rename>::class.java).toList()
    }.getOrDefault(emptyList())

    fun save(project: File, list: List<Rename>) {
        mappingFile(project).apply { parentFile?.mkdirs(); writeText(gson.toJson(list.sortedBy { it.key })) }
        cache.remove(project.absolutePath)
    }

    private val cache = HashMap<String, Pair<Long, Map<String, Rename>>>()
    /** Mapa clase (descriptor) → nombre propuesto (con caché). */
    fun classNames(project: File): Map<String, Rename> {
        val f = mappingFile(project)
        cache[project.absolutePath]?.takeIf { it.first == f.lastModified() }?.let { return it.second }
        val m = load(project).filter { it.kind == "class" }.associateBy { it.cls }
        cache[project.absolutePath] = f.lastModified() to m
        return m
    }

    /** ¿Parece un nombre ofuscado? (a, bx, Il1, a0, C0123…) */
    fun isObfuscated(simple: String): Boolean {
        val n = simple.substringAfterLast('$')
        return n.length <= 2 || (n.length <= 3 && n.all { it.isLowerCase() || it.isDigit() }) || n.all { it in "Il1|_" } ||
            Regex("^[a-z]{1,3}\\d{0,3}$").matches(n) || Regex("^[A-Z]\\d{3,}[a-z]?$").matches(n)
    }

    // ───────────── lectura ligera de smali ─────────────

    class SClass(val desc: String, val sup: String, val ifaces: List<String>, val source: String?, val fields: List<Pair<String, String>>,
                 val methods: List<SMethod>, val text: String) {
        val simple get() = desc.removePrefix("L").removeSuffix(";").substringAfterLast('/')
        val pkgPath get() = desc.removePrefix("L").removeSuffix(";").substringBeforeLast('/', "")
    }
    class SMethod(val name: String, val proto: String, val body: List<String>) { val id get() = "$name$proto" }

    fun parse(text: String): SClass? {
        val lines = text.lines()
        val desc = lines.firstOrNull { it.startsWith(".class") }?.substringAfterLast(' ') ?: return null
        val sup = lines.firstOrNull { it.startsWith(".super") }?.substringAfterLast(' ') ?: ""
        val ifaces = lines.filter { it.startsWith(".implements") }.map { it.substringAfterLast(' ') }
        val source = lines.firstOrNull { it.startsWith(".source") }?.substringAfter('"')?.substringBefore('"')
        val fields = lines.filter { it.startsWith(".field") }.mapNotNull { l ->
            val decl = l.removePrefix(".field").trim().split(' ').last { it.contains(':') }.substringBefore(" =")
            decl.substringBefore(':') to decl.substringAfter(':')
        }
        val methods = ArrayList<SMethod>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith(".method")) {
                val sig = l.trim().split(' ').last()
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].startsWith(".end method")) { lines[i].trim().takeIf { it.isNotEmpty() && !it.startsWith(".line") && !it.startsWith(".param") && !it.startsWith(".prologue") }?.let { body += it }; i++ }
                methods += SMethod(sig.substringBefore('('), "(" + sig.substringAfter('('), body)
            }
            i++
        }
        return SClass(desc, sup, ifaces, source, fields, methods, text)
    }

    // ───────────── 1) la app deduce ─────────────

    /** Analiza el proyecto y guarda las deducciones (respeta lo que pusiste tú o la IA con más confianza). */
    fun analyze(project: File, listener: ProgressListener = ProgressListener.NONE): Report {
        listener.onProgress("🔍 Desofuscando: leyendo pistas del código…")
        val files = candidateFiles(project).toList()
        val classes = files.mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
        val byDesc = classes.associateBy { it.desc }
        val ids = resourceIds(project)
        val idToLayout = viewIdLayouts(project)
        val out = HashMap<String, Rename>()
        fun put(r: Rename) { val old = out[r.key]; if (old == null || r.conf > old.conf) out[r.key] = r }

        classes.forEachIndexed { idx, c ->
            if (idx % 200 == 0) listener.onPercent(idx * 70 / maxOf(1, classes.size))
            val obfCls = isObfuscated(c.simple)
            // toString de data class: "Name(field=" … ", other="
            c.methods.firstOrNull { it.name == "toString" && it.proto == "()Ljava/lang/String;" }?.let { m ->
                var lastField: Pair<String, String>? = null
                m.body.forEach { l ->
                    Regex("""const-string(?:/jumbo)? [vp]\d+, "(\w+)\((\w+)="""").find(l)?.let { r ->
                        if (obfCls) put(Rename("class", c.desc, "", c.simple, r.groupValues[1], 0.95f, "toString() devuelve \"${r.groupValues[1]}(…)\"", "App"))
                        lastField = null; pendingName = r.groupValues[2]
                    }
                    Regex("""const-string(?:/jumbo)? [vp]\d+, ", (\w+)="""").find(l)?.let { r -> pendingName = r.groupValues[1] }
                    Regex("""iget[\w-]* [vp]\d+, p0, ${Regex.escape(c.desc)}->(\w+):(\S+)""").find(l)?.let { r ->
                        val fname = r.groupValues[1]
                        pendingName?.let { pn -> if (isObfuscated(fname) && pn != fname) put(Rename("field", c.desc, "$fname:${r.groupValues[2]}", fname, pn, 0.95f, "aparece en toString() como \"$pn=\"", "App")) }
                        pendingName = null; lastField = fname to r.groupValues[2]
                    }
                }
                pendingName = null
            }
            if (!obfCls) return@forEachIndexed
            // archivo de origen
            c.source?.takeIf { it != "SourceFile" && it.contains('.') && !c.desc.contains('$') }?.let { src ->
                val n = src.substringBefore('.')
                if (n.length > 2 && n[0].isUpperCase()) put(Rename("class", c.desc, "", c.simple, n, 0.9f, ".source \"$src\"", "App"))
            }
            // etiqueta de log
            Regex("""const-string(?:/jumbo)? ([vp]\d+), "([A-Z][A-Za-z0-9]{3,40})"\s*\n(?:.*\n){0,6}?.*Landroid/util/Log;->[dewiv]\(""").find(c.text)?.let { r ->
                put(Rename("class", c.desc, "", c.simple, r.groupValues[2], 0.8f, "etiqueta de log \"${r.groupValues[2]}\"", "App"))
            }
            // rol + layout que infla
            val role = role(c, byDesc)
            val usedRes = Regex("""const(?:/high16)?\s+[vp]\d+,\s+(0x7f[0-9a-fA-F]{6})""").findAll(c.text).mapNotNull { ids[it.groupValues[1].lowercase()] }.toList()
            // layouts que infla, o si no infla ninguno, el layout donde están las vistas que usa (ViewHolder…)
            val layouts = usedRes.filter { it.first == "layout" }.map { it.second }.distinct().ifEmpty {
                usedRes.filter { it.first == "id" }.mapNotNull { idToLayout[it.second] }.groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }.map { it.key }
            }
            val fromLayout = layouts.firstNotNullOfOrNull { lay -> nameFromLayout(lay, role) }
            when {
                fromLayout != null -> put(Rename("class", c.desc, "", c.simple, fromLayout, 0.75f,
                    if (usedRes.any { it.first == "layout" }) "${role ?: "clase"} que infla el layout ${layouts.first()}"
                    else "${role ?: "clase"} que usa las vistas del layout ${layouts.first()}", "App"))
                role != null -> put(Rename("class", c.desc, "", c.simple, role + "_" + c.simple.substringAfterLast('$'), 0.4f, "extiende/implementa $role", "App"))
            }
            // URLs: API de red
            if (c.text.contains("Lokhttp3/") || c.text.contains("Lretrofit2/") || c.text.contains("Ljava/net/HttpURLConnection;")) {
                Regex("""const-string(?:/jumbo)? [vp]\d+, "(?:https?://[^/"]+)?/([a-zA-Z][\w-]{2,30})""").find(c.text)?.let { r ->
                    put(Rename("class", c.desc, "", c.simple, camel(r.groupValues[1], true) + "Api", 0.5f, "hace peticiones HTTP a …/${r.groupValues[1]}", "App"))
                }
            }
        }
        listener.onPercent(75)
        val clsName = { d: String -> out["class|$d|"]?.name }

        // campos: findViewById → id, tipo de clase renombrada o tipo de Android conocido
        classes.forEach { c ->
            c.methods.forEach { m ->
                for (k in m.body.indices) {
                    val l = m.body[k]
                    if (!l.contains("->findViewById(I)") && !l.contains("->requireViewById(I)")) continue
                    val idHex = (k - 1 downTo maxOf(0, k - 4)).firstNotNullOfOrNull { j -> Regex("""const(?:/high16)?\s+[vp]\d+,\s+(0x7f[0-9a-fA-F]{6})""").find(m.body[j])?.groupValues?.get(1) }
                    val idName = idHex?.let { ids[it.lowercase()] }?.takeIf { it.first == "id" }?.second ?: continue
                    (k + 1..minOf(m.body.size - 1, k + 5)).firstNotNullOfOrNull { j -> Regex("""iput-object [vp]\d+, p0, ${Regex.escape(c.desc)}->(\w+):(\S+)""").find(m.body[j]) }?.let { r ->
                        val f = r.groupValues[1]
                        if (isObfuscated(f)) put(Rename("field", c.desc, "$f:${r.groupValues[2]}", f, camel(idName, false), 0.85f, "guarda findViewById(R.id.$idName)", "App"))
                    }
                }
            }
            val used = HashSet<String>()
            c.fields.forEach { (f, type) ->
                if (!isObfuscated(f) || out.containsKey("field|${c.desc}|$f:$type")) return@forEach
                val base = clsName(type)?.let { decap(it) } ?: KNOWN_TYPES[type] ?: return@forEach
                var n = base; var i = 2
                while (!used.add(n)) n = base + i++
                put(Rename("field", c.desc, "$f:$type", f, n, if (clsName(type) != null) 0.6f else 0.5f, "campo de tipo ${type.substringAfterLast('/').removeSuffix(";")}", "App"))
            }
        }
        listener.onPercent(85)
        // métodos: getters / setters
        classes.forEach { c ->
            c.methods.forEach { m ->
                if (!isObfuscated(m.name) || m.name.startsWith("<")) return@forEach
                val code = m.body.filter { !it.startsWith(".") && !it.startsWith(":") }
                fun fieldLabel(f: String, t: String) = out["field|${c.desc}|$f:$t"]?.name ?: if (!isObfuscated(f)) f else null
                if (code.size == 2 && code[1].startsWith("return")) Regex("""iget[\w-]* \S+ p0, ${Regex.escape(c.desc)}->(\w+):(\S+)""").find(code[0])?.let { r ->
                    fieldLabel(r.groupValues[1], r.groupValues[2])?.let { put(Rename("method", c.desc, m.id, m.name, "get" + cap(it), 0.7f, "devuelve el campo $it", "App")) }
                }
                if (code.size == 2 && code[1] == "return-void") Regex("""iput[\w-]* p1, p0, ${Regex.escape(c.desc)}->(\w+):(\S+)""").find(code[0])?.let { r ->
                    fieldLabel(r.groupValues[1], r.groupValues[2])?.let { put(Rename("method", c.desc, m.id, m.name, "set" + cap(it), 0.7f, "asigna el campo $it", "App")) }
                }
            }
        }
        val merged = merge(load(project), out.values.toList())
        save(project, uniqueClassNames(merged))
        listener.onPercent(100)
        val obf = classes.count { isObfuscated(it.simple) }
        val m = load(project)
        return Report(classes.size, obf, m.count { it.kind == "class" }, m.count { it.kind == "method" }, m.count { it.kind == "field" })
            .also { listener.onProgress("🔍 ${it.obfuscated} de ${it.classes} clases ofuscadas · nombres: ${it.renamed} clases, ${it.methods} métodos, ${it.fields} campos") }
    }

    private var pendingName: String? = null

    /** Todo el código menos librerías conocidas (lo ofuscado suele quedar en paquetes cortos: a/b/c). */
    private fun candidateFiles(project: File) = File(project, "smali").walkTopDown()
        .filter { it.isFile && it.extension == "smali" && ProjectReader.LIB.none { l -> it.path.contains(l) } }

    private val KNOWN_TYPES = mapOf(
        "Landroidx/recyclerview/widget/RecyclerView;" to "recyclerView", "Landroid/webkit/WebView;" to "webView",
        "Landroid/content/SharedPreferences;" to "prefs", "Lokhttp3/OkHttpClient;" to "httpClient", "Landroid/os/Handler;" to "handler",
        "Landroid/content/Context;" to "context", "Landroid/widget/TextView;" to "textView", "Landroid/widget/ImageView;" to "imageView",
        "Landroid/widget/Button;" to "button", "Landroid/widget/EditText;" to "editText", "Landroid/widget/ProgressBar;" to "progressBar",
        "Landroid/app/Activity;" to "activity", "Landroid/view/View;" to "view", "Ljava/util/List;" to "list", "Ljava/util/ArrayList;" to "list",
        "Landroid/media/MediaPlayer;" to "mediaPlayer", "Lcom/google/android/exoplayer2/ExoPlayer;" to "player", "Landroidx/media3/exoplayer/ExoPlayer;" to "player",
        "Lretrofit2/Retrofit;" to "retrofit", "Lcom/google/gson/Gson;" to "gson", "Landroid/database/sqlite/SQLiteDatabase;" to "database",
    )

    private fun role(c: SClass, all: Map<String, SClass>): String? {
        var sup = c.sup; var depth = 0
        while (depth++ < 6) {
            val s = sup
            when {
                s.contains("ViewHolder") -> return "ViewHolder"
                s.contains("Adapter") -> return "Adapter"
                s.endsWith("Activity;") -> return "Activity"
                s.endsWith("Fragment;") -> return if (s.contains("DialogFragment")) "Dialog" else "Fragment"
                s.endsWith("Dialog;") -> return "Dialog"
                s.endsWith("Service;") -> return "Service"
                s.endsWith("BroadcastReceiver;") -> return "Receiver"
                s.endsWith("Application;") -> return "App"
                s.endsWith("ViewModel;") -> return "ViewModel"
                s.endsWith("Exception;") -> return "Exception"
                s.endsWith("ContentProvider;") -> return "Provider"
                s.endsWith("View;") || s.endsWith("Layout;") -> return "View"
            }
            sup = all[s]?.sup ?: break
        }
        return when {
            c.ifaces.any { it.contains("OnClickListener") } -> "ClickListener"
            c.ifaces.any { it == "Ljava/lang/Runnable;" } -> "Task"
            c.ifaces.any { it.contains("Callback") } -> "Callback"
            else -> null
        }
    }

    private fun nameFromLayout(layout: String, role: String?): String? {
        val parts = layout.split('_')
        val kind = parts.first()
        val rest = parts.drop(1).ifEmpty { parts }.joinToString("") { cap(it) }
        return when {
            role == "Activity" && kind == "activity" -> rest + "Activity"
            role == "Fragment" && kind == "fragment" -> rest + "Fragment"
            role == "Dialog" && (kind == "dialog" || kind == "fragment") -> rest + "Dialog"
            role == "Adapter" && (kind == "item" || kind == "row" || kind == "list" || kind == "cell") -> rest + "Adapter"
            role == "ViewHolder" && (kind == "item" || kind == "row" || kind == "cell") -> rest + "ViewHolder"
            role == "View" -> rest + "View"
            role != null -> parts.joinToString("") { cap(it) } + role
            else -> null
        }
    }

    /** id de vista → layout donde se declara (el primero que lo define). */
    private fun viewIdLayouts(project: File): Map<String, String> {
        val m = HashMap<String, String>()
        File(project, "resources").listFiles().orEmpty().forEach { pkg ->
            File(pkg, "res").listFiles { d -> d.isDirectory && d.name.startsWith("layout") }.orEmpty().flatMap { it.listFiles().orEmpty().toList() }.forEach { f ->
                runCatching { Regex("""@\+id/(\w+)""").findAll(f.readText()).forEach { m.putIfAbsent(it.groupValues[1], f.nameWithoutExtension) } }
            }
        }
        return m
    }

    private fun resourceIds(project: File): Map<String, Pair<String, String>> {
        val m = HashMap<String, Pair<String, String>>()
        File(project, "resources").listFiles().orEmpty().forEach { pkg ->
            File(pkg, "res/values/public.xml").takeIf { it.isFile }?.readText()?.let { t ->
                Regex("""id="0x([0-9a-fA-F]{8})"\s+type="([^"]+)"\s+name="([^"]+)"""").findAll(t).forEach { m["0x" + it.groupValues[1].lowercase()] = it.groupValues[2] to it.groupValues[3] }
            }
        }
        return m
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    private fun decap(s: String) = s.replaceFirstChar { it.lowercase() }
    private fun camel(s: String, upper: Boolean): String {
        val p = s.split('_', '-', '.').filter { it.isNotEmpty() }
        val j = p.mapIndexed { i, w -> if (i == 0 && !upper) w.lowercase() else cap(w.lowercase()) }.joinToString("")
        return if (j.firstOrNull()?.isDigit() == true) "n$j" else j
    }

    /** Lo tuyo manda; si no, gana la mayor confianza. */
    fun merge(old: List<Rename>, new: List<Rename>): List<Rename> {
        val m = LinkedHashMap<String, Rename>()
        (old + new).forEach { r ->
            val o = m[r.key]
            m[r.key] = when {
                o == null -> r
                o.source == "Tú" -> o
                r.source == "Tú" -> r
                r.conf >= o.conf -> r
                else -> o
            }
        }
        return m.values.toList()
    }

    /** Dos clases del mismo paquete no pueden llamarse igual. */
    private fun uniqueClassNames(list: List<Rename>): List<Rename> {
        val seen = HashMap<String, Int>()
        return list.sortedByDescending { it.conf }.map { r ->
            if (r.kind != "class") return@map r
            val pkg = r.cls.substringBeforeLast('/')
            val k = "$pkg|${r.name}"
            val n = seen.merge(k, 1, Int::plus)!!
            if (n == 1) r else r.copy(name = r.name + n)
        }
    }

    // ───────────── 2) la IA completa lo que la app no resolvió ─────────────

    /** Resumen compacto de una clase para la IA (lo que la app ya sabe + pistas). */
    fun summary(project: File, c: SClass, known: Map<String, Rename>): String = buildString {
        val name = known["class|${c.desc}|"]
        append("CLASE ${c.desc}")
        name?.let { append("  (la app cree: ${it.name}, ${(it.conf * 100).toInt()}% — ${it.evidence})") }
        appendLine()
        appendLine("  hereda de ${c.sup}" + if (c.ifaces.isNotEmpty()) "; implementa ${c.ifaces.joinToString()}" else "")
        if (c.fields.isNotEmpty()) appendLine("  campos: " + c.fields.take(12).joinToString { (f, t) -> "$f:${t.substringAfterLast('/')}" + (known["field|${c.desc}|$f:$t"]?.let { " (${it.name})" } ?: "") })
        appendLine("  métodos: " + c.methods.take(15).joinToString { m -> m.name + m.proto.substringBefore(')') + ")" + (known["method|${c.desc}|${m.id}"]?.let { " (${it.name})" } ?: "") })
        val strings = Regex("""const-string(?:/jumbo)? [vp]\d+, "([^"]{2,80})"""").findAll(c.text).map { it.groupValues[1] }.distinct().take(10).toList()
        if (strings.isNotEmpty()) appendLine("  cadenas: " + strings.joinToString(" | "))
        val apis = Regex("""L(android|androidx|java|okhttp3|retrofit2|com/google)/[\w/$]+;->\w+""").findAll(c.text).map { it.value.substringAfterLast('/') }.distinct().take(12).toList()
        if (apis.isNotEmpty()) appendLine("  llama a: " + apis.joinToString())
    }

    /** Clases ofuscadas que más importan y la app no resolvió bien (para pedírselas a la IA). */
    fun pendingForAi(project: File, limit: Int = 24): List<SClass> {
        val known = load(project).associateBy { it.key }
        return candidateFiles(project).mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
            .filter { isObfuscated(it.simple) && (known["class|${it.desc}|"]?.conf ?: 0f) < 0.7f }
            .sortedByDescending { c -> c.text.length.coerceAtMost(20000) + Regex("const-string").findAll(c.text).count() * 300 }
            .take(limit).toList()
    }

    /** La IA propone nombres para lo pendiente, por lotes. Devuelve cuántos nombres añadió. */
    fun refineWithAi(project: File, client: AiTextClient, listener: ProgressListener = ProgressListener.NONE, limit: Int = 24): Int {
        val pending = pendingForAi(project, limit)
        if (pending.isEmpty()) { listener.onProgress("No quedan clases ofuscadas importantes sin nombre"); return 0 }
        var added = 0
        pending.chunked(6).forEachIndexed { i, batch ->
            listener.onProgress("🤖 La IA analiza ${batch.size} clase(s) ofuscada(s) (${i * 6 + batch.size}/${pending.size})…")
            listener.onPercent((i * 6 * 100 / pending.size))
            val known = load(project).associateBy { it.key }
            val answer = runCatching {
                client.complete(
                    "Eres experto en ingeniería inversa de Android. La app te da un resumen de clases OFUSCADAS (lo que ya dedujo, cadenas, " +
                        "APIs que llaman). Propón nombres reales en estilo Java (CamelCase para clases, camelCase para métodos y campos). " +
                        "Si no hay pistas suficientes, no inventes: omítelo. Responde SOLO un JSON: " +
                        "{\"classes\":[{\"class\":\"Lcom/a/b;\",\"name\":\"ChannelAdapter\",\"why\":\"...\"}]," +
                        "\"methods\":[{\"class\":\"Lcom/a/b;\",\"method\":\"a(I)V\",\"name\":\"loadChannels\",\"why\":\"...\"}]," +
                        "\"fields\":[{\"class\":\"Lcom/a/b;\",\"field\":\"a:Ljava/lang/String;\",\"name\":\"token\",\"why\":\"...\"}]}",
                    batch.joinToString("\n") { summary(project, it, known) } +
                        "\n(Para métodos usa el formato nombre(params)retorno exacto del smali; para campos nombre:tipo.)",
                )
            }.getOrElse { listener.onProgress("  IA no disponible: ${it.message?.take(80)}"); return added }
            val news = parseAi(answer, batch)
            added += news.size
            save(project, uniqueClassNames(merge(load(project), news)))
            news.filter { it.kind == "class" }.take(4).forEach { listener.onProgress("  ✔ ${it.obf} → ${it.name} (${it.evidence.take(60)})") }
        }
        listener.onPercent(100)
        return added
    }

    private fun parseAi(answer: String, batch: List<SClass>): List<Rename> = runCatching {
        val json = JsonParser.parseString(answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1)).asJsonObject
        val byDesc = batch.associateBy { it.desc }
        val valid = Regex("^[A-Za-z_][A-Za-z0-9_]{1,60}$")
        val out = ArrayList<Rename>()
        json.getAsJsonArray("classes")?.forEach { e ->
            val o = e.asJsonObject; val c = byDesc[o["class"]?.asString] ?: return@forEach
            val n = o["name"]?.asString ?: return@forEach
            if (valid.matches(n)) out += Rename("class", c.desc, "", c.simple, n, 0.7f, "IA: " + (o["why"]?.asString ?: "").take(160), "IA")
        }
        json.getAsJsonArray("methods")?.forEach { e ->
            val o = e.asJsonObject; val c = byDesc[o["class"]?.asString] ?: return@forEach
            val m = c.methods.firstOrNull { it.id == o["method"]?.asString } ?: return@forEach
            val n = o["name"]?.asString ?: return@forEach
            if (valid.matches(n) && isObfuscated(m.name)) out += Rename("method", c.desc, m.id, m.name, n, 0.65f, "IA: " + (o["why"]?.asString ?: "").take(160), "IA")
        }
        json.getAsJsonArray("fields")?.forEach { e ->
            val o = e.asJsonObject; val c = byDesc[o["class"]?.asString] ?: return@forEach
            val id = o["field"]?.asString ?: return@forEach
            val f = c.fields.firstOrNull { "${it.first}:${it.second}" == id } ?: return@forEach
            val n = o["name"]?.asString ?: return@forEach
            if (valid.matches(n) && isObfuscated(f.first)) out += Rename("field", c.desc, id, f.first, n, 0.65f, "IA: " + (o["why"]?.asString ?: "").take(160), "IA")
        }
        out
    }.getOrDefault(emptyList())

    /** Renombrar a mano (o la IA desde el chat con rename_symbol). */
    fun rename(project: File, kind: String, cls: String, id: String, newName: String, source: String, why: String): Rename {
        require(Regex("^[A-Za-z_][A-Za-z0-9_]{0,80}$").matches(newName)) { "Nombre no válido: $newName" }
        val desc = if (cls.startsWith("L")) cls.removeSuffix(";") + ";" else "L" + cls.replace('.', '/') + ";"
        val obf = when (kind) { "class" -> desc.removeSuffix(";").substringAfterLast('/'); "method" -> id.substringBefore('('); else -> id.substringBefore(':') }
        val r = Rename(kind, desc, if (kind == "class") "" else id, obf, newName, if (source == "Tú") 1f else 0.8f, why.take(200), source)
        save(project, merge(load(project), listOf(r)))
        return r
    }

    /** Texto para la IA: nombres reales de las clases ofuscadas que aparecen en [paths]. */
    fun contextFor(project: File, paths: List<String>): String {
        val names = classNames(project)
        if (names.isEmpty()) return ""
        val lines = paths.filter { it.endsWith(".smali") }.distinct().mapNotNull { p ->
            val desc = "L" + p.substringAfter("smali/").substringAfter('/').removeSuffix(".smali") + ";"
            names[desc]?.let { "  ${p.substringAfterLast('/')} = ${it.name} (${(it.conf * 100).toInt()}%, ${it.source}: ${it.evidence.take(80)})" }
        }.take(15)
        return if (lines.isEmpty()) "" else "NOMBRES REALES DEDUCIDOS (desofuscación de la app + IA):\n" + lines.joinToString("\n")
    }

    /** Renombrados en formato jadx (para ver el Java con nombres legibles). */
    fun jadxRenames(project: File): List<jadx.api.data.ICodeRename> = load(project).mapNotNull { r ->
        val decl = r.cls.removePrefix("L").removeSuffix(";").replace('/', '.')
        runCatching {
            when (r.kind) {
                "class" -> jadx.api.data.impl.JadxCodeRename(jadx.api.data.impl.JadxNodeRef(jadx.api.data.IJavaNodeRef.RefType.CLASS, decl, null), r.name)
                "method" -> jadx.api.data.impl.JadxCodeRename(jadx.api.data.impl.JadxNodeRef(jadx.api.data.IJavaNodeRef.RefType.METHOD, decl, r.id), r.name)
                "field" -> jadx.api.data.impl.JadxCodeRename(jadx.api.data.impl.JadxNodeRef(jadx.api.data.IJavaNodeRef.RefType.FIELD, decl, r.id), r.name)
                else -> null
            }
        }.getOrNull()
    }
}
