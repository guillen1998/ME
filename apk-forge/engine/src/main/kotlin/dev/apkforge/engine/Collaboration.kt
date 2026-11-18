package dev.apkforge.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.util.Base64

// =====================================================================================
//  La APP y la IA trabajando juntas: la app prepara, ordena y localiza; la IA decide y edita.
// =====================================================================================

/** Modelos con visión (sin clave, probados): leen capturas y fotos. */
object VisionAi {
    private const val OVH = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1"
    val endpoints = listOf(
        AiEndpoint(OVH, "", "Qwen2.5-VL-72B-Instruct", "Qwen 2.5 VL 72B (OVH)"),
        AiEndpoint(OVH, "", "qwen3-vl-30b-a3b", "Qwen 3 VL 30B (OVH)"),
        AiEndpoint(OVH, "", "gemma-4-26b-a4b", "Gemma 4 26B (OVH)"),
        AiEndpoint(OVH, "", "nemotron3-omni-30b", "Nemotron Omni 30B (OVH)"),
    )

    /**
     * Describe imágenes para que el agente (que trabaja con texto y herramientas) las entienda:
     * transcribe TODO el texto visible tal cual y describe la pantalla y sus elementos.
     */
    fun describe(images: List<ByteArray>, userText: String, endpoints: List<AiEndpoint> = this.endpoints): String {
        val content = mutableListOf<Map<String, Any>>(mapOf("type" to "text", "text" to """
            Estás ayudando a modificar una app Android. El usuario adjunta esta(s) imagen(es) con el mensaje: "$userText".
            1) TEXTO VISIBLE: transcribe exactamente, línea por línea, todo el texto que aparece (botones, títulos, etiquetas, menús).
            2) PANTALLA: describe la disposición (lista, cuadrícula, pestañas, barra superior…), colores principales (en hex aproximado) y elementos.
            3) Si la imagen es un icono o logo, descríbelo (forma, colores, fondo).
            Responde en español y sé concreto.
        """.trimIndent()))
        images.forEach { img ->
            val mime = if (img.size > 3 && img[0] == 0x89.toByte() && img[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
            content += mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:$mime;base64," + Base64.getEncoder().encodeToString(img)))
        }
        val body = JsonObject().apply {
            add("messages", Gson().toJsonTree(listOf(mapOf("role" to "user", "content" to content))))
            addProperty("max_tokens", 1500)
        }
        val errors = LinkedHashMap<String, String>()
        for (round in 0 until 3) {
            for (ep in endpoints) {
                try {
                    val json = OpenAiHttp.chat(ep, body)
                    val text = json.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
                        .get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    if (text.isNotBlank()) return text.trim()
                } catch (e: OpenAiHttp.RetryableException) { errors[ep.label] = e.message ?: "error" }
            }
            if (round < 2) Thread.sleep(OpenAiHttp.waitSeconds(errors.values, round) * 1000L)
        }
        throw EngineException("No se pudo analizar la imagen:\n" + errors.values.joinToString("\n") { "• $it" })
    }
}

/**
 * Mapa del proyecto que la app arma sola y se le da a la IA desde el principio: así no gasta
 * pasos (ni cupo) en descubrir lo básico. Distingue el código PROPIO de la app de las librerías.
 */
object ProjectIndex {
    private val LIBS = listOf("android", "androidx", "kotlin", "kotlinx", "com/google", "com/android", "org/jetbrains", "okhttp3", "okio",
        "retrofit2", "com/squareup", "io/reactivex", "dagger", "javax", "j\$", "org/intellij", "com/facebook", "com/bumptech", "coil", "org/json")

    fun build(project: File): String {
        if (!ApkProject.isProject(project)) return ""
        val cache = File(project, ".apkforge/index.md")
        if (cache.isFile && cache.lastModified() > File(project, ".apkforge/project.properties").lastModified() &&
            System.currentTimeMillis() - cache.lastModified() < 6 * 3600_000) return cache.readText()
        val text = runCatching { compute(project) }.getOrElse { "" }
        runCatching { cache.writeText(text) }
        return text
    }

    private fun compute(project: File): String = buildString {
        val info = runCatching { ApkProject.readInfo(project) }.getOrNull()
        val manifest = File(project, "AndroidManifest.xml").takeIf { it.isFile }?.readText().orEmpty()
        val res = File(project, "resources").listFiles()?.sortedBy { it.name }?.firstNotNullOfOrNull { File(it, "res").takeIf { r -> r.isDirectory } }
        appendLine("MAPA DEL PROYECTO (preparado por la app)")
        appendLine("Paquete: ${info?.packageName} · minSdk ${info?.minSdk}")
        val label = Regex("<application[^>]*android:label=\"([^\"]+)\"").find(manifest)?.groupValues?.get(1)
        val icon = Regex("<application[^>]*android:icon=\"([^\"]+)\"").find(manifest)?.groupValues?.get(1)
        appendLine("Nombre (android:label): $label · Icono: $icon")
        if (res != null && label?.startsWith("@string/") == true) {
            val name = label.removePrefix("@string/")
            Regex("<string name=\"$name\">([^<]*)</string>").find(File(res, "values/strings.xml").takeIf { it.isFile }?.readText().orEmpty())
                ?.let { appendLine("  → valor actual: \"${it.groupValues[1]}\" (resources/…/res/values/strings.xml)") }
        }
        val acts = Regex("<activity[^>]*android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toList()
        appendLine("Actividades (${acts.size}): " + acts.take(25).joinToString(", "))
        manifest.split("<activity").drop(1).firstOrNull { it.substringBefore("</activity>").contains("android.intent.category.LAUNCHER") }
            ?.let { b -> Regex("android:name=\"([^\"]+)\"").find(b)?.groupValues?.get(1) }
            ?.let { appendLine("Actividad principal: $it") }
        if (res != null) {
            val layouts = File(res, "layout").list()?.filter { !it.startsWith("abc_") && !it.startsWith("mtrl_") && !it.startsWith("design_") &&
                !it.startsWith("material_") && !it.startsWith("notification_") && !it.startsWith("m3_") &&
                !it.startsWith("select_dialog_") && !it.startsWith("support_simple_") && !it.startsWith("preference") && !it.startsWith("browser_actions") &&
                !it.startsWith("custom_dialog") && !it.startsWith("ime_") && !it.startsWith("splash_screen_view") }?.sorted().orEmpty()
            appendLine("Layouts propios (${layouts.size}): " + layouts.take(60).joinToString(", ") { it.removeSuffix(".xml") })
            val strings = File(res, "values/strings.xml").takeIf { it.isFile }?.readText().orEmpty()
            appendLine("Textos en strings.xml: ${Regex("<string ").findAll(strings).count()} · carpetas de idiomas: " +
                res.list()?.filter { it.startsWith("values-") && it.length in 9..13 && !it.contains("v2") && !it.contains("dp") }?.take(15)?.joinToString(", "))
            val colors = File(res, "values/colors.xml").takeIf { it.isFile }?.readText().orEmpty()
            val mainColors = Regex("<color name=\"((?:color)?[Pp]rimary[^\"]*|accent[^\"]*|colorAccent|[a-z_]*background[^\"]*)\">([^<]+)</color>").findAll(colors)
                .take(8).joinToString(", ") { "${it.groupValues[1]}=${it.groupValues[2]}" }
            if (mainColors.isNotBlank()) appendLine("Colores principales: $mainColors")
            val mipmaps = res.list()?.filter { it.startsWith("mipmap") }?.joinToString(", ")
            if (!mipmaps.isNullOrBlank()) appendLine("Iconos en: $mipmaps")
        }
        val smali = File(project, "smali")
        if (smali.isDirectory) {
            val appPrefix = info?.packageName?.split('.')?.take(2)?.joinToString("/")
            val own = HashMap<String, Int>(); val libs = HashMap<String, Int>()
            smali.listFiles()?.forEach { dexDir ->
                dexDir.walkTopDown().filter { it.extension == "smali" }.forEach { f ->
                    val rel = f.relativeTo(dexDir).path
                    val pkg = rel.substringBeforeLast('/', "(raíz)").split('/').take(3).joinToString(".")
                    if (appPrefix != null && rel.startsWith("$appPrefix/")) own.merge("$pkg  [${dexDir.name}]", 1, Int::plus)
                    else if (LIBS.none { rel.startsWith("$it/") }) libs.merge(rel.split('/').take(2).joinToString("."), 1, Int::plus)
                }
            }
            appendLine("Código propio de la app (paquete ${appPrefix?.replace('/', '.')}), clases por paquete:")
            own.entries.sortedByDescending { it.value }.take(15).forEach { appendLine("  ${it.key}: ${it.value}") }
            if (own.isEmpty()) appendLine("  (ofuscado o en paquetes cortos: revisa smali/classes*/ y usa find_related)")
            appendLine("Librerías de terceros detectadas: " + libs.entries.sortedByDescending { it.value }.take(10).joinToString(", ") { it.key })
        }
        val assets = File(project, "root/assets")
        if (assets.isDirectory) appendLine("Assets: " + assets.list()?.take(20)?.joinToString(", "))
        val libs = File(project, "root/lib")
        if (libs.isDirectory) appendLine("Librerías nativas: " + libs.listFiles()?.firstOrNull()?.list()?.take(15)?.joinToString(", "))
    }
}

/**
 * La app localiza antes que la IA: a partir del mensaje del usuario (y del texto leído en sus
 * capturas) busca qué archivos tienen que ver (textos visibles en strings.xml, layouts e ids con
 * esos nombres, smali que los usa) y se los entrega a la IA con un extracto.
 */
object ContextFinder {
    data class Hit(val path: String, val line: Int, val snippet: String, val why: String)

    private val STOP = setOf("para", "como", "esta", "este", "esto", "quiero", "puedes", "hacer", "haga", "que", "los", "las", "una", "del",
        "con", "por", "más", "mas", "sea", "sean", "vean", "forma", "ahora", "cambia", "cambiar", "pon", "poner", "todo", "todos", "app", "apk",
        "the", "and", "with", "from", "this", "that", "edita", "editar", "modifica", "modificar", "diseño", "pantalla",
        "texto", "textos", "nombre", "cambie", "quede", "bien", "solo", "también", "tambien", "imagen", "captura", "favor", "gracias")

    fun keywords(text: String): List<String> {
        val quoted = Regex("[\"“']([^\"”']{3,40})[\"”']").findAll(text).map { it.groupValues[1].trim() }.toList()
        val words = Regex("[\\p{L}_][\\p{L}0-9_]{3,}").findAll(text).map { it.value }
            .filter { it.lowercase() !in STOP }.toList()
        // singular simple: "canales" -> "canal"
        val stems = words.flatMap { w -> listOf(w, w.removeSuffix("es").takeIf { it.length >= 4 && w.endsWith("es") }, w.removeSuffix("s").takeIf { it.length >= 4 && w.endsWith("s") }) }
            .filterNotNull()
        return (quoted + stems).map { it.trim() }.filter { it.length >= 3 }.distinctBy { it.lowercase() }.take(14)
    }

    fun find(project: File, text: String, limit: Int = 25): List<Hit> {
        if (!ApkProject.isProject(project)) return emptyList()
        val keys = keywords(text)
        if (keys.isEmpty()) return emptyList()
        val hits = LinkedHashMap<String, Hit>()
        val res = File(project, "resources").listFiles()?.flatMap { listOfNotNull(File(it, "res").takeIf { r -> r.isDirectory }) }.orEmpty()
        fun add(f: File, line: Int, snippet: String, why: String) {
            val rel = f.relativeTo(project).path
            if (hits.size < limit * 3) hits.putIfAbsent("$rel:$line", Hit(rel, line, snippet.trim().take(180), why))
        }
        for (r in res) {
            // 1) Archivos de layout cuyo NOMBRE coincide (item_channel.xml para "canal/channel")
            val trans = keys.flatMap { listOf(it, translate(it)) }.filterNotNull().map { it.lowercase() }.distinct()
            r.listFiles { d -> d.isDirectory && d.name.startsWith("layout") }.orEmpty().forEach { dir ->
                dir.listFiles().orEmpty().filter { f -> trans.any { k -> f.nameWithoutExtension.lowercase().contains(k) } }
                    .forEach { add(it, 1, "(layout cuyo nombre coincide)", "nombre del archivo") }
            }
            // 2) Textos visibles en strings.xml (valores), 3) ids/nombres en layouts y values
            r.walkTopDown().filter { it.isFile && it.extension == "xml" && it.length() < 3_000_000 }
                .sortedBy { f -> val d = f.parentFile.name; if (d.startsWith("layout")) 0 else if (d == "values") 1 else if (d.startsWith("values-") && d.length <= 13) 3 else 2 }
                .forEach { f ->
                runCatching {
                    f.useLines { lines ->
                        lines.forEachIndexed { i, l ->
                            val low = l.lowercase()
                            keys.firstOrNull { k -> low.contains(k.lowercase()) }?.let { k ->
                                val why = if (f.name.startsWith("strings")) "texto “$k”" else if (f.parentFile.name.startsWith("layout")) "layout contiene “$k”" else "contiene “$k”"
                                add(f, i + 1, l, why)
                            }
                        }
                    }
                }
                if (hits.size >= limit * 3) return rank(hits.values.toList()).take(limit)
            }
        }
        // 4) smali propio que usa esos textos
        File(project, "smali").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.extension == "smali" && !it.path.contains("/androidx/") && !it.path.contains("/kotlin") }
            ?.take(20000)?.forEach { f ->
                if (hits.size >= limit) return@forEach
                runCatching {
                    f.useLines { lines -> lines.forEachIndexed { i, l -> if (l.contains("const-string") && keys.any { l.contains(it, true) }) add(f, i + 1, l, "smali usa el texto") } }
                }
            }
        return rank(hits.values.toList()).take(limit)
    }

    /** Primero layouts y textos del idioma base; las traducciones a otros idiomas al final. */
    private fun rank(list: List<Hit>): List<Hit> = list.sortedBy { h ->
        val dir = h.path.substringBeforeLast('/').substringAfterLast('/')
        when {
            h.why == "nombre del archivo" -> 0
            dir.startsWith("layout") -> 1
            dir == "values" -> 2
            h.path.endsWith(".smali") -> 3
            dir.startsWith("values-") && dir.length <= 13 -> 6   // otros idiomas
            else -> 4
        }
    }

    /** Pequeño diccionario es→en de palabras típicas de UI (los nombres de layouts suelen estar en inglés). */
    /** Diccionario es→en de palabras típicas de apps (los nombres de clases/layouts y la red neuronal van en inglés). */
    private val ES_EN: Map<String, String> = """
        canal=channel pelicula=movie serie=series boton=button lista=list inicio=home menu=menu ajuste=settings ajustes=settings
        configuracion=settings opcion=option opciones=options perfil=profile busqueda=search buscar=search categoria=category
        imagen=image icono=icon fondo=background titulo=title reproductor=player video=video favorito=favorite cuadricula=grid
        tarjeta=card pestana=tab barra=bar pantalla=screen detalle=detail detalles=details aplicacion=app aplicaciones=apps
        descargar=download descarga=download subir=upload repositorio=repository usuario=user cuenta=account contrasena=password
        clave=key iniciar=login sesion=session registro=register cerrar=close abrir=open guardar=save borrar=delete eliminar=delete
        editar=edit nuevo=new actualizar=update actualizacion=update notificacion=notification mensaje=message chat=chat
        pago=payment pagos=payment compra=purchase premium=premium suscripcion=subscription anuncio=ad anuncios=ads publicidad=ads
        idioma=language tema=theme oscuro=dark claro=light color=color texto=text letra=font fuente=font tamano=size
        ventana=window dialogo=dialog alerta=alert error=error cargando=loading cargar=load red=network internet=network
        servidor=server enlace=link direccion=url conexion=connection archivo=file carpeta=folder foto=photo camara=camera
        galeria=gallery mapa=map ubicacion=location telefono=phone correo=email contacto=contact ayuda=help acerca=about
        version=version licencia=license privacidad=privacy seguridad=security cifrado=encryption cifrar=encrypt descifrar=decrypt
        firma=signature permiso=permission permisos=permissions instalar=install desinstalar=uninstall reproducir=play pausa=pause
        musica=music audio=audio volumen=volume deporte=sports deportes=sports noticias=news en vivo=live directo=live
        envivo=live calidad=quality filtro=filter ordenar=sort fecha=date hora=time precio=price tienda=store carrito=cart
        producto=product pedido=order fila=row filas=rows columna=column columnas=columns celda=cell elemento=item
        adaptador=adapter vista=view diseno=layout estilo=style animacion=animation splash=splash bienvenida=welcome
    """.trim().split(Regex("\\s+")).mapNotNull { it.split('=').takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap()

    internal fun translate(w: String): String? {
        val k = java.text.Normalizer.normalize(w.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return ES_EN[k] ?: ES_EN[k.removeSuffix("s")] ?: ES_EN[k.removeSuffix("es")]
    }

    fun format(hits: List<Hit>): String = if (hits.isEmpty()) "" else buildString {
        appendLine("ARCHIVOS RELACIONADOS (la app los encontró por tu mensaje; empieza por aquí):")
        hits.forEach { appendLine("• ${it.path}:${it.line} — ${it.why}: ${it.snippet}") }
    }
}

/** Comprueba que un XML editado sigue siendo válido (la IA recibe el error al momento para corregirlo). */
object XmlCheck {
    fun error(text: String): String? = runCatching {
        val p = com.reandroid.xml.XMLFactory.newPullParser(text)
        while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) { /* recorrer */ }
        null
    }.getOrElse { "XML no válido: ${it.message?.take(200)}" }
}

/**
 * Prepara cada mensaje del usuario antes de que llegue a la IA: añade lo que se ve en sus imágenes
 * (texto leído + descripción) y los archivos del proyecto que la app encontró relacionados.
 */
object MessagePreparer {
    /** Candidatos para el motor neuronal: archivos encontrados por la app + los ya editados antes. */
    fun candidates(project: File, hits: List<ContextFinder.Hit>): List<NeuralEngine.Candidate> {
        val byFile = hits.groupBy { it.path }
        val order = hits.map { it.path }.distinct()
        val touched = ChangeLog.all(project).map { it.file }.distinct()
        return (order + touched.filter { it !in byFile }).map { p -> NeuralEngine.Candidate(p, order.indexOf(p).let { if (it < 0) 50 else it }, byFile[p]?.size ?: 0) }
    }

    /** La red neuronal del móvil reordena lo encontrado: primero los archivos con más probabilidad de ser los que hay que tocar. */
    fun neuralRerank(project: File, query: String, hits: List<ContextFinder.Hit>, limit: Int = 25): List<ContextFinder.Hit> {
        if (hits.isEmpty()) return hits
        val touched = ChangeLog.all(project).map { it.file }.toSet()
        val scores = NeuralEngine.shared.rank(query, candidates(project, hits), touched).associate { it.path to it.score }
        return hits.sortedByDescending { scores[it.path] ?: 0f }.take(limit)
    }

    /** Después de que la IA trabaje: lo que editó de verdad enseña a la red (y mide si acertó). */
    fun learnFrom(project: File, query: String, since: Long): Int {
        val hits = runCatching { ContextFinder.find(project, query, 60) }.getOrDefault(emptyList())
        return NeuralBrain.learnFromEdit(project, query, since, hits)
    }

    data class Prepared(val text: String, val imageNotes: String?, val related: List<ContextFinder.Hit>, val thought: NeuralBrain.Thought? = null)

    fun prepare(
        project: File?, userText: String, images: List<ByteArray>,
        visionEndpoints: List<AiEndpoint> = VisionAi.endpoints, listener: ProgressListener = ProgressListener.NONE,
    ): Prepared {
        var notes: String? = null
        if (images.isNotEmpty()) {
            listener.onProgress("La app analiza ${images.size} imagen(es)…")
            notes = runCatching { VisionAi.describe(images, userText, visionEndpoints) }.getOrElse { "(No se pudo analizar la imagen: ${it.message})" }
        }
        val related = if (project != null) {
            listener.onProgress("La app busca archivos relacionados…")
            val q = userText + "\n" + (notes ?: "")
            val raw = runCatching { ContextFinder.find(project, q, 60) }.getOrDefault(emptyList())
            runCatching { neuralRerank(project, q, raw) }.getOrDefault(raw.take(25))
        } else emptyList()
        // Desofuscación: la primera vez, la app deduce los nombres reales (segundos, sin internet)
        if (project != null && !Deobfuscator.mappingFile(project).isFile) runCatching {
            listener.onProgress("🔍 La app desofusca el proyecto (nombres reales)…"); Deobfuscator.analyze(project, ProgressListener.NONE)
        }
        // El motor neuronal piensa: significado (CPU/GPU) + grafo del proyecto + lo aprendido
        val thought = if (project != null) runCatching {
            listener.onProgress("🧠 El motor neuronal razona sobre el proyecto…")
            NeuralBrain.think(project, userText + "\n" + (notes ?: ""), related, listener)
        }.getOrNull() else null
        val text = buildString {
            append(userText)
            notes?.let { appendLine(); appendLine(); appendLine("IMÁGENES ADJUNTAS (analizadas por la app):"); appendLine(it) }
            if (related.isNotEmpty()) { appendLine(); appendLine(); append(ContextFinder.format(related)) }
            thought?.format()?.takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(); append(it) }
            if (project != null) runCatching {
                Deobfuscator.contextFor(project, related.map { it.path } + (thought?.ranked?.map { it.first } ?: emptyList()))
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(); append(it) }
        }
        return Prepared(text, notes, related, thought)
    }
}

/** Localiza en un error de compilación el archivo y la línea exactos donde falla. */
object BuildErrors {
    data class Location(val file: File, val line: Int, val message: String)

    fun locate(error: String): Location? {
        // ARSCLib: "at /ruta/res/values/colors.xml [line = 108]"
        Regex("""(/[^\s\[\]"]+\.(?:xml|json))\s*\[line\s*=\s*(\d+)]""").find(error)?.let { m ->
            return Location(File(m.groupValues[1]), m.groupValues[2].toInt(), error.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty())
        }
        // smali: "/ruta/smali/classes/com/app/A.smali[12,4] no viable alternative…"
        Regex("""(/[^\s\[\]"]+\.smali)\[(\d+),\d+]\s*(.*)""").find(error)?.let { m ->
            return Location(File(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].trim())
        }
        // genérico: "/ruta/archivo.xml:12"
        Regex("""(/[^\s:"]+\.(?:xml|smali)):(\d+)""").find(error)?.let { m -> return Location(File(m.groupValues[1]), m.groupValues[2].toInt(), error.take(200)) }
        return null
    }

    /** Instrucción para que la IA corrija el error, con el extracto de código alrededor. */
    fun fixPrompt(project: File, error: String): String {
        val loc = locate(error)
        return buildString {
            appendLine("La exportación del APK falló. Corrige el error para que compile, con el cambio mínimo, sin perder lo que se quería hacer.")
            appendLine(); appendLine("ERROR:"); appendLine(error.take(1500))
            if (loc != null && loc.file.isFile) {
                val lines = loc.file.readLines()
                val from = (loc.line - 8).coerceAtLeast(1); val to = (loc.line + 8).coerceAtMost(lines.size)
                appendLine(); appendLine("ARCHIVO: ${runCatching { loc.file.relativeTo(project).path }.getOrDefault(loc.file.path)} (línea ${loc.line})")
                (from..to).forEach { n -> appendLine((if (n == loc.line) "→ " else "  ") + "$n: " + lines[n - 1]) }
            }
            appendLine(); append("Revisa también list_changes para ver qué se editó antes y que la corrección no lo deshaga.")
        }
    }
}
