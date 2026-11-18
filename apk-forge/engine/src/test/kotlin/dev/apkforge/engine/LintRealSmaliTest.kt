package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class LintRealSmaliTest {
    @Test fun noFalsePositivesOnRealCode() {
        val dir = File(System.getProperty("java.io.tmpdir"), "apkforge-test/proj/smali")
        assumeTrue(dir.isDirectory)
        val files = dir.walkTopDown().filter { it.extension == "smali" }.toList()
        val bad = files.mapNotNull { f -> CodeAssistant.lintRegisters(f.readText())?.let { f to it } }
        println("Smali revisados: ${files.size}, con avisos: ${bad.size}")
        bad.take(5).forEach { println("  ${it.first.name}: ${it.second.lines().first()}") }
        assertTrue(bad.isEmpty(), "No debe marcar errores en smali real que funciona")
    }
}
