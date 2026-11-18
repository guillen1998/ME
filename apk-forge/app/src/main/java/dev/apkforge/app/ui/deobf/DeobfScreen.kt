package dev.apkforge.app.ui.deobf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.AiFactory
import dev.apkforge.app.ui.Accent
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Ok
import dev.apkforge.engine.Deobfuscator
import dev.apkforge.engine.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desofuscar: la app deduce los nombres reales en segundos, la IA completa lo que falta y tú puedes
 * corregir cualquiera. Los nombres se ven en el Java legible y los usan la IA y el motor neuronal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeobfScreen(project: File, onBack: () -> Unit, onOpenJava: (File) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<Deobfuscator.Rename>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var percent by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("class") }
    var editing by remember { mutableStateOf<Deobfuscator.Rename?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refresh) { list = withContext(Dispatchers.IO) { Deobfuscator.load(project) } }

    fun run(block: (ProgressListener) -> String) {
        busy = true; percent = 0
        scope.launch {
            val l = object : ProgressListener {
                override fun onProgress(message: String) { scope.launch { status = message } }
                override fun onPercent(p: Int) { scope.launch { percent = p } }
            }
            status = withContext(Dispatchers.IO) { runCatching { block(l) }.getOrElse { "✖ ${it.message}" } }
            busy = false; percent = -1; refresh++
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("Desofuscar", fontSize = 18.sp); Text(project.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            actions = { IconButton(onClick = { onOpenJava(project) }) { Icon(Icons.Default.Coffee, "Ver Java con nombres reales") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text("1) La app lee las pistas que la ofuscación no borra (toString, logs, layouts, vistas, getters…). 2) La IA estudia lo que quede. " +
                    "3) Tú corriges si quieres. Los nombres aparecen en el Java y la IA los usa. El smali no se toca.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(enabled = !busy, onClick = { run { l -> Deobfuscator.analyze(project, l).let { "✔ ${it.obfuscated} de ${it.classes} clases ofuscadas · ${it.renamed} clases, ${it.methods} métodos y ${it.fields} campos con nombre" } } },
                        modifier = Modifier.weight(1f)) { Icon(Icons.Default.Search, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("Analizar (app)", fontSize = 13.sp) }
                    OutlinedButton(enabled = !busy, onClick = {
                        val client = AiFactory.textClient(ApkForgeApp.prefs)
                        if (client == null) status = "La IA no está disponible" else run { l ->
                            if (!Deobfuscator.mappingFile(project).isFile) Deobfuscator.analyze(project, l)
                            "✔ La IA añadió ${Deobfuscator.refineWithAi(project, client, l)} nombre(s)"
                        }
                    }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.size(4.dp)); Text("Mejorar con IA", fontSize = 13.sp) }
                }
                if (busy) LinearProgressIndicator(progress = { (percent.coerceAtLeast(0)) / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                status?.let { Text(it, fontSize = 12.sp, color = if (it.startsWith("✖")) MaterialTheme.colorScheme.error else Ok, modifier = Modifier.padding(top = 4.dp)) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    listOf("class" to "Clases", "method" to "Métodos", "field" to "Campos").forEach { (k, t) ->
                        FilterChip(kind == k, { kind = k }, label = { Text(t + " (" + (list?.count { it.kind == k } ?: 0) + ")") }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text("Filtrar") }, modifier = Modifier.fillMaxWidth())
            }
            val l = list
            when {
                l == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                l.isEmpty() -> Text("Aún no hay nombres. Pulsa “Analizar (app)”.", modifier = Modifier.padding(16.dp))
                else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val shown = l.filter { it.kind == kind && (filter.isBlank() || it.name.contains(filter, true) || it.cls.contains(filter, true) || it.evidence.contains(filter, true)) }
                        .sortedByDescending { it.conf }
                    items(shown.take(500), key = { it.key }) { r ->
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(10.dp), onClick = { editing = r }) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${(r.conf * 100).toInt()}% · ${r.source}", fontSize = 11.sp,
                                        color = when (r.source) { "Tú" -> Ok; "IA" -> Accent; else -> AccentWarm })
                                }
                                Text(r.cls.removePrefix("L").removeSuffix(";").replace('/', '.') + if (r.kind != "class") " · ${r.id}" else "",
                                    fontSize = 11.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(r.evidence, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { r ->
        var name by remember(r) { mutableStateOf(r.name) }
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text("Renombrar ${r.obf}") },
            text = {
                Column {
                    Text(r.evidence, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(name, { name = it.trim() }, singleLine = true, label = { Text("Nombre real") }, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = {
                runCatching { Deobfuscator.rename(project, r.kind, r.cls, r.id, name, "Tú", "puesto a mano") }
                    .onFailure { status = "✖ ${it.message}" }
                editing = null; refresh++
            }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancelar") } })
    }
}
