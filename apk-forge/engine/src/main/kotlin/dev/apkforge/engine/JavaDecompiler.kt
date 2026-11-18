package dev.apkforge.engine

import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import jadx.api.CommentsLevel
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.plugins.JadxPlugin
import jadx.api.plugins.loader.JadxPluginLoader
import jadx.plugins.input.dex.DexInputPlugin
import java.io.Closeable
import java.io.File

data class JavaDecompilerOptions(
    /** Renombra identificadores ofuscados (a, b, c…) a nombres únicos y legibles. */
    val deobfuscate: Boolean = false,
    /** Muestra el código aunque jadx no lo pueda reconstruir al 100 % (en vez de “dump skipped”). */
    val showInconsistentCode: Boolean = true,
    /** Sustituye 0x7f… por R.string.nombre, R.id.boton, etc. */
    val replaceConstants: Boolean = true,
    /** Usa metadatos de Kotlin para dar nombres reales a las variables. */
    val kotlinNames: Boolean = true,
    /** Oculta el bloque @Metadata(d1 = {...}) de Kotlin, que es ruido ilegible. */
    val hideKotlinMetadata: Boolean = true,
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
)

/** Resultado de la búsqueda de clases. */
data class ClassEntry(val fullName: String, val rawName: String, val packageName: String, val simpleName: String)

/**
 * Sesión de decompilación a Java (jadx). Se carga una vez y decompila clase por clase
 * bajo demanda, para que funcione fluido en el móvil incluso con APKs grandes.
 */
