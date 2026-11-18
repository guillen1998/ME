package dev.apkforge.engine

import java.io.File

/**
 * Detecta las PROTECCIONES CONTRA MODIFICACIÓN de una app. Es la causa nº 1 de que un mod "deje de
 * funcionar" aunque el cambio sea cosmético: al recompilar hay que RE-FIRMAR con otra clave, y
 * entonces una comprobación (en el móvil o en el servidor) ve que la firma cambió y bloquea algo
 * —típicamente "no se pudo cargar la lista de canales", el login o directamente cierra la app.
 *
 * La app avisa de esto para que ni tú ni la IA pierdan tiempo editando la pantalla equivocada. NO
 * rompe la protección: solo explica dónde está y por qué tu cambio la disparó.
 */
object IntegrityScanner {

    enum class Kind { SIGNATURE_LOCAL, SIGNATURE_SERVER, INSTALLER, ROOT, EMULATOR, DEBUGGER, REPACKAGE, PLAY_INTEGRITY, NATIVE }

    data class Finding(val kind: Kind, val file: String, val line: Int, val evidence: String, val detail: String)
    data class Report(val findings: List<Finding>) {
        val blocksAfterResign get() = findings.any { it.kind in setOf(Kind.SIGNATURE_LOCAL, Kind.SIGNATURE_SERVER, Kind.PLAY_INTEGRITY) }
        fun explain(): String = buildString {
            if (findings.isEmpty()) { append("No encontré comprobaciones de integridad. Si tu mod falla, el motivo es otro (JSON/modelo roto, recurso, etc.)."); return@buildString }
            appendLine("⚠ PROTECCIÓN CONTRA MODIFICACIÓN DETECTADA — por esto tu mod deja de funcionar al re-firmar:")
            findings.groupBy { it.kind }.forEach { (k, list) ->
                appendLine(); appendLine("• ${title(k)} (${list.size})")
                appendLine("  ${advice(k)}")
                list.take(4).forEach { appendLine("    ${it.file}:${it.line} — ${it.evidence}") }
            }
            if (blocksAfterResign) {
                appendLine(); appendLine("CONCLUSIÓN: el problema NO está en la pantalla ni en tu cambio; está en la comprobación de firma.")
                appendLine("Editar el diseño o el login no lo arregla. Lo correcto es avisar al usuario de esto.")
            }
        }
        private fun title(k: Kind) = when (k) {
            Kind.SIGNATURE_LOCAL -> "Comprobación de firma LOCAL (la app compara su propia firma)"
            Kind.SIGNATURE_SERVER -> "Firma enviada al SERVIDOR (el servidor valida la firma de la app)"
            Kind.INSTALLER -> "Comprobación de quién instaló la app (Play Store)"
            Kind.ROOT -> "Detección de root"
            Kind.EMULATOR -> "Detección de emulador"
            Kind.DEBUGGER -> "Detección de depurador / app depurable"
            Kind.REPACKAGE -> "Comprobación de reempaquetado (nombre de paquete / CRC)"
            Kind.PLAY_INTEGRITY -> "Play Integrity / SafetyNet (atestación de Google)"
            Kind.NATIVE -> "Posible comprobación en librería nativa (.so)"
        }
        private fun advice(k: Kind) = when (k) {
            Kind.SIGNATURE_LOCAL -> "La app lee su certificado y lo compara con un valor fijo; al re-firmar deja de coincidir y bloquea. (Causa el cierre o un error local.)"
            Kind.SIGNATURE_SERVER -> "La app manda el hash de su firma al servidor en cada petición; con otra firma, el servidor RECHAZA la lista/el login. No se arregla en el móvil: la decisión es del servidor."
            Kind.INSTALLER -> "Comprueba que la instaló Google Play; un APK modificado no lo cumple."
            Kind.ROOT -> "Si detecta root puede negarse a funcionar."
            Kind.EMULATOR -> "Si detecta emulador puede negarse a funcionar."
            Kind.DEBUGGER -> "Si detecta depuración o que el APK es depurable, puede cerrarse."
            Kind.REPACKAGE -> "Comprueba el nombre del paquete o un CRC propio; cambiarlos la dispara."
            Kind.PLAY_INTEGRITY -> "Pide a Google un veredicto firmado de que la app es la original y no está modificada. Un mod re-firmado no pasa."
            Kind.NATIVE -> "Parte de la comprobación puede estar en una librería .so; revísala con los strings del binario."
        }
    }

    private data class Rule(val kind: Kind, val needle: String, val regex: Boolean = false, val evidence: String)

