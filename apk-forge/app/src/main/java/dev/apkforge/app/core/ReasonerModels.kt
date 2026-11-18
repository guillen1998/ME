package dev.apkforge.app.core

import androidx.compose.runtime.mutableStateMapOf
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.engine.LocalReasoner
import dev.apkforge.engine.NeuralBrain
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Descarga (reanudable) de los modelos que razonan en el móvil. Sigue aunque salgas de la pantalla. */
object ReasonerModels {
    data class State(val percent: Int = 0, val running: Boolean = false, val error: String? = null)
    val states = mutableStateMapOf<String, State>()
    private val jobs = HashMap<String, Job>()

    fun dir() = File(ApkForgeApp.instance.filesDir, "reasoner").apply { mkdirs() }
    fun file(id: String) = File(dir(), "$id.litertlm")
    fun isDownloaded(m: LocalReasoner.Companion.ModelInfo) = file(m.id).let { it.isFile && it.length() == m.bytes }

    fun download(m: LocalReasoner.Companion.ModelInfo) {
        if (jobs[m.id]?.isActive == true) return
        states[m.id] = State(0, true)
        BackgroundService.ensureRunning()
        jobs[m.id] = AppScope.scope.launch {
            val part = File(dir(), "${m.id}.part")
            try {
                var done = if (part.isFile) part.length() else 0L
                val conn = (URL(m.url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true; connectTimeout = 30_000; readTimeout = 60_000
                    if (done > 0) setRequestProperty("Range", "bytes=$done-")
                }
                if (conn.responseCode == 200) done = 0   // el servidor no reanuda: empezar de cero
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, done > 0).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var lastPct = -1
                        while (isActive) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val pct = (done * 100 / m.bytes).toInt()
                            if (pct != lastPct) { lastPct = pct; onMain { states[m.id] = State(pct, true) } }
                        }
                    }
                }
                if (part.length() != m.bytes) throw IllegalStateException("Descarga incompleta (${part.length() / 1_048_576} de ${m.bytes / 1_048_576} MB); pulsa de nuevo para continuar")
                part.renameTo(file(m.id))
                onMain {
                    states[m.id] = State(100, false)
                    if (ApkForgeApp.prefs.reasonerModel.isBlank()) ApkForgeApp.prefs.reasonerModel = m.id
                    NeuralBrain.resetReasoner()
                }
            } catch (t: Throwable) {
                onMain { states[m.id] = State(states[m.id]?.percent ?: 0, false, t.message ?: t.toString()) }
            }
        }
    }

    fun cancel(id: String) { jobs[id]?.cancel(); states[id] = State(states[id]?.percent ?: 0, false, "Pausado") }

    fun delete(id: String) {
        cancel(id); file(id).delete(); File(dir(), "$id.part").delete(); states.remove(id)
        if (ApkForgeApp.prefs.reasonerModel == id) { ApkForgeApp.prefs.reasonerModel = ""; NeuralBrain.resetReasoner() }
    }

    /** Lo usa NeuralBrain: carga el modelo elegido en GPU/NPU/CPU. */
    fun load(): LocalReasoner? {
        val id = ApkForgeApp.prefs.reasonerModel.ifBlank { return null }
        val m = LocalReasoner.MODELS.firstOrNull { it.id == id } ?: return null
        if (!isDownloaded(m)) return null
        return LocalReasoner.load(file(id), File(ApkForgeApp.instance.cacheDir, "litertlm").apply { mkdirs() })
    }
}
