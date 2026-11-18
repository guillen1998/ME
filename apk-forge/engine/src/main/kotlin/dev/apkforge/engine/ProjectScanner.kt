package dev.apkforge.engine

import java.io.File
import java.util.zip.ZipFile

/** Categoría de hallazgo del escáner. */
enum class FindingKind { URL, IP, DOMAIN, EMAIL, SECRET, FIREBASE, S3, PATH, SQL, CRYPTO, DEEPLINK, OTHER }

data class Finding(
    val kind: FindingKind,
    val rule: String,
    val value: String,
    val source: String,   // ruta relativa + (:línea) o (dex), (so), (strings)
    val file: File,
    val line: Int,        // 0 si no aplica
)

data class ScanOptions(
    val urls: Boolean = true,
    val ips: Boolean = true,
    val emails: Boolean = true,
    val secrets: Boolean = true,
    val paths: Boolean = true,
    val sql: Boolean = true,
    val crypto: Boolean = true,
    /** Entrar en .dex con dexlib2 y en .so/binarios con extracción de cadenas. */
    val scanBinaries: Boolean = true,
    val maxPerRule: Int = 5000,
)

private class Rule(val kind: FindingKind, val name: String, pattern: String, val group: Int = 0) {
    val regex = Regex(pattern)
}

/**
 * Análisis estático tipo apkleaks/MobSF: busca URLs, IPs, dominios, correos, claves/secretos,
 * rutas, SQL y pistas de criptografía por TODO el contenido — texto, smali, xml, json, y también
 * DENTRO de .dex y librerías .so. No hace falta escribir nada: detecta solo.
 */
object ProjectScanner {

    // Patrones (inspirados en apkleaks y las reglas de MobSF). Son candidatos: requieren revisión.
    private val rules = listOf(
        Rule(FindingKind.URL, "URL http/https", """\bhttps?://[^\s"'<>()\\{}\[\]|^`]+"""),
        Rule(FindingKind.URL, "URL ws/ftp", """\b(?:wss?|ftp|ftps|file|content|jdbc):\/\/[^\s"'<>()]+"""),
        Rule(FindingKind.DEEPLINK, "Deep link / esquema", """\b[a-zA-Z][a-zA-Z0-9+.\-]{2,}://[^\s"'<>()]+"""),
        Rule(FindingKind.FIREBASE, "Firebase DB", """\bhttps?://[a-z0-9.\-]+\.firebaseio\.com[^\s"']*"""),
        Rule(FindingKind.FIREBASE, "Firebase App", """\bhttps?://[a-z0-9.\-]+\.firebaseapp\.com[^\s"']*"""),
        Rule(FindingKind.S3, "Amazon S3", """\b(?:[a-z0-9.\-]+\.s3[a-z0-9.\-]*\.amazonaws\.com|s3://[a-z0-9.\-/]+)"""),
        Rule(FindingKind.IP, "IPv4", """\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?::\d{1,5})?\b"""),
        Rule(FindingKind.EMAIL, "Email", """\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b"""),

        Rule(FindingKind.SECRET, "Clave privada PEM", """-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----"""),
        Rule(FindingKind.SECRET, "AWS Access Key", """\b((?:A3T[A-Z0-9]|AKIA|ASIA|AGPA|AIDA|AROA|ANPA|ANVA)[A-Z0-9]{16})\b"""),
        Rule(FindingKind.SECRET, "Google API Key", """\bAIza[0-9A-Za-z\-_]{35}\b"""),
        Rule(FindingKind.SECRET, "Google OAuth", """\b[0-9]+-[0-9A-Za-z_]{32}\.apps\.googleusercontent\.com\b"""),
        Rule(FindingKind.SECRET, "Firebase Cloud Messaging", """\bAAAA[A-Za-z0-9_\-]{7}:[A-Za-z0-9_\-]{140}\b"""),
        Rule(FindingKind.SECRET, "JWT", """\beyJ[A-Za-z0-9_\-]+\.eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\b"""),
        Rule(FindingKind.SECRET, "Slack Token", """\bxox[baprs]-[0-9A-Za-z\-]{10,72}\b"""),
        Rule(FindingKind.SECRET, "Slack Webhook", """https://hooks\.slack\.com/services/[A-Za-z0-9/+]+"""),
        Rule(FindingKind.SECRET, "GitHub Token", """\b(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{20,}\b"""),
        Rule(FindingKind.SECRET, "Stripe Key", """\b(?:sk|pk|rk)_(?:live|test)_[0-9A-Za-z]{10,}\b"""),
        Rule(FindingKind.SECRET, "Twilio SID", """\bAC[a-z0-9]{32}\b"""),
        Rule(FindingKind.SECRET, "SendGrid", """\bSG\.[A-Za-z0-9_\-]{22}\.[A-Za-z0-9_\-]{43}\b"""),
        Rule(FindingKind.SECRET, "Mapbox Token", """\bpk\.eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\b"""),
        Rule(FindingKind.SECRET, "Facebook Token", """\bEAACEdEose0cBA[0-9A-Za-z]+\b"""),
        Rule(FindingKind.SECRET, "Bearer/Authorization", """(?i)(?:authorization|bearer|api[_\- ]?key|secret|passwd|password|token)["'=:\s]{1,4}[A-Za-z0-9/+_\-\.]{12,}"""),

        Rule(FindingKind.PATH, "Ruta de dispositivo", """\b(?:/data/data/|/sdcard/|/storage/|/system/|/proc/|/data/local/tmp/)[^\s"'<>()]*"""),
        Rule(FindingKind.SQL, "SQL embebido", """(?i)\b(?:SELECT\s+.+\s+FROM|INSERT\s+INTO|UPDATE\s+\w+\s+SET|DELETE\s+FROM|CREATE\s+TABLE)\b[^;\n]{0,160}"""),
        Rule(FindingKind.CRYPTO, "Algoritmo/modo criptográfico", """\b(?:AES/[A-Z]+/[A-Za-z0-9]+|DES|DESede|RC4|MD5|SHA-?1|ECB|PBKDF2[A-Za-z]*|HmacSHA\d+)\b"""),
    )

