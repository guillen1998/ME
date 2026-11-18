package dev.apkforge.engine

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** La red aprende de lo que edita la IA y en el siguiente pedido parecido pone primero el archivo correcto. */
class NeuralEngineTest {
    private val cands = listOf(
        NeuralEngine.Candidate("resources/package_1/res/values/strings.xml", 0, 9),
        NeuralEngine.Candidate("resources/package_1/res/values-fr/strings.xml", 1, 7),
        NeuralEngine.Candidate("resources/package_1/res/layout/activity_main.xml", 2, 2),
        NeuralEngine.Candidate("smali/classes/com/tv/ui/ChannelsFragment.smali", 3, 1),
        NeuralEngine.Candidate("smali/classes/androidx/recyclerview/widget/LinearLayoutManager.smali", 4, 1),
        NeuralEngine.Candidate("resources/package_1/res/layout/item_channel.xml", 5, 1),
    )

    @Test fun learnsWhichFilesToTouch() {
        val store = File(System.getProperty("java.io.tmpdir"), "neural-test/model.json").apply { delete() }
        val net = NeuralEngine(store)
        val q1 = "pon los canales en cuadrícula de 3 columnas"
        val before = net.rank(q1, cands).indexOfFirst { it.path.endsWith("ChannelsFragment.smali") }
        // La IA, en varios proyectos, editó el fragment de la lista y el layout del elemento
        repeat(6) { i ->
            net.learn(listOf(q1, "quiero la lista de canales en filas y columnas", "cuadrícula de 2 columnas para canales")[i % 3], cands,
                setOf("smali/classes/com/tv/ui/ChannelsFragment.smali", "resources/package_1/res/layout/item_channel.xml"))
        }
        val ranked = net.rank("canales en cuadrícula", cands)
        ranked.forEach { println("  %.3f  %s".format(it.score, it.path)) }
        val after = ranked.indexOfFirst { it.path.endsWith("ChannelsFragment.smali") }
        println("Posición del archivo correcto: antes $before → después $after")
        assertTrue(after <= 1, "Tras aprender debía estar entre los 2 primeros")
        assertTrue(ranked.take(2).map { it.path }.toSet().contains("resources/package_1/res/layout/item_channel.xml"))
        // Persistencia: otra instancia carga lo aprendido
        val reloaded = NeuralEngine(store)
        assertEquals(net.examples, reloaded.examples)
        assertEquals(ranked.first().path, reloaded.rank("canales en cuadrícula", cands).first().path)
    }

    @Test fun tokensSplitAndTranslate() {
        val t = NeuralEngine.tokens("ChannelsFragment canales cuadrícula item_channel")
        println(t)
        assertTrue("channel" in t && "grid" in t && "canal" in t)
    }
}
