package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/** El error del usuario: un color nuevo (lime_green) rompía la exportación con "Undefined entry name". */
class NewResourcesTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun newColorStringLayoutAndIdCompile() {
        assumeTrue(sample != null)
        val root = File(System.getProperty("java.io.tmpdir"), "newres").apply { deleteRecursively(); mkdirs() }
        val proj = File(root, "proj")
        ApkProject.decompile(sample!!, proj, DecompileOptions(decodeDex = false))
        val res = File(proj, "resources/package_1/res")
        fun addTo(file: File, xml: String) = file.writeText(file.readText().replace("</resources>", "$xml\n</resources>"))
        addTo(File(res, "values/colors.xml"), """  <color name="lime_green">#32CD32</color>""")
        addTo(File(res, "values/strings.xml"), """  <string name="forge_hello">Hola desde ApkForge</string>""")
        File(res, "layout/forge_card.xml").writeText("""<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="160.0dp" android:layout_height="160.0dp"
    android:background="@color/lime_green"><TextView android:id="@+id/forge_title" android:layout_width="wrap_content"
    android:layout_height="wrap_content" android:text="@string/forge_hello" /></LinearLayout>""")
        val log = StringBuilder()
        val out = File(root, "out.apk")
        ApkProject.build(proj, out, listener = ProgressListener { log.appendLine(it) })
        println(log.lines().filter { it.contains("Registrados") }.joinToString("\n"))
        assertTrue(ApkInspector.verify(out).verified)
        val dump = ProcessBuilder("/opt/android-sdk/build-tools/35.0.0/aapt2", "dump", "resources", out.path)
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        listOf("color/lime_green", "string/forge_hello", "layout/forge_card", "id/forge_title").forEach {
            println("  $it en el APK: ${dump.contains(it)}")
            assertTrue(dump.contains(it), "Falta $it en el APK exportado")
        }
    }
}
