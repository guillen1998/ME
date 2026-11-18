package dev.apkforge.engine

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import com.reandroid.apk.ApkModuleEncoder
import com.reandroid.apk.DexDecoder
import com.reandroid.apk.DexEncoder
import com.reandroid.apk.DexFileInputSource
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.InputSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

private const val SMALI_DIR = "smali"
private const val ORIG_DEX_DIR = "$META_DIR/dex"

/**
 * dex -> smali con baksmali, configurado para que el resultado sea lo más legible posible:
 *  - conserva info de depuración (.line, nombres de variables locales con .local / .param)
 *  - etiquetas secuenciales (:cond_0, :goto_1 en vez de offsets)
 *  - comentarios en accesores sintéticos
 *  - comentarios con el nombre del recurso junto a cada ID (0x7f0a0012 -> # @id/boton)
 */
internal class BaksmaliDexDecoder(
    private val resourceIds: Map<Int, String>,
    private val listener: ProgressListener,
) : DexDecoder {

    private class Job(val name: String, val bytes: ByteArray, val classes: Int, val out: File, val origDir: File)
    private val jobs = mutableListOf<Job>()

    /**
     * ARSCLib nos va pasando los dex de uno en uno; aquí solo los ponemos en cola. Luego
     * [runAll] los desensambla EN PARALELO (varios dex a la vez) con porcentaje real.
     */
    override fun decodeDex(source: DexFileInputSource, mainDir: File) {
        val name = source.simpleNameWoExtension // classes, classes2...
        val bytes = source.openStream().use { it.readBytes() }
        // Guardamos el dex original: si el usuario no toca ese smali, se reutiliza tal cual.
        val origDir = File(mainDir, ORIG_DEX_DIR).ensureDir()
        File(origDir, "$name.dex").writeBytes(bytes)
        jobs += Job(name, bytes, classCount(bytes), File(mainDir, "$SMALI_DIR/$name").ensureDir(), origDir)
    }

    fun hasJobs() = jobs.isNotEmpty()

    fun runAll(range: PercentRange) {
        if (jobs.isEmpty()) return
        val total = jobs.sumOf { it.classes }.coerceAtLeast(1)
        val cores = Runtime.getRuntime().availableProcessors()
        val parallel = minOf(jobs.size, if (cores >= 6) 3 else 2).coerceAtLeast(1)
        val threadsEach = (cores / parallel).coerceAtLeast(1)
        listener.onProgress("Desensamblando ${jobs.size} dex ($total clases) — $parallel en paralelo…")

        val pool = java.util.concurrent.Executors.newFixedThreadPool(parallel) { r ->
            Thread(null, r, "baksmali", 256L * 1024 * 1024).apply { isDaemon = true }
        }
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        // Monitor: cuenta los .smali ya escritos para dar un porcentaje real.
        val monitor = Thread {
            while (!done.get()) {
                val written = jobs.sumOf { countSmali(it.out) }
                range.at(written.toDouble() / total)
                runCatching { Thread.sleep(1200) }
            }
        }.apply { isDaemon = true; start() }

        try {
            val futures = jobs.map { job ->
                pool.submit<Unit> {
                    val dexFile = DexBackedDexFile(Opcodes.getDefault(), job.bytes)
                    val ok = Baksmali.disassembleDexFile(dexFile, job.out, threadsEach, options())
                    if (!ok) listener.onProgress("⚠ Algunas clases de ${job.name}.dex no se pudieron desensamblar")
                    job.origDir.resolve("${job.name}.fp").writeText(fingerprint(job.out))
                    listener.onProgress("✔ ${job.name}.dex → smali (${job.classes} clases)")
                }
            }
            futures.forEach { it.get() }
        } finally {
            done.set(true)
            monitor.interrupt()
            pool.shutdownNow()
            jobs.clear()
        }
        range.at(1.0)
    }

    private fun options() = BaksmaliOptions().apply {
        apiLevel = 34
        debugInfo = true
        parameterRegisters = true
        localsDirective = true
        sequentialLabels = true
        accessorComments = true
        implicitReferences = false
        this.resourceIds = HashMap(this@BaksmaliDexDecoder.resourceIds)
    }

    companion object {
        /** class_defs_size de la cabecera del dex (offset 0x60), sin parsear el archivo. */
        fun classCount(bytes: ByteArray): Int =
            if (bytes.size < 0x64) 0
            else (bytes[0x60].toInt() and 0xFF) or ((bytes[0x61].toInt() and 0xFF) shl 8) or
                ((bytes[0x62].toInt() and 0xFF) shl 16) or ((bytes[0x63].toInt() and 0xFF) shl 24)

        /** Cuenta .smali usando solo nombres (sin stat por archivo: rápido incluso en la sdcard). */
        fun countSmali(dir: File): Int {
            val names = dir.list() ?: return 0
            var n = 0
            for (name in names) {
                if (name.endsWith(".smali")) n++ else n += countSmali(File(dir, name))
            }
            return n
        }
    }
}

