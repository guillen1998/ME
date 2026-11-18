package dev.apkforge.engine

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams
import java.io.File

/** Agentes especializados: cada uno es un objetivo distinto con las mismas herramientas. */
enum class AgentPreset(val title: String, val description: String, val goal: String) {
    CHAT(
        "Asistente libre", "Pregúntale lo que quieras sobre la app",
        "Responde lo que el usuario pida sobre esta app usando las herramientas para mirar el código real.",
    ),
    EXPLAIN(
        "Explicador", "Qué hace la app, sus pantallas y flujos principales",
        "Explica qué hace esta app: punto de entrada (Application, actividad principal), pantallas, " +
            "flujos principales, librerías que usa y cómo se conectan las partes. Cita clases y archivos concretos.",
    ),
    NETWORK(
        "Mapa de red y APIs", "Servidores, endpoints, URLs y cómo se llaman",
        "Mapea toda la comunicación de red: servidores, endpoints, parámetros, cabeceras, librería HTTP " +
            "(Retrofit/OkHttp/Volley…) y qué clase llama a cada endpoint. Usa scan_findings y busca en el código.",
    ),
    DEOBFUSCATE(
        "Desofuscador", "Propone nombres reales para clases y métodos ofuscados",
        "La app está ofuscada. Lee las clases ofuscadas (a, b, c…) y deduce por su comportamiento, cadenas y " +
            "llamadas a APIs de Android qué hace cada una. Devuelve una tabla 'nombre ofuscado → nombre propuesto " +
            "→ por qué', empezando por las clases más usadas (mira class_xref).",
    ),
    SECURITY(
        "Auditor de seguridad", "Secretos, cripto débil, almacenamiento y red inseguros",
        "Audita la seguridad: secretos/claves en el código, criptografía débil, tráfico sin TLS, " +
            "WebViews peligrosas, componentes exportados, almacenamiento inseguro, logs con datos sensibles. " +
            "Para cada hallazgo da archivo/clase, gravedad y cómo corregirlo.",
    ),
    MODDER(
        "Editor / modder", "Hace los cambios que le pidas en smali y XML",
        "Haz en el proyecto los cambios que el usuario pida (textos, colores, layouts, lógica en smali). " +
            "Antes de editar, lee el archivo; haz cambios mínimos; explica qué cambiaste y en qué archivos. " +
            "Recuerda que luego hay que compilar el proyecto.",
    ),
}

data class AgentConfig(
    val apiKey: String,
    val model: String = "claude-opus-5-5",
    val effort: BetaOutputConfig.Effort = BetaOutputConfig.Effort.HIGH,
    val allowEdits: Boolean = false,
    val maxTurns: Int = 40,
    /** Solo para pruebas (servidor simulado). */
    val baseUrl: String? = null,
)

sealed class AgentEvent {
    data class Text(val text: String) : AgentEvent()
    data class ToolCall(val tool: String, val summary: String) : AgentEvent()
    data class ToolResult(val tool: String, val preview: String, val isError: Boolean) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class Done(val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long) : AgentEvent()
}

/**
 * Agente de IA (Claude) que trabaja sobre el proyecto/APK con herramientas que se ejecutan EN EL
 * MÓVIL: listar y leer archivos, buscar texto, escaneo automático, ver Java de clases, referencias
 * cruzadas, cadenas de binarios y (si lo permites) editar archivos con copia de seguridad.
 *
 * Bucle de tool use manual sobre la API de Mensajes, con caché de prompt y fallback del servidor.
 */
