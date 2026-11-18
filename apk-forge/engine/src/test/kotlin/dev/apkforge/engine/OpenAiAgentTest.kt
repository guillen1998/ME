package dev.apkforge.engine

import com.sun.net.httpserver.HttpServer
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertTrue

class OpenAiAgentTest {
    @Test fun toolLoopOpenAiFormat() {
        val proj = File(System.getProperty("java.io.tmpdir"), "oai-proj").apply { deleteRecursively(); mkdirs() }
        File(proj, "assets").mkdirs()
        File(proj, "assets/config.bin").writeBytes(ByteArray(4096) { (it * 131 % 251).toByte() })
        val bodies = mutableListOf<String>()
        val auth = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            bodies += ex.requestBody.readBytes().toString(Charsets.UTF_8)
            auth += ex.requestHeaders.getFirst("Authorization")
            val resp = if (bodies.size == 1) """
              {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"Analizo el archivo.",
                "tool_calls":[{"id":"call_1","type":"function","function":{"name":"analyze_file","arguments":"{\"path\":\"assets/config.bin\"}"}}]}}],
               "usage":{"prompt_tokens":50,"completion_tokens":10}}
            """ else """
              {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Es un binario de alta entropía."}}],
               "usage":{"prompt_tokens":80,"completion_tokens":8}}
            """
            val b = resp.trimIndent().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            val events = mutableListOf<AgentEvent>()
            val agent: AiAgent = OpenAiCompatAgent(proj, null, "http://127.0.0.1:${server.address.port}/v1", "sk-or-test",
                "openrouter/free", AgentPreset.FILE_ANALYST, false, { null }, { events += it; println("  evento: $it") })
            agent.send("¿Qué es config.bin?")
            assertTrue(bodies.size == 2)
            assertTrue(auth[0] == "Bearer sk-or-test")
            assertTrue(bodies[0].contains("\"tools\"") && bodies[0].contains("analyze_file") && bodies[0].contains("\"role\":\"system\""))
            // El 2º envío debe llevar el tool_call del asistente y la respuesta role=tool con el análisis real
            assertTrue(bodies[1].contains("\"tool_call_id\":\"call_1\"") && bodies[1].contains("\"role\":\"tool\""))
            assertTrue(bodies[1].contains("Entrop"), "El resultado de analyze_file debe incluir la entropía")
            assertTrue(events.any { it is AgentEvent.Text && it.text.contains("entropía") })
            assertTrue(events.any { it is AgentEvent.Done && it.inputTokens == 130L })
        } finally { server.stop(0) }
    }

    @Test fun clearErrorWhenServerDown() {
        val events = mutableListOf<AgentEvent>()
        OpenAiCompatAgent(File(System.getProperty("java.io.tmpdir")), null, "http://127.0.0.1:1/v1", "", "x",
            AgentPreset.CHAT, false, { null }, { events += it }).send("hola")
        val err = events.filterIsInstance<AgentEvent.Error>().firstOrNull()?.message.orEmpty()
        println("Mensaje de error: $err")
        assertTrue(err.contains("sin conexión"))
    }
}
