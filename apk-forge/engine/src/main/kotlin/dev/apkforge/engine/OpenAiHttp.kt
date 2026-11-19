package dev.apkforge.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP compartido para APIs compatibles con OpenAI (agente y asistente de código). */
internal object OpenAiHttp {
    private val gson = Gson()

    /** Fallo por el que tiene sentido probar otro servicio (saturado, caído, sin red, modelo no disponible). */
    class RetryableException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** Segundos a esperar antes de reintentar: lo que diga el servicio ("retry after N"), o 4/10/20 s. */
    fun waitSeconds(messages: Collection<String>, round: Int): Int {
        val asked = messages.mapNotNull { Regex("(?i)retry after (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }.minOrNull()
        return (asked ?: listOf(4, 10, 20)[round.coerceAtMost(2)]).coerceIn(2, 30)
    }

    /** Modelos en pausa (cuota diaria agotada, piden clave o ya no existen): se saltan sin perder tiempo. */
    private val cooled = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun key(ep: AiEndpoint) = ep.baseUrl + "|" + ep.model
    fun isCooled(ep: AiEndpoint) = (cooled[key(ep)] ?: 0L) > System.currentTimeMillis()
    fun cool(ep: AiEndpoint, millis: Long) { cooled[key(ep)] = System.currentTimeMillis() + millis }
    fun resetCooldown(ep: AiEndpoint) { cooled.remove(key(ep)) }

    /**
     * Prueba un endpoint con VARIOS modelos y devuelve el real: qué funcionó (o el error exacto de
     * cada uno). Quita la pausa antes de probar, para que un fallo viejo no tape el resultado.
     */
    fun probe(baseUrl: String, apiKey: String, models: List<String>): Pair<String?, String> {
        val errs = StringBuilder()
        for (m in models) {
            val ep = AiEndpoint(baseUrl, apiKey, m, m)
            resetCooldown(ep)
            val body = com.google.gson.JsonObject().apply {
                add("messages", gson.toJsonTree(listOf(mapOf("role" to "user", "content" to "Responde solo: OK"))))
                addProperty("max_tokens", 20)
            }
            val r = runCatching { chat(ep, body) }
            r.onSuccess { return m to "OK" }
            r.onFailure { errs.appendLine("$m: ${it.message?.take(160)}") ; resetCooldown(ep) }
        }
        return null to errs.toString().trim()
    }

    fun chat(ep: AiEndpoint, body: JsonObject): JsonObject {
        if (isCooled(ep)) throw RetryableException("${ep.label}: en pausa (cuota agotada o no disponible)")
        val payload = body.deepCopy().apply { addProperty("model", ep.model) }
        val url = URL(ep.baseUrl.trimEnd('/').let { if (it.endsWith("/openai")) it else "$it/chat/completions" })
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 30_000; readTimeout = 300_000
            setRequestProperty("Content-Type", "application/json")
            if (ep.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${ep.apiKey}")
            setRequestProperty("X-Title", "ApkForge")
        }
        try {
            conn.outputStream.use { it.write(gson.toJson(payload).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching {
                    JsonParser.parseString(text).asJsonObject.getAsJsonObject("error")?.get("message")?.asString
                }.getOrNull() ?: text.take(300)
                val low = detail.lowercase()
                when {
                    code == 429 && (low.contains("daily") || low.contains("quota") || low.contains("per day")) -> cool(ep, 60 * 60_000L)
                    code == 401 || code == 403 || low.contains("missing api key") -> cool(ep, 6 * 60 * 60_000L)
                    code == 404 || low.contains("model not found") || low.contains("does not exist") -> cool(ep, 6 * 60 * 60_000L)
                }
                val msg = when (code) {
                    401, 403 -> "${ep.label}: clave no válida o sin permiso ($code)"
                    429 -> "${ep.label}: límite de uso alcanzado ($detail)"
                    else -> "${ep.label}: error $code ($detail)"
                }
                throw RetryableException(msg)
            }
            val json = JsonParser.parseString(text).asJsonObject
            if (json.getAsJsonArray("choices")?.size() ?: 0 == 0) throw RetryableException("${ep.label}: respuesta vacía")
            return json
        } catch (e: RetryableException) {
            throw e
        } catch (e: IOException) {
            throw RetryableException("${ep.label}: sin conexión (${e.javaClass.simpleName})", e)
        } catch (e: IllegalStateException) {
            throw RetryableException("${ep.label}: respuesta no válida", e)
        } finally {
            conn.disconnect()
        }
    }
}
