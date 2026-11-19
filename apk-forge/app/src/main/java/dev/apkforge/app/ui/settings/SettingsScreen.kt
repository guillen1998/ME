package dev.apkforge.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.JavaSessions
import dev.apkforge.app.core.Workspace
import dev.apkforge.app.ui.SectionCard
import dev.apkforge.app.ui.apk.CheckRow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val p = ApkForgeApp.prefs
    var signMode by remember { mutableStateOf(p.signMode) }
    var ksPath by remember { mutableStateOf(p.keystorePath) }
    var ksPass by remember { mutableStateOf(p.keystorePass) }
    var alias by remember { mutableStateOf(p.keyAlias) }
    var keyPass by remember { mutableStateOf(p.keyPass) }
    var deobf by remember { mutableStateOf(p.deobfuscate) }
    var inconsistent by remember { mutableStateOf(p.showInconsistent) }
    var kotlinNames by remember { mutableStateOf(p.kotlinNames) }
    var hideMeta by remember { mutableStateOf(p.hideMetadata) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("IA potente gratis (Gemini)") { GeminiCard(p) }
            SectionCard("Firma de APKs") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(signMode == "test", { signMode = "test"; p.signMode = "test" })
                    Column {
                        Text("Clave de prueba de ApkForge")
                        Text("Para probar mods en tu móvil.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(signMode == "keystore", { signMode = "keystore"; p.signMode = "keystore" })
                    Column {
                        Text("Mi keystore")
                        Text("PKCS12 (.p12/.pfx) o BKS. Para JKS, conviértelo a PKCS12 con keytool.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (signMode == "keystore") {
                    Spacer(Modifier.size(6.dp))
                    OutlinedTextField(ksPath, { ksPath = it; p.keystorePath = it }, label = { Text("Ruta del keystore") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("${Workspace.root.absolutePath}/mi-clave.p12") })
                    OutlinedTextField(ksPass, { ksPass = it; p.keystorePass = it }, label = { Text("Contraseña del keystore") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(alias, { alias = it; p.keyAlias = it }, label = { Text("Alias (vacío = el primero)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(keyPass, { keyPass = it; p.keyPass = it }, label = { Text("Contraseña de la clave (vacío = la misma)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    "Una app recompilada lleva otra firma: para instalarla encima de la original, primero desinstala la original.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                SignatureFingerprint(p)
            }

            SectionCard("Agente IA") {
                var provider by remember { mutableStateOf(p.aiProvider) }
                var key by remember(provider) { mutableStateOf(p.keyFor(provider)) }
                var model by remember(provider) { mutableStateOf(p.modelFor(provider)) }
                var url by remember(provider) { mutableStateOf(p.urlFor(provider)) }
                var effort by remember { mutableStateOf(p.agentEffort) }
                var allowEdits by remember { mutableStateOf(p.agentAllowEdits) }

                Text("Proveedor", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                dev.apkforge.engine.AiProvider.entries.forEach { pr ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(provider == pr, { provider = pr; p.aiProvider = pr })
                        Text(pr.title, fontSize = 14.sp)
                    }
                }
                Text(provider.help, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (provider == dev.apkforge.engine.AiProvider.BUILTIN) BuiltinModelsEditor(p)
                Spacer(Modifier.size(6.dp))
                val builtin = provider == dev.apkforge.engine.AiProvider.BUILTIN
                if (!builtin && provider != dev.apkforge.engine.AiProvider.CLAUDE) {
                    OutlinedTextField(url, { url = it; p.setUrlFor(provider, it.trim()) },
                        label = { Text("URL base (…/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (!builtin) OutlinedTextField(key, { key = it; p.setKeyFor(provider, it.trim()) },
                    label = { Text(if (provider.needsKey) "Clave de API" else "Clave de API (opcional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (!builtin) OutlinedTextField(model, { model = it; p.setModelFor(provider, it.trim()) },
                    label = { Text("Modelo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val suggestions = when (provider) {
                    dev.apkforge.engine.AiProvider.CLAUDE -> listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")
                    dev.apkforge.engine.AiProvider.OPENROUTER -> listOf("openrouter/free", "qwen/qwen3-coder:free", "deepseek/deepseek-chat-v3.1:free", "meta-llama/llama-3.3-70b-instruct:free")
                    dev.apkforge.engine.AiProvider.GROQ -> listOf("llama-3.3-70b-versatile", "qwen/qwen3-32b", "openai/gpt-oss-120b")
                    dev.apkforge.engine.AiProvider.GEMINI -> listOf("gemini-flash-latest", "gemini-pro-latest", "gemini-flash-lite-latest")
                    dev.apkforge.engine.AiProvider.OLLAMA -> listOf("qwen2.5-coder:7b", "llama3.1:8b", "qwen3:8b")
                    dev.apkforge.engine.AiProvider.CUSTOM, dev.apkforge.engine.AiProvider.BUILTIN -> emptyList()
                }
                if (suggestions.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    suggestions.forEach { m ->
                        androidx.compose.material3.AssistChip(onClick = { model = m; p.setModelFor(provider, m) }, label = { Text(m, fontSize = 11.sp) },
                            modifier = Modifier.padding(end = 6.dp))
                    }
                }
                if (provider == dev.apkforge.engine.AiProvider.CLAUDE) {
                    Text("Esfuerzo", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        listOf("low", "medium", "high", "xhigh").forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(effort == e, { effort = e; p.agentEffort = e }); Text(e, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Text("Las claves se guardan solo en este dispositivo.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CheckRow("Permitir que el agente edite archivos", "Hace cambios en smali/XML (guarda copia de seguridad). Si no, solo propone.", allowEdits) {
                    allowEdits = it; p.agentAllowEdits = it
                }
            }

            SectionCard("Motor neuronal (en el móvil)") { NeuralPanel() }
            SectionCard("Decompilador Java (jadx)") {
                CheckRow("Mostrar código aunque sea inconsistente", "Muestra lo que se pudo reconstruir en vez de “dump skipped”", inconsistent) {
                    inconsistent = it; p.showInconsistent = it; JavaSessions.close()
                }
                CheckRow("Desofuscar nombres", "Renombra a, b, c… a nombres únicos (C0123a, m45b…) para seguir el código", deobf) {
                    deobf = it; p.deobfuscate = it; JavaSessions.close()
                }
                CheckRow("Nombres reales desde Kotlin", "Usa metadatos de Kotlin para nombrar variables", kotlinNames) {
                    kotlinNames = it; p.kotlinNames = it; JavaSessions.close()
                }
                CheckRow("Ocultar @Metadata de Kotlin", "Quita el bloque binario ilegible de las clases Kotlin", hideMeta) {
                    hideMeta = it; p.hideMetadata = it; JavaSessions.close()
                }
            }

            SectionCard("Carpetas") {
                Text("Proyectos (lo que editas): ${Workspace.root.absolutePath}/Proyectos", fontSize = 13.sp)
                Text("Compilados (APKs exportados y firmados): ${Workspace.root.absolutePath}/Compilados", fontSize = 13.sp)
                Text("Extraídos: ${Workspace.root.absolutePath}/Extraidos", fontSize = 13.sp)
                Text("Java exportado: ${Workspace.root.absolutePath}/Java", fontSize = 13.sp)
            }

            SectionCard("Acerca de") {
                Text("ApkForge 0.1.0", fontSize = 14.sp)
                Text(
                    "Motor: ARSCLib (recursos sin aapt), smali/baksmali, apksig y jadx.\n" +
                        "Úsalo con apps propias, de código abierto o con permiso de su autor.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


/** Lista de modelos de la IA integrada: activar/desactivar cada uno y añadir los tuyos. */
@Composable
private fun BuiltinModelsEditor(p: dev.apkforge.app.core.Prefs) {
    var disabled by remember { mutableStateOf(p.disabledModels) }
    var custom by remember { mutableStateOf(p.customModels) }
    var adding by remember { mutableStateOf(false) }
    Spacer(Modifier.size(6.dp))
    Text("Modelos que trabajan en conjunto (si uno está ocupado, sigue el siguiente):", fontSize = 12.sp, fontWeight = FontWeight.Medium)
    (custom + dev.apkforge.engine.BuiltinAi.endpoints).forEach { ep ->
        val isCustom = ep in custom
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(ep.label !in disabled, { on ->
                disabled = if (on) disabled - ep.label else disabled + ep.label; p.disabledModels = disabled
            })
            Column(Modifier.weight(1f)) {
                Text(ep.label, fontSize = 13.sp)
                Text(ep.baseUrl.removePrefix("https://").substringBefore('/') + (if (isCustom) " · tuyo" else ""),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isCustom) androidx.compose.material3.TextButton(onClick = { custom = custom - ep; p.customModels = custom }) { Text("Quitar") }
        }
    }
    androidx.compose.material3.OutlinedButton(onClick = { adding = true }) { Text("+ Añadir modelo") }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        var model by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Añadir modelo") },
            text = {
                Column {
                    Text("Cualquier API compatible con OpenAI (/v1/chat/completions) que admita herramientas.", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(url, { url = it.trim() }, label = { Text("URL base (…/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it.trim() }, label = { Text("Modelo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(key, { key = it.trim() }, label = { Text("Clave (opcional)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = url.length > 10 && model.isNotBlank(), onClick = {
                    custom = custom + dev.apkforge.engine.AiEndpoint(url, key, model, name.ifBlank { model })
                    p.customModels = custom; adding = false
                }) { Text("Añadir") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { adding = false }) { Text("Cancelar") } },
        )
    }
}


/** Estado del motor neuronal: acelerador (GPU/NPU/CPU), lo aprendido, aciertos y errores corregidos. */
@Composable
private fun NeuralPanel() {
    var stats by remember { mutableStateOf(dev.apkforge.engine.NeuralBrain.stats()) }
    var loading by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        Text("Red neuronal MiniLM (6 capas) que corre en tu móvil: entiende el significado de tus pedidos, razona sobre cómo está " +
            "conectado el proyecto y aprende de las IAs y de sus propios errores. Prepara el trabajo para los modelos de internet.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        dev.apkforge.app.ui.KeyValue("Acelerador", stats.backend?.let { "$it · %.1f ms por texto".format(stats.msPerText) } ?: "Sin cargar (se carga al usar el agente)")
        dev.apkforge.app.ui.KeyValue("Ejemplos aprendidos de la IA", "${stats.rankerExamples}")
        dev.apkforge.app.ui.KeyValue("Acierto reciente", stats.precision?.let { "$it % (últimos pedidos)" } ?: "aún pocos datos")
        dev.apkforge.app.ui.KeyValue("Errores propios corregidos", "${stats.mistakesFixed}")
        dev.apkforge.app.ui.KeyValue("Detector de riesgo", "${stats.riskExamples} cambios vistos · ${stats.riskErrors} rompían")
        if (stats.recent.isNotEmpty()) {
            Text("Últimos aprendizajes", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            stats.recent.forEach { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        ReasonerSection()
        Row(Modifier.padding(top = 8.dp)) {
            OutlinedButton(enabled = !loading, onClick = {
                loading = true
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.apkforge.engine.NeuralBrain.embedder() }
                    stats = dev.apkforge.engine.NeuralBrain.stats(); loading = false
                }
            }) { Text(if (loading) "Probando GPU/CPU…" else if (stats.backend == null) "Cargar y probar acelerador" else "Actualizar") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { confirmReset = true }) { Text("Reiniciar aprendizaje", color = dev.apkforge.app.ui.Danger) }
        }
    }
    if (confirmReset) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("¿Reiniciar el aprendizaje?") },
        text = { Text("El motor neuronal olvida lo aprendido (aciertos, errores y riesgos). La red de significado se mantiene.") },
        confirmButton = { TextButton(onClick = { dev.apkforge.engine.NeuralBrain.reset(); stats = dev.apkforge.engine.NeuralBrain.stats(); confirmReset = false }) { Text("Reiniciar") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } })
}


/** El modelo que RAZONA en el móvil: descargar, elegir, probar y cuándo usarlo. */
@Composable
private fun ReasonerSection() {
    val prefs = dev.apkforge.app.ApkForgeApp.prefs
    var selected by remember { mutableStateOf(prefs.reasonerModel) }
    var mode by remember { mutableStateOf(prefs.reasonMode) }
    var test by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(top = 10.dp)) {
        Text("Razonamiento en el móvil", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("Un modelo que PIENSA paso a paso (Qwen3) en la GPU/NPU/CPU de tu móvil, sin internet. Usa lo que prepara el motor " +
            "(significado, conexiones del proyecto, nombres desofuscados y recetas) y su conclusión la verifican y ejecutan las IAs de internet. " +
            "Sin internet, responde él solo.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        dev.apkforge.engine.LocalReasoner.MODELS.forEach { m ->
            val st = dev.apkforge.app.core.ReasonerModels.states[m.id]
            val have = dev.apkforge.app.core.ReasonerModels.isDownloaded(m)
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = selected == m.id, enabled = have, onClick = {
                            selected = m.id; prefs.reasonerModel = m.id; dev.apkforge.engine.NeuralBrain.resetReasoner()
                        })
                        Column(Modifier.weight(1f)) {
                            Text(m.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(m.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    when {
                        st?.running == true -> {
                            androidx.compose.material3.LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            Row { Text("Descargando ${st.percent}%", fontSize = 11.sp, modifier = Modifier.weight(1f)); TextButton(onClick = { dev.apkforge.app.core.ReasonerModels.cancel(m.id) }) { Text("Pausar") } }
                        }
                        have -> Row { Text("✔ Descargado", fontSize = 12.sp, color = dev.apkforge.app.ui.Ok, modifier = Modifier.weight(1f))
                            TextButton(onClick = { dev.apkforge.app.core.ReasonerModels.delete(m.id); if (selected == m.id) selected = "" }) { Text("Borrar") } }
                        else -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(st?.error ?: "No descargado", fontSize = 11.sp, modifier = Modifier.weight(1f), color = if (st?.error != null) dev.apkforge.app.ui.Danger else MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { dev.apkforge.app.core.ReasonerModels.download(m) }) { Text(if ((st?.percent ?: 0) > 0) "Continuar" else "Descargar") }
                        }
                    }
                }
            }
        }
        Text("Cuándo razonar", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Row {
            listOf("always" to "Antes de cada pedido", "offline" to "Solo sin internet", "off" to "Nunca").forEach { (k, t) ->
                androidx.compose.material3.FilterChip(mode == k, { mode = k; prefs.reasonMode = k }, label = { Text(t, fontSize = 11.sp) }, modifier = Modifier.padding(end = 4.dp))
            }
        }
        if (selected.isNotBlank()) OutlinedButton(enabled = !testing, onClick = {
            testing = true; test = "Cargando el modelo y razonando…"
            scope.launch {
                test = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val r = dev.apkforge.engine.NeuralBrain.reasoner() ?: error("No se pudo cargar el modelo")
                        val res = r.reason("Eres un experto en apps Android. Responde en español, breve.",
                            "Una app muestra los canales en una lista de filas con LinearLayoutManager. ¿Qué cambio exacto hace falta para verlos en 3 columnas?", think = true, budget = 512, maxOutput = 700)
                        "✔ ${r.backend} · ${res.millis / 1000} s\n${res.answer}"
                    }.getOrElse { "✖ ${it.message}" }
                }
                testing = false
            }
        }, modifier = Modifier.padding(top = 6.dp)) { Text(if (testing) "Razonando…" else "Probar razonamiento") }
        test?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}


/** Muestra el SHA-256 de tu firma — el valor que tu servidor debe aceptar (si tu app valida la firma). */
@Composable
private fun SignatureFingerprint(p: dev.apkforge.app.core.Prefs) {
    var info by remember { mutableStateOf<dev.apkforge.engine.ApkSigning.CertInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    Text("Mantener la firma original", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Text("Una firma NO se puede “mantener” en un APK modificado: la firma sella el contenido exacto, así que al cambiar algo se invalida. " +
        "La única forma de que sea la MISMA firma es volver a firmar con TU keystore original (el que usaste para publicar). " +
        "Si no lo tienes, la firma será distinta y, si TU servidor la valida, registra la nueva huella (de abajo) en tu servidor.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
    OutlinedButton(enabled = !busy, onClick = {
        busy = true; error = null
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { dev.apkforge.engine.ApkSigning.fingerprint(p.signingConfig()) } }
            r.onSuccess { info = it }.onFailure { error = it.message }
            busy = false
        }
    }) { Text(if (busy) "Calculando…" else "Ver huella de mi firma (SHA-256)", fontSize = 13.sp) }
    error?.let { Text("✗ $it", fontSize = 12.sp, color = dev.apkforge.app.ui.Danger) }
    info?.let { c ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text("Si TU app o TU servidor validan la firma, este es el valor que deben aceptar:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(c.sha256Plain, fontFamily = dev.apkforge.app.ui.Mono, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
                Text("Con dos puntos: ${c.sha256}", fontFamily = dev.apkforge.app.ui.Mono, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Text("SHA-1: ${c.sha1}", fontFamily = dev.apkforge.app.ui.Mono, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("Certificado: ${c.subject}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(c.sha256Plain)) }) { Text("Copiar SHA-256") }
                }
                Text("Nota: si firmas con tu keystore ORIGINAL, esta huella será la misma que la del APK publicado y tu servidor la aceptará sin cambios.",
                    fontSize = 11.sp, color = dev.apkforge.app.ui.AccentWarm, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Conecta Gemini (gratis, potente): sacar la clave, pegarla, probar, y que se sume al equipo o sea la principal. */
@Composable
private fun GeminiCard(p: dev.apkforge.app.core.Prefs) {
    val gemini = dev.apkforge.engine.AiProvider.GEMINI
    var key by remember { mutableStateOf(p.keyFor(gemini)) }
    var model by remember { mutableStateOf(p.modelFor(gemini)) }
    var asMain by remember { mutableStateOf(p.aiProvider == gemini) }
    var test by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    fun open(url: String) = runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    Column {
        Text("Gemini de Google es gratis y MUCHO más potente que los modelos sueltos. Con una clave gratis, razona de verdad (ideal para lo que viste que hace Claude). Se saca en 1 minuto y no se paga nada.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text("1) Saca tu clave gratis", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { open("https://aistudio.google.com/apikey") }) { Text("Abrir Google AI Studio", fontSize = 13.sp) }
        }
        Text("Inicia sesión con tu cuenta Google → \"Create API key\" → copia la clave (empieza por AIza...).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("2) Pégala aquí", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        OutlinedTextField(key, { key = it.trim(); p.setKeyFor(gemini, key) }, label = { Text("Clave de Gemini") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it.trim(); p.setModelFor(gemini, model) }, label = { Text("Modelo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("gemini-flash-latest" to "Rápido", "gemini-pro-latest" to "Más listo", "gemini-flash-lite-latest" to "Ligero").forEach { (m, t) ->
                androidx.compose.material3.FilterChip(model == m, { model = m; p.setModelFor(gemini, m) }, label = { Text("$t", fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Switch(asMain, { asMain = it; p.aiProvider = if (it) gemini else dev.apkforge.engine.AiProvider.BUILTIN })
            Column(Modifier.padding(start = 8.dp)) {
                Text(if (asMain) "Gemini como IA principal" else "Gemini se suma al equipo gratis", fontSize = 13.sp)
                Text(if (asMain) "Todo lo hace Gemini." else "Se usa junto a los modelos gratis; si Gemini se satura, siguen los demás.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        Button(enabled = !busy && key.isNotBlank(), onClick = {
            busy = true; test = "Probando varios modelos…"
            scope.launch {
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // primero los RÁPIDOS (responden en 1-2 s); el "pro" al final porque tarda más
                    val candidates = (listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-2.0-flash",
                        "gemini-2.5-flash-lite", model, "gemini-pro-latest", "gemini-2.5-pro")).filter { it.isNotBlank() }.distinct()
                    dev.apkforge.engine.GeminiSetup.probe(gemini.baseUrl, key, candidates) { m -> scope.launch { test = "Probando $m…" } }
                }
                test = if (res.first != null) {
                    model = res.first!!; p.setModelFor(gemini, model)
                    "✔ Funciona con el modelo \"" + res.first + "\" (lo dejé puesto). Gemini está listo."
                } else "✖ Google rechazó la clave/los modelos:\n" + res.second.take(400)
                busy = false
            }
        }) { Text(if (busy) "Probando…" else "Probar Gemini", fontSize = 13.sp) }
        test?.let { Text(it, fontSize = 12.sp, color = if (it.startsWith("✖")) dev.apkforge.app.ui.Danger else dev.apkforge.app.ui.Ok, modifier = Modifier.padding(top = 4.dp)) }
    }
}