class JavaDecompiler private constructor(
    private val jadx: JadxDecompiler,
    private val hideKotlinMetadata: Boolean,
) : Closeable {

    val classes: List<ClassEntry> by lazy {
        jadx.classes.map { ClassEntry(it.fullName, it.rawName, it.`package`, it.name) }.sortedBy { it.fullName }
    }

    val errorCount: Int get() = jadx.errorsCount

    private fun find(fullName: String): JavaClass =
        jadx.searchJavaClassByAliasFullName(fullName)
            ?: jadx.searchJavaClassByOrigFullName(fullName)
            ?: jadx.classes.firstOrNull { it.fullName == fullName || it.rawName == fullName }
            ?: throw EngineException("Clase no encontrada: $fullName")

    fun javaCode(fullName: String): String = cleanup(find(fullName).code)

    fun smaliCode(fullName: String): String = find(fullName).smali

    fun searchClasses(query: String, limit: Int = 500): List<ClassEntry> {
        val q = query.trim()
        if (q.isEmpty()) return classes.take(limit)
        return classes.asSequence().filter { it.fullName.contains(q, ignoreCase = true) || it.rawName.contains(q, true) }
            .take(limit).toList()
    }

    /**
     * Busca texto dentro del código Java decompilado (decompila las clases que haga falta).
     * Devuelve (clase, línea, texto de la línea).
     */
    fun searchCode(
        text: String,
        limit: Int = 300,
        packageFilter: String? = null,
        listener: ProgressListener = ProgressListener.NONE,
        isCancelled: () -> Boolean = { false },
        /** Cada coincidencia en cuanto aparece (resultados en vivo). */
        onHit: ((Triple<String, Int, String>) -> Unit)? = null,
    ): List<Triple<String, Int, String>> {
        val out = mutableListOf<Triple<String, Int, String>>()
        val list = jadx.classes.filter { packageFilter == null || it.fullName.startsWith(packageFilter) }
        for ((i, cls) in list.withIndex()) {
            if (isCancelled() || out.size >= limit) break
            if (i % 50 == 0) {
                listener.onProgress("Buscando… ${i}/${list.size}")
                listener.onPercent((i * 100L / list.size.coerceAtLeast(1)).toInt())
            }
            val code = runCatching { cls.code }.getOrNull() ?: continue
            code.lineSequence().forEachIndexed { n, line ->
                if (out.size < limit && line.contains(text, ignoreCase = true)) {
                    val hit = Triple(cls.fullName, n + 1, line.trim())
                    out += hit; onHit?.invoke(hit)
                }
            }
            cls.unload()
        }
        return out
    }

    /** Exporta todo el código fuente Java a una carpeta (solo código; los recursos van por ARSCLib). */
    fun exportAll(outDir: File, listener: ProgressListener = ProgressListener.NONE) {
        exportSourcesTo(File(outDir, "sources"), File(outDir, "resources"), listener)
        listener.onProgress("✔ Exportado en ${outDir.absolutePath}")
    }

    /** Escribe las fuentes Java en [srcDir]. Solo código: el 9-patch de jadx usa AWT (no hay en Android). */
    fun exportSourcesTo(srcDir: File, resDir: File = File(srcDir.parentFile, "res"), listener: ProgressListener = ProgressListener.NONE) {
        jadx.args.outDir = srcDir.parentFile
        jadx.args.setOutDirSrc(srcDir)
        jadx.args.setOutDirRes(resDir)
        jadx.args.setSkipResources(true)
        jadx.save(400) { done, total ->
            listener.onProgress("Exportando Java… $done/$total")
            if (total > 0) listener.onPercent(10 + (done * 90L / total).toInt())
        }
    }

    /** Firma legible de cada método: `tipo nombre(argumentos)`. */
    fun methods(fullName: String): List<String> = runCatching {
        find(fullName).methods.map { m ->
            val args = m.arguments.joinToString(", ") { it.toString().substringAfterLast('.') }
            "${m.returnType.toString().substringAfterLast('.')} ${m.name}($args)"
        }
    }.getOrDefault(emptyList())

    /** Referencias cruzadas: en qué otras clases se usa esta clase (quién depende de ella). */
    fun usedIn(fullName: String): List<String> = runCatching {
        find(fullName).useIn.map { it.fullName }.distinct().sorted()
    }.getOrDefault(emptyList())

    /** De qué otras clases depende esta (a qué código llama). */
    fun dependencies(fullName: String): List<String> = runCatching {
        find(fullName).dependencies.map { it.fullName }.distinct().sorted()
    }.getOrDefault(emptyList())

    override fun close() = jadx.close()

    private fun cleanup(code: String): String =
        if (!hideKotlinMetadata) code
        else code.lineSequence().filterNot { it.trimStart().startsWith("@Metadata(") }.joinToString("\n")

    companion object {
        /** Abre un .apk, .dex, .jar o .zip con dex dentro. */
        fun open(
            file: File,
            options: JavaDecompilerOptions = JavaDecompilerOptions(),
            listener: ProgressListener = ProgressListener.NONE,
        ): JavaDecompiler = open(listOf(file), options, listener)

        fun open(
            files: List<File>,
            options: JavaDecompilerOptions = JavaDecompilerOptions(),
            listener: ProgressListener = ProgressListener.NONE,
        ): JavaDecompiler {
            listener.onProgress("Cargando clases para Java…")
            val args = JadxArgs().apply {
                setInputFiles(files)
                setPluginLoader(object : JadxPluginLoader {
                    override fun load(): List<JadxPlugin> = listOf(DexInputPlugin())
                    override fun close() {}
                })
                setSkipResources(false)
                setShowInconsistentCode(options.showInconsistentCode)
                setDeobfuscationOn(options.deobfuscate)
                setDeobfuscationMinLength(3)
                setDeobfuscationMaxLength(64)
                setReplaceConsts(options.replaceConstants)
                setInlineAnonymousClasses(true)
                setInlineMethods(true)
                setMoveInnerClasses(true)
                setRestoreSwitchOverString(true)
                setExtractFinally(true)
                setDebugInfo(true)
                setUseImports(true)
                setEscapeUnicode(false)
                setRespectBytecodeAccModifiers(false)
                setCommentsLevel(CommentsLevel.INFO)
                setUseKotlinMethodsForVarNames(
                    if (options.kotlinNames) JadxArgs.UseKotlinMethodsForVarNames.APPLY
                    else JadxArgs.UseKotlinMethodsForVarNames.DISABLE
                )
                threadsCount = options.threads
                setSkipFilesSave(false)
                // Parser XML compatible con Android (el de jadx revienta en el móvil).
                security = AndroidJadxSecurity()
            }
            val jadx = JadxDecompiler(args)
            try {
                jadx.load()
            } catch (e: Throwable) {
                runCatching { jadx.close() }
                throw EngineException("No se pudo cargar para Java: ${describe(e)}", e)
            }
            listener.onProgress("✔ ${jadx.classes.size} clases cargadas")
            return JavaDecompiler(jadx, options.hideKotlinMetadata)
        }

        /**
         * Muestra en Java un archivo .smali (por ejemplo, el que estás editando) para ver
         * cómo queda tu cambio. Si se pasan más archivos smali se usan como contexto.
         */
        fun smaliToJava(smaliFile: File, apiLevel: Int = 26, options: JavaDecompilerOptions = JavaDecompilerOptions()): String {
            val tmp = File.createTempFile("apkforge-view", ".dex")
            try {
                val opts = SmaliOptions().apply {
                    this.apiLevel = apiLevel
                    outputDexFile = tmp.absolutePath
                    jobs = 1
                }
                val (ok, err) = captureStderr { Smali.assemble(opts, listOf(smaliFile.absolutePath)) }
                if (!ok) throw EngineException("El smali tiene errores:\n$err")
                open(tmp, options.copy(threads = 1)).use { d ->
                    return d.classes.joinToString("\n\n") { d.javaCode(it.fullName) }
                }
            } finally {
                tmp.delete()
            }
        }
    }
}
