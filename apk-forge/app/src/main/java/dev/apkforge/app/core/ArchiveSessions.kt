package dev.apkforge.app.core

import dev.apkforge.engine.ApkArchive
import dev.apkforge.engine.DexEditor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Mantiene abierto el APK que estás explorando (y su editor de dex) mientras saltas entre
 * pantallas, con sus cambios pendientes. Avisa a la UI cuando cambia algo.
 */
object ArchiveSessions {
    private var archive: ApkArchive? = null
    private var dex: DexEditor? = null
    private val _version = MutableStateFlow(0)
    /** Sube cada vez que hay cambios pendientes nuevos (para refrescar la UI). */
    val version: StateFlow<Int> = _version

    @Synchronized
    fun archive(apk: File): ApkArchive {
        archive?.let { if (it.apk.absolutePath == apk.absolutePath) return it }
        runCatching { archive?.close() }
        runCatching { dex?.close() }; dex = null
        return ApkArchive.open(apk).also { archive = it }
    }

    @Synchronized
    fun dexEditor(apk: File): DexEditor {
        dex?.let { if (archive?.apk?.absolutePath == apk.absolutePath) return it }
        archive(apk)
        return DexEditor.open(apk).also { dex = it }
    }

    fun changed() { _version.value++ }

    @Synchronized
    fun close() {
        runCatching { archive?.close() }; archive = null
        runCatching { dex?.close() }; dex = null
        changed()
    }
}
