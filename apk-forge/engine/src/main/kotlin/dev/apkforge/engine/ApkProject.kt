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
        listener.onPercent(1)
        val module = ApkModule.loadApkFile(logger(listener), apk)
        listener.onPercent(4)
        try {
            val manifest = module.androidManifest
            val minSdk = manifest?.minSdkVersion ?: 21

            val decoder: ApkModuleDecoder = if (options.decodeResources) {
                ApkModuleXmlDecoder(module)
            } else {
                ApkModuleRawDecoder(module)
            }
            decoder.setApkLogger(logger(listener))
            val dexDecoder = if (options.decodeDex) BaksmaliDexDecoder(resourceIdMap(module), listener) else null
            dexDecoder?.let { decoder.setDexDecoder(it) }
            listener.onProgress(if (options.decodeResources) "Decodificando recursos y manifest a XML…" else "Extrayendo archivos…")
            decoder.decode(outDir)
            // Recursos listos: el resto del avance es el dex (lo más pesado).
            listener.onPercent(if (dexDecoder?.hasJobs() == true) 22 else 97)
            dexDecoder?.runAll(PercentRange(listener, 22, 98))

            val info = ProjectInfo(
                dir = outDir,
                sourceApk = apk.absolutePath,
                packageName = manifest?.packageName,
                minSdk = minSdk,
                resourcesDecoded = options.decodeResources,
                dexDecoded = options.decodeDex,
            )
            writeInfo(info)
            listener.onPercent(100)
            listener.onProgress("✔ Decompilado en ${outDir.absolutePath}")
            return info
        } finally {
            runCatching { module.close() }
        }
    }

    fun isProject(dir: File) = File(dir, PROPS).isFile

    /** ¿Es una exportación para Android Studio (PC)? Devuelve el APK de origen si se conoce. */
    fun studioSource(dir: File): String? {
        if (isProject(dir) || !File(dir, "settings.gradle.kts").isFile || !File(dir, "app/src/main").isDirectory) return null
        return File(dir, "$META_DIR/studio.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("sourceApk=") }?.substringAfter('=') ?: ""
    }

    /** Proyectos de ApkForge dentro de [projectsDir], el más reciente primero. */
    fun listProjects(projectsDir: File): List<ProjectInfo> =
        projectsDir.listFiles { f -> f.isDirectory && isProject(f) }.orEmpty()
            .mapNotNull { runCatching { readInfo(it) }.getOrNull() }
            .sortedByDescending { File(it.dir, PROPS).lastModified().coerceAtLeast(it.dir.lastModified()) }

    /** Proyecto ya decompilado de este APK (mismo archivo de origen), si existe. */
    fun findProjectFor(apk: File, projectsDir: File): ProjectInfo? =
        listProjects(projectsDir).firstOrNull { it.sourceApk == apk.absolutePath }

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
        encoder.setDexEncoder(
            SmaliDexEncoder(maxOf(info.minSdk, 15), options.forceRebuildDex, listener, PercentRange(listener, 20, 82))
        )

        listener.onPercent(2)
        listener.onProgress(if (info.resourcesDecoded) "Compilando recursos y manifest…" else "Empaquetando archivos…")
        encoder.scanDirectory(projectDir)
        listener.onPercent(84)
        val module = encoder.apkModule
        outApk.absoluteFile.parentFile.ensureDir()
        val unsigned = if (options.sign) File(outApk.absolutePath + ".unsigned.tmp") else outApk
        try {
            listener.onProgress("Escribiendo APK…")
            module.writeApk(unsigned)
            listener.onPercent(if (options.sign) 90 else 100)
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
        listener.onPercent(100)
        listener.onProgress("✔ APK generado: ${outApk.absolutePath} (${outApk.length() / 1024} KB)")
        return outApk
    }

    /**
     * Reconstruye un proyecto de Android Studio ABRIBLE Y COMPILABLE combinando lo mejor de cada
     * herramienta: código Java con jadx y recursos+manifest limpios con ARSCLib (sin aapt, sin AWT).
     * Genera build.gradle.kts, settings.gradle.kts y gradle.properties.
     *
     * Aviso honesto: ninguna herramienta garantiza compilar a la primera una app de release
     * (R, librerías sin fuente, ofuscación). Es un punto de partida real, no magia.
     */
    fun exportStudioProject(
        apk: File,
        outDir: File,
        javaOptions: JavaDecompilerOptions = JavaDecompilerOptions(),
        listener: ProgressListener = ProgressListener.NONE,
    ): File {
        // 1) Proyecto EDITABLE normal (smali + recursos): se puede editar y exportar como APK en el móvil.
        listener.onProgress("Creando proyecto editable…")
        decompile(apk, outDir, DecompileOptions(), listener)

        // 2) Dentro, la versión para Android Studio (PC): Gradle + recursos + Java.
        val studioDir = File(outDir, "android-studio").apply { ensureDir() }
        val mainDir = File(studioDir, "app/src/main").apply { ensureDir() }
        File(outDir, "AndroidManifest.xml").takeIf { it.isFile }?.let { manifest ->
            // AGP 8 no admite package= en el manifest: lo movemos a namespace del build.gradle.
            val text = manifest.readText()
            val pkg = Regex("package=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            val cleaned = text
                .replace(Regex("\\s+package=\"[^\"]*\""), "")
                .replace(Regex("\\s+platformBuildVersion(?:Code|Name)=\"[^\"]*\""), "")
                .replace(Regex("\\s+compileSdkVersion(?:Codename)?=\"[^\"]*\""), "")
            File(mainDir, "AndroidManifest.xml").writeText(cleaned)
            File(studioDir, ".pkg").writeText(pkg ?: "")
        }
        val resSrc = File(outDir, "resources").listFiles()?.sortedBy { it.name }
            ?.firstNotNullOfOrNull { File(it, "res").takeIf { r -> r.isDirectory } }
        if (resSrc != null) {
            val resDst = File(mainDir, "res")
            resSrc.copyRecursively(resDst, overwrite = true)
            File(resDst, "values/public.xml").delete()
        }

        // 3) Código Java con jadx.
        listener.onProgress("Decompilando código a Java…")
        JavaDecompiler.open(apk, javaOptions, listener).use { it.exportSourcesTo(File(mainDir, "java"), File(studioDir, ".res-ignore"), listener) }
        File(studioDir, ".res-ignore").deleteRecursively()

        // 3) Scripts de Gradle.
        val info = runCatching { ApkInspector.inspect(apk, verifySignature = false) }.getOrNull()
        val pkg = File(studioDir, ".pkg").takeIf { it.isFile }?.readText()?.ifBlank { null }
            ?: info?.packageName ?: "com.decompiled.app"
        File(studioDir, ".pkg").delete()
        val min = info?.minSdk ?: 21
        val target = info?.targetSdk ?: 34
        writeGradleFiles(studioDir, pkg, min, target, info?.versionCode ?: 1, info?.versionName ?: "1.0")

        listener.onProgress("✔ Proyecto en ${outDir.absolutePath}: editable y exportable aquí; para PC abre la carpeta android-studio")
        return outDir
    }

    private fun writeGradleFiles(outDir: File, pkg: String, min: Int, target: Int, vCode: Int, vName: String) {
        File(outDir, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "${pkg.substringAfterLast('.')}"
            include(":app")
            """.trimIndent()
        )
        File(outDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application") version "8.7.3" apply false
                id("org.jetbrains.kotlin.android") version "2.0.21" apply false
            }
            """.trimIndent()
        )
        File(outDir, "gradle.properties").writeText(
            "org.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=true\nandroid.nonFinalResIds=false\n"
        )
        File(outDir, "gradle/wrapper").ensureDir()
        File(outDir, "gradle/wrapper/gradle-wrapper.properties").writeText(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip\n"
        )
        File(outDir, "app/build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }
            android {
                namespace = "$pkg"
                compileSdk = ${maxOf(target, 34)}
                defaultConfig {
                    applicationId = "$pkg"
                    minSdk = $min
                    targetSdk = $target
                    versionCode = $vCode
                    versionName = "$vName"
                }
                buildTypes { release { isMinifyEnabled = false } }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
            dependencies {
                // Añade aquí las librerías que la app usaba (AndroidX, Material, etc.).
                // jadx no puede saber las versiones exactas; revisa los imports sin resolver.
            }
            """.trimIndent()
        )
        File(outDir, "LEEME.txt").writeText(
            """
            Proyecto reconstruido por ApkForge (código: jadx · recursos: ARSCLib).

            Cómo abrirlo:
              1. Ábrelo en Android Studio (File > Open).
              2. Deja que sincronice Gradle.
              3. Añade en app/build.gradle.kts las dependencias que falten (AndroidX, Material,
                 Retrofit, etc.) según los imports que aparezcan sin resolver.

            Por qué puede no compilar a la primera (le pasa a CUALQUIER decompilador):
              - Las librerías externas vienen como código decompilado; conviene borrar esos
                paquetes y añadirlos como dependencias de Gradle.
              - Si la app estaba ofuscada, los nombres no se pueden recuperar.
              - Algunos recursos con IDs fijos pueden necesitar ajustes.
            """.trimIndent()
        )
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
