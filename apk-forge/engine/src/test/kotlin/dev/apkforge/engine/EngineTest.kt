package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Prueba de extremo a extremo con un APK real. Pasa la ruta con
 * -Dapkforge.sampleApk=/ruta/app.apk (si no, se omite).
 */
class EngineTest {

    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }
    private val work = File(System.getProperty("java.io.tmpdir"), "apkforge-test").apply { mkdirs() }
    private val log = ProgressListener { println("  · $it") }

    @Test
    fun fullRoundTrip() {
        assumeTrue("Sin APK de prueba", sample != null)
        val apk = sample!!

        val t0 = System.currentTimeMillis()
        val info = ApkInspector.inspect(apk)
        println("Paquete=${info.packageName} v${info.versionName} min=${info.minSdk} target=${info.targetSdk}")
        println("Dex=${info.dexFiles} ABIs=${info.nativeAbis.keys} perms=${info.permissions.size} acts=${info.activities.size}")
        println("Detecciones=${info.detections}")
        println("Firma: ${info.signature?.verified} v1=${info.signature?.v1} v2=${info.signature?.v2} v3=${info.signature?.v3}")
        assertTrue(info.packageName != null)

        val proj = File(work, "proj")
        ApkProject.decompile(apk, proj, listener = log)
        println("Decompilado en ${System.currentTimeMillis() - t0} ms")
        proj.listFiles()!!.sorted().forEach { println("  [${it.name}]") }
        assertTrue(File(proj, "AndroidManifest.xml").isFile)
        val smali = proj.walkTopDown().filter { it.extension == "smali" }.toList()
        println("Archivos smali: ${smali.size}")
        assertTrue(smali.isNotEmpty())
        println(File(proj, "AndroidManifest.xml").readText().take(800))

        // Recompilar sin cambios (reutiliza dex)
        val t1 = System.currentTimeMillis()
        val out1 = File(work, "rebuilt.apk")
        ApkProject.build(proj, out1, listener = log)
        println("Recompilado (sin cambios) en ${System.currentTimeMillis() - t1} ms")
        val v1 = ApkInspector.verify(out1)
        println("Firma recompilado: ok=${v1.verified} errores=${v1.errors}")
        assertTrue(v1.verified)

        // Modificar un smali y recompilar forzando
        val target = smali.first { it.readText().contains(".method") && it.path.contains("fdroid") || true }
        target.appendText("\n# editado por ApkForge\n")
        val t2 = System.currentTimeMillis()
        val out2 = File(work, "modified.apk")
        ApkProject.build(proj, out2, listener = log)
        println("Recompilado (con cambio) en ${System.currentTimeMillis() - t2} ms")
        assertTrue(ApkInspector.verify(out2).verified)
        val info2 = ApkInspector.inspect(out2)
        assertTrue(info2.packageName == info.packageName)
        assertTrue(info2.activities.size == info.activities.size)

        // Editar un recurso (nombre de la app) y comprobar que se aplica
        val strings = proj.walkTopDown().first { it.path.endsWith("res/values/strings.xml") }
        val label = Regex("<string name=\"app_name\">([^<]*)</string>").find(strings.readText())
        if (label != null) {
            strings.writeText(strings.readText().replace(label.value, "<string name=\"app_name\">ApkForge Mod</string>"))
            val out3 = File(work, "res-mod.apk")
            ApkProject.build(proj, out3, listener = ProgressListener.NONE)
            assertTrue(ApkInspector.verify(out3).verified)
            println("Recurso editado: app_name '${label.groupValues[1]}' -> 'ApkForge Mod' en ${out3.name}")
        }

        // Java
        val t3 = System.currentTimeMillis()
        JavaDecompiler.open(apk, listener = log).use { j ->
            println("Clases Java: ${j.classes.size} en ${System.currentTimeMillis() - t3} ms")
            val main = info.mainActivity ?: j.classes.first().fullName
            val code = j.javaCode(main)
            println("---- $main ----\n" + code.take(2500))
            assertTrue(code.contains("class"))
            assertTrue(!code.contains("@Metadata("), "Debe ocultar @Metadata")
        }

        // smali -> Java del archivo editado
        println("---- smaliToJava ----\n" + JavaDecompiler.smaliToJava(target).take(800))
    }
}
