package dev.apkforge.engine

import com.sun.net.httpserver.HttpServer
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertTrue

/** Bucle del agente contra una API simulada: tool_use (2 herramientas en paralelo) -> end_turn. */
class AgentTest {
    @Test fun toolLoopRoundTrip() {
        val proj = File(System.getProperty("java.io.tmpdir"), "agent-proj").apply { deleteRecursively(); mkdirs() }
        File(proj, "AndroidManifest.xml").writeText("<manifest package=\"com.demo\">\n<uses-permission android:name=\"android.permission.INTERNET\"/>\n</manifest>")
        File(proj, "smali/classes/com/demo").mkdirs()
        File(proj, "smali/classes/com/demo/Api.smali").writeText(".class public Lcom/demo/Api;\n.super Ljava/lang/Object;\nconst-string v0, \"https://api.demo.com/v1/login\"\n")

        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/messages") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            bodies += body
            val resp = if (bodies.size == 1) """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":"Voy a mirar el proyecto."},
                   {"type":"tool_use","id":"tu_1","name":"search_text","input":{"query":"https://"}},
                   {"type":"tool_use","id":"tu_2","name":"read_file","input":{"path":"AndroidManifest.xml"}}],
                 "stop_reason":"tool_use","usage":{"input_tokens":100,"output_tokens":20}}
            """ else """
                {"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":"La app llama a https://api.demo.com/v1/login."}],
                 "stop_reason":"end_turn","usage":{"input_tokens":150,"output_tokens":15,"cache_read_input_tokens":90}}
            """
            val bytes = resp.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val events = mutableListOf<AgentEvent>()
            val agent = ForgeAgent(proj, null,
                AgentConfig(apiKey = "test", baseUrl = "http://127.0.0.1:${server.address.port}"),
                AgentPreset.NETWORK, java = { null }) { events += it; println("  evento: $it") }
            agent.send("¿A qué servidores se conecta?")

            assertTrue(bodies.size == 2, "Debe hacer 2 llamadas (tool_use y final)")
            val second = bodies[1]
            // Los 2 resultados van en UN mensaje de usuario, con el contenido real de las herramientas
            assertTrue(second.contains("\"tool_use_id\":\"tu_1\"") && second.contains("\"tool_use_id\":\"tu_2\""))
            assertTrue(second.contains("api.demo.com"), "El resultado de search_text debe incluir la URL")
            assertTrue(second.contains("uses-permission"), "El resultado de read_file debe incluir el manifest")
            assertTrue(bodies[0].contains("\"cache_control\"") && bodies[0].contains("\"fallbacks\""))
            assertTrue(bodies[0].contains("\"effort\":\"high\""))
            assertTrue(events.any { it is AgentEvent.Done && it.cachedTokens == 90L })
            println("Petición 1 (inicio): " + bodies[0].take(300))
        } finally {
            server.stop(0)
        }
    }

    @Test fun pathEscapeBlocked() {
        val proj = File(System.getProperty("java.io.tmpdir"), "agent-proj2").apply { mkdirs() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var n = 0; var second = ""
        server.createContext("/v1/messages") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8); n++
            if (n == 2) second = body
            val resp = if (n == 1) """{"id":"m","type":"message","role":"assistant","model":"x","content":[{"type":"tool_use","id":"t","name":"read_file","input":{"path":"../../etc/passwd"}}],"stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1}}"""
            else """{"id":"m2","type":"message","role":"assistant","model":"x","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}"""
            val b = resp.toByteArray(); ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            ForgeAgent(proj, null, AgentConfig("test", baseUrl = "http://127.0.0.1:${server.address.port}"), AgentPreset.CHAT, { null }) {}.send("lee")
            assertTrue(second.contains("\"is_error\":true") && second.contains("fuera del proyecto"), "Debe bloquear rutas fuera del proyecto")
        } finally { server.stop(0) }
    }
}