    /** Escanea un APK o una carpeta (proyecto o lo que sea). */
    fun scan(
        target: File,
        options: ScanOptions = ScanOptions(),
        listener: ProgressListener = ProgressListener.NONE,
        isCancelled: () -> Boolean = { false },
        /** Se llama con cada hallazgo nuevo en cuanto aparece (resultados en vivo). */
        onFinding: ((Finding) -> Unit)? = null,
    ): List<Finding> {
        val active = rules.filter {
            when (it.kind) {
                FindingKind.URL, FindingKind.DEEPLINK, FindingKind.FIREBASE, FindingKind.S3, FindingKind.DOMAIN -> options.urls
                FindingKind.IP -> options.ips
                FindingKind.EMAIL -> options.emails
                FindingKind.SECRET -> options.secrets
                FindingKind.PATH -> options.paths
                FindingKind.SQL -> options.sql
                FindingKind.CRYPTO -> options.crypto
                FindingKind.OTHER -> true
            }
        }
        val found = LinkedHashMap<String, Finding>() // dedupe por kind+valor+fuente
        val counts = HashMap<String, Int>()

        fun emit(kind: FindingKind, rule: String, value: String, source: String, file: File, line: Int) {
            val v = value.trim().trim('"', '\'', ',', ';', ')').take(500)
            if (v.length < 4) return
            val c = counts.merge(rule, 1, Int::plus)!!
            if (c > options.maxPerRule) return
            val k = "$kind|$v|$source"
            if (!found.containsKey(k)) {
                val f = Finding(kind, rule, v, source, file, line)
                found[k] = f
                onFinding?.invoke(f)
            }
        }

        fun scanText(text: String, source: String, file: File, withLines: Boolean) {
            if (withLines) {
                text.lineSequence().forEachIndexed { i, line ->
                    if (line.length > 6) active.forEach { r ->
                        r.regex.findAll(line).forEach { m -> emit(r.kind, r.name, m.value, "$source:${i + 1}", file, i + 1) }
                    }
                }
            } else {
                active.forEach { r -> r.regex.findAll(text).forEach { m -> emit(r.kind, r.name, m.value, source, file, 0) } }
            }
        }

        when {
            target.isDirectory -> {
                val files = target.walkTopDown()
                    .onEnter { it.name != ".apkforge" && it.name != "build" && it.name != ".gradle" }
                    .filter { it.isFile }.toList()
                files.forEachIndexed { idx, f ->
                    if (isCancelled()) return found.values.toList()
                    if (idx % 200 == 0) {
                        listener.onProgress("Analizando… $idx/${files.size}  (${found.size} hallazgos)")
                        listener.onPercent((idx * 100L / files.size.coerceAtLeast(1)).toInt())
                    }
                    scanFile(f, target, options, ::scanText, ::emit)
                }
            }
            target.extension.equals("apk", true) || target.extension.equals("zip", true) ||
                target.extension.equals("jar", true) || target.extension.equals("apks", true) -> {
                scanArchive(target, options, listener, isCancelled, ::scanText, ::emit)
            }
            else -> scanFile(target, target.parentFile ?: target, options, ::scanText, ::emit)
        }
        listener.onProgress("✔ ${found.size} hallazgos")
        return found.values.sortedWith(compareBy({ it.kind.ordinal }, { it.value }))
    }

