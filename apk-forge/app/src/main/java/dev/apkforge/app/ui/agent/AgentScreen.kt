package dev.apkforge.app.ui.agent

import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.History
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
import androidx.compose.runtime.collectAsState
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
/**
 * Entrada al agente. Si le pasas un APK, la IA no trabaja sobre el APK suelto: usa (o crea
 * decompilándolo) su proyecto en ApkTools/Proyectos, para que lo que edite se pueda exportar.
 */
@Composable
fun AgentScreen(target: File, onBack: () -> Unit, onOpenSettings: () -> Unit,
                initialPreset: AgentPreset? = null, initialPrompt: String? = null, onOpenDir: (File) -> Unit = {},
                onSwitch: (File) -> Unit = {}, onBackups: (File) -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Carpeta dentro de un proyecto -> la raíz del proyecto. Carpeta antigua de Android Studio -> su APK original.
    val resolved = remember(target) {
        when {
            target.isDirectory && dev.apkforge.engine.ApkProject.isProject(target) -> target
            target.isDirectory -> generateSequence(target) { it.parentFile }.firstOrNull { dev.apkforge.engine.ApkProject.isProject(it) }
                ?: dev.apkforge.engine.ApkProject.studioSource(target)?.let { m -> dev.apkforge.app.core.Workspace.findSourceApkForStudio(ctx, target, m) }
                ?: target
            else -> target
        }
    }
    if (resolved != target) { AgentScreen(resolved, onBack, onOpenSettings, initialPreset, initialPrompt, onOpenDir, onSwitch, onBackups); return }
    val isApk = target.isFile && target.extension.equals("apk", true)
    if (!isApk) { AgentChat(target, onBack, onOpenSettings, initialPreset, initialPrompt, onOpenDir, onSwitch, onBackups); return }

    var project by remember(target) {
        mutableStateOf(dev.apkforge.engine.ApkProject.findProjectFor(target, dev.apkforge.app.core.Workspace.projects)?.dir)
    }
    val task by dev.apkforge.app.core.TaskRunner.state.collectAsState()
    LaunchedEffect(target) {
        if (project == null && !dev.apkforge.app.core.TaskRunner.isBusy) {
            dev.apkforge.app.core.TaskRunner.run("Preparando el proyecto para la IA", dev.apkforge.app.core.ResultKind.NONE) { log ->
                val out = dev.apkforge.app.core.Workspace.newProjectDir(target)
                dev.apkforge.engine.ApkProject.decompile(target, out, dev.apkforge.engine.DecompileOptions(), log)
                out
            }
        }
    }
    LaunchedEffect(task?.running, task?.error) {
        val t = task
        if (project == null && t != null && !t.running && t.error == null) {
            project = dev.apkforge.engine.ApkProject.findProjectFor(target, dev.apkforge.app.core.Workspace.projects)?.dir
            if (project != null) dev.apkforge.app.core.TaskRunner.dismiss()
        }
    }
    val p = project
    if (p != null) AgentChat(p, onBack, onOpenSettings, initialPreset, initialPrompt, onOpenDir, onSwitch, onBackups)
    else Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.size(80.dp))
        CircularProgressIndicator()
        Spacer(Modifier.size(16.dp))
        Text("Decompilando ${target.name} en ApkTools/Proyectos para que la IA pueda editarlo y luego exportarlo…",
            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        task?.error?.let { Text(it, color = Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentChat(target: File, onBack: () -> Unit, onOpenSettings: () -> Unit,
                      initialPreset: AgentPreset?, initialPrompt: String?, onOpenDir: (File) -> Unit,
                      onSwitch: (File) -> Unit = {}, onBackups: (File) -> Unit = {}) {
    val prefs = ApkForgeApp.prefs
    var preset by rememberSaveable {
        mutableStateOf(initialPreset ?: AgentStore.lastPreset(target)
            ?: if (target.isFile && !target.extension.equals("apk", true)) AgentPreset.FILE_ANALYST else AgentPreset.EXPLAIN)
    }
    var input by remember { mutableStateOf("") }
    var showExport by remember { mutableStateOf(false) }
    var exportDir by remember { mutableStateOf<File?>(null) }
    var showSide by rememberSaveable { mutableStateOf(true) }
    if (showExport || exportDir != null) {
        val dir = exportDir ?: target
        val info = remember(dir) { runCatching { dev.apkforge.engine.ApkProject.readInfo(dir) }.getOrNull() }
        dev.apkforge.app.ui.ExportApkDialog(
            defaultName = dev.apkforge.app.core.Workspace.exportName(info?.packageName ?: dir.name),
            detail = "Compila “${dir.name}” con los cambios (tuyos y de la IA) y lo firma, listo para instalar y probar.",
            onDismiss = { showExport = false; exportDir = null },
            project = dir,
        ) { out ->
            dev.apkforge.app.core.TaskRunner.run("Exportando ${out.name}", dev.apkforge.app.core.ResultKind.APK, project = dir) { log ->
                prefs.exportProject(dir, out, log)
            }
        }
    }
    var resetCounter by remember { mutableIntStateOf(0) }
    var showHistory by remember { mutableStateOf(false) }
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
        if (initialPrompt != null && messages.isEmpty() && session.agent != null && !running)
            session.send(initialPrompt, emptyList(), if (dev.apkforge.engine.ApkProject.isProject(target)) target else null)
    }

    // Imágenes para mostrarle a la IA (capturas de la app, iconos nuevos, fotos…)
    val pendingImages = remember { androidx.compose.runtime.mutableStateListOf<ByteArray>() }
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(4)
    ) { uris -> uris.forEach { u -> loadImage(appCtx, u)?.let { pendingImages += it } } }
    val isProject = dev.apkforge.engine.ApkProject.isProject(target)

    if (showHistory) ChatHistoryDialog(AgentStore.chatFile(key), running, onDismiss = { showHistory = false }) { chat ->
        showHistory = false
        session.persist()
        AgentStore.chatFile(key)?.let { f -> runCatching { dev.apkforge.app.core.AgentSession.saver.submit { dev.apkforge.engine.ChatHistory.archive(f) }.get(3, java.util.concurrent.TimeUnit.SECONDS) } }
        session.loadChat(chat); session.persist()
    }

    fun send() {
        val text = input.trim().ifEmpty { if (pendingImages.isNotEmpty()) "Analiza esta imagen." else defaultPrompt(preset) }
        input = ""
        session.send(text, pendingImages.toList(), if (isProject) target else null)
        pendingImages.clear()
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
                    IconButton(onClick = { showSide = !showSide }) { Icon(Icons.Default.ViewSidebar, if (showSide) "Ocultar proyectos" else "Mostrar proyectos") }
                    IconButton(onClick = { showHistory = true }) { Icon(Icons.Default.History, "Historial de conversaciones") }
                    if (messages.isNotEmpty() && !running) IconButton(onClick = { AgentStore.drop(key); resetCounter++ }) {
                        Icon(Icons.Default.DeleteSweep, "Nueva conversación")
                    }
                },
            )
        },
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize().imePadding()) {
        if (showSide) {
            ProjectsSidePanel(current = target, onSwitch = onSwitch, onExport = { exportDir = it })
            androidx.compose.material3.VerticalDivider()
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (dev.apkforge.engine.ApkProject.isProject(target)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Proyecto", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(target.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        androidx.compose.material3.TextButton(onClick = { onOpenDir(target) }) { Text("Abrir") }
                        androidx.compose.material3.TextButton(onClick = { onBackups(target) }) { Text("Copias") }
                        androidx.compose.material3.Button(onClick = { showExport = true }) { Text("Exportar APK") }
                    }
                }
            }
            if (session.needsResume.value && !running) {
                Surface(color = AccentWarm.copy(alpha = 0.16f), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (session.interrupted.value) "⚠ La app se cerró mientras la IA trabajaba" else "📜 Conversación recuperada",
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Del " + java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(session.restoredAt)) +
                            ". Pulsa reanudar y la IA recibe todo lo hablado y los cambios hechos, y sigue donde quedó.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.material3.Button(onClick = { session.resume(if (isProject) target else null) },
                            enabled = session.agent != null, modifier = Modifier.padding(top = 6.dp).fillMaxWidth()) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Reanudar sesión")
                        }
                    }
                }
            }
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
                        Spacer(Modifier.size(8.dp)); Text(session.status.value ?: "La IA trabaja…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (tokens.isNotEmpty()) Text(tokens, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp))

            if (isProject && !running) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quickTasks.forEach { (label, prompt) ->
                    androidx.compose.material3.SuggestionChip(onClick = { input = prompt }, label = { Text(label, fontSize = 11.sp) })
                }
            }
            if (pendingImages.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pendingImages.forEachIndexed { i, img ->
                    androidx.compose.foundation.layout.Box {
                        Thumb(img, 64)
                        androidx.compose.material3.SmallFloatingActionButton(onClick = { pendingImages.removeAt(i) },
                            modifier = Modifier.size(22.dp).align(Alignment.TopEnd)) { Text("×", fontSize = 12.sp) }
                    }
                }
            }
            Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = {
                    picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, enabled = ready && !running) { Icon(Icons.Default.AddPhotoAlternate, "Adjuntar imagen", tint = AccentWarm) }
                OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), enabled = ready, maxLines = 4,
                    placeholder = { Text(if (preset == AgentPreset.CHAT) "Pregunta algo…" else "Escribe o pulsa enviar para empezar") })
                Spacer(Modifier.size(6.dp))
                if (running) IconButton(onClick = { session.stop() }) { Icon(Icons.Default.Stop, "Parar", tint = Danger) }
                else IconButton(onClick = { send() }, enabled = ready) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = Accent) }
            }
        }
        }
    }
}

