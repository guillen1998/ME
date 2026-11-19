package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class CrashCatcherTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun injectsAssemblesAndDiagnoses() {
        assumeTrue(sample != null)
        val root = File(System.getProperty("java.io.tmpdir"), "crashcatch").apply { deleteRecursively(); mkdirs() }
        val proj = File(root, "proj")
        ApkProject.decompile(sample!!, proj, DecompileOptions())
        assertTrue(CrashCatcher.inject(proj) { println("  $it") })
        assertTrue(CrashCatcher.isInjected(proj))
        val manifest = File(proj, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("dev.apkforge.crash.CrashProvider") && manifest.contains("WRITE_EXTERNAL_STORAGE"))
        // compila+firma: ENSAMBLA el smali inyectado; si está mal, falla aquí
        val out = File(root, "out.apk")
        ApkProject.build(proj, out)
        assertTrue(ApkInspector.verify(out).verified, "El APK con el capturador debe compilar y firmar")
        // diagnóstico con un stack trace que apunta a una clase propia
        val cls = ProjectReader.ownSmali(proj).first().path.substringAfter("/smali/").substringAfter('/').removeSuffix(".smali").replace('/', '.')
        val crash = "FATAL EXCEPTION: main\njava.lang.NoSuchMethodError: No virtual method i\n\tat $cls.onCreate(SourceFile)\n"
        val diag = CrashCatcher.diagnosis(proj, crash)
        println(diag.take(600))
        assertTrue(diag.contains("NoSuchMethod") && diag.contains(".smali"))
    }

    @Test fun intentUnderstandsCasualCrashReport() {
        val proj = File(System.getProperty("java.io.tmpdir"), "intent").apply { deleteRecursively(); mkdirs() }
        File(proj, ".apkforge/project.properties").apply { parentFile.mkdirs(); writeText("packageName=com.x") }
        File(proj, "smali/a").mkdirs(); File(proj, "smali/a/A.smali").writeText(".class La/A;\n.super Ljava/lang/Object;\n")
        File(proj, "res/values").mkdirs(); val f = File(proj, "res/values/x.xml"); f.writeText("<r/>")
        ChangeLog.record(f, "<r/>", "<r2/>", "IA", "test")
        listOf("la última actualización hizo que se cierre", "ya no abre la app", "tu último cambio la rompió", "se sale sola al abrir").forEach { msg ->
            val out = RequestIntent.detect(proj, msg)
            assertTrue(out != null && out.contains("SE CIERRA"), "No entendió: \"$msg\"")
        }
        // un mensaje normal no dispara el modo crash
        assertTrue(RequestIntent.detect(proj, "cambia el color del fondo a rojo") == null)
        println(RequestIntent.detect(proj, "se cierra al abrir")!!.take(400))
    }
}