    private val TEXT_EXT = setOf("smali", "xml", "json", "txt", "java", "kt", "properties", "html", "htm",
        "js", "css", "md", "yml", "yaml", "cfg", "conf", "ini", "csv", "sql", "gradle", "pro", "toml", "plist")

    private fun scanFile(
        f: File, root: File, options: ScanOptions,
        scanText: (String, String, File, Boolean) -> Unit,
        emit: (FindingKind, String, String, String, File, Int) -> Unit,
    ) {
        val rel = runCatching { f.relativeTo(root).path }.getOrDefault(f.name)
        val ext = f.extension.lowercase()
        when {
            ext in TEXT_EXT -> runCatching { if (f.length() < 12 * 1024 * 1024) scanText(f.readText(), rel, f, true) }
            !options.scanBinaries -> {}
            ext == "dex" -> runCatching {
                BinaryTools.dexStrings(f).forEach { s ->
                    scanStringsLine(s, "$rel (dex)", f, options, emit)
                }
            }
            ext == "so" || ext == "bin" || ext == "dat" || ext == "" -> runCatching {
                BinaryTools.strings(f).forEach { s -> scanStringsLine(s, "$rel (nativo)", f, options, emit) }
            }
            f.length() < 6 * 1024 * 1024 && BinaryTools.identify(f).let { it == "Texto" } ->
                runCatching { scanText(f.readText(), rel, f, true) }
            f.length() < 20 * 1024 * 1024 && ext !in setOf("png", "jpg", "jpeg", "webp", "gif", "ttf", "otf", "mp3", "mp4", "ogg", "wav") ->
                runCatching { BinaryTools.strings(f).forEach { s -> scanStringsLine(s, "$rel (bin)", f, options, emit) } }
        }
    }

    private fun scanStringsLine(
        s: String, source: String, f: File, options: ScanOptions,
        emit: (FindingKind, String, String, String, File, Int) -> Unit,
    ) {
        if (s.length < 6) return
        rules.forEach { r ->
            if (!ruleEnabled(r.kind, options)) return@forEach
            r.regex.find(s)?.let { m -> emit(r.kind, r.name, m.value, source, f, 0) }
        }
    }

    private fun ruleEnabled(kind: FindingKind, o: ScanOptions) = when (kind) {
        FindingKind.URL, FindingKind.DEEPLINK, FindingKind.FIREBASE, FindingKind.S3, FindingKind.DOMAIN -> o.urls
        FindingKind.IP -> o.ips; FindingKind.EMAIL -> o.emails; FindingKind.SECRET -> o.secrets
        FindingKind.PATH -> o.paths; FindingKind.SQL -> o.sql; FindingKind.CRYPTO -> o.crypto; FindingKind.OTHER -> true
    }

    private fun scanArchive(
        apk: File, options: ScanOptions, listener: ProgressListener, isCancelled: () -> Boolean,
        scanText: (String, String, File, Boolean) -> Unit,
        emit: (FindingKind, String, String, String, File, Int) -> Unit,
    ) {
        val tmp = File.createTempFile("scan", "").apply { delete(); mkdirs() }
        try {
            ZipFile(apk).use { zip ->
                val entries = zip.entries().toList().filter { !it.isDirectory }
                entries.forEachIndexed { idx, e ->
                    if (isCancelled()) return
                    if (idx % 50 == 0) {
                        listener.onProgress("Analizando ${apk.name}… $idx/${entries.size}")
                        listener.onPercent((idx * 100L / entries.size.coerceAtLeast(1)).toInt())
                    }
                    val ext = e.name.substringAfterLast('.', "").lowercase()
                    val interesting = ext in TEXT_EXT || ext == "dex" || ext == "so" || ext == "bin" || ext == "arsc" ||
                        e.name == "AndroidManifest.xml" || ext == ""
                    if (!interesting || e.size > 40L * 1024 * 1024) return@forEachIndexed
                    val out = File(tmp, "e$idx." + ext.ifEmpty { "bin" })
                    zip.getInputStream(e).use { ins -> out.outputStream().use { ins.copyTo(it) } }
                    when {
                        ext in TEXT_EXT -> runCatching { scanText(out.readText(), e.name, out, true) }
                        ext == "dex" && options.scanBinaries -> BinaryTools.dexStrings(out).forEach { scanStringsLine(it, "${e.name} (dex)", out, options, emit) }
                        options.scanBinaries -> BinaryTools.strings(out).forEach { scanStringsLine(it, "${e.name}", out, options, emit) }
                    }
                }
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
