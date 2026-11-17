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

/** Carpetas de trabajo en /sdcard/ApkForge. */
object Workspace {
    val root: File get() = File(Environment.getExternalStorageDirectory(), "ApkForge")
    val projects get() = File(root, "projects").also { it.mkdirs() }
    val builds get() = File(root, "builds").also { it.mkdirs() }
    val extracted get() = File(root, "extracted").also { it.mkdirs() }
    val java get() = File(root, "java").also { it.mkdirs() }
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
    var showHidden: Boolean
        get() = p.getBoolean("hidden", false)
        set(v) = p.edit().putBoolean("hidden", v).apply()

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
