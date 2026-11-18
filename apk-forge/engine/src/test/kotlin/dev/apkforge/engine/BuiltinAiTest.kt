package dev.apkforge.engine

import com.sun.net.httpserver.HttpServer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertTrue

class BuiltinAiTest {
    private fun server(handler: (String) -> Pair<Int, String>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { ex ->
                val (code, body) = handler(ex.requestBody.readBytes().toString(Charsets.UTF_8))
                val b = body.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }

    @Test fun failoverToSecondService() {
        val busy = server { 429 to """{"error":{"message":"rate limited"}}""" }
        var models = mutableListOf<String>()
        val ok = server { body ->
            models += Regex("\"model\":\"([^\"]+)\"").find(body)!!.groupValues[1]
            200 to """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Respuesta del segundo servicio"}}]}"""
        }
        try {
            val events = mutableListOf<AgentEvent>()
            val eps = listOf(
                AiEndpoint("http://127.0.0.1:${busy.address.port}/v1", "", "modelo-a", "Servicio A"),
                AiEndpoint("http://127.0.0.1:${ok.address.port}/v1", "", "modelo-b", "Servicio B"),
            )
            OpenAiCompatAgent(File(System.getProperty("java.io.tmpdir")), null, eps, AgentPreset.CHAT, false, { null }, { events += it }).send("hola")
            println("Eventos: $events")
            assertTrue(events.any { it is AgentEvent.Text && it.text.contains("segundo servicio") })
            assertTrue(models == listOf("modelo-b"), "Debe mandar el modelo del servicio B")
            // Cliente de texto (Modo IA del editor) con la misma cadena
            assertTrue(OpenAiTextClient(eps).complete("s", "u").contains("segundo servicio"))
        } finally { busy.stop(0); ok.stop(0) }
    }

    /** Prueba REAL contra la IA integrada (internet, sin clave). Activa con -Dapkforge.live=true */
    @Test fun liveBuiltinAgentEditsFile() {
        assumeTrue(System.getProperty("apkforge.live") == "true")
        val proj = File(System.getProperty("java.io.tmpdir"), "live-proj").apply { deleteRecursively(); mkdirs() }
        File(proj, "res/values").mkdirs()
        File(proj, "res/values/strings.xml").writeText(
            "<resources>\n    <string name=\"app_name\">Demo</string>\n    <string name=\"hello\">Hello world</string>\n</resources>\n")
        val events = mutableListOf<AgentEvent>()
        val t0 = System.currentTimeMillis()
        OpenAiCompatAgent(proj, null, BuiltinAi.endpoints, AgentPreset.CHAT, true, { null },
            { events += it; println("  evento: ${it.toString().take(220)}") })
            .send("En res/values/strings.xml cambia el texto de app_name a 'Hola ApkForge'. Lee el archivo y usa replace_in_file.")
        val after = File(proj, "res/values/strings.xml").readText()
        println("Tiempo: ${System.currentTimeMillis() - t0} ms\nArchivo final:\n$after")
        assertTrue(events.any { it is AgentEvent.ToolCall }, "La IA integrada debe usar herramientas")
        assertTrue(after.contains("Hola ApkForge"), "La IA integrada debe haber editado el archivo")
    }

    /** Cada modelo chino gratuito nuevo, por separado, con el ciclo completo de herramientas. */
    @Test fun liveChineseModelsEachEdit() {
        assumeTrue(System.getProperty("apkforge.live") == "true")
        val results = mutableListOf<String>()
        BuiltinAi.endpoints.filter { it.baseUrl.contains("llm7") }.forEach { ep ->
            val proj = File(System.getProperty("java.io.tmpdir"), "live-${ep.model.replace(':', '_')}").apply { deleteRecursively(); mkdirs() }
            File(proj, "res/values").mkdirs()
            File(proj, "res/values/strings.xml").writeText("<resources>\n    <string name=\"app_name\">Demo</string>\n</resources>\n")
            var tools = 0
            var lastError = ""
            runCatching {
                OpenAiCompatAgent(proj, null, listOf(ep), AgentPreset.CHAT, true, { null }, {
                    if (it is AgentEvent.ToolCall) tools++
                    if (it is AgentEvent.Error) lastError = it.message.lines().last().take(120)
                })
                    .send("En res/values/strings.xml cambia el texto de app_name a 'Hola ApkForge'. Lee el archivo y usa replace_in_file.")
            }
            val ok = File(proj, "res/values/strings.xml").readText().contains("Hola ApkForge")
            results += "${if (ok) "✔" else "✖"} ${ep.label}: herramientas=$tools $lastError"
        }
        results.forEach { println("  $it") }
        // LLM7 sin clave tiene cuota diaria por IP: si se agotó, no es un fallo de la app
        val quota = results.count { it.contains("cuota") || it.contains("quota") || it.contains("pausa") }
        assumeTrue("Cuota diaria de LLM7 agotada en esta IP", quota < results.size)
        assertTrue(results.count { it.startsWith("✔") } >= 2, "Al menos 2 modelos chinos deben editar de verdad")
    }
}
