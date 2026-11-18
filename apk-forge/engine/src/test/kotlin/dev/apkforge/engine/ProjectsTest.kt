package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectsTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun findProjectOfApkAndStudioMarker() {
        assumeTrue(sample != null)
        val root = File(System.getProperty("java.io.tmpdir"), "projects-test").apply { deleteRecursively(); mkdirs() }
        val proj = File(root, "org.fdroid_v2")
        ApkProject.decompile(sample!!, proj, DecompileOptions(decodeDex = false))
        val found = ApkProject.findProjectFor(sample, root)
        assertEquals(proj.absolutePath, found?.dir?.absolutePath, "Debe encontrar el proyecto ya decompilado de ese APK")
        assertEquals(1, ApkProject.listProjects(root).size)

        val studio = File(root, "AndroidStudio/x").apply { mkdirs() }
        File(studio, "settings.gradle.kts").writeText("x"); File(studio, "app/src/main").mkdirs()
        assertEquals("", ApkProject.studioSource(studio), "Carpeta Studio sin marca = origen desconocido")
        assertTrue(ApkProject.studioSource(proj) == null, "Un proyecto editable no es de Studio")
        // Exportar el proyecto editable sigue funcionando
        val out = File(root, "out.apk")
        ApkProject.build(proj, out)
        assertTrue(ApkInspector.verify(out).verified)
    }
}