/** smali -> dex con smali. Reutiliza el dex original de las carpetas sin cambios. */
internal class SmaliDexEncoder(
    private val apiLevel: Int,
    private val forceRebuild: Boolean,
    private val listener: ProgressListener,
    private val range: PercentRange? = null,
) : DexEncoder {

    override fun buildDexFiles(encoder: ApkModuleEncoder, mainDir: File): List<InputSource> {
        val smaliRoot = File(mainDir, SMALI_DIR)
        if (!smaliRoot.isDirectory) return encoder.rawDexEncoder.buildDexFiles(encoder, mainDir)

        val dirs = smaliRoot.listFiles { f -> f.isDirectory }.orEmpty()
            .sortedWith(compareBy({ InputSource.getDexNumber(it.name + ".dex") }, { it.name }))
        // Peso de cada dex = nº de archivos smali (los que no cambian casi no cuentan).
        val weights = dirs.associateWith { BaksmaliDexDecoder.countSmali(it).coerceAtLeast(1) }
        val total = weights.values.sum().toDouble()
        var acc = 0.0
        return dirs.map { dir ->
            range?.at(acc / total)
            acc += weights.getValue(dir)
            val dexName = "${dir.name}.dex"
            val orig = File(mainDir, "$ORIG_DEX_DIR/${dir.name}.dex")
            val fp = File(mainDir, "$ORIG_DEX_DIR/${dir.name}.fp")
            val unchanged = !forceRebuild && orig.isFile && fp.isFile && fp.readText() == fingerprint(dir)
            val bytes = if (unchanged) {
                listener.onProgress("$dexName sin cambios → se reutiliza el original")
                orig.readBytes()
            } else {
                listener.onProgress("Ensamblando ${dir.name} → $dexName…")
                assemble(dir)
            }
            range?.at(acc / total)
            ByteInputSource(bytes, dexName)
        }
    }

    private fun assemble(dir: File): ByteArray {
        val tmp = File.createTempFile("apkforge", ".dex")
        try {
            val options = SmaliOptions().apply {
                apiLevel = this@SmaliDexEncoder.apiLevel
                jobs = Runtime.getRuntime().availableProcessors()
                outputDexFile = tmp.absolutePath
                verboseErrors = false
            }
            val (ok, errors) = captureStderr { Smali.assemble(options, listOf(dir.absolutePath)) }
            if (!ok) throw EngineException("Errores de smali en ${dir.name}:\n" + errors.ifBlank { "(sin detalle)" })
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }
}

private val stderrLock = Any()

/** smali reporta los errores por System.err; los capturamos para mostrarlos en la app. */
internal fun <T> captureStderr(block: () -> T): Pair<T, String> = synchronized(stderrLock) {
    val old = System.err
    val buf = ByteArrayOutputStream()
    System.setErr(PrintStream(buf, true, "UTF-8"))
    try {
        block() to buf.toString("UTF-8")
    } finally {
        System.setErr(old)
    }
}
