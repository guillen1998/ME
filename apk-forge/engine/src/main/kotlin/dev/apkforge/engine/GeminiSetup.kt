package dev.apkforge.engine

/** Ayuda a conectar Gemini: prueba varios modelos y devuelve el que funciona o el error real de Google. */
object GeminiSetup {
    /** Devuelve (modeloQueFunciona?, erroresSiFalla). */
    fun probe(baseUrl: String, apiKey: String, models: List<String>): Pair<String?, String> =
        OpenAiHttp.probe(baseUrl, apiKey, models)
}
