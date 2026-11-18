package dev.apkforge.app.ui.apk

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.Installer
import dev.apkforge.app.core.ResultKind
import dev.apkforge.app.core.TaskRunner
import dev.apkforge.app.core.Workers
import dev.apkforge.app.core.Workspace
import dev.apkforge.app.core.humanSize
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.ActionTile
import dev.apkforge.app.ui.Danger
import dev.apkforge.app.ui.KeyValue
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Ok
import dev.apkforge.app.ui.Pill
import dev.apkforge.app.ui.SectionCard
import dev.apkforge.engine.ApkInfo
import dev.apkforge.engine.ApkInspector
import dev.apkforge.engine.ApkProject
import dev.apkforge.engine.ApkSigning
import dev.apkforge.engine.DecompileOptions
import kotlinx.coroutines.withContext
import java.io.File

private data class Loaded(val info: ApkInfo?, val error: String?, val icon: Drawable?, val label: String?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ApkInfoScreen(apk: File, onBack: () -> Unit, onOpenDir: (File) -> Unit, onOpenJava: (File) -> Unit,
                  onScan: (File) -> Unit = {}, onAgent: (File) -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = ApkForgeApp.prefs
    var showDecompile by remember { mutableStateOf(false) }

    val loaded by produceState<Loaded?>(null, apk) {
        value = withContext(Workers.dispatcher) {
            val pm = ctx.packageManager
            val pkgInfo = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_META_DATA) }.getOrNull()
            pkgInfo?.applicationInfo?.apply { sourceDir = apk.absolutePath; publicSourceDir = apk.absolutePath }
            val icon = runCatching { pkgInfo?.applicationInfo?.loadIcon(pm) }.getOrNull()
            val label = runCatching { pkgInfo?.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull()
            try {
                Loaded(ApkInspector.inspect(apk), null, icon, label)
            } catch (t: Throwable) {
                Loaded(null, t.message ?: t.toString(), icon, label)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(apk.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = { IconButton(onClick = { Installer.share(ctx, apk) }) { Icon(Icons.Default.Share, "Compartir") } },
            )
        },
    ) { pad ->
        val l = loaded
        if (l == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val info = l.info
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Cabecera
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                val bmp = remember(l.icon) { runCatching { l.icon?.toBitmap(144, 144)?.asImageBitmap() }.getOrNull() }
                if (bmp != null) Image(bmp, null, Modifier.size(56.dp))
                else Icon(Icons.Default.Android, null, Modifier.size(56.dp), tint = Ok)
                Spacer(Modifier.size(14.dp))
                Column {
                    Text(l.label ?: info?.appLabel ?: apk.nameWithoutExtension, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(info?.packageName ?: "—", fontFamily = Mono, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        "v${info?.versionName ?: "?"} (${info?.versionCode ?: "?"})  ·  ${apk.length().humanSize()}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            l.error?.let { Text("No se pudo analizar: $it", color = Danger) }

            // Acciones
            FlowRow(
                maxItemsInEachRow = 3,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val m = Modifier.weight(1f)
                ActionTile(Icons.Default.Unarchive, "Decompilar", m) { showDecompile = true }
                ActionTile(Icons.Default.Coffee, "Ver Java", m, tint = AccentWarm) { onOpenJava(apk) }
                ActionTile(Icons.Default.Draw, "Firmar", m, tint = Ok) {
                    val out = File(Workspace.builds, apk.nameWithoutExtension + "-firmado.apk")
                    TaskRunner.run("Firmando ${apk.name}", ResultKind.APK) { log ->
                        ApkSigning.sign(apk, out, prefs.signingConfig(), log)
                    }
                }
                ActionTile(Icons.Default.InstallMobile, "Instalar", m) { Installer.install(ctx, apk) }
                ActionTile(Icons.Default.Science, "Exportar a Android Studio (PC)", m, tint = Ok) {
                    val out = Workspace.unique(Workspace.projects, Workspace.safeName((info?.packageName ?: apk.nameWithoutExtension) + "_studio"))
                    TaskRunner.run("Exportando a Android Studio (para PC)", ResultKind.DIRECTORY) { log ->
                        dev.apkforge.engine.ApkProject.exportStudioProject(apk, out, prefs.javaOptions(), log)
                    }
                }
                ActionTile(Icons.Default.TravelExplore, "Analizar", m) { onScan(apk) }
                ActionTile(Icons.Default.AutoAwesome, "Agente IA", m, tint = AccentWarm) { onAgent(apk) }
            }

            if (info != null) {
                if (info.detections.isNotEmpty()) {
                    SectionCard("Detectado") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            info.detections.forEach { d ->
                                Pill(d, if (d.startsWith("Protección")) Danger else AccentWarm)
                            }
                        }
                    }
                }

                SectionCard("General") {
                    KeyValue("Paquete", info.packageName, mono = true)
                    KeyValue("Versión", "${info.versionName} (${info.versionCode})")
                    KeyValue("SDK mínimo", info.minSdk?.let { "$it  (${androidName(it)})" })
                    KeyValue("SDK objetivo", info.targetSdk?.let { "$it  (${androidName(it)})" })
                    KeyValue("Compilado con", info.compileSdk?.toString())
                    KeyValue("Application", info.applicationClass, mono = true)
                    KeyValue("Actividad principal", info.mainActivity, mono = true)
                    KeyValue("Depurable", if (info.debuggable) "Sí" else "No")
                    KeyValue("Dex", info.dexFiles.joinToString())
                    KeyValue("Archivos", info.entryCount.toString())
                    KeyValue("Ruta", apk.absolutePath, mono = true)
                }

                info.signature?.let { sig ->
                    SectionCard("Firma") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill(if (sig.verified) "Válida" else "No válida", if (sig.verified) Ok else Danger)
                            if (sig.v1) Pill("v1"); if (sig.v2) Pill("v2"); if (sig.v3) Pill("v3"); if (sig.v4) Pill("v4")
                        }
                        sig.signers.forEach { s ->
                            Spacer(Modifier.size(8.dp))
                            KeyValue("Titular", s.subject)
                            KeyValue("Algoritmo", s.algorithm)
                            KeyValue("Válido hasta", s.validUntil)
                            KeyValue("SHA-256", s.sha256, mono = true)
                            KeyValue("SHA-1", s.sha1, mono = true)
                        }
                        sig.errors.take(5).forEach { Text("• $it", color = Danger, fontSize = 12.sp) }
                    }
                }

                if (info.nativeAbis.isNotEmpty()) {
                    SectionCard("Librerías nativas") {
                        info.nativeAbis.forEach { (abi, libs) ->
                            ExpandableList("$abi (${libs.size})", libs)
                        }
                    }
                }

                SectionCard("Componentes") {
                    ExpandableList("Permisos (${info.permissions.size})", info.permissions)
                    ExpandableList("Actividades (${info.activities.size})", info.activities)
                    ExpandableList("Servicios (${info.services.size})", info.services)
                    ExpandableList("Receptores (${info.receivers.size})", info.receivers)
                    ExpandableList("Proveedores (${info.providers.size})", info.providers)
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    if (showDecompile) {
        DecompileDialog(
            onDismiss = { showDecompile = false },
            onStart = { res, dex ->
                showDecompile = false
                val info = loaded?.info
                val base = Workspace.safeName(
                    (info?.packageName ?: apk.nameWithoutExtension) + (info?.versionName?.let { "_v$it" } ?: "")
                )
                val out = Workspace.unique(Workspace.projects, base)
                TaskRunner.run("Decompilando ${apk.name}", ResultKind.DIRECTORY) { log ->
                    ApkProject.decompile(apk, out, DecompileOptions(decodeResources = res, decodeDex = dex), log)
                    out
                }
            },
        )
    }
}

@Composable
private fun DecompileDialog(onDismiss: () -> Unit, onStart: (Boolean, Boolean) -> Unit) {
    var res by remember { mutableStateOf(true) }
    var dex by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Decompilar") },
        text = {
            Column {
                CheckRow("Recursos y manifest a XML", "Edita strings, layouts, colores y el AndroidManifest", res) { res = it }
                CheckRow("Código dex a smali", "Edita la lógica de la app", dex) { dex = it }
                Spacer(Modifier.size(8.dp))
                Text(
                    "Desmarca lo que no vayas a tocar: decompila y compila más rápido, y es más seguro.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onStart(res, dex) }) { Text("Decompilar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun CheckRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange)
        Column {
            Text(title, fontSize = 14.sp)
            subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ExpandableList(title: String, items: List<String>) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
    if (open) SelectionContainer {
        Column(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
            items.forEach { Text(it, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

private fun androidName(api: Int) = when (api) {
    in 0..20 -> "Android 4.x"; 21, 22 -> "Android 5"; 23 -> "Android 6"; 24, 25 -> "Android 7"
    26, 27 -> "Android 8"; 28 -> "Android 9"; 29 -> "Android 10"; 30 -> "Android 11"; 31, 32 -> "Android 12"
    33 -> "Android 13"; 34 -> "Android 14"; 35 -> "Android 15"; 36 -> "Android 16"; else -> "Android ${api - 20}"
}
