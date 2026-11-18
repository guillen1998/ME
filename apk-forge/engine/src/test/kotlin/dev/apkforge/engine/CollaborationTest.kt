package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class CollaborationTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun projectMapOfRealApk() {
        assumeTrue(sample != null)
        val proj = File(System.getProperty("java.io.tmpdir"), "collab-real").apply { deleteRecursively() }
        ApkProject.decompile(sample!!, proj, DecompileOptions())
        val map = ProjectIndex.build(proj)
        println(map)
        assertTrue(map.contains("org.fdroid.fdroid") && map.contains("Actividades") && map.contains("Código propio"))
        val hits = ContextFinder.find(proj, "cambia el texto \"Repositories\" del menú")
        println(ContextFinder.format(hits).lines().take(6).joinToString("\n"))
        assertTrue(hits.isNotEmpty())
    }

    private fun fakeProject(): File {
        val p = File(System.getProperty("java.io.tmpdir"), "collab-fake").apply { deleteRecursively(); mkdirs() }
        File(p, ".apkforge").mkdirs()
        File(p, ".apkforge/project.properties").writeText("packageName=com.demo.tv\nminSdk=21\n")
        File(p, "AndroidManifest.xml").writeText("<manifest package=\"com.demo.tv\"><application android:label=\"@string/app_name\"/></manifest>")
        val res = File(p, "resources/package_1/res").apply { mkdirs() }
        File(res, "values").mkdirs(); File(res, "layout").mkdirs()
        File(res, "values/strings.xml").writeText("<resources>\n<string name=\"app_name\">Play TV</string>\n<string name=\"movies_series\">PELÍCULAS Y SERIES</string>\n<string name=\"label_channel\">CANAL</string>\n</resources>")
        File(res, "layout/item_channel.xml").writeText("<LinearLayout android:layout_width=\"fill_parent\" android:layout_height=\"60.0dp\" android:orientation=\"horizontal\"/>")
        File(res, "layout/activity_main.xml").writeText("<androidx.recyclerview.widget.RecyclerView android:id=\"@id/recyclerChannels\"/>")
        return p
    }

    @Test fun finderLocatesChannelLayout() {
        val p = fakeProject()
        val hits = ContextFinder.find(p, "puedes hacer que los canales se vean de forma cuadrada")
        println(ContextFinder.format(hits))
        assertTrue(hits.any { it.path.endsWith("layout/item_channel.xml") }, "Debe encontrar item_channel.xml por 'canales'")
        assertTrue(ProjectIndex.build(p).contains("Play TV"))
    }

    /** Real: la app lee la captura (visión, sin clave) y localiza los archivos. -Dapkforge.live=true */
    @Test fun liveImageToFiles() {
        assumeTrue(System.getProperty("apkforge.live") == "true")
        val p = fakeProject()
        val img = javaClass.getResourceAsStream("/test_ui.png")!!.readBytes()
        val t0 = System.currentTimeMillis()
        val prep = MessagePreparer.prepare(p, "que los canales de esta pantalla se vean cuadrados", listOf(img))
        println("Tiempo: ${System.currentTimeMillis() - t0} ms\n--- Mensaje que recibe la IA ---\n${prep.text}")
        assertTrue(prep.imageNotes!!.contains("GEN_TV", ignoreCase = true), "Debe leer el texto de la captura")
        assertTrue(prep.related.any { it.path.endsWith("item_channel.xml") })
        assertTrue(prep.related.any { it.snippet.contains("PELÍCULAS Y SERIES") }, "Debe encontrar el texto visto en la imagen")
    }

    /** Flujo completo real: captura + mensaje -> la app prepara -> la IA integrada edita el archivo correcto. */
    @Test fun liveFullCollaboration() {
        assumeTrue(System.getProperty("apkforge.live") == "true")
        val p = fakeProject()
        val img = javaClass.getResourceAsStream("/test_ui.png")!!.readBytes()
        val prep = MessagePreparer.prepare(p, "haz que cada canal de esta pantalla sea un cuadrado de 160dp de alto", listOf(img))
        val events = mutableListOf<AgentEvent>()
        val t0 = System.currentTimeMillis()
        OpenAiCompatAgent(p, null, BuiltinAi.endpoints, AgentPreset.MODDER, true, { null },
            { events += it; if (it !is AgentEvent.Done) println("  evento: ${it.toString().take(170)}") }).send(prep.text)
        val layout = File(p, "resources/package_1/res/layout/item_channel.xml").readText()
        println("Tiempo IA: ${System.currentTimeMillis() - t0} ms\nitem_channel.xml final: $layout")
        println(ChangeLog.report(p).lines().take(4).joinToString("\n"))
        assertTrue(layout.contains("160"), "La IA debe editar item_channel.xml (el que localizó la app)")
        assertTrue(XmlCheck.error(layout) == null, "El XML debe seguir siendo válido")
    }
}
