package dev.apkforge.app

import android.app.Application
import dev.apkforge.app.core.Prefs
import dev.apkforge.app.ui.editor.CodeLanguages

class ApkForgeApp : Application() {
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        // Carga gramáticas y tema del editor en segundo plano para que abrir archivos sea instantáneo.
        Thread { runCatching { CodeLanguages.init(this) } }.start()
    }

    companion object {
        lateinit var instance: ApkForgeApp
            private set
        val prefs get() = instance.prefs
    }
}
