package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class IntegrityScannerTest {
    /** Prueba contra un proyecto smali real pasado con -Dapkforge.smaliDir=... */
    @Test fun detectsOnRealApp() {
        val dir = System.getProperty("apkforge.smaliDir")?.let(::File)?.takeIf { File(it, "smali").isDirectory }
        assumeTrue(dir != null)
        val rep = IntegrityScanner.scan(dir!!) { println("  $it") }
        println(rep.explain())
        println("\nblocksAfterResign=${rep.blocksAfterResign}")
        assertTrue(rep.findings.any { it.kind == IntegrityScanner.Kind.SIGNATURE_SERVER }, "Debía detectar la firma enviada al servidor")
    }

    @Test fun cleanProjectNoFalsePositive() {
        val p = File(System.getProperty("java.io.tmpdir"), "integ-clean").apply { deleteRecursively(); File(this, "smali/a").mkdirs() }
        File(p, "smali/a/Main.smali").writeText(".class public La/Main;\n.super Ljava/lang/Object;\n.method x()V\n.locals 0\nreturn-void\n.end method\n")
        assertTrue(IntegrityScanner.scan(p).findings.isEmpty())
    }
}
