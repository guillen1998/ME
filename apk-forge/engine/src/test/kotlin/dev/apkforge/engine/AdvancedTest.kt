package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class AdvancedTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }
    private val work = File(System.getProperty("java.io.tmpdir"), "apkforge-adv").apply { mkdirs() }
    private val log = ProgressListener { println("  · $it") }

    @Test fun scanFindsUrls() {
        assumeTrue(sample != null)
        val findings = ProjectScanner.scan(sample!!, listener = log)
        val byKind = findings.groupingBy { it.kind }.eachCount()
        println("Hallazgos por tipo: $byKind  (total ${findings.size})")
        findings.filter { it.kind == FindingKind.URL }.take(8).forEach { println("  URL ${it.value}  <= ${it.source}") }
        findings.filter { it.kind == FindingKind.SECRET }.take(8).forEach { println("  SECRET ${it.rule}: ${it.value.take(40)}  <= ${it.source}") }
        assertTrue(findings.any { it.kind == FindingKind.URL }, "Debe encontrar URLs dentro del APK/dex")
    }

    @Test fun binaryTools() {
        assumeTrue(sample != null)
        // Extraer un .so del APK y leerlo
        val tmp = File(work, "libs").apply { mkdirs() }
        java.util.zip.ZipFile(sample!!).use { z ->
            val so = z.entries().toList().firstOrNull { it.name.endsWith(".so") }
            if (so != null) {
                val out = File(tmp, "lib.so")
                z.getInputStream(so).use { i -> out.outputStream().use { i.copyTo(it) } }
                println("Tipo: ${BinaryTools.identify(out)}")
                val strings = BinaryTools.strings(out)
                println("Cadenas en .so: ${strings.size}; ejemplos: ${strings.filter { it.length > 8 }.take(5)}")
                println("Hex:\n${BinaryTools.hexDump(out, 0, 128)}")
                assertTrue(BinaryTools.identify(out).startsWith("ELF"))
            }
        }
        // dex strings
        val dexTmp = File(work, "d").apply { mkdirs() }
        java.util.zip.ZipFile(sample!!).use { z ->
            val d = z.entries().toList().first { it.name == "classes.dex" }
            val out = File(dexTmp, "classes.dex")
            z.getInputStream(d).use { i -> out.outputStream().use { i.copyTo(it) } }
            val s = BinaryTools.dexStrings(out)
            println("Cadenas en dex: ${s.size}")
            assertTrue(s.size > 100)
        }
    }

    @Test fun studioProject() {
        assumeTrue(sample != null)
        val out = File(work, "studio")
        out.deleteRecursively()
        ApkProject.exportStudioProject(sample!!, out, listener = log)
        val must = listOf("settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml", "app/src/main/res", "app/src/main/java")
        must.forEach { assertTrue(File(out, it).exists(), "Falta $it") }
        val manifest = File(out, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(!manifest.contains("package="), "El manifest no debe llevar package= (AGP 8)")
        val javaFiles = File(out, "app/src/main/java").walkTopDown().count { it.extension == "java" }
        println("Proyecto: $javaFiles .java, res=${File(out, "app/src/main/res").list()?.size} tipos")
        assertTrue(javaFiles > 10)
    }
}
