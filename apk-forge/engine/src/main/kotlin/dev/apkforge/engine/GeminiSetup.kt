package dev.apkforge.engine

/** Ayuda a conectar Gemini: prueba varios modelos y devuelve el que funciona o el error real de Google. */
object GeminiSetup {
    /** Devuelve (modeloQueFunciona?, erroresSiFalla). */
    fun probe(baseUrl: String, apiKey: String, models: List<String>, onTry: (String) -> Unit = {}): Pair<String?, String> =
        OpenAiHttp.probe(baseUrl, apiKey, models, onTry)
}
