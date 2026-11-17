package dev.apkforge.app.core

import dev.apkforge.engine.JavaDecompiler
import dev.apkforge.engine.JavaDecompilerOptions
import dev.apkforge.engine.ProgressListener
import java.io.File

/**
 * Mantiene abierta la última sesión de jadx, para que pasar de la lista de clases al código
 * y volver sea instantáneo (no recarga el APK cada vez).
 */
object JavaSessions {
    private var key: String? = null
    private var session: JavaDecompiler? = null

    /** Entradas que jadx debe leer: un APK/dex, o los dex originales de un proyecto. */
    fun inputsFor(path: File): List<File> {
        if (path.isDirectory) {
            val dexDir = File(path, ".apkforge/dex")
            val dex = dexDir.listFiles { f -> f.extension == "dex" }?.sortedBy { it.name }.orEmpty()
            if (dex.isNotEmpty()) return dex
            val raw = File(path, "dex").listFiles { f -> f.extension == "dex" }?.sortedBy { it.name }.orEmpty()
            if (raw.isNotEmpty()) return raw
            throw IllegalStateException("Este proyecto no tiene dex para ver en Java")
        }
        return listOf(path)
    }

    @Synchronized
    fun get(path: File, options: JavaDecompilerOptions, listener: ProgressListener = ProgressListener.NONE): JavaDecompiler {
        val k = path.absolutePath + "|" + options.hashCode()
        session?.let { if (key == k) return it }
        close()
        val s = JavaDecompiler.open(inputsFor(path), options, listener)
        session = s
        key = k
        return s
    }

    @Synchronized
    fun peek(path: File): JavaDecompiler? = session?.takeIf { key?.startsWith(path.absolutePath + "|") == true }

    @Synchronized
    fun close() {
        runCatching { session?.close() }
        session = null
        key = null
    }
}
