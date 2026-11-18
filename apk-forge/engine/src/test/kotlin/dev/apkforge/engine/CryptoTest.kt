package dev.apkforge.engine

import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class CryptoTest {
    @Test fun aesCbcRoundTrip() {
        val key = "0123456789abcdef".toByteArray()
        val iv = "abcdef9876543210".toByteArray()
        val secret = "https://api.secreta.com/v2/token?k=XYZ"
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val enc = c.doFinal(secret.toByteArray())
        val b64 = Base64.getEncoder().encodeToString(enc)

        // Como lo haría el agente: clave/iv en texto, dato en base64
        val r = CryptoTools.decode("aes", input = b64, key = "0123456789abcdef", iv = "abcdef9876543210")
        assertEquals(secret, r.text)

        // Clave en hex
        val keyHex = key.joinToString("") { "%02x".format(it) }
        assertEquals(secret, CryptoTools.decode("aes", input = b64, key = keyHex, iv = iv.joinToString("") { "%02x".format(it) }, keyEncoding = "hex", ivEncoding = "hex").text)

        // auto_aes sin saber el modo
        val auto = CryptoTools.autoAes(enc, key, iv)
        assertTrue(auto.any { it.text == secret }, "autoAes debe encontrar el modo CBC")
        println("autoAes encontró: ${auto.map { it.label }}")
    }

    @Test fun xorAndBase64AndGzip() {
        assertEquals("hola mundo", CryptoTools.decode("base64", input = Base64.getEncoder().encodeToString("hola mundo".toByteArray())).text)
        val x = CryptoTools.xor("texto".toByteArray(), byteArrayOf(0x42))
        assertEquals("texto", CryptoTools.xor(x, byteArrayOf(0x42)).toString(Charsets.UTF_8))
        val gz = java.io.ByteArrayOutputStream().also { bo -> java.util.zip.GZIPOutputStream(bo).use { it.write("comprimido".toByteArray()) } }.toByteArray()
        assertEquals("comprimido", CryptoTools.decode("gzip", dataBytes = gz).text)
    }

    @Test fun keyDerivationForOddLength() {
        // Clave de longitud rara -> se deriva por hash (comportamiento habitual en apps)
        val key = "mi_clave_secreta".toByteArray() // 16 bytes justo, válida
        val r = CryptoTools.aes("dato de prueba 12345".toByteArray(), key, ByteArray(16), "CBC", decrypt = false)
        assertEquals("dato de prueba 12345", CryptoTools.aes(r, key, ByteArray(16), "CBC").toString(Charsets.UTF_8))
    }
}
