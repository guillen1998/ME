package dev.apkforge.app.core

import dev.apkforge.engine.ProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class ResultKind { NONE, APK, DIRECTORY, FILE }

data class TaskState(
    val title: String,
    val logs: List<String> = emptyList(),
    val running: Boolean = true,
    val error: String? = null,
    val result: File? = null,
    val resultKind: ResultKind = ResultKind.NONE,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
)

/**
 * Ejecuta operaciones largas (decompilar, compilar, firmar…) fuera de la UI, con un log en vivo
 * que se muestra en la consola. Sobrevive a rotaciones y cambios de pantalla.
 */
object TaskRunner {
    private val scope = CoroutineScope(SupervisorJob() + Workers.dispatcher)
    private val _state = MutableStateFlow<TaskState?>(null)
    val state: StateFlow<TaskState?> = _state
    private var job: Job? = null

    val isBusy get() = _state.value?.running == true

    fun run(
        title: String,
        resultKind: ResultKind = ResultKind.NONE,
        block: suspend (ProgressListener) -> File?,
    ) {
        if (isBusy) return
        _state.value = TaskState(title)
        val listener = ProgressListener { msg -> _state.update { it?.copy(logs = (it.logs + msg).takeLast(400)) } }
        job = scope.launch {
            try {
                val result = block(listener)
                _state.update {
                    it?.copy(running = false, result = result, resultKind = if (result != null) resultKind else ResultKind.NONE,
                        finishedAt = System.currentTimeMillis())
                }
            } catch (t: Throwable) {
                val msg = when (t) {
                    is OutOfMemoryError -> "Memoria insuficiente. Prueba a decompilar solo recursos o solo smali."
                    is StackOverflowError -> "Desbordamiento de pila al procesar el archivo."
                    else -> t.message ?: t.toString()
                }
                _state.update { it?.copy(running = false, error = msg, finishedAt = System.currentTimeMillis()) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.update { it?.copy(running = false, error = "Cancelado", finishedAt = System.currentTimeMillis()) }
    }

    fun dismiss() {
        if (!isBusy) _state.value = null
    }
}
