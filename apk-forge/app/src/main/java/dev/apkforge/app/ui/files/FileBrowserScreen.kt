package dev.apkforge.app.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.Installer
import dev.apkforge.app.core.ResultKind
import dev.apkforge.app.core.Storage
import dev.apkforge.app.core.TaskRunner
import dev.apkforge.app.core.Workers
import dev.apkforge.app.core.Workspace
import dev.apkforge.app.core.humanSize
import dev.apkforge.app.ui.Accent
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.Ok
import dev.apkforge.app.ui.Pill
import dev.apkforge.engine.ApkProject
import dev.apkforge.engine.ApkSigning
import dev.apkforge.engine.BuildOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TEXT_EXT = setOf(
    "smali", "xml", "java", "kt", "json", "txt", "md", "properties", "yml", "yaml", "html", "htm", "css", "js",
    "gradle", "kts", "cfg", "conf", "ini", "log", "csv", "pro", "sh", "py", "mf", "sf", "svg", "plist", "toml",
)

fun isTextFile(f: File): Boolean {
    val ext = f.extension.lowercase()
    if (ext in TEXT_EXT) return true
    if (f.length() > 4 * 1024 * 1024) return false
    // Sin extensión conocida: miramos si los primeros bytes parecen texto.
    return runCatching {
        val buf = ByteArray(1024)
        val n = f.inputStream().use { it.read(buf) }
        n <= 0 || (0 until n).none { buf[it] == 0.toByte() }
    }.getOrDefault(false)
}

private object FileClipboard {
    var files: List<File> = emptyList()
    var cut = false
}

