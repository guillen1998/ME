package dev.apkforge.app.core

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.apkforge.engine.AgentEvent
import dev.apkforge.engine.AiAgent
import dev.apkforge.engine.Finding
import dev.apkforge.engine.ProgressListener
import dev.apkforge.engine.ProjectScanner
import dev.apkforge.engine.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

private val main = Handler(Looper.getMainLooper())
internal fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }

/** Acumula elementos desde un hilo de trabajo y los vuelca a la UI por lotes (fluido aunque haya miles). */
private class Batcher<T>(private val target: SnapshotStateList<T>) {
    private val queue = ConcurrentLinkedQueue<T>()
    @Volatile private var scheduled = false
    fun add(item: T) {
        queue += item
        if (!scheduled) { scheduled = true; main.postDelayed({ flush() }, 150) }
    }
    fun flush() {
        scheduled = false
        val batch = ArrayList<T>()
        while (true) batch += (queue.poll() ?: break)
        if (batch.isNotEmpty()) target.addAll(batch)
    }
}

// ---------------------------------------------------------------- Búsqueda en archivos

data class Hit(val file: File, val line: Int, val text: String)

/**
 * Búsqueda en archivos del proyecto: los resultados aparecen EN VIVO mientras busca, puedes
 * abrirlos y volver sin que la búsqueda se pare (sigue en segundo plano).
 */
object SearchStore {
    val hits: SnapshotStateList<Hit> = mutableStateListOf()
    val status = mutableStateOf<String?>(null)
    val running = mutableStateOf(false)
    var root: File? = null; private set
    var query: String = ""; private set
    private var job: Job? = null
    @Volatile private var scanned = 0

    fun busyInfo() = if (running.value) BusyInfo("Buscando “$query”", "${hits.size} resultados · $scanned archivos", -1) else null

    fun start(root: File, query: String, regex: Boolean, matchCase: Boolean, filter: String) {
        job?.cancel()
        val matcher: (String) -> Boolean = runCatching<(String) -> Boolean> {
            if (regex) { val r = if (matchCase) Regex(query) else Regex(query, RegexOption.IGNORE_CASE); { s -> r.containsMatchIn(s) } }
            else { s -> s.contains(query, ignoreCase = !matchCase) }
        }.getOrElse { status.value = "Expresión regular no válida"; return }
        this.root = root; this.query = query
        hits.clear(); scanned = 0; running.value = true; status.value = "Buscando…"
        val batcher = Batcher(hits)
        BackgroundService.ensureRunning()
        job = AppScope.scope.launch {
            var found = 0
            try {
                root.walkTopDown().onEnter { it.name != ".apkforge" && it.name != "build" }
                    .filter { it.isFile && matchesFilter(it, filter) }
                    .forEach { f ->
                        ensureActive()
                        scanned++
                        runCatching {
                            f.useLines { lines ->
                                lines.forEachIndexed { i, line ->
                                    if (found < 5000 && matcher(line)) { found++; batcher.add(Hit(f, i + 1, line.trim().take(300))) }
                                }
                            }
                        }
                        if (scanned % 200 == 0) onMain { status.value = "Buscando… $scanned archivos · $found resultados" }
                        if (found >= 5000) return@forEach
                    }
                onMain { batcher.flush(); status.value = "$found resultado(s) en $scanned archivos" + if (found >= 5000) " (límite)" else "" }
            } finally {
                onMain { batcher.flush(); running.value = false }
            }
        }
    }

    fun cancel() { job?.cancel(); running.value = false; status.value = "Detenida (${hits.size} resultados)" }

    private val TEXT = setOf("smali", "xml", "json", "txt", "java", "kt", "properties", "html", "js", "css", "yml", "yaml", "cfg", "ini", "md", "gradle", "kts", "pro")
    private fun matchesFilter(f: File, filter: String) = when (filter) {
        "smali" -> f.extension == "smali"
        "xml" -> f.extension == "xml"
        else -> f.length() < 8 * 1024 * 1024 && f.extension in TEXT
    }
}

// ---------------------------------------------------------------- Escáner automático