    private val RULES = listOf(
        // Firma
        Rule(Kind.SIGNATURE_LOCAL, "getApkContentsSigners", evidence = "lee las firmas del APK"),
        Rule(Kind.SIGNATURE_LOCAL, "getSigningCertificateHistory", evidence = "historial de certificados de firma"),
        Rule(Kind.SIGNATURE_LOCAL, "GET_SIGNING_CERTIFICATES", evidence = "pide los certificados de firma"),
        Rule(Kind.SIGNATURE_LOCAL, "->getSignatures()", evidence = "lee las firmas (PackageInfo.signatures)"),
        Rule(Kind.SIGNATURE_LOCAL, "Landroid/content/pm/Signature;->toByteArray", evidence = "convierte la firma a bytes (para compararla/hashearla)"),
        Rule(Kind.SIGNATURE_LOCAL, "Landroid/content/pm/SigningInfo;", evidence = "usa SigningInfo (info de firma)"),
        // Enviada al servidor: cabeceras/parámetros con la firma
        Rule(Kind.SIGNATURE_SERVER, """"[Xx]-?[A-Za-z]*-?[Ss]ign(ature)?(-?SHA\d+)?"""", regex = true, evidence = "cabecera con la firma de la app"),
        Rule(Kind.SIGNATURE_SERVER, """"(sig|signature|apk_?sig|cert_?hash|fingerprint)"""", regex = true, evidence = "parámetro con la firma/fingerprint"),
        // Play Integrity / SafetyNet
        Rule(Kind.PLAY_INTEGRITY, "IntegrityManager", evidence = "Play Integrity API"),
        Rule(Kind.PLAY_INTEGRITY, "requestIntegrityToken", evidence = "pide token de Play Integrity"),
        Rule(Kind.PLAY_INTEGRITY, "com/google/android/play/core/integrity", evidence = "librería Play Integrity"),
        Rule(Kind.PLAY_INTEGRITY, "SafetyNet", evidence = "SafetyNet Attestation"),
        Rule(Kind.PLAY_INTEGRITY, "attest", evidence = "atestación"),
        // Instalador
        Rule(Kind.INSTALLER, "getInstallerPackageName", evidence = "mira quién instaló la app"),
        Rule(Kind.INSTALLER, "getInstallSourceInfo", evidence = "origen de la instalación"),
        Rule(Kind.INSTALLER, "com.android.vending", evidence = "comprueba que viene de Google Play"),
        // Root
        Rule(Kind.ROOT, "/system/bin/su", evidence = "busca el binario su (root)"),
        Rule(Kind.ROOT, "/system/xbin/su", evidence = "busca su (root)"),
        Rule(Kind.ROOT, "RootBeer", evidence = "librería RootBeer (detección de root)"),
        Rule(Kind.ROOT, "eu/chainfire/libsuperuser", evidence = "detección de superusuario"),
        Rule(Kind.ROOT, "test-keys", evidence = "busca ROM con test-keys (root)"),
        Rule(Kind.ROOT, "Lcom/scottyab/rootbeer", evidence = "RootBeer"),
        // Emulador
        Rule(Kind.EMULATOR, "goldfish", evidence = "detecta el emulador (goldfish)"),
        Rule(Kind.EMULATOR, "ro.kernel.qemu", evidence = "detecta QEMU (emulador)"),
        Rule(Kind.EMULATOR, "Genymotion", evidence = "detecta Genymotion (emulador)"),
        // Depurador
        Rule(Kind.DEBUGGER, "isDebuggerConnected", evidence = "comprueba si hay un depurador"),
        Rule(Kind.DEBUGGER, "FLAG_DEBUGGABLE", evidence = "comprueba si el APK es depurable"),
        // Reempaquetado
        Rule(Kind.REPACKAGE, "getCrc", evidence = "calcula un CRC (anti-reempaquetado)"),
        Rule(Kind.REPACKAGE, "ZipEntry;->getCrc", evidence = "CRC de un archivo del propio APK"),
    )

    /** La app propia (sin librerías), más algunas librerías de integridad conocidas. */
    private fun ownAndIntegrity(f: File): Boolean {
        val p = f.path
        if (ProjectReader.LIB.none { p.contains(it) }) return true
        return p.contains("/play/core/integrity") || p.contains("/scottyab/rootbeer") || p.contains("SafetyNet")
    }

    private fun cacheFile(project: File) = File(project, "$META_DIR/integrity.txt")

    /** Escanea una vez y cachea el texto; lo usan el agente y la preparación de mensajes. */
    fun cachedExplain(project: File): String {
        val c = cacheFile(project)
        if (c.isFile) return c.readText()
        val txt = runCatching { scan(project).explain() }.getOrDefault("")
        runCatching { c.parentFile?.mkdirs(); c.writeText(txt) }
        return txt
    }

    fun scan(project: File, listener: ProgressListener = ProgressListener.NONE): Report {
        val out = ArrayList<Finding>()
        val smaliDir = File(project, "smali").takeIf { it.isDirectory } ?: project
        val files = smaliDir.walkTopDown().filter { it.isFile && it.extension == "smali" && ownAndIntegrity(it) }.toList()
        listener.onProgress("🛡 Buscando comprobaciones de integridad en ${files.size} archivos…")
        val plain = RULES.filter { !it.regex }
        val rx = RULES.filter { it.regex }.map { it to Regex(it.needle) }
        files.forEachIndexed { idx, f ->
            if (idx % 500 == 0) listener.onPercent(idx * 100 / files.size.coerceAtLeast(1))
            val rel = f.relativeTo(smaliDir.takeIf { it.name == "smali" } ?: project).path
            val lines = runCatching { f.readLines() }.getOrNull() ?: return@forEachIndexed
            lines.forEachIndexed { i, l ->
                plain.forEach { r -> if (l.contains(r.needle)) out += Finding(r.kind, rel, i + 1, r.evidence, r.needle) }
                rx.forEach { (r, re) -> re.find(l)?.let { out += Finding(r.kind, rel, i + 1, r.evidence + ": " + it.value, it.value) } }
            }
        }
        // Librerías nativas que suelen esconder comprobaciones de firma
        File(project, "lib").walkTopDown().filter { it.extension == "so" }.map { it.name }.distinct().forEach { so ->
            if (so.contains("integrity") || so.contains("security") || so.contains("guard") || so.contains("protect"))
                out += Finding(Kind.NATIVE, "lib/.../$so", 0, "librería nativa sospechosa", so)
        }
        // dedup por kind+archivo
        val dedup = out.distinctBy { it.kind.name + "|" + it.file + "|" + it.evidence }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.file }))
        listener.onProgress("🛡 ${dedup.size} señal(es) de integridad")
        return Report(dedup)
    }
}