/**
 * Panel lateral del agente: el proyecto que edita la IA ahora y tus otros proyectos
 * (decompilaciones), cada uno con botón para exportarlo firmado. Tocar uno cambia la IA a ese proyecto.
 */
@Composable
private fun ProjectsSidePanel(current: File, onSwitch: (File) -> Unit, onExport: (File) -> Unit) {
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val projects by androidx.compose.runtime.produceState<List<dev.apkforge.engine.ProjectInfo>>(emptyList(), current) {
        value = kotlinx.coroutines.withContext(dev.apkforge.app.core.Workers.dispatcher) {
            runCatching { dev.apkforge.engine.ApkProject.listProjects(dev.apkforge.app.core.Workspace.projects) }.getOrDefault(emptyList())
        }
    }
    val active = AgentStore.activeTargets()
    LazyColumn(
        Modifier.width(if (wide) 240.dp else 150.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(vertical = 6.dp),
    ) {
        item {
            Text("Editando", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            SideProject(current.name, isCurrent = true, working = active[current.absolutePath] == true,
                exportable = dev.apkforge.engine.ApkProject.isProject(current), onClick = {}, onExport = { onExport(current) })
            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Proyectos", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        items(projects.filter { it.dir.absolutePath != current.absolutePath }, key = { it.dir.absolutePath }) { p ->
            SideProject(p.dir.name, isCurrent = false, working = active[p.dir.absolutePath] == true, exportable = true,
                onClick = { onSwitch(p.dir) }, onExport = { onExport(p.dir) })
        }
        if (projects.size <= 1) item {
            Text("Decompila otros APK y aparecerán aquí.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
        }
    }
}

@Composable
private fun SideProject(name: String, isCurrent: Boolean, working: Boolean, exportable: Boolean, onClick: () -> Unit, onExport: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FolderSpecial, null, tint = Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(name, fontSize = 12.sp, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (working) Text("IA trabajando…", fontSize = 10.sp, color = AccentWarm, modifier = Modifier.padding(start = 24.dp))
        if (exportable) androidx.compose.material3.TextButton(onClick = onExport, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = Modifier.padding(start = 16.dp)) {
            Icon(Icons.Default.Save, null, Modifier.size(14.dp), tint = Ok); Spacer(Modifier.size(4.dp)); Text("Exportar APK", fontSize = 11.sp, color = Ok)
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
                Column(Modifier.padding(12.dp)) {
                    if (m.images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        m.images.take(4).forEach { Thumb(it, 72) }
                    }
                    Text(m.text, fontSize = 14.sp)
                }
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


/** Tareas guiadas: la app prepara la instrucción; tú completas el detalle. */
private val quickTasks = listOf(
    "Cambiar nombre de la app" to "Cambia el nombre visible de la app a \"\"",
    "Cambiar icono" to "Cambia el icono de la app por la imagen que adjunto (impórtala y úsala en todos los mipmap).",
    "Traducir al español" to "Traduce al español todos los textos visibles de la app que estén en otro idioma (strings.xml base).",
    "Cambiar colores" to "Cambia los colores principales del tema a: ",
    "Explicar esta pantalla" to "Te adjunto una captura: dime qué layout y qué clases forman esta pantalla y cómo se podría cambiar.",
    "Cambiar un texto" to "Cambia el texto \"\" por \"\" en toda la app.",
    "Cambiar diseño de lista" to "En la pantalla de la captura, haz que los elementos de la lista se muestren en cuadrícula de 3 columnas.",
)

/** Lee una imagen elegida y la reduce (máx. 1280 px, JPEG) para enviarla rápido. */
private fun loadImage(ctx: android.content.Context, uri: android.net.Uri): ByteArray? = runCatching {
    val raw = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@runCatching raw
    val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
    val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
    java.io.ByteArrayOutputStream().also { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}.getOrNull()

@Composable
private fun Thumb(bytes: ByteArray, sizeDp: Int) {
    val bmp = remember(bytes) { runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
    if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.size(sizeDp.dp).androidx_clip(),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

private fun Modifier.androidx_clip() = this.then(Modifier.clip(RoundedCornerShape(8.dp)))


/** Conversaciones anteriores de esta carpeta y perfil (la más reciente arriba). Abrir una la recupera para reanudarla. */
@Composable
private fun ChatHistoryDialog(current: File?, running: Boolean, onDismiss: () -> Unit, onOpen: (dev.apkforge.engine.ChatHistory.Chat) -> Unit) {
    val list = remember(current) { current?.let { dev.apkforge.engine.ChatHistory.archives(it) }.orEmpty() }
    val fmt = remember { java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Historial de conversaciones") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (list.isEmpty()) Text("No hay conversaciones anteriores. Al pulsar “Nueva conversación”, la actual se guarda aquí.", fontSize = 13.sp)
                list.forEach { (f, c) ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(fmt.format(java.util.Date(c.updated)) + " · ${c.messages.size} mensajes", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(c.messages.firstOrNull { it.kind == "user" }?.text?.take(90) ?: "", fontSize = 11.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            androidx.compose.material3.TextButton(enabled = !running, onClick = { f.delete(); onOpen(c) }) { Text("Abrir") }
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}
