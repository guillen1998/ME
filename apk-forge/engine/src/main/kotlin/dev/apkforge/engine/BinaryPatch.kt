package dev.apkforge.engine

import java.io.File
import java.io.RandomAccessFile

/**
 * Edición de archivos BINARIOS (como el editor hex de MT Manager): .so, .dex, .bin, .dat, assets,
 * imágenes… Escribir bytes en un offset, o buscar y reemplazar texto/bytes. Siempre con copia
 * de seguridad y registro en el informe de cambios.
 */
object BinaryPatch {

    data class Result(val replaced: Int, val offsets: List<Long>, val message: String)

    /** "48 65 6C", "48656c", "0x48,0x65" -> bytes. */
    fun parseHex(hex: String): ByteArray = CryptoTools.hex(hex) ?: throw EngineException("Hex no válido: $hex")

    private fun backupAndLog(file: File, note: String, changedBytes: Int) {
        val project = ChangeLog.projectOf(file)
        val base = project ?: file.parentFile
        runCatching {
            val dst = File(base, ".apkforge/backup/${System.currentTimeMillis()}/${file.absoluteFile.relativeTo(base).path}")
            dst.parentFile?.mkdirs(); file.copyTo(dst, overwrite = true)
        }
        ChangeLog.recordBinary(file, note, changedBytes)
    }

    /** Escribe [bytes] a partir de [offset] (sobrescribe; no cambia el tamaño salvo que se pase del final). */
    fun writeAt(file: File, offset: Long, bytes: ByteArray, who: String = "Manual"): Result {
        if (offset < 0 || offset > file.length()) throw EngineException("Offset fuera del archivo (0..${file.length()})")
        backupAndLog(file, "$who · escribir ${bytes.size} bytes en 0x%X".format(offset), bytes.size)
        RandomAccessFile(file, "rw").use { it.seek(offset); it.write(bytes) }
        return Result(1, listOf(offset), "Escritos ${bytes.size} bytes en 0x%X".format(offset))
    }

    /**
     * Busca [find] y lo reemplaza por [replace]. Con [keepSize] (recomendado para .so/.dex) el
     * reemplazo debe ocupar lo mismo: si es más corto se rellena con [pad] (0x00), si es más largo se rechaza.
     */
    fun replace(
        file: File, find: ByteArray, replace: ByteArray, keepSize: Boolean = true, pad: Byte = 0,
        maxCount: Int = Int.MAX_VALUE, who: String = "Manual",
    ): Result {
        if (find.isEmpty()) throw EngineException("Nada que buscar")
        var repl = replace
        if (keepSize) {
            if (repl.size > find.size) throw EngineException("El reemplazo (${repl.size} B) es más largo que lo buscado (${find.size} B). En binarios debe ocupar lo mismo; acórtalo.")
            if (repl.size < find.size) repl = repl + ByteArray(find.size - repl.size) { pad }
        }
        val data = file.readBytes()
        val offsets = ArrayList<Long>()
        var i = 0
        while (i <= data.size - find.size && offsets.size < maxCount) {
            if (matchAt(data, find, i)) { offsets += i.toLong(); i += find.size } else i++
        }
        if (offsets.isEmpty()) return Result(0, emptyList(), "No se encontró")
        backupAndLog(file, "$who · reemplazo de ${find.size} B en ${offsets.size} sitio(s): " +
            offsets.take(5).joinToString { "0x%X".format(it) }, offsets.size * repl.size)
        if (keepSize) {
            RandomAccessFile(file, "rw").use { raf -> offsets.forEach { raf.seek(it); raf.write(repl) } }
        } else {
            val out = java.io.ByteArrayOutputStream(data.size)
            var last = 0
            offsets.forEach { o -> out.write(data, last, (o - last).toInt()); out.write(repl); last = (o + find.size).toInt() }
            out.write(data, last, data.size - last)
            file.writeBytes(out.toByteArray())
        }
        return Result(offsets.size, offsets, "Reemplazado en ${offsets.size} sitio(s)")
    }

    fun replaceText(file: File, find: String, replace: String, keepSize: Boolean = true, who: String = "Manual") =
        replace(file, find.toByteArray(Charsets.UTF_8), replace.toByteArray(Charsets.UTF_8), keepSize, 0, Int.MAX_VALUE, who)

    /** Offsets donde aparece un texto o secuencia de bytes. */
    fun find(file: File, needle: ByteArray, max: Int = 200): List<Long> {
        val data = file.readBytes()
        val out = ArrayList<Long>()
        var i = 0
        while (i <= data.size - needle.size && out.size < max) { if (matchAt(data, needle, i)) out += i.toLong(); i++ }
        return out
    }

    private fun matchAt(data: ByteArray, find: ByteArray, i: Int): Boolean {
        for (j in find.indices) if (data[i + j] != find[j]) return false
        return true
    }
}
