package dev.apkforge.app.ui.agent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateListOf
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
import dev.apkforge.app.core.JavaSessions
import dev.apkforge.app.core.Workers
import dev.apkforge.app.ui.Accent
import dev.apkforge.app.ui.AccentWarm
import dev.apkforge.app.ui.Danger
import dev.apkforge.app.ui.Mono
import dev.apkforge.app.ui.Ok
import com.anthropic.models.beta.messages.BetaOutputConfig
import dev.apkforge.engine.AgentConfig
import dev.apkforge.engine.AgentEvent
import dev.apkforge.engine.AgentPreset
import dev.apkforge.engine.ForgeAgent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed class Msg {
    data class User(val text: String) : Msg()
    data class Assistant(val text: String) : Msg()
    data class Tool(val tool: String, val summary: String, var result: String? = null, var error: Boolean = false) : Msg()
    data class Info(val text: String, val error: Boolean) : Msg()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(target: File, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val prefs = ApkForgeApp.prefs
    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf(AgentPreset.EXPLAIN) }
    val messages = remember { mutableStateListOf<Msg>() }
    var input by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()
    var tokens by remember { mutableStateOf("") }

    val apiKey = prefs.apiKey
    val agent = remember(preset, target) {
        if (apiKey.isBlank()) null else {
            val effort = when (prefs.agentEffort) {
                "low" -> BetaOutputConfig.Effort.LOW; "medium" -> BetaOutputConfig.Effort.MEDIUM
                "xhigh" -> BetaOutputConfig.Effort.XHIGH; "max" -> BetaOutputConfig.Effort.MAX
                else -> BetaOutputConfig.Effort.HIGH
            }
            // Entrada para jadx: proyecto (sus dex) o el propio APK.
            val javaInput = target
            ForgeAgent(
                root = target,
                apk = if (target.isFile && target.extension.equals("apk", true)) target else null,
                config = AgentConfig(apiKey, prefs.agentModel, effort, prefs.agentAllowEdits),
                preset = preset,
                java = { runCatching { JavaSessions.get(javaInput, prefs.javaOptions()) }.getOrNull() },
            ) { ev ->
                scope.launch {
                    when (ev) {
                        is AgentEvent.Text -> messages += Msg.Assistant(ev.text)
                        is AgentEvent.ToolCall -> messages += Msg.Tool(ev.tool, ev.summary)
                        is AgentEvent.ToolResult -> {
                            val last = messages.lastOrNull { it is Msg.Tool && (it as Msg.Tool).result == null } as? Msg.Tool
                            if (last != null) { last.result = ev.preview; last.error = ev.isError }
                        }
                        is AgentEvent.Error -> messages += Msg.Info(ev.message, true)
                        is AgentEvent.Done -> tokens = "entrada ${ev.inputTokens} · salida ${ev.outputTokens}" +
                            (if (ev.cachedTokens > 0) " · caché ${ev.cachedTokens}" else "")
                    }
                }
            }
        }
    }

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || agent == null || job?.isActive == true) return
        messages += Msg.User(text)
        input = ""
        job = scope.launch {
            withContext(Workers.dispatcher) { agent.send(text) { job?.isActive != true } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agente IA", fontSize = 17.sp)
                        Text(preset.title + "  ·  " + target.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            // Presets
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AgentPreset.entries.forEach { p ->
                    FilterChip(preset == p, { if (job?.isActive != true) { preset = p; messages.clear(); tokens = "" } }, label = { Text(p.title) })
                }
            }

            if (apiKey.isBlank()) {
                Surface(color = AccentWarm.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Falta la clave de API de Claude", fontWeight = FontWeight.SemiBold)
                        Text("Añádela en Ajustes para usar el agente. El análisis y la edición manuales funcionan sin ella.",
                            fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                        AssistChip(onClick = onOpenSettings, label = { Text("Abrir Ajustes") })
                    }
                }
            } else if (messages.isEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    Text(preset.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (prefs.agentAllowEdits) Text("Edición ACTIVADA: el agente puede cambiar archivos (con copia de seguridad).",
                        fontSize = 12.sp, color = AccentWarm, modifier = Modifier.padding(top = 8.dp))
                }
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { m -> MessageBubble(m) }
                if (job?.isActive == true) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp)); Text("Pensando…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (tokens.isNotEmpty()) Text(tokens, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp))

            Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text(if (preset == AgentPreset.CHAT) "Pregunta algo…" else "Escribe o pulsa enviar para empezar") },
                    enabled = apiKey.isNotBlank(), maxLines = 4)
                Spacer(Modifier.size(6.dp))
                if (job?.isActive == true) {
                    IconButton(onClick = { job?.cancel() }) { Icon(Icons.Default.Stop, "Parar", tint = Danger) }
                } else {
                    IconButton(onClick = {
                        if (input.isBlank()) input = defaultPrompt(preset)
                        send()
                    }, enabled = apiKey.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = Accent) }
                }
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
    AgentPreset.CHAT -> "Hola, ¿qué puedes hacer con esta app?"
}

@Composable
private fun MessageBubble(m: Msg) {
    when (m) {
        is Msg.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(0.85f)) {
                Text(m.text, Modifier.padding(12.dp), fontSize = 14.sp)
            }
        }
        is Msg.Assistant -> SelectionContainer {
            Text(m.text, fontSize = 14.sp, lineHeight = 20.sp)
        }
        is Msg.Tool -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()) {
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
        is Msg.Info -> Text(m.text, fontSize = 12.sp, color = if (m.error) Danger else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
