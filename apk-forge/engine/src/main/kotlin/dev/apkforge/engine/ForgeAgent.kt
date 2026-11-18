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
    FILE_ANALYST(
        "Analista de archivos", "Qué es un archivo, qué contiene, si está cifrado y cómo abrirlo",
        "Analiza a fondo los archivos que el usuario indique (o los más interesantes del proyecto: assets, .so, .bin, .dat, " +
            "bases de datos). Usa analyze_file primero: tipo real, entropía (alta = cifrado/comprimido), cadenas. " +
            "Si parece cifrado, busca en el código (search_text 'Cipher', 'SecretKeySpec', nombre del archivo) la clave y el IV, " +
            "y descífralo con decrypt/auto_aes. Explica qué es cada archivo, para qué lo usa la app y qué contiene.",
    ),
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
) : AiAgent {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(config.apiKey)
        .apply { config.baseUrl?.let { baseUrl(it) } }.build()
    private val history = mutableListOf<BetaMessageParam>()

    private val toolbox = AgentToolbox(root, apk, config.allowEdits, java)
    private val system = toolbox.systemPrompt(preset)
    private val tools: List<BetaTool> = toolbox.specs.map { s ->
        val p = BetaTool.InputSchema.Properties.builder()
        s.props.forEach { (k, v) -> p.putAdditionalProperty(k, JsonValue.from(v)) }
        BetaTool.builder().name(s.name).description(s.description)
            .inputSchema(BetaTool.InputSchema.builder().properties(p.build()).required(s.required).build())
            .build()
    }

    /** Envía un mensaje del usuario y ejecuta el bucle del agente hasta que termine. */
    override fun send(userText: String, isCancelled: () -> Boolean) {
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
                            onEvent(AgentEvent.ToolCall(use.name(), toolbox.summarize(use.name(), input)))
                            val (out, err) = try {
                                toolbox.run(use.name(), input) to false
                            } catch (t: Throwable) {
                                "Error: ${describe(t)}" to true
                            }
                            onEvent(AgentEvent.ToolResult(use.name(), out.take(300), err))
                            BetaContentBlockParam.ofToolResult(
                                BetaToolResultBlockParam.builder()
                                    .toolUseId(use.id()).content(toolbox.clip(out)).isError(err).build()
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

    override fun reset() = history.clear()

}
