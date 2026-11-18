package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/** Reproduce el fallo del móvil ("…SecureDBFHolder") con un parser XML estilo Android. */
class AndroidCompatTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun jadxLoadsWithAndroidXmlParser() {
        assumeTrue(sample != null)
        val prop = "javax.xml.parsers.DocumentBuilderFactory"
        val old = System.getProperty(prop)
        System.setProperty(prop, "dev.apkforge.engine.fake.AndroidLikeDbf")
        try {
            JavaDecompiler.open(sample!!).use { j ->
                println("Clases con parser estilo Android: ${j.classes.size}")
                val code = j.javaCode(j.classes.first { it.packageName.isNotEmpty() }.fullName)
                assertTrue(code.isNotBlank())
            }
        } finally {
            if (old == null) System.clearProperty(prop) else System.setProperty(prop, old)
        }
    }
}
