package dev.apkforge.app.ui.apps

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.apkforge.app.core.ResultKind
import dev.apkforge.app.core.TaskRunner
import dev.apkforge.app.core.Workers
import dev.apkforge.app.core.Workspace
import dev.apkforge.app.core.humanSize
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Pill
import kotlinx.coroutines.withContext
import java.io.File

private class AppItem(
    val label: String,
    val pkg: String,
    val version: String,
    val apk: File,
    val splits: List<File>,
    val system: Boolean,
    val icon: Drawable?,
) {
    var bitmap: ImageBitmap? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppsScreen(onBack: () -> Unit, onOpenApk: (File) -> Unit) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AppItem?>(null) }

    val apps by produceState<List<AppItem>?>(null) {
        value = withContext(Workers.dispatcher) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA).mapNotNull { ai ->
                val src = ai.sourceDir ?: return@mapNotNull null
                AppItem(
                    label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName),
                    pkg = ai.packageName,
                    version = runCatching { pm.getPackageInfo(ai.packageName, 0).versionName }.getOrNull() ?: "",
                    apk = File(src),
                    splits = ai.splitSourceDirs?.map(::File).orEmpty(),
                    system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    icon = runCatching { ai.loadIcon(pm) }.getOrNull(),
                )
            }.sortedBy { it.label.lowercase() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps instaladas") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Nombre o paquete") },
                )
                Spacer(Modifier.size(8.dp))
                FilterChip(showSystem, { showSystem = !showSystem }, label = { Text("Sistema") })
            }
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                val filtered = list.filter {
                    (showSystem || !it.system) &&
                        (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.pkg }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = app }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val bmp = remember(app) {
                                app.bitmap ?: runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull().also { app.bitmap = it }
                            }
                            if (bmp != null) Image(bmp, null, Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.pkg, fontSize = 12.sp, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (app.splits.isNotEmpty()) Pill("split")
                        }
                    }
                }
            }
        }
    }

    selected?.let { app ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(app.label) },
            text = {
                Column {
                    Text(app.pkg, fontFamily = Mono, fontSize = 13.sp)
                    Text("v${app.version}  ·  ${app.apk.length().humanSize()}", fontSize = 13.sp)
                    if (app.splits.isNotEmpty()) Text(
                        "App dividida en ${app.splits.size + 1} APKs (base + splits). Se extraen todos; el código está en base.apk.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selected = null
                    val out = Workspace.unique(Workspace.extracted, Workspace.safeName("${app.pkg}_v${app.version}"))
                    TaskRunner.run("Extrayendo ${app.label}", ResultKind.DIRECTORY) { log ->
                        out.mkdirs()
                        log.onProgress("Copiando base.apk…")
                        app.apk.copyTo(File(out, "base.apk"), overwrite = true)
                        app.splits.forEach { s ->
                            log.onProgress("Copiando ${s.name}…")
                            s.copyTo(File(out, s.name), overwrite = true)
                        }
                        log.onProgress("✔ Extraído en ${out.absolutePath}")
                        out
                    }
                }) { Text("Extraer APK") }
            },
            dismissButton = {
                TextButton(onClick = { selected = null; onOpenApk(app.apk) }) { Text("Abrir herramientas") }
            },
        )
    }
}
