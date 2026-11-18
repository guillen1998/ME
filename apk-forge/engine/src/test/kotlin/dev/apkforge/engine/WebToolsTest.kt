package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertTrue

class WebToolsTest {
    private val live = System.getProperty("apkforge.live") == "true"

    @Test fun fetchReadsAndStripsHtml() {
        assumeTrue(live)
        val t = WebTools.fetch("https://raw.githubusercontent.com/REAndroid/APKEditor/master/README.md", 2000)
        println(t.take(200))
        assertTrue(t.contains("APKEditor", true), "Debe leer el contenido real de la URL")
    }

    @Test fun searchReturnsOrSaysBlocked() {
        assumeTrue(live)
        val out = WebTools.searchFormatted("android GridLayoutManager spanCount", 5)
        println(out.take(400))
        assertTrue(out.isNotBlank())
    }
}
