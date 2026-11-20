package dev.apkforge.app.core

import dev.apkforge.app.ApkForgeApp
import dev.apkforge.engine.AgentConfig
import dev.apkforge.engine.AgentPreset
import dev.apkforge.engine.AiAgent
import dev.apkforge.engine.AiProvider
import dev.apkforge.engine.ForgeAgent
import dev.apkforge.engine.JavaDecompiler
import dev.apkforge.engine.OpenAiCompatAgent
import dev.apkforge.engine.AiTeam
import dev.apkforge.engine.ApkProject
import dev.apkforge.engine.BuiltinAi
import dev.apkforge.engine.OpenAiTextClient
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
        if (provider == AiProvider.BUILTIN) {
            val eps = prefs.builtinEndpoints()
            val exec = OpenAiCompatAgent(target, apkFile, eps, preset, allowEdits, java, session.onEvent)
            // Equipo: un modelo planifica, el agente ejecuta con herramientas y OTRO modelo revisa los cambios
            val project = (if (target.isDirectory) target else target.parentFile)?.takeIf { ApkProject.isProject(it) }
            if (project == null || !allowEdits) return exec
            val planners = BuiltinAi.plannerOrder(eps); val reviewers = BuiltinAi.reviewerOrder(eps)
            val local = { if (prefs.reasonMode != "off") dev.apkforge.engine.NeuralBrain.reasoner() else null }
            return AiTeam(project, exec, dev.apkforge.engine.FallbackTextClient(OpenAiTextClient(planners), local),
                dev.apkforge.engine.FallbackTextClient(OpenAiTextClient(reviewers), local),
                planners.first().label, reviewers.first().label, session.onEvent)
        }
        if (provider == AiProvider.CLAUDE)
            return ForgeAgent(target, apkFile, AgentConfig(prefs.keyFor(provider), prefs.modelFor(provider), AiFactory.effort(prefs), allowEdits),
                preset, java, session.onEvent)
        // Tu modelo elegido (p. ej. Gemini) va PRIMERO. Si es un proveedor de nube con clave y se
        // queda sin cuota, da 503 (saturado) o falla, la IA SIGUE SOLA con el equipo gratis detrás,
        // sin que tengas que cambiar nada. Ollama/Personalizado (local/privado) no añaden respaldo
        // público, para no filtrar tu código a servicios externos.
        val primary = dev.apkforge.engine.AiEndpoint(prefs.urlFor(provider), prefs.keyFor(provider), prefs.modelFor(provider),
            "${prefs.modelFor(provider)} (${provider.title.substringBefore(" ·")})")
        val chain = if (provider.needsKey)
            (listOf(primary) + BuiltinAi.endpoints).distinctBy { it.baseUrl + "|" + it.model }
        else listOf(primary)
        return OpenAiCompatAgent(target, apkFile, chain, preset, allowEdits, java, session.onEvent)
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
