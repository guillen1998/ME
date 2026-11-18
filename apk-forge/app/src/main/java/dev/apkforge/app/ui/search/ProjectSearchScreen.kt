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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.core.SearchStore
import dev.apkforge.app.core.Workers
import dev.apkforge.app.ui.Mono
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Búsqueda en archivos: los resultados salen EN VIVO mientras busca y se pueden abrir; al volver,
 * la búsqueda sigue (o ya terminó) y la lista está intacta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSearchScreen(root: File, onBack: () -> Unit, onOpen: (File, Int) -> Unit) {
    // Siempre mostramos la última búsqueda (aunque abras el buscador desde otra carpeta).
    val lastRoot = SearchStore.root
    val sameRoot = lastRoot != null
    var query by rememberSaveable { mutableStateOf(SearchStore.query) }
    var replacement by rememberSaveable { mutableStateOf("") }
    var regex by rememberSaveable { mutableStateOf(false) }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("smali") }
    var showReplace by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val running by SearchStore.running
    val status by SearchStore.status
    val hits = if (sameRoot) SearchStore.hits else emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Buscar en archivos", fontSize = 17.sp)
                        Text("En: " + root.name + (if (lastRoot != null && lastRoot != root) "  ·  resultados de ${lastRoot.name}" else ""),
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Texto, método, clase, const-string, URL…") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    FilterChip(filter == "smali", { filter = "smali" }, label = { Text("smali") })
                    FilterChip(filter == "xml", { filter = "xml" }, label = { Text("xml") })
                    FilterChip(filter == "all", { filter = "all" }, label = { Text("todo") })
                    FilterChip(regex, { regex = !regex }, label = { Text(".*") })
                    FilterChip(matchCase, { matchCase = !matchCase }, label = { Text("Aa") })
                }
                if (showReplace) OutlinedTextField(replacement, { replacement = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("Reemplazar por") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = query.isNotEmpty() && !running, onClick = { SearchStore.start(root, query, regex, matchCase, filter) }) { Text("Buscar") }
                    Spacer(Modifier.size(8.dp))
                    if (running) TextButton(onClick = { SearchStore.cancel() }) { Text("Detener") }
                    TextButton(onClick = { showReplace = !showReplace }) { Text(if (showReplace) "Ocultar reemplazo" else "Reemplazar…") }
                    if (showReplace && hits.isNotEmpty() && !running) TextButton(onClick = {
                        val targets = hits.map { it.file }.distinct()
                        scope.launch {
                            val n = withContext(Workers.dispatcher) {
                                val r = if (regex) (if (matchCase) Regex(query) else Regex(query, RegexOption.IGNORE_CASE))
                                    else Regex(Regex.escape(query), if (matchCase) setOf() else setOf(RegexOption.IGNORE_CASE))
                                targets.count { f ->
                                    val old = f.readText()
                                    val new = old.replace(r, if (regex) replacement else Regex.escapeReplacement(replacement))
                                    if (new != old) {
                                        f.writeText(new)
                                        dev.apkforge.engine.ChangeLog.record(f, old, new, "Buscar y reemplazar", "“$query” → “$replacement”")
                                        true
                                    } else false
                                }
                            }
                            SearchStore.status.value = "Reemplazado en $n archivo(s)"
                            SearchStore.hits.clear()
                        }
                    }) { Text("Reemplazar todo") }
                }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (sameRoot) status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(hits, key = { i, h -> "$i:${h.file.path}:${h.line}" }) { _, h ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(h.file, h.line) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(runCatching { h.file.relativeTo(lastRoot ?: root).path }.getOrDefault(h.file.name) + ":" + h.line, fontSize = 12.sp, fontFamily = Mono,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.text, fontSize = 12.sp, fontFamily = Mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
