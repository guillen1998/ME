package dev.apkforge.engine

import java.io.File

/**
 * Entiende lo que el usuario quiere decir con palabras normales y le prepara a la IA una tarea
 * concreta, para que no haga falta darle instrucciones técnicas. La app "piensa como un humano"
 * sobre la intención; la IA ejecuta.
 */
object RequestIntent {

    /** Devuelve un preámbulo con la tarea ya planteada, o null si el mensaje no encaja en un patrón. */
    fun detect(project: File, text: String): String? {
        val t = norm(text)
        return when {
            crashes(t) -> crashTask(project)
            exportFails(t) -> "El usuario dice que la EXPORTACIÓN falla. Revisa list_changes y check_integrity; si un archivo no compila, arréglalo (replace_lines) o avísale."
            noChange(t) -> "El usuario dice que su cambio NO se refleja en el APK. Comprueba con list_changes que editaste archivos del proyecto (smali/ o resources/), no android-studio/. Si editaste lo correcto, puede ser caché: dile que exporte de nuevo. Si no, haz el cambio en el archivo correcto."
            else -> null
        }
    }

    /**
     * ¿Es un mensaje SIMPLE/conversacional (saludo, pregunta sobre la IA, agradecimiento)? Entonces
     * no hace falta analizar el proyecto: se responde al instante. Solo lo claramente conversacional.
     */
    fun isChatty(text: String): Boolean {
        val t = norm(text).trim()
        if (t.length > 80) return false
        val metas = listOf("que modelo", "cual modelo", "quien eres", "que eres", "que ia eres", "como te llamas",
            "que puedes hacer", "que sabes hacer", "para que sirves", "hola", "buenas", "buenos dias", "buenas tardes",
            "buenas noches", "gracias", "ok", "okay", "gracias!", "como estas", "quien te creo", "que version")
        if (metas.any { t == it || t.startsWith("$it ") || t.startsWith("$it,") || t.startsWith("$it?") || t == "$it?" }) return true
        // preguntas muy cortas que no nombran el proyecto/código
        val codey = listOf("smali", "clase", "metodo", "archivo", "layout", "xml", "color", "boton", "pantalla", "canal",
            "firma", "export", "compil", "crash", "cierra", "funcion", "codigo", "recurso", "icono", "texto", "modif", "cambia", "edita", "busca", "arregla")
        return t.endsWith("?") && t.length <= 40 && codey.none { t.contains(it) }
    }

    /** Estado visible mientras la app razona sobre tu mensaje (para que veas que ENTENDIÓ). */
    fun statusFor(text: String): String {
        val t = norm(text)
        return when {
            crashes(t) -> "🧠 Entendí: la app se cierra. Reviso tus últimos cambios y busco el log del cierre…"
            exportFails(t) -> "🧠 Entendí: falla la exportación. Reviso qué no compila…"
            noChange(t) -> "🧠 Entendí: tu cambio no se refleja. Reviso qué se editó…"
            else -> "La app prepara el contexto…"
        }
    }

    private fun crashes(t: String) = listOf(
        "se cierra", "se cierre", "se sale", "se cerro", "cierra sola", "no abre", "no abra", "ya no abre", "deja de abrir",
        "dejo de abrir", "no inicia", "no arranca", "crashe", "crash", "peta", "se rompe", "se rompio", "la rompiste",
        "lo rompiste", "dejo de funcionar", "ya no funciona", "no funciona mas", "se queda en negro", "pantalla negra",
        "ultima actualizacion", "ultimo cambio", "el cambio que hiciste"
    ).any { t.contains(it) }

    private fun exportFails(t: String) = (t.contains("export") || t.contains("compil")) && (t.contains("falla") || t.contains("error") || t.contains("no se puede") || t.contains("no deja"))

    private fun noChange(t: String) = (t.contains("no cambia") || t.contains("no cambio") || t.contains("sigue igual") || t.contains("mismo apk") || t.contains("no se refleja") || t.contains("no se aplica"))

    /** La app arma TODO el contexto del cierre: log del crash (si existe) + qué se editó + cómo ubicarlo. */
    private fun crashTask(project: File): String = buildString {
        appendLine("⚠ EL USUARIO DICE QUE LA APP SE CIERRA / NO ABRE DESPUÉS DE TUS CAMBIOS.")
        appendLine("No esperes instrucciones técnicas: TÚ debes diagnosticar y arreglar la regresión que causaron los últimos cambios.")
        val pkg = runCatching { ApkProject.readInfo(project).packageName }.getOrNull()
        val crash = pkg?.let { CrashCatcher.crashFile(it) }?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        if (!crash.isNullOrBlank()) {
            appendLine(); appendLine("La app capturó el error real del cierre:")
            append(CrashCatcher.diagnosis(project, crash))
        } else {
            appendLine()
            appendLine("No hay log del cierre todavía" + (if (pkg != null && CrashCatcher.isInjected(project)) ", aunque el capturador está puesto (pide al usuario que abra la app hasta que se cierre y vuelva)." else "."))
            appendLine("Haz el diagnóstico con lo que tienes:")
            appendLine("1. list_changes (only_pending) para ver TODO lo que se editó desde la última exportación: eso es lo sospechoso.")
            appendLine("2. Revisa cada archivo editado con read_file: ¿borraste un método/campo que otro código usa? ¿quedó un .locals o registros mal? ¿quitaste una inicialización? ¿un <clinit> inconsistente?")
            appendLine("3. Si un cambio es dudoso, corrígelo (replace_lines) o propón deshacerlo.")
            appendLine("4. Recomiéndale activar el capturador de cierres (así el próximo cierre dirá la causa exacta).")
            val edited = ChangeLog.sinceLastExport(project).map { it.file }.distinct()
            if (edited.isNotEmpty()) { appendLine(); appendLine("Archivos editados desde la última exportación (empieza por aquí):"); edited.take(15).forEach { appendLine("  • $it") } }
        }
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
