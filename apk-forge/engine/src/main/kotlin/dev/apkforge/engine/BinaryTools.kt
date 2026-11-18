package dev.apkforge.engine

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import java.io.RandomAccessFile

/**
 * Lectura de archivos binarios que los extractores normales no abren: .so, .dex, .bin, .arsc,
 * cualquier cosa. Extrae cadenas legibles (como el comando `strings`), vuelca en hexadecimal
 * e identifica el tipo real por sus "magic bytes".
 */
object BinaryTools {

    /** Identifica el formato real del archivo por su contenido, no por la extensión. */
    fun identify(file: File): String {
        if (!file.isFile) return "—"
        val h = ByteArray(16)
        val n = file.inputStream().use { it.read(h) }
        if (n <= 0) return "vacío"
        fun starts(vararg b: Int) = n >= b.size && b.indices.all { h[it].toInt() and 0xFF == b[it] }
        fun ascii(s: String) = n >= s.length && String(h, 0, s.length, Charsets.ISO_8859_1) == s
        return when {
            starts(0x7F, 0x45, 0x4C, 0x46) -> "ELF — librería/ejecutable nativo (${elfBits(h)}, ${elfArch(file)})"
            ascii("dex\n") || starts(0x64, 0x65, 0x78, 0x0A) -> "DEX — bytecode Dalvik"
            ascii("cdex") -> "CDEX — dex comprimido (ART)"
            starts(0x50, 0x4B, 0x03, 0x04) || starts(0x50, 0x4B, 0x05, 0x06) -> "ZIP/APK/JAR"
            starts(0x02, 0x00, 0x0C, 0x00) || starts(0x03, 0x00, 0x08, 0x00) -> "ARSC — tabla de recursos de Android"
            starts(0x03, 0x00, 0x08, 0x00) -> "AXML — XML binario de Android"
            ascii("%PDF") -> "PDF"
            starts(0x89, 0x50, 0x4E, 0x47) -> "PNG"
            starts(0xFF, 0xD8, 0xFF) -> "JPEG"
            ascii("RIFF") -> "RIFF (WAV/WEBP)"
            ascii("OggS") -> "OGG"
            ascii("fLaC") -> "FLAC"
            starts(0x1F, 0x8B) -> "GZIP"
            ascii("SQLite") -> "SQLite (base de datos)"
            ascii("\u0000asm") -> "WebAssembly"
            else -> if (looksText(file)) "Texto" else "Binario desconocido"
        }
    }

    private fun elfBits(h: ByteArray) = if (h.size > 4 && h[4].toInt() == 2) "64-bit" else "32-bit"

    private fun elfArch(file: File): String = runCatching {
        RandomAccessFile(file, "r").use { f ->
            f.seek(18) // e_machine
            val lo = f.read(); val hi = f.read()
            when (lo or (hi shl 8)) {
                0xB7 -> "arm64-v8a"; 0x28 -> "armeabi-v7a"; 0x3E -> "x86_64"; 0x03 -> "x86"
                0xF3 -> "riscv64"; else -> "arch?"
            }
        }
    }.getOrDefault("arch?")

    private fun looksText(file: File): Boolean = runCatching {
        val buf = ByteArray(2048)
        val n = file.inputStream().use { it.read(buf) }
        n <= 0 || (0 until n).count { val c = buf[it].toInt() and 0xFF; c == 0 || (c < 9) } == 0
    }.getOrDefault(false)

    /**
     * Extrae todas las cadenas imprimibles de un binario (ASCII y UTF-16LE),
     * como `strings -a`. [minLen] es el largo mínimo.
     */
    fun strings(file: File, minLen: Int = 4, limit: Int = 200_000): List<String> {
        val out = ArrayList<String>(4096)
        val bytes = if (file.length() <= 64L * 1024 * 1024) file.readBytes()
        else file.inputStream().use { it.readNBytes(64 * 1024 * 1024) }

        // ASCII
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) sb.append(c.toChar())
            else { if (sb.length >= minLen) out += sb.toString(); sb.setLength(0) }
            if (out.size >= limit) break
        }
        if (sb.length >= minLen) out += sb.toString()

        // UTF-16LE (típico en recursos de .NET / Unity): letra, 0x00, letra, 0x00…
        val sb2 = StringBuilder()
        var i = 0
        while (i + 1 < bytes.size && out.size < limit) {
            val c = bytes[i].toInt() and 0xFF
            val z = bytes[i + 1].toInt() and 0xFF
            if (c in 0x20..0x7E && z == 0) { sb2.append(c.toChar()); i += 2 }
            else { if (sb2.length >= minLen) out += sb2.toString(); sb2.setLength(0); i++ }
        }
        if (sb2.length >= minLen) out += sb2.toString()
        return out
    }

    /** Todas las cadenas de un .dex (constantes, nombres de métodos, clases…). */
    fun dexStrings(file: File): List<String> = runCatching {
        val dex = DexBackedDexFile(Opcodes.getDefault(), file.readBytes())
        dex.stringReferences.map { it.string }
    }.getOrElse { strings(file) }

    /** Vuelco hexadecimal clásico: offset | 16 bytes hex | ASCII. */
    fun hexDump(file: File, offset: Long = 0, length: Int = 64 * 1024): String {
        val sb = StringBuilder()
        RandomAccessFile(file, "r").use { f ->
            f.seek(offset)
            val buf = ByteArray(minOf(length.toLong(), f.length() - offset).toInt().coerceAtLeast(0))
            f.readFully(buf)
            var i = 0
            while (i < buf.size) {
                sb.append("%08X  ".format(offset + i))
                val row = minOf(16, buf.size - i)
                for (j in 0 until 16) {
                    if (j < row) sb.append("%02X ".format(buf[i + j])) else sb.append("   ")
                    if (j == 7) sb.append(' ')
                }
                sb.append(" |")
                for (j in 0 until row) {
                    val c = buf[i + j].toInt() and 0xFF
                    sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
                }
                sb.append("|\n")
                i += 16
            }
        }
        return sb.toString()
    }

    /** Símbolos exportados de una librería .so (nombres de funciones en la tabla .dynstr). */
    fun elfExports(file: File, limit: Int = 4000): List<String> = runCatching {
        // Heurística simple y robusta: en .dynstr los símbolos son cadenas ASCII; filtramos las
        // que parecen nombres de función/símbolo (C/C++/JNI).
        strings(file, minLen = 3)
            .filter { it.length in 3..200 && it.all { c -> c == '_' || c == '.' || c == '$' || c.isLetterOrDigit() } }
            .filter { it.startsWith("Java_") || it.startsWith("_Z") || it.startsWith("JNI_") ||
                it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{2,}")) }
            .distinct().take(limit)
    }.getOrDefault(emptyList())
}
