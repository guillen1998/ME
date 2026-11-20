package dev.apkforge.app.core

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.apkforge.engine.JavaDecompilerOptions
import dev.apkforge.engine.SigningConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/** Hilos con pila grande: jadx y smali son muy recursivos y revientan con la pila por defecto (1 MB). */
object Workers {
    val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(3) { r ->
        Thread(null, r, "forge-worker", 256L * 1024 * 1024).apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/**
 * Carpetas de trabajo en /sdcard/ApkTools:
 *  - Proyectos:  APKs decompilados (y proyectos de Android Studio) que estás editando
 *  - Compilados: APKs nuevos ya editados, exportados y firmados
 *  - Extraidos:  APKs sacados de apps instaladas y archivos extraídos de un APK
 *  - Java:       código Java exportado
 */
object Workspace {
    val root: File get() = File(Environment.getExternalStorageDirectory(), "ApkTools")
    val projects get() = File(root, "Proyectos").also { it.mkdirs() }
    val builds get() = File(root, "Compilados").also { it.mkdirs() }
    val extracted get() = File(root, "Extraidos").also { it.mkdirs() }
    val java get() = File(root, "Java").also { it.mkdirs() }
    /** Proyectos para Android Studio en PC (código Java): no se compilan en el móvil. */
    val studio get() = File(projects, "AndroidStudio").also { it.mkdirs() }

    /**
     * APK de origen de una carpeta antigua de Android Studio: por el applicationId busca la app
     * instalada o un APK extraído con ese paquete.
     */
    fun findSourceApkForStudio(ctx: android.content.Context, studioDir: File, marker: String?): File? {
        marker?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }?.let { return it }
        val gradle = File(studioDir, "app/build.gradle.kts").takeIf { it.isFile }?.readText() ?: return null
        val pkg = Regex("applicationId\\s*=\\s*\"([^\"]+)\"").find(gradle)?.groupValues?.get(1) ?: return null
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir }.getOrNull()
            ?.let(::File)?.takeIf { it.canRead() }?.let { return it }
        return extracted.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "apk" }
            .firstOrNull { runCatching { dev.apkforge.engine.ApkInspector.inspect(it, verifySignature = false).packageName == pkg }.getOrDefault(false) }
    }

    /** Carpeta para decompilar este APK: nombre del paquete + versión (sin repetir). */
    fun newProjectDir(apk: File): File {
        val info = runCatching { dev.apkforge.engine.ApkInspector.inspect(apk, verifySignature = false) }.getOrNull()
        val base = safeName((info?.packageName ?: apk.nameWithoutExtension) + (info?.versionName?.let { "_v$it" } ?: ""))
        return unique(projects, base)
    }

    /** Crea la estructura al iniciar la app (si hay permiso). */
    fun init() = runCatching { projects; builds; extracted; java }

    /** Nombre sugerido para un APK exportado: app_v1.2_mod (sin repetir). */
    fun exportName(base: String) = safeName(base.removeSuffix(".apk").removeSuffix("-firmado").removeSuffix("-mod")) + "_mod"
    val storage: File get() = Environment.getExternalStorageDirectory()
    val downloads: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "app" }

    /** Devuelve un archivo/carpeta que no exista: nombre, nombre_2, nombre_3… */
    fun unique(dir: File, base: String, ext: String = ""): File {
        var f = File(dir, base + ext)
        var i = 2
        while (f.exists()) f = File(dir, "${base}_$i$ext").also { i++ }
        return f
    }
}

object Storage {
    fun hasAllFilesAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun allFilesAccessIntent(ctx: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        } else null
}

