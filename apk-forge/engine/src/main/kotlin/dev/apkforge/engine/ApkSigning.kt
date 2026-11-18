package dev.apkforge.engine

import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

sealed class SigningConfig {
    /** Clave de prueba incluida en la app (para probar mods; no publicar con ella). */
    data object TestKey : SigningConfig()

    /** Keystore propio: PKCS12 (.p12/.pfx), BKS, o JKS (JKS solo en PC). */
    data class Keystore(
        val file: File,
        val storePassword: String,
        val alias: String? = null,
        val keyPassword: String? = null,
        val type: String? = null,
    ) : SigningConfig()

    /** Par clave privada PKCS#8 (.pk8) + certificado X.509 (.pem/.der), estilo AOSP. */
    data class Pk8(val key: File, val cert: File) : SigningConfig()
}

data class SigningSchemes(
    val v1: Boolean = true,
    val v2: Boolean = true,
    val v3: Boolean = true,
)

object ApkSigning {

    fun sign(
        input: File,
        output: File,
        config: SigningConfig = SigningConfig.TestKey,
        listener: ProgressListener = ProgressListener.NONE,
        schemes: SigningSchemes = SigningSchemes(),
    ): File {
        val (key, chain) = load(config)
        listener.onProgress("Firmando (v1=${schemes.v1}, v2=${schemes.v2}, v3=${schemes.v3}) y alineando…")
        val tmp = if (input.canonicalPath == output.canonicalPath) File(output.path + ".signing.tmp") else output
        val signer = ApkSigner.SignerConfig.Builder("CERT", key, chain).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(tmp)
            .setV1SigningEnabled(schemes.v1)
            .setV2SigningEnabled(schemes.v2)
            .setV3SigningEnabled(schemes.v3)
            .setAlignmentPreserved(false) // re-alinea: entradas sin comprimir a 4 bytes
            .setLibraryPageAlignmentBytes(16 * 1024) // .so a 16 KB (compatible con 4 KB)
            .setDebuggableApkPermitted(true)
            .setOtherSignersSignaturesPreserved(false)
            .setCreatedBy("ApkForge")
            .build()
            .sign()
        if (tmp != output) {
            output.delete()
            if (!tmp.renameTo(output)) {
                tmp.copyTo(output, overwrite = true); tmp.delete()
            }
        }
        listener.onPercent(100)
        listener.onProgress("✔ Firmado: ${output.name}")
        return output
    }

    /** Huellas del certificado de firma: lo que apps/servidores comparan (p. ej. la cabecera X-App-Signature-SHA256). */
    data class CertInfo(val sha256: String, val sha256Plain: String, val sha1: String, val md5: String, val subject: String, val validUntil: String)

    private fun info(cert: X509Certificate): CertInfo {
        fun digest(alg: String): String {
            val d = java.security.MessageDigest.getInstance(alg).digest(cert.encoded)
            return d.joinToString(":") { "%02X".format(it) }
        }
        val sha256 = digest("SHA-256")
        return CertInfo(sha256, sha256.replace(":", "").lowercase(), digest("SHA-1"), digest("MD5"),
            cert.subjectX500Principal.name, cert.notAfter.toString())
    }

    /** Huella de la firma con la que ApkForge exportará (keystore propio o clave de prueba). */
    fun fingerprint(config: SigningConfig): CertInfo = info(load(config).second.first())

    /** Huella de la firma de un APK YA firmado (para comparar el original con el modificado). */
    fun fingerprintOf(apk: File): CertInfo? = runCatching {
        val result = com.android.apksig.ApkVerifier.Builder(apk).build().verify()
        val cert = result.signerCertificates.firstOrNull() ?: return null
        info(cert)
    }.getOrNull()

    private fun load(config: SigningConfig): Pair<PrivateKey, List<X509Certificate>> = when (config) {
        is SigningConfig.TestKey -> {
            fun res(n: String) = ApkSigning::class.java.getResourceAsStream("/dev/apkforge/engine/$n")
                ?.use { it.readBytes() } ?: throw EngineException("Falta la clave de prueba ($n)")
            pk8(res("testkey.pk8")) to listOf(cert(res("testkey.x509.der")))
        }

        is SigningConfig.Pk8 -> pk8(config.key.readBytes()) to listOf(cert(config.cert.readBytes()))

        is SigningConfig.Keystore -> {
            val types = listOfNotNull(config.type) + listOf("PKCS12", "BKS", "JKS")
            var lastError: Exception? = null
            var ks: KeyStore? = null
            for (t in types.distinct()) {
                try {
                    ks = KeyStore.getInstance(t).apply {
                        config.file.inputStream().use { load(it, config.storePassword.toCharArray()) }
                    }
                    break
                } catch (e: Exception) {
                    lastError = e
                }
            }
            ks ?: throw EngineException("No se pudo abrir el keystore (¿contraseña o formato?)", lastError)
            val alias = config.alias ?: ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: throw EngineException("El keystore no tiene claves privadas")
            val pass = (config.keyPassword ?: config.storePassword).toCharArray()
            val key = ks.getKey(alias, pass) as? PrivateKey
                ?: throw EngineException("No se encontró la clave '$alias'")
            val chain = ks.getCertificateChain(alias)?.map { it as X509Certificate }
                ?: throw EngineException("La clave '$alias' no tiene certificado")
            key to chain
        }
    }

    private fun pk8(bytes: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(bytes)
        for (alg in listOf("RSA", "EC", "DSA")) {
            runCatching { return KeyFactory.getInstance(alg).generatePrivate(spec) }
        }
        throw EngineException("Clave privada PKCS#8 no válida")
    }

    private fun cert(bytes: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
}
