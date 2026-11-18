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
        // Motor neuronal local: lo aprendido se guarda en la app y sirve para todos los proyectos
        val neural = java.io.File(filesDir, "neural")
        dev.apkforge.engine.NeuralEngine.shared = dev.apkforge.engine.NeuralEngine(java.io.File(neural, "model.json"))
        dev.apkforge.engine.NeuralBrain.storeDir = neural
        // La red (MiniLM) viene dentro del APK; se copia una vez a la memoria interna y se carga en la
        // GPU/NPU o la CPU (lo que sea más rápido en este móvil) la primera vez que se necesita.
        dev.apkforge.engine.NeuralBrain.modelLoader = {
            val model = java.io.File(neural, "minilm.onnx")
            if (!model.isFile || model.length() < 1_000_000) {
                neural.mkdirs()
                assets.open("neural/minilm.onnx").use { i -> model.outputStream().use { i.copyTo(it) } }
            }
            val vocab = assets.open("neural/vocab.txt").bufferedReader().readLines()
            dev.apkforge.engine.OnnxEmbedder.load(model, vocab)
        }
        // Modelo que RAZONA en el móvil (si lo descargaste en Ajustes → Motor neuronal)
        dev.apkforge.engine.NeuralBrain.reasonerLoader = { dev.apkforge.app.core.ReasonerModels.load() }
        dev.apkforge.engine.MessagePreparer.localReasoning = prefs.reasonMode == "always"
        // Carga gramáticas y tema del editor en segundo plano para que abrir archivos sea instantáneo.
        Thread { runCatching { CodeLanguages.init(this) } }.start()
    }

    companion object {
        lateinit var instance: ApkForgeApp
            private set
        val prefs get() = instance.prefs
    }
}
