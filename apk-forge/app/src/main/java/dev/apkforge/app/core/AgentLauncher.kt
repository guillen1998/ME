package dev.apkforge.app.core

import dev.apkforge.app.ApkForgeApp
import dev.apkforge.engine.AgentConfig
import dev.apkforge.engine.AgentPreset
import dev.apkforge.engine.AiAgent
import dev.apkforge.engine.AiProvider
import dev.apkforge.engine.ForgeAgent
import dev.apkforge.engine.JavaDecompiler
import dev.apkforge.engine.OpenAiCompatAgent
import java.io.File

/**
 * Crea y lanza agentes desde cualquier sitio (no solo desde el chat): por ejemplo, al terminar
 * de decompilar, la IA empieza sola la tarea que pediste sobre el proyecto.
 */
object AgentLauncher {

    /** Clave de sesión: la misma que usa la pantalla del agente, para que al abrirla veas el trabajo. */
    fun key(target: File, preset: AgentPreset, generation: Int = 0) =
        "${target.absolutePath}|$preset|${ApkForgeApp.prefs.aiProvider}|$generation"

    fun create(target: File, preset: AgentPreset, session: AgentSession, allowEdits: Boolean): AiAgent? {
        val prefs = ApkForgeApp.prefs
        if (!prefs.aiReady()) return null
        val provider = prefs.aiProvider
        val apkFile = if (target.isFile && target.extension.equals("apk", true)) target else null
        val javaInput = if (target.isDirectory || apkFile != null) target else null
        val java: () -> JavaDecompiler? = { javaInput?.let { runCatching { JavaSessions.get(it, prefs.javaOptions()) }.getOrNull() } }
        if (provider == AiProvider.BUILTIN) return OpenAiCompatAgent(target, apkFile, dev.apkforge.engine.BuiltinAi.endpoints,
            preset, allowEdits, java, session.onEvent)
        return if (provider == AiProvider.CLAUDE)
            ForgeAgent(target, apkFile, AgentConfig(prefs.keyFor(provider), prefs.modelFor(provider), AiFactory.effort(prefs), allowEdits),
                preset, java, session.onEvent)
        else OpenAiCompatAgent(target, apkFile, prefs.urlFor(provider), prefs.keyFor(provider), prefs.modelFor(provider),
            preset, allowEdits, java, session.onEvent)
    }

    /**
     * Lanza la tarea [prompt] sobre [target] en segundo plano. Si la pides tú explícitamente
     * ("que la IA lo haga"), el agente puede editar archivos (siempre con copia de seguridad).
     * Devuelve false si no hay proveedor de IA configurado.
     */
    fun start(target: File, prompt: String, preset: AgentPreset = AgentPreset.MODDER, allowEdits: Boolean = true): Boolean {
        if (!ApkForgeApp.prefs.aiReady()) return false
        val session = AgentStore.session(key(target, preset), preset.title) { s -> create(target, preset, s, allowEdits) }
        if (session.agent == null) return false
        onMain { session.send(prompt) }
        return true
    }
}
