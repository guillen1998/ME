package dev.apkforge.engine

import java.io.File

/**
 * Cambios GRANDES de diseño que la app hace sola (y la IA puede pedir): convertir una lista
 * vertical (RecyclerView + LinearLayoutManager) en una CUADRÍCULA de N columnas
 * (GridLayoutManager), en smali o en el XML del layout.
 */
object LayoutPatcher {

    data class Site(val file: File, val line: Int, val method: String, val kind: String, val owner: String) {
        val id get() = "${file.path}:$line"
    }

    private val PKGS = listOf("Landroidx/recyclerview/widget/", "Landroid/support/v7/widget/")
    private val NEW = Regex("""^\s*new-instance ([vp]\d+), (L[\w/$]+/)LinearLayoutManager;\s*$""")

    /** Todas las listas (LinearLayoutManager) del proyecto, en smali y en XML. */
    fun find(project: File): List<Site> {
        val out = mutableListOf<Site>()
        project.walkTopDown().onEnter { it.name != ".apkforge" && it.name != "android-studio" && it.name != "root" }
            .filter { it.isFile && (it.extension == "smali" || (it.extension == "xml" && it.path.contains("/layout"))) }
            .forEach { f ->
                val lines = runCatching { f.readLines() }.getOrNull() ?: return@forEach
                if (f.extension == "xml") {
                    lines.forEachIndexed { i, l -> if (l.contains("layoutManager=") && l.contains("LinearLayoutManager")) out += Site(f, i + 1, "", "xml", f.nameWithoutExtension) }
                    return@forEach
                }
                if (lines.none { it.contains("LinearLayoutManager;") }) return@forEach
                if (lines.firstOrNull()?.let { l -> PKGS.any { l.contains(it.removePrefix("L")) } } == true) return@forEach // la propia librería
                var method = ""
                lines.forEachIndexed { i, l ->
                    if (l.startsWith(".method")) method = l.substringAfterLast(' ')
                    val m = NEW.find(l) ?: return@forEachIndexed
                    if (m.groupValues[2] !in PKGS) return@forEachIndexed
                    val owner = lines.first().substringAfterLast(' ').trim().removePrefix("L").removeSuffix(";").replace('/', '.')
                    out += Site(f, i + 1, method, "smali", owner)
                }
            }
        return out
    }

    /** Convierte la lista en [site] a una cuadrícula de [columns] columnas. Devuelve un resumen. */
    fun toGrid(project: File, site: Site, columns: Int = 2): String {
        require(columns in 1..8) { "Columnas entre 1 y 8" }
        val f = site.file
        val old = f.readText()
        val new = if (site.kind == "xml") xmlToGrid(old, site.line, columns) else smaliToGrid(old, site.line, columns, project)
        if (new == old) throw EngineException("No se pudo cambiar ${f.name}:${site.line}")
        f.writeText(new)
        ChangeLog.record(f, old, new, "App", "Lista → cuadrícula de $columns columnas")
        return "✔ ${site.owner} (${f.name}:${site.line}) ahora es una cuadrícula de $columns columnas"
    }