/** Busca hacia arriba la raíz de un proyecto de ApkForge. */
fun findProjectRoot(dir: File): File? {
    var d: File? = dir
    while (d != null) {
        if (ApkProject.isProject(d)) return d
        d = d.parentFile
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    startPath: String?,
    onOpenApk: (File) -> Unit,
    onOpenEditor: (File) -> Unit,
    onOpenBinary: (File) -> Unit,
    onOpenJava: (File) -> Unit,
    onSearch: (File) -> Unit,
    onScan: (File) -> Unit,
    onAgent: (File) -> Unit,
    onApps: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ApkForgeApp.prefs
    val scope = rememberCoroutineScope()

    var path by rememberSaveable {
        mutableStateOf(startPath ?: prefs.lastDir.takeIf { File(it).isDirectory } ?: Workspace.storage.absolutePath)
    }
    val dir = File(path)
    var refresh by remember { mutableIntStateOf(0) }
    var hasAccess by remember { mutableStateOf(Storage.hasAllFilesAccess(ctx)) }
    var showHidden by remember { mutableStateOf(prefs.showHidden) }
    var menuOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<File?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) } // "newFolder", "newFile", "rename", "delete"
    var clipboardCount by remember { mutableIntStateOf(FileClipboard.files.size) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasAccess = Storage.hasAllFilesAccess(ctx); refresh++
    }

    // Al volver de Ajustes (permiso de archivos) o del editor, refrescamos.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasAccess = Storage.hasAllFilesAccess(ctx); refresh++
        }
    }
    LaunchedEffect(path) { prefs.lastDir = path }

    val entries by produceState<List<File>?>(null, path, refresh, showHidden) {
        value = withContext(Workers.dispatcher) {
            dir.listFiles()?.filter { showHidden || !it.name.startsWith(".") }
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        }
    }
    val projectRoot = remember(path) { findProjectRoot(dir) }
    val storageRoot = Workspace.storage.absolutePath

    BackHandler(enabled = path != storageRoot && dir.parentFile != null) {
        path = dir.parentFile!!.absolutePath
    }

    fun buildProject(root: File) {
        val out = File(Workspace.builds, root.name + "-firmado.apk")
        TaskRunner.run("Compilando ${root.name}", ResultKind.APK) { log ->
            ApkProject.build(root, out, BuildOptions(signing = prefs.signingConfig()), log)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (path != storageRoot && dir.parentFile != null) {
                        IconButton(onClick = { path = dir.parentFile!!.absolutePath }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Subir")
                        }
                    }
                },
                title = {
                    Column {
                        Text(if (path == storageRoot) "ApkForge" else dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            path.removePrefix(storageRoot).ifEmpty { "/" },
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (clipboardCount > 0) {
                        IconButton(onClick = {
                            scope.launch {
                                val n = withContext(Workers.dispatcher) { paste(dir) }
                                Toast.makeText(ctx, "$n elemento(s) pegado(s)", Toast.LENGTH_SHORT).show()
                                clipboardCount = FileClipboard.files.size
                                refresh++
                            }
                        }) { Icon(Icons.Default.ContentPaste, "Pegar") }
                    }
                    IconButton(onClick = { onScan(projectRoot ?: dir) }) { Icon(Icons.Default.TravelExplore, "Analizar (URLs, secretos…)") }
                    IconButton(onClick = { onSearch(projectRoot ?: dir) }) { Icon(Icons.Default.Search, "Buscar en archivos") }
                    IconButton(onClick = onApps) { Icon(Icons.Default.Apps, "Apps instaladas") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Más") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Nueva carpeta") }, onClick = { menuOpen = false; dialog = "newFolder" })
                            DropdownMenuItem(text = { Text("Nuevo archivo") }, onClick = { menuOpen = false; dialog = "newFile" })
                            DropdownMenuItem(
                                text = { Text(if (showHidden) "Ocultar archivos ocultos" else "Mostrar archivos ocultos") },
                                onClick = { menuOpen = false; showHidden = !showHidden; prefs.showHidden = showHidden },
                            )
                            DropdownMenuItem(text = { Text("Actualizar") }, onClick = { menuOpen = false; refresh++ })
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Ajustes") }, leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { menuOpen = false; onSettings() },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Accesos rápidos
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = { path = storageRoot }, label = { Text("Almacenamiento") })
                AssistChip(onClick = { path = Workspace.downloads.absolutePath }, label = { Text("Descargas") })
                AssistChip(onClick = { path = Workspace.projects.absolutePath }, label = { Text("Proyectos") })
                AssistChip(onClick = { path = Workspace.builds.absolutePath }, label = { Text("Compilados") })
                AssistChip(onClick = { path = Workspace.extracted.absolutePath }, label = { Text("Extraídos") })
            }

            if (!hasAccess) {
                Surface(
                    color = AccentWarm.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = AccentWarm)
                            Spacer(Modifier.size(8.dp))
                            Text("Permiso de archivos necesario", fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            "ApkForge necesita acceso a todos los archivos para abrir APKs y guardar proyectos.",
                            fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Button(onClick = {
                            val i = Storage.allFilesAccessIntent(ctx)
                            if (i != null) ctx.startActivity(i) else permLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                )
                            )
                        }) { Text("Conceder permiso") }
                    }
                }
            }

            projectRoot?.let { root ->
                val info = remember(root) { runCatching { ApkProject.readInfo(root) }.getOrNull() }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderSpecial, null, tint = Accent)
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Proyecto: ${root.name}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(info?.packageName ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onAgent(root) }) { Icon(Icons.Default.AutoAwesome, "Agente IA") }
                        IconButton(onClick = { onOpenJava(root) }) { Icon(Icons.Default.Coffee, "Ver Java") }
                        Button(onClick = { buildProject(root) }) {
                            Icon(Icons.Default.Build, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Compilar")
                        }
                    }
                }
            }

            val list = entries
            when {
                list == null -> Box(Modifier.fillMaxSize())
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Carpeta vacía", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.absolutePath }) { f ->
                        FileRow(
                            file = f,
                            onClick = {
                                when {
                                    f.isDirectory -> path = f.absolutePath
                                    f.extension.equals("apk", true) -> onOpenApk(f)
                                    isTextFile(f) -> onOpenEditor(f)
                                    else -> onOpenBinary(f)
                                }
                            },
                            onLongClick = { selected = f },
                        )
                    }
                }
            }
        }
    }

    // Menú contextual
    selected?.let { f ->
        if (dialog == null) {
            FileActionsDialog(
                file = f,
                onDismiss = { selected = null },
                onAction = { action ->
                    when (action) {
                        "edit" -> { selected = null; onOpenEditor(f) }
                        "binary" -> { selected = null; onOpenBinary(f) }
                        "apk" -> { selected = null; onOpenApk(f) }
                        "java" -> { selected = null; onOpenJava(f) }
                        "build" -> { selected = null; buildProject(f) }
                        "sign" -> {
                            selected = null
                            val out = File(f.parentFile, f.nameWithoutExtension + "-firmado.apk")
                            TaskRunner.run("Firmando ${f.name}", ResultKind.APK) { log ->
                                ApkSigning.sign(f, out, prefs.signingConfig(), log)
                            }
                        }
                        "copy", "cut" -> {
                            FileClipboard.files = listOf(f); FileClipboard.cut = action == "cut"
                            clipboardCount = 1; selected = null
                            Toast.makeText(ctx, "Ve a otra carpeta y pulsa Pegar", Toast.LENGTH_SHORT).show()
                        }
                        "path" -> {
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("ruta", f.absolutePath))
                            selected = null
                        }
                        "share" -> { selected = null; runCatching { Installer.share(ctx, f) } }
                        "rename", "delete" -> dialog = action
                    }
                },
            )
        }
    }

    when (dialog) {
        "newFolder", "newFile" -> NameDialog(
            title = if (dialog == "newFolder") "Nueva carpeta" else "Nuevo archivo", initial = "",
            onDismiss = { dialog = null },
        ) { name ->
            val target = File(dir, name)
            val ok = if (dialog == "newFolder") target.mkdirs() else runCatching { target.createNewFile() }.getOrDefault(false)
            if (!ok) Toast.makeText(ctx, "No se pudo crear", Toast.LENGTH_SHORT).show()
            dialog = null; refresh++
        }
        "rename" -> selected?.let { f ->
            NameDialog("Renombrar", f.name, onDismiss = { dialog = null; selected = null }) { name ->
                if (!f.renameTo(File(f.parentFile, name))) Toast.makeText(ctx, "No se pudo renombrar", Toast.LENGTH_SHORT).show()
                dialog = null; selected = null; refresh++
            }
        }
        "delete" -> selected?.let { f ->
            AlertDialog(
                onDismissRequest = { dialog = null; selected = null },
                title = { Text("¿Eliminar?") },
                text = { Text("Se eliminará “${f.name}”" + if (f.isDirectory) " y todo su contenido." else ".") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Workers.dispatcher) { f.deleteRecursively() }
                            dialog = null; selected = null; refresh++
                        }
                    }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { dialog = null; selected = null }) { Text("Cancelar") } },
            )
        }
    }
}

