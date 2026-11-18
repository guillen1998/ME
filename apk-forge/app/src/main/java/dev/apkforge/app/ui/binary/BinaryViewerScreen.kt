package dev.apkforge.app.ui.binary

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.core.Workers
import dev.apkforge.app.core.humanSize
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Pill
import dev.apkforge.engine.BinaryTools
import dev.apkforge.engine.CryptoTools
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Abre CUALQUIER archivo que no sea texto: identifica el formato, extrae cadenas, vuelca hex,
 * lista símbolos de librerías .so y permite descifrar in situ (AES/XOR/Base64/gzip…).
 * Ningún archivo se queda sin poder abrirse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinaryViewerScreen(file: File, onBack: () -> Unit, onAnalyzeAi: (File) -> Unit = {}) {
    var tab by remember { mutableIntStateOf(0) }
    var showDecrypt by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val tabs = remember(file) {
        buildList {
            add("Cadenas"); add("Hex")
            if (file.extension.equals("so", true) || BinaryTools.identify(file).startsWith("ELF")) add("Símbolos")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.name, maxLines = 1, fontSize = 17.sp)
                        Text(file.length().humanSize(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = {
                    IconButton(onClick = { onAnalyzeAi(file) }) { Icon(Icons.Default.AutoAwesome, "Analizar con IA") }
                    IconButton(onClick = { showEdit = true }) { Icon(Icons.Default.Edit, "Editar bytes") }
                    IconButton(onClick = { showDecrypt = true }) { Icon(Icons.Default.LockOpen, "Descifrar") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val kind by produceState("…", file) { value = withContext(Workers.dispatcher) { BinaryTools.identify(file) } }
            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("tipo")
                Spacer(Modifier.size(8.dp))
                SelectionContainer { Text(kind, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
            TabRow(tab) {
                tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            androidx.compose.runtime.key(version) {
                when (tabs.getOrNull(tab)) {
                    "Cadenas" -> StringsTab(file)
                    "Hex" -> HexTab(file)
                    "Símbolos" -> SymbolsTab(file)
                }
            }
        }
    }

    if (showDecrypt) DecryptDialog(file) { showDecrypt = false }
    if (showEdit) HexEditDialog(file, onDone = { version++ }) { showEdit = false }
}

@Composable
private fun StringsTab(file: File) {
    var filter by remember { mutableStateOf("") }
    val all by produceState<List<String>?>(null, file) {
        value = withContext(Workers.dispatcher) {
            if (file.extension.equals("dex", true)) BinaryTools.dexStrings(file) else BinaryTools.strings(file)
        }
    }
    Column {
        OutlinedTextField(filter, { filter = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(10.dp),
            placeholder = { Text("Filtrar cadenas") })
        val list = all
        if (list == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        else {
            val shown = if (filter.isBlank()) list else list.filter { it.contains(filter, true) }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Text("${shown.size} cadenas", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
                    items(shown.take(20000)) { Text(it, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) }
                }
            }
        }
    }
}

@Composable
private fun HexTab(file: File) {
    val hex by produceState<String?>(null, file) {
        value = withContext(Workers.dispatcher) { BinaryTools.hexDump(file, 0, 128 * 1024) }
    }
    val h = hex
    if (h == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    else SelectionContainer {
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            items(h.split('\n')) { Text(it, fontFamily = Mono, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp)) }
            item { if (file.length() > 128 * 1024) Text("[mostrando los primeros 128 KB de ${file.length().humanSize()}]", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun SymbolsTab(file: File) {
    val syms by produceState<List<String>?>(null, file) {
        value = withContext(Workers.dispatcher) { BinaryTools.elfExports(file) }
    }
    val list = syms
    if (list == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    else SelectionContainer {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Text("${list.size} símbolos (incluye funciones JNI Java_*)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp)) }
            items(list) { Text(it, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) }
        }
    }
}

@Composable
private fun DecryptDialog(file: File, onDismiss: () -> Unit) {
    var algo by remember { mutableStateOf("aes") }
    var key by remember { mutableStateOf("") }
    var iv by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Descifrar archivo") },
        text = {
            Column {
                Text("Algoritmo: aes · aes-ecb · aes-gcm · des · 3des · rc4 · xor · base64 · gzip · zlib",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(algo, { algo = it }, singleLine = true, label = { Text("Algoritmo") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, singleLine = true, label = { Text("Clave (texto/hex/base64)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(iv, { iv = it }, singleLine = true, label = { Text("IV (opcional)") }, modifier = Modifier.fillMaxWidth())
                result?.let {
                    Spacer(Modifier.size(8.dp))
                    Box(Modifier.heightIn(max = 260.dp)) {
                        SelectionContainer {
                            androidx.compose.foundation.rememberScrollState().let { sc ->
                                Text(it, fontFamily = Mono, fontSize = 11.sp,
                                    modifier = Modifier.fillMaxWidth().verticalScroll(sc))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true; result = null
                scope.launch {
                    val r = withContext(Workers.dispatcher) {
                        runCatching {
                            val data = file.readBytes()
                            val res = CryptoTools.decode(algo.trim(), dataBytes = data, key = key.ifBlank { null }, iv = iv.ifBlank { null })
                            if (res.looksText) "✔ Texto:\n" + res.text.take(8000)
                            else {
                                val out = File(file.parentFile, file.nameWithoutExtension + ".descifrado")
                                out.writeBytes(res.bytes)
                                "✔ Binario (${BinaryTools.identifyBytes(res.bytes)}). Guardado: ${out.name}\n" + res.preview
                            }
                        }.getOrElse { "✖ ${it.message}" }
                    }
                    result = r; busy = false
                }
            }) { Text(if (busy) "…" else "Descifrar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}



/**
 * Editor de bytes (estilo MT Manager): reemplazar texto o bytes, o escribir en un offset.
 * Mantiene el tamaño por defecto (necesario en .so/.dex). Copia de seguridad + informe de cambios.
 */
@Composable
private fun HexEditDialog(file: File, onDone: () -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) } // 0 texto, 1 hex, 2 offset
    var find by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    var offset by remember { mutableStateOf("0x0") }
    var keep by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar bytes") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Texto", "Hex", "Offset").forEachIndexed { i, l ->
                        androidx.compose.material3.FilterChip(mode == i, { mode = i; result = null }, label = { Text(l) })
                    }
                }
                when (mode) {
                    0 -> {
                        OutlinedTextField(find, { find = it }, label = { Text("Buscar texto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(repl, { repl = it }, label = { Text("Reemplazar por") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    1 -> {
                        OutlinedTextField(find, { find = it }, label = { Text("Buscar bytes (hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("00 00 A0 E3") })
                        OutlinedTextField(repl, { repl = it }, label = { Text("Reemplazar por (hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        OutlinedTextField(offset, { offset = it }, label = { Text("Offset (0x… o decimal)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(repl, { repl = it }, label = { Text("Bytes a escribir (hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (mode != 2) Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(keep, { keep = it })
                    Text("Mantener tamaño (recomendado en .so/.dex)", fontSize = 12.sp)
                }
                result?.let { Text(it, fontSize = 12.sp, fontFamily = Mono, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    result = withContext(Workers.dispatcher) {
                        runCatching {
                            val r = when (mode) {
                                0 -> dev.apkforge.engine.BinaryPatch.replaceText(file, find, repl, keep)
                                1 -> dev.apkforge.engine.BinaryPatch.replace(file, dev.apkforge.engine.BinaryPatch.parseHex(find),
                                    dev.apkforge.engine.BinaryPatch.parseHex(repl), keep)
                                else -> {
                                    val off = offset.trim().let { if (it.startsWith("0x", true)) it.substring(2).toLong(16) else it.toLong() }
                                    dev.apkforge.engine.BinaryPatch.writeAt(file, off, dev.apkforge.engine.BinaryPatch.parseHex(repl))
                                }
                            }
                            "✔ " + r.message + if (r.offsets.isNotEmpty()) " · " + r.offsets.take(6).joinToString { "0x%X".format(it) } else ""
                        }.getOrElse { "✖ " + (it.message ?: it.toString()) }
                    }
                    onDone()
                }
            }) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
