package dev.apkforge.engine

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.Closeable
import java.io.File

/**
 * RAZONAMIENTO EN EL MÓVIL: un modelo de lenguaje que piensa paso a paso (Qwen3 con modo
 * "thinking") ejecutado con LiteRT-LM en la GPU, la NPU o la CPU del teléfono. Sin internet.
 *
 * Trabaja junto al resto del motor neuronal: la red de significado y el grafo le dan el contexto
 * del proyecto ya filtrado, y él razona (qué pide el usuario, dónde está, qué cambiar, qué riesgos
 * hay). Su conclusión la reciben las IAs de internet, que la verifican y la ejecutan; si no hay
 * internet o todas fallan, responde él solo.
 */
class LocalReasoner private constructor(
    private val engine: Engine,
    /** "GPU", "NPU" o "CPU ×N". */
    val backend: String,
    val model: String,
) : AiTextClient, Closeable {

    data class Result(val thinking: String, val answer: String, val millis: Long)

    /** Razona y responde. [think] = paso a paso (más lento, más listo). */
    @Synchronized
    fun reason(system: String, user: String, think: Boolean = true, budget: Int = 1024, maxOutput: Int = 1536): Result {
        val t0 = System.currentTimeMillis()
        val cfg = ConversationConfig(
            Contents.of(system), emptyList(), emptyList(), SamplerConfig(40, 0.95, if (think) 0.6 else 0.3, 0),
            false, emptyList(), emptyMap(), null, false, maxOutput, ThinkingConfig(think, budget), false,
        )
        engine.createConversation(cfg).use { conv ->
            val msg = conv.sendMessage(user, emptyMap(), null, null, null, null, ThinkingConfig(think, budget), null)
            val text = msg.contents.contents.joinToString("") { (it as? com.google.ai.edge.litertlm.Content.Text)?.text.orEmpty() }
            // El pensamiento puede llegar en un canal aparte o entre <think>…</think>
            val channelThought = msg.channels.entries.filter { it.key.contains("think", true) || it.key.contains("thought", true) || it.key.contains("reason", true) }
                .joinToString("\n") { it.value }
            val inline = Regex("<think>([\\s\\S]*?)</think>").find(text)?.groupValues?.get(1).orEmpty()
            val answer = text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            return Result((channelThought + "\n" + inline).trim(), answer, System.currentTimeMillis() - t0)
        }
    }

    override fun complete(system: String, user: String): String = reason(system, user, think = true).answer

    /**
     * Razona CON INTERNET: el modelo local puede pedir buscar o leer la web. Si en su respuesta
     * escribe "BUSCAR: <texto>" o "LEER: <url>", la app lo hace y le pasa el resultado para que
     * continúe. Así el modelo gratis del móvil investiga y luego actúa. Máx. [maxSteps] búsquedas.
     */
    fun reasonWithWeb(system: String, user: String, onStep: (String) -> Unit = {}, maxSteps: Int = 3): String {
        val sys = system + "\n\nTIENES INTERNET. Si necesitas información actual o que no sabes, escribe en una línea " +
            "'BUSCAR: <lo que buscas>' o 'LEER: <url>' y PARA; la app te dará el resultado y seguirás. Cuando tengas la " +
            "respuesta, contéstala directamente sin BUSCAR ni LEER."
        var convo = "PREGUNTA: $user"
        repeat(maxSteps + 1) { step ->
            val ans = reason(sys, convo, think = step == 0, budget = 512, maxOutput = 900).answer
            val search = Regex("""(?im)^\s*BUSCAR:\s*(.+)$""").find(ans)?.groupValues?.get(1)?.trim()
            val read = Regex("""(?im)^\s*LEER:\s*(\S+)$""").find(ans)?.groupValues?.get(1)?.trim()
            when {
                step < maxSteps && !search.isNullOrBlank() -> {
                    onStep("🌐 Busca en internet: $search")
                    val res = runCatching { WebTools.searchFormatted(search, 5) }.getOrElse { "Error: ${it.message}" }
                    convo += "\n\nBuscaste \"$search\". Resultados:\n$res\n\nUsa esto para responder (o LEER: una url para ver más)."
                }
                step < maxSteps && !read.isNullOrBlank() -> {
                    onStep("🌐 Lee: $read")
                    val res = runCatching { WebTools.fetch(read, 6000) }.getOrElse { "Error: ${it.message}" }
                    convo += "\n\nLeíste $read:\n$res\n\nUsa esto para responder."
                }
                else -> return ans.replace(Regex("""(?im)^\s*(BUSCAR|LEER):.*$"""), "").trim()
            }
        }
        return reason(sys, convo + "\n\nResponde ya con lo que tienes.", think = false, maxOutput = 900).answer
    }

    override fun close() = runCatching { engine.close() }.let { }

    companion object {
        /** Modelos que se pueden descargar (públicos, licencia abierta). */
        data class ModelInfo(val id: String, val title: String, val url: String, val bytes: Long, val note: String)

        val MODELS = listOf(
            ModelInfo("qwen3-0.6b", "Qwen3 0.6B · ligero (343 MB)",
                "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm", 343_490_560,
                "Razona paso a paso, rápido en casi cualquier móvil."),
            ModelInfo("qwen3-1.7b", "Qwen3 1.7B · potente (977 MB)",
                "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm", 977_184_032,
                "Razona mejor; recomendado con 8 GB de RAM o más."),
        )

        /**
         * Carga el modelo probando GPU → NPU → CPU (lo primero que funcione en este móvil).
         * [cacheDir] guarda la compilación para que la próxima carga sea rápida.
         */
        fun load(model: File, cacheDir: File?, tryAccelerators: Boolean = true, log: (String) -> Unit = {}): LocalReasoner {
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            val cands = buildList {
                if (tryAccelerators) { add("GPU" to { Backend.GPU() as Backend }); add("NPU" to { Backend.NPU() as Backend }) }
                add("CPU ×$cores" to { Backend.CPU(cores, null) as Backend })
            }
            var lastError: Throwable? = null
            for ((name, backend) in cands) {
                val r = runCatching {
                    val eng = Engine(EngineConfig(model.absolutePath, backend(), null, null, 4096, null, cacheDir?.absolutePath))
                    eng.initialize()
                    LocalReasoner(eng, name, model.nameWithoutExtension)
                }
                r.onSuccess { log("  Razonador: $name ✔"); return it }
                r.onFailure { lastError = it; log("  Razonador: $name no disponible (${it.message?.take(80)})") }
            }
            throw EngineException("No se pudo cargar el modelo de razonamiento: ${lastError?.message}")
        }
    }
}

/** Usa las IAs de internet y, si todas fallan (sin red, cuotas agotadas), el modelo que razona en el móvil. */
class FallbackTextClient(private val primary: AiTextClient, private val local: () -> AiTextClient?) : AiTextClient {
    override fun complete(system: String, user: String): String = try {
        primary.complete(system, user)
    } catch (e: Exception) {
        val l = runCatching { local() }.getOrNull() ?: throw e
        l.complete(system, user)
    }
}
