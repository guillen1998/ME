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

    override fun decodeDex(source: DexFileInputSource, mainDir: File) {
        val name = source.simpleNameWoExtension // classes, classes2...
        listener.onProgress("Desensamblando ${source.name} a smali…")
        val bytes = source.openStream().use { it.readBytes() }

        // Guardamos el dex original: si el usuario no toca ese smali, se reutiliza tal cual.
        val origDir = File(mainDir, ORIG_DEX_DIR).ensureDir()
        File(origDir, "$name.dex").writeBytes(bytes)

        val dexFile = DexBackedDexFile(Opcodes.getDefault(), bytes)
        val options = BaksmaliOptions().apply {
            apiLevel = 34
            debugInfo = true
            parameterRegisters = true
            localsDirective = true
            sequentialLabels = true
            accessorComments = true
            implicitReferences = false
            this.resourceIds = HashMap(this@BaksmaliDexDecoder.resourceIds)
        }
        val out = File(mainDir, "$SMALI_DIR/$name").ensureDir()
        val ok = Baksmali.disassembleDexFile(dexFile, out, Runtime.getRuntime().availableProcessors(), options)
        if (!ok) listener.onProgress("⚠ Algunas clases de ${source.name} no se pudieron desensamblar")
        File(origDir, "$name.fp").writeText(fingerprint(out))
    }
}

/** smali -> dex con smali. Reutiliza el dex original de las carpetas sin cambios. */
internal class SmaliDexEncoder(
    private val apiLevel: Int,
    private val forceRebuild: Boolean,
    private val listener: ProgressListener,
) : DexEncoder {

    override fun buildDexFiles(encoder: ApkModuleEncoder, mainDir: File): List<InputSource> {
        val smaliRoot = File(mainDir, SMALI_DIR)
        if (!smaliRoot.isDirectory) return encoder.rawDexEncoder.buildDexFiles(encoder, mainDir)

        val dirs = smaliRoot.listFiles { f -> f.isDirectory }.orEmpty()
            .sortedWith(compareBy({ InputSource.getDexNumber(it.name + ".dex") }, { it.name }))
        return dirs.map { dir ->
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
