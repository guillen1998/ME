package dev.apkforge.engine

import com.reandroid.xml.XMLFactory
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Recursos NUEVOS en un proyecto decompilado (un color, un texto, un layout, una imagen, un
 * @+id…) necesitan un ID en public.xml o la compilación falla con "Undefined entry name".
 * Esto los detecta y les asigna el siguiente ID libre de su tipo, automáticamente, antes de exportar.
 */
object ResourceIds {

    private val VALUE_TAGS = mapOf(
        "string" to "string", "color" to "color", "dimen" to "dimen", "bool" to "bool", "integer" to "integer",
        "style" to "style", "attr" to "attr", "plurals" to "plurals", "drawable" to "drawable", "fraction" to "fraction",
        "string-array" to "array", "integer-array" to "array", "array" to "array",
    )

    data class Added(val type: String, val name: String, val id: String)

    /** Registra todo recurso que falte en public.xml. Devuelve lo añadido. */
    fun registerNew(projectDir: File, listener: ProgressListener = ProgressListener.NONE): List<Added> {
        val added = mutableListOf<Added>()
        File(projectDir, "resources").listFiles { f -> f.isDirectory }.orEmpty().forEach { pkg ->
            val res = File(pkg, "res").takeIf { it.isDirectory } ?: return@forEach
            val publicXml = File(res, "values/public.xml").takeIf { it.isFile } ?: return@forEach
            added += registerIn(res, publicXml)
        }
        if (added.isNotEmpty()) listener.onProgress("Registrados ${added.size} recurso(s) nuevo(s): " +
            added.take(6).joinToString { "${it.type}/${it.name}" } + if (added.size > 6) "…" else "")
        return added
    }

    private fun registerIn(res: File, publicXml: File): List<Added> {
        val text = publicXml.readText()
        val entry = Regex("""<public\s+id="0x([0-9a-fA-F]{8})"\s+type="([^"]+)"\s+name="([^"]+)"""")
        val existing = HashSet<String>()
        val maxEntry = HashMap<String, Int>()     // tipo -> mayor id de entrada
        val typeId = HashMap<String, Int>()       // tipo -> byte de tipo
        var pkgId = 0x7f
        entry.findAll(text).forEach { m ->
            val id = m.groupValues[1].toLong(16).toInt()
            val type = m.groupValues[2]; val name = m.groupValues[3]
            existing += "$type/$name"
            pkgId = (id ushr 24) and 0xFF
            typeId[type] = (id ushr 16) and 0xFF
            maxEntry[type] = maxOf(maxEntry[type] ?: -1, id and 0xFFFF)
        }

        val declared = LinkedHashSet<Pair<String, String>>()
        val newIds = LinkedHashSet<String>()
        res.listFiles { f -> f.isDirectory }.orEmpty().forEach { dir ->
            val dirType = dir.name.substringBefore('-')
            if (dirType == "values") {
                dir.listFiles { f -> f.extension == "xml" && f.name != "public.xml" }.orEmpty().forEach { declared += valuesIn(it) }
            } else {
                dir.listFiles { f -> f.isFile }.orEmpty().forEach { f ->
                    declared += dirType to f.name.substringBefore('.')
                    if (f.extension == "xml") Regex("""@\+id/([A-Za-z0-9_.]+)""").findAll(runCatching { f.readText() }.getOrDefault(""))
                        .forEach { newIds += it.groupValues[1] }
                }
            }
        }
        newIds.forEach { declared += "id" to it }

        val toAdd = declared.filter { (t, n) -> "$t/$n" !in existing && n.isNotBlank() }
        if (toAdd.isEmpty()) return emptyList()

        // Los @+id nuevos también deben existir como <item type="id"> en values/ids.xml
        val newIdNames = toAdd.filter { it.first == "id" }.map { it.second }
        if (newIdNames.isNotEmpty()) {
            val ids = File(res, "values/ids.xml")
            val base = if (ids.isFile) ids.readText() else "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n"
            val missing = newIdNames.filter { !base.contains("name=\"$it\"") }
            if (missing.isNotEmpty()) ids.writeText(base.replace("</resources>", missing.joinToString("") { "  <item type=\"id\" name=\"$it\" />\n" } + "</resources>"))
        }

        val added = mutableListOf<Added>()
        val sb = StringBuilder()
        toAdd.forEach { (type, name) ->
            val t = typeId.getOrPut(type) { (typeId.values.maxOrNull() ?: 0) + 1 }
            val e = (maxEntry[type] ?: -1) + 1
            maxEntry[type] = e
            val id = "0x%02x%02x%04x".format(pkgId, t, e)
            sb.append("  <public id=\"$id\" type=\"$type\" name=\"$name\" />\n")
            added += Added(type, name, id)
        }
        publicXml.writeText(text.replace("</resources>", sb.toString() + "</resources>"))
        return added
    }

    /** Recursos declarados en un archivo XML de values. */
    private fun valuesIn(f: File): List<Pair<String, String>> = runCatching {
        val out = mutableListOf<Pair<String, String>>()
        val p = XMLFactory.newPullParser(f)
        var depth = 0
        var inStyleable = false
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> {
                    depth++
                    val name = p.getAttributeValue(null, "name")
                    if (depth == 2 && name != null) {
                        when (p.name) {
                            "item" -> p.getAttributeValue(null, "type")?.let { out += it to name }
                            "declare-styleable" -> inStyleable = true
                            "eat-comment", "public", "skip" -> {}
                            else -> VALUE_TAGS[p.name]?.let { out += it to name }
                        }
                    } else if (depth == 3 && inStyleable && p.name == "attr" && name != null && !name.startsWith("android:")) {
                        out += "attr" to name
                    }
                }
                XmlPullParser.END_TAG -> { if (depth == 2) inStyleable = false; depth-- }
            }
        }
        out
    }.getOrDefault(emptyList())
}
