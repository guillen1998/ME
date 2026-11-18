package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/** El modelo que razona en el dispositivo (Qwen3 + thinking) responde con lógica sobre un caso de Android. */
class LocalReasonerTest {
    private val model: File? = System.getProperty("apkforge.reasonerModel")?.let(::File)?.takeIf { it.isFile }

    @Test fun reasonsAboutProject() {
        assumeTrue(model != null)
        val t0 = System.currentTimeMillis()
        val r = LocalReasoner.load(model!!, File(System.getProperty("java.io.tmpdir"), "llm-cache").apply { mkdirs() }, tryAccelerators = false) { println(it) }
        println("Carga: ${System.currentTimeMillis() - t0} ms en ${r.backend}")
        NeuralBrain.setReasonerForTests(r)
        val query = "Quiero ver los canales en una cuadrícula de 3 columnas en vez de filas"
        val facts = AndroidKnowledge.format(AndroidKnowledge.find(query)) +
            "\nRAZONAMIENTO DEL GRAFO: ChannelsFragment.smali es una pantalla (Fragment) y crea una lista vertical (LinearLayoutManager); " +
            "ChannelsFragment.smali —usa→ ChannelAdapter.smali —infla→ item_channel.xml (logo a la izquierda, nombre a la derecha)."
        println("Hechos:\n$facts")
        val d = NeuralBrain.deliberate(query, facts)!!
        println("Tiempo: ${d.millis} ms\n--- PENSAMIENTO ---\n${d.thinking.take(1500)}\n--- CONCLUSIÓN ---\n${d.conclusion}")
        val a = (d.conclusion + d.thinking).lowercase()
        assertTrue(a.contains("gridlayoutmanager") || a.contains("grid"), "Debía razonar que hay que usar GridLayoutManager")
        assertTrue(a.contains("item_channel"), "Debía razonar que hay que adaptar item_channel.xml")
        r.close()
    }
}
