package dev.apkforge.app.ui.workspace

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.ArchiveSessions
import dev.apkforge.app.core.Installer
import dev.apkforge.app.core.ResultKind
import dev.apkforge.app.core.TaskRunner
import dev.apkforge.app.core.Workers
import dev.apkforge.app.core.Workspace
import dev.apkforge.app.core.humanSize
import dev.apkforge.app.ui.Accent
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.Danger
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Ok
import dev.apkforge.app.ui.Pill
import dev.apkforge.engine.ApkArchive
import dev.apkforge.engine.ApkProject
import dev.apkforge.engine.ApkSigning
import dev.apkforge.engine.DecompileOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Acciones del panel izquierdo (hacia otras pantallas). */
class WorkspaceNav(
    val info: (File) -> Unit,
    val java: (File) -> Unit,
    val dexEditor: (File) -> Unit,
    val scan: (File) -> Unit,
    val agent: (File) -> Unit,
    val openDir: (File) -> Unit,
    val editEntry: (apk: File, entry: String) -> Unit,
    val binary: (File) -> Unit,
)

/**
 * Pantalla doble estilo MT Manager: a la IZQUIERDA las opciones/herramientas y a la DERECHA
 * el interior del APK (carpetas y archivos), que puedes abrir, editar, extraer, reemplazar o
 * borrar sin decompilar. Los cambios se guardan como un APK nuevo firmado.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ApkWorkspaceScreen(apk: File, onBack: () -> Unit, nav: WorkspaceNav) {
    val ctx = LocalContext.current
    val prefs = ApkForgeApp.prefs
    val scope = rememberCoroutineScope()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var showLeft by rememberSaveable { mutableStateOf(true) }
    var dir by rememberSaveable(apk.absolutePath) { mutableStateOf("") }
    var selected by remember { mutableStateOf<ApkArchive.Node?>(null) }
    var preview by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var replaceTarget by remember { mutableStateOf<String?>(null) }
    val version by ArchiveSessions.version.collectAsState()
    var showExport by remember { mutableStateOf(false) }

    val archive by produceState<Result<ApkArchive>?>(null, apk) {
        value = withContext(Workers.dispatcher) { runCatching { ArchiveSessions.archive(apk) } }
    }
    val nodes by produceState<List<ApkArchive.Node>?>(null, archive, dir, version) {
        val a = archive?.getOrNull()
        value = if (a == null) null else withContext(Workers.dispatcher) { runCatching { a.list(dir) }.getOrDefault(emptyList()) }
    }
    val pending = archive?.getOrNull()?.let { a -> version.let { a.pendingCount } } ?: 0

    BackHandler(enabled = dir.isNotEmpty()) { dir = dir.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val target = replaceTarget ?: return@rememberLauncherForActivityResult
        replaceTarget = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Workers.dispatcher) {
                runCatching {
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val a = archive!!.getOrThrow()
                    if (target.endsWith("/")) {
                        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "nuevo.bin"
                        a.replace(target + name, bytes)
                    } else a.replace(target, bytes)
                }.isSuccess
            }
            ArchiveSessions.changed()
            Toast.makeText(ctx, if (ok) "Cambio añadido (pendiente de guardar)" else "No se pudo leer el archivo", Toast.LENGTH_SHORT).show()
        }
    }

    fun openEntry(n: ApkArchive.Node) {
        val a = archive?.getOrNull() ?: return
        val ext = n.name.substringAfterLast('.', "").lowercase()
        when {
            ext == "dex" -> nav.dexEditor(apk)
            ext == "xml" || ext in setOf("json", "txt", "properties", "html", "htm", "js", "css", "md", "cfg", "ini", "csv", "kotlin_builtins", "version") ->
                nav.editEntry(apk, n.path)
            ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") -> scope.launch {
                preview = withContext(Workers.dispatcher) { runCatching { n.path to a.read(n.path) }.getOrNull() }
            }
            ext == "apk" || ext == "jar" || ext == "zip" -> scope.launch {
                val f = withContext(Workers.dispatcher) { a.extract(n.path, ctx.cacheDir) }
                nav.info(f)
            }
            else -> scope.launch {
                val f = withContext(Workers.dispatcher) { a.extract(n.path, ctx.cacheDir) }
                nav.binary(f)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                title = {
                    Column {
                        Text(apk.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                        Text("/" + dir, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    IconButton(onClick = { showLeft = !showLeft }) { Icon(Icons.Default.MenuOpen, if (showLeft) "Ocultar opciones" else "Mostrar opciones") }
                },
            )
        },
        bottomBar = {
            if (pending > 0) Surface(color = AccentWarm.copy(alpha = 0.15f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$pending cambio(s) sin guardar", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    TextButton(onClick = { archive?.getOrNull()?.discard(); ArchiveSessions.changed() }) { Text("Descartar") }
                    Button(onClick = { showExport = true }) {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Exportar y firmar")
                    }
                }
            }
        },
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
            // ---------------- Panel IZQUIERDO: opciones ----------------
            if (showLeft) {
                Column(
                    Modifier.width(if (wide) 240.dp else 132.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                ) {
                    SideItem(Icons.Default.Save, "Exportar nuevo APK y firmar", Ok) { showExport = true }
                    SideItem(Icons.Default.Info, "Información", Accent) { nav.info(apk) }
                    SideItem(Icons.Default.Unarchive, "Decompilar", Accent) {
                        val out = Workspace.unique(Workspace.projects, Workspace.safeName(apk.nameWithoutExtension))
                        TaskRunner.run("Decompilando ${apk.name}", ResultKind.DIRECTORY) { log ->
                            ApkProject.decompile(apk, out, DecompileOptions(), log); out
                        }
                    }
                    SideItem(Icons.Default.Coffee, "Ver Java", AccentWarm) { nav.java(apk) }
                    SideItem(Icons.Default.Memory, "Editor dex", Color(0xFF56B6C2)) { nav.dexEditor(apk) }
                    SideItem(Icons.Default.EditNote, "Manifest", Color(0xFFE06C75)) { nav.editEntry(apk, "AndroidManifest.xml") }
                    SideItem(Icons.Default.TravelExplore, "Analizar", Accent) { nav.scan(apk) }
                    SideItem(Icons.Default.AutoAwesome, "Agente IA", AccentWarm) { nav.agent(apk) }
                    SideItem(Icons.Default.Science, "Proyecto Android", Ok) {
                        val out = Workspace.unique(Workspace.projects, Workspace.safeName(apk.nameWithoutExtension + "_studio"))
                        TaskRunner.run("Reconstruyendo proyecto Android Studio", ResultKind.DIRECTORY) { log ->
                            ApkProject.exportStudioProject(apk, out, prefs.javaOptions(), log)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SideItem(Icons.Default.Draw, "Firmar", Ok) {
                        val out = File(Workspace.builds, apk.nameWithoutExtension + "-firmado.apk")
                        TaskRunner.run("Firmando ${apk.name}", ResultKind.APK) { log -> ApkSigning.sign(apk, out, prefs.signingConfig(), log) }
                    }
                    SideItem(Icons.Default.InstallMobile, "Instalar", Accent) { Installer.install(ctx, apk) }
                    SideItem(Icons.Default.Share, "Compartir", Accent) { Installer.share(ctx, apk) }
                }
                VerticalDivider()
            }

            // ---------------- Panel DERECHO: interior del APK ----------------
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // Migas de pan
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Crumb(apk.nameWithoutExtension.take(18)) { dir = "" }
                    var acc = ""
                    dir.trimEnd('/').split('/').filter { it.isNotEmpty() }.forEach { part ->
                        acc += "$part/"; val target = acc
                        Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Crumb(part) { dir = target }
                    }
                }
                HorizontalDivider()
                val list = nodes
                when {
                    archive?.isFailure == true -> Text("No se pudo abrir el APK: ${archive?.exceptionOrNull()?.message}", Modifier.padding(16.dp), color = Danger)
                    list == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Vacío", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.path }) { n ->
                            val changed = archive?.getOrNull()?.pendingPaths()?.containsKey(n.path) == true
                            EntryRow(n, changed,
                                onClick = { if (n.isDir) dir = n.path else openEntry(n) },
                                onLongClick = { selected = n })
                        }
                    }
                }
            }
        }
    }

    if (showExport) {
        val a = archive?.getOrNull()
        val n = a?.pendingCount ?: 0
        dev.apkforge.app.ui.ExportApkDialog(
            defaultName = Workspace.exportName(apk.nameWithoutExtension),
            detail = if (n > 0) "Aplica tus $n cambio(s) dentro del APK, lo alinea y lo firma." else "Sin cambios: crea una copia alineada y firmada con tu clave.",
            onDismiss = { showExport = false },
        ) { out ->
            TaskRunner.run("Exportando ${out.name}", ResultKind.APK) { log ->
                if (a != null && a.pendingCount > 0) a.save(out, prefs.signingConfig(), log).also { a.discard(); ArchiveSessions.changed() }
                else ApkSigning.sign(apk, out, prefs.signingConfig(), log)
            }
        }
    }

    // Acciones sobre una entrada
    selected?.let { n ->
        val a = archive?.getOrNull()
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(n.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 17.sp) },
            text = {
                Column {
                    Text(if (n.isDir) "${n.count} archivos · ${n.size.humanSize()}"
                        else "${n.size.humanSize()} · ${if (n.stored) "sin comprimir" else "comprimido ${n.compressed.humanSize()}"}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    val actions = buildList {
                        if (!n.isDir) { add("open" to "Abrir"); add("hex" to "Ver como binario / descifrar"); add("ai" to "Analizar con IA") }
                        add("extract" to "Extraer a ${Workspace.extracted.name}/")
                        add("replace" to if (n.isDir) "Añadir archivo aquí…" else "Reemplazar por otro archivo…")
                        add("delete" to "Eliminar del APK")
                    }
                    actions.forEach { (id, label) ->
                        Text(label, color = if (id == "delete") Danger else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable {
                                selected = null
                                when (id) {
                                    "open" -> openEntry(n)
                                    "hex", "ai" -> scope.launch {
                                        val f = withContext(Workers.dispatcher) { a!!.extract(n.path, ctx.cacheDir) }
                                        if (id == "hex") nav.binary(f) else nav.agent(f)
                                    }
                                    "extract" -> TaskRunner.run("Extrayendo ${n.name}", ResultKind.DIRECTORY) { log ->
                                        val outDir = File(Workspace.extracted, apk.nameWithoutExtension)
                                        val paths = if (n.isDir) collectFiles(a!!, n.path) else listOf(n.path)
                                        paths.forEachIndexed { i, p ->
                                            File(outDir, p).apply { parentFile?.mkdirs() }.writeBytes(a!!.read(p))
                                            log.onPercent((i + 1) * 100 / paths.size)
                                        }
                                        log.onProgress("✔ ${paths.size} archivo(s) en ${outDir.absolutePath}")
                                        outDir
                                    }
                                    "replace" -> { replaceTarget = n.path; picker.launch("*/*") }
                                    "delete" -> { a?.delete(n.path); ArchiveSessions.changed() }
                                }
                            }.padding(vertical = 11.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Cerrar") } },
        )
    }

    // Vista previa de imágenes
    preview?.let { (path, bytes) ->
        val bmp = remember(bytes) { runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(path.substringAfterLast('/'), fontSize = 15.sp) },
            text = {
                if (bmp != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bmp, null, Modifier.fillMaxWidth().heightIn(max = 360.dp).background(Color(0xFF2A2F38)))
                    Text("${bmp.width}×${bmp.height} · ${bytes.size.toLong().humanSize()}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                } else Text("No es una imagen que Android pueda mostrar (¿vector XML o .9.png?).")
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Cerrar") } },
        )
    }
}

