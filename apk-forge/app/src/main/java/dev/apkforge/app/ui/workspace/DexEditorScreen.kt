package dev.apkforge.app.ui.workspace

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.ArchiveSessions
import dev.apkforge.app.core.ResultKind
import dev.apkforge.app.core.TaskRunner
import dev.apkforge.app.core.Workers
import dev.apkforge.app.core.Workspace
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.Danger
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Pill
import dev.apkforge.app.ui.editor.CodeController
import dev.apkforge.app.ui.editor.CodeView
import dev.apkforge.app.ui.editor.SearchBarPanel
import dev.apkforge.engine.DexEditor
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Editor de dex rápido (como el "Dex Editor++" de MT): clases del APK sin decompilar todo;
 * editas el smali de UNA clase, se ensambla solo esa y se reinyecta en su dex.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexEditorScreen(apk: File, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ApkForgeApp.prefs
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var openType by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    val editor by produceState<Result<DexEditor>?>(null, apk) {
        value = withContext(Workers.dispatcher) { runCatching { ArchiveSessions.dexEditor(apk) } }
    }

    BackHandler(enabled = openType != null) { openType = null }

    val e = editor?.getOrNull()
    if (openType != null && e != null) {
        ClassSmaliEditor(e, openType!!, onClose = { openType = null; refresh++ })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                title = {
                    Column {
                        Text("Editor dex", fontSize = 17.sp)
                        Text(apk.name + (e?.let { "  ·  ${it.classes.size} clases" } ?: ""), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
            )
        },
        bottomBar = {
            val n = remember(refresh, e) { e?.modifiedCount ?: 0 }
            if (n > 0) Surface(color = AccentWarm.copy(alpha = 0.15f)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$n clase(s) modificada(s)", Modifier.weight(1f))
                    Button(onClick = {
                        val out = File(Workspace.builds, apk.nameWithoutExtension + "-dex.apk")
                        TaskRunner.run("Guardando APK con dex editado", ResultKind.APK) { log -> e!!.save(out, prefs.signingConfig(), log) }
                    }) { Icon(Icons.Default.Save, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Guardar APK") }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                editor == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                editor!!.isFailure -> Text("No se pudo abrir: ${editor!!.exceptionOrNull()?.message}", Modifier.padding(16.dp), color = Danger)
                else -> {
                    OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(10.dp),
                        leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Buscar clase (p. ej. MainActivity)") })
                    val list = remember(query, refresh) { e!!.search(query, 1500) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.type }) { c ->
                            Row(Modifier.fillMaxWidth().clickable { openType = c.type }.padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Memory, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.javaName.substringAfterLast('.'), fontFamily = Mono, fontSize = 13.sp)
                                    Text(c.javaName.substringBeforeLast('.', "") + "  ·  " + c.dex, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (e!!.isModified(c.type)) Pill("editada", AccentWarm)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassSmaliEditor(editor: DexEditor, type: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ApkForgeApp.prefs
    val scope = rememberCoroutineScope()
    val controller = remember { CodeController() }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var modified by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    LaunchedEffect(type) {
        text = withContext(Workers.dispatcher) { runCatching { editor.smali(type) }.getOrElse { "# Error: ${it.message}" } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                title = {
                    Column {
                        Text((if (modified) "● " else "") + DexEditor.typeToJava(type).substringAfterLast('.'), fontSize = 16.sp, maxLines = 1)
                        Text(DexEditor.typeToJava(type), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    if (editor.isModified(type)) TextButton(onClick = { editor.revert(type); onClose() }) { Text("Revertir") }
                    IconButton(onClick = { searchOpen = !searchOpen }) { Icon(Icons.Default.Search, "Buscar") }
                    IconButton(enabled = modified, onClick = {
                        val content = controller.text()
                        scope.launch {
                            val r = withContext(Workers.dispatcher) { runCatching { editor.replace(type, content) } }
                            r.onSuccess { modified = false; error = null; Toast.makeText(ctx, "Clase ensamblada ✔", Toast.LENGTH_SHORT).show() }
                                .onFailure { error = it.message }
                        }
                    }) { Icon(Icons.Default.Check, "Aplicar") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            error?.let { Text(it, color = Danger, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(10.dp)) }
            if (searchOpen) SearchBarPanel(controller) { searchOpen = false; controller.stopSearch() }
            val t = text
            if (t == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            else CodeView(text = t, fileName = "x.smali", controller = controller, wordWrap = prefs.editorWrap,
                fontSize = prefs.editorFontSize, modifier = Modifier.fillMaxSize(), onChanged = { modified = true })
        }
    }
}
