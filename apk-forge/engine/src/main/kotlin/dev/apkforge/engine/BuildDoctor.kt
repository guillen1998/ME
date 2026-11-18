package dev.apkforge.engine

import java.io.File

/**
 * "Doctor de compilación": encuentra TODOS los errores que romperían la exportación (no solo el
 * primero), los repara con la IA y, si no lo consigue, deshace SOLO los archivos que rompen,
 * para que siempre salga un APK y sepas exactamente qué falló.
 */
object BuildDoctor {

    data class Problem(val file: File, val line: Int, val message: String, val hard: Boolean = true) {
        fun label(project: File) = "${runCatching { file.relativeTo(project).path }.getOrDefault(file.path)}:$line — $message"
    }

    private val RES_TYPES = setOf("color", "string", "dimen", "drawable", "layout", "style", "mipmap", "integer", "bool", "array", "anim",
        "animator", "xml", "raw", "font", "menu", "plurals", "attr", "id", "interpolator", "transition", "navigation", "fraction")

    /** Archivos tocados (por la IA, a mano, buscar y reemplazar…) que siguen existiendo. */
    fun touched(project: File): List<File> =
        ChangeLog.all(project).map { File(project, it.file) }.distinct().filter { it.isFile }

    /** Revisa los archivos tocados. [all] = revisar todos los XML de valores también (duplicados). */
    fun diagnose(project: File, listener: ProgressListener = ProgressListener.NONE): List<Problem> {
        val files = touched(project)
        val out = mutableListOf<Problem>()
        if (files.isEmpty()) return out
        listener.onProgress("🩺 Revisando ${files.size} archivo(s) modificado(s)…")
        val declared by lazy { declaredResources(project) }
        files.forEach { f ->
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            when (f.extension.lowercase()) {
                "smali" -> CodeAssistant.validateSmali(text)?.let { err ->
                    // el error de smali trae "línea[12,4] ..." → extraemos la línea
                    val line = Regex("""\[(\d+),\d+]""").find(err)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""(?i)l[ií]nea\s+(\d+)""").find(err)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val first = err.lines().firstOrNull { it.isNotBlank() } ?: err
                    out += Problem(f, line, first.replace(Regex("""^.*?l[ií]nea\[\d+,\d+]\s*"""), "").take(300))
                }
                "xml" -> {
                    val xmlErr = XmlCheck.error(text)
                    if (xmlErr != null) {
                        val line = Regex("""\[(\d+):\d+]""").find(xmlErr)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("""(?i)(?:line|l[ií]nea)\D{0,4}(\d+)""").find(xmlErr)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        out += Problem(f, line, "XML inválido: " + xmlErr.removePrefix("XML no válido: ").replace(Regex("""\s*\(position:.*"""), ""))
                    } else if (f.path.contains("/res/") || f.name == "AndroidManifest.xml") {
                        out += missingRefs(f, text, declared)
                    }
                }
            }
        }
        out += duplicates(project, files)
        return out
    }

    /** Recursos declarados (public.xml + values + archivos). */
    fun declaredResources(project: File): Set<String> {
        val set = HashSet<String>()
        File(project, "resources").listFiles().orEmpty().forEach { pkg ->
            val res = File(pkg, "res").takeIf { it.isDirectory } ?: return@forEach
            File(res, "values/public.xml").takeIf { it.isFile }?.readText()?.let { t ->
                Regex("""type="([^"]+)"\s+name="([^"]+)"""").findAll(t).forEach { set += "${it.groupValues[1]}/${it.groupValues[2]}" }
            }
            res.listFiles { d -> d.isDirectory }.orEmpty().forEach { dir ->
                val type = dir.name.substringBefore('-')
                dir.listFiles().orEmpty().forEach { f ->
                    if (type == "values") runCatching {
                        val t = f.readText()
                        Regex("""<(\w[\w-]*)\s+[^>]*?name="([^"]+)"""").findAll(t).forEach { m ->
                            val tag = m.groupValues[1]
                            val ty = when (tag) { "item" -> Regex("""type="([^"]+)"""").find(m.value)?.groupValues?.get(1) ?: "item"
                                "string-array", "integer-array" -> "array"; else -> tag }
                            set += "$ty/${m.groupValues[2]}"
                        }
                    } else set += "$type/${f.name.substringBefore('.')}"
                    if (f.extension == "xml") runCatching { Regex("""@\+id/([\w.]+)""").findAll(f.readText()).forEach { set += "id/${it.groupValues[1]}" } }
                }
            }
        }
        return set
    }

    private fun missingRefs(f: File, text: String, declared: Set<String>): List<Problem> {
        if (declared.isEmpty()) return emptyList()
        val out = mutableListOf<Problem>()
        text.lines().forEachIndexed { i, l ->
            Regex("""(?<![\w:+])@(\w+)/([\w.]+)""").findAll(l).forEach { m ->
                val type = m.groupValues[1]; val name = m.groupValues[2]
                if (type in RES_TYPES && "$type/$name" !in declared && "$type/${name.replace('.', '_')}" !in declared)
                    out += Problem(f, i + 1, "Recurso inexistente @$type/$name (créalo o usa uno que exista)", hard = false)
            }
        }
        return out
    }

    /** Mismo recurso definido dos veces en la misma carpeta values* → aapt/ARSCLib fallan. */
    private fun duplicates(project: File, touched: List<File>): List<Problem> {
        val dirs = touched.filter { it.parentFile.name.startsWith("values") }.map { it.parentFile }.distinct()
        val out = mutableListOf<Problem>()
        dirs.forEach { dir ->
            val seen = HashMap<String, Pair<File, Int>>()
            dir.listFiles { f -> f.extension == "xml" && f.name != "public.xml" }.orEmpty().sortedBy { it.name }.forEach { f ->
                runCatching { f.readLines() }.getOrDefault(emptyList()).forEachIndexed { i, l ->
                    val m = Regex("""^\s*<(\w[\w-]*)\s+[^>]*?name="([^"]+)"""").find(l) ?: return@forEachIndexed
                    val tag = m.groupValues[1]
                    if (tag == "item" && !l.contains("type=")) return@forEachIndexed   // items dentro de style/array
                    if (l.trimStart().startsWith("<attr") ) return@forEachIndexed
                    val ty = if (tag == "item") Regex("""type="([^"]+)"""").find(l)!!.groupValues[1] else tag
                    val key = "$ty/${m.groupValues[2]}"
                    val prev = seen[key]
                    if (prev != null && (f in touched || prev.first in touched)) out += Problem(f, i + 1, "Recurso duplicado $key (ya está en ${prev.first.name}:${prev.second})")
                    else seen[key] = f to (i + 1)
                }
            }
        }
        return out
    }

    /**
     * Reparación sin IA de recursos duplicados en el mismo archivo: lo normal es que se quisiera
     * CAMBIAR el valor, así que el valor nuevo pasa a la definición original y se quita la repetida.
     */
    fun fixDuplicates(project: File, problems: List<Problem>, listener: ProgressListener): Int {
        var fixed = 0
        problems.filter { it.message.startsWith("Recurso duplicado") }.groupBy { it.file }.forEach { (f, ps) ->
            val lines = f.readLines().toMutableList()
            val before = lines.joinToString("\n")
            val remove = mutableListOf<Int>()
            ps.forEach { p ->
                val dup = lines.getOrNull(p.line - 1) ?: return@forEach
                val m = Regex("""^\s*<(\w[\w-]*)\s+([^>]*?)name="([^"]+)"([^>]*)>(.*)</\1>\s*$""").find(dup) ?: return@forEach
                val (tag, name, value) = Triple(m.groupValues[1], m.groupValues[3], m.groupValues[5])
                val firstIdx = lines.indexOfFirst { Regex("""^\s*<$tag\s+[^>]*name="${Regex.escape(name)}"[^>]*>.*</$tag>\s*$""").matches(it) }
                if (firstIdx < 0 || firstIdx == p.line - 1) return@forEach
                lines[firstIdx] = lines[firstIdx].replace(Regex(""">(.*)</$tag>"""), Regex.escapeReplacement(">$value</$tag>"))
                remove += p.line - 1; fixed++
                listener.onProgress("  ✔ ${f.name}: $tag “$name” estaba repetido → se cambió el valor original a $value")
            }
            remove.sortedDescending().forEach { lines.removeAt(it) }
            if (remove.isNotEmpty()) { val now = lines.joinToString("\n"); f.writeText(now); ChangeLog.record(f, before, now, "App", "Duplicados unificados") }
        }
        return fixed
    }

    // ───────────── deshacer ─────────────

    fun originalOf(project: File, file: File): File? {
        val rel = file.absoluteFile.relativeTo(project.absoluteFile).path
        File(project, "$META_DIR/original/$rel").takeIf { it.isFile }?.let { return it }
        File(project, "$META_DIR/original/$rel${ChangeLog.NEW_MARK}").takeIf { it.isFile }?.let { return it }
        // copia de seguridad más antigua de la IA
        return File(project, "$META_DIR/backup").listFiles().orEmpty().sortedBy { it.name }.map { File(it, rel) }.firstOrNull { it.isFile }
    }

    fun canRevert(project: File, file: File) = originalOf(project, file) != null

    /** Vuelve el archivo a como estaba antes de los cambios (o lo borra si era nuevo). */
    fun revert(project: File, file: File, reason: String): Boolean {
        val orig = originalOf(project, file) ?: return false
        val before = runCatching { file.readText() }.getOrNull()
        if (orig.name.endsWith(ChangeLog.NEW_MARK)) file.delete() else orig.copyTo(file, overwrite = true)
        ChangeLog.record(file, before, runCatching { file.readText() }.getOrDefault(""), "App", "Deshecho: $reason".take(300))
        return true
    }

    // ───────────── reparar con IA ─────────────

    /** Intenta arreglar [p] con la IA. Devuelve true si el archivo queda sin ese error. */
    fun aiFix(project: File, p: Problem, client: AiTextClient, listener: ProgressListener): Boolean {
        val f = p.file
        val lines = f.readLines()
        val whole = lines.size <= 700
        val from = if (whole) 0 else (p.line - 60).coerceAtLeast(0)
        val to = if (whole) lines.size else (p.line + 60).coerceAtMost(lines.size)
        val chunk = lines.subList(from, to).joinToString("\n")
        val orig = originalOf(project, f)?.takeIf { !it.name.endsWith(ChangeLog.NEW_MARK) }
        val origHint = orig?.let { o ->
            val ol = o.readLines(); "\n\nVERSIÓN ORIGINAL (compilaba) del mismo tramo, como referencia:\n```\n" +
                ol.subList(from.coerceAtMost(ol.size), to.coerceAtMost(ol.size)).joinToString("\n").take(12000) + "\n```"
        }.orEmpty()
        repeat(2) { attempt ->
            val answer = runCatching {
                client.complete(
                    "Eres un experto en smali y recursos Android. Corrige el error de compilación con el cambio MÍNIMO, conservando la " +
                        "intención del cambio. Devuelve SOLO el tramo corregido completo en un bloque ``` ```, sin explicaciones.",
                    "ARCHIVO: ${f.name}\nERROR (línea ${p.line}): ${p.message}\n\nTRAMO ${if (whole) "(archivo completo)" else "(líneas ${from + 1}-$to)"}:\n```\n$chunk\n```$origHint",
                )
            }.getOrNull() ?: return false
            val fixed = CodeAssistant.extractCode(answer) ?: return@repeat
            val newText = (lines.subList(0, from) + fixed.lines() + lines.subList(to, lines.size)).joinToString("\n")
            val err = when (f.extension) {
                "smali" -> CodeAssistant.validateSmali(newText)
                "xml" -> XmlCheck.error(newText)
                else -> null
            }
            if (err == null) {
                val before = f.readText()
                f.writeText(newText)
                if (diagnose(project).any { it.hard && it.file == f && it.message == p.message }) { f.writeText(before); return@repeat }
                ChangeLog.record(f, before, newText, "IA (reparación)", p.message.take(200))
                listener.onProgress("  ✔ IA reparó ${f.name}:${p.line}")
                return true
            }
            listener.onProgress("  … intento ${attempt + 1} de la IA aún falla: ${err.lines().first().take(120)}")
        }
        return false
    }

    /**
     * Exportación a prueba de errores:
     * 1) revisa todo, 2) la IA repara lo que pueda, 3) deshace solo lo que siga roto,
     * 4) compila; si la compilación aún señala un archivo modificado, lo deshace y reintenta.
     */
    fun buildRepairing(
        project: File, out: File, options: BuildOptions, ai: AiTextClient?, listener: ProgressListener,
        revertBroken: Boolean = true,
    ): File {
        val reverted = mutableListOf<String>()
        val pending = ChangeLog.sinceLastExport(project)
        var problems = diagnose(project, listener).filter { it.hard }
        // El motor neuronal aprende qué tipo de cambios rompen la compilación
        if (problems.isNotEmpty()) runCatching { NeuralBrain.learnFromBuild(project, problems.map { it.file.relativeTo(project).path }.toSet(), pending) }
        if (problems.isNotEmpty()) {
            listener.onProgress("⚠ ${problems.size} problema(s):")
            problems.take(30).forEach { listener.onProgress("  • " + it.label(project)) }
            if (fixDuplicates(project, problems, listener) > 0) problems = diagnose(project, ProgressListener.NONE).filter { it.hard }
            if (ai != null && problems.isNotEmpty()) {
                listener.onProgress("🤖 La IA intenta repararlos…")
                problems.distinctBy { it.file to it.line }.take(12).forEach { p -> runCatching { aiFix(project, p, ai, listener) } }
                problems = diagnose(project, ProgressListener.NONE).filter { it.hard }
            }
            if (problems.isNotEmpty() && revertBroken) problems.map { it.file }.distinct().forEach { f ->
                val why = problems.first { it.file == f }.message
                if (revert(project, f, why)) { reverted += f.relativeTo(project).path; listener.onProgress("↩ Deshecho ${f.name} (no compilaba: ${why.take(100)})") }
            }
        }
        var lastError: Throwable? = null
        repeat(6) {
            try {
                val apk = ApkProject.build(project, out, options, listener)
                if (problems.isEmpty() && reverted.isEmpty()) runCatching { NeuralBrain.learnFromBuild(project, emptySet(), pending) }
                if (reverted.isNotEmpty()) {
                    listener.onProgress("✔ APK exportado. Se deshicieron ${reverted.size} archivo(s) que no compilaban: " + reverted.joinToString())
                    runCatching { File(out.parentFile, out.nameWithoutExtension + "_cambios.txt").appendText(
                        "\n\nDESHECHOS PORQUE NO COMPILABAN:\n" + reverted.joinToString("\n") { "  - $it" }) }
                }
                return apk
            } catch (t: Throwable) {
                lastError = t
                if (!revertBroken) throw t
                val msg = t.message ?: throw t
                val loc = BuildErrors.locate(msg) ?: throw t
                val file = loc.file.takeIf { it.isFile && it.absolutePath.startsWith(project.absolutePath) } ?: throw t
                if (ai != null && runCatching { aiFix(project, Problem(file, loc.line, loc.message), ai, listener) }.getOrDefault(false)) return@repeat
                runCatching { NeuralBrain.learnFromBuild(project, setOf(file.relativeTo(project).path), pending) }
                if (!revert(project, file, loc.message)) throw t
                reverted += file.relativeTo(project).path
                listener.onProgress("↩ Deshecho ${file.name} (la compilación falló ahí: ${loc.message.take(100)}); reintentando…")
            }
        }
        throw lastError ?: EngineException("No se pudo compilar")
    }
}
