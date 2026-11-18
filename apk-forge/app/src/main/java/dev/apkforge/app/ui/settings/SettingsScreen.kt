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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
