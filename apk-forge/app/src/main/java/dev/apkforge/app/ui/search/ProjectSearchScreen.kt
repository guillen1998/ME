package dev.apkforge.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.core.Workers
import dev.apkforge.app.ui.Mono
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Hit(val file: File, val line: Int, val text: String)

/** Búsqueda (y reemplazo) de texto en todos los archivos de un proyecto, como grep. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSearchScreen(root: File, onBack: () -> Unit, onOpen: (File, Int) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var regex by rememberSaveable { mutableStateOf(false) }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("smali") } // smali | xml | all
    var showReplace by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val hits = remember { mutableStateListOf<Hit>() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun matcher(): ((String) -> Boolean)? = runCatching {
        if (regex) {
            val r = if (matchCase) Regex(query) else Regex(query, RegexOption.IGNORE_CASE)
            ({ s: String -> r.containsMatchIn(s) })
        } else ({ s: String -> s.contains(query, ignoreCase = !matchCase) })
    }.getOrNull()

    fun files(): Sequence<File> = root.walkTopDown()
        .onEnter { it.name != ".apkforge" && it.name != "build" }
        .filter { it.isFile }
        .filter {
            when (filter) {
                "smali" -> it.extension == "smali"
                "xml" -> it.extension == "xml"
                else -> it.length() < 8 * 1024 * 1024 && it.extension in setOf("smali", "xml", "json", "txt", "java", "properties", "html", "js")
            }
        }

    fun runSearch() {
        val m = matcher() ?: run { status = "Expresión regular no válida"; return }
        hits.clear()
        job = scope.launch {
            var scanned = 0
            val found = mutableListOf<Hit>()
            withContext(Workers.dispatcher) {
                for (f in files()) {
                    ensureActive()
                    scanned++
                    if (scanned % 300 == 0) status = "Buscando… $scanned archivos, ${found.size} coincidencias"
                    runCatching {
                        f.useLines { lines ->
                            lines.forEachIndexed { i, line -> if (found.size < 2000 && m(line)) found += Hit(f, i + 1, line.trim()) }
                        }
                    }
                    if (found.size >= 2000) break
                }
            }
            hits.addAll(found)
            status = "${found.size} coincidencia(s) en $scanned archivos" + if (found.size >= 2000) " (límite)" else ""
        }
    }

    fun runReplaceAll() {
        val r = runCatching {
            if (regex) (if (matchCase) Regex(query) else Regex(query, RegexOption.IGNORE_CASE))
            else Regex(Regex.escape(query), if (matchCase) setOf() else setOf(RegexOption.IGNORE_CASE))
        }.getOrNull() ?: run { status = "Expresión regular no válida"; return }
        val targets = hits.map { it.file }.distinct()
        job = scope.launch {
            var changed = 0
            withContext(Workers.dispatcher) {
                for (f in targets) {
                    ensureActive()
                    val old = f.readText()
                    val new = if (regex) old.replace(r, replacement) else old.replace(r, Regex.escapeReplacement(replacement))
                    if (new != old) { f.writeText(new); changed++ }
                }
            }
            status = "Reemplazado en $changed archivo(s)"
            hits.clear()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Buscar en archivos", fontSize = 17.sp)
                        Text(root.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Texto, método, clase, const-string…") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    FilterChip(filter == "smali", { filter = "smali" }, label = { Text("smali") })
                    FilterChip(filter == "xml", { filter = "xml" }, label = { Text("xml") })
                    FilterChip(filter == "all", { filter = "all" }, label = { Text("todo") })
                    FilterChip(regex, { regex = !regex }, label = { Text(".*") })
                    FilterChip(matchCase, { matchCase = !matchCase }, label = { Text("Aa") })
                }
                if (showReplace) OutlinedTextField(replacement, { replacement = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("Reemplazar por") })
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Button(enabled = query.isNotEmpty() && job?.isActive != true, onClick = { runSearch() }) { Text("Buscar") }
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = { showReplace = !showReplace }) { Text(if (showReplace) "Ocultar reemplazo" else "Reemplazar…") }
                    if (showReplace && hits.isNotEmpty() && job?.isActive != true)
                        TextButton(onClick = { runReplaceAll() }) { Text("Reemplazar todo") }
                    if (job?.isActive == true) TextButton(onClick = { job?.cancel(); status = "Cancelado" }) { Text("Cancelar") }
                }
                if (job?.isActive == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(hits) { h ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(h.file, h.line) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(
                            h.file.relativeTo(root).path + ":" + h.line, fontSize = 12.sp, fontFamily = Mono,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(h.text, fontSize = 12.sp, fontFamily = Mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
