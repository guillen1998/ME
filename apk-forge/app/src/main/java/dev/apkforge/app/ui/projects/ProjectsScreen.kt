package dev.apkforge.app.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
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
    val backups: (File) -> Unit = {},
    val deobf: (File) -> Unit = {},
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
    var reportFor by remember { mutableStateOf<ProjectInfo?>(null) }
    var designFor by remember { mutableStateOf<ProjectInfo?>(null) }
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
                        onReport = { reportFor = p },
                        onDesign = { designFor = p },
                        onBackups = { nav.backups(p.dir) },
                        onDeobf = { nav.deobf(p.dir) },
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
            project = p.dir,
        ) { out ->
            TaskRunner.run("Exportando ${out.name}", ResultKind.APK, project = p.dir) { log ->
                prefs.exportProject(p.dir, out, log)
            }
        }
    }
    designFor?.let { p -> GridDialog(p.dir, onDismiss = { designFor = null; refresh++ }, onAskAi = { prompt ->
        designFor = null
        dev.apkforge.app.core.AgentLauncher.start(p.dir, prompt); nav.agent(p.dir)
    }) }
    reportFor?.let { p ->
        AlertDialog(
            onDismissRequest = { reportFor = null },
            title = { Text("Informe de cambios") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(dev.apkforge.engine.ChangeLog.report(p.dir), fontFamily = Mono, fontSize = 11.sp,
                        modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { reportFor = null }) { Text("Cerrar") } },
        )
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
    onSearch: () -> Unit, onJava: () -> Unit, onReport: () -> Unit, onDesign: () -> Unit, onDelete: () -> Unit,
    onBackups: () -> Unit = {},
    onDeobf: () -> Unit = {},
) {
    val changes = remember(p.dir) { dev.apkforge.engine.ChangeLog.all(p.dir) }
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
            OutlinedButton(onClick = onDeobf, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)); Text("Desofuscar y descifrar textos (app + IA)", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onBackups, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Icon(Icons.Default.History, null, Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)); Text("Copias de seguridad · restaurar / explicar con IA", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onDesign, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Icon(Icons.Default.GridView, null, Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)); Text("Diseño: listas → cuadrícula (filas y columnas)", fontSize = 12.sp)
            }
            androidx.compose.material3.TextButton(onClick = onReport) {
                Text(if (changes.isEmpty()) "Sin cambios todavía" else "Cambios: ${changes.size} en ${changes.map { it.file }.distinct().size} archivo(s) — ver informe",
                    fontSize = 12.sp, color = if (changes.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else AccentWarm)
            }
        }
    }
}


/** Cambia listas de filas a cuadrícula: la app lo hace sola, o se lo encarga a la IA con el contexto ya preparado. */
@Composable
private fun GridDialog(project: java.io.File, onDismiss: () -> Unit, onAskAi: (String) -> Unit) {
    var sites by remember { mutableStateOf<List<dev.apkforge.engine.LayoutPatcher.Site>?>(null) }
    var columns by remember { mutableStateOf(2) }
    var msg by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(reload) {
        sites = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.apkforge.engine.LayoutPatcher.find(project) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listas → cuadrícula") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Columnas:", fontSize = 13.sp)
                    (2..4).forEach { n ->
                        androidx.compose.material3.FilterChip(selected = columns == n, onClick = { columns = n }, label = { Text("$n") },
                            modifier = Modifier.padding(start = 6.dp))
                    }
                }
                msg?.let { Text(it, fontSize = 12.sp, color = AccentWarm, modifier = Modifier.padding(vertical = 6.dp)) }
                val l = sites
                when {
                    l == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    l.isEmpty() -> Text("No encontré listas con LinearLayoutManager. Pídeselo a la IA: buscará cómo está hecha esa pantalla.", fontSize = 12.sp)
                    else -> l.forEach { s ->
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.owner.substringAfterLast('.'), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${s.file.name}:${s.line} ${s.method}", fontSize = 10.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = {
                                    msg = runCatching { dev.apkforge.engine.LayoutPatcher.toGrid(project, s, columns) }.getOrElse { "✖ " + it.message }
                                    reload++
                                }) { Text("Cambiar") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAskAi("Quiero que la lista principal (por ejemplo la de canales/categorías) se vea en CUADRÍCULA de $columns columnas (filas y columnas) en vez de una fila por elemento. " +
                    "Usa find_lists para localizar las listas, identifica cuál es la pantalla correcta buscando su adaptador y textos, conviértela con list_to_grid " +
                    "y adapta el layout del elemento para que se vea bien en celdas (imagen arriba, texto centrado debajo, ancho match_parent).")
            }) { Text("Que lo haga la IA") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
