package dev.apkforge.engine

import java.io.File
import kotlin.math.ln

/**
 * Prepara para la IA cualquier ARCHIVO que el usuario sube en el chat (no solo imágenes): detecta
 * el tipo real por su contenido, y según sea texto o binario le da a la IA lo útil — el texto
 * (recortado), o un resumen con tipo, tamaño, entropía (detecta cifrado/comprimido), cadenas
 * legibles, hash y un vistazo hex. Así la IA puede analizarlo aunque sea un .so, .dex, .bin, .json…
 */
object FileAnalyst {
    data class Attachment(val name: String, val bytes: ByteArray)

    private val TEXT_EXT = setOf("txt", "json", "xml", "smali", "java", "kt", "html", "htm", "js", "css", "md", "yml", "yaml",
        "properties", "cfg", "conf", "ini", "csv", "tsv", "sql", "gradle", "pro", "toml", "log", "m3u", "m3u8", "sh", "py", "c", "cpp", "h")
    private val ZIP_EXT = setOf("zip", "apk", "jar", "aar", "aab", "xapk", "apks", "ipa", "epub", "war", "zipx")

    private fun isZip(b: ByteArray, ext: String) = ext in ZIP_EXT ||
        (b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && (b[2].toInt() == 3 || b[2].toInt() == 5 || b[2].toInt() == 7))

    /** Resumen/volcado del archivo para la IA. [maxText] limita el texto incluido. */
    fun analyze(att: Attachment, maxText: Int = 24_000): String {
        val bytes = att.bytes
        val ext = att.name.substringAfterLast('.', "").lowercase()
        val type = BinaryTools.identifyBytes(bytes)
        val textish = ext in TEXT_EXT || (looksText(bytes) && ext !in setOf("dex", "so", "bin", "png", "jpg", "jpeg", "webp", "zip", "apk"))
        return buildString {
            appendLine("ARCHIVO ADJUNTO: ${att.name}  (${human(bytes.size.toLong())}, tipo: $type)")
            if (isZip(bytes, ext)) {
                append(runCatching { zipSummary(bytes, maxText) }.getOrElse { "No se pudo abrir el ZIP: ${it.message}" })
            } else if (textish) {
                val text = String(bytes, Charsets.UTF_8)
                appendLine("CONTENIDO:")
                append("```")
                append(ext.ifEmpty { "text" }); appendLine()
                append(if (text.length <= maxText) text else text.take(maxText) + "\n… [recortado: ${text.length} caracteres en total]")
                appendLine(); append("```")
            } else {
                val ent = entropy(bytes)
                appendLine("Entropía: ${"%.2f".format(ent)} bits/byte → " + when {
                    ent > 7.6 -> "MUY ALTA (casi seguro cifrado o comprimido)"
                    ent > 6.5 -> "alta (comprimido, multimedia o código nativo)"
                    else -> "normal (estructura/texto legible)"
                })
                runCatching { CryptoTools.hashes(bytes) }.getOrNull()?.forEach { (k, v) -> appendLine("$k: $v") }
                val strs = runCatching {
                    if (ext == "dex" || type.contains("DEX")) BinaryTools.dexStringsBytes(bytes) else BinaryTools.stringsBytes(bytes)
                }.getOrDefault(emptyList())
                val interesting = strs.filter { it.length >= 5 && (it.contains("http") || it.contains("/") || it.contains(".") || it.contains("key", true) || it.contains("token", true)) }
                appendLine("Cadenas legibles: ${strs.size}" + if (interesting.isNotEmpty()) " (destacadas abajo)" else "")
                (interesting.ifEmpty { strs }).take(200).forEach { appendLine("  $it") }
                appendLine("Inicio en hex:")
                append(BinaryTools.hexDumpBytes(bytes, 0, 256))
            }
        }
    }

    fun analyzeAll(atts: List<Attachment>): String? {
        if (atts.isEmpty()) return null
        return atts.joinToString("\n\n") { analyze(it) }
    }

    /** Abre un ZIP/APK/JAR en memoria: lista sus archivos y vuelca el TEXTO de los de dentro (recortado). */
    private fun zipSummary(bytes: ByteArray, maxText: Int): String = buildString {
        val names = ArrayList<String>()
        val textFiles = ArrayList<Pair<String, ByteArray>>()
        var count = 0; var totalUncomp = 0L; var textBudget = maxText
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null && count < 3000) {
                if (!e.isDirectory) {
                    count++
                    val nm = e.name
                    val content = zis.readBytes()                 // ZipInputStream: lee solo esta entrada
                    totalUncomp += content.size
                    names += "  $nm  (${human(content.size.toLong())})"
                    val ex = nm.substringAfterLast('.', "").lowercase()
                    if (textBudget > 0 && content.isNotEmpty() && (ex in TEXT_EXT || (content.size in 1..300_000 && looksText(content)))) {
                        textFiles += nm to content; textBudget -= content.size
                    }
                }
                zis.closeEntry(); e = zis.nextEntry
            }
        }
        appendLine("ZIP/comprimido: $count archivo(s), ${human(totalUncomp)} sin comprimir.")
        appendLine("CONTENIDO (lista de archivos):")
        names.take(400).forEach { appendLine(it) }
        if (count > 400) appendLine("  … y ${count - 400} más")
        if (textFiles.isNotEmpty()) {
            appendLine(); appendLine("ARCHIVOS DE TEXTO DE DENTRO (recortados):")
            var budget = maxText
            for ((nm, c) in textFiles) {
                if (budget <= 0) { appendLine("… (resto omitido por tamaño)"); break }
                val t = String(c, Charsets.UTF_8).let { if (it.length <= budget) it else it.take(budget) + "\n… [recortado]" }
                appendLine(); appendLine("── $nm ──"); appendLine(t)
                budget -= t.length
            }
        } else appendLine("(No hay archivos de texto legibles directamente; pídeme extraer uno concreto si lo necesitas.)")
    }

    private fun looksText(b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        val sample = b.take(4096)
        val printable = sample.count { val i = it.toInt() and 0xFF; i == 9 || i == 10 || i == 13 || i in 32..126 || i >= 160 }
        return printable.toDouble() / sample.size >= 0.90
    }

    private fun entropy(b: ByteArray): Double {
        if (b.isEmpty()) return 0.0
        val counts = IntArray(256); b.forEach { counts[it.toInt() and 0xFF]++ }
        return counts.filter { it > 0 }.sumOf { val p = it.toDouble() / b.size; -p * (ln(p) / ln(2.0)) }
    }

    private fun human(n: Long) = when {
        n >= 1_048_576 -> "%.1f MB".format(n / 1_048_576.0); n >= 1024 -> "${n / 1024} KB"; else -> "$n B"
    }
}
