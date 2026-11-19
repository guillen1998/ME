package dev.apkforge.engine

import java.io.File

/**
 * DIAGNÓSTICO DE CIERRES: el mayor problema al modificar una app es que compila pero se CIERRA al
 * abrir (crash en ejecución) y no hay forma de saber por qué. Esto inyecta un capturador: una clase
 * que arranca con la app y, si algo la hace cerrarse, escribe el error (stack trace) en
 * /sdcard/ApkTools/crashes/<paquete>.txt. Luego ApkForge lee ese archivo y la IA ve EXACTAMENTE qué
 * clase/método reventó, lo cruza con lo que editó y lo arregla. Igual que un humano mirando el logcat.
 */
object CrashCatcher {

    const val CRASH_DIR = "/sdcard/ApkTools/crashes"
    private const val PKG = "dev/apkforge/crash"

    fun crashFile(packageName: String) = File(CRASH_DIR, "$packageName.txt")
    fun isInjected(project: File) = smaliFile(project)?.isFile == true

    private fun smaliDir(project: File): File {
        val smali = File(project, "smali")
        val existing = smali.listFiles { f -> f.isDirectory && f.name.startsWith("classes") }?.minByOrNull { it.name }
        return (existing ?: File(smali, "classes")).apply { mkdirs() }
    }
    private fun smaliFile(project: File): File? = File(project, "smali").listFiles { f -> f.isDirectory }
        .orEmpty().map { File(it, "$PKG/CrashProvider.smali") }.firstOrNull { it.isFile }