private fun paste(dir: File): Int {
    var n = 0
    for (src in FileClipboard.files) {
        if (!src.exists()) continue
        val dst = Workspace.unique(dir, src.nameWithoutExtension.takeIf { src.isFile } ?: src.name,
            if (src.isFile && src.extension.isNotEmpty()) ".${src.extension}" else "")
        if (FileClipboard.cut && src.renameTo(dst)) { n++; continue }
        if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
        if (FileClipboard.cut) src.deleteRecursively()
        n++
    }
    if (FileClipboard.cut) FileClipboard.files = emptyList()
    return n
}

private val dateFmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

private fun iconFor(f: File): Pair<ImageVector, Color> = when {
    f.isDirectory && ApkProject.isProject(f) -> Icons.Default.FolderSpecial to Accent
    f.isDirectory -> Icons.Default.Folder to Color(0xFF8AB4F8)
    else -> when (f.extension.lowercase()) {
        "apk", "apks", "xapk" -> Icons.Default.Android to Ok
        "smali" -> Icons.Default.Code to Color(0xFF61AFEF)
        "java", "kt" -> Icons.Default.Coffee to AccentWarm
        "xml" -> Icons.Default.Code to Color(0xFFE06C75)
        "json" -> Icons.Default.DataObject to Color(0xFFD19A66)
        "png", "jpg", "jpeg", "webp", "gif" -> Icons.Default.Image to Color(0xFFC678DD)
        "dex", "so" -> Icons.Default.Memory to Color(0xFF56B6C2)
        "txt", "md" -> Icons.Default.Description to Color(0xFFA9AFBC)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFFA9AFBC)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: File, onClick: () -> Unit, onLongClick: () -> Unit) {
    val (icon, tint) = remember(file) { iconFor(file) }
    val isProject = remember(file) { file.isDirectory && ApkProject.isProject(file) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
                if (isProject) { Spacer(Modifier.size(6.dp)); Pill("proyecto") }
            }
            Text(
                (if (file.isFile) file.length().humanSize() + "  ·  " else "") + dateFmt.format(Date(file.lastModified())),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileActionsDialog(file: File, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    val isApk = file.isFile && file.extension.equals("apk", true)
    val isDex = file.isFile && file.extension.equals("dex", true)
    val isProject = file.isDirectory && ApkProject.isProject(file)
    val actions = buildList {
        if (isApk) { add("apk" to "Abrir herramientas APK"); add("java" to "Ver código Java"); add("sign" to "Firmar") }
        if (isDex) add("java" to "Ver código Java")
        if (isProject) { add("build" to "Compilar y firmar"); add("java" to "Ver código Java") }
        if (file.isFile) add("edit" to "Abrir en el editor de texto")
        if (file.isFile) add("binary" to "Abrir como binario (hex / strings / descifrar)")
        add("copy" to "Copiar"); add("cut" to "Cortar"); add("rename" to "Renombrar")
        add("path" to "Copiar ruta")
        if (file.isFile) add("share" to "Compartir / abrir con…")
        add("delete" to "Eliminar")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 17.sp) },
        text = {
            Column {
                actions.forEach { (id, label) ->
                    Text(
                        label,
                        color = if (id == "delete") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { onAction(id) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank() && '/' !in name, onClick = { onConfirm(name.trim()) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
