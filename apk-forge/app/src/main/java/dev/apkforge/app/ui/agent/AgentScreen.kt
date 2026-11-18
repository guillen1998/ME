package dev.apkforge.app.ui.agent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.AgentMsg
import dev.apkforge.app.core.AgentStore
import dev.apkforge.app.core.AiFactory
import dev.apkforge.app.core.JavaSessions
import dev.apkforge.app.ui.Accent
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.Danger
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Ok
import dev.apkforge.engine.AgentConfig
import dev.apkforge.engine.AgentPreset
import dev.apkforge.engine.AiProvider
import dev.apkforge.engine.ForgeAgent
import dev.apkforge.engine.JavaDecompiler
import dev.apkforge.engine.OpenAiCompatAgent
import java.io.File

/**
 * Chat con el agente de IA. La conversación vive en AgentStore: si sales de la pantalla (o de la
 * app) el agente sigue trabajando y la notificación muestra qué está haciendo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(target: File, onBack: () -> Unit, onOpenSettings: () -> Unit,
                initialPreset: AgentPreset? = null, initialPrompt: String? = null) {
    val prefs = ApkForgeApp.prefs
    var preset by rememberSaveable {
        mutableStateOf(initialPreset ?: if (target.isFile && !target.extension.equals("apk", true)) AgentPreset.FILE_ANALYST else AgentPreset.EXPLAIN)
    }
    var input by remember { mutableStateOf("") }
    var resetCounter by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val ready = prefs.aiReady()
    val provider = prefs.aiProvider
    val key = dev.apkforge.app.core.AgentLauncher.key(target, preset, resetCounter)

    val session = remember(key, ready) {
        AgentStore.session(key, preset.title) { s ->
            dev.apkforge.app.core.AgentLauncher.create(target, preset, s, prefs.agentAllowEdits)
        }
    }
    val messages = session.messages
    val running by session.running
    val tokens by session.tokens

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    // "Analizar con IA" desde un archivo: arranca solo.
    LaunchedEffect(session) {
        if (initialPrompt != null && messages.isEmpty() && session.agent != null && !running) session.send(initialPrompt)
    }

    fun send() {
        val text = input.trim().ifEmpty { defaultPrompt(preset) }
        input = ""
        session.send(text)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agente IA", fontSize = 17.sp)
                        Text(preset.title + "  ·  " + provider.title.substringBefore(" ·") + "  ·  " + target.name,
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = {
                    if (messages.isNotEmpty() && !running) IconButton(onClick = { AgentStore.drop(key); resetCounter++ }) {
                        Icon(Icons.Default.DeleteSweep, "Nueva conversación")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AgentPreset.entries.forEach { p ->
                    FilterChip(preset == p, { if (!running) preset = p }, label = { Text(p.title) })
                }
            }

            if (!ready) {
                Surface(color = AccentWarm.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Configura un proveedor de IA", fontWeight = FontWeight.SemiBold)
                        Text("En Ajustes elige Claude o un modelo abierto gratis: OpenRouter, Groq u Ollama (en tu PC, sin clave).",
                            fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                        AssistChip(onClick = onOpenSettings, label = { Text("Abrir Ajustes") })
                    }
                }
            } else if (messages.isEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    Text(preset.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Sigue trabajando aunque salgas: verás el progreso en la notificación.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    if (prefs.agentAllowEdits) Text("Edición ACTIVADA: puede cambiar archivos (con copia de seguridad).",
                        fontSize = 12.sp, color = AccentWarm, modifier = Modifier.padding(top = 6.dp))
                }
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages.toList()) { m -> MessageBubble(m) }
                if (running) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp)); Text("Trabajando…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (tokens.isNotEmpty()) Text(tokens, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp))

            Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), enabled = ready, maxLines = 4,
                    placeholder = { Text(if (preset == AgentPreset.CHAT) "Pregunta algo…" else "Escribe o pulsa enviar para empezar") })
                Spacer(Modifier.size(6.dp))
                if (running) IconButton(onClick = { session.stop() }) { Icon(Icons.Default.Stop, "Parar", tint = Danger) }
                else IconButton(onClick = { send() }, enabled = ready) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = Accent) }
            }
        }
    }
}

private fun defaultPrompt(p: AgentPreset) = when (p) {
    AgentPreset.EXPLAIN -> "Explícame qué hace esta app."
    AgentPreset.NETWORK -> "Haz el mapa de red: servidores, endpoints y qué clase llama a cada uno."
    AgentPreset.DEOBFUSCATE -> "Propón nombres reales para las clases ofuscadas más importantes."
    AgentPreset.SECURITY -> "Haz una auditoría de seguridad de esta app."
    AgentPreset.MODDER -> "Dime qué puedo modificar y espera mis instrucciones."
    AgentPreset.FILE_ANALYST -> "Analiza este archivo: qué es, qué contiene y si está cifrado, descífralo."
    AgentPreset.CHAT -> "Hola, ¿qué puedes hacer con esta app?"
}

@Composable
private fun MessageBubble(m: AgentMsg) {
    when (m) {
        is AgentMsg.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(0.85f)) {
                Text(m.text, Modifier.padding(12.dp), fontSize = 14.sp)
            }
        }
        is AgentMsg.Assistant -> SelectionContainer { Text(m.text, fontSize = 14.sp, lineHeight = 20.sp) }
        is AgentMsg.Tool -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Build, null, Modifier.size(14.dp), tint = if (m.error) Danger else Ok)
                    Spacer(Modifier.size(6.dp))
                    Text(m.tool, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (m.summary.isNotBlank()) { Spacer(Modifier.size(6.dp)); Text(m.summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                m.result?.let { Text(it, fontFamily = Mono, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    color = if (m.error) Danger else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
        is AgentMsg.Info -> Text(m.text, fontSize = 12.sp, color = if (m.error) Danger else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