    /** Inyecta el capturador en el proyecto (smali + provider en el manifest + permiso). */
    fun inject(project: File, listener: ProgressListener = ProgressListener.NONE): Boolean {
        if (isInjected(project)) { listener.onProgress("El capturador de cierres ya estaba puesto"); return false }
        val manifest = File(project, "AndroidManifest.xml")
        if (!manifest.isFile) throw EngineException("No hay AndroidManifest.xml")
        val pkg = Regex("""package="([^"]+)"""").find(manifest.readText())?.groupValues?.get(1)
            ?: ApkProject.readInfo(project).packageName ?: throw EngineException("No se pudo leer el paquete")
        val dir = File(smaliDir(project), PKG).apply { mkdirs() }
        File(dir, "CrashProvider.smali").writeText(providerSmali())
        patchManifest(manifest, pkg)
        ChangeLog.recordBinary(File(dir, "CrashProvider.smali"), "Capturador de cierres inyectado", 1, "App")
        listener.onProgress("🩹 Capturador de cierres inyectado. Exporta, instala, ábrela hasta que se cierre, y pulsa \"La app se cierra\".")
        return true
    }

    private fun patchManifest(manifest: File, pkg: String) {
        var t = manifest.readText()
        val authorities = "$pkg.apkforge.crash"
        if (!t.contains("dev.apkforge.crash.CrashProvider")) {
            val provider = "    <provider android:name=\"dev.apkforge.crash.CrashProvider\" " +
                "android:authorities=\"$authorities\" android:exported=\"false\" android:initOrder=\"2147483647\" />\n"
            t = if (t.contains("</application>")) t.replaceFirst("</application>", provider + "</application>")
            else throw EngineException("Manifest sin </application>")
        }
        if (!t.contains("android.permission.WRITE_EXTERNAL_STORAGE")) {
            val perm = "    <uses-permission android:name=\"android.permission.WRITE_EXTERNAL_STORAGE\" />\n"
            t = if (t.contains("<application")) t.replaceFirst("<application", perm + "    <application") else t.replaceFirst("</manifest>", perm + "</manifest>")
        }
        if (!t.contains("requestLegacyExternalStorage"))
            t = t.replaceFirst("<application ", "<application android:requestLegacyExternalStorage=\"true\" ")
        manifest.writeText(t)
    }

    /** Quita el capturador (para el APK final, si no lo quieres dentro). */
    fun remove(project: File) {
        File(project, "smali").listFiles { f -> f.isDirectory }.orEmpty().forEach { File(it, PKG).deleteRecursively() }
        val manifest = File(project, "AndroidManifest.xml")
        if (manifest.isFile) manifest.writeText(manifest.readText()
            .replace(Regex("""\s*<provider android:name="dev\.apkforge\.crash\.CrashProvider"[^/]*/>"""), ""))
    }

    /**
     * Prepara para la IA el informe del cierre: el stack trace + el archivo smali de cada clase que
     * aparece en él + lo que se editó desde la última exportación. Con esto la IA ubica y arregla.
     */
    fun diagnosis(project: File, crashText: String): String = buildString {
        appendLine("La app COMPILÓ pero se CIERRA al abrir. Este es el error en ejecución (logcat capturado por la app).")
        appendLine("Tu tarea: encontrar qué cambio lo causó y arreglarlo para que abra sin cerrarse.")
        appendLine(); appendLine("=== ERROR / STACK TRACE ==="); appendLine(crashText.take(6000))
        // clases propias que aparecen en el stack trace → su archivo smali
        val classes = Regex("""at ([\w.$]+)\.[\w<>$]+\(""").findAll(crashText).map { it.groupValues[1] }.distinct()
            .filter { c -> ProjectReader.LIB.none { c.replace('.', '/').contains(it.trim('/')) } }
        val smaliRoot = File(project, "smali")
        val located = classes.mapNotNull { c ->
            val rel = c.substringBefore('$').replace('.', '/') + ".smali"
            smaliRoot.walkTopDown().firstOrNull { it.isFile && it.path.endsWith("/$rel") }?.let { c to it.relativeTo(project).path }
        }.take(8).toList()
        if (located.isNotEmpty()) {
            appendLine(); appendLine("CLASES DE LA APP EN EL ERROR (empieza por aquí, con read_file):")
            located.forEach { (c, p) -> appendLine("  • $c  →  $p") }
        }
        // causa raíz típica: NoSuchMethod/NoClassDef/VerifyError tras un cambio de smali
        val hint = when {
            crashText.contains("NoSuchMethodError") || crashText.contains("NoSuchFieldError") -> "Pista: NoSuchMethod/Field = borraste o cambiaste la firma de un método/campo que otro código usa. Revisa tu último cambio."
            crashText.contains("VerifyError") -> "Pista: VerifyError = el smali quedó inconsistente (registros, tipos o .locals mal). Revisa el método que editaste."
            crashText.contains("ClassNotFoundException") || crashText.contains("NoClassDefFoundError") -> "Pista: falta una clase (¿la borraste o renombraste mal?)."
            crashText.contains("NullPointerException") -> "Pista: algo quedó null tras el cambio (¿quitaste una inicialización?)."
            crashText.contains("ExceptionInInitializerError") || crashText.contains("<clinit>") -> "Pista: falló la inicialización estática (<clinit>) de una clase que tocaste."
            else -> ""
        }
        if (hint.isNotEmpty()) { appendLine(); appendLine(hint) }
        val edited = ChangeLog.sinceLastExport(project).map { it.file }.distinct()
        if (edited.isNotEmpty()) {
            appendLine(); appendLine("ARCHIVOS QUE SE EDITARON ANTES DE EXPORTAR (sospechosos):")
            edited.take(15).forEach { appendLine("  • $it") }
        }
        appendLine(); append("Localiza la causa, corrígela (replace_lines) y dime en una frase qué estaba mal.")
    }

    // ───────────── smali del capturador ─────────────

    private fun providerSmali(): String = """
        .class public Ldev/apkforge/crash/CrashProvider;
        .super Landroid/content/ContentProvider;
        .implements Ljava/lang/Thread${'$'}UncaughtExceptionHandler;

        .field private h:Ljava/lang/Thread${'$'}UncaughtExceptionHandler;
        .field private p:Ljava/lang/String;

        .method public constructor <init>()V
            .locals 0
            invoke-direct {p0}, Landroid/content/ContentProvider;-><init>()V
            return-void
        .end method

        .method public onCreate()Z
            .locals 4
            invoke-static {}, Ljava/lang/Thread;->getDefaultUncaughtExceptionHandler()Ljava/lang/Thread${'$'}UncaughtExceptionHandler;
            move-result-object v0
            iput-object v0, p0, Ldev/apkforge/crash/CrashProvider;->h:Ljava/lang/Thread${'$'}UncaughtExceptionHandler;
            new-instance v0, Ljava/io/File;
            const-string v1, "$CRASH_DIR"
            invoke-direct {v0, v1}, Ljava/io/File;-><init>(Ljava/lang/String;)V
            invoke-virtual {v0}, Ljava/io/File;->mkdirs()Z
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            const-string v1, "$CRASH_DIR/"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {p0}, Ldev/apkforge/crash/CrashProvider;->getContext()Landroid/content/Context;
            move-result-object v1
            invoke-virtual {v1}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
            move-result-object v1
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            const-string v1, ".txt"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            iput-object v0, p0, Ldev/apkforge/crash/CrashProvider;->p:Ljava/lang/String;
            invoke-static {p0}, Ljava/lang/Thread;->setDefaultUncaughtExceptionHandler(Ljava/lang/Thread${'$'}UncaughtExceptionHandler;)V
            const/4 v0, 0x0
            return v0
        .end method

        .method public uncaughtException(Ljava/lang/Thread;Ljava/lang/Throwable;)V
            .locals 4
            :try_start_0
            new-instance v0, Ljava/lang/StringBuilder;
            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
            invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
            move-result-wide v1
            invoke-virtual {v0, v1, v2}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;
            const-string v1, "\n"
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-static {p2}, Landroid/util/Log;->getStackTraceString(Ljava/lang/Throwable;)Ljava/lang/String;
            move-result-object v1
            invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
            invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
            move-result-object v0
            new-instance v1, Ljava/io/FileOutputStream;
            iget-object v2, p0, Ldev/apkforge/crash/CrashProvider;->p:Ljava/lang/String;
            invoke-direct {v1, v2}, Ljava/io/FileOutputStream;-><init>(Ljava/lang/String;)V
            invoke-virtual {v0}, Ljava/lang/String;->getBytes()[B
            move-result-object v2
            invoke-virtual {v1, v2}, Ljava/io/FileOutputStream;->write([B)V
            invoke-virtual {v1}, Ljava/io/FileOutputStream;->close()V
            :try_end_0
            .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0
            goto :goto_0
            :catch_0
            move-exception v0
            :goto_0
            iget-object v0, p0, Ldev/apkforge/crash/CrashProvider;->h:Ljava/lang/Thread${'$'}UncaughtExceptionHandler;
            if-eqz v0, :cond_0
            invoke-interface {v0, p1, p2}, Ljava/lang/Thread${'$'}UncaughtExceptionHandler;->uncaughtException(Ljava/lang/Thread;Ljava/lang/Throwable;)V
            :cond_0
            return-void
        .end method

        .method public query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;
            .locals 1
            const/4 v0, 0x0
            return-object v0
        .end method

        .method public getType(Landroid/net/Uri;)Ljava/lang/String;
            .locals 1
            const/4 v0, 0x0
            return-object v0
        .end method

        .method public insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;
            .locals 1
            const/4 v0, 0x0
            return-object v0
        .end method

        .method public delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I
            .locals 1
            const/4 v0, 0x0
            return v0
        .end method

        .method public update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I
            .locals 1
            const/4 v0, 0x0
            return v0
        .end method
    """.trimIndent()
}
