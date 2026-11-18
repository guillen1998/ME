package dev.apkforge.app.core

import com.anthropic.models.beta.messages.BetaOutputConfig
import dev.apkforge.engine.AiProvider
import dev.apkforge.engine.AiTextClient
import dev.apkforge.engine.ClaudeTextClient
import dev.apkforge.engine.OpenAiTextClient

/** Crea el cliente de IA (Claude u otro proveedor) según los Ajustes. */
object AiFactory {
    fun effort(p: Prefs) = when (p.agentEffort) {
        "low" -> BetaOutputConfig.Effort.LOW; "medium" -> BetaOutputConfig.Effort.MEDIUM
        "xhigh" -> BetaOutputConfig.Effort.XHIGH; "max" -> BetaOutputConfig.Effort.MAX
        else -> BetaOutputConfig.Effort.HIGH
    }

    fun textClient(p: Prefs): AiTextClient? {
        if (!p.aiReady()) return null
        val pr = p.aiProvider
        if (pr == AiProvider.BUILTIN) return OpenAiTextClient(p.builtinEndpoints())
        return if (pr == AiProvider.CLAUDE) ClaudeTextClient(p.keyFor(pr), p.modelFor(pr), effort(p))
        else OpenAiTextClient(p.urlFor(pr), p.keyFor(pr), p.modelFor(pr))
    }
}
