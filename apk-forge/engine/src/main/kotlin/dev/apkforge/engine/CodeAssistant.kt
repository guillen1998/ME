package dev.apkforge.engine

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** Una llamada de texto a la IA (sin herramientas), para cualquier proveedor. */
interface AiTextClient {
    fun complete(system: String, user: String): String
}

/** Claude con streaming (respuestas largas, p. ej. reescribir un archivo entero, sin cortes por tiempo). */
class ClaudeTextClient(
    apiKey: String,
    private val model: String,
    private val effort: BetaOutputConfig.Effort = BetaOutputConfig.Effort.HIGH,
    baseUrl: String? = null,
) : AiTextClient {
    private val client = AnthropicOkHttpClient.builder().apiKey(apiKey).apply { baseUrl?.let { baseUrl(it) } }.build()

    override fun complete(system: String, user: String): String {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(64000L)
            .system(system)
            .addUserMessage(user)
            .outputConfig(BetaOutputConfig.builder().effort(effort).build())
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacks(BetaFallbacksParam.ofDefault())
            .build()
        val acc = BetaMessageAccumulator.create()
        client.beta().messages().createStreaming(params).use { stream -> stream.stream().forEach { acc.accumulate(it) } }
        val msg = acc.message()
        if (msg.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw EngineException("El modelo no quiso hacer este cambio.")
        return msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
    }
}

/** Cualquier API compatible con OpenAI (IA integrada, OpenRouter, Groq…), con cambio automático de servicio. */
class OpenAiTextClient(private val endpoints: List<AiEndpoint>) : AiTextClient {
    constructor(baseUrl: String, apiKey: String, model: String) : this(listOf(AiEndpoint(baseUrl, apiKey, model, model)))
    @Volatile private var current = 0

    override fun complete(system: String, user: String): String {
        val body = JsonObject().apply {
            add("messages", Gson().toJsonTree(listOf(mapOf("role" to "system", "content" to system), mapOf("role" to "user", "content" to user))))
            addProperty("max_tokens", 16000)
        }
        val errors = LinkedHashMap<String, String>()
        for (round in 0 until 4) {
            for (attempt in endpoints.indices) {
                val i = (current + attempt) % endpoints.size
                try {
                    val json = OpenAiHttp.chat(endpoints[i], body)
                    current = i
                    val msg = json.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
                    val text = msg.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                    if (text.isNotBlank()) return text
                    errors[endpoints[i].label] = "${endpoints[i].label}: respuesta vacía"
                } catch (e: OpenAiHttp.RetryableException) { errors[endpoints[i].label] = e.message ?: "error" }
            }
            if (round < 3) Thread.sleep(OpenAiHttp.waitSeconds(errors.values, round) * 1000L)
        }
        throw EngineException("Ningún servicio de IA respondió:\n" + errors.values.joinToString("\n") { "• $it" })
    }
}

/** Resultado de una edición con IA: código nuevo + diff para revisarlo antes de aplicar. */
data class AiEdit(val newCode: String, val diff: String, val added: Int, val removed: Int, val notes: String, val validated: Boolean?)

/**
 * Asistente de código del editor ("Modo IA"): modifica, crea, parchea o quita código según una
 * instrucción en lenguaje natural. Con smali lo ensambla para comprobar que es válido y, si
 * falla, le devuelve los errores a la IA para que lo corrija sola.
 */
object CodeAssistant {

    private fun langOf(fileName: String) = when (fileName.substringAfterLast('.', "").lowercase()) {
        "smali" -> "smali (bytecode Dalvik)"; "xml" -> "XML de Android"; "java" -> "Java"; "kt" -> "Kotlin"
        "json" -> "JSON"; "js" -> "JavaScript"; else -> "texto"
    }

    private fun system(fileName: String) = """
        Eres un experto en ingeniería inversa y modding de Android dentro de ApkForge.
        Editas código ${langOf(fileName)} según lo que pida el usuario: reemplazar, parchear, quitar o añadir funciones, crear código nuevo.
        Reglas:
        - Devuelve el código COMPLETO resultante dentro de UN único bloque ``` ``` (sin omitir partes con "..." ni comentarios tipo "resto igual").
        - Después del bloque, en 1-4 líneas, explica qué cambiaste.
        - Cambios mínimos y precisos; conserva todo lo demás exactamente igual (formato, nombres, orden).
        - Smali: mantén .locals/.registers suficientes para los registros que uses, tipos correctos (Lpaquete/Clase;), etiquetas únicas, y que cada método termine en return/throw/goto.
        - XML de Android: conserva los namespaces y atributos android: válidos.
        - Trabaja solo en apps propias, de código abierto o con permiso; no ayudes a saltarse pagos, licencias o DRM de apps comerciales de terceros.
        - Responde en español.
    """.trimIndent()

    /** Edita [code] (o solo un fragmento seleccionado) siguiendo [instruction]. */
    fun edit(
        client: AiTextClient,
        code: String,
        fileName: String,
        instruction: String,
        isFragment: Boolean = false,
        listener: ProgressListener = ProgressListener.NONE,
    ): AiEdit {
        val what = if (isFragment) "este FRAGMENTO de $fileName (devuelve solo el fragmento modificado)" else "el archivo $fileName"
        listener.onProgress("La IA está editando…")
        var answer = client.complete(system(fileName), "Instrucción: $instruction\n\nCódigo de $what:\n```\n$code\n```")
        var newCode = extractCode(answer) ?: throw EngineException("La IA no devolvió código. Respuesta:\n${answer.take(500)}")
        var validated: Boolean? = null

        // Validación automática de smali (archivo completo): ensamblar y, si falla, pedir corrección.
        if (fileName.endsWith(".smali", true) && !isFragment) {
            var errors = validateSmali(newCode)
            var tries = 0
            while (errors != null && tries < 2) {
                tries++
                listener.onProgress("El smali no ensambla; la IA lo corrige (intento $tries)…")
                answer = client.complete(system(fileName),
                    "Tu versión anterior tiene errores de smali:\n$errors\n\nCorrígelos manteniendo el cambio pedido ($instruction). Código:\n```\n$newCode\n```")
                newCode = extractCode(answer) ?: break
                errors = validateSmali(newCode)
            }
            validated = errors == null
            if (errors != null) listener.onProgress("⚠ Aún tiene errores: revisa antes de aplicar")
        }
        val notes = answer.substringAfterLast("```").trim().take(800)
        val (diff, add, rem) = diff(code, newCode, fileName)
        return AiEdit(newCode, diff, add, rem, notes, validated)
    }

    /** Explica/analiza código sin modificarlo (qué hace, qué devuelve, cómo parchearlo). */
    fun explain(client: AiTextClient, code: String, fileName: String, question: String): String =
        client.complete(
            "Eres un experto en ingeniería inversa de Android. Analiza código ${langOf(fileName)} y responde en español, claro y concreto: " +
                "qué hace, qué devuelve cada función, qué llama, y si se pide, cómo modificarlo (con el código exacto).",
            "Pregunta: ${question.ifBlank { "¿Qué hace este código?" }}\n\nCódigo ($fileName):\n```\n$code\n```",
        )

    fun extractCode(answer: String): String? {
        val blocks = Regex("```[a-zA-Z0-9_+-]*\\n([\\s\\S]*?)```").findAll(answer).map { it.groupValues[1] }.toList()
        return blocks.maxByOrNull { it.length }?.trimEnd('\n')
    }

    /** null si el smali ensambla bien; si no, los errores. */
    fun validateSmali(code: String): String? {
        val dir = File.createTempFile("aivalid", "").apply { delete(); mkdirs() }
        return try {
            val src = File(dir, "C.smali").apply { writeText(code) }
            val opts = SmaliOptions().apply { apiLevel = 30; outputDexFile = File(dir, "o.dex").absolutePath; jobs = 1 }
            val (ok, err) = captureStderr { Smali.assemble(opts, listOf(src.absolutePath)) }
            if (!ok) err.replace(src.absolutePath, "línea").ifBlank { "Error de ensamblado" }
            else lintRegisters(code)
        } catch (t: Throwable) {
            describe(t)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * El ensamblador no comprueba que vN quepa en los registros del método, pero el verificador de
     * Android sí (la app se cierra al arrancar). Aquí lo detectamos antes.
     */
    fun lintRegisters(code: String): String? {
        val errors = mutableListOf<String>()
        var inMethod = false; var skip = false
        var name = ""; var paramRegs = 0; var locals: Int? = null; var registers: Int? = null
        var maxV = -1; var maxP = -1
        for (raw in code.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith(".method ")) {
                inMethod = true; locals = null; registers = null; maxV = -1; maxP = -1
                val decl = line.removePrefix(".method ")
                skip = " abstract " in " $decl " || " native " in " $decl "
                val sig = decl.substringAfterLast(' ')
                name = sig.substringBefore('(')
                paramRegs = (if (" static " in " $decl ") 0 else 1) + paramRegisters(sig.substringAfter('(').substringBefore(')'))
                continue
            }
            if (!inMethod) continue
            if (line.startsWith(".end method")) {
                if (!skip) {
                    val localCount = locals ?: registers?.minus(paramRegs)
                    if (localCount != null) {
                        if (maxV >= localCount) errors += "Método $name: usa v$maxV pero solo hay $localCount registro(s) locales (.locals $localCount). Sube .locals a ${maxV + 1}."
                        if (maxP >= paramRegs) errors += "Método $name: usa p$maxP pero solo tiene $paramRegs registro(s) de parámetros."
                    }
                }
                inMethod = false; continue
            }
            if (line.startsWith(".locals ")) { locals = line.substringAfter(' ').trim().toIntOrNull(); continue }
            if (line.startsWith(".registers ")) { registers = line.substringAfter(' ').trim().toIntOrNull(); continue }
            if (line.isEmpty() || line[0] == '#' || line[0] == ':' ) continue
            // Escanear tokens fuera de cadenas, comentarios y tipos.
            scanRegisters(line) { kind, n -> if (kind == 'v') { if (n > maxV) maxV = n } else if (n > maxP) maxP = n }
        }
        return errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun paramRegisters(d: String): Int {
        var n = 0; var i = 0
        while (i < d.length) {
            when (d[i]) {
                'J', 'D' -> { n += 2; i++ }
                'L' -> { n++; i = d.indexOf(';', i).let { if (it < 0) d.length else it + 1 } }
                '[' -> { while (i < d.length && d[i] == '[') i++; if (i < d.length && d[i] == 'L') i = d.indexOf(';', i).let { if (it < 0) d.length else it + 1 } else i++; n++ }
                else -> { n++; i++ }
            }
        }
        return n
    }

    private inline fun scanRegisters(line: String, onReg: (Char, Int) -> Unit) {
        var i = 0; val len = line.length
        while (i < len) {
            val c = line[i]
            when {
                c == '"' -> { i++; while (i < len && line[i] != '"') { if (line[i] == '\\') i++; i++ }; i++ }
                c == '#' -> return
                c == 'L' && (i == 0 || !line[i - 1].isLetterOrDigit()) && line.indexOf(';', i) > 0 &&
                    line.substring(i, line.indexOf(';', i)).none { it == ' ' || it == ',' } -> i = line.indexOf(';', i) + 1
                (c == 'v' || c == 'p') && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '{' || line[i - 1] == ',') -> {
                    var j = i + 1; var n = 0; var digits = 0
                    while (j < len && line[j].isDigit()) { n = n * 10 + (line[j] - '0'); j++; digits++ }
                    if (digits > 0 && (j == len || line[j] == ',' || line[j] == ' ' || line[j] == '}')) onReg(c, n)
                    i = j
                }
                else -> i++
            }
        }
    }

    /** Diff unificado + líneas añadidas/quitadas. */
    fun diff(old: String, new: String, name: String = "archivo"): Triple<String, Int, Int> {
        val a = old.lines(); val b = new.lines()
        val patch = DiffUtils.diff(a, b)
        var add = 0; var rem = 0
        patch.deltas.forEach { add += it.target.lines.size; rem += it.source.lines.size }
        val unified = UnifiedDiffUtils.generateUnifiedDiff(name, name, a, patch, 3).drop(2).joinToString("\n")
        return Triple(unified, add, rem)
    }
}