    private fun xmlToGrid(text: String, line: Int, columns: Int): String {
        val lines = text.lines().toMutableList()
        val i = line - 1
        val attr = Regex("""(\w+):layoutManager="([^"]*)LinearLayoutManager"""")
        val m = attr.find(lines[i]) ?: return text
        val prefix = m.groupValues[1]
        // El spanCount va justo detrás del atributo (dentro de la etiqueta, aunque cierre en la misma línea)
        val span = if (text.contains(":spanCount=")) "" else " $prefix:spanCount=\"$columns\""
        lines[i] = lines[i].replaceRange(m.range, "$prefix:layoutManager=\"${m.groupValues[2]}GridLayoutManager\"$span")
        var out = lines.joinToString("\n")
        if (span.isEmpty()) out = out.replace(Regex("""(\w+:spanCount=)"[^"]*""""), "$1\"$columns\"")
        return out
    }

    private fun smaliToGrid(text: String, line: Int, columns: Int, project: File): String {
        val lines = text.lines().toMutableList()
        val i = line - 1
        val m = NEW.find(lines[i]) ?: throw EngineException("La línea $line no crea un LinearLayoutManager")
        val reg = m.groupValues[1]; val pkg = m.groupValues[2]
        val gridFile = project.walkTopDown().onEnter { it.name != ".apkforge" && it.name != "android-studio" }
            .firstOrNull { it.name == "GridLayoutManager.smali" && it.path.contains(pkg.removePrefix("L").removeSuffix("/")) }
            ?: throw EngineException("Esta app no incluye GridLayoutManager (se eliminó al optimizarla); pide a la IA otro enfoque")
        val gridText = gridFile.readText()
        val simpleCtor = gridText.contains("constructor <init>(Landroid/content/Context;I)V")
        val attrsCtor = gridText.contains("constructor <init>(Landroid/content/Context;Landroid/util/AttributeSet;II)V")
        if (!simpleCtor && (!attrsCtor || !gridText.contains("setSpanCount(I)V")))
            throw EngineException("GridLayoutManager de esta app no tiene constructores utilizables; pide a la IA otro enfoque")
        // Constructor del LinearLayoutManager (cualquiera que empiece por Context)
        val ctorRx = Regex("""^\s*invoke-direct \{$reg, ([vp]\d+)(?:, [vp]\d+)*\}, ${Regex.escape(pkg)}LinearLayoutManager;-><init>\(Landroid/content/Context;[^)]*\)V\s*$""")
        val j = (i + 1 until minOf(lines.size, i + 40)).firstOrNull { ctorRx.matches(lines[it]) }
            ?: throw EngineException("Constructor no estándar en la línea $line; pide a la IA que lo adapte")
        val ctx = ctorRx.find(lines[j])!!.groupValues[1]
        // Registro libre: añadimos uno a .locals del método
        val start = (i downTo 0).first { lines[it].startsWith(".method") }
        val localsIdx = (start until i).firstOrNull { lines[it].trim().startsWith(".locals") }
            ?: throw EngineException("Método sin .locals")
        val locals = lines[localsIdx].trim().removePrefix(".locals").trim().toInt()
        val free = "v$locals"
        if (locals > 15 || listOf(reg, ctx).any { regNum(it, locals + 1) > 15 })
            throw EngineException("Método con demasiados registros para el cambio automático; pide a la IA que lo adapte")
        lines[localsIdx] = lines[localsIdx].replace(".locals $locals", ".locals ${locals + 1}")
        lines[i] = lines[i].replace("${pkg}LinearLayoutManager;", "${pkg}GridLayoutManager;")
        val ind = lines[j].takeWhile { it.isWhitespace() }
        val n = "0x" + columns.toString(16)
        val grid = "${pkg}GridLayoutManager;"
        lines[j] = if (simpleCtor)
            "${ind}const/4 $free, $n\n\n${ind}invoke-direct {$reg, $ctx, $free}, $grid-><init>(Landroid/content/Context;I)V"
        else // (Context, AttributeSet=null, 0, 0) + setSpanCount(N): funciona aunque R8 haya quitado el constructor simple
            "${ind}const/4 $free, 0x0\n\n${ind}invoke-direct {$reg, $ctx, $free, $free, $free}, $grid-><init>(Landroid/content/Context;Landroid/util/AttributeSet;II)V\n\n" +
                "${ind}const/4 $free, $n\n\n${ind}invoke-virtual {$reg, $free}, $grid->setSpanCount(I)V"
        return lines.joinToString("\n")
    }

    /** Número real de registro (los pN se desplazan al aumentar .locals). */
    private fun regNum(r: String, locals: Int): Int =
        if (r.startsWith("v")) r.drop(1).toInt() else locals + r.drop(1).toInt()
}
