package dev.apkforge.app.ui.workspace

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.ArchiveSessions
import dev.apkforge.app.core.Workers
import dev.apkforge.app.ui.Danger
import dev.apkforge.app.ui.editor.AiEditBar
import dev.apkforge.app.ui.editor.CodeController
import dev.apkforge.app.ui.editor.EditorModeSwitch
import dev.apkforge.app.ui.editor.CodeView
import dev.apkforge.app.ui.editor.SearchBarPanel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Edita un archivo DENTRO del APK sin decompilar. Si es XML binario (AndroidManifest, layouts…)
 * se muestra como texto y al aplicar se vuelve a codificar a binario con la tabla del propio APK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryEditorScreen(apk: File, entry: String, onBack: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val ctx = LocalContext.current
    val prefs = ApkForgeApp.prefs
    val scope = rememberCoroutineScope()
    val controller = remember { CodeController() }
    var aiMode by remember { mutableStateOf(ApkForgeApp.prefs.editorAiMode) }
    var text by remember { mutableStateOf<String?>(null) }
    var binaryXml by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var modified by remember { mutableStateOf(false) }
    var askExit by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    LaunchedEffect(apk, entry) {
        withContext(Workers.dispatcher) {
            runCatching {
                val a = ArchiveSessions.archive(apk)
                if (a.isBinaryXml(entry)) { binaryXml = true; a.decodeXml(entry) }
                else a.read(entry).toString(Charsets.UTF_8)
            }.onSuccess { text = it }.onFailure { error = it.message ?: it.toString() }
        }
    }

    fun apply(then: (() -> Unit)? = null) {
        val content = controller.text()
        scope.launch {
            val r = withContext(Workers.dispatcher) {
                runCatching {
                    val a = ArchiveSessions.archive(apk)
                    if (binaryXml) a.encodeXml(entry, content) else a.replace(entry, content.toByteArray(Charsets.UTF_8))
                }
            }
            r.onSuccess {
                modified = false
                ArchiveSessions.changed()
                Toast.makeText(ctx, "Aplicado. Pulsa Guardar en la pantalla del APK.", Toast.LENGTH_SHORT).show()
                then?.invoke()
            }.onFailure { error = it.message }
        }
    }

    BackHandler { if (modified) askExit = true else onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { if (modified) askExit = true else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                title = {
                    Column {
                        Text((if (modified) "● " else "") + entry.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                        Text(apk.name + " › " + entry + if (binaryXml) "  (XML binario)" else "", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    IconButton(onClick = { controller.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Deshacer") }
                    IconButton(onClick = { controller.redo() }) { Icon(Icons.AutoMirrored.Filled.Redo, "Rehacer") }
                    IconButton(onClick = { searchOpen = !searchOpen }) { Icon(Icons.Default.Search, "Buscar") }
                    IconButton(enabled = modified, onClick = { apply() }) { Icon(Icons.Default.Check, "Aplicar al APK") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            EditorModeSwitch(aiMode) { aiMode = it; ApkForgeApp.prefs.editorAiMode = it }
            if (aiMode) AiEditBar(apk.absolutePath + "!" + entry, entry.substringAfterLast('/'), controller, onOpenSettings) { modified = true }
            error?.let { Text(it, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(12.dp)) }
            if (searchOpen) SearchBarPanel(controller) { searchOpen = false; controller.stopSearch() }
            val t = text
            if (t == null && error == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            else if (t != null) CodeView(
                text = t, fileName = entry.substringAfterLast('/').ifEmpty { "x.txt" }, controller = controller,
                wordWrap = prefs.editorWrap, fontSize = prefs.editorFontSize, modifier = Modifier.fillMaxSize(),
                onChanged = { modified = true; error = null },
            )
        }
    }

    if (askExit) AlertDialog(
        onDismissRequest = { askExit = false },
        title = { Text("Cambios sin aplicar") },
        text = { Text("¿Aplicar los cambios de ${entry.substringAfterLast('/')} al APK?") },
        confirmButton = { TextButton(onClick = { askExit = false; apply { onBack() } }) { Text("Aplicar") } },
        dismissButton = { TextButton(onClick = { askExit = false; onBack() }) { Text("Descartar") } },
    )
}
