package dev.apkforge.engine

import org.junit.Test
import kotlin.test.assertTrue

/** La ventana de contexto: continuar NO reinicia, y no reenvía millones de tokens (recorta lo viejo). */
class ContextWindowTest {
    @Test fun oldToolResultsAreTrimmedKeepingRecent() {
        // servidor falso que registra el tamaño del body enviado y responde "fin"
        val sizes = mutableListOf<Int>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0).apply {
            createContext("/chat/completions") { ex ->
                val body = ex.requestBody.readBytes(); sizes += body.size
                val resp = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}"""
                ex.sendResponseHeaders(200, resp.toByteArray().size.toLong()); ex.responseBody.use { it.write(resp.toByteArray()) }
            }
            start()
        }
        try {
            val ep = AiEndpoint("http://localhost:${server.address.port}", "", "m", "m")
            val dir = java.io.File(System.getProperty("java.io.tmpdir"), "ctxwin").apply { deleteRecursively(); mkdirs() }
            val agent = OpenAiCompatAgent(dir, null, listOf(ep), AgentPreset.CHAT, false, { null }, {})
            // inyecta historial gigante simulando resultados de herramientas enormes
            val big = "A".repeat(50_000)
            val f = OpenAiCompatAgent::class.java.getDeclaredField("messages").apply { isAccessible = true }
            val msgs = f.get(agent) as com.google.gson.JsonArray
            repeat(20) { i ->
                msgs.add(com.google.gson.JsonObject().apply { addProperty("role", "assistant"); addProperty("content", "paso $i") })
                msgs.add(com.google.gson.JsonObject().apply { addProperty("role", "tool"); addProperty("tool_call_id", "t$i"); addProperty("content", big) })
            }
            agent.send("sigue") { false }
            println("tamaño del body enviado: ${sizes.firstOrNull()} bytes (historial crudo ~${20*50_000})")
            assertTrue(sizes.isNotEmpty())
            assertTrue(sizes.first() < 400_000, "Debe recortar: el body no puede llevar el millón de caracteres del historial")
        } finally { server.stop(0) }
    }
}
