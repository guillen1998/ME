package dev.apkforge.engine

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class ReplaceTolerantTest {
    private fun project(): File {
        val p = File(System.getProperty("java.io.tmpdir"), "repltol").apply { deleteRecursively(); mkdirs() }
        File(p, ".apkforge/project.properties").apply { parentFile.mkdirs(); writeText("x=1") }
        return p
    }

    @Test fun matchesDespiteTrailingSpacesAndIndent() {
        val p = project()
        // el archivo tiene espacios finales y tabs; la IA copia "limpio"
        File(p, "smali/a").mkdirs()
        val f = File(p, "smali/a/A.smali")
        f.writeText(".method public i()V \n    .locals 8  \n    const/4 v0, 0x1\n    return-void\n.end method\n")
        val tb = AgentToolbox(p, null, true) { null }
        // la IA pasa el fragmento sin los espacios finales
        val out = tb.run("replace_in_file", mapOf("path" to "smali/a/A.smali",
            "old" to "    .locals 8\n    const/4 v0, 0x1", "new" to "    .locals 8\n    const/4 v0, 0x2"))
        println(out)
        assertTrue(f.readText().contains("const/4 v0, 0x2"), "Debió reemplazar pese a los espacios finales")
    }

    @Test fun helpfulErrorWhenNotFound() {
        val p = project()
        File(p, "smali/a").mkdirs()
        File(p, "smali/a/A.smali").writeText(".method public getToken()V\n    return-void\n.end method\n")
        val tb = AgentToolbox(p, null, true) { null }
        val err = runCatching { tb.run("replace_in_file", mapOf("path" to "smali/a/A.smali", "old" to "getTokenXYZ inexistente", "new" to "x")) }.exceptionOrNull()?.message ?: ""
        println(err)
        assertTrue(err.contains("read_file"), "Debe orientar a usar read_file")
    }
}
