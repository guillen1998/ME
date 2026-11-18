package dev.apkforge.engine

import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Un cambio en un archivo del proyecto. */
data class ChangeEntry(
    val time: Long,
    val file: String,     // ruta relativa al proyecto
    val who: String,      // "IA", "Manual", "Buscar y reemplazar"…
    val added: Int,
    val removed: Int,
    val note: String = "",
    val diff: String = "",  // fragmento del diff (recortado)
)

/**
 * Informe de cambios de cada proyecto: qué se tocó, quién y cuándo. Lo usa la IA (para saber qué
 * se editó antes), la pantalla de exportar (para avisar si no hay cambios) y se guarda junto al
 * APK exportado como "_cambios.txt".
 */
object ChangeLog {
    private val gson = Gson()
    private fun logFile(project: File) = File(project, ".apkforge/changes.jsonl")
    private fun lastExportFile(project: File) = File(project, ".apkforge/last_export")

    const val NEW_MARK = ".__nuevo__"

    /** Proyecto al que pertenece un archivo (sube carpetas hasta encontrar .apkforge/project.properties). */
    fun projectOf(file: File): File? = generateSequence(file.absoluteFile.parentFile) { it.parentFile }.firstOrNull { ApkProject.isProject(it) }

    /** Registra un cambio comparando el contenido anterior y el nuevo. No hace nada si el archivo no está en un proyecto. */
    fun record(file: File, before: String?, after: String?, who: String, note: String = "") {
        val project = projectOf(file) ?: return
        // Guarda el ORIGINAL la primera vez que se toca un archivo: permite "deshacer lo que rompe la compilación"
        runCatching {
            val orig = File(project, "$META_DIR/original/" + file.absoluteFile.relativeTo(project).path)
            val newMark = File(orig.path + NEW_MARK)
            if (!orig.exists() && !newMark.exists()) {
                orig.parentFile?.mkdirs()
                if (before != null) orig.writeText(before) else newMark.writeText("")
            }
        }
        runCatching {
            val (diff, add, rem) = CodeAssistant.diff(before ?: "", after ?: "", file.name)
            if (add == 0 && rem == 0) return
            val now = System.currentTimeMillis()
            // Copia de seguridad del estado ANTERIOR: permite restaurar el proyecto a cualquier fecha
            runCatching {
                val rel = file.absoluteFile.relativeTo(project).path
                val dst = File(project, "$META_DIR/backup/$now/$rel")
                dst.parentFile?.mkdirs()
                if (before != null) dst.writeText(before) else File(dst.path + NEW_MARK).writeText("")
            }
            val e = ChangeEntry(now, file.absoluteFile.relativeTo(project).path, who, add, rem, note.take(300),
                diff.lines().take(40).joinToString("\n").take(3000))
            logFile(project).apply { parentFile?.mkdirs() }.appendText(gson.toJson(e) + "\n")
        }
    }

    /** Cambio en un archivo binario o de estructura (borrar, mover, importar…). */
    fun recordBinary(file: File, note: String, changed: Int = 0, who: String = note.substringBefore(" ·").ifBlank { "Manual" }) {
        val project = projectOf(file) ?: return
        runCatching {
            val e = ChangeEntry(System.currentTimeMillis(), file.absoluteFile.relativeTo(project).path, who, changed, 0, note.take(300))
            logFile(project).apply { parentFile?.mkdirs() }.appendText(gson.toJson(e) + "\n")
        }
    }

    /** Reescribe el historial dejando solo las entradas que cumplan [keep]. */
    fun retain(project: File, keep: (ChangeEntry) -> Boolean) = runCatching {
        val kept = all(project).filter(keep)
        logFile(project).writeText(kept.joinToString("") { gson.toJson(it) + "\n" })
    }

    fun all(project: File): List<ChangeEntry> = runCatching {
        logFile(project).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }
            ?.mapNotNull { runCatching { gson.fromJson(it, ChangeEntry::class.java) }.getOrNull() }.orEmpty()
    }.getOrDefault(emptyList())

    fun lastExport(project: File): Long = runCatching { lastExportFile(project).readText().trim().toLong() }.getOrDefault(0L)

    fun sinceLastExport(project: File): List<ChangeEntry> = lastExport(project).let { t -> all(project).filter { it.time > t } }

    fun markExported(project: File) = runCatching { lastExportFile(project).apply { parentFile?.mkdirs() }.writeText(System.currentTimeMillis().toString()) }

    private val fmt = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    /** Informe legible (para la IA, para la pantalla y para guardar junto al APK). */
    fun report(project: File, entries: List<ChangeEntry> = all(project), withDiff: Boolean = true): String = buildString {
        appendLine("INFORME DE CAMBIOS — ${project.name}")
        if (entries.isEmpty()) { appendLine("Sin cambios registrados."); return@buildString }
        val byFile = entries.groupBy { it.file }
        appendLine("${entries.size} cambio(s) en ${byFile.size} archivo(s)")
        appendLine()
        byFile.forEach { (f, list) ->
            appendLine("■ $f  (+${list.sumOf { it.added }} / −${list.sumOf { it.removed }})")
            list.forEach { e ->
                appendLine("  ${fmt.format(Date(e.time))} · ${e.who}" + (if (e.note.isNotBlank()) " · ${e.note}" else ""))
                if (withDiff && e.diff.isNotBlank()) e.diff.lines().forEach { appendLine("    $it") }
            }
            appendLine()
        }
    }
}
