package dev.apkforge.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.ResultKind
import dev.apkforge.app.core.TaskRunner
import dev.apkforge.app.core.Workers
import dev.apkforge.app.core.Workspace
import dev.apkforge.app.ui.Accent
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.Danger
import dev.apkforge.app.ui.ExportApkDialog
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Ok
import dev.apkforge.app.ui.Pill
import dev.apkforge.engine.ApkProject
import dev.apkforge.engine.BuildOptions
import dev.apkforge.engine.ProjectInfo
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectsNav(
    val openDir: (File) -> Unit,
    val agent: (File) -> Unit,
    val search: (File) -> Unit,
    val java: (File) -> Unit,
)

/**
 * Todos tus proyectos (APKs decompilados) en ApkTools/Proyectos, el más reciente primero. Desde
 * aquí pides a la IA que trabaje en uno, lo exportas como APK firmado para probarlo, buscas, etc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onBack: () -> Unit, nav: ProjectsNav) {
    val prefs = ApkForgeApp.prefs
    var refresh by remember { mutableIntStateOf(0) }
    var exportFor by remember { mutableStateOf<ProjectInfo?>(null) }
    var deleteFor by remember { mutableStateOf<ProjectInfo?>(null) }
    val projects by produceState<List<ProjectInfo>?>(null, refresh) {
        value = withContext(Workers.dispatcher) { runCatching { ApkProject.listProjects(Workspace.projects) }.getOrDefault(emptyList()) }
    }
    val studio by produceState<List<File>>(emptyList(), refresh) {
        value = withContext(Workers.dispatcher) {
            Workspace.studio.listFiles { f -> f.isDirectory }.orEmpty().sortedByDescending { it.lastModified() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Proyectos", fontSize = 18.sp)
                        Text("ApkTools/Proyectos", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { pad ->
        val list = projects
        when {
            list == null -> Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() && studio.isEmpty() -> Column(Modifier.padding(pad).padding(24.dp)) {
                Text("Aún no tienes proyectos.", fontWeight = FontWeight.SemiBold)
                Text("Abre un APK y pulsa “Decompilar” (o “Agente IA”, que lo decompila solo). El proyecto aparecerá aquí para editarlo, pedirle cosas a la IA y exportarlo firmado.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Edita (tú o la IA) y pulsa “Exportar APK”: se compila, se firma y queda en Compilados listo para instalar.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(list, key = { it.dir.absolutePath }) { p ->
                    ProjectCard(p,
                        onAgent = { nav.agent(p.dir) },
                        onExport = { exportFor = p },
                        onOpen = { nav.openDir(p.dir) },
                        onSearch = { nav.search(p.dir) },
                        onJava = { nav.java(p.dir) },
                        onDelete = { deleteFor = p })
                }
                if (studio.isNotEmpty()) {
                    item {
                        Text("Para Android Studio (PC)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                        Text("Código Java para abrir en el ordenador. No se compilan en el móvil: para editar y exportar aquí, usa los proyectos de arriba.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(studio, key = { it.absolutePath }) { d ->
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp), onClick = { nav.openDir(d) }) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Folder, null, tint = AccentWarm)
                                Spacer(Modifier.size(10.dp))
                                Text(d.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Pill("PC", AccentWarm)
                            }
                        }
                    }
                }
            }
        }
    }

    exportFor?.let { p ->
        ExportApkDialog(
            defaultName = Workspace.exportName(p.packageName ?: p.dir.name),
            detail = "Compila “${p.dir.name}” con todos los cambios (tuyos y de la IA) y lo firma, listo para instalar y probar.",
            onDismiss = { exportFor = null },
        ) { out ->
            TaskRunner.run("Exportando ${out.name}", ResultKind.APK) { log ->
                ApkProject.build(p.dir, out, BuildOptions(signing = prefs.signingConfig()), log)
            }
        }
    }
    deleteFor?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("¿Eliminar proyecto?") },
            text = { Text("Se borrará la carpeta ${p.dir.name} y todos sus cambios. Los APK ya exportados en Compilados no se tocan.") },
            confirmButton = { TextButton(onClick = { p.dir.deleteRecursively(); deleteFor = null; refresh++ }) { Text("Eliminar", color = Danger) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancelar") } },
        )
    }
}

private val dateFmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

@Composable
private fun ProjectCard(
    p: ProjectInfo, onAgent: () -> Unit, onExport: () -> Unit, onOpen: () -> Unit,
    onSearch: () -> Unit, onJava: () -> Unit, onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderSpecial, null, tint = Accent)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.dir.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((p.packageName ?: "") + "  ·  " + dateFmt.format(Date(p.dir.lastModified())),
                        fontSize = 11.sp, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Eliminar", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAgent, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Pedir a la IA")
                }
                Button(onClick = onExport, modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Ok.copy(alpha = 0.85f))) {
                    Icon(Icons.Default.Build, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Exportar APK")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Folder, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("Archivos", fontSize = 12.sp) }
                OutlinedButton(onClick = onSearch, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Search, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("Buscar", fontSize = 12.sp) }
                OutlinedButton(onClick = onJava, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Coffee, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("Java", fontSize = 12.sp) }
            }
        }
    }
}
