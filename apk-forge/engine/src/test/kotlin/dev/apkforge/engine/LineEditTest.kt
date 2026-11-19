package dev.apkforge.engine

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineEditTest {
    private fun proj(): File {
        val p = File(System.getProperty("java.io.tmpdir"), "lineedit").apply { deleteRecursively(); mkdirs() }
        File(p, ".apkforge/project.properties").apply { parentFile.mkdirs(); writeText("x=1") }
        return p
    }

    @Test fun replaceAndInsertByLine() {
        val p = proj()
        File(p, "smali/a").mkdirs()
        val f = File(p, "smali/a/A.smali")
        f.writeText("l1\nl2\nl3\nl4\nl5\n")
        val tb = AgentToolbox(p, null, true) { null }
        // reemplaza líneas 2-3 por una sola
        tb.run("replace_lines", mapOf("path" to "smali/a/A.smali", "start_line" to 2, "end_line" to 3, "new" to "NUEVA"))
        assertEquals("l1\nNUEVA\nl4\nl5\n", f.readText())
        // inserta después de la línea 1
        tb.run("insert_lines", mapOf("path" to "smali/a/A.smali", "after_line" to 1, "new" to "X\nY"))
        assertEquals("l1\nX\nY\nNUEVA\nl4\nl5\n", f.readText())
        // borra la línea 1 (new vacío)
        tb.run("replace_lines", mapOf("path" to "smali/a/A.smali", "start_line" to 1, "end_line" to 1, "new" to ""))
        assertEquals("X\nY\nNUEVA\nl4\nl5\n", f.readText())
        // rango inválido da error claro
        val err = runCatching { tb.run("replace_lines", mapOf("path" to "smali/a/A.smali", "start_line" to 99, "end_line" to 99, "new" to "z")) }.exceptionOrNull()?.message ?: ""
        println(err)
        assertTrue(err.contains("Rango inválido"))
    }
}
