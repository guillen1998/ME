package dev.apkforge.app.ui.java

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
import dev.apkforge.app.core.JavaSessions
import dev.apkforge.app.core.Workers
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.editor.CodeController
import dev.apkforge.app.ui.editor.CodeView
import dev.apkforge.app.ui.editor.SearchBarPanel
import dev.apkforge.engine.ClassEntry
import dev.apkforge.engine.JavaDecompiler
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JavaBrowserScreen(source: File, onBack: () -> Unit, onOpenClass: (String) -> Unit) {
    val prefs = ApkForgeApp.prefs
    var session by remember { mutableStateOf<JavaDecompiler?>(JavaSessions.peek(source)) }
    var loadMsg by remember { mutableStateOf("Preparando…") }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(source) {
        if (session != null) return@LaunchedEffect
        withContext(Workers.dispatcher) {
            try {
                session = JavaSessions.get(source, prefs.javaOptions()) { loadMsg = it }
            } catch (t: Throwable) {
                error = if (t is OutOfMemoryError) "Memoria insuficiente para cargar esta app." else (t.message ?: t.toString())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Código Java", fontSize = 17.sp)
                        Text(source.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val s = session
            when {
                error != null -> Text("Error: $error", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                s == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.size(80.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.size(16.dp))
                    Text(loadMsg, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    TabRow(tab) {
                        Tab(tab == 0, { tab = 0 }, text = { Text("Clases (${s.classes.size})") })
                        Tab(tab == 1, { tab = 1 }, text = { Text("Buscar en código") })
                    }
                    if (tab == 0) ClassList(s, onOpenClass) else CodeSearch(s, onOpenClass)
                }
            }
        }
    }
}

private sealed interface Node {
    data class Pkg(val name: String, val count: Int, val open: Boolean) : Node
    data class Cls(val entry: ClassEntry) : Node
}

@Composable
private fun ClassList(session: JavaDecompiler, onOpenClass: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val expanded = remember { mutableStateListOf<String>() }
    val byPackage = remember(session) { session.classes.groupBy { it.packageName.ifEmpty { "(sin paquete)" } }.toSortedMap() }

    val nodes: List<Node> = remember(query, expanded.toList(), byPackage) {
        if (query.isNotBlank()) session.searchClasses(query, 1000).map { Node.Cls(it) }
        else buildList {
            byPackage.forEach { (pkg, list) ->
                val open = pkg in expanded
                add(Node.Pkg(pkg, list.size, open))
                if (open) list.forEach { add(Node.Cls(it)) }
            }
        }
    }

    Column {
        OutlinedTextField(
            query, { query = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Buscar clase (nombre o paquete)") },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(nodes, key = { if (it is Node.Pkg) "p:" + it.name else "c:" + (it as Node.Cls).entry.fullName }) { n ->
                when (n) {
                    is Node.Pkg -> Row(
                        Modifier.fillMaxWidth()
                            .clickable { if (n.open) expanded.remove(n.name) else expanded.add(n.name) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (n.open) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(20.dp))
                        Spacer(Modifier.size(6.dp))
                        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(10.dp))
                        Text(n.name, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${n.count}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is Node.Cls -> Row(
                        Modifier.fillMaxWidth().clickable { onOpenClass(n.entry.fullName) }
                            .padding(start = if (query.isBlank()) 50.dp else 14.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Coffee, null, tint = AccentWarm, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(n.entry.simpleName, fontFamily = Mono, fontSize = 13.sp)
                            if (query.isNotBlank()) Text(n.entry.packageName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (n.entry.rawName != n.entry.fullName) Text("orig: ${n.entry.rawName}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeSearch(session: JavaDecompiler, onOpenClass: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var pkg by rememberSaveable { mutableStateOf("") }
    var progress by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<Triple<String, Int, String>>() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    Column {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Texto a buscar (p. ej. una URL o mensaje)") })
            Spacer(Modifier.size(6.dp))
            OutlinedTextField(pkg, { pkg = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Solo en paquete (opcional, p. ej. com.app)") })
            Spacer(Modifier.size(8.dp))
            Row {
                Button(enabled = query.isNotBlank() && job?.isActive != true, onClick = {
                    results.clear()
                    job = scope.launch {
                        val found = withContext(Workers.dispatcher) {
                            session.searchCode(query, 300, pkg.ifBlank { null }, { progress = it }, { !isActive })
                        }
                        results.addAll(found)
                        progress = "${found.size} resultado(s)"
                    }
                }) { Text("Buscar") }
                if (job?.isActive == true) TextButton(onClick = { job?.cancel(); progress = "Cancelado" }) { Text("Cancelar") }
            }
            progress?.let {
                Spacer(Modifier.size(6.dp))
                if (job?.isActive == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Decompila cada clase para buscar: en apps grandes puede tardar. Filtra por paquete para ir más rápido.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(results) { (cls, line, text) ->
                Column(Modifier.fillMaxWidth().clickable { onOpenClass(cls) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("$cls:$line", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontFamily = Mono)
                    Text(text, fontSize = 12.sp, fontFamily = Mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JavaClassScreen(source: File, className: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ApkForgeApp.prefs
    var smali by rememberSaveable { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    val controller = remember(smali) { CodeController() }

    LaunchedEffect(smali) {
        code = null
        code = withContext(Workers.dispatcher) {
            runCatching {
                val s = JavaSessions.get(source, prefs.javaOptions())
                if (smali) s.smaliCode(className) else s.javaCode(className)
            }.getOrElse { "// Error al decompilar:\n// ${it.message}" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(className.substringAfterLast('.'), fontSize = 17.sp, maxLines = 1)
                        Text(className, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = {
                    TextButton(onClick = { smali = !smali }) { Text(if (smali) "Java" else "Smali") }
                    IconButton(onClick = { searchOpen = !searchOpen }) { Icon(Icons.Default.Search, "Buscar") }
                    IconButton(onClick = {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText(className, code ?: ""))
                        Toast.makeText(ctx, "Código copiado", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, "Copiar") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (searchOpen) SearchBarPanel(controller) { searchOpen = false; controller.stopSearch() }
            val c = code
            if (c == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else CodeView(
                text = c, fileName = if (smali) "x.smali" else "x.java", controller = controller,
                editable = false, wordWrap = prefs.editorWrap, fontSize = prefs.editorFontSize,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Visor de solo lectura para textos generados (p. ej. “Ver como Java” desde el editor de smali). */
object TextViewerHolder {
    var title: String = ""
    var text: String = ""
    var fileName: String = "x.txt"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextViewerScreen(onBack: () -> Unit) {
    val prefs = ApkForgeApp.prefs
    val controller = remember { CodeController() }
    var searchOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(TextViewerHolder.title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = { IconButton(onClick = { searchOpen = !searchOpen }) { Icon(Icons.Default.Search, "Buscar") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (searchOpen) SearchBarPanel(controller) { searchOpen = false; controller.stopSearch() }
            CodeView(
                text = TextViewerHolder.text, fileName = TextViewerHolder.fileName, controller = controller,
                editable = false, wordWrap = prefs.editorWrap, fontSize = prefs.editorFontSize,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
