package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditEverythingTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun aiCanEditBinariesAndStructure() {
        assumeTrue(sample != null)
        val root = File(System.getProperty("java.io.tmpdir"), "editall").apply { deleteRecursively(); mkdirs() }
        val proj = File(root, "proj")
        ApkProject.decompile(sample!!, proj, DecompileOptions(decodeDex = false))
        val so = proj.walkTopDown().first { it.extension == "so" }
        val rel = so.relativeTo(proj).path
        val size = so.length()
        val tb = AgentToolbox(proj, null, allowEdits = true) { null }

        // 1) Binario .so: buscar un texto y reemplazarlo manteniendo el tamaño
        val target = BinaryTools.strings(so).first { it.length in 8..30 && it.all { c -> c.isLetter() || c == '_' } }
        println("Parcheando en $rel el texto '$target'")
        println(tb.run("hex_read", mapOf("path" to rel, "find_text" to target)).lines().first())
        val newText = "FORGE".padEnd(target.length, 'X').take(target.length)
        println(tb.run("hex_patch", mapOf("path" to rel, "find_text" to target, "replace_text" to newText)))
        assertEquals(size, so.length(), "El .so debe mantener su tamaño")
        assertTrue(BinaryTools.strings(so).any { it.contains(newText) })

        // 2) Escribir bytes en un offset
        println(tb.run("hex_patch", mapOf("path" to rel, "offset" to 16.0, "hex" to "AA BB")))
        val bytes = so.readBytes(); assertTrue(bytes[16] == 0xAA.toByte() && bytes[17] == 0xBB.toByte())

        // 3) Importar, mover, borrar y reemplazar en todo el proyecto
        val ext = File(root, "icono.png").apply { writeBytes(byteArrayOf(-119, 80, 78, 71, 1, 2, 3)) }
        println(tb.run("import_file", mapOf("source" to ext.absolutePath, "to" to "root/assets/nuevo.png")))
        println(tb.run("move_file", mapOf("from" to "root/assets/nuevo.png", "to" to "root/assets/forge/icono.png")))
        assertTrue(File(proj, "root/assets/forge/icono.png").isFile)
        val strings = proj.walkTopDown().first { it.path.endsWith("res/values/strings.xml") }
        File(strings.parentFile, "forge_tmp.xml").writeText("<resources/>")
        println(tb.run("delete_file", mapOf("path" to File(strings.parentFile, "forge_tmp.xml").relativeTo(proj).path)))
        println(tb.run("replace_all", mapOf("find" to "F-Droid", "replace" to "ForgeDroid", "extension" to "xml")).lines().first())

        // 4) Todo queda en el informe, y al exportar el .so parcheado va DENTRO del APK
        val report = tb.run("list_changes", emptyMap())
        println(report.lines().take(3).joinToString("\n"))
        assertTrue(report.contains(rel) && report.contains("icono.png"))
        val out = File(root, "out.apk")
        ApkProject.build(proj, out)
        assertTrue(ApkInspector.verify(out).verified)
        ZipFile(out).use { z ->
            val e = z.entries().toList().first { it.name.endsWith("/" + so.name) && it.name.contains(so.parentFile.name) }
            val inApk = z.getInputStream(e).readBytes()
            assertTrue(inApk.contentEquals(so.readBytes()), "El .so del APK exportado debe ser el parcheado")
            assertTrue(z.getEntry("assets/forge/icono.png") != null, "El archivo importado debe ir en el APK")
            println("APK exportado: .so parcheado ✔ (${e.name}), assets/forge/icono.png ✔")
        }
    }
}
