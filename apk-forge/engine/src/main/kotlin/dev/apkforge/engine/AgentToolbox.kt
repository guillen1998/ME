package dev.apkforge.engine

import java.io.File

/** Definición de herramienta independiente del proveedor de IA (Claude, OpenRouter, Groq, Ollama…). */
data class ToolSpec(
    val name: String,
    val description: String,
    val props: Map<String, Map<String, String>>,
    val required: List<String>,
)

/**
 * Herramientas del agente que se ejecutan EN EL MÓVIL sobre el proyecto/APK. Las comparten todos
 * los proveedores de IA, así que da igual qué modelo uses: las capacidades son las mismas.
 */
class AgentToolbox(
    private val root: File,
    private val apk: File?,
    val allowEdits: Boolean,
    private val java: () -> JavaDecompiler?,
) {
    private var findingsCache: List<Finding>? = null

    private fun projectDir(): File? = (if (root.isDirectory) root else root.parentFile).let { d ->
        if (ApkProject.isProject(d)) d else ChangeLog.projectOf(File(d, "x"))
    }

    /** Mapa del proyecto que la app prepara para la IA (vacío si no es un proyecto). */
    fun projectMap(): String = projectDir()?.let { runCatching { ProjectIndex.build(it) }.getOrDefault("") }.orEmpty()

    private fun xmlWarn(f: File): String =
        if (f.extension == "xml") XmlCheck.error(f.readText())?.let { "\n⚠ $it — corrígelo antes de seguir." } ?: "" else ""

    fun systemPrompt(preset: AgentPreset): String = """
        Eres un experto en ingeniería inversa de Android integrado en ApkForge, una app móvil.
        Trabajas sobre ${if (root.isDirectory) "el proyecto decompilado en '${root.name}'" else "el archivo '${root.name}'"}${apk?.let { " (APK original: ${it.name})" } ?: ""}.
        Estructura típica del proyecto: AndroidManifest.xml, resources/package_*/res (XML), smali/classesN/ (código smali), root/ (assets, lib/*.so).

        Tu tarea en esta sesión: ${preset.goal}

        ${projectMap()}

        Reglas:
        - Basa todo en lo que veas con las herramientas; no inventes clases ni archivos. Cita rutas y nombres.
        - Para leer lógica prefiere java_class (Java legible) y usa smali solo cuando haga falta editar.
        - Sé eficiente: busca primero (search_text, find_classes, scan_findings) y lee solo lo necesario.
        - Si es un proyecto con cambios previos, consulta list_changes para saber qué se editó antes.
        - La app trabaja contigo: arriba tienes el MAPA DEL PROYECTO, y en los mensajes del usuario la app añade lo que se ve en sus capturas y los ARCHIVOS RELACIONADOS que encontró. Úsalos primero; si falta algo, usa find_related.
        - Puedes editar CUALQUIER cosa del proyecto: smali, XML, recursos, manifest, assets, imágenes y binarios (.so/.dex/.bin con hex_read + hex_patch, manteniendo el tamaño). No digas que algo no se puede editar: busca la forma con las herramientas.
        - Edita SOLO archivos del proyecto (smali/, resources/, AndroidManifest.xml, root/): son los que entran al APK al exportar. La carpeta android-studio/ es solo de lectura para el PC y no cambia el APK.
        - Para editar archivos grandes (como smali) usa replace_lines por NÚMERO DE LÍNEA (lo que te da read_file): es mucho más fiable que copiar el texto exacto. Si replace_in_file falla con "aparece 0 veces", NO lo repitas: cambia a replace_lines.
        - ${if (allowEdits) "Puedes editar archivos con write_file/replace_in_file (se guarda copia de seguridad)." else "Edición DESACTIVADA: si hace falta cambiar algo, explica el cambio exacto en vez de hacerlo."}
        - Trabaja solo en apps propias, de código abierto o con permiso; no ayudes a saltarse pagos, licencias o DRM de apps comerciales de terceros.
        - IMPORTANTE: si tras un cambio la app "no cambia", se cierra, o falla al cargar la lista/el login, NO insistas editando la pantalla: usa check_integrity. Casi siempre es una comprobación de FIRMA (local o en el servidor) que se dispara al re-firmar el APK. Si la firma la valida el SERVIDOR, no se puede arreglar en el móvil: dilo claramente al usuario en vez de seguir intentando.
        - Si hay textos CIFRADOS (URLs, claves o mensajes ilegibles que pasan por un método), usa decrypt_strings: la app los descifra; para los pendientes, lee el descifrador, deduce el algoritmo y dáselo con decrypt_recipe.
        - Si el código está OFUSCADO (clases a, b, c…), no te rindas: usa deobfuscate (la app deduce nombres reales), deobf_info para estudiar las clases que falten y rename_symbol para guardar lo que deduzcas. Así tú y la app desofuscan juntos.
        - Para cambios de diseño grandes (p. ej. pasar una lista de filas a cuadrícula de filas y columnas) usa find_lists y list_to_grid, y después adapta el layout del elemento si hace falta.
        - Responde en español, claro y organizado.
    """.trimIndent()

    val specs: List<ToolSpec> = buildList {
        add(tool("list_files", "Lista archivos y carpetas dentro de una ruta del proyecto (relativa a la raíz).",
            mapOf("path" to str("Ruta relativa; '' para la raíz"), "max" to int("Máximo de entradas (por defecto 200)")), listOf("path")))
        add(tool("read_file", "Lee un archivo de texto (smali, xml, json…) por líneas.",
            mapOf("path" to str("Ruta relativa"), "start_line" to int("Primera línea (1 por defecto)"), "max_lines" to int("Máximo de líneas (400 por defecto)")), listOf("path")))
        add(tool("search_text", "Busca texto o regex en los archivos del proyecto (como grep). Devuelve archivo:línea: texto.",
            mapOf("query" to str("Texto o expresión regular"), "regex" to bool("true si query es regex"), "extension" to str("Filtrar por extensión: smali, xml, json… ('' = todas)")), listOf("query")))
        add(tool("scan_findings", "Escaneo automático de URLs, IPs, correos, secretos, rutas, SQL y cripto en TODO el proyecto (incluido dentro de dex y .so).",
            mapOf("kind" to str("Filtrar: URL, IP, EMAIL, SECRET, FIREBASE, S3, PATH, SQL, CRYPTO, DEEPLINK ('' = todos)")), listOf()))
        add(tool("find_classes", "Busca clases por nombre (Java) en el código de la app.",
            mapOf("query" to str("Parte del nombre de clase o paquete")), listOf("query")))
        add(tool("java_class", "Decompila una clase a Java legible (jadx).",
            mapOf("class_name" to str("Nombre completo, p. ej. com.app.MainActivity")), listOf("class_name")))
        add(tool("class_xref", "Referencias cruzadas de una clase: métodos (firma), quién la usa y de qué depende.",
            mapOf("class_name" to str("Nombre completo de la clase")), listOf("class_name")))
        add(tool("binary_strings", "Extrae las cadenas legibles de un archivo binario (.so, .dex, .bin…), filtrando opcionalmente.",
            mapOf("path" to str("Ruta relativa del binario"), "filter" to str("Solo cadenas que contengan esto ('' = todas)")), listOf("path")))
        add(tool("analyze_file", "Análisis completo de UN archivo: tipo real por contenido, tamaño, hashes, entropía (detecta cifrado/compresión), cadenas destacadas y hex inicial. Úsalo primero con cualquier archivo desconocido.",
            mapOf("path" to str("Ruta relativa del archivo")), listOf("path")))
        add(tool("list_changes", "Informe de TODO lo que se ha editado en este proyecto (por la IA y a mano): archivos, quién, cuándo y las líneas cambiadas. Consúltalo antes de seguir editando para no repetir ni deshacer cambios.",
            mapOf("only_pending" to bool("true = solo lo cambiado desde la última exportación")), listOf()))
        add(tool("hex_read", "Lee bytes de un archivo binario en hexadecimal (offset y longitud), o busca dónde aparece un texto/bytes. Úsalo antes de parchear .so/.dex/.bin.",
            mapOf("path" to str("Ruta relativa"), "offset" to int("Offset inicial (por defecto 0)"), "length" to int("Bytes a leer (máx. 4096)"),
                "find_text" to str("Opcional: texto a buscar (devuelve offsets)"), "find_hex" to str("Opcional: bytes en hex a buscar")), listOf("path")))
        add(tool("project_map", "Mapa del proyecto preparado por la app: paquete, nombre, icono, actividades, layouts propios, idiomas, colores, paquetes de código propio, assets.",
            mapOf("refresh" to bool("true para recalcular")), listOf()))
        add(tool("find_related", "La app busca qué archivos tienen que ver con un texto o descripción (textos visibles, nombres de layouts/ids, smali que los usa). Úsalo para localizar rápido qué editar.",
            mapOf("text" to str("Texto visible, nombre de pantalla o descripción de lo que buscas")), listOf("text")))
        add(tool("decrypt", "Descifra o decodifica datos automáticamente. Úsalo cuando encuentres AES/DES/RC4/XOR/Base64/gzip en el código: lee la clave e IV del smali y descífralo tú.",
            mapOf(
                "algorithm" to str("aes | aes-ecb | aes-gcm | aes-ctr | des | 3des | rc4 | xor | base64 | hex | gzip | zlib | deflate"),
                "input" to str("Dato a descifrar como texto (base64 o hex). Usa esto o 'path', no ambos"),
                "path" to str("Ruta relativa de un archivo a descifrar en vez de 'input'"),
                "key" to str("Clave: texto, hex o base64 (se detecta sola)"),
                "iv" to str("IV/nonce opcional: texto, hex o base64"),
                "key_encoding" to str("auto (por defecto) | raw | hex | base64 — cómo interpretar la clave"),
                "iv_encoding" to str("auto | raw | hex | base64"),
                "save_to" to str("Si se da, guarda el resultado descifrado en esta ruta relativa"),
            ), listOf("algorithm")))
        add(tool("auto_aes", "Prueba los modos AES habituales (CBC/ECB/GCM/CTR, IV=0 o al principio) con una clave y devuelve los resultados que parecen reales (texto, dex, zip, JSON…). Para cuando no sabes el modo exacto.",
            mapOf(
                "key" to str("Clave: texto, hex o base64"),
                "input" to str("Dato cifrado como base64/hex, o usa 'path'"),
                "path" to str("Ruta relativa del archivo cifrado"),
                "iv" to str("IV opcional"),
                "save_to" to str("Ruta relativa donde guardar el primer resultado plausible (opcional)"),
            ), listOf("key")))
        add(tool("semantic_search", "Búsqueda por SIGNIFICADO con el motor neuronal de la app (red en la CPU/GPU del móvil) + razonamiento sobre el grafo del proyecto: devuelve los archivos más probables y cadenas como 'pantalla → adaptador → layout de la fila'. Úsalo cuando no sepas el nombre exacto de lo que buscas.",
            mapOf("query" to str("Qué buscas, en lenguaje natural (p. ej. 'pantalla de canales', 'donde se descarga la lista')")), listOf("query")))
        add(tool("deobfuscate", "La APP desofusca el proyecto en segundos (toString de Kotlin, etiquetas de log, layouts que infla cada clase, findViewById, getters/setters, tipos) y devuelve los nombres reales deducidos. Úsalo cuando el código esté ofuscado (clases a, b, c…).",
            mapOf("filter" to str("Opcional: solo nombres que contengan esto")), listOf()))
        add(tool("deobf_info", "Resumen de una clase ofuscada preparado por la app (superclase, campos, métodos, cadenas, APIs que llama y los nombres ya deducidos) para que decidas su nombre real.",
            mapOf("class_name" to str("Clase, p. ej. a.b.c o La/b/c;")), listOf("class_name")))
        add(tool("rename_symbol", "Guarda el nombre REAL que deduces para una clase, método o campo ofuscado. Se aplica al Java legible, a la búsqueda y al contexto (no toca el smali, exportar sigue siendo seguro).",
            mapOf("kind" to str("class | method | field"), "class_name" to str("Clase propietaria (a.b.c)"),
                "symbol" to str("Para método: nombre(params)retorno del smali, p. ej. a(I)V; para campo: nombre:tipo, p. ej. a:Ljava/lang/String;; vacío para clase"),
                "new_name" to str("Nombre real propuesto"), "reason" to str("Por qué")), listOf("kind", "class_name", "new_name", "reason")))
        add(tool("web_search", "Busca en INTERNET (sin clave). Úsalo para documentación, sintaxis de smali, cómo funciona una librería, qué devuelve una API, etc. Devuelve título, URL y un fragmento de cada resultado.",
            mapOf("query" to str("Qué buscar")), listOf("query")))
        add(tool("web_fetch", "Lee una página web o una URL (JSON, texto, HTML) y devuelve su contenido legible. Úsalo para leer la documentación o lo que encontró web_search, o una URL que aparezca en la app.",
            mapOf("url" to str("La URL a leer")), listOf("url")))
        add(tool("check_integrity", "Comprueba si la app tiene PROTECCIONES CONTRA MODIFICACIÓN (comprobación de firma local o enviada al servidor, Play Integrity, root/emulador/depurador). Es la causa nº1 de que un mod 'deje de funcionar' al re-firmar aunque el cambio sea cosmético. Úsalo SIEMPRE que un cambio no se refleje o la app falle al cargar datos/login tras modificarla.",
            mapOf(), listOf()))
        add(tool("decrypt_strings", "La APP busca los textos CIFRADOS de la app (métodos descifradores llamados con constantes raras), los descifra ejecutando el método en un intérprete aislado o probando cifrados típicos, y devuelve los textos claros. Lo que no pueda, te lo pasa con el código del descifrador para que deduzcas el algoritmo.",
            mapOf("filter" to str("Opcional: solo textos que contengan esto")), listOf()))
        add(tool("decrypt_recipe", "Cuando deduzcas el algoritmo de un descifrador, dale a la app la receta de pasos; la app la aplica a TODOS sus textos y solo la acepta si salen legibles. Pasos: base64, hex, reverse, rot(n), add(n), xor(key|n), xorchars(key|n), aes(key,mode,iv), rc4(key).",
            mapOf("decryptor" to str("Id del descifrador tal como lo dio decrypt_strings (Lpaquete/Clase;->metodo(...)Ljava/lang/String;)"),
                "steps" to str("JSON: [{\"op\":\"base64\"},{\"op\":\"xor\",\"key\":\"clave\"}]")), listOf("decryptor", "steps")))
        add(tool("find_lists", "Encuentra las LISTAS de la app (RecyclerView con LinearLayoutManager) en smali y layouts XML: clase, método y línea. Úsalo para cambios de diseño como pasar filas a cuadrícula.",
            mapOf(), listOf()))
        if (allowEdits) {
            add(tool("list_to_grid", "Convierte una lista vertical en CUADRÍCULA de N columnas (filas y columnas) de forma segura: cambia LinearLayoutManager por GridLayoutManager ajustando registros. Pasa el 'site' que devolvió find_lists.",
                mapOf("site" to str("ruta:línea tal como lo dio find_lists"), "columns" to int("Número de columnas (2 por defecto)")), listOf("site")))
            add(tool("hex_patch", "Edita un archivo BINARIO (.so, .dex, .bin, assets, imágenes…): escribe bytes en un offset, o busca y reemplaza texto/bytes. Por defecto mantiene el tamaño (obligatorio en .so/.dex).",
                mapOf("path" to str("Ruta relativa"), "offset" to int("Offset donde escribir (con 'hex')"), "hex" to str("Bytes a escribir en hex, p. ej. '00 00 A0 E3'"),
                    "find_text" to str("Texto a buscar"), "replace_text" to str("Texto nuevo"), "find_hex" to str("Bytes a buscar (hex)"), "replace_hex" to str("Bytes nuevos (hex)"),
                    "keep_size" to bool("Mantener tamaño (por defecto true)")), listOf("path")))
            add(tool("replace_all", "Busca y reemplaza un texto (o regex) en TODOS los archivos de texto del proyecto (smali, xml, json…). Devuelve en qué archivos cambió.",
                mapOf("find" to str("Texto o regex"), "replace" to str("Reemplazo"), "regex" to bool("true si 'find' es regex"), "extension" to str("Solo esta extensión ('' = todas)")), listOf("find", "replace")))
            add(tool("delete_file", "Borra un archivo o carpeta del proyecto (se guarda copia de seguridad).",
                mapOf("path" to str("Ruta relativa")), listOf("path")))
            add(tool("move_file", "Mueve o renombra un archivo/carpeta dentro del proyecto.",
                mapOf("from" to str("Ruta relativa actual"), "to" to str("Ruta relativa nueva")), listOf("from", "to")))
            add(tool("import_file", "Copia un archivo del móvil al proyecto (p. ej. una imagen o icono nuevo que el usuario tiene en Descargas).",
                mapOf("source" to str("Ruta absoluta en el móvil, p. ej. /storage/emulated/0/Download/icono.png"), "to" to str("Ruta relativa destino en el proyecto")), listOf("source", "to")))
            add(tool("write_file", "Crea o reemplaza un archivo de texto completo (guarda copia de seguridad).",
                mapOf("path" to str("Ruta relativa"), "content" to str("Contenido completo")), listOf("path", "content")))
            add(tool("replace_in_file", "Reemplaza un fragmento exacto (que aparece una sola vez) dentro de un archivo. Para archivos grandes es más fiable replace_lines.",
                mapOf("path" to str("Ruta relativa"), "old" to str("Texto exacto a reemplazar"), "new" to str("Texto nuevo")), listOf("path", "old", "new")))
            add(tool("replace_lines", "Edita por NÚMERO DE LÍNEA (lo más fiable, sobre todo en archivos grandes). Reemplaza las líneas de start_line a end_line (incluidas, como las muestra read_file) por el texto nuevo. Para BORRAR líneas, deja new vacío. Lee primero con read_file para ver los números.",
                mapOf("path" to str("Ruta relativa"), "start_line" to int("Primera línea a reemplazar (1 = la primera)"),
                    "end_line" to int("Última línea a reemplazar (incluida)"), "new" to str("Texto nuevo (varias líneas permitidas; vacío = borrar)")), listOf("path", "start_line", "end_line", "new")))
            add(tool("insert_lines", "Inserta texto DESPUÉS de una línea, sin borrar nada (usa after_line=0 para ponerlo al principio).",
                mapOf("path" to str("Ruta relativa"), "after_line" to int("Insertar después de esta línea (0 = al inicio)"), "new" to str("Texto a insertar")), listOf("path", "after_line", "new")))
        }
    }


    // ---------------- Herramientas (se ejecutan en el móvil) ----------------

    private fun resolve(path: String?): File {
        val base = if (root.isDirectory) root else root.parentFile
        val f = File(base, (path ?: "").trimStart('/')).canonicalFile
        if (!f.path.startsWith(base.canonicalPath)) throw EngineException("Ruta fuera del proyecto: $path")
        return f
    }

    /** Resuelve una ruta que DEBE ser un archivo. Si es una carpeta, lo dice claro y orienta (en vez de reventar con EISDIR). */
    private fun fileArg(path: String?): File {
        val f = resolve(path)
        if (f.isDirectory) {
            val kids = f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })).orEmpty()
            val bins = kids.filter { it.isFile && it.extension.lowercase() in setOf("so", "bin", "dex", "dat", "jar", "zip", "png", "jpg", "ttf", "mp3", "mp4") }
            throw EngineException("“$path” es una CARPETA, no un archivo. " +
                (if (f.name == "classes" || f.path.contains("/smali")) "Dentro hay código smali (texto): usa search_text, scan_findings o read_file sobre un .smali concreto. " else "") +
                (if (bins.isNotEmpty()) "Binarios dentro: " + bins.take(8).joinToString { it.name } + ". " else "") +
                "Contenido: " + kids.take(12).joinToString { if (it.isDirectory) it.name + "/" else it.name } + (if (kids.size > 12) "…" else ""))
        }
        if (!f.exists()) throw EngineException("No existe: $path")
        return f
    }

    fun run(name: String, a: Map<String, Any?>): String = when (name) {
        "list_files" -> {
            val dir = resolve(a["path"] as? String)
            val max = (a["max"] as? Number)?.toInt() ?: 200
            val list = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: throw EngineException("No es una carpeta")
            list.take(max).joinToString("\n") { if (it.isDirectory) "${it.name}/  (${it.list()?.size ?: 0})" else "${it.name}  ${it.length()} B" } +
                if (list.size > max) "\n… y ${list.size - max} más" else ""
        }
        "read_file" -> {
            val f = resolve(a["path"] as String)
            if (!f.isFile) throw EngineException("No existe: ${a["path"]}")
            // Robusto: si es binario, en vez de fallar muestra cadenas + un vistazo hex.
            if (!looksTextFile(f)) {
                "[${BinaryTools.identify(f)}; no es texto — uso cadenas + hex]\n\nCadenas:\n" +
                    (if (f.extension == "dex") BinaryTools.dexStrings(f) else BinaryTools.strings(f)).take(200).joinToString("\n") +
                    "\n\nHex (inicio):\n" + BinaryTools.hexDump(f, 0, 512)
            } else {
                val start = ((a["start_line"] as? Number)?.toInt() ?: 1).coerceAtLeast(1)
                val max = ((a["max_lines"] as? Number)?.toInt() ?: 400).coerceIn(1, 2000)
                val lines = f.readText().split('\n')
                lines.drop(start - 1).take(max).mapIndexed { i, l -> "${start + i}: $l" }.joinToString("\n") +
                    "\n[líneas $start-${minOf(start + max - 1, lines.size)} de ${lines.size}]"
            }
        }
        "analyze_file" -> {
            val f = fileArg(a["path"] as? String)
            val bytes = if (f.length() <= 32L * 1024 * 1024) f.readBytes() else f.inputStream().use { it.readNBytes(32 * 1024 * 1024) }
            val ent = entropy(bytes)
            val strs = if (f.extension == "dex") BinaryTools.dexStrings(f) else BinaryTools.strings(f)
            val interesting = strs.filter { s -> s.length >= 6 && (s.contains("http") || s.contains("/") || s.contains(".") || s.any { it.isUpperCase() }) }
            buildString {
                appendLine("Archivo: ${f.name}  (${f.length()} bytes)")
                appendLine("Tipo real: ${BinaryTools.identify(f)}")
                appendLine("Entropía: ${"%.2f".format(ent)} bits/byte  → " + when {
                    ent > 7.6 -> "MUY ALTA: casi seguro cifrado o comprimido"
                    ent > 6.5 -> "alta: comprimido, multimedia o código nativo"
                    else -> "normal: texto/estructura legible"
                })
                CryptoTools.hashes(bytes).forEach { (k, v) -> appendLine("$k: $v") }
                appendLine("Cadenas: ${strs.size} (destacadas abajo)")
                interesting.take(120).forEach { appendLine("  $it") }
                appendLine("\nHex inicial:")
                append(BinaryTools.hexDump(f, 0, 256))
            }
        }
        "project_map" -> {
            val d = projectDir() ?: throw EngineException("No es un proyecto")
            if (a["refresh"] == true) File(d, ".apkforge/index.md").delete()
            ProjectIndex.build(d)
        }
        "find_related" -> {
            val d = projectDir() ?: throw EngineException("No es un proyecto")
            ContextFinder.format(ContextFinder.find(d, a["text"] as String, 40)).ifBlank { "No encontré archivos relacionados con eso." }
        }
        "list_changes" -> {
            val proj = (if (root.isDirectory) root else root.parentFile).let { d -> if (ApkProject.isProject(d)) d else ChangeLog.projectOf(File(d, "x")) }
                ?: throw EngineException("No es un proyecto: no hay informe de cambios")
            val entries = if (a["only_pending"] == true) ChangeLog.sinceLastExport(proj) else ChangeLog.all(proj)
            ChangeLog.report(proj, entries)
        }
        "decrypt" -> {
            val algo = a["algorithm"] as String
            val path = a["path"] as? String
            val data = path?.let { resolve(it).readBytes() }
            val r = CryptoTools.decode(algo, input = a["input"] as? String, dataBytes = data,
                key = a["key"] as? String, iv = a["iv"] as? String,
                keyEncoding = (a["key_encoding"] as? String) ?: "auto",
                ivEncoding = (a["iv_encoding"] as? String) ?: "auto")
            saveIfAsked(a["save_to"] as? String, r.bytes) +
                "Descifrado (${algo}, ${r.bytes.size} bytes). " +
                (if (r.looksText) "Texto:\n" + r.text.take(6000) else "Binario (${BinaryTools.identifyBytes(r.bytes)}). Hex:\n" + r.preview)
        }
        "auto_aes" -> {
            val key = CryptoTools.material(a["key"] as String, (a["key_encoding"] as? String) ?: "auto", setOf(16, 24, 32))
            val path = a["path"] as? String
            val data = path?.let { resolve(it).readBytes() }
                ?: (a["input"] as? String)?.let { CryptoTools.base64(it) ?: CryptoTools.hex(it) ?: it.toByteArray() }
                ?: throw EngineException("Falta 'input' o 'path'")
            val iv = (a["iv"] as? String)?.let { CryptoTools.material(it) }
            val results = CryptoTools.autoAes(data, key, iv)
            if (results.isEmpty()) "Ningún modo AES dio un resultado reconocible con esa clave."
            else {
                val best = results.first()
                saveIfAsked(a["save_to"] as? String, best.bytes) +
                    "Modos que funcionan: ${results.joinToString { it.label }}\n\n" +
                    results.take(3).joinToString("\n\n") { "== ${it.label} ==\n" + (if (it.looksText) it.text.take(2000) else it.preview) }
            }
        }
        "search_text" -> {
            val q = a["query"] as String
            val ext = (a["extension"] as? String).orEmpty()
            val rx = if (a["regex"] == true) Regex(q) else Regex(Regex.escape(q), RegexOption.IGNORE_CASE)
            val base = if (root.isDirectory) root else root.parentFile
            val out = StringBuilder(); var n = 0
            base.walkTopDown().onEnter { it.name != ".apkforge" }.filter { it.isFile && (ext.isEmpty() || it.extension == ext) && it.length() < 5_000_000 }
                .forEach { f ->
                    if (n >= 150) return@forEach
                    runCatching {
                        f.useLines { ls -> ls.forEachIndexed { i, l -> if (n < 150 && rx.containsMatchIn(l)) { out.append("${f.relativeTo(base)}:${i + 1}: ${l.trim().take(200)}\n"); n++ } } }
                    }
                }
            if (n == 0) "Sin resultados" else out.toString() + if (n >= 150) "[límite de 150 resultados]" else ""
        }
        "scan_findings" -> {
            val all = findingsCache ?: ProjectScanner.scan(apk ?: root).also { findingsCache = it }
            val kind = (a["kind"] as? String).orEmpty().uppercase()
            val list = if (kind.isEmpty()) all else all.filter { it.kind.name == kind }
            "${list.size} hallazgos" + (if (kind.isEmpty()) " ${all.groupingBy { it.kind }.eachCount()}" else "") + "\n" +
                list.take(300).joinToString("\n") { "[${it.kind}] ${it.value}  <= ${it.source}" }
        }
        "find_classes" -> {
            val j = java() ?: throw EngineException("No hay código Java disponible para este archivo")
            val r = j.searchClasses(a["query"] as String, 200)
            if (r.isEmpty()) "Sin clases" else r.joinToString("\n") { it.fullName }
        }
        "java_class" -> {
            val j = java() ?: throw EngineException("No hay código Java disponible para este archivo")
            j.javaCode(a["class_name"] as String)
        }
        "class_xref" -> {
            val j = java() ?: throw EngineException("No hay código Java disponible para este archivo")
            val c = a["class_name"] as String
            "Métodos:\n" + j.methods(c).joinToString("\n") { "  $it" } +
                "\n\nUsada en (${j.usedIn(c).size}):\n" + j.usedIn(c).take(80).joinToString("\n") { "  $it" } +
                "\n\nDepende de:\n" + j.dependencies(c).take(80).joinToString("\n") { "  $it" }
        }
        "binary_strings" -> {
            val f = fileArg(a["path"] as? String)
            val filter = (a["filter"] as? String).orEmpty()
            val s = if (f.extension == "dex") BinaryTools.dexStrings(f) else BinaryTools.strings(f)
            val list = if (filter.isEmpty()) s else s.filter { it.contains(filter, true) }
            "${BinaryTools.identify(f)} — ${list.size} cadenas\n" + list.take(400).joinToString("\n")
        }
        "hex_read" -> {
            val f = fileArg(a["path"] as? String)
            val ft = a["find_text"] as? String; val fh = a["find_hex"] as? String
            if (!ft.isNullOrEmpty() || !fh.isNullOrEmpty()) {
                val needle = if (!ft.isNullOrEmpty()) ft.toByteArray() else BinaryPatch.parseHex(fh!!)
                val offs = BinaryPatch.find(f, needle)
                if (offs.isEmpty()) "No aparece en ${f.name}" else "Aparece ${offs.size} vez/veces en: " + offs.joinToString { "0x%X".format(it) } +
                    "\n\n" + BinaryTools.hexDump(f, (offs.first() - 32).coerceAtLeast(0), 128)
            } else {
                val off = (a["offset"] as? Number)?.toLong() ?: 0L
                val len = ((a["length"] as? Number)?.toInt() ?: 256).coerceIn(1, 4096)
                "${BinaryTools.identify(f)} · ${f.length()} bytes\n" + BinaryTools.hexDump(f, off.coerceIn(0, f.length()), len)
            }
        }
        "semantic_search" -> {
            val p = projectDir() ?: throw EngineException("No es un proyecto")
            val q = a["query"] as String
            val hits = runCatching { ContextFinder.find(p, q, 40) }.getOrDefault(emptyList())
            NeuralBrain.think(p, q, hits).format().ifBlank { "El motor neuronal no encontró nada relacionado" }
        }
        "deobfuscate" -> {
            val p = projectDir() ?: throw EngineException("No es un proyecto")
            val rep = Deobfuscator.analyze(p)
            val filter = (a["filter"] as? String).orEmpty()
            val list = Deobfuscator.load(p).filter { filter.isEmpty() || it.name.contains(filter, true) || it.cls.contains(filter, true) }
                .sortedByDescending { it.conf }
            "Clases: ${rep.classes} · ofuscadas: ${rep.obfuscated} · nombres deducidos: ${rep.renamed} clases, ${rep.methods} métodos, ${rep.fields} campos\n" +
                list.filter { it.kind == "class" }.take(60).joinToString("\n") { "${it.cls} → ${it.name} (${(it.conf * 100).toInt()}% ${it.source}: ${it.evidence.take(70)})" } +
                "\nPendientes para ti (sin nombre fiable): " + Deobfuscator.pendingForAi(p, 15).joinToString { it.desc } +
                "\nUsa deobf_info para estudiarlas y rename_symbol para guardar los nombres."
        }
        "deobf_info" -> {
            val p = projectDir() ?: throw EngineException("No es un proyecto")
            val raw = (a["class_name"] as String).trim()
            val desc = if (raw.startsWith("L")) raw.removeSuffix(";") + ";" else "L" + raw.replace('.', '/') + ";"
            val f = File(p, "smali").walkTopDown().firstOrNull { it.isFile && it.path.endsWith(desc.removePrefix("L").removeSuffix(";") + ".smali") }
                ?: throw EngineException("No encuentro la clase $raw")
            val c = Deobfuscator.parse(f.readText()) ?: throw EngineException("No se pudo leer")
            Deobfuscator.summary(p, c, Deobfuscator.load(p).associateBy { it.key }) + "\nArchivo: ${f.relativeTo(p).path}"
        }
        "rename_symbol" -> {
            val p = projectDir() ?: throw EngineException("No es un proyecto")
            val r = Deobfuscator.rename(p, a["kind"] as String, a["class_name"] as String, (a["symbol"] as? String).orEmpty(),
                a["new_name"] as String, "IA", (a["reason"] as? String).orEmpty())
            "Guardado: ${r.cls} ${r.kind} ${r.obf} → ${r.name}"
        }
        "web_search" -> WebTools.searchFormatted(a["query"] as String)
        "web_fetch" -> {
            val url = a["url"] as String
            "Contenido de $url:\n" + runCatching { WebTools.fetch(url) }.getOrElse { "No se pudo leer: ${it.message}" }
        }
        "check_integrity" -> {
            val p = projectDir() ?: throw EngineException("No es un proyecto")
            IntegrityScanner.scan(p).explain()
        }
        "decrypt_strings" -> {
            val p = projectDir() ?: throw EngineException("No es un proyecto")
            val rep = StringDecryptor.run(p)
            val filter = (a["filter"] as? String).orEmpty()
            val list = StringDecryptor.load(p).filter { filter.isEmpty() || it.output.contains(filter, true) }
            val pend = StringDecryptor.findDecryptors(p).filter { it.id in rep.pending }
            "Descifradores: ${rep.decryptors} · textos descifrados: ${rep.decrypted}\n" +
                list.take(80).joinToString("\n") { "${it.file}:${it.line} → \"${it.output.take(120)}\" [${it.how}]" } +
                (if (pend.isEmpty()) "" else "\n\nPENDIENTES (deduce el algoritmo y usa decrypt_recipe):\n" + pend.take(5).joinToString("\n\n") { d ->
                    "${d.id} — ${d.sites.size} textos, p. ej.: " + d.sites.take(4).joinToString { s -> s.args.joinToString() } +
                        "\nLee su código con read_file en smali (clase ${d.cls})." })
        }
        "decrypt_recipe" -> {
            val p = projectDir() ?: throw EngineException("No es un proyecto")
            StringDecryptor.applyRecipeFromAi(p, a["decryptor"] as String, a["steps"] as String)
        }
        "find_lists" -> {
            val p = projectDir() ?: throw EngineException("No es un proyecto")
            val sites = LayoutPatcher.find(p)
            if (sites.isEmpty()) "No hay listas con LinearLayoutManager" else sites.take(60).joinToString("\n") {
                "${it.file.relativeTo(p).path}:${it.line} · ${it.owner}${if (it.method.isNotEmpty()) " · ${it.method}" else ""} (${it.kind})"
            } + "\nPista: identifica cuál es la pantalla buscando su adaptador o textos (search_text) antes de cambiarla."
        }
        "list_to_grid" -> {
            if (!allowEdits) throw EngineException("Edición desactivada")
            val p = projectDir() ?: throw EngineException("No es un proyecto")
            val want = (a["site"] as String).trim()
            val site = LayoutPatcher.find(p).firstOrNull { s -> "${s.file.relativeTo(p).path}:${s.line}" == want || want.endsWith("${s.file.name}:${s.line}") }
                ?: throw EngineException("No encuentro esa lista; usa find_lists")
            backup(site.file)
            LayoutPatcher.toGrid(p, site, (a["columns"] as? Number)?.toInt() ?: 2) +
                "\nSi las celdas quedan estrechas, ajusta el layout del elemento (p. ej. orientación vertical: imagen arriba y texto debajo)."
        }
        "hex_patch" -> {
            if (!allowEdits) throw EngineException("Edición desactivada")
            val f = fileArg(a["path"] as? String)
            val keep = a["keep_size"] != false
            val r = when {
                a["hex"] != null && a["offset"] != null -> BinaryPatch.writeAt(f, (a["offset"] as Number).toLong(), BinaryPatch.parseHex(a["hex"] as String), "IA")
                !(a["find_text"] as? String).isNullOrEmpty() -> BinaryPatch.replaceText(f, a["find_text"] as String, (a["replace_text"] as? String).orEmpty(), keep, "IA")
                !(a["find_hex"] as? String).isNullOrEmpty() -> BinaryPatch.replace(f, BinaryPatch.parseHex(a["find_hex"] as String),
                    BinaryPatch.parseHex((a["replace_hex"] as? String).orEmpty().ifBlank { "" }.let { if (it.isEmpty()) "00" else it }), keep, who = "IA")
                else -> throw EngineException("Indica offset+hex, find_text+replace_text o find_hex+replace_hex")
            }
            r.message + if (r.offsets.isNotEmpty()) " (" + r.offsets.take(10).joinToString { "0x%X".format(it) } + ")" else ""
        }
        "replace_all" -> {
            if (!allowEdits) throw EngineException("Edición desactivada")
            val base = if (root.isDirectory) root else root.parentFile
            val ext = (a["extension"] as? String).orEmpty()
            val rx = if (a["regex"] == true) Regex(a["find"] as String) else Regex(Regex.escape(a["find"] as String))
            val repl = a["replace"] as String
            val changed = mutableListOf<String>()
            base.walkTopDown().onEnter { it.name != ".apkforge" && it.name != "android-studio" }
                .filter { it.isFile && it.length() < 8_000_000 && (if (ext.isEmpty()) it.extension in setOf("smali", "xml", "json", "txt", "properties", "html", "js") else it.extension == ext) }
                .forEach { f ->
                    val old = f.readText()
                    val new = old.replace(rx, if (a["regex"] == true) repl else Regex.escapeReplacement(repl))
                    if (new != old) { backup(f); f.writeText(new); ChangeLog.record(f, old, new, "IA", "replace_all"); changed += f.relativeTo(base).path }
                }
            if (changed.isEmpty()) "Sin coincidencias" else "Cambiado en ${changed.size} archivo(s):\n" + changed.take(100).joinToString("\n")
        }
        "delete_file" -> {
            if (!allowEdits) throw EngineException("Edición desactivada")
            val f = resolve(a["path"] as String)
            if (!f.exists()) throw EngineException("No existe")
            if (f.isFile) backup(f) else f.walkTopDown().filter { it.isFile }.forEach { backup(it) }
            ChangeLog.recordBinary(f, "IA · borrado", 0, "IA")
            f.deleteRecursively(); "Borrado ${a["path"]}"
        }
        "move_file" -> {
            if (!allowEdits) throw EngineException("Edición desactivada")
            val from = resolve(a["from"] as String); val to = resolve(a["to"] as String)
            if (!from.exists()) throw EngineException("No existe: ${a["from"]}")
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) { from.copyRecursively(to, overwrite = true); from.deleteRecursively() }
            ChangeLog.recordBinary(to, "IA · movido desde ${a["from"]}", 0, "IA"); "Movido a ${a["to"]}"
        }
        "import_file" -> {
            if (!allowEdits) throw EngineException("Edición desactivada")
            val src = File(a["source"] as String)
            if (!src.isFile) throw EngineException("No existe en el móvil: ${src.path}")
            val to = resolve(a["to"] as String)
            if (to.isFile) backup(to)
            to.parentFile?.mkdirs(); src.copyTo(to, overwrite = true)
            ChangeLog.recordBinary(to, "IA · importado desde ${src.name}", src.length().toInt(), "IA"); "Importado ${src.name} → ${a["to"]} (${src.length()} B)"
        }
        "write_file" -> {
            if (!allowEdits) throw EngineException("Edición desactivada")
            val f = resolve(a["path"] as String)
            val before = if (f.isFile) f.readText() else null
            backup(f)
            f.parentFile?.mkdirs()
            f.writeText(a["content"] as String)
            ChangeLog.record(f, before, a["content"] as String, "IA", "write_file")
            "Escrito ${f.name} (${f.length()} B)" + xmlWarn(f)
        }
        "replace_lines" -> {
            if (!allowEdits) throw EngineException("Edición desactivada")
            val f = fileArg(a["path"] as? String)
            val text = f.readText()
            val lines = text.split("\n").toMutableList()
            val start = (a["start_line"] as Number).toInt()
            val end = (a["end_line"] as Number).toInt()
            if (start < 1 || end < start || start > lines.size)
                throw EngineException("Rango inválido: $start-$end (el archivo tiene ${lines.size} líneas). Lee con read_file para ver los números.")
            val to = end.coerceAtMost(lines.size)
            val newText = (a["new"] as? String).orEmpty()
            val newLines = if (newText.isEmpty()) emptyList() else newText.split("\n")
            val result = (lines.subList(0, start - 1) + newLines + lines.subList(to, lines.size)).joinToString("\n")
            backup(f); f.writeText(result)
            ChangeLog.record(f, text, result, "IA", "replace_lines $start-$to")
            "Reemplazadas las líneas $start-$to de ${f.name} (${to - start + 1} → ${newLines.size} líneas)" + xmlWarn(f)
        }
        "insert_lines" -> {
            if (!allowEdits) throw EngineException("Edición desactivada")
            val f = fileArg(a["path"] as? String)
            val text = f.readText()
            val lines = text.split("\n").toMutableList()
            val after = (a["after_line"] as Number).toInt().coerceIn(0, lines.size)
            val newLines = (a["new"] as String).split("\n")
            val result = (lines.subList(0, after) + newLines + lines.subList(after, lines.size)).joinToString("\n")
            backup(f); f.writeText(result)
            ChangeLog.record(f, text, result, "IA", "insert_lines @$after")
            "Insertadas ${newLines.size} línea(s) después de la línea $after en ${f.name}" + xmlWarn(f)
        }
        "replace_in_file" -> {
            if (!allowEdits) throw EngineException("Edición desactivada")
            val f = fileArg(a["path"] as? String)
            val text = f.readText()
            val old = a["old"] as String
            val new = a["new"] as String
            val exact = Regex(Regex.escape(old)).findAll(text).count()
            val after = when {
                exact == 1 -> text.replace(old, new)                               // coincidencia exacta
                exact > 1 -> throw EngineException("El fragmento aparece $exact veces; debe aparecer exactamente una. Incluye más líneas alrededor para que sea único.")
                else -> replaceByLines(text, old, new) ?: throw EngineException(noMatchHelp(text, old))
            }
            backup(f)
            f.writeText(after)
            ChangeLog.record(f, text, after, "IA", "replace_in_file")
            "Reemplazado en ${f.name}" + xmlWarn(f)
        }
        else -> throw EngineException("Herramienta desconocida: $name")
    }

    /** Entropía de Shannon (0–8): >7.5 suele indicar cifrado o compresión. */
    private fun entropy(b: ByteArray): Double {
        if (b.isEmpty()) return 0.0
        val counts = IntArray(256)
        b.forEach { counts[it.toInt() and 0xFF]++ }
        return counts.filter { it > 0 }.sumOf { val p = it.toDouble() / b.size; -p * (Math.log(p) / Math.log(2.0)) }
    }

    private fun looksTextFile(f: File): Boolean = runCatching {
        val buf = ByteArray(2048)
        val n = f.inputStream().use { it.read(buf) }
        n <= 0 || (0 until n).none { buf[it].toInt() == 0 }
    }.getOrDefault(false)

    private fun saveIfAsked(savePath: String?, bytes: ByteArray): String {
        if (savePath.isNullOrBlank()) return ""
        if (!allowEdits) return "[No guardado: activa la edición en Ajustes para escribir archivos]\n"
        val f = resolve(savePath)
        backup(f)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return "Guardado en ${f.name} (${bytes.size} bytes)\n"
    }

    /** Reemplazo tolerante a espacios: compara por líneas ignorando espacios finales/indentación extra. */
    private fun replaceByLines(text: String, old: String, new: String): String? {
        fun norm(l: String) = l.trim()
        val tl = text.split("\n"); val ol = old.trim('\n').split("\n")
        if (ol.isEmpty() || ol.all { it.isBlank() }) return null
        val tn = tl.map { norm(it) }; val on = ol.map { norm(it) }
        val starts = (0..tl.size - ol.size).filter { i -> (on.indices).all { tn[i + it] == on[it] } }
        if (starts.size != 1) return null
        val i = starts[0]
        // conserva la indentación real de la primera línea original para las nuevas líneas
        val indent = tl[i].takeWhile { it == ' ' || it == '\t' }
        val newLines = new.trim('\n').split("\n").mapIndexed { k, l -> if (k == 0 || l.isBlank()) l else if (l.startsWith(" ") || l.startsWith("\t")) l else indent + l }
        return (tl.subList(0, i) + newLines + tl.subList(i + ol.size, tl.size)).joinToString("\n")
    }

    /** Mensaje de ayuda cuando no se encuentra el fragmento: muestra la línea más parecida. */
    private fun noMatchHelp(text: String, old: String): String {
        val first = old.trim('\n').lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        val lines = text.split("\n")
        val near = lines.withIndex().filter { (_, l) -> first.length > 4 && l.contains(first.take(20)) }.take(3)
        return "El fragmento no aparece en el archivo (ni ignorando espacios). " +
            (if (near.isNotEmpty()) "¿Querías alguna de estas líneas?\n" + near.joinToString("\n") { "  ${it.index + 1}: ${it.value.trim()}" } +
                "\nUsa read_file para copiar el texto EXACTO antes de reemplazar."
             else "Usa read_file para ver el contenido actual y copiar el texto exacto.")
    }

    private fun backup(f: File) {
        if (!f.isFile) return
        val base = if (root.isDirectory) root else root.parentFile
        val dst = File(base, "$META_DIR/backup/${System.currentTimeMillis()}/${f.relativeTo(base).path}")
        dst.parentFile?.mkdirs()
        f.copyTo(dst, overwrite = true)
    }

    fun clip(s: String) = if (s.length <= 40_000) s else s.take(40_000) + "\n[… recortado: ${s.length} caracteres en total]"

    fun summarize(name: String, a: Map<String, Any?>) = when (name) {
        "read_file", "list_files", "binary_strings", "write_file", "replace_in_file", "analyze_file" -> "${a["path"] ?: "/"}"
        "list_changes" -> if (a["only_pending"] == true) "pendientes" else "todos"
        "find_related" -> "“${a["text"]}”"
        "project_map" -> ""
        "hex_read", "hex_patch", "delete_file" -> "${a["path"]}"
        "move_file" -> "${a["from"]} → ${a["to"]}"
        "import_file" -> "${a["source"]} → ${a["to"]}"
        "web_search" -> "“${a["query"]}”"
        "web_fetch" -> "${a["url"]}"
        "semantic_search" -> "“${a["query"]}”"
        "decrypt_recipe" -> "${(a["decryptor"] as? String)?.substringAfterLast('/')}"
        "rename_symbol" -> "${a["class_name"]} ${a["symbol"] ?: ""} → ${a["new_name"]}"
        "deobf_info" -> "${a["class_name"]}"
        "replace_lines" -> "${a["path"]} líneas ${a["start_line"]}-${a["end_line"]}"
        "insert_lines" -> "${a["path"]} tras línea ${a["after_line"]}"
        "list_to_grid" -> "${a["site"]} → ${a["columns"] ?: 2} columnas"
        "replace_all" -> "“${a["find"]}” → “${a["replace"]}”"
        "search_text" -> "“${a["query"]}”" + ((a["extension"] as? String)?.takeIf { it.isNotEmpty() }?.let { " en .$it" } ?: "")
        "java_class", "class_xref" -> "${a["class_name"]}"
        "find_classes" -> "“${a["query"]}”"
        "scan_findings" -> (a["kind"] as? String)?.ifEmpty { "todo" } ?: "todo"
        else -> ""
    }

    private fun str(d: String) = mapOf("type" to "string", "description" to d)
    private fun int(d: String) = mapOf("type" to "integer", "description" to d)
    private fun bool(d: String) = mapOf("type" to "boolean", "description" to d)

    private fun tool(name: String, desc: String, props: Map<String, Map<String, String>>, required: List<String>) =
        ToolSpec(name, desc, props, required)
}
