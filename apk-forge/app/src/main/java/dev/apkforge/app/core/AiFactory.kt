package dev.apkforge.app.core

import com.anthropic.models.beta.messages.BetaOutputConfig
import dev.apkforge.engine.AiProvider
import dev.apkforge.engine.AiTextClient
import dev.apkforge.engine.ClaudeTextClient
import dev.apkforge.engine.OpenAiTextClient

/** Crea el cliente de IA (Claude u otro proveedor) según los Ajustes. */
object AiFactory {
    /** Etiqueta del modelo de IA en uso ahora (para la cabecera): "Gemini: gemini-flash-latest", "Equipo gratis", etc. */
    fun label(p: Prefs): String {
        val pr = p.aiProvider
        val n = runCatching { p.builtinEndpoints().size }.getOrDefault(0)
        return when (pr) {
            dev.apkforge.engine.AiProvider.BUILTIN -> if (n > 0) "Equipo de $n IAs (gratis)" else "Equipo IA (gratis)"
            dev.apkforge.engine.AiProvider.CLAUDE -> "Claude: ${p.modelFor(pr)}"
            dev.apkforge.engine.AiProvider.OLLAMA -> "Ollama: ${p.modelFor(pr)}"
            dev.apkforge.engine.AiProvider.CUSTOM -> "Personalizado: ${p.modelFor(pr)}"
            // Nube con clave (OpenRouter/Gemini/Groq): tu modelo LIDERA y va turnando con el resto.
            else -> "Equipo de $n · 1ª: ${p.modelFor(pr)}"
        }
    }

    fun effort(p: Prefs) = when (p.agentEffort) {
        "low" -> BetaOutputConfig.Effort.LOW; "medium" -> BetaOutputConfig.Effort.MEDIUM
        "xhigh" -> BetaOutputConfig.Effort.XHIGH; "max" -> BetaOutputConfig.Effort.MAX
        else -> BetaOutputConfig.Effort.HIGH
    }

    fun textClient(p: Prefs): AiTextClient? {
        if (!p.aiReady()) return null
        val pr = p.aiProvider
        // La IA local NO responde: solo la nube. (El motor neuronal sigue ayudando en la búsqueda.)
        if (pr == AiProvider.BUILTIN) return OpenAiTextClient(p.builtinEndpoints())
        return if (pr == AiProvider.CLAUDE) ClaudeTextClient(p.keyFor(pr), p.modelFor(pr), effort(p))
        else OpenAiTextClient(p.urlFor(pr), p.keyFor(pr), p.modelFor(pr))
    }
}
