package dev.apkforge.app.core

import dev.apkforge.engine.AiEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ámbito de la app: lo que se lanza aquí sigue aunque cierres la pantalla (y el servicio lo mantiene vivo). */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Workers.dispatcher)
}

/** Trabajo de "Modo IA" en un editor. Clave = archivo/entrada que se edita. */
data class AiEditJob(
    val key: String,
    val instruction: String,
    val running: Boolean = true,
    val progress: String = "La IA está trabajando…",
    val edit: AiEdit? = null,
    val explanation: String? = null,
    val error: String? = null,
    val selection: dev.apkforge.app.ui.editor.CodeController.Selection? = null,
)

object AiEditStore {
    private val _jobs = MutableStateFlow<Map<String, AiEditJob>>(emptyMap())
    val jobs: StateFlow<Map<String, AiEditJob>> = _jobs
    private val running = HashMap<String, Job>()

    val busy get() = _jobs.value.values.any { it.running }

    fun start(key: String, instruction: String, selection: dev.apkforge.app.ui.editor.CodeController.Selection?,
              block: suspend ((String) -> Unit) -> Pair<AiEdit?, String?>) {
        running[key]?.cancel()
        _jobs.update { it + (key to AiEditJob(key, instruction, selection = selection)) }
        BackgroundService.ensureRunning()
        running[key] = AppScope.scope.launch {
            try {
                val (edit, text) = block { msg -> _jobs.update { m -> m[key]?.let { m + (key to it.copy(progress = msg)) } ?: m } }
                _jobs.update { m -> m + (key to (m[key] ?: AiEditJob(key, instruction)).copy(running = false, edit = edit, explanation = text)) }
            } catch (t: Throwable) {
                _jobs.update { m -> m + (key to (m[key] ?: AiEditJob(key, instruction)).copy(running = false, error = dev.apkforge.engine.describe(t))) }
            }
        }
    }

    fun cancel(key: String) { running.remove(key)?.cancel(); clear(key) }
    fun clear(key: String) = _jobs.update { it - key }
}
