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
    /** PLANIFICADOR: los coders/razonadores más potentes primero. REVISOR: otra familia distinta, para cruzar. */
    fun plannerOrder(list: List<AiEndpoint> = endpoints) = list.sortedBy { rank(it, listOf("DeepSeek-V4.1", "397B", "gpt-oss-120b")) }
    fun reviewerOrder(list: List<AiEndpoint> = endpoints) = list.sortedBy { rank(it, listOf("Llama-3_3", "qwen3.6-35b", "Qwen3.8-27B", "gpt-oss-120b")) }
    private fun rank(e: AiEndpoint, prefs: List<String>) = prefs.indexOfFirst { e.model.contains(it, ignoreCase = true) }.let { if (it < 0) prefs.size else it }

    private const val OVH = "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1"
    private const val KILO = "https://api.kilo.ai/api/openrouter"

    /**
     * VERIFICADOS EN VIVO: responden SIN clave y saben usar herramientas (tool calling), ordenados de
     * más potente a menos para que el trabajo salga bien a la primera. OVH es la columna: DeepSeek
     * V4.1 (coder dedicado, 1M de contexto), Qwen 3.5 397B (MoE enorme), GPT-OSS 120B, Qwen 3.6/3.8 y
     * Llama 3.3 70B. Kilo (auto-enruta al mejor modelo gratis) y Pollinations como respaldo. Si uno se
     * satura o cae, sigue el siguiente sin que lo notes.
     *
     * (LLM7 se quitó de la lista sin clave: ahora todos sus modelos exigen fondos/suscripción —
     * 402/429/401 —, lo que solo generaba "ocupado". Puedes añadir tu propia clave LLM7/OpenRouter en
     * Ajustes y ese modelo se suma al equipo.)
     */
    val endpoints = listOf(
        AiEndpoint(OVH, "", "DeepSeek-V4.1-Flash-1017", "DeepSeek V4.1 · código · 1M (OVH)"),
        AiEndpoint(OVH, "", "Qwen3.5-397B-A17B", "Qwen 3.5 397B (OVH)"),
        AiEndpoint(OVH, "", "gpt-oss-120b", "GPT-OSS 120B (OVH)"),
        AiEndpoint(OVH, "", "qwen3.6-35b-a3b", "Qwen 3.6 35B (OVH)"),
        AiEndpoint(OVH, "", "Qwen3.8-27B", "Qwen 3.8 27B (OVH)"),
        AiEndpoint(OVH, "", "Meta-Llama-3_3-70B-Instruct", "Llama 3.3 70B (OVH)"),
        AiEndpoint(OVH, "", "Qwen3.5-9B", "Qwen 3.5 9B · rápido (OVH)"),
        AiEndpoint(KILO, "", "kilo-auto/efficient", "Kilo Auto · mejor modelo gratis"),
        AiEndpoint(KILO, "", "kilo-auto/free", "Kilo Auto (gratis)"),
        AiEndpoint("https://text.pollinations.ai/openai", "", "openai-fast", "GPT-OSS 20B (Pollinations)"),
    )

    /**
     * Modelos GRATIS fuertes de OpenRouter (verificados: declaran tool calling), de más potente a
     * menos. Cuando guardas tu clave de OpenRouter, TODOS estos se suman al equipo de golpe, así
     * trabajas con muchos modelos sin elegir ninguno. `openrouter/free` además auto-enruta entre todos
     * los modelos gratis disponibles. (Nivel gratis con tope diario; al agotarse, sigue el equipo de OVH.)
     */
    private val openRouterFree = listOf(
        "nvidia/nemotron-3-ultra-550b-a55b:free" to "Nemotron 550B · OpenRouter",
        "cohere/north-mini-code:free" to "North Mini · código · OpenRouter",
        "inclusionai/ling-3.2-flash:free" to "Ling 3.2 Flash · 1M · OpenRouter",
        "openrouter/free" to "OpenRouter Auto · mejor gratis",
    )

    /** Endpoints de OpenRouter para el equipo: tu modelo elegido primero (si es específico) + la lista fuerte. */
    fun openRouterTeam(baseUrl: String, apiKey: String, userModel: String): List<AiEndpoint> {
        val out = LinkedHashMap<String, AiEndpoint>()
        if (userModel.isNotBlank()) out[userModel] = AiEndpoint(baseUrl, apiKey, userModel, "$userModel · OpenRouter")
        openRouterFree.forEach { (m, label) -> out.getOrPut(m) { AiEndpoint(baseUrl, apiKey, m, label) } }
        return out.values.toList()
    }
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
        "Ya viene lista: une modelos abiertos gratuitos VERIFICADOS que responden sin clave y saben editar código y usar herramientas — DeepSeek V4.1 (coder, 1M de contexto), Qwen 3.5 397B, GPT-OSS 120B, Qwen 3.6/3.8 y Llama 3.3 70B. Trabajan en equipo: uno planifica, otro ejecuta y otro revisa; si uno se satura, sigue otro. No configuras nada."),
    CLAUDE("Claude (Anthropic)", "", "claude-opus-5-5", true,
        "Clave en console.anthropic.com. El más capaz."),
    OPENROUTER("OpenRouter · modelos abiertos gratis", "https://openrouter.ai/api/v1", "openrouter/free", true,
        "Clave gratis en openrouter.ai. Al guardarla, se SUMAN AL EQUIPO varios modelos gratis fuertes a la vez (Nemotron 550B, un coder dedicado y 'openrouter/free', que auto-enruta entre todos los gratis) junto al resto — trabajan en equipo y se alternan solos. No hace falta elegir modelo: deja 'openrouter/free'."),
    GEMINI("Google Gemini · clave gratis", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-flash-latest", true,
        "API oficial de Google con nivel gratuito: crea la clave gratis en aistudio.google.com (Get API key). Modelos: gemini-flash-latest, gemini-pro-latest."),
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

    /**
     * VENTANA DE CONTEXTO: la IA recuerda la tarea, pero no reenviamos millones de tokens cada vez.
     * Los resultados de herramientas ANTIGUOS (volcados de archivos, búsquedas) se recortan a un
     * resumen; así continúa donde quedó sin gastar recursos ni repetir trabajo. El system y los
     * mensajes recientes se mantienen completos.
     */
    private fun trimContext(budgetChars: Int = 220_000, keepRecent: Int = 8) {
        fun len(o: JsonObject) = o.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.length ?: 0
        // 1) recorta resultados de herramientas que ya no son recientes
        val lastIdx = messages.size() - 1
        for (i in 1..lastIdx) {
            val m = messages[i].asJsonObject
            if (m.get("role")?.asString == "tool" && i < lastIdx - keepRecent) {
                val c = m.get("content")?.asString ?: continue
                if (c.length > 1200) m.addProperty("content", c.take(600) + "\n…[resultado recortado para ahorrar contexto: ${c.length} car.]…\n" + c.takeLast(300))
            }
        }
        // 2) si aún es enorme, elimina los mensajes más antiguos (menos el system), por pares
        var total = (0 until messages.size()).sumOf { len(messages[it].asJsonObject) }
        while (total > budgetChars && messages.size() > keepRecent + 2) {
            // no dejar un 'tool' huérfano sin su 'assistant' con tool_calls: quita desde el índice 1
            val removed = messages[1].asJsonObject
            total -= len(removed)
            messages.remove(1)
            // si el siguiente es un 'tool' colgado, quítalo también
            while (messages.size() > 2 && messages[1].asJsonObject.get("role")?.asString == "tool") {
                total -= len(messages[1].asJsonObject); messages.remove(1)
            }
        }
    }

    override fun send(userText: String, isCancelled: () -> Boolean) {
        messages.add(JsonObject().apply { addProperty("role", "user"); addProperty("content", userText) })
        var inTok = 0L; var outTok = 0L
        var continues = 0
        try {
            for (turn in 0 until maxTurns) {
                if (isCancelled()) { onEvent(AgentEvent.Error("Detenido")); break }
                trimContext()
                val body = JsonObject().apply {
                    add("messages", messages)
                    add("tools", toolsJson)
                    addProperty("tool_choice", "auto")
                    addProperty("max_tokens", 16000)
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
                    // Si el modelo se cortó por longitud, el agente le pide que siga solo (hasta 4 veces)
                    if (choice.get("finish_reason")?.asString == "length" && continues < 4) {
                        continues++
                        onEvent(AgentEvent.Text("… (continúa)"))
                        messages.add(JsonObject().apply { addProperty("role", "user"); addProperty("content", "Continúa exactamente desde donde te cortaste, sin repetir lo ya escrito.") })
                        continue
                    }
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
                    onEvent(AgentEvent.ToolResult(name, out.take(4000), err))
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
        val shown = HashSet<String>()
        for (round in 0 until 4) {
            for (attempt in endpoints.indices) {
                val i = (current + attempt) % endpoints.size
                val ep = endpoints[i]
                try {
                    val res = OpenAiHttp.chat(ep, body)
                    if (i != current) { current = i; onEvent(AgentEvent.Text("↪ Sigo con ${ep.label}")) }
                    return res
                } catch (e: OpenAiHttp.RetryableException) {
                    val why = e.message ?: "error"
                    errors[ep.label] = why
                    if (shown.add(why)) onEvent(AgentEvent.Text("⚠ $why"))   // muestra el error REAL de esa IA (cuál y por qué), sin repetir
                }
            }
            if (round == 3 || endpoints.all { OpenAiHttp.isCooled(it) }) break
            val wait = OpenAiHttp.waitSeconds(errors.values, round)
            onEvent(AgentEvent.Text("⏳ Reintento en $wait s…"))
            Thread.sleep(wait * 1000L)
        }
        throw EngineException((if (endpoints.size == 1) "La IA falló:\n" else "Ninguna IA respondió:\n") +
            errors.entries.joinToString("\n") { "• ${it.value}" })
    }
}
