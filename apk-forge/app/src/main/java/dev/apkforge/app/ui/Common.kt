package dev.apkforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.core.Installer
import dev.apkforge.app.core.ResultKind
import dev.apkforge.app.core.TaskRunner
import java.io.File

/** Consola de la tarea en curso (decompilar, compilar, firmar…) con log en vivo. */
@Composable
fun TaskConsole(onOpenDir: (File) -> Unit, onOpenFile: (File) -> Unit, onOpenAgent: (File) -> Unit = {},
                onOpenFileAt: (File, Int) -> Unit = { f, _ -> onOpenFile(f) }) {
    val state by TaskRunner.state.collectAsState()
    val s = state ?: return
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(s.logs.size) { if (s.logs.isNotEmpty()) listState.scrollToItem(s.logs.size - 1) }

    AlertDialog(
        onDismissRequest = { if (!s.running) TaskRunner.dismiss() },
        icon = {
            when {
                s.running -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                s.error != null -> Icon(Icons.Default.Error, null, tint = Danger)
                else -> Icon(Icons.Default.CheckCircle, null, tint = Ok)
            }
        },
        title = { Text(s.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (s.running && s.percent in 0..100) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${s.percent}%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
                }
                Surface(
                    color = Color(0xFF0A0C10),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp),
                ) {
                    SelectionContainer {
                        LazyColumn(state = listState, modifier = Modifier.padding(10.dp)) {
                            itemsIndexed(s.logs) { _, line ->
                                Text(
                                    line, fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp,
                                    color = when {
                                        line.startsWith("✔") -> Ok
                                        line.startsWith("⚠") -> AccentWarm
                                        else -> Color(0xFFB8BECB)
                                    },
                                )
                            }
                        }
                    }
                }
                s.error?.let {
                    Spacer(Modifier.size(10.dp))
                    SelectionContainer {
                        Text(
                            it, color = Danger, fontFamily = Mono, fontSize = 12.sp,
                            modifier = Modifier.heightIn(max = 220.dp),
                        )
                    }
                }
                s.finishedAt?.let {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "Tiempo: %.1f s".format((it - s.startedAt) / 1000.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (s.running) {
                    TextButton(onClick = { TaskRunner.cancel() }) { Text("Cancelar") }
                } else {
                    val r = s.result
                    if (r != null && s.error == null) when (s.resultKind) {
                        ResultKind.APK -> {
                            TextButton(onClick = { Installer.share(ctx, r) }) { Text("Compartir") }
                            TextButton(onClick = { Installer.install(ctx, r) }) { Text("Instalar") }
                        }
                        ResultKind.DIRECTORY -> {
                            if (dev.apkforge.engine.ApkProject.isProject(r)) TextButton(onClick = { TaskRunner.dismiss(); onOpenAgent(r) }) { Text("Pedir a la IA") }
                            TextButton(onClick = { TaskRunner.dismiss(); onOpenDir(r) }) { Text("Abrir") }
                        }
                        ResultKind.FILE -> TextButton(onClick = { TaskRunner.dismiss(); onOpenFile(r) }) { Text("Abrir") }
                        ResultKind.NONE -> {}
                    }
                    s.error?.let { err ->
                        val loc = androidx.compose.runtime.remember(err) { dev.apkforge.engine.BuildErrors.locate(err) }
                        if (loc != null && loc.file.isFile) TextButton(onClick = { TaskRunner.dismiss(); onOpenFileAt(loc.file, loc.line) }) { Text("Ir al error") }
        val projForUndo = s.project
        if (loc != null && projForUndo != null && dev.apkforge.engine.BuildDoctor.canRevert(projForUndo, loc.file))
            TextButton(onClick = { dev.apkforge.engine.BuildDoctor.revert(projForUndo, loc.file, loc.message); TaskRunner.dismiss() }) { Text("Deshacer ese archivo") }
                        val proj = s.project
                        if (proj != null && err != "Cancelado") TextButton(onClick = {
                            TaskRunner.dismiss()
                            dev.apkforge.app.core.AgentLauncher.start(proj, dev.apkforge.engine.BuildErrors.fixPrompt(proj, err))
                            onOpenAgent(proj)
                        }) { Text("Corregir con la IA") }
                    }
                    TextButton(onClick = { TaskRunner.dismiss() }) { Text("Cerrar") }
                }
            }
        },
    )
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String?, mono: Boolean = false) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            key, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.width(120.dp),
        )
        SelectionContainer {
            Text(value, fontSize = 13.sp, fontFamily = if (mono) Mono else null)
        }
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun ActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, tint = tint)
            Spacer(Modifier.size(6.dp))
            Text(label, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Diálogo único "Exportar nuevo APK y firmar": nombre del archivo y con qué clave se firma.
 * El APK se guarda en ApkTools/Compilados sin sobrescribir los anteriores.
 */
@Composable
fun ExportApkDialog(defaultName: String, detail: String, onDismiss: () -> Unit, project: File? = null, onExport: (File) -> Unit) {
    val prefs = dev.apkforge.app.ApkForgeApp.prefs
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(defaultName) }
    val signing = if (prefs.signMode == "keystore" && prefs.keystorePath.isNotBlank())
        "Tu keystore: " + File(prefs.keystorePath).name else "Clave de prueba de ApkForge (cámbiala en Ajustes)"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exportar nuevo APK y firmar") },
        text = {
            Column {
                Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                project?.let { pd ->
                    val all = androidx.compose.runtime.remember(pd) { dev.apkforge.engine.ChangeLog.all(pd) }
                    val pending = androidx.compose.runtime.remember(pd) { dev.apkforge.engine.ChangeLog.sinceLastExport(pd) }
                    Spacer(Modifier.size(6.dp))
                    if (all.isEmpty()) Text("⚠ Este proyecto no tiene cambios registrados: el APK saldrá igual que el original.",
                        fontSize = 12.sp, color = AccentWarm)
                    else Text("Incluye ${all.size} cambio(s) en ${all.map { it.file }.distinct().size} archivo(s)" +
                        (if (pending.isNotEmpty()) " · ${pending.size} nuevo(s) desde la última exportación" else ""),
                        fontSize = 12.sp, color = Ok)
                }
                Spacer(Modifier.size(10.dp))
                androidx.compose.material3.OutlinedTextField(name, { name = it.replace("/", "_") }, singleLine = true,
                    label = { Text("Nombre") }, suffix = { Text(".apk") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                Text("Firma: $signing", fontSize = 12.sp)
                if (project != null) {
                    var crash by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.crashCatcher) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        androidx.compose.material3.Checkbox(crash, { crash = it; prefs.crashCatcher = it })
                        Text("Capturar cierres: si al abrir se cierra, deja escrito el error para que la IA lo arregle y pueda diagnosticarlo",
                            fontSize = 12.sp)
                    }
                    var repair by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.autoRepair) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        androidx.compose.material3.Checkbox(repair, { repair = it; prefs.autoRepair = it })
                        Text("Reparar errores solo: la app y la IA corrigen lo que no compila y, si no pueden, deshacen solo ese archivo",
                            fontSize = 12.sp)
                    }
                }
                Text("Se guardará en ApkTools/Compilados" + (if (project != null) " junto con el informe de cambios" else ""),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(enabled = name.isNotBlank(), onClick = {
                onDismiss()
                onExport(dev.apkforge.app.core.Workspace.unique(dev.apkforge.app.core.Workspace.builds,
                    dev.apkforge.app.core.Workspace.safeName(name), ".apk"))
            }) { Text("Exportar y firmar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Elemento del panel lateral de opciones (estilo MT Manager). */
@Composable
fun SidePanelItem(icon: ImageVector, label: String, tint: Color, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
