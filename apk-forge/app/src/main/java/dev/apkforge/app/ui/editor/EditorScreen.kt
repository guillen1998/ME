package dev.apkforge.app.ui.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.Workers
import dev.apkforge.app.ui.files.findProjectRoot
import dev.apkforge.engine.ApkProject
import dev.apkforge.engine.JavaDecompiler
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(file: File, initialLine: Int, onBack: () -> Unit, onShowText: (title: String, text: String, fileName: String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = ApkForgeApp.prefs
    val scope = rememberCoroutineScope()
    val controller = remember { CodeController() }

    var text by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var modified by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(prefs.editorWrap) }
    var fontSize by remember { mutableStateOf(prefs.editorFontSize) }
    var searchOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var askExit by remember { mutableStateOf(false) }
    var gotoOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(file) {
        withContext(Workers.dispatcher) {
            try {
                text = file.readText()
            } catch (t: Throwable) {
                loadError = t.message
            }
        }
    }

    fun save(then: (() -> Unit)? = null) {
        val content = controller.text()
        scope.launch {
            val ok = withContext(Workers.dispatcher) { runCatching { file.writeText(content) }.isSuccess }
            if (ok) { modified = false; Toast.makeText(ctx, "Guardado", Toast.LENGTH_SHORT).show(); then?.invoke() }
            else Toast.makeText(ctx, "No se pudo guardar", Toast.LENGTH_LONG).show()
        }
    }

    BackHandler { if (modified) askExit = true else onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (modified) askExit = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás")
                    }
                },
                title = {
                    Column {
                        Text((if (modified) "● " else "") + file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp)
                        Text(file.parent ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { controller.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Deshacer") }
                    IconButton(onClick = { controller.redo() }) { Icon(Icons.AutoMirrored.Filled.Redo, "Rehacer") }
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) controller.stopSearch() }) {
                        Icon(Icons.Default.Search, "Buscar")
                    }
                    IconButton(enabled = modified, onClick = { save() }) { Icon(Icons.Default.Save, "Guardar") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Más") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            if (file.extension == "smali") DropdownMenuItem(
                                text = { Text("Ver como Java") },
                                leadingIcon = { Icon(Icons.Default.Coffee, null) },
                                onClick = {
                                    menuOpen = false
                                    busy = true
                                    val content = controller.text()
                                    scope.launch {
                                        val result = withContext(Workers.dispatcher) {
                                            runCatching {
                                                val tmp = File(ctx.cacheDir, "view-" + file.name).apply { writeText(content) }
                                                val api = findProjectRoot(file)?.let { runCatching { ApkProject.readInfo(it).minSdk }.getOrNull() } ?: 26
                                                JavaDecompiler.smaliToJava(tmp, maxOf(api, 15), prefs.javaOptions()).also { tmp.delete() }
                                            }
                                        }
                                        busy = false
                                        result.onSuccess { onShowText(file.nameWithoutExtension + ".java", it, "x.java") }
                                            .onFailure { onShowText("Error", it.message ?: it.toString(), "error.txt") }
                                    }
                                },
                            )
                            DropdownMenuItem(text = { Text("Ir a línea…") }, onClick = { menuOpen = false; gotoOpen = true })
                            DropdownMenuItem(
                                text = { Text(if (wrap) "Desactivar ajuste de línea" else "Ajuste de línea") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.WrapText, null) },
                                onClick = { menuOpen = false; wrap = !wrap; prefs.editorWrap = wrap },
                            )
                            DropdownMenuItem(text = { Text("Texto más grande") }, onClick = {
                                fontSize = (fontSize + 1).coerceAtMost(24f); prefs.editorFontSize = fontSize
                            })
                            DropdownMenuItem(text = { Text("Texto más pequeño") }, onClick = {
                                fontSize = (fontSize - 1).coerceAtLeast(8f); prefs.editorFontSize = fontSize
                            })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (searchOpen) SearchBarPanel(controller) { searchOpen = false; controller.stopSearch() }
            val t = text
            when {
                loadError != null -> Text("No se pudo abrir: $loadError", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                t == null || busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> CodeView(
                    text = t, fileName = file.name, controller = controller,
                    wordWrap = wrap, fontSize = fontSize, initialLine = initialLine,
                    modifier = Modifier.fillMaxSize(),
                    onChanged = { modified = true },
                )
            }
        }
    }

    if (askExit) AlertDialog(
        onDismissRequest = { askExit = false },
        title = { Text("Cambios sin guardar") },
        text = { Text("¿Guardar los cambios en ${file.name}?") },
        confirmButton = { TextButton(onClick = { askExit = false; save { onBack() } }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { askExit = false; onBack() }) { Text("Descartar") } },
    )

    if (gotoOpen) {
        var line by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { gotoOpen = false },
            title = { Text("Ir a línea") },
            text = {
                OutlinedTextField(line, { line = it.filter(Char::isDigit) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = {
                TextButton(onClick = { line.toIntOrNull()?.let(controller::jumpTo); gotoOpen = false }) { Text("Ir") }
            },
        )
    }
}

@Composable
fun SearchBarPanel(controller: CodeController, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var matchCase by remember { mutableStateOf(false) }
    var showReplace by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(0 to 0) }

    fun refreshInfo() { info = controller.matchInfo() }
    LaunchedEffect(query, regex, matchCase) {
        controller.search(query, regex, matchCase)
        kotlinx.coroutines.delay(250); refreshInfo()
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("Buscar") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { controller.next(); refreshInfo() }),
                    supportingText = if (query.isNotEmpty()) ({ Text("${info.first}/${info.second}") }) else null,
                )
                IconButton(onClick = { controller.previous(); refreshInfo() }) { Icon(Icons.Default.KeyboardArrowUp, "Anterior") }
                IconButton(onClick = { controller.next(); refreshInfo() }) { Icon(Icons.Default.KeyboardArrowDown, "Siguiente") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cerrar") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(regex, { regex = !regex }, label = { Text(".*") })
                androidx.compose.foundation.layout.Spacer(Modifier.padding(3.dp))
                FilterChip(matchCase, { matchCase = !matchCase }, label = { Text("Aa") })
                androidx.compose.foundation.layout.Spacer(Modifier.padding(3.dp))
                FilterChip(showReplace, { showReplace = !showReplace }, label = { Text("Reemplazar") })
            }
            if (showReplace) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(replacement, { replacement = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("Reemplazar por") })
                TextButton(onClick = { controller.replace(replacement); refreshInfo() }) { Text("Uno") }
                TextButton(onClick = { controller.replaceAll(replacement); refreshInfo() }) { Text("Todos") }
            }
        }
    }
}
