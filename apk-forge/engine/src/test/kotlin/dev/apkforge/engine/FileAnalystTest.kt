package dev.apkforge.engine

import org.junit.Test
import kotlin.test.assertTrue

class FileAnalystTest {
    @Test fun textFileGivesContent() {
        val json = """{"server":"https://api.x.com","channels":[{"name":"Canal 1","url":"http://s/live.m3u8"}]}"""
        val out = FileAnalyst.analyze(FileAnalyst.Attachment("config.json", json.toByteArray()))
        println(out.take(400))
        assertTrue(out.contains("CONTENIDO") && out.contains("api.x.com") && out.contains("config.json"))
    }

    @Test fun binaryFileGivesSummary() {
        // bytes aleatorios con una URL dentro (simula un .bin)
        val rnd = java.util.Random(7)
        val b = ByteArray(2000).also { rnd.nextBytes(it) }
        val url = "https://secreto.ejemplo.com/token=abc".toByteArray()
        System.arraycopy(url, 0, b, 100, url.size)
        val out = FileAnalyst.analyze(FileAnalyst.Attachment("femon-direct.bin", b))
        println(out.take(500))
        assertTrue(out.contains("Entropía") && out.contains("hex") && out.contains("secreto.ejemplo.com"))
        assertTrue(!out.contains("CONTENIDO:"), "Un binario no debe volcar el contenido crudo como texto")
    }
}
