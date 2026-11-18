package dev.apkforge.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.AiEditStore
import dev.apkforge.app.core.AiFactory
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.Danger
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Ok
import dev.apkforge.app.ui.Pill
import dev.apkforge.engine.CodeAssistant

/** Selector Modo manual / Modo IA (se recuerda entre sesiones). */
@Composable
fun EditorModeSwitch(aiMode: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(!aiMode, { onChange(false) }, label = { Text("Modo manual") })
        FilterChip(aiMode, { onChange(true) }, label = { Text("Modo IA") },
            leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)) })
    }
}

/**
 * Barra del Modo IA: describes el cambio (reemplazar, parchear, quitar/añadir funciones, crear
 * código) o pides un análisis. Actúa sobre la selección o sobre todo el archivo. Antes de aplicar
 * muestra el diff (verde/rojo); en smali lo ensambla para comprobarlo y lo corrige sola si falla.
 */
@Composable
fun AiEditBar(key: String, fileName: String, controller: CodeController, onOpenSettings: () -> Unit, onApplied: () -> Unit) {
    val prefs = ApkForgeApp.prefs
    val jobs by AiEditStore.jobs.collectAsState()
    val job = jobs[key]
    var instruction by remember { mutableStateOf("") }
    val ready = prefs.aiReady()

    fun run(explain: Boolean) {
        val client = AiFactory.textClient(prefs) ?: return
        val sel = controller.selection()
        val code = sel?.text ?: controller.text()
        val text = instruction.ifBlank { if (explain) "¿Qué hace este código?" else return }
        AiEditStore.start(key, text, sel) { progress ->
            if (explain) null to CodeAssistant.explain(client, code, fileName, text)
            else CodeAssistant.edit(client, code, fileName, text, isFragment = sel != null,
                listener = { progress(it) }) to null
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            if (!ready) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Configura un proveedor de IA (Claude, OpenRouter, Groq u Ollama).", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenSettings) { Text("Ajustes") }
                }
                return@Column
            }
            val sel = controller.selection()
            Text(if (sel != null) "Sobre la selección (${sel.text.lines().size} líneas)" else "Sobre todo el archivo — selecciona texto para limitar el cambio",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val quick = if (fileName.endsWith(".smali")) listOf(
                    "Haz que este método devuelva true", "Quita esta llamada", "Añade un Log.d al inicio de cada método",
                    "Muestra un Toast al iniciar", "Crea un método nuevo que…",
                ) else if (fileName.endsWith(".xml")) listOf(
                    "Cambia los textos al español", "Cambia el color principal a #4FC1FF", "Quita este permiso", "Añade un botón nuevo",
                ) else listOf("Explica este código", "Corrige errores", "Optimiza esto")
                quick.forEach { q -> AssistChip(onClick = { instruction = q }, label = { Text(q, fontSize = 11.sp) }) }
            }
            OutlinedTextField(instruction, { instruction = it }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
                placeholder = { Text("¿Qué quieres cambiar, parchear, quitar o crear?") })
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (job?.running == true) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(job.progress, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { AiEditStore.cancel(key) }) { Text("Cancelar") }
                } else {
                    OutlinedButton(onClick = { run(explain = true) }) {
                        Icon(Icons.Default.Psychology, null, Modifier.size(18.dp)); Spacer(Modifier.size(4.dp)); Text("Analizar")
                    }
                    Spacer(Modifier.size(8.dp))
                    Button(enabled = instruction.isNotBlank(), onClick = { run(explain = false) }) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.size(4.dp)); Text("Aplicar con IA")
                    }
                }
            }
        }
    }

    // Resultado: diff para revisar, o explicación
    if (job != null && !job.running) {
        when {
            job.error != null -> AlertDialog(
                onDismissRequest = { AiEditStore.clear(key) },
                title = { Text("La IA no pudo hacerlo") },
                text = { Text(job.error, color = Danger) },
                confirmButton = { TextButton(onClick = { AiEditStore.clear(key) }) { Text("Cerrar") } },
            )
            job.explanation != null -> AlertDialog(
                onDismissRequest = { AiEditStore.clear(key) },
                title = { Text("Análisis de la IA") },
                text = { SelectionContainer { Text(job.explanation, fontSize = 13.sp, modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) } },
                confirmButton = { TextButton(onClick = { AiEditStore.clear(key) }) { Text("Cerrar") } },
            )
            job.edit != null -> DiffDialog(job.edit, job.instruction,
                onApply = { controller.replace(job.selection, job.edit.newCode); AiEditStore.clear(key); onApplied() },
                onDiscard = { AiEditStore.clear(key) })
        }
    }
}

@Composable
private fun DiffDialog(edit: dev.apkforge.engine.AiEdit, instruction: String, onApply: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDiscard,
        title = {
            Column {
                Text("Cambios propuestos", fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Pill("+${edit.added}", Ok); Pill("−${edit.removed}", Danger)
                    when (edit.validated) {
                        true -> Pill("smali válido ✔", Ok)
                        false -> Pill("smali con errores", Danger)
                        null -> {}
                    }
                }
            }
        },
        text = {
            Column {
                Text(instruction, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (edit.notes.isNotBlank()) Text(edit.notes, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                Box(Modifier.heightIn(max = 380.dp).background(Color(0xFF0A0C10), RoundedCornerShape(8.dp))) {
                    val lines = edit.diff.lines().ifEmpty { listOf("(sin cambios)") }
                    LazyColumn(Modifier.padding(8.dp)) {
                        items(lines) { l ->
                            val color = when {
                                l.startsWith("+") -> Ok; l.startsWith("-") -> Danger
                                l.startsWith("@@") -> AccentWarm; else -> Color(0xFFB8BECB)
                            }
                            Text(l, fontFamily = Mono, fontSize = 11.sp, color = color, maxLines = 1,
                                fontWeight = if (l.startsWith("@@")) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.horizontalScroll(rememberScrollState()))
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onApply) { Text("Aplicar") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Descartar") } },
    )
}
