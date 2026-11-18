package dev.apkforge.engine

/**
 * Base de conocimiento de modificación de apps Android (recetas comprobadas). La red de significado
 * busca las recetas que encajan con el pedido y se las da al razonador local y a las IAs de
 * internet: un modelo pequeño razona bien cuando tiene los hechos correctos delante.
 */
object AndroidKnowledge {
    data class Recipe(val title: String, val keywords: String, val steps: String)

    val recipes = listOf(
        Recipe("Lista a cuadrícula (filas y columnas)", "grid cuadrícula columnas filas list recyclerview layout manager",
            "En el código que crea el LayoutManager (smali: new-instance …LinearLayoutManager) cambiarlo por GridLayoutManager con spanCount = columnas " +
                "(herramientas find_lists y list_to_grid lo hacen seguro). Si la lista se define en XML: app:layoutManager=\"…GridLayoutManager\" + app:spanCount. " +
                "Después adaptar el layout del elemento (item_*.xml): ancho match_parent, orientación vertical (imagen arriba, texto debajo, centrado)."),
        Recipe("Cambiar colores de la app", "color colores tema theme primary accent fondo background",
            "Colores en res/values/colors.xml (y values-night para modo oscuro). El tema en res/values/themes.xml o styles.xml (colorPrimary, colorSecondary, " +
                "android:windowBackground). Cambiar el valor existente en vez de duplicar el nombre (duplicado = error de compilación)."),
        Recipe("Cambiar textos visibles", "texto string strings nombre titulo label traducir idioma",
            "Textos en res/values/strings.xml (y values-xx para otros idiomas). Si el texto no está ahí, buscarlo con search_text en smali (const-string) o en layouts (android:text)."),
        Recipe("Cambiar nombre de la app", "nombre app label launcher titulo application name",
            "AndroidManifest.xml: <application android:label=\"@string/app_name\"> → cambiar app_name en strings.xml de todos los idiomas, o poner el texto directo en android:label."),
        Recipe("Cambiar icono", "icono icon launcher mipmap logo",
            "AndroidManifest.xml android:icon / android:roundIcon apuntan a @mipmap/ic_launcher. Reemplazar las imágenes en res/mipmap-*dpi (mismo nombre) o el XML adaptive-icon en mipmap-anydpi-v26."),
        Recipe("Cambiar tamaño, márgenes o diseño de una pantalla", "tamaño margen padding diseño layout ancho alto size dp",
            "Layouts en res/layout/*.xml: android:layout_width/height, layout_margin, padding, textSize (sp). Dimensiones compartidas en res/values/dimens.xml."),
        Recipe("Ocultar un elemento de la interfaz", "ocultar esconder quitar boton view visibility gone",
            "En el layout: android:visibility=\"gone\" en la vista. Si se muestra desde código, buscar setVisibility en el smali de la clase que controla esa vista (id)."),
        Recipe("Cambiar URL o servidor", "url servidor server api endpoint dominio host https",
            "Buscar la URL con scan_findings/search_text (const-string en smali, strings.xml, assets, o BuildConfig). Si está cifrada, usar auto_aes/decrypt. Cambiar el texto manteniendo comillas."),
        Recipe("Permitir HTTP sin cifrar", "http cleartext network security config trafico",
            "AndroidManifest <application android:usesCleartextTraffic=\"true\"> o res/xml/network_security_config.xml con <base-config cleartextTrafficPermitted=\"true\">."),
        Recipe("Añadir un permiso", "permiso permission manifest uses-permission",
            "AndroidManifest.xml: <uses-permission android:name=\"android.permission.X\"/> antes de <application>. Los peligrosos además se piden en tiempo de ejecución."),
        Recipe("Forzar orientación", "orientacion horizontal vertical landscape portrait rotar",
            "AndroidManifest <activity android:screenOrientation=\"portrait|landscape|sensor\">."),
        Recipe("Modo oscuro", "oscuro dark night tema",
            "Crear/editar res/values-night/colors.xml y themes.xml con colores oscuros; el tema debe heredar de un DayNight (Theme.MaterialComponents.DayNight / Theme.Material3.DayNight)."),
        Recipe("Cambiar fuente", "fuente font letra tipografia",
            "Añadir el .ttf en res/font/, y en el tema <item name=\"android:fontFamily\">@font/nombre</item> o en cada TextView android:fontFamily."),
        Recipe("Cambiar versión de la app", "version versionName versionCode actualizar",
            "AndroidManifest.xml (decompilado): android:versionCode y android:versionName en <manifest>. Subir versionCode para instalar encima."),
        Recipe("Cambiar paquete (clonar app)", "paquete package clonar applicationId duplicar",
            "Cambiar package en AndroidManifest y las authorities de providers (${'$'}{applicationId}.xxx) para evitar conflictos; no hace falta renombrar las clases smali."),
        Recipe("Reglas de smali", "smali registros registers locals invoke const error compilar",
            ".locals N define v0..vN-1; los parámetros son p0 (this) p1…; invoke-* con 5 registros máx. y todos ≤ v15 (si no, invoke-*/range con registros consecutivos). " +
                "const/4 solo -8..7, const/16 hasta 32767. Tipos: I int, Z boolean, J long (2 registros), Ljava/lang/String;. Tras invoke que devuelve, move-result."),
        Recipe("Recursos nuevos", "nuevo recurso color string layout drawable id public xml crear",
            "Declararlo en su XML (values/colors.xml, layout nuevo…). La app registra el ID en public.xml al exportar. No repetir un nombre existente."),
        Recipe("Cambiar comportamiento de un botón", "boton click pulsar accion onclick listener",
            "Buscar el id del botón en el layout, luego en smali la clase que hace findViewById(id) y setOnClickListener; la lógica está en el método onClick de la clase listener (a menudo una clase interna/lambda)."),
        Recipe("Pantalla de inicio / splash", "splash inicio arranque pantalla inicial launcher activity",
            "La Activity con <intent-filter> MAIN/LAUNCHER en AndroidManifest es la primera pantalla. Un retraso de splash suele ser postDelayed/Handler o un Thread.sleep en esa clase."),
        Recipe("WebView", "webview web pagina navegador loadurl",
            "Buscar Landroid/webkit/WebView;->loadUrl en smali; la URL está en un const-string cercano o en strings.xml. Para JavaScript: setJavaScriptEnabled."),
        Recipe("Reproductor de vídeo / IPTV", "video player reproductor exoplayer stream m3u8 canal tv",
            "Buscar ExoPlayer/Media3 (MediaItem.fromUri, setMediaItem) o MediaPlayer.setDataSource; las URLs de stream suelen venir de una API (buscar http y .m3u8)."),
        Recipe("Desofuscar", "ofuscado ofuscacion nombres a b c proguard r8 desofuscar",
            "Usar deobfuscate (la app deduce nombres por toString, logs, layouts, findViewById…), deobf_info para estudiar clases pendientes y rename_symbol para guardar nombres."),
        Recipe("Textos cifrados", "cifrado encriptado aes base64 xor decrypt string ofuscado",
            "Buscar el método descifrador (estático, recibe y devuelve String, llamado desde muchos sitios). Probar auto_aes/decrypt con claves cercanas; base64 y XOR son comunes."),
    )

    @Volatile private var vectors: List<FloatArray>? = null

    /** Recetas más relevantes para el pedido (por significado si hay red; si no, por palabras). */
    fun find(query: String, k: Int = 3): List<Recipe> {
        val e = NeuralBrain.embedder()
        if (e != null) runCatching {
            val vs = vectors ?: e.embed(recipes.map { it.title + ". " + it.keywords }, 64).also { vectors = it }
            val q = e.embed(listOf(NeuralBrain.queryText(query)), 64).first()
            return recipes.indices.map { i -> i to vs[i].indices.sumOf { (vs[i][it] * q[it]).toDouble() } }
                .sortedByDescending { it.second }.filter { it.second > 0.3 }.take(k).map { recipes[it.first] }
        }
        val toks = NeuralEngine.tokens(query) + query.lowercase().split(Regex("\\W+"))
        return recipes.map { r -> r to toks.count { t -> t.length > 3 && r.keywords.contains(t) } }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(k).map { it.first }
    }

    fun format(list: List<Recipe>): String = if (list.isEmpty()) "" else
        "CONOCIMIENTO DE LA APP (recetas comprobadas):\n" + list.joinToString("\n") { "• ${it.title}: ${it.steps}" }
}
