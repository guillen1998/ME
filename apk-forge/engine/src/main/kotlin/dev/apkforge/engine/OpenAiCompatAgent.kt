package dev.apkforge.engine

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Interfaz común de los agentes (Claude u otros modelos). */
interface AiAgent {
    fun send(userText: String, isCancelled: () -> Boolean = { false })
    fun reset()
}

/** Proveedores de IA. Los compatibles con OpenAI dan acceso a modelos de código abierto. */
enum class AiProvider(
    val title: String,
    val baseUrl: String,
    val defaultModel: String,
    val needsKey: Boolean,
    val help: String,
) {
    CLAUDE("Claude (Anthropic)", "", "claude-opus-5-5", true,
        "Clave en console.anthropic.com. El más capaz."),
    OPENROUTER("OpenRouter · modelos abiertos gratis", "https://openrouter.ai/api/v1", "openrouter/free", true,
        "Clave gratis en openrouter.ai. 'openrouter/free' elige un modelo abierto gratis con herramientas (Qwen, Llama, DeepSeek…)."),
    GROQ("Groq · Llama muy rápido", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", true,
        "Clave gratis en console.groq.com. Solo modelos abiertos."),
    OLLAMA("Ollama · en tu PC, sin clave", "http://192.168.1.10:11434/v1", "qwen2.5-coder:7b", false,
        "Instala Ollama en tu PC, 'ollama pull qwen2.5-coder:7b', y pon aquí la IP del PC. Todo local y gratis."),
    CUSTOM("Personalizado (API compatible OpenAI)", "", "", false,
        "Cualquier servidor con /v1/chat/completions y tool calling (LM Studio, vLLM, LocalAI…)."),
}

/**
 * Agente sobre cualquier API compatible con OpenAI (chat/completions + tool calling): OpenRouter,
 * Groq, Ollama, LM Studio… Usa exactamente las mismas herramientas que el agente de Claude.
 */
class OpenAiCompatAgent(
    root: File,
    apk: File?,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    preset: AgentPreset,
    allowEdits: Boolean,
    java: () -> JavaDecompiler?,
    private val onEvent: (AgentEvent) -> Unit,
    private val maxTurns: Int = 30,
) : AiAgent {
    private val toolbox = AgentToolbox(root, apk, allowEdits, java)
    private val gson = Gson()
    private val messages = JsonArray().apply {
        add(JsonObject().apply { addProperty("role", "system"); addProperty("content", toolbox.systemPrompt(preset)) })
    }

    private val toolsJson: JsonArray = JsonArray().apply {
        toolbox.specs.forEach { s ->
            add(JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", s.name)
                    addProperty("description", s.description)
                    add("parameters", JsonObject().apply {
                        addProperty("type", "object")
                        add("properties", gson.toJsonTree(s.props))
                        add("required", gson.toJsonTree(s.required))
                    })
                })
            })
        }
    }

    override fun reset() {
        while (messages.size() > 1) messages.remove(messages.size() - 1)
    }

    override fun send(userText: String, isCancelled: () -> Boolean) {
        messages.add(JsonObject().apply { addProperty("role", "user"); addProperty("content", userText) })
        var inTok = 0L; var outTok = 0L
        try {
            for (turn in 0 until maxTurns) {
                if (isCancelled()) { onEvent(AgentEvent.Error("Detenido")); break }
                val body = JsonObject().apply {
                    addProperty("model", model)
                    add("messages", messages)
                    add("tools", toolsJson)
                    addProperty("tool_choice", "auto")
                    addProperty("max_tokens", 8000)
                }
                val resp = post(body)
                resp.getAsJsonObject("usage")?.let {
                    inTok += it.get("prompt_tokens")?.asLong ?: 0
                    outTok += it.get("completion_tokens")?.asLong ?: 0
                }
                val choice = resp.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?: throw EngineException("Respuesta vacía del modelo")
                val msg = choice.getAsJsonObject("message")
                val text = msg.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (text.isNotBlank()) onEvent(AgentEvent.Text(text.trim()))

                val calls = msg.getAsJsonArray("tool_calls")
                // Guardamos el mensaje del asistente tal cual (con tool_calls) para el siguiente turno.
                messages.add(JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", text)
                    if (calls != null && calls.size() > 0) add("tool_calls", calls)
                })
                if (calls == null || calls.size() == 0) {
                    if (choice.get("finish_reason")?.asString == "length") onEvent(AgentEvent.Error("Respuesta cortada por longitud. Pide que continúe."))
                    break
                }
                for (c in calls) {
                    val call = c.asJsonObject
                    val fn = call.getAsJsonObject("function")
                    val name = fn.get("name").asString
                    @Suppress("UNCHECKED_CAST")
                    val args: Map<String, Any?> = runCatching {
                        val raw = fn.get("arguments")
                        val json = if (raw.isJsonPrimitive) JsonParser.parseString(raw.asString) else raw
                        gson.fromJson(json, Map::class.java) as Map<String, Any?>
                    }.getOrDefault(emptyMap())
                    onEvent(AgentEvent.ToolCall(name, toolbox.summarize(name, args)))
                    val (out, err) = try {
                        toolbox.run(name, normalizeNumbers(args)) to false
                    } catch (t: Throwable) {
                        "Error: ${describe(t)}" to true
                    }
                    onEvent(AgentEvent.ToolResult(name, out.take(300), err))
                    messages.add(JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", call.get("id")?.asString ?: name)
                        addProperty("content", toolbox.clip(out))
                    })
                }
                if (turn == maxTurns - 1) onEvent(AgentEvent.Error("Límite de pasos alcanzado. Pide que continúe."))
            }
        } catch (t: Throwable) {
            onEvent(AgentEvent.Error(describe(t)))
        }
        onEvent(AgentEvent.Done(inTok, outTok, 0))
    }

    /** Gson lee los números como Double; las herramientas esperan Number, así que vale tal cual. */
    private fun normalizeNumbers(m: Map<String, Any?>) = m

    private fun post(body: JsonObject): JsonObject {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 240_000
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("X-Title", "ApkForge")
        }
        try {
            conn.outputStream.use { it.write(gson.toJson(body).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching {
                    JsonParser.parseString(text).asJsonObject.getAsJsonObject("error")?.get("message")?.asString
                }.getOrNull() ?: text.take(300)
                throw EngineException(
                    when (code) {
                        401, 403 -> "Clave de API no válida o sin permiso ($code). Revísala en Ajustes."
                        404 -> "Modelo no encontrado o sin soporte de herramientas: $detail"
                        429 -> "Límite gratuito alcanzado; espera un poco o prueba otro modelo. ($detail)"
                        else -> "Error $code del proveedor: $detail"
                    }
                )
            }
            return JsonParser.parseString(text).asJsonObject
        } catch (e: java.net.ConnectException) {
            throw EngineException("No se pudo conectar a $baseUrl (¿servidor encendido? ¿misma red Wi-Fi?)", e)
        } finally {
            conn.disconnect()
        }
    }
}
