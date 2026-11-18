package dev.apkforge.app.ui.scan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.core.ScanStore
import dev.apkforge.app.core.Workers
import dev.apkforge.app.ui.Accent
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.Danger
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Ok
import dev.apkforge.app.ui.Pill
import dev.apkforge.engine.Finding
import dev.apkforge.engine.FindingKind
import dev.apkforge.engine.ProjectScanner
import dev.apkforge.engine.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun kindLabel(k: FindingKind) = when (k) {
    FindingKind.URL -> "URLs"; FindingKind.IP -> "IPs"; FindingKind.DOMAIN -> "Dominios"
    FindingKind.EMAIL -> "Correos"; FindingKind.SECRET -> "Secretos"; FindingKind.FIREBASE -> "Firebase"
    FindingKind.S3 -> "Amazon S3"; FindingKind.PATH -> "Rutas"; FindingKind.SQL -> "SQL"
    FindingKind.CRYPTO -> "Cripto"; FindingKind.DEEPLINK -> "Deep links"; FindingKind.OTHER -> "Otros"
}

private fun kindColor(k: FindingKind) = when (k) {
    FindingKind.SECRET -> Danger; FindingKind.FIREBASE, FindingKind.S3 -> AccentWarm
    FindingKind.URL, FindingKind.DEEPLINK -> Accent; FindingKind.CRYPTO -> Ok
    else -> Accent
}

/**
 * Análisis automático: encuentra URLs, IPs, correos, claves/secretos, rutas, SQL y cripto por
 * TODO el contenido (texto, smali, xml, json, y dentro de dex y librerías .so). Sin escribir nada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(target: File, onBack: () -> Unit, onOpen: (File, Int) -> Unit) {
    val ctx = LocalContext.current
    // El análisis vive en ScanStore: sigue aunque abras un resultado o salgas de la app.
    LaunchedEffect(target) { ScanStore.start(target) }
    val running by ScanStore.running
    val statusText by ScanStore.status
    val pct by ScanStore.percent
    val all = if (ScanStore.target == target) ScanStore.findings else emptyList()
    var filter by remember { mutableStateOf<FindingKind?>(null) }
    val counts = all.groupingBy { it.kind }.eachCount()
    val shown = if (filter == null) all else all.filter { it.kind == filter }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Análisis automático", fontSize = 17.sp)
                        Text(target.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = {
                    if (running) TextButton(onClick = { ScanStore.cancel() }) { Text("Parar") }
                    else IconButton(onClick = { ScanStore.start(target, force = true) }) { Icon(Icons.Default.PlayArrow, "Volver a analizar") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (running) {
                if (pct in 0..100) LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            statusText?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }
            if (counts.isNotEmpty()) Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(filter == null, { filter = null }, label = { Text("Todo ${all.size}") })
                counts.entries.sortedByDescending { it.value }.forEach { (k, n) ->
                    FilterChip(filter == k, { filter = if (filter == k) null else k }, label = { Text("${kindLabel(k)} $n") })
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown.toList(), key = { it.kind.name + it.value + it.source }) { f ->
                    FindingRow(f, onClick = {
                        if (f.line > 0 && f.file.isFile && f.file.canRead()) onOpen(f.file, f.line)
                        else {
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("valor", f.value))
                            Toast.makeText(ctx, "Copiado (está dentro de un binario)", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
                if (!running && all.isEmpty()) item {
                    Text("Sin hallazgos.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FindingRow(f: Finding, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Pill(kindLabel(f.kind).trimEnd('s'), kindColor(f.kind))
            Spacer(Modifier.size(8.dp))
            Text(f.rule, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SelectionContainer {
            Text(f.value, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        Text(f.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
