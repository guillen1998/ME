package dev.apkforge.engine

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Descifrado/decodificación automático, como haría una persona a mano pero sin tocar nada:
 * AES (CBC/ECB/GCM/CTR), DES/3DES, RC4, XOR, Base64, hex, URL, gzip/zlib/deflate y hashes.
 *
 * Pensado para que el agente de IA lea la clave/IV del smali y descifre assets o cadenas de la app
 * sin intervención del usuario (muchos extractores ni siquiera lo intentan).
 */
object CryptoTools {

    data class DecodeResult(val label: String, val bytes: ByteArray) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
        val looksText: Boolean get() = bytes.isNotEmpty() && bytes.count { val c = it.toInt() and 0xFF; c == 0 || (c < 9 && c != 0) } == 0
        val preview: String get() = if (looksText) text.take(4000) else BinaryTools.run { "" } .let {
            bytes.take(64).joinToString(" ") { b -> "%02X".format(b) } + (if (bytes.size > 64) "…" else "")
        }
    }

    // ---------- Entradas flexibles ----------

    /**
     * Interpreta una clave/IV. [encoding] = auto|raw|utf8|hex|base64. En "auto", si hay longitudes
     * válidas (p. ej. 16/24/32 para una clave AES) se prefiere la interpretación que encaje, para
     * evitar la ambigüedad de un texto que también es hex válido (p. ej. "0123456789abcdef").
     */
    fun material(input: String, encoding: String = "auto", valid: Set<Int> = emptySet()): ByteArray {
        val s = input.trim()
        return when (encoding.lowercase()) {
            "raw", "utf8", "utf-8", "text" -> s.toByteArray(Charsets.UTF_8)
            "hex" -> hex(s) ?: throw EngineException("Hex no válido: $s")
            "base64", "b64" -> base64(s) ?: throw EngineException("Base64 no válido")
            else -> {
                val raw = s.toByteArray(Charsets.UTF_8)
                if (valid.isNotEmpty()) {
                    if (raw.size in valid) return raw
                    hex(s)?.let { if (it.size in valid) return it }
                    base64(s)?.let { if (it.size in valid) return it }
                    return raw
                }
                hex(s)?.let { return it }
                base64(s)?.let { if (it.isNotEmpty()) return it }
                raw
            }
        }
    }

    fun hex(s: String): ByteArray? {
        val clean = s.trim().removePrefix("0x").replace(Regex("[\\s:,]"), "")
        if (clean.length < 2 || clean.length % 2 != 0 || !clean.matches(Regex("[0-9a-fA-F]+"))) return null
        return ByteArray(clean.length / 2) { ((clean[it * 2].digitToInt(16) shl 4) or clean[it * 2 + 1].digitToInt(16)).toByte() }
    }

    fun base64(s: String): ByteArray? = runCatching {
        val clean = s.trim().replace("\n", "").replace("\r", "")
        if (clean.length < 4) return null
        if (clean.contains('-') || clean.contains('_')) Base64.getUrlDecoder().decode(clean)
        else Base64.getDecoder().decode(clean)
    }.getOrNull()

    /** Deriva una clave AES del tamaño pedido si la clave dada no mide 16/24/32 (MD5/SHA, muy común). */
    private fun fitAesKey(key: ByteArray, bits: Int): ByteArray {
        val need = bits / 8
        if (key.size == need) return key
        if (key.size == 16 || key.size == 24 || key.size == 32) return key
        return when {
            need == 16 -> MessageDigest.getInstance("MD5").digest(key)
            else -> MessageDigest.getInstance("SHA-256").digest(key).copyOf(need)
        }
    }

    // ---------- Cifrados ----------

    fun aes(
        data: ByteArray, key: ByteArray, iv: ByteArray? = null,
        mode: String = "CBC", padding: String = "PKCS5Padding", decrypt: Boolean = true,
    ): ByteArray {
        val k = if (key.size in intArrayOf(16, 24, 32)) key else fitAesKey(key, 128)
        val transform = "AES/$mode/${if (mode == "GCM" || mode == "CTR") "NoPadding" else padding}"
        val cipher = Cipher.getInstance(transform)
        val op = if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE
        val spec = SecretKeySpec(k, "AES")
        when (mode) {
            "ECB" -> cipher.init(op, spec)
            "GCM" -> cipher.init(op, spec, GCMParameterSpec(128, iv ?: ByteArray(12)))
            else -> cipher.init(op, spec, IvParameterSpec(iv ?: ByteArray(16)))
        }
        return cipher.doFinal(data)
    }

    fun des(data: ByteArray, key: ByteArray, iv: ByteArray? = null, triple: Boolean = false, mode: String = "CBC", decrypt: Boolean = true): ByteArray {
        val alg = if (triple) "DESede" else "DES"
        val cipher = Cipher.getInstance("$alg/$mode/PKCS5Padding")
        val op = if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE
        val spec = SecretKeySpec(key, alg)
        if (mode == "ECB") cipher.init(op, spec) else cipher.init(op, spec, IvParameterSpec(iv ?: ByteArray(8)))
        return cipher.doFinal(data)
    }

    fun rc4(data: ByteArray, key: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) { j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF; s[i] = s[j].also { s[j] = s[i] } }
        val out = ByteArray(data.size); var a = 0; var b = 0
        for (i in data.indices) {
            a = (a + 1) and 0xFF; b = (b + s[a]) and 0xFF
            s[a] = s[b].also { s[b] = s[a] }
            out[i] = (data[i].toInt() xor s[(s[a] + s[b]) and 0xFF]).toByte()
        }
        return out
    }

    fun xor(data: ByteArray, key: ByteArray): ByteArray =
        ByteArray(data.size) { (data[it].toInt() xor key[it % key.size].toInt()).toByte() }

    // ---------- Compresión ----------

    fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }

    fun inflate(data: ByteArray, raw: Boolean = false): ByteArray {
        val inf = Inflater(raw)
        inf.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && inf.needsInput()) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    fun hashes(data: ByteArray): Map<String, String> = listOf("MD5", "SHA-1", "SHA-256").associateWith { alg ->
        MessageDigest.getInstance(alg).digest(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * Descifrado "una llamada": [algorithm] = aes|aes-ecb|aes-gcm|aes-ctr|des|3des|rc4|xor|base64|hex|gzip|zlib|deflate.
     * [input] es el dato (texto base64/hex, o se usa [dataBytes]). Clave e IV se interpretan solos.
     */
    fun decode(
        algorithm: String,
        input: String? = null,
        dataBytes: ByteArray? = null,
        key: String? = null,
        iv: String? = null,
        keyEncoding: String = "auto",
        ivEncoding: String = "auto",
    ): DecodeResult {
        val data = dataBytes ?: input?.let { base64(it) ?: hex(it) ?: it.toByteArray() } ?: ByteArray(0)
        val k = key?.let { material(it, keyEncoding, valid = setOf(16, 24, 32)) } ?: ByteArray(0)
        val ivValid = if (algorithm.lowercase().contains("gcm")) setOf(12, 16) else setOf(8, 16)
        val ivb = iv?.let { material(it, ivEncoding, valid = ivValid) }
        val out = when (algorithm.lowercase().replace("_", "-")) {
            "base64" -> base64(input ?: "") ?: throw EngineException("Base64 no válido")
            "hex" -> hex(input ?: "") ?: throw EngineException("Hex no válido")
            "gzip", "gz" -> gunzip(data)
            "zlib" -> inflate(data, raw = false)
            "deflate", "inflate" -> inflate(data, raw = true)
            "xor" -> xor(data, k)
            "rc4" -> rc4(data, k)
            "des" -> des(data, k, ivb, triple = false, mode = if (ivb == null) "ECB" else "CBC")
            "3des", "desede", "tripledes" -> des(data, k, ivb, triple = true, mode = if (ivb == null) "ECB" else "CBC")
            "aes", "aes-cbc" -> aes(data, k, ivb, "CBC")
            "aes-ecb" -> aes(data, k, null, "ECB")
            "aes-gcm" -> aes(data, k, ivb, "GCM")
            "aes-ctr" -> aes(data, k, ivb, "CTR")
            else -> throw EngineException("Algoritmo no soportado: $algorithm")
        }
        return DecodeResult(algorithm, out)
    }

    /**
     * Cuando no se sabe el modo exacto: prueba combinaciones habituales de AES (CBC/ECB/GCM, IV=0 o
     * los primeros 16 bytes) y devuelve las que dan texto o datos reconocibles (dex/zip/JSON/XML).
     */
    fun autoAes(data: ByteArray, key: ByteArray, iv: ByteArray? = null): List<DecodeResult> {
        val out = mutableListOf<DecodeResult>()
        val tries = buildList {
            add(Triple("CBC", iv ?: ByteArray(16), "PKCS5Padding"))
            add(Triple("ECB", ByteArray(0), "PKCS5Padding"))
            if (data.size > 16) add(Triple("CBC", data.copyOf(16), "PKCS5Padding")) // IV al principio
            add(Triple("GCM", iv ?: data.copyOf(12.coerceAtMost(data.size)), "NoPadding"))
            add(Triple("CTR", iv ?: ByteArray(16), "NoPadding"))
        }
        for ((mode, ivTry, pad) in tries) {
            runCatching {
                val payload = if (mode == "CBC" && iv == null && data.size > 16 && ivTry.contentEquals(data.copyOf(16)))
                    data.copyOfRange(16, data.size) else data
                val r = aes(payload, key, ivTry, mode, pad)
                if (plausible(r)) out += DecodeResult("AES/$mode", r)
            }
        }
        return out.distinctBy { it.bytes.toList() }
    }

    /** ¿El resultado parece real? (texto imprimible o cabecera de formato conocido). */
    fun plausible(b: ByteArray): Boolean {
        if (b.size < 2) return false
        fun magic(vararg x: Int) = b.size >= x.size && x.indices.all { b[it].toInt() and 0xFF == x[it] }
        if (magic(0x50, 0x4B, 0x03, 0x04)) return true          // zip/apk/jar
        if (magic(0x64, 0x65, 0x78, 0x0A)) return true          // dex
        if (magic(0x1F, 0x8B)) return true                      // gzip
        if (magic(0x7F, 0x45, 0x4C, 0x46)) return true          // elf
        val printable = b.count { val c = it.toInt() and 0xFF; c in 0x20..0x7E || c == 0x09 || c == 0x0A || c == 0x0D }
        return printable.toDouble() / b.size > 0.90
    }
}
