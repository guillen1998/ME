package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/** El caso del usuario: la IA edita un layout del proyecto -> el APK exportado DEBE llevar el cambio. */
class EditExportTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun aiEditReachesExportedApk() {
        assumeTrue(sample != null)
        val root = File(System.getProperty("java.io.tmpdir"), "editexport").apply { deleteRecursively(); mkdirs() }
        val proj = File(root, "proj")
        ApkProject.decompile(sample!!, proj, DecompileOptions())
        // Un layout real con layout_height en dp
        val rx = Regex("android:layout_height=\"[0-9.]+dp\"")
        val layout = File(proj, "resources").walkTopDown().first { f ->
            f.path.contains("/res/layout/") && f.isFile && rx.find(f.readText())?.value?.let { v -> Regex(Regex.escape(v)).findAll(f.readText()).count() == 1 } == true
        }
        val rel = layout.relativeTo(proj).path
        val text = layout.readText()
        val old = Regex("android:layout_height=\"[0-9.]+dp\"").find(text)!!.value
        println("Editando $rel: $old -> android:layout_height=\"160.0dp\"")

        // Lo que hace la IA: replace_in_file con la caja de herramientas real
        val tb = AgentToolbox(proj, null, allowEdits = true) { null }
        val r = tb.run("replace_in_file", mapOf("path" to rel, "old" to old, "new" to "android:layout_height=\"160.0dp\""))
        println("Herramienta: $r")
        println(tb.run("list_changes", emptyMap()).lines().take(8).joinToString("\n"))
        assertTrue(ChangeLog.all(proj).size == 1)

        val out = File(root, "Compilados/mod.apk").apply { parentFile.mkdirs() }
        ApkProject.build(proj, out)
        assertTrue(ApkInspector.verify(out).verified)
        assertTrue(File(root, "Compilados/mod_cambios.txt").readText().contains(rel), "Debe dejar el informe junto al APK")

        // Comprobar DENTRO del APK exportado que el layout cambió (las rutas pueden estar acortadas: res/aB.xml)
        val countIn = { apk: File -> ApkArchive.open(apk).use { a ->
            java.util.zip.ZipFile(apk).use { z -> z.entries().toList().map { it.name } }
                .filter { it.startsWith("res/") && it.endsWith(".xml") }
                .count { runCatching { a.decodeXml(it).contains("\"160.0dp\"") }.getOrDefault(false) }
        } }
        val inOriginal = countIn(sample)
        val inExported = countIn(out)
        println("Layouts con 160.0dp → original: $inOriginal · exportado: $inExported")
        assertTrue(inExported == inOriginal + 1, "El cambio debe estar en el APK exportado")
        assertTrue(ChangeLog.sinceLastExport(proj).isEmpty(), "Tras exportar, no quedan cambios pendientes")
    }
}