object ScanStore {
    val findings: SnapshotStateList<Finding> = mutableStateListOf()
    val status = mutableStateOf<String?>(null)
    val running = mutableStateOf(false)
    val percent = mutableStateOf(-1)
    var target: File? = null; private set
    private var job: Job? = null

    fun busyInfo() = if (running.value) BusyInfo("Analizando ${target?.name}", "${findings.size} hallazgos", percent.value) else null

    /** Empieza (o no hace nada si ya hay resultados de ese mismo objetivo y [force] es false). */
    fun start(target: File, force: Boolean = false) {
        if (!force && this.target == target && (running.value || findings.isNotEmpty())) return
        job?.cancel()
        this.target = target
        findings.clear(); running.value = true; percent.value = 0; status.value = "Analizando…"
        val batcher = Batcher(findings)
        BackgroundService.ensureRunning()
        job = AppScope.scope.launch {
            try {
                val listener = object : ProgressListener {
                    override fun onProgress(message: String) = onMain { status.value = message }
                    override fun onPercent(percent: Int) = onMain { this@ScanStore.percent.value = percent }
                }
                val all = ProjectScanner.scan(target, ScanOptions(), listener, { !isActive }) { batcher.add(it) }
                onMain { batcher.flush(); status.value = "${all.size} hallazgos" }
            } finally {
                onMain { batcher.flush(); running.value = false }
            }
        }
    }

    fun cancel() { job?.cancel(); running.value = false; status.value = "Detenido (${findings.size} hallazgos)" }
}

// ---------------------------------------------------------------- Agente IA

sealed class AgentMsg {
    data class User(val text: String, val images: List<ByteArray> = emptyList()) : AgentMsg()
    data class Assistant(val text: String) : AgentMsg()
    data class Tool(val tool: String, val summary: String, val result: String? = null, val error: Boolean = false) : AgentMsg()
    data class Info(val text: String, val error: Boolean) : AgentMsg()
}

/** Conversación con el agente: sigue trabajando aunque salgas de la pantalla o de la app. */
class AgentSession(val key: String, val title: String) {
    val messages: SnapshotStateList<AgentMsg> = mutableStateListOf()
    val running = mutableStateOf(false)
    val tokens = mutableStateOf("")
    var agent: AiAgent? = null
    internal var job: Job? = null

    val onEvent: (AgentEvent) -> Unit = { ev ->
        onMain {
            when (ev) {
                is AgentEvent.Text -> messages += AgentMsg.Assistant(ev.text)
                is AgentEvent.ToolCall -> messages += AgentMsg.Tool(ev.tool, ev.summary)
                is AgentEvent.ToolResult -> {
                    val idx = messages.indexOfLast { it is AgentMsg.Tool && it.result == null }
                    if (idx >= 0) messages[idx] = (messages[idx] as AgentMsg.Tool).copy(result = ev.preview, error = ev.isError)
                }
                is AgentEvent.Error -> messages += AgentMsg.Info(ev.message, true)
                is AgentEvent.Done -> tokens.value = "entrada ${ev.inputTokens} · salida ${ev.outputTokens}" +
                    (if (ev.cachedTokens > 0) " · caché ${ev.cachedTokens}" else "")
            }
        }
    }

