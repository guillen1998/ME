package dev.apkforge.engine

import java.io.File

/** Recibe mensajes de progreso legibles para mostrar en la UI / log. */
fun interface ProgressListener {
    fun onProgress(message: String)

    /** Avance total de la operación, 0..100 (opcional). */
    fun onPercent(percent: Int) {}

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

/** Mensaje útil aunque la excepción no traiga texto (p. ej. NoClassDefFoundError → muestra la causa raíz). */
fun describe(t: Throwable): String {
    var root: Throwable = t
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val head = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    return if (root === t) head else "$head ← ${root.javaClass.simpleName}: ${root.message ?: ""}".trimEnd(' ', ':')
}

/** Reparte un rango de porcentaje [from, to] entre sub-pasos. */
internal class PercentRange(private val l: ProgressListener, private val from: Int, private val to: Int) {
    private var last = -1
    fun at(fraction: Double) {
        val p = (from + (to - from) * fraction.coerceIn(0.0, 1.0)).toInt()
        if (p != last) { last = p; l.onPercent(p) }
    }
}
