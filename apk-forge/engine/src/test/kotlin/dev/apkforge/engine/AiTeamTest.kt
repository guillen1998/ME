package dev.apkforge.engine

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Las IAs trabajan entre sí: plan → ejecución → revisión → corrección. */
class AiTeamTest {
    @Test fun reviewerSendsProblemsBackToExecutor() {
        val proj = File(System.getProperty("java.io.tmpdir"), "aiteam").apply { deleteRecursively(); mkdirs() }
        File(proj, ".apkforge/project.properties").apply { parentFile.mkdirs(); writeText("x=1") }
        val target = File(proj, "res/values/colors.xml").apply { parentFile.mkdirs(); writeText("<resources></resources>") }
        val sent = mutableListOf<String>()
        val executor = object : AiAgent {
            override fun reset() {}
            override fun send(userText: String, isCancelled: () -> Boolean) {
                sent += userText
                val old = target.readText()
                // 1ª vez deja el XML roto; 2ª lo arregla
                val new = if (sent.size == 1) "<resources><color name=\"lime_green\">#32CD32</resources>" else "<resources><color name=\"lime_green\">#32CD32</color></resources>"
                target.writeText(new); ChangeLog.record(target, old, new, "IA", "test")
            }
        }
        val planner = object : AiTextClient { override fun complete(system: String, user: String) = "1. Añadir color lime_green en colors.xml" }
        var reviews = 0
        val reviewer = object : AiTextClient {
            override fun complete(system: String, user: String): String { reviews++; return if (user.contains("✖")) "PROBLEMAS\n- colors.xml mal cerrado" else "OK\nTodo bien" }
        }
        val events = mutableListOf<String>()
        AiTeam(proj, executor, planner, reviewer, "Qwen", "GPT-OSS", { if (it is AgentEvent.Text) events += it.text }).send("añade un color verde") { false }
        events.forEach { println(it.lines().first()) }
        assertEquals(2, sent.size, "El ejecutor debía corregir tras la revisión")
        assertTrue(sent[0].contains("PLAN DEL PLANIFICADOR"))
        assertTrue(sent[1].contains("REVISOR"))
        assertEquals(2, reviews)
        assertTrue(events.any { it.startsWith("🧠") } && events.any { it.startsWith("🔍") })
    }
}
