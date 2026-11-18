package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FingerprintTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun fingerprintFormat() {
        val fp = ApkSigning.fingerprint(SigningConfig.TestKey)
        println("SHA-256 (cabecera): ${fp.sha256Plain}")
        println("SHA-256 (:) ${fp.sha256}")
        assertTrue(fp.sha256Plain.length == 64 && fp.sha256Plain.all { it in "0123456789abcdef" })
        assertEquals(fp.sha256Plain, fp.sha256.replace(":", "").lowercase())
    }

    @Test fun fingerprintEqualsResignedApk() {
        assumeTrue(sample != null)
        val fp = ApkSigning.fingerprint(SigningConfig.TestKey)
        val signed = File(System.getProperty("java.io.tmpdir"), "fp-signed.apk")
        ApkSigning.sign(sample!!, signed, SigningConfig.TestKey)
        val fp2 = ApkSigning.fingerprintOf(signed)!!
        println("config   SHA-256: ${fp.sha256Plain}")
        println("APK real SHA-256: ${fp2.sha256Plain}")
        assertEquals(fp.sha256Plain, fp2.sha256Plain, "La huella de la config = la del APK firmado con ella")
    }
}