/** Ajustes persistentes. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("apkforge", Context.MODE_PRIVATE)

    var signMode: String
        get() = p.getString("signMode", "test")!!
        set(v) = p.edit().putString("signMode", v).apply()
    var keystorePath: String
        get() = p.getString("ksPath", "")!!
        set(v) = p.edit().putString("ksPath", v).apply()
    var keystorePass: String
        get() = p.getString("ksPass", "")!!
        set(v) = p.edit().putString("ksPass", v).apply()
    var keyAlias: String
        get() = p.getString("ksAlias", "")!!
        set(v) = p.edit().putString("ksAlias", v).apply()
    var keyPass: String
        get() = p.getString("ksKeyPass", "")!!
        set(v) = p.edit().putString("ksKeyPass", v).apply()

    var deobfuscate: Boolean
        get() = p.getBoolean("deobf", false)
        set(v) = p.edit().putBoolean("deobf", v).apply()
    var showInconsistent: Boolean
        get() = p.getBoolean("inconsistent", true)
        set(v) = p.edit().putBoolean("inconsistent", v).apply()
    var kotlinNames: Boolean
        get() = p.getBoolean("kotlinNames", true)
        set(v) = p.edit().putBoolean("kotlinNames", v).apply()
    var hideMetadata: Boolean
        get() = p.getBoolean("hideMeta", true)
        set(v) = p.edit().putBoolean("hideMeta", v).apply()

    var editorWrap: Boolean
        get() = p.getBoolean("wrap", false)
        set(v) = p.edit().putBoolean("wrap", v).apply()
    var editorFontSize: Float
        get() = p.getFloat("fontSize", 13f)
        set(v) = p.edit().putFloat("fontSize", v).apply()
    var lastDir: String
        get() = p.getString("lastDir", "")!!
        set(v) = p.edit().putString("lastDir", v).apply()
    var editorAiMode: Boolean
        get() = p.getBoolean("editorAi", false)
        set(v) = p.edit().putBoolean("editorAi", v).apply()
    /** Orden de archivos: DATE_DESC (por defecto), DATE_ASC, NAME_ASC, NAME_DESC, SIZE_DESC, SIZE_ASC, TYPE. */
    var sortMode: String
        get() = p.getString("sortMode", "DATE_DESC")!!
        set(v) = p.edit().putString("sortMode", v).apply()
    var foldersFirst: Boolean
        get() = p.getBoolean("foldersFirst", true)
        set(v) = p.edit().putBoolean("foldersFirst", v).apply()
    var showHidden: Boolean
        get() = p.getBoolean("hidden", false)
        set(v) = p.edit().putBoolean("hidden", v).apply()

    // ---- Agente IA (Claude) ----
    var apiKey: String
        get() = p.getString("apiKey", "")!!
        set(v) = p.edit().putString("apiKey", v).apply()
    var agentModel: String
        get() = p.getString("agentModel", "claude-opus-5-5")!!.let { if (it == "claude-opus-4-8") "claude-opus-5-5" else it }
        set(v) = p.edit().putString("agentModel", v).apply()

    /** Proveedor elegido: CLAUDE, OPENROUTER, GROQ, OLLAMA o CUSTOM. */
    var aiProvider: dev.apkforge.engine.AiProvider
        get() {
            val stored = runCatching { dev.apkforge.engine.AiProvider.valueOf(p.getString("aiProvider", "BUILTIN")!!) }
                .getOrDefault(dev.apkforge.engine.AiProvider.BUILTIN)
            // Sin configurar (falta la clave) -> la IA integrada, que funciona sin nada.
            return if (stored.needsKey && keyFor(stored).isBlank()) dev.apkforge.engine.AiProvider.BUILTIN else stored
        }
        set(v) = p.edit().putString("aiProvider", v.name).apply()

    fun keyFor(pr: dev.apkforge.engine.AiProvider): String =
        if (pr == dev.apkforge.engine.AiProvider.CLAUDE) apiKey else p.getString("key_${pr.name}", "")!!
    fun setKeyFor(pr: dev.apkforge.engine.AiProvider, v: String) =
        if (pr == dev.apkforge.engine.AiProvider.CLAUDE) apiKey = v else p.edit().putString("key_${pr.name}", v).apply()

    fun modelFor(pr: dev.apkforge.engine.AiProvider): String =
        if (pr == dev.apkforge.engine.AiProvider.CLAUDE) agentModel else p.getString("model_${pr.name}", pr.defaultModel)!!
    fun setModelFor(pr: dev.apkforge.engine.AiProvider, v: String) =
        if (pr == dev.apkforge.engine.AiProvider.CLAUDE) agentModel = v else p.edit().putString("model_${pr.name}", v).apply()

    fun urlFor(pr: dev.apkforge.engine.AiProvider): String = p.getString("url_${pr.name}", pr.baseUrl)!!
    fun setUrlFor(pr: dev.apkforge.engine.AiProvider, v: String) = p.edit().putString("url_${pr.name}", v).apply()

    // ---- Modelos de la IA integrada (activar/desactivar y añadir los tuyos) ----
    var disabledModels: Set<String>
        get() = p.getStringSet("disabledModels", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("disabledModels", v).apply()

    var customModels: List<dev.apkforge.engine.AiEndpoint>
        get() = runCatching {
            com.google.gson.Gson().fromJson(p.getString("customModels", "[]"), Array<dev.apkforge.engine.AiEndpoint>::class.java).toList()
        }.getOrDefault(emptyList())
        set(v) = p.edit().putString("customModels", com.google.gson.Gson().toJson(v)).apply()

    /** Cadena final de la IA integrada: tus modelos añadidos primero + los integrados activos. */
    fun builtinEndpoints(): List<dev.apkforge.engine.AiEndpoint> {
        // Si guardaste una clave gratis (Gemini, OpenRouter, Groq…), SE SUMA al equipo integrado.
        // OpenRouter aporta VARIOS modelos fuertes de golpe (no solo uno) para trabajar con muchos.
        val keyed = dev.apkforge.engine.AiProvider.entries.filter {
            it != dev.apkforge.engine.AiProvider.BUILTIN && it != dev.apkforge.engine.AiProvider.CLAUDE && it.needsKey && keyFor(it).isNotBlank()
        }.flatMap {
            if (it == dev.apkforge.engine.AiProvider.OPENROUTER)
                dev.apkforge.engine.BuiltinAi.openRouterTeam(urlFor(it), keyFor(it), modelFor(it))
            else listOf(dev.apkforge.engine.AiEndpoint(urlFor(it), keyFor(it), modelFor(it), "${modelFor(it)} (${it.title.substringBefore(" ·")})"))
        }
        // El proveedor que TÚ seleccionas va PRIMERO (su modelo trabaja antes); detrás, el resto de tus
        // claves (Gemini, Groq…) y, al final, el equipo básico gratis. Así turna de potente a básico.
        val activeUrl = aiProvider.let { if (it != dev.apkforge.engine.AiProvider.BUILTIN && it != dev.apkforge.engine.AiProvider.CLAUDE && it.needsKey) urlFor(it) else "" }
        val keyedOrdered = keyed.sortedByDescending { it.baseUrl == activeUrl }
        val list = customModels.filter { it.label !in disabledModels } + keyedOrdered.filter { it.label !in disabledModels } +
            dev.apkforge.engine.BuiltinAi.endpoints.filter { it.label !in disabledModels }
        return list.ifEmpty { dev.apkforge.engine.BuiltinAi.endpoints }
    }

    /** ¿Está el proveedor actual listo para usarse? */
    fun aiReady(): Boolean {
        val pr = aiProvider
        if (pr == dev.apkforge.engine.AiProvider.BUILTIN) return true
        return (!pr.needsKey || keyFor(pr).isNotBlank()) &&
            (pr == dev.apkforge.engine.AiProvider.CLAUDE || urlFor(pr).isNotBlank()) && modelFor(pr).isNotBlank()
    }
    var agentEffort: String
        get() = p.getString("agentEffort", "high")!!
        set(v) = p.edit().putString("agentEffort", v).apply()
    /** Modelo que razona en el móvil (id de LocalReasoner.MODELS) y cuándo usarlo: always | offline | off. */
    var reasonerModel: String
        get() = p.getString("reasonerModel", "")!!
        set(v) = p.edit().putString("reasonerModel", v).apply()
    var reasonMode: String
        get() = p.getString("reasonMode", "always")!!
        set(v) { p.edit().putString("reasonMode", v).apply(); dev.apkforge.engine.MessagePreparer.localReasoning = v == "always" }

    /** Al exportar: inyectar el capturador de cierres para diagnosticar crashes en ejecución. */
    var crashCatcher: Boolean
        get() = p.getBoolean("crashCatcher", true)
        set(v) = p.edit().putBoolean("crashCatcher", v).apply()
    /** Al exportar: reparar errores (app + IA) y deshacer solo lo que no compile. */
    var autoRepair: Boolean
        get() = p.getBoolean("autoRepair", true)
        set(v) = p.edit().putBoolean("autoRepair", v).apply()
    var agentAllowEdits: Boolean
        get() = p.getBoolean("agentEdits", true)
        set(v) = p.edit().putBoolean("agentEdits", v).apply()

    /** Exporta un proyecto con el doctor de compilación (si está activado). */
    fun exportProject(dir: java.io.File, out: java.io.File, log: dev.apkforge.engine.ProgressListener): java.io.File {
        if (crashCatcher) runCatching { dev.apkforge.engine.CrashCatcher.inject(dir, log) }
        val opts = dev.apkforge.engine.BuildOptions(signing = signingConfig())
        return if (autoRepair) dev.apkforge.engine.BuildDoctor.buildRepairing(dir, out, opts, AiFactory.textClient(this), log)
        else dev.apkforge.engine.ApkProject.build(dir, out, opts, log)
    }

    fun signingConfig(): SigningConfig =
        if (signMode == "keystore" && keystorePath.isNotBlank()) SigningConfig.Keystore(
            file = File(keystorePath),
            storePassword = keystorePass,
            alias = keyAlias.ifBlank { null },
            keyPassword = keyPass.ifBlank { null },
        ) else SigningConfig.TestKey

    fun javaOptions() = JavaDecompilerOptions(
        deobfuscate = deobfuscate,
        showInconsistentCode = showInconsistent,
        kotlinNames = kotlinNames,
        hideKotlinMetadata = hideMetadata,
    )
}

