package dev.apkforge.engine

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Si no me gustó": restaurar a una fecha, borrar copias y deshacer la restauración. */
class ProjectHistoryTest {
    private fun project(): File {
        val p = File(System.getProperty("java.io.tmpdir"), "history").apply { deleteRecursively(); mkdirs() }
        File(p, ".apkforge/project.properties").apply { parentFile.mkdirs(); writeText("x=1") }
        File(p, "res/values").mkdirs()
        File(p, "res/values/strings.xml").writeText("<resources><string name=\"a\">Original</string></resources>")
        return p
    }

    private fun edit(f: File, text: String, who: String) { val old = if (f.isFile) f.readText() else null; f.parentFile.mkdirs(); f.writeText(text); ChangeLog.record(f, old, text, who) }

    /** Simula que los cambios ocurrieron en momentos distintos (separados > 5 min). */
    private fun shiftAll(p: File, ms: Long) {
        val g = com.google.gson.Gson()
        val log = File(p, ".apkforge/changes.jsonl")
        log.writeText(log.readLines().filter { it.isNotBlank() }.joinToString("") { g.toJson(g.fromJson(it, ChangeEntry::class.java).let { e -> e.copy(time = e.time - ms) }) + "\n" })
        File(p, ".apkforge/backup").listFiles()!!.forEach { d -> d.renameTo(File(d.parentFile, (d.name.toLong() - ms).toString())) }
    }

    @Test fun restoreDeleteAndUndo() {
        val p = project()
        val strings = File(p, "res/values/strings.xml")
        val layout = File(p, "res/layout/nuevo.xml")
        edit(strings, "<resources><string name=\"a\">Cambio IA 1</string></resources>", "IA")
        edit(layout, "<LinearLayout/>", "IA")
        shiftAll(p, 20 * 60_000L)                     // primer punto: hace 20 min
        edit(strings, "<resources><string name=\"a\">Cambio manual 2</string></resources>", "Manual")

        val pts = ProjectHistory.points(p)
        pts.forEach { println("  punto ${it.start} · ${it.who} · ${it.files}") }
        assertEquals(2, pts.size, "Dos tandas de cambios = dos puntos")
        assertEquals(listOf("Manual"), pts.first().who, "El más reciente primero")

        // Restaurar a antes del cambio manual → queda el de la IA
        println(ProjectHistory.restoreBefore(p, pts[0]).summary())
        assertTrue(strings.readText().contains("Cambio IA 1"))
        assertTrue(layout.isFile)

        // Restaurar al original → texto original y el layout nuevo desaparece
        val r = ProjectHistory.restoreOriginal(p)!!
        println(r.summary())
        assertTrue(strings.readText().contains("Original"))
        assertFalse(layout.exists(), "El archivo creado por la IA debía quitarse")

        // La restauración también se puede deshacer
        val undo = ProjectHistory.points(p).first()
        assertEquals(listOf("Restaurar"), undo.who)
        ProjectHistory.restoreBefore(p, undo)
        assertTrue(strings.readText().contains("Cambio IA 1") || strings.readText().contains("Cambio manual 2"))

        // Borrar un punto y borrar todo
        val before = ProjectHistory.points(p).size
        ProjectHistory.delete(p, ProjectHistory.points(p).last())
        assertEquals(before - 1, ProjectHistory.points(p).size)
        ProjectHistory.deleteAll(p)
        assertTrue(ProjectHistory.points(p).isEmpty())
        assertFalse(File(p, ".apkforge/backup").exists())
    }
}
