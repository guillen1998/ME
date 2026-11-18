package dev.apkforge.app.ui.backups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.AiFactory
import dev.apkforge.app.ui.Accent
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.Danger
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Ok
import dev.apkforge.engine.ChangeLog
import dev.apkforge.engine.ProjectHistory
import dev.apkforge.engine.ProjectHistory.RestorePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fmt = SimpleDateFormat("EEE dd/MM/yy · HH:mm", Locale("es"))

/**
 * Copias de seguridad del proyecto ordenadas por fecha (la más reciente arriba). Cada tarjeta es una
 * tanda de cambios: puedes volver a cómo estaba ANTES, ver qué cambió, pedir a la IA que lo explique
 * o borrar la copia.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsScreen(project: File, onBack: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var points by remember { mutableStateOf<List<RestorePoint>?>(null) }
    var sizes by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var confirmRestore by remember { mutableStateOf<RestorePoint?>(null) }
    var confirmOriginal by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<RestorePoint?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<RestorePoint?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val explanations = remember { mutableStateMapOf<Long, String>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refresh) {
        val pts = withContext(Dispatchers.IO) { ProjectHistory.points(project) }
        points = pts
        sizes = withContext(Dispatchers.IO) { pts.associate { it.id to ProjectHistory.size(project, it) } }
    }

    fun work(block: () -> String) {
        busy = true
        scope.launch {
            message = withContext(Dispatchers.IO) { runCatching(block).getOrElse { "✖ ${it.message}" } }
            busy = false; refresh++
        }
    }

    fun explain(p: RestorePoint) {
        val client = AiFactory.textClient(ApkForgeApp.prefs) ?: run { explanations[p.id] = "La IA no está disponible."; return }
        explanations[p.id] = "…"
        scope.launch {
            explanations[p.id] = withContext(Dispatchers.IO) {
                runCatching { val (sys, user) = ProjectHistory.explainPrompt(project, p); client.complete(sys, user).trim() }
                    .getOrElse { "No se pudo explicar: ${it.message?.take(160)}" }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("Copias de seguridad", fontSize = 18.sp); Text(project.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            actions = {
                if (!points.isNullOrEmpty()) IconButton(onClick = { confirmDeleteAll = true }) { Icon(Icons.Default.DeleteSweep, "Borrar todas", tint = Danger) }
            },
        )
    }) { pad ->
        val list = points
        when {
            list == null -> Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Column(Modifier.padding(pad).padding(24.dp)) {
                Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                Text("Aún no hay copias.", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text("Cada vez que tú o la IA cambian algo se guarda una copia de cómo estaba antes, y aparece aquí ordenada por fecha.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Más reciente arriba. “Restaurar” deja el proyecto como estaba ANTES de esa tanda (y de las posteriores). Restaurar también se guarda aquí, así que se puede deshacer.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    message?.let { Text(it, fontSize = 13.sp, color = if (it.startsWith("✖")) Danger else Ok, modifier = Modifier.padding(top = 6.dp)) }
                    if (busy) CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp))
                }
                items(list, key = { it.id }) { p ->
                    PointCard(p, sizes[p.id] ?: 0, explanations[p.id],
                        onRestore = { confirmRestore = p }, onDetails = { details = p },
                        onExplain = { explain(p) }, onDelete = { confirmDelete = p })
                }
                item {
                    OutlinedButton(onClick = { confirmOriginal = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Icon(Icons.Default.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Volver al proyecto original (quitar todos los cambios)")
                    }
                }
            }
        }
    }

    confirmRestore?.let { p ->
        AlertDialog(onDismissRequest = { confirmRestore = null },
            title = { Text("¿Restaurar?") },
            text = { Text("El proyecto volverá a como estaba antes del ${fmt.format(Date(p.start))}. Se deshacen esos cambios y todos los posteriores. Podrás deshacer la restauración desde esta misma lista.") },
            confirmButton = { TextButton(onClick = { confirmRestore = null; work { "✔ " + ProjectHistory.restoreBefore(project, p).summary() } }) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancelar") } })
    }
    if (confirmOriginal) AlertDialog(onDismissRequest = { confirmOriginal = false },
        title = { Text("¿Volver al original?") },
        text = { Text("Se quitan todos los cambios (tuyos y de la IA). La restauración queda en la lista por si quieres deshacerla.") },
        confirmButton = { TextButton(onClick = { confirmOriginal = false; work { ProjectHistory.restoreOriginal(project)?.let { "✔ Original: " + it.summary() } ?: "Sin cambios" } }) { Text("Volver al original") } },
        dismissButton = { TextButton(onClick = { confirmOriginal = false }) { Text("Cancelar") } })
    confirmDelete?.let { p ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("¿Borrar esta copia?") },
            text = { Text("Se borra la copia del ${fmt.format(Date(p.start))} y su entrada del historial. Los archivos del proyecto NO cambian, pero ya no podrás volver a antes de ese momento.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; work { ProjectHistory.delete(project, p); "✔ Copia borrada" } }) { Text("Borrar", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } })
    }
    if (confirmDeleteAll) AlertDialog(onDismissRequest = { confirmDeleteAll = false },
        title = { Text("¿Borrar todas las copias?") },
        text = { Text("Libera espacio. El proyecto se queda tal como está ahora y el historial se vacía.") },
        confirmButton = { TextButton(onClick = { confirmDeleteAll = false; work { ProjectHistory.deleteAll(project); "✔ Copias borradas" } }) { Text("Borrar todas", color = Danger) } },
        dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancelar") } })
    details?.let { p ->
        AlertDialog(onDismissRequest = { details = null },
            title = { Text("Cambios del ${fmt.format(Date(p.start))}") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(ChangeLog.report(project, p.entries), fontFamily = Mono, fontSize = 11.sp,
                        modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Cerrar") } })
    }
}

@Composable
private fun PointCard(p: RestorePoint, bytes: Long, explanation: String?, onRestore: () -> Unit, onDetails: () -> Unit, onExplain: () -> Unit, onDelete: () -> Unit) {
    val whoColor = when {
        p.who.any { it.startsWith("IA") } -> Accent
        p.who.contains("Restaurar") -> AccentWarm
        else -> Ok
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp), onClick = onDetails) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, null, tint = whoColor)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(fmt.format(Date(p.start)), fontWeight = FontWeight.SemiBold)
                    Text("${p.who.joinToString()} · ${p.files.size} archivo(s) · +${p.added} / −${p.removed} · ${human(bytes)}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Borrar copia", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            p.entries.mapNotNull { it.note.takeIf { n -> n.isNotBlank() } }.distinct().take(2).forEach {
                Text("• $it", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(p.files.take(4).joinToString { it.substringAfterLast('/') } + if (p.files.size > 4) "…" else "",
                fontSize = 11.sp, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            explanation?.let {
                Surface(color = Accent.copy(alpha = 0.10f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    if (it == "…") Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)); Text("La IA está leyendo los cambios…", fontSize = 12.sp)
                    } else Text("🤖 $it", fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onExplain, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("Explicar con IA", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Restore, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("Restaurar antes", fontSize = 12.sp)
                }
            }
        }
    }
}

private fun human(b: Long) = when {
    b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}