object Installer {
    fun uriFor(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)

    fun install(ctx: Context, apk: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(ctx, apk), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    fun share(ctx: Context, file: File) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension == "apk") "application/vnd.android.package-archive" else "*/*"
            putExtra(Intent.EXTRA_STREAM, uriFor(ctx, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun Long.humanSize(): String {
    if (this < 1024) return "$this B"
    val kb = this / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024)
}

/** Ordena una lista de archivos según los Ajustes (por defecto: carpetas primero, lo más reciente arriba). */
object FileSort {
    val modes = listOf(
        "DATE_DESC" to "Fecha: más reciente primero",
        "DATE_ASC" to "Fecha: más antiguo primero",
        "NAME_ASC" to "Nombre: A → Z",
        "NAME_DESC" to "Nombre: Z → A",
        "SIZE_DESC" to "Tamaño: más grande primero",
        "SIZE_ASC" to "Tamaño: más pequeño primero",
        "TYPE" to "Tipo (extensión)",
    )

    fun sort(files: List<File>, mode: String, foldersFirst: Boolean): List<File> {
        val byName = compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val inner: Comparator<File> = when (mode) {
            "DATE_ASC" -> compareBy<File> { it.lastModified() }.then(byName)
            "NAME_ASC" -> byName
            "NAME_DESC" -> byName.reversed()
            "SIZE_DESC" -> compareByDescending<File> { if (it.isDirectory) 0L else it.length() }.then(byName)
            "SIZE_ASC" -> compareBy<File> { if (it.isDirectory) 0L else it.length() }.then(byName)
            "TYPE" -> compareBy<File, String>(String.CASE_INSENSITIVE_ORDER) { it.extension }.then(byName)
            else -> compareByDescending<File> { it.lastModified() }.then(byName) // DATE_DESC
        }
        // Leemos la fecha una sola vez por archivo (en la sdcard cada lectura es lenta).
        val dates = HashMap<File, Long>(files.size)
        files.forEach { dates[it] = it.lastModified() }
        val cmp = when (mode) {
            "DATE_DESC" -> compareByDescending<File> { dates[it] }.then(byName)
            "DATE_ASC" -> compareBy<File> { dates[it] }.then(byName)
            else -> inner
        }
        return if (foldersFirst) files.sortedWith(compareBy<File> { !it.isDirectory }.then(cmp)) else files.sortedWith(cmp)
    }
}
