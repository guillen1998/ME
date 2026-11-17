package dev.apkforge.engine

import java.io.File

/** Recibe mensajes de progreso legibles para mostrar en la UI / log. */
fun interface ProgressListener {
    fun onProgress(message: String)

    companion object {
        val NONE = ProgressListener { }
    }
}

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Carpeta interna de cada proyecto decompilado (metadatos de ApkForge). */
internal const val META_DIR = ".apkforge"

internal fun File.ensureDir(): File {
    if (!isDirectory && !mkdirs()) throw EngineException("No se pudo crear la carpeta: $absolutePath")
    return this
}

/**
 * Huella rápida de una carpeta (cantidad de archivos, tamaño total y última
 * modificación). Sirve para saber si el usuario tocó el smali de un dex y así
 * reutilizar el dex original cuando no hay cambios (compilación más rápida y segura).
 */
internal fun fingerprint(dir: File): String {
    var count = 0L
    var size = 0L
    var newest = 0L
    dir.walkTopDown().filter { it.isFile }.forEach {
        count++
        size += it.length()
        if (it.lastModified() > newest) newest = it.lastModified()
    }
    return "$count:$size:$newest"
}