class ForgeAgent(
    private val root: File,
    private val apk: File?,
    private val config: AgentConfig,
    private val preset: AgentPreset,
    private val java: () -> JavaDecompiler?,
    private val onEvent: (AgentEvent) -> Unit,
) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(config.apiKey)
        .apply { config.baseUrl?.let { baseUrl(it) } }.build()
    private val history = mutableListOf<BetaMessageParam>()
    private var findingsCache: List<Finding>? = null

    private val system = """
        Eres un experto en ingeniería inversa de Android integrado en ApkForge, una app móvil.
        Trabajas sobre ${if (root.isDirectory) "el proyecto decompilado en '${root.name}'" else "el archivo '${root.name}'"}${apk?.let { " (APK original: ${it.name})" } ?: ""}.
        Estructura típica del proyecto: AndroidManifest.xml, resources/package_*/res (XML), smali/classesN/ (código smali), root/ (assets, lib/*.so).

        Tu tarea en esta sesión: ${preset.goal}

        Reglas:
        - Basa todo en lo que veas con las herramientas; no inventes clases ni archivos. Cita rutas y nombres.
        - Para leer lógica prefiere java_class (Java legible) y usa smali solo cuando haga falta editar.
        - Sé eficiente: busca primero (search_text, find_classes, scan_findings) y lee solo lo necesario.
        - ${if (config.allowEdits) "Puedes editar archivos con write_file/replace_in_file (se guarda copia de seguridad)." else "Edición DESACTIVADA: si hace falta cambiar algo, explica el cambio exacto en vez de hacerlo."}
        - Trabaja solo en apps propias, de código abierto o con permiso; no ayudes a saltarse pagos, licencias o DRM de apps comerciales de terceros.
        - Responde en español, claro y organizado.
    """.trimIndent()

    private val tools: List<BetaTool> = buildList {
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
        if (config.allowEdits) {
            add(tool("write_file", "Crea o reemplaza un archivo de texto completo (guarda copia de seguridad).",
                mapOf("path" to str("Ruta relativa"), "content" to str("Contenido completo")), listOf("path", "content")))
            add(tool("replace_in_file", "Reemplaza un fragmento exacto (que aparece una sola vez) dentro de un archivo.",
                mapOf("path" to str("Ruta relativa"), "old" to str("Texto exacto a reemplazar"), "new" to str("Texto nuevo")), listOf("path", "old", "new")))
        }
    }

    /** Envía un mensaje del usuario y ejecuta el bucle del agente hasta que termine. */
    fun send(userText: String, isCancelled: () -> Boolean = { false }) {
        history += BetaMessageParam.ofUser(userText)
        var inTok = 0L; var outTok = 0L; var cached = 0L
        try {
            for (turn in 0 until config.maxTurns) {
                if (isCancelled()) { onEvent(AgentEvent.Error("Detenido")); return }
                val builder = MessageCreateParams.builder()
                tools.forEach { builder.addTool(it) }
                val params = builder
                    .model(config.model)
                    .maxTokens(16000L)
                    .system(system)
                    .messages(history)
                    .outputConfig(BetaOutputConfig.builder().effort(config.effort).build())
                    // Caché automática: herramientas + system + historial se reutilizan entre turnos.
                    .cacheControl(BetaCacheControlEphemeral.builder().build())
                    // Si el modelo rechaza algo por un falso positivo, el servidor prueba con otro modelo.
                    .addBeta("server-side-fallback-2026-07-01")
                    .fallbacks(BetaFallbacksParam.ofDefault())
                    .build()

                val response = client.beta().messages().create(params)
                response.usage().let {
                    inTok += it.inputTokens(); outTok += it.outputTokens()
                    cached += it.cacheReadInputTokens().orElse(0L)
                }
                history += response.toParam()

                response.content().forEach { b -> b.text().ifPresent { if (it.text().isNotBlank()) onEvent(AgentEvent.Text(it.text())) } }

                when (response.stopReason().orElse(null)) {
                    BetaStopReason.TOOL_USE -> {
                        val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { use ->
                            val input: Map<String, Any?> = runCatching {
                                @Suppress("UNCHECKED_CAST")
                                use._input().convert(Map::class.java) as Map<String, Any?>
                            }.getOrDefault(emptyMap())
                            onEvent(AgentEvent.ToolCall(use.name(), summarize(use.name(), input)))
                            val (out, err) = try {
                                runTool(use.name(), input) to false
                            } catch (t: Throwable) {
                                "Error: ${describe(t)}" to true
                            }
                            onEvent(AgentEvent.ToolResult(use.name(), out.take(300), err))
                            BetaContentBlockParam.ofToolResult(
                                BetaToolResultBlockParam.builder()
                                    .toolUseId(use.id()).content(clip(out)).isError(err).build()
                            )
                        }
                        // Todos los resultados en un único mensaje (permite llamadas en paralelo).
                        history += BetaMessageParam.ofUserOfContentBlockParams(results)
                    }
                    BetaStopReason.REFUSAL -> {
                        onEvent(AgentEvent.Error("El modelo no quiso continuar con esta petición."))
                        break
                    }
                    BetaStopReason.MAX_TOKENS -> {
                        onEvent(AgentEvent.Error("Respuesta cortada por longitud. Pide que continúe."))
                        break
                    }
                    else -> break // END_TURN y demás: terminado
                }
                if (turn == config.maxTurns - 1) onEvent(AgentEvent.Error("Límite de pasos alcanzado. Pide que continúe."))
            }
        } catch (e: UnauthorizedException) {
            onEvent(AgentEvent.Error("Clave de API no válida. Revísala en Ajustes."))
        } catch (e: PermissionDeniedException) {
            onEvent(AgentEvent.Error("La clave no tiene permiso para este modelo."))
        } catch (e: RateLimitException) {
            onEvent(AgentEvent.Error("Límite de uso alcanzado; espera un momento y reintenta."))
        } catch (e: OverloadedException) {
            onEvent(AgentEvent.Error("El servicio está saturado; reintenta en unos segundos."))
        } catch (e: AnthropicServiceException) {
            onEvent(AgentEvent.Error("Error del servicio (${e.statusCode()}): ${e.message}"))
        } catch (e: AnthropicIoException) {
            onEvent(AgentEvent.Error("Sin conexión: ${e.message}"))
        }
        onEvent(AgentEvent.Done(inTok, outTok, cached))
    }

    fun reset() = history.clear()

    // ---------------- Herramientas (se ejecutan en el móvil) ----------------

    private fun resolve(path: String?): File {
        val base = if (root.isDirectory) root else root.parentFile
        val f = File(base, (path ?: "").trimStart('/')).canonicalFile
        if (!f.path.startsWith(base.canonicalPath)) throw EngineException("Ruta fuera del proyecto: $path")
        return f
    }

    private fun runTool(name: String, a: Map<String, Any?>): String = when (name) {
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
            val f = resolve(a["path"] as String)
            val filter = (a["filter"] as? String).orEmpty()
            val s = if (f.extension == "dex") BinaryTools.dexStrings(f) else BinaryTools.strings(f)
            val list = if (filter.isEmpty()) s else s.filter { it.contains(filter, true) }
            "${BinaryTools.identify(f)} — ${list.size} cadenas\n" + list.take(400).joinToString("\n")
        }
        "write_file" -> {
            if (!config.allowEdits) throw EngineException("Edición desactivada")
            val f = resolve(a["path"] as String)
            backup(f)
            f.parentFile?.mkdirs()
            f.writeText(a["content"] as String)
            "Escrito ${f.name} (${f.length()} B)"
        }
        "replace_in_file" -> {
            if (!config.allowEdits) throw EngineException("Edición desactivada")
            val f = resolve(a["path"] as String)
            val text = f.readText()
            val old = a["old"] as String
            val count = Regex(Regex.escape(old)).findAll(text).count()
            if (count != 1) throw EngineException("El fragmento aparece $count veces; debe aparecer exactamente una")
            backup(f)
            f.writeText(text.replace(old, a["new"] as String))
            "Reemplazado en ${f.name}"
        }
        else -> throw EngineException("Herramienta desconocida: $name")
    }

    private fun looksTextFile(f: File): Boolean = runCatching {
        val buf = ByteArray(2048)
        val n = f.inputStream().use { it.read(buf) }
        n <= 0 || (0 until n).none { buf[it].toInt() == 0 }
    }.getOrDefault(false)

    private fun saveIfAsked(savePath: String?, bytes: ByteArray): String {
        if (savePath.isNullOrBlank()) return ""
        if (!config.allowEdits) return "[No guardado: activa la edición en Ajustes para escribir archivos]\n"
        val f = resolve(savePath)
        backup(f)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return "Guardado en ${f.name} (${bytes.size} bytes)\n"
    }

    private fun backup(f: File) {
        if (!f.isFile) return
        val base = if (root.isDirectory) root else root.parentFile
        val dst = File(base, "$META_DIR/backup/${System.currentTimeMillis()}/${f.relativeTo(base).path}")
        dst.parentFile?.mkdirs()
        f.copyTo(dst, overwrite = true)
    }

    private fun clip(s: String) = if (s.length <= 40_000) s else s.take(40_000) + "\n[… recortado: ${s.length} caracteres en total]"

    private fun summarize(name: String, a: Map<String, Any?>) = when (name) {
        "read_file", "list_files", "binary_strings", "write_file", "replace_in_file" -> "${a["path"] ?: "/"}"
        "search_text" -> "“${a["query"]}”" + ((a["extension"] as? String)?.takeIf { it.isNotEmpty() }?.let { " en .$it" } ?: "")
        "java_class", "class_xref" -> "${a["class_name"]}"
        "find_classes" -> "“${a["query"]}”"
        "scan_findings" -> (a["kind"] as? String)?.ifEmpty { "todo" } ?: "todo"
        else -> ""
    }

    private fun str(d: String) = mapOf("type" to "string", "description" to d)
    private fun int(d: String) = mapOf("type" to "integer", "description" to d)
    private fun bool(d: String) = mapOf("type" to "boolean", "description" to d)

    private fun tool(name: String, desc: String, props: Map<String, Map<String, String>>, required: List<String>): BetaTool {
        val p = BetaTool.InputSchema.Properties.builder()
        props.forEach { (k, v) -> p.putAdditionalProperty(k, JsonValue.from(v)) }
        return BetaTool.builder()
            .name(name)
            .description(desc)
            .inputSchema(BetaTool.InputSchema.builder().properties(p.build()).required(required).build())
            .build()
    }
}
