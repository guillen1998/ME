package dev.apkforge.engine

import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleDecoder
import com.reandroid.apk.ApkModuleEncoder
import com.reandroid.apk.ApkModuleRawDecoder
import com.reandroid.apk.ApkModuleRawEncoder
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkModuleXmlEncoder
import java.io.File
import java.util.Properties

data class DecompileOptions(
    /** Recursos y AndroidManifest a XML legible. Si es false quedan binarios (más rápido). */
    val decodeResources: Boolean = true,
    /** dex a smali. Si es false se copian los .dex tal cual. */
    val decodeDex: Boolean = true,
    /** Borra la carpeta de salida si ya existe. */
    val overwrite: Boolean = true,
)

data class BuildOptions(
    /** Fuerza re-ensamblar todos los dex aunque no tengan cambios. */
    val forceRebuildDex: Boolean = false,
    /** Firmar automáticamente el APK resultante. */
    val sign: Boolean = true,
    val signing: SigningConfig = SigningConfig.TestKey,
)

/** Información guardada en .apkforge/project.properties. */
data class ProjectInfo(
    val dir: File,
    val sourceApk: String?,
    val packageName: String?,
    val minSdk: Int,
    val resourcesDecoded: Boolean,
    val dexDecoded: Boolean,
)

object ApkProject {

    private const val PROPS = "$META_DIR/project.properties"

    fun decompile(
        apk: File,
        outDir: File,
        options: DecompileOptions = DecompileOptions(),
        listener: ProgressListener = ProgressListener.NONE,
    ): ProjectInfo {
        if (outDir.exists()) {
            if (!options.overwrite) throw EngineException("La carpeta ya existe: ${outDir.absolutePath}")
            listener.onProgress("Limpiando carpeta de salida…")
            outDir.deleteRecursively()
        }
        outDir.ensureDir()

        listener.onProgress("Leyendo ${apk.name}…")
        val module = ApkModule.loadApkFile(logger(listener), apk)
        try {
            val manifest = module.androidManifest
            val minSdk = manifest?.minSdkVersion ?: 21

            val decoder: ApkModuleDecoder = if (options.decodeResources) {
                ApkModuleXmlDecoder(module)
            } else {
                ApkModuleRawDecoder(module)
            }
            decoder.setApkLogger(logger(listener))
            if (options.decodeDex) {
                decoder.setDexDecoder(BaksmaliDexDecoder(resourceIdMap(module), listener))
            }
            listener.onProgress(if (options.decodeResources) "Decodificando recursos y manifest a XML…" else "Extrayendo archivos…")
            decoder.decode(outDir)

            val info = ProjectInfo(
                dir = outDir,
                sourceApk = apk.absolutePath,
                packageName = manifest?.packageName,
                minSdk = minSdk,
                resourcesDecoded = options.decodeResources,
                dexDecoded = options.decodeDex,
            )
            writeInfo(info)
            listener.onProgress("✔ Decompilado en ${outDir.absolutePath}")
            return info
        } finally {
            runCatching { module.close() }
        }
    }

    fun isProject(dir: File) = File(dir, PROPS).isFile

    fun readInfo(dir: File): ProjectInfo {
        val f = File(dir, PROPS)
        if (!f.isFile) throw EngineException("No es un proyecto de ApkForge (falta $PROPS)")
        val p = Properties().apply { f.inputStream().use { load(it) } }
        return ProjectInfo(
            dir = dir,
            sourceApk = p.getProperty("sourceApk"),
            packageName = p.getProperty("packageName"),
            minSdk = p.getProperty("minSdk")?.toIntOrNull() ?: 21,
            resourcesDecoded = p.getProperty("resourcesDecoded") != "false",
            dexDecoded = p.getProperty("dexDecoded") != "false",
        )
    }

    private fun writeInfo(info: ProjectInfo) {
        val p = Properties()
        info.sourceApk?.let { p.setProperty("sourceApk", it) }
        info.packageName?.let { p.setProperty("packageName", it) }
        p.setProperty("minSdk", info.minSdk.toString())
        p.setProperty("resourcesDecoded", info.resourcesDecoded.toString())
        p.setProperty("dexDecoded", info.dexDecoded.toString())
        val f = File(info.dir, PROPS)
        f.parentFile.ensureDir()
        f.outputStream().use { p.store(it, "ApkForge project") }
    }

    /**
     * Compila la carpeta del proyecto a un APK. Si [BuildOptions.sign] es true, el resultado
     * queda firmado (v1+v2+v3) y alineado, listo para instalar.
     */
    fun build(
        projectDir: File,
        outApk: File,
        options: BuildOptions = BuildOptions(),
        listener: ProgressListener = ProgressListener.NONE,
    ): File {
        val info = readInfo(projectDir)
        val encoder: ApkModuleEncoder = if (info.resourcesDecoded) ApkModuleXmlEncoder() else ApkModuleRawEncoder()
        encoder.setApkLogger(logger(listener))
        encoder.setDexEncoder(SmaliDexEncoder(maxOf(info.minSdk, 15), options.forceRebuildDex, listener))

        listener.onProgress(if (info.resourcesDecoded) "Compilando recursos y manifest…" else "Empaquetando archivos…")
        encoder.scanDirectory(projectDir)
        val module = encoder.apkModule
        outApk.absoluteFile.parentFile.ensureDir()
        val unsigned = if (options.sign) File(outApk.absolutePath + ".unsigned.tmp") else outApk
        try {
            listener.onProgress("Escribiendo APK…")
            module.writeApk(unsigned)
        } finally {
            runCatching { module.close() }
        }
        if (options.sign) {
            try {
                ApkSigning.sign(unsigned, outApk, options.signing, listener)
            } finally {
                unsigned.delete()
            }
        }
        listener.onProgress("✔ APK generado: ${outApk.absolutePath} (${outApk.length() / 1024} KB)")
        return outApk
    }

    /** ID de recurso -> "tipo/nombre", para comentar las constantes en el smali. */
    private fun resourceIdMap(module: ApkModule): Map<Int, String> {
        val table = runCatching { module.tableBlock }.getOrNull() ?: return emptyMap()
        val map = HashMap<Int, String>()
        runCatching {
            for (pkg in table.listPackages()) {
                val it = pkg.resources
                while (it.hasNext()) {
                    val e = it.next()
                    val type = e.type ?: continue
                    val name = e.name ?: continue
                    map[e.resourceId] = "$type/$name"
                }
            }
        }
        return map
    }

    internal fun logger(listener: ProgressListener) = object : APKLogger {
        override fun logMessage(msg: String) = listener.onProgress(msg)
        override fun logError(msg: String, tr: Throwable?) =
            listener.onProgress("⚠ $msg" + (tr?.message?.let { ": $it" } ?: ""))
        override fun logVerbose(msg: String) {}
    }
}
