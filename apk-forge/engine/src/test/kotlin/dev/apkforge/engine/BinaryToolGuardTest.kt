package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class BinaryToolGuardTest {
    @Test fun directoryGivesFriendlyMessageAndSoFileWorks() {
        val dir = File(System.getProperty("apkforge.smaliDir") ?: "/tmp/femon/out").takeIf { it.isDirectory }
        assumeTrue(dir != null)
        val tb = AgentToolbox(dir!!, null, false) { null }
        // Carpeta -> mensaje claro, NO EISDIR
        val msg = runCatching { tb.run("binary_strings", mapOf("path" to "smali/com")) }.exceptionOrNull()?.message ?: "SIN ERROR"
        println("Carpeta → $msg")
        assertTrue(msg.contains("CARPETA", true) && !msg.contains("EISDIR"), "Debe avisar que es carpeta sin EISDIR")
        // .so real -> abre y saca cadenas
        val so = File(dir, "lib/arm64-v8a/libconscrypt_jni.so").takeIf { it.isFile } ?: File("/tmp/femon/lib/arm64-v8a/libconscrypt_jni.so")
        if (so.isFile) {
            val tb2 = AgentToolbox(so.parentFile, null, false) { null }
            val out = tb2.run("binary_strings", mapOf("path" to so.name, "filter" to "conscrypt"))
            println(".so → " + out.lines().take(3).joinToString(" | "))
            assertTrue(out.contains("cadenas"))
        }
    }
}
