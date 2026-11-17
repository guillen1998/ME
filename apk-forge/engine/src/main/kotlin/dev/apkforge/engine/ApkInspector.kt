package dev.apkforge.engine

import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

data class SignerInfo(
    val subject: String,
    val issuer: String,
    val sha256: String,
    val sha1: String,
    val algorithm: String,
    val validUntil: String,
)

data class SignatureReport(
    val verified: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val v4: Boolean,
    val signers: List<SignerInfo>,
    val errors: List<String>,
)

data class ApkInfo(
    val file: File,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Int?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val compileSdk: Int?,
    val appLabel: String?,
    val applicationClass: String?,
    val mainActivity: String?,
    val debuggable: Boolean,
    val permissions: List<String>,
    val activities: List<String>,
    val services: List<String>,
    val receivers: List<String>,
    val providers: List<String>,
    val dexFiles: List<String>,
    val nativeAbis: Map<String, List<String>>,
    val entryCount: Int,
    val sizeBytes: Long,
    /** Tecnologías / protecciones detectadas (Flutter, Unity, empaquetadores...). */
    val detections: List<String>,
    val signature: SignatureReport?,
)

object ApkInspector {

    fun inspect(apk: File, verifySignature: Boolean = true): ApkInfo {
        if (!apk.isFile) throw EngineException("No existe el archivo: ${apk.absolutePath}")
        val entries = ZipFile(apk).use { zip -> zip.entries().toList().map { it.name } }

        val dex = entries.filter { it.matches(Regex("classes\\d*\\.dex")) }
            .sortedWith(compareBy({ it.length }, { it }))
        val abis = entries.filter { it.startsWith("lib/") && it.endsWith(".so") }
            .groupBy({ it.split('/')[1] }, { it.substringAfterLast('/') })

        var manifest: AndroidManifestBlock? = null
        val module = try {
            ApkModule.loadApkFile(apk)
        } catch (e: Exception) {
            null
        }
        try {
            manifest = module?.androidManifest
            fun names(tag: String) = manifest?.listApplicationElementsByTag(tag)
                ?.mapNotNull { AndroidManifestBlock.getAndroidNameValue(it) }.orEmpty()

            return ApkInfo(
                file = apk,
                packageName = manifest?.packageName,
                versionName = manifest?.versionName,
                versionCode = manifest?.versionCode,
                minSdk = manifest?.minSdkVersion,
                targetSdk = manifest?.targetSdkVersion,
                compileSdk = manifest?.compileSdkVersion ?: manifest?.platformBuildVersionCode,
                appLabel = runCatching { manifest?.applicationLabelString }.getOrNull(),
                applicationClass = manifest?.applicationClassName,
                mainActivity = runCatching { manifest?.mainActivityClassName }.getOrNull(),
                debuggable = manifest?.isDebuggable ?: false,
                permissions = manifest?.usesPermissions.orEmpty().sorted(),
                activities = names("activity") + names("activity-alias"),
                services = names("service"),
                receivers = names("receiver"),
                providers = names("provider"),
                dexFiles = dex,
                nativeAbis = abis,
                entryCount = entries.size,
                sizeBytes = apk.length(),
                detections = detect(entries, abis.values.flatten().toSet()),
                signature = if (verifySignature) verify(apk) else null,
            )
        } finally {
            runCatching { module?.close() }
        }
    }

    fun verify(apk: File): SignatureReport = try {
        val r = ApkVerifier.Builder(apk).build().verify()
        SignatureReport(
            verified = r.isVerified,
            v1 = r.isVerifiedUsingV1Scheme,
            v2 = r.isVerifiedUsingV2Scheme,
            v3 = r.isVerifiedUsingV3Scheme,
            v4 = r.isVerifiedUsingV4Scheme,
            signers = r.signerCertificates.map { it.toInfo() },
            errors = r.allErrors.map { it.toString() },
        )
    } catch (e: Exception) {
        SignatureReport(false, false, false, false, false, emptyList(), listOf(e.message ?: e.toString()))
    }

    private fun X509Certificate.toInfo() = SignerInfo(
        subject = subjectX500Principal.name,
        issuer = issuerX500Principal.name,
        sha256 = digest("SHA-256", encoded),
        sha1 = digest("SHA-1", encoded),
        algorithm = sigAlgName,
        validUntil = notAfter.toString(),
    )

    private fun digest(alg: String, data: ByteArray) =
        MessageDigest.getInstance(alg).digest(data).joinToString(":") { "%02X".format(it) }

    private fun detect(entries: List<String>, libs: Set<String>): List<String> {
        val out = mutableListOf<String>()
        fun lib(vararg n: String) = n.any { it in libs }
        fun entry(p: (String) -> Boolean) = entries.any(p)

        if (lib("libflutter.so")) out += "Flutter (el código Dart está en libapp.so, no en el dex)"
        if (lib("libunity.so", "libil2cpp.so")) out += if (lib("libil2cpp.so"))
            "Unity IL2CPP (lógica en libil2cpp.so + global-metadata.dat)" else "Unity Mono (lógica en assets/bin/Data/Managed/*.dll)"
        if (lib("libreactnativejni.so", "libhermes.so") || entry { it == "assets/index.android.bundle" })
            out += "React Native (JS en assets/index.android.bundle)"
        if (entry { it.startsWith("assemblies/") } || lib("libmonodroid.so")) out += "Xamarin / .NET MAUI (código en assemblies/*.dll)"
        if (entry { it.startsWith("assets/www/") }) out += "Cordova / Ionic (web en assets/www)"
        if (entry { it.startsWith("kotlin/") } || entry { it.endsWith(".kotlin_module") }) out += "Kotlin"
        if (entry { it.startsWith("META-INF/androidx.compose") }) out += "Jetpack Compose"

        val packers = mapOf(
            "libjiagu.so" to "360 Jiagu", "libjiagu_64.so" to "360 Jiagu",
            "libshell-super.2019.so" to "Tencent Legu", "libshella-2.10.so" to "Tencent Legu",
            "libsecexe.so" to "Bangcle", "libDexHelper.so" to "SecNeo/Bangcle",
            "libexec.so" to "Ijiami", "libbaiduprotect.so" to "Baidu",
            "libprotectClass.so" to "Qihoo", "libdexjni.so" to "Dexprotector",
            "libapp-protect.so" to "Promon", "libpairipcore.so" to "Google Play Integrity (PairIP)",
        )
        packers.forEach { (so, name) -> if (so in libs) out += "Protección/empaquetador: $name" }
        if (entry { it == "assets/classes.jar" || it.matches(Regex("assets/.*\\.(dex|jar)")) })
            out += "Posible dex oculto/cifrado en assets"
        return out.distinct()
    }
}
