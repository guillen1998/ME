package dev.apkforge.engine

import java.io.File

/**
 * Equipo de IAs que trabajan ENTRE SÍ sobre un proyecto:
 *  🧠 Planificador (un modelo grande): convierte el pedido en un plan concreto.
 *  🔧 Ejecutor (agente con herramientas): hace los cambios siguiendo el plan.
 *  🔍 Revisor (otro modelo distinto): revisa los cambios reales + las comprobaciones de la app
 *     (XML válido, registros smali) y, si encuentra fallos, se los devuelve al ejecutor.
 */
class AiTeam(
    private val project: File,
    private val executor: AiAgent,
    private val planner: AiTextClient,
    private val reviewer: AiTextClient,
    private val plannerName: String,
    private val reviewerName: String,
    private val onEvent: (AgentEvent) -> Unit,
    private val maxReviewRounds: Int = 2,
) : AiAgent {

    override fun reset() = executor.reset()

    override fun send(userText: String, isCancelled: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        // 1) Plan
        val plan = runCatching {
            planner.complete(
                "Eres el PLANIFICADOR de un equipo que modifica apps Android decompiladas (smali + recursos XML). " +
                    "Con el pedido del usuario y el contexto que te da la app, escribe un plan BREVE (máx. 8 pasos): qué archivos " +
                    "revisar/editar (rutas concretas si aparecen), qué cambiar exactamente y qué riesgos vigilar (recursos nuevos, " +
                    "registros smali, XML válido). No escribas el código completo. Responde en español.",
                "MAPA DEL PROYECTO:\n${ProjectIndex.build(project).take(6000)}\n\nPEDIDO (con lo que preparó la app):\n${userText.take(8000)}",
            ).trim()
        }.getOrElse { onEvent(AgentEvent.Text("🧠 Planificador no disponible (${it.message?.take(80)}); el ejecutor sigue solo.")); "" }
        if (plan.isNotBlank()) onEvent(AgentEvent.Text("🧠 Plan ($plannerName):\n$plan"))
        if (isCancelled()) return

        // 2) Ejecución
        onEvent(AgentEvent.Text("🔧 Ejecutor: manos a la obra…"))
        executor.send(if (plan.isBlank()) userText else "$userText\n\nPLAN DEL PLANIFICADOR (síguelo, ajústalo si ves algo mejor):\n$plan", isCancelled)

        // 3) Revisión (solo si hubo cambios)
        repeat(maxReviewRounds) { round ->
            if (isCancelled()) return
            val changes = ChangeLog.all(project).filter { it.time >= t0 }
            if (changes.isEmpty()) return
            val checks = appChecks(changes)
            val verdict = runCatching {
                reviewer.complete(
                    "Eres el REVISOR de un equipo que modifica apps Android decompiladas. Revisa los cambios del ejecutor: ¿cumplen el " +
                        "pedido?, ¿pueden romper la compilación o la app (XML inválido, recurso inexistente, registros smali, tipos)? " +
                        "Si todo está bien responde EXACTAMENTE 'OK' en la primera línea y una frase. Si hay problemas, responde " +
                        "'PROBLEMAS' en la primera línea y lista cada uno con archivo y cómo corregirlo. En español.",
                    "PEDIDO:\n${userText.take(3000)}\n\nPLAN:\n${plan.take(2000)}\n\nCOMPROBACIONES DE LA APP:\n$checks\n\nCAMBIOS (diff):\n" +
                        ChangeLog.report(project, changes).take(12000),
                ).trim()
            }.getOrElse { onEvent(AgentEvent.Text("🔍 Revisor no disponible (${it.message?.take(80)}).")); return }
            onEvent(AgentEvent.Text("🔍 Revisión $reviewerName:\n$verdict"))
            val ok = verdict.lineSequence().firstOrNull()?.trim()?.uppercase()?.startsWith("OK") == true && !checks.contains("✖")
            if (ok) return
            if (round == maxReviewRounds - 1) { onEvent(AgentEvent.Text("⚠ Quedan observaciones del revisor: revísalas antes de exportar.")); return }
            onEvent(AgentEvent.Text("🔧 Ejecutor: corrigiendo lo que marcó el revisor…"))
            executor.send("El REVISOR encontró estos problemas en tus cambios. Corrígelos:\n$verdict\n\nComprobaciones de la app:\n$checks", isCancelled)
        }
    }

    /** Comprobaciones automáticas de la app sobre los archivos tocados. */
    private fun appChecks(changes: List<ChangeEntry>): String = buildString {
        changes.map { it.file }.distinct().forEach { rel ->
            val f = File(project, rel)
            when {
                !f.isFile -> appendLine("• $rel: (borrado o movido)")
                f.extension == "xml" -> appendLine("• $rel: " + (XmlCheck.error(f.readText())?.let { "✖ $it" } ?: "✔ XML válido"))
                f.extension == "smali" -> appendLine("• $rel: " + (CodeAssistant.lintRegisters(f.readText())?.let { "✖ $it" } ?: "✔ registros correctos"))
                else -> appendLine("• $rel: modificado")
            }
        }
    }
}