    /**
     * Envía un mensaje. Antes, la APP prepara el trabajo: analiza las imágenes (visión) y busca en
     * el proyecto los archivos relacionados; todo eso se le entrega a la IA junto con tu mensaje.
     */
    fun send(text: String, images: List<ByteArray> = emptyList(), project: File? = null) {
        val a = agent ?: return
        if (running.value) return
        messages += AgentMsg.User(text, images)
        running.value = true
        BackgroundService.ensureRunning()
        job = AppScope.scope.launch {
            try {
                val prep = dev.apkforge.engine.MessagePreparer.prepare(project, text, images,
                    dev.apkforge.engine.VisionAi.endpoints, ProgressListener { msg -> onMain { status.value = msg } })
                onMain {
                    status.value = null
                    val parts = buildList {
                        if (prep.imageNotes != null) add("leí ${images.size} imagen(es)")
                        if (prep.related.isNotEmpty()) add("encontré ${prep.related.size} archivo(s) relacionado(s) (ordenados por el motor neuronal): " +
                            prep.related.map { it.path.substringAfterLast('/') }.distinct().take(4).joinToString(", "))
                    }
                    if (parts.isNotEmpty()) messages += AgentMsg.Info("🧩 La app: " + parts.joinToString(" · "), false)
                }
                val t0 = System.currentTimeMillis()
                a.send(prep.text) { !isActive }
                // La IA ayuda a la app: el motor neuronal aprende qué archivos tocó para este tipo de pedido
                if (project != null) runCatching { dev.apkforge.engine.MessagePreparer.learnFrom(project, text, t0) }.getOrNull()?.takeIf { it > 0 }?.let { n ->
                    onMain { messages += AgentMsg.Info("🧬 Motor neuronal: aprendió de $n archivo(s) editado(s) (${dev.apkforge.engine.NeuralEngine.shared.examples} ejemplos en total)", false) }
                }
            } finally { onMain { running.value = false; status.value = null } }
        }
    }

    /** Qué está haciendo la app antes de pasarle el trabajo a la IA. */
    val status = mutableStateOf<String?>(null)

    fun stop() { job?.cancel(); running.value = false }
}

object AgentStore {
    private val sessions = LinkedHashMap<String, AgentSession>()

    fun session(key: String, title: String, create: (AgentSession) -> AiAgent?): AgentSession =
        sessions.getOrPut(key) { AgentSession(key, title) }.also { s -> if (s.agent == null) s.agent = create(s) }

    fun drop(key: String) { sessions.remove(key)?.stop() }

    /** Carpetas sobre las que hay conversación con la IA (ruta -> ¿está trabajando ahora?). */
    fun activeTargets(): Map<String, Boolean> =
        sessions.values.groupBy { it.key.substringBefore('|') }.mapValues { (_, l) -> l.any { it.running.value } }

    fun busyInfo(): BusyInfo? = sessions.values.firstOrNull { it.running.value }?.let { s ->
        val last = s.messages.lastOrNull()
        BusyInfo("Agente IA: ${s.title}", when (last) {
            is AgentMsg.Tool -> "Usando ${last.tool} ${last.summary}"
            is AgentMsg.Assistant -> last.text.take(120)
            else -> "Pensando…"
        }, -1)
    }
}

// ---------------------------------------------------------------- Búsqueda en código Java

/** Búsqueda de texto en el Java decompilado: resultados en vivo que se mantienen al abrir una clase y volver. */
object JavaSearchStore {
    val results: SnapshotStateList<Triple<String, Int, String>> = mutableStateListOf()
    val status = mutableStateOf<String?>(null)
    val running = mutableStateOf(false)
    var source: File? = null; private set
    var query = ""; private set
    var pkg = ""; private set
    private var job: Job? = null

    fun busyInfo() = if (running.value) BusyInfo("Buscando en Java “$query”", "${results.size} resultados", -1) else null

    fun start(source: File, session: dev.apkforge.engine.JavaDecompiler, query: String, pkg: String) {
        job?.cancel()
        this.source = source; this.query = query; this.pkg = pkg
        results.clear(); running.value = true; status.value = "Buscando…"
        val batcher = Batcher(results)
        BackgroundService.ensureRunning()
        job = AppScope.scope.launch {
            try {
                val found = session.searchCode(query, 500, pkg.ifBlank { null },
                    ProgressListener { msg -> onMain { status.value = msg } }, { !isActive }) { batcher.add(it) }
                onMain { batcher.flush(); status.value = "${found.size} resultado(s)" }
            } finally {
                onMain { batcher.flush(); running.value = false }
            }
        }
    }

    fun cancel() { job?.cancel(); running.value = false; status.value = "Detenida (${results.size} resultados)" }
}

/** Estado de la lista de clases (paquetes desplegados) por origen, para que no se pliegue al volver. */
object JavaBrowserState {
    val expanded = HashMap<String, androidx.compose.runtime.snapshots.SnapshotStateList<String>>()
    fun expandedFor(source: File) = expanded.getOrPut(source.absolutePath) { mutableStateListOf() }
}
