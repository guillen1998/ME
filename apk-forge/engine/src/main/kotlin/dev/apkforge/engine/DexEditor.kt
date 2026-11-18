package dev.apkforge.engine

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/**
 * Editor de dex rápido: abre las clases DIRECTAMENTE dentro del APK, sin decompilar la app
 * entera. Editas el smali de una clase, se ensambla solo esa clase, se reinyecta en su dex y se
 * genera el APK firmado. En apps grandes pasa de minutos a segundos.
 */
class DexEditor private constructor(
    private val apk: File,
    private val dexes: LinkedHashMap<String, DexBackedDexFile>,
) : Closeable {

    data class Entry(val type: String, val javaName: String, val dex: String)

    /** Todas las clases del APK con el dex en que viven. */
    val classes: List<Entry> by lazy {
        dexes.flatMap { (dexName, dex) ->
            dex.classes.map { Entry(it.type, typeToJava(it.type), dexName) }
        }.sortedBy { it.javaName }
    }

    private val editedSource = HashMap<String, String>()
    private val editedClass = HashMap<String, Pair<String, ClassDef>>() // tipo -> (dex, clase nueva)

    val modifiedCount get() = editedClass.size
    fun isModified(type: String) = type in editedClass

    fun search(query: String, limit: Int = 800): List<Entry> {
        val q = query.trim()
        if (q.isEmpty()) return classes.take(limit)
        return classes.asSequence().filter { it.javaName.contains(q, true) }.take(limit).toList()
    }

    /** smali de una clase (la versión editada si ya la cambiaste). */
    fun smali(type: String): String {
        editedSource[type]?.let { return it }
        val entry = classes.firstOrNull { it.type == type } ?: throw EngineException("Clase no encontrada: $type")
        val dex = dexes.getValue(entry.dex)
        val tmp = File.createTempFile("dexed", "").apply { delete(); mkdirs() }
        try {
            val opts = BaksmaliOptions().apply {
                apiLevel = dex.opcodes.api
                debugInfo = true; parameterRegisters = true; localsDirective = true
                sequentialLabels = true; accessorComments = true
            }
            Baksmali.disassembleDexFile(dex, tmp, 1, opts, listOf(type))
            val f = tmp.walkTopDown().firstOrNull { it.isFile && it.extension == "smali" }
                ?: throw EngineException("No se pudo desensamblar $type")
            return f.readText()
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Ensambla el smali editado. Si tiene errores, se lanzan con línea y columna y NO se aplica.
     */
    fun replace(type: String, smaliText: String) {
        val entry = classes.firstOrNull { it.type == type } ?: throw EngineException("Clase no encontrada: $type")
        val dex = dexes.getValue(entry.dex)
        val dir = File.createTempFile("dexedit", "").apply { delete(); mkdirs() }
        try {
            val src = File(dir, "Edit.smali").apply { writeText(smaliText) }
            val out = File(dir, "out.dex")
            val opts = SmaliOptions().apply {
                apiLevel = dex.opcodes.api.coerceAtLeast(15)
                outputDexFile = out.absolutePath
                jobs = 1
            }
            val (ok, err) = captureStderr { Smali.assemble(opts, listOf(src.absolutePath)) }
            if (!ok) throw EngineException("Errores en el smali:\n" + err.replace(src.absolutePath, "línea"))
            val newDex = DexBackedDexFile(dex.opcodes, out.readBytes())
            val newClass = newDex.classes.singleOrNull()
                ?: throw EngineException("El archivo debe definir exactamente una clase")
            if (newClass.type != type) throw EngineException("La clase se llama ${newClass.type}; debe seguir siendo $type")
            editedClass[type] = entry.dex to newClass
            editedSource[type] = smaliText
        } finally {
            dir.deleteRecursively()
        }
    }

    fun revert(type: String) { editedClass.remove(type); editedSource.remove(type) }

    /** Reescribe solo los dex tocados, los mete en el APK y lo firma. */
    fun save(
        outApk: File,
        signing: SigningConfig = SigningConfig.TestKey,
        listener: ProgressListener = ProgressListener.NONE,
    ): File {
        if (editedClass.isEmpty()) throw EngineException("No hay cambios que guardar")
        val byDex = editedClass.entries.groupBy({ it.value.first }, { it.key to it.value.second })
        listener.onPercent(2)
        val newBytes = LinkedHashMap<String, ByteArray>()
        byDex.entries.forEachIndexed { i, (dexName, changes) ->
            listener.onProgress("Reescribiendo $dexName (${changes.size} clase/s modificada/s)…")
            val repl = changes.toMap()
            val original = dexes.getValue(dexName)
            val merged = original.classes.map { repl[it.type] ?: it }
            val store = MemoryDataStore()
            DexPool.writeTo(store, ImmutableDexFile(original.opcodes, merged))
            newBytes[dexName] = store.data.copyOf(store.size)
            listener.onPercent(5 + 60 * (i + 1) / byDex.size)
        }

        listener.onProgress("Empaquetando APK…")
        val unsigned = File(outApk.absolutePath + ".unsigned.tmp")
        outApk.absoluteFile.parentFile.ensureDir()
        val module = ApkModule.loadApkFile(apk)
        try {
            newBytes.forEach { (name, bytes) ->
                module.zipEntryMap.remove(name)
                module.zipEntryMap.add(ByteInputSource(bytes, name))
            }
            module.writeApk(unsigned)
        } finally {
            runCatching { module.close() }
        }
        listener.onPercent(80)
        try {
            ApkSigning.sign(unsigned, outApk, signing, listener)
        } finally {
            unsigned.delete()
        }
        listener.onProgress("✔ APK con ${editedClass.size} clase(s) editada(s): ${outApk.absolutePath}")
        return outApk
    }

    override fun close() { dexes.clear() }

    companion object {
        fun open(apk: File, listener: ProgressListener = ProgressListener.NONE): DexEditor {
            listener.onProgress("Abriendo dex de ${apk.name}…")
            val map = LinkedHashMap<String, DexBackedDexFile>()
            ZipFile(apk).use { zip ->
                zip.entries().toList()
                    .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .sortedWith(compareBy({ it.name.length }, { it.name }))
                    .forEach { e ->
                        val bytes = zip.getInputStream(e).use { it.readBytes() }
                        map[e.name] = DexBackedDexFile.fromInputStream(null, bytes.inputStream().buffered())
                    }
            }
            if (map.isEmpty()) throw EngineException("Este APK no tiene dex")
            return DexEditor(apk, map)
        }

        fun typeToJava(type: String) = type.removePrefix("L").removeSuffix(";").replace('/', '.')
    }
}