private fun collectFiles(a: ApkArchive, dir: String): List<String> =
    a.list(dir).flatMap { if (it.isDir) collectFiles(a, it.path) else listOf(it.path) }

@Composable
private fun SideItem(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Crumb(text: String, onClick: () -> Unit) {
    Text(text, fontSize = 12.sp, color = Accent, fontFamily = Mono,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp))
}

private fun iconFor(n: ApkArchive.Node): Pair<ImageVector, Color> = when {
    n.isDir -> Icons.Default.Folder to Color(0xFF8AB4F8)
    else -> when (n.name.substringAfterLast('.', "").lowercase()) {
        "dex" -> Icons.Default.Memory to Color(0xFF56B6C2)
        "so" -> Icons.Default.Build to Color(0xFFC678DD)
        "xml" -> Icons.Default.Code to Color(0xFFE06C75)
        "arsc" -> Icons.Default.DataObject to AccentWarm
        "json" -> Icons.Default.DataObject to Color(0xFFD19A66)
        "png", "jpg", "jpeg", "webp", "gif" -> Icons.Default.Image to Color(0xFFC678DD)
        "apk", "jar", "zip" -> Icons.Default.Android to Ok
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFFA9AFBC)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(n: ApkArchive.Node, changed: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val (icon, tint) = iconFor(n)
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (changed) { Spacer(Modifier.size(6.dp)); Pill("modificado", AccentWarm) }
            }
            Text(if (n.isDir) "${n.count} archivos" else n.size.humanSize() + if (n.stored) " · sin comprimir" else "",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
