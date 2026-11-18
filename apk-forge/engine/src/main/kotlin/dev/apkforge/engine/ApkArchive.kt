package dev.apkforge.engine

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.xml.XMLFactory
import java.io.Closeable
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Explorador del INTERIOR de un APK sin decompilarlo (como MT Manager): navegar carpetas,
 * abrir cualquier entrada, ver el XML binario (AndroidManifest, res/layout…) como texto,
 * y modificar el APK directamente: reemplazar, añadir o borrar archivos y editar XML binario.
 * Los cambios se guardan en un APK nuevo, alineado y firmado.
 */
class ApkArchive private constructor(val apk: File, private val zip: ZipFile) : Closeable {

    data class Node(
        val path: String,        // ruta completa dentro del APK ("res/layout/main.xml" o "res/layout/")
        val name: String,
        val isDir: Boolean,
        val size: Long,          // tamaño real (suma si es carpeta)
        val compressed: Long,
        val stored: Boolean,     // sin comprimir
        val count: Int,          // nº de archivos dentro (carpetas)
    )

    private val entries: List<ZipEntry> = zip.entries().toList().filter { !it.isDirectory }

    /** Cambios pendientes: ruta -> bytes nuevos (null = borrar). */
    private val pending = LinkedHashMap<String, ByteArray?>()
    val pendingCount get() = pending.size
    fun pendingPaths(): Map<String, Boolean> = pending.mapValues { it.value != null }

    val totalEntries get() = entries.size

    /** Hijos directos de una carpeta ("" = raíz). Carpetas primero. */
    fun list(dir: String): List<Node> {
        val prefix = dir.trimEnd('/').let { if (it.isEmpty()) "" else "$it/" }
        val dirs = LinkedHashMap<String, MutableList<ZipEntry>>()
        val files = mutableListOf<Node>()
        val all = currentNames()
        for (name in all) {
            if (!name.startsWith(prefix)) continue
            val rest = name.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                dirs.getOrPut(rest.substring(0, slash)) { mutableListOf() }.let { list -> zip.getEntry(name)?.let { list += it } }
            } else {
                val e = zip.getEntry(name)
                val size = pending[name]?.size?.toLong() ?: e?.size ?: 0
                files += Node(name, rest, false, size, e?.compressedSize ?: size, e?.method == ZipEntry.STORED, 0)
            }
        }
        val dirNodes = dirs.map { (d, list) ->
            val count = all.count { it.startsWith("$prefix$d/") }
            Node("$prefix$d/", d, true, list.sumOf { it.size }, list.sumOf { it.compressedSize }, false, count)
        }
        return dirNodes.sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }
    }

    private fun currentNames(): List<String> =
        (entries.map { it.name }.filter { pending[it] != null || it !in pending } + pending.filterValues { it != null }.keys)
            .distinct()

    fun exists(path: String) = path in currentNames()

    /** Bytes de una entrada (la versión modificada si la cambiaste). */
    fun read(path: String): ByteArray {
        pending[path]?.let { return it }
        if (path in pending) throw EngineException("Borrado: $path")
        val e = zip.getEntry(path) ?: throw EngineException("No existe en el APK: $path")
        return zip.getInputStream(e).use { it.readBytes() }
    }

    /** Extrae una entrada a un archivo temporal (para abrirla en el editor o visor). */
    fun extract(path: String, cacheDir: File): File {
        val out = File(cacheDir, "apk-entries/${apk.nameWithoutExtension}/$path")
        out.parentFile?.mkdirs()
        out.writeBytes(read(path))
        return out
    }

    fun isBinaryXml(path: String): Boolean =
        path.endsWith(".xml") && runCatching { ResXmlDocument.isResXmlBlock(read(path)) }.getOrDefault(false)

    /** XML binario de Android (AXML) -> texto XML legible, con nombres de atributos y recursos. */
    fun decodeXml(path: String): String {
        val module = ApkModule.loadApkFile(apk)
        try {
            val doc: ResXmlDocument = if (path == "AndroidManifest.xml") AndroidManifestBlock() else ResXmlDocument()
            doc.readBytes(read(path).inputStream())
            doc.setApkFile(module)
            module.tableBlock?.pickOne()?.let { doc.setPackageBlock(it) }
            return runCatching { doc.serializeToXml() }.getOrElse { doc.toXml(true).toText(true, false) }
        } finally {
            runCatching { module.close() }
        }
    }

    /**
     * Texto XML editado -> XML binario de Android, usando la tabla de recursos del propio APK
     * para resolver @string/…, @drawable/… y los atributos android:.
     */
    fun encodeXml(path: String, xmlText: String) {
        val module = ApkModule.loadApkFile(apk)
        try {
            val pkg = module.tableBlock?.pickOne() ?: throw EngineException("El APK no tiene tabla de recursos")
            val doc: ResXmlDocument = if (path == "AndroidManifest.xml") AndroidManifestBlock() else ResXmlDocument()
            doc.setPackageBlock(pkg)
            doc.parse(XMLFactory.newPullParser(xmlText))
            doc.refresh()
            pending[path] = doc.bytes
        } catch (e: EngineException) {
            throw e
        } catch (e: Exception) {
            throw EngineException("XML no válido: ${describe(e)}", e)
        } finally {
            runCatching { module.close() }
        }
    }

    fun replace(path: String, bytes: ByteArray) { pending[path] = bytes }
    fun add(dir: String, file: File): String {
        val path = dir.trimEnd('/').let { if (it.isEmpty()) file.name else "$it/${file.name}" }
        pending[path] = file.readBytes()
        return path
    }
    fun delete(path: String) {
        if (path.endsWith("/")) currentNames().filter { it.startsWith(path) }.forEach { pending[it] = null }
        else pending[path] = null
    }
    fun discard() = pending.clear()

    /** Escribe un APK nuevo con los cambios, alineado y firmado. */
    fun save(outApk: File, signing: SigningConfig = SigningConfig.TestKey, listener: ProgressListener = ProgressListener.NONE): File {
        if (pending.isEmpty()) throw EngineException("No hay cambios")
        listener.onPercent(5)
        listener.onProgress("Aplicando ${pending.size} cambio(s)…")
        val module = ApkModule.loadApkFile(apk)
        val unsigned = File(outApk.absolutePath + ".unsigned.tmp")
        outApk.absoluteFile.parentFile?.mkdirs()
        try {
            pending.forEach { (path, bytes) ->
                module.zipEntryMap.remove(path)
                if (bytes != null) module.zipEntryMap.add(ByteInputSource(bytes, path))
            }
            // Las firmas viejas ya no valen tras modificar el APK.
            module.zipEntryMap.removeIf { it.name.startsWith("META-INF/") && (it.name.endsWith(".SF") || it.name.endsWith(".RSA") || it.name.endsWith(".EC") || it.name.endsWith(".DSA") || it.name == "META-INF/MANIFEST.MF") }
            listener.onProgress("Escribiendo APK…")
            module.writeApk(unsigned)
        } finally {
            runCatching { module.close() }
        }
        listener.onPercent(70)
        try {
            ApkSigning.sign(unsigned, outApk, signing, listener)
        } finally {
            unsigned.delete()
        }
        listener.onProgress("✔ APK modificado: ${outApk.absolutePath}")
        return outApk
    }

    override fun close() = zip.close()

    companion object {
        fun open(apk: File) = ApkArchive(apk, ZipFile(apk))
    }
}
