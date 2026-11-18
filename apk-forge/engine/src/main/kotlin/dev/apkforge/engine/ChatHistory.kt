package dev.apkforge.engine

import com.google.gson.GsonBuilder
import java.io.File

/**
 * Historial del chat con el agente guardado en disco: si se cierra la app o se apaga el móvil,
 * al volver la conversación sigue ahí y se puede REANUDAR (la IA recibe un resumen de lo hablado
 * y de los cambios ya hechos, y continúa donde quedó).
 */
object ChatHistory {
    /** kind: user | assistant | tool | info */
    data class Msg(
        val kind: String, val text: String = "", val tool: String = "", val summary: String = "",
        val result: String? = null, val error: Boolean = false, val images: Int = 0,
    )

    data class Chat(val title: String = "", val running: Boolean = false, val updated: Long = 0, val messages: List<Msg> = emptyList())

    private val gson = GsonBuilder().create()

    /** Dónde se guarda: dentro del proyecto si lo es, si no en la carpeta privada de la app. */
    fun fileFor(target: File, preset: String, appDir: File): File {
        val project = if (ApkProject.isProject(target)) target else null
        return if (project != null) File(project, "$META_DIR/chats/$preset.json")
        else File(appDir, "chats/${Integer.toHexString(target.absolutePath.hashCode())}_${target.name.take(40).replace(Regex("[^\\w.-]"), "_")}/$preset.json")
    }

    @Synchronized
    fun save(file: File, chat: Chat) = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        // resultados de herramientas recortados para que el archivo no crezca sin límite
        val slim = chat.copy(messages = chat.messages.takeLast(600).map { it.copy(result = it.result?.take(2000), text = it.text.take(20000)) })
        tmp.writeText(gson.toJson(slim))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }   // escritura atómica: no se corrompe si se apaga a mitad
    }

    fun load(file: File): Chat? = runCatching { file.takeIf { it.isFile }?.let { gson.fromJson(it.readText(), Chat::class.java) } }.getOrNull()
        ?.let { c -> c.copy(messages = c.messages.orEmpty()) }

    /** Guarda la conversación actual como antigua (al empezar una nueva) y deja la actual vacía. */
    fun archive(file: File) {
        if (!file.isFile) return
        val c = load(file)
        if (c == null || c.messages.isEmpty()) { file.delete(); return }
        file.renameTo(File(file.parentFile, file.nameWithoutExtension + "-" + (c.updated.takeIf { it > 0 } ?: System.currentTimeMillis()) + ".json"))
    }

    /** Conversaciones anteriores (más reciente primero). */
    fun archives(file: File): List<Pair<File, Chat>> =
        file.parentFile?.listFiles { f -> f.name.startsWith(file.nameWithoutExtension + "-") && f.extension == "json" }.orEmpty()
            .mapNotNull { f -> load(f)?.let { f to it } }.sortedByDescending { it.second.updated }

    /** Lo que recibe la IA al reanudar: resumen de la conversación + pista para continuar. */
    fun resumeContext(messages: List<Msg>, interrupted: Boolean, maxChars: Int = 9000): String {
        val lines = messages.mapNotNull { m ->
            when (m.kind) {
                "user" -> "USUARIO: " + m.text.take(1500)
                "assistant" -> "IA: " + m.text.take(1200)
                "tool" -> "  [herramienta ${m.tool} ${m.summary.take(120)}${if (m.error) " → error" else ""}]"
                "info" -> if (m.error) "  [error: ${m.text.take(200)}]" else null
                else -> null
            }
        }
        val sb = StringBuilder()
        for (l in lines.asReversed()) { if (sb.length + l.length > maxChars) break; sb.insert(0, l + "\n") }
        val lastUser = messages.lastOrNull { it.kind == "user" }?.text?.take(1500).orEmpty()
        return buildString {
            appendLine("🔄 REANUDAR SESIÓN. La app se cerró${if (interrupted) " mientras trabajabas" else ""} y perdiste la memoria de la conversación.")
            appendLine("Esto es lo que se habló y se hizo (lo más reciente al final):")
            appendLine(); append(sb); appendLine()
            appendLine("Primero consulta list_changes para ver qué archivos ya se editaron (no repitas ni deshagas esos cambios).")
            append(if (interrupted) "Luego CONTINÚA la tarea que quedó a medias: “$lastUser”."
                   else "Luego di en una frase en qué quedó la última tarea (“$lastUser”) y sigue si faltaba algo.")
        }
    }
}
