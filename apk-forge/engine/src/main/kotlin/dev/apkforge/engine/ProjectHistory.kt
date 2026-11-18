package dev.apkforge.engine

import java.io.File

/**
 * Copias de seguridad del proyecto como "puntos de restauración": cada tanda de cambios (de la IA,
 * tuyos, buscar y reemplazar…) es un punto, ordenado por fecha. Puedes volver a como estaba el
 * proyecto ANTES de cualquier punto, borrar copias que ya no quieras o pedir a la IA que explique
 * qué se cambió. Restaurar también es un cambio, así que se puede deshacer.
 */
object ProjectHistory {

    /** Cambios del mismo autor separados por más de esto forman puntos distintos. */
    private const val GAP = 5 * 60_000L

    data class RestorePoint(val start: Long, val end: Long, val entries: List<ChangeEntry>) {
        val files get() = entries.map { it.file }.distinct()
        val who get() = entries.map { it.who }.distinct()
        val added get() = entries.sumOf { it.added }
        val removed get() = entries.sumOf { it.removed }
        val id get() = start
    }

    data class RestoreResult(val restored: List<String>, val removed: List<String>, val missing: List<String>) {
        fun summary() = buildString {
            append("${restored.size} archivo(s) restaurado(s)")
            if (removed.isNotEmpty()) append(", ${removed.size} archivo(s) nuevo(s) quitado(s)")
            if (missing.isNotEmpty()) append(", ${missing.size} sin copia: ${missing.take(3).joinToString()}")
        }
    }

    private fun backupRoot(project: File) = File(project, "$META_DIR/backup")

    /** Puntos de restauración, del más reciente al más antiguo. */
    fun points(project: File): List<RestorePoint> {
        val entries = ChangeLog.all(project).sortedBy { it.time }
        val groups = mutableListOf<MutableList<ChangeEntry>>()
        // Un punto nuevo si pasó un rato o si cambia quién hizo los cambios (IA, tú, restaurar…)
        entries.forEach { e ->
            val last = groups.lastOrNull()?.last()
            if (last == null || e.time - last.time > GAP || e.who != last.who) groups += mutableListOf(e) else groups.last() += e
        }
        return groups.map { RestorePoint(it.first().time, it.last().time, it) }.reversed()
    }

    /** Tamaño en disco de las copias de un punto. */
    fun size(project: File, p: RestorePoint): Long = backupDirs(project, p).sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    private fun backupDirs(project: File, p: RestorePoint) = backupRoot(project).listFiles { d -> d.isDirectory }.orEmpty()
        .filter { d -> d.name.toLongOrNull()?.let { it in (p.start - 3000)..(p.end + 3000) } == true }

    /** Copia del archivo [rel] tal como estaba en el instante [t] (la primera copia hecha desde entonces). */
    private fun backupAt(project: File, rel: String, t: Long): File? =
        backupRoot(project).listFiles { d -> d.isDirectory }.orEmpty()
            .mapNotNull { d -> d.name.toLongOrNull()?.let { it to d } }.filter { it.first >= t - 3000 }.sortedBy { it.first }
            .firstNotNullOfOrNull { (_, d) -> File(d, rel).takeIf { it.isFile } ?: File(d, rel + ChangeLog.NEW_MARK).takeIf { it.isFile } }

    /** Deja el proyecto como estaba ANTES del punto [p] (deshace ese punto y todo lo posterior). */
    fun restoreBefore(project: File, p: RestorePoint, listener: ProgressListener = ProgressListener.NONE): RestoreResult {
        val t = p.start
        val label = java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
        val files = ChangeLog.all(project).filter { it.time >= t }.map { it.file }.distinct()
        val restored = mutableListOf<String>(); val removed = mutableListOf<String>(); val missing = mutableListOf<String>()
        files.forEach { rel ->
            val target = File(project, rel)
            val copy = backupAt(project, rel, t)
            val before = runCatching { target.takeIf { it.isFile }?.readText() }.getOrNull()
            when {
                copy == null -> missing += rel
                copy.name.endsWith(ChangeLog.NEW_MARK) -> if (target.exists()) {
                    target.deleteRecursively(); removed += rel
                    ChangeLog.recordBinary(target, "Restaurado a antes del $label · quitado (era nuevo)", 0, "Restaurar")
                }
                else -> {
                    target.parentFile?.mkdirs()
                    val isText = before != null || runCatching { copy.readText(); true }.getOrDefault(false)
                    copy.copyTo(target, overwrite = true)
                    restored += rel
                    if (isText) ChangeLog.record(target, before, runCatching { target.readText() }.getOrNull(), "Restaurar", "Restaurado a antes del $label")
                    else ChangeLog.recordBinary(target, "Restaurado a antes del $label", 0, "Restaurar")
                }
            }
            listener.onProgress("↩ $rel")
        }
        return RestoreResult(restored, removed, missing)
    }

    /** Vuelve todo el proyecto al estado original (antes del primer cambio). */
    fun restoreOriginal(project: File, listener: ProgressListener = ProgressListener.NONE): RestoreResult? =
        points(project).lastOrNull()?.let { restoreBefore(project, it, listener) }

    /** Borra las copias y la entrada del historial de un punto (ya no se podrá volver a antes de él). */
    fun delete(project: File, p: RestorePoint) {
        backupDirs(project, p).forEach { it.deleteRecursively() }
        ChangeLog.retain(project) { it.time !in p.start..p.end }
    }

    /** Borra todas las copias y el historial (el proyecto queda tal como está ahora). */
    fun deleteAll(project: File) {
        backupRoot(project).deleteRecursively()
        ChangeLog.retain(project) { false }
    }

    /** Texto para que la IA explique, en sencillo, qué se cambió en ese punto. */
    fun explainPrompt(project: File, p: RestorePoint): Pair<String, String> =
        "Eres un experto en apps Android decompiladas (smali y recursos). Explica en español sencillo, para alguien que no programa, " +
            "QUÉ se cambió en esta tanda de cambios y QUÉ efecto tendrá en la app (qué se verá o se comportará distinto). " +
            "Agrupa por objetivo, no archivo por archivo. Si algo parece arriesgado o puede romper la app, avísalo. Máximo 12 líneas." to
            ChangeLog.report(project, p.entries).take(14000)
}
