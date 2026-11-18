package dev.apkforge.engine

import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Acceso a INTERNET para la IA (incluido el modelo local): buscar en la web y leer páginas.
 * Sin clave. La búsqueda usa DuckDuckGo (funciona desde el móvil); leer una URL funciona siempre.
 */
object WebTools {
    private const val UA = "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    data class Result(val title: String, val url: String, val snippet: String)

    private fun http(url: String, timeout: Int = 20_000): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; instanceFollowRedirects = true
            connectTimeout = timeout; readTimeout = timeout
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "es,en;q=0.8")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        val code = conn.responseCode
        val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
        val bytes = raw?.let { if (conn.contentEncoding == "gzip") GZIPInputStream(it) else it }?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) throw IOException("HTTP $code")
        return String(bytes, Charsets.UTF_8)
    }

    /** Busca en la web. Devuelve título, URL y fragmento de cada resultado. */
    fun search(query: String, limit: Int = 8): List<Result> {
        val q = URLEncoder.encode(query, "UTF-8")
        // 1) DuckDuckGo HTML (mejores resultados; funciona desde IP residencial / móvil)
        runCatching {
            val html = http("https://html.duckduckgo.com/html/?q=$q&kl=es-es")
            val out = parseDuckHtml(html)
            if (out.isNotEmpty()) return out.take(limit)
        }
        // 2) DuckDuckGo lite (más simple)
        runCatching {
            val html = http("https://lite.duckduckgo.com/lite/?q=$q")
            val out = parseDuckLite(html)
            if (out.isNotEmpty()) return out.take(limit)
        }
        // 3) Respuesta instantánea de DuckDuckGo (sin bloqueo por IP, limitada)
        runCatching {
            val json = JsonParser.parseString(http("https://api.duckduckgo.com/?q=$q&format=json&no_html=1&t=apkforge")).asJsonObject
            val out = ArrayList<Result>()
            json.get("AbstractText")?.asString?.takeIf { it.isNotBlank() }?.let {
                out += Result(json.get("Heading")?.asString ?: query, json.get("AbstractURL")?.asString ?: "", it)
            }
            json.getAsJsonArray("RelatedTopics")?.forEach { t ->
                val o = t.asJsonObject
                o.get("Text")?.asString?.takeIf { it.isNotBlank() }?.let {
                    out += Result(it.take(60), o.getAsJsonObject("FirstURL")?.asString ?: o.get("FirstURL")?.asString ?: "", it)
                }
            }
            if (out.isNotEmpty()) return out.take(limit)
        }
        return emptyList()
    }

    private fun parseDuckHtml(html: String): List<Result> {
        val out = ArrayList<Result>()
        val linkRx = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snipRx = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val links = linkRx.findAll(html).toList(); val snips = snipRx.findAll(html).toList()
        links.forEachIndexed { i, m ->
            out += Result(clean(m.groupValues[2]), decodeDuckUrl(m.groupValues[1]), snips.getOrNull(i)?.let { clean(it.groupValues[1]) } ?: "")
        }
        return out
    }

    private fun parseDuckLite(html: String): List<Result> {
        val out = ArrayList<Result>()
        val rx = Regex("""<a[^>]*class="result-link"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snipRx = Regex("""class="result-snippet"[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
        val links = rx.findAll(html).toList(); val snips = snipRx.findAll(html).toList()
        links.forEachIndexed { i, m ->
            out += Result(clean(m.groupValues[2]), decodeDuckUrl(m.groupValues[1]), snips.getOrNull(i)?.let { clean(it.groupValues[1]) } ?: "")
        }
        return out
    }

    private fun decodeDuckUrl(u: String): String {
        val x = if (u.startsWith("//")) "https:$u" else u
        return runCatching {
            if (x.contains("uddg=")) java.net.URLDecoder.decode(x.substringAfter("uddg=").substringBefore("&"), "UTF-8") else x
        }.getOrDefault(x)
    }

    /** Lee una página web y devuelve su texto legible (sin HTML), recortado. */
    fun fetch(url: String, maxChars: Int = 12_000): String {
        val full = if (url.startsWith("http")) url else "https://$url"
        val body = http(full)
        return if (full.endsWith(".json") || body.trimStart().startsWith("{") || body.trimStart().startsWith("["))
            body.take(maxChars)
        else htmlToText(body).take(maxChars)
    }

    fun searchFormatted(query: String, limit: Int = 8): String {
        val r = search(query, limit)
        return if (r.isEmpty()) "Sin resultados (o la búsqueda está bloqueada desde esta red; en el móvil suele funcionar)."
        else r.joinToString("\n\n") { "• ${it.title}\n  ${it.url}\n  ${it.snippet.take(200)}" }
    }

    // ───────────── HTML → texto ─────────────

    private fun htmlToText(html: String): String {
        var t = html
        t = Regex("""<(script|style|head|nav|footer|svg)[^>]*>.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).replace(t, " ")
        t = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL).replace(t, " ")
        t = Regex("""<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>""", RegexOption.IGNORE_CASE).replace(t, "\n")
        t = Regex("""<[^>]+>""").replace(t, " ")
        t = decodeEntities(t)
        return t.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n")
    }

    private fun clean(s: String) = decodeEntities(Regex("""<[^>]+>""").replace(s, "")).trim()

    private fun decodeEntities(s: String): String {
        var r = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ").replace("&apos;", "'")
        r = Regex("""&#(\d+);""").replace(r) { runCatching { it.groupValues[1].toInt().toChar().toString() }.getOrDefault(it.value) }
        r = Regex("""&#x([0-9a-fA-F]+);""").replace(r) { runCatching { it.groupValues[1].toInt(16).toChar().toString() }.getOrDefault(it.value) }
        return r
    }
}
