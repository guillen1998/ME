package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Da muchos errores de compilación": varios errores a la vez → se reparan o se deshacen y SIEMPRE sale el APK. */
class BuildDoctorTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    private fun edit(f: File, transform: (String) -> String) { val old = f.readText(); val new = transform(old); f.writeText(new); ChangeLog.record(f, old, new, "IA", "test") }

    @Test fun manyErrorsStillExport() {
        assumeTrue(sample != null)
        val root = File(System.getProperty("java.io.tmpdir"), "doctor").apply { deleteRecursively(); mkdirs() }
        val proj = File(root, "proj")
        ApkProject.decompile(sample!!, proj, DecompileOptions())
        val res = File(proj, "resources/package_1/res")
        val smali = proj.walkTopDown().first { it.path.contains("/org/fdroid/") && it.extension == "smali" && it.readText().contains("const-string") }
        val smali2 = proj.walkTopDown().filter { it.path.contains("/org/fdroid/") && it.extension == "smali" && it.readText().contains("return-void") }.drop(3).first()
        // 1) smali roto (instrucción inventada) 2) smali con registro fuera de rango
        edit(smali) { it.replaceFirst("const-string", "const-strin") }
        edit(smali2) { it.replaceFirst("return-void", "const/4 v99, 0x1\n    return-void") }
        // 3) XML mal cerrado 4) color duplicado 5) un cambio BUENO que debe sobrevivir
        val colors = File(res, "values/colors.xml")
        val firstColor = Regex("""<color name="([^"]+)"""").find(colors.readText())!!.groupValues[1]
        edit(colors) { it.replace("</resources>", "  <color name=\"$firstColor\">#FF0000</color>\n  <color name=\"forge_ok\">#00FF00</color>\n</resources>") }
        val strings = File(res, "values/strings.xml")
        edit(strings) { it.replace("</resources>", "  <string name=\"forge_broken\">sin cerrar\n</resources>") }

        val problems = BuildDoctor.diagnose(proj)
        problems.forEach { println("  • " + it.label(proj)) }
        assertTrue(problems.size >= 4, "Debía encontrar todos los errores, no solo el primero")

        // IA de mentira: arregla el smali con la instrucción mal escrita
        val fakeAi = object : AiTextClient {
            override fun complete(system: String, user: String): String {
                val code = Regex("```\\n([\\s\\S]*?)\\n```").find(user)!!.groupValues[1]
                return "```\n" + code.replace("const-strin ", "const-string ") + "\n```"
            }
        }
        val log = StringBuilder()
        val out = File(root, "out.apk")
        BuildDoctor.buildRepairing(proj, out, BuildOptions(), fakeAi, ProgressListener { log.appendLine(it); println(it) })
        assertTrue(ApkInspector.verify(out).verified)
        assertTrue(smali.readText().contains("const-string"), "La IA debía reparar el primer smali")
        assertTrue(!smali2.readText().contains("v99"), "El smali irreparable debía deshacerse")
        assertTrue(log.contains("IA reparó"))
        assertTrue(log.contains("Deshecho"))
        val c = colors.readText()
        assertTrue(c.contains("forge_ok"), "El cambio bueno de colors.xml debía sobrevivir")
        assertEquals(1, Regex("name=\"$firstColor\"").findAll(c).count())
        assertTrue(c.contains("#FF0000"), "El valor nuevo del color repetido debía aplicarse")
    }
}
