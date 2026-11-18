package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/** Motor neuronal real: red MiniLM en la CPU, índice de un APK real, búsqueda por significado, razonamiento y aprendizaje. */
class NeuralBrainTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }
    private val dir: File? = System.getProperty("apkforge.neuralDir")?.let(::File)?.takeIf { File(it, "minilm.onnx").isFile }

    private fun embedder(): OnnxEmbedder {
        val e = OnnxEmbedder.load(File(dir, "minilm.onnx"), File(dir, "vocab.txt").readLines()) { println(it) }
        println("Acelerador elegido: ${e.backend} (%.1f ms/texto)".format(e.msPerText))
        return e
    }

    @Test fun embeddingsUnderstandMeaning() {
        assumeTrue(dir != null)
        val e = embedder()
        val (a, b, c) = e.embed(listOf("channel list screen", "ChannelsFragment shows the list of tv channels", "encrypt password with AES key"))
        fun cos(x: FloatArray, y: FloatArray) = x.indices.sumOf { (x[it] * y[it]).toDouble() }
        println("similitud lista/canales=%.2f  lista/cifrado=%.2f".format(cos(a, b), cos(a, c)))
        assertTrue(cos(a, b) > cos(a, c) + 0.1, "Debe entender que 'channel list' se parece más a ChannelsFragment que a cifrado")
    }

    @Test fun indexReasonAndLearnOnRealApk() {
        assumeTrue(dir != null && sample != null)
        val root = File(System.getProperty("java.io.tmpdir"), "brain").apply { deleteRecursively(); mkdirs() }
        val proj = File(root, "proj")
        ApkProject.decompile(sample!!, proj, DecompileOptions())
        NeuralBrain.storeDir = File(root, "store")
        NeuralBrain.setEmbedderForTests(embedder())
        NeuralEngine.shared = NeuralEngine(File(root, "store/model.json"))

        val t0 = System.currentTimeMillis()
        val n = NeuralBrain.index(proj, ProgressListener { println("  $it") })
        println("Indexados $n elementos en ${System.currentTimeMillis() - t0} ms")
        val t1 = System.currentTimeMillis(); NeuralBrain.index(proj); println("Reindexar sin cambios: ${System.currentTimeMillis() - t1} ms")

        val g = ProjectGraph.of(proj); println("Grafo: ${g.nodeCount} nodos")
        listOf("pantalla de ajustes de la app", "descargar la lista de repositorios", "pantalla con los detalles de una aplicación").forEach { q ->
            val th = NeuralBrain.think(proj, q)
            println("\n### $q\n  semántica: " + th.semantic.take(5).joinToString { "${it.title}=%.2f".format(it.score) } + "\n" + th.format().lines().take(14).joinToString("\n"))
        }
        val th = NeuralBrain.think(proj, "pantalla de ajustes de la app")
        assertTrue(th.semantic.isNotEmpty())
        assertTrue(th.semantic.take(5).any { it.path.lowercase().contains("setting") }, "Debía encontrar los ajustes por significado")

        // Aprender de un error: la IA edita un archivo que no estaba en el top-5
        val target = th.ranked.drop(8).first().first
        val since = System.currentTimeMillis()
        val f = File(proj, target); val old = f.readText(); f.appendText("\n"); ChangeLog.record(f, old, f.readText(), "IA", "test")
        NeuralBrain.learnFromEdit(proj, "pantalla de ajustes de la app", since)
        println("Stats: ${NeuralBrain.stats()}")
        println("Texto para la red: " + NeuralBrain.queryText("abrir la configuración de la aplicación"))
        // Pedido PARECIDO (otras palabras): debe recordar lo que la IA editó
        val after = NeuralBrain.think(proj, "abrir la configuración de la aplicación").ranked.indexOfFirst { it.first == target }
        println("Tras corregirse, $target pasó a la posición $after")
        assertTrue(after in 0..4, "Tras equivocarse y aprender, el archivo correcto debía subir al top-5")
        assertTrue(NeuralBrain.stats().mistakesFixed >= 1)
    }
}
