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

/** Un servicio compatible con OpenAI (URL + clave opcional + modelo). */
data class AiEndpoint(val baseUrl: String, val apiKey: String, val model: String, val label: String)

/**
 * IA integrada de ApkForge: funciona SIN clave y sin configurar nada. Une varios servicios
 * gratuitos que ofrecen acceso anónimo oficial, con modelos abiertos que saben usar herramientas;
 * si uno se satura o falla, se pasa al siguiente automáticamente.
 */
object BuiltinAi {
    /** Orden para el PLANIFICADOR (modelo más grande primero) y para el REVISOR (otro modelo distinto). */
    fun plannerOrder(list: List<AiEndpoint> = endpoints) = list.sortedBy { if (it.model.contains("397B")) 0 else if (it.model.contains("DeepSeek")) 1 else 2 }
    fun reviewerOrder(list: List<AiEndpoint> = endpoints) = list.sortedBy { if (it.model.contains("gpt-oss-120b")) 0 else if (it.model.contains("qwen3.6")) 1 else 2 }

    private const val OVH = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1"

    /**
     * Probados sin clave y con herramientas. Ordenados por potencia y velocidad para código.
     * Cada servicio limita peticiones por modelo, así que la cadena reparte el trabajo entre todos.
     */
    val endpoints = listOf(
        AiEndpoint(OVH, "", "DeepSeek-V4.1-Flash-1017", "DeepSeek V4.1 Flash (OVH)"),
        AiEndpoint(OVH, "", "Qwen3.5-397B-A17B", "Qwen 3.5 397B (OVH)"),
        AiEndpoint(OVH, "", "gpt-oss-120b", "GPT-OSS 120B (OVH)"),
        AiEndpoint(OVH, "", "qwen3.6-35b-a3b", "Qwen 3.6 35B (OVH)"),
        AiEndpoint("https://api.kilo.ai/api/openrouter", "", "kilo-auto/free", "Kilo Auto (gratis)"),
        AiEndpoint("https://api.llm7.io/v1", "", "default", "Gemma 4 31B (LLM7)"),
        AiEndpoint(OVH, "", "Qwen3.8-27B", "Qwen 3.8 27B (OVH)"),
        AiEndpoint(OVH, "", "Meta-Llama-3_3-70B-Instruct", "Llama 3.3 70B (OVH)"),
        AiEndpoint("https://text.pollinations.ai/openai", "", "openai", "GPT-OSS 20B (Pollinations)"),
    )
}

/** Proveedores de IA. Los compatibles con OpenAI dan acceso a modelos de código abierto. */
enum class AiProvider(
    val title: String,
    val baseUrl: String,
    val defaultModel: String,
    val needsKey: Boolean,
    val help: String,
) {
    BUILTIN("ApkForge IA · integrada, gratis, sin clave", "", "auto", false,
        "Ya viene lista: une 9 modelos abiertos gratuitos (DeepSeek, Qwen 397B, GPT-OSS 120B, Gemma, Llama…) que trabajan en conjunto: si uno está ocupado, sigue otro. No necesitas configurar nada."),
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
    private val endpoints: List<AiEndpoint>,
    preset: AgentPreset,
    allowEdits: Boolean,
    java: () -> JavaDecompiler?,
    private val onEvent: (AgentEvent) -> Unit,
    private val maxTurns: Int = 30,
) : AiAgent {
    constructor(
        root: File, apk: File?, baseUrl: String, apiKey: String, model: String, preset: AgentPreset,
        allowEdits: Boolean, java: () -> JavaDecompiler?, onEvent: (AgentEvent) -> Unit, maxTurns: Int = 30,
    ) : this(root, apk, listOf(AiEndpoint(baseUrl, apiKey, model, model)), preset, allowEdits, java, onEvent, maxTurns)

    /** Servicio que está funcionando ahora (se mantiene mientras responda bien). */
    @Volatile private var current = 0
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
                    // Algunos modelos abiertos (GPT-OSS) "ensucian" el nombre: "read_file<|channel|>commentary".
                    val name = toolbox.specs.map { it.name }.let { known ->
                        val raw = fn.get("name").asString.substringBefore("<|").substringBefore(" ").trim()
                        known.firstOrNull { it == raw } ?: known.firstOrNull { raw.startsWith(it) || raw.endsWith(it) } ?: raw
                    }
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

    /**
     * Envía con cambio automático de servicio: si uno falla (saturado, caído, sin red), prueba el
     * siguiente; si todos están ocupados, espera (lo que pida el servicio, máx. 30 s) y reintenta.
     */
    private fun post(body: JsonObject): JsonObject {
        val errors = LinkedHashMap<String, String>()
        for (round in 0 until 4) {
            for (attempt in endpoints.indices) {
                val i = (current + attempt) % endpoints.size
                val ep = endpoints[i]
                try {
                    val res = OpenAiHttp.chat(ep, body)
                    if (i != current) { current = i; onEvent(AgentEvent.Text("↪ Sigo con ${ep.label}")) }
                    return res
                } catch (e: OpenAiHttp.RetryableException) {
                    errors[ep.label] = e.message ?: "error"
                }
            }
            if (round == 3) break
            val wait = OpenAiHttp.waitSeconds(errors.values, round)
            onEvent(AgentEvent.Text("⏳ Servicios de IA ocupados; reintento en $wait s…"))
            Thread.sleep(wait * 1000L)
        }
        throw EngineException("Ningún servicio de IA respondió:\n" + errors.entries.joinToString("\n") { "• ${it.value}" })
    }
}
