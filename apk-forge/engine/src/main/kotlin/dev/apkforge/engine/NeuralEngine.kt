package dev.apkforge.engine

import com.google.gson.Gson
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Motor neuronal básico que corre EN EL MÓVIL (sin internet, en milisegundos).
 *
 * Es una red pequeña "wide & deep":
 *  - parte ancha: pesos aprendidos para cada par (palabra del pedido × tipo de archivo), p. ej.
 *    "cuadrícula × smali propio" o "color × values", que se aprenden con el uso;
 *  - parte profunda: 14 rasgos (incluida la similitud de significado de la red de embeddings y la
 *    conexión en el grafo del proyecto) → 16 neuronas ocultas (tanh) → probabilidad.
 *
 * La APP AYUDA A LA IA: antes de cada pedido ordena los archivos candidatos por probabilidad de ser
 * los que hay que tocar, y la IA recibe primero los buenos.
 * La IA AYUDA A LA APP: cuando la IA termina, los archivos que realmente editó son ejemplos
 * positivos y el resto negativos; la red se entrena y la próxima vez acierta más.
 * Lo aprendido se guarda y sirve para todos los proyectos.
 */
class NeuralEngine(private val store: File? = null) {

    private class Weights(
        var wide: FloatArray = FloatArray(WIDE),
        var w1: FloatArray = FloatArray(HIDDEN * FEATS) { (Random(7 + it).nextFloat() - 0.5f) * 0.4f },
        var b1: FloatArray = FloatArray(HIDDEN),
        var w2: FloatArray = FloatArray(HIDDEN) { (Random(99 + it).nextFloat() - 0.5f) * 0.4f },
        var b2: Float = 0f,
        var examples: Int = 0,
    )

    private var w: Weights = load()
    val examples get() = w.examples

    /** [semantic] = similitud de significado (red de embeddings), [graph] = conexión en el grafo del proyecto. */
    data class Candidate(val path: String, val heuristicRank: Int = 0, val keywordHits: Int = 0, val semantic: Float = 0f, val graph: Float = 0f)
    data class Scored(val path: String, val score: Float)

    // ───────────── rasgos ─────────────

    private fun fileType(path: String): Int {
        val p = path.lowercase()
        return when {
            p.endsWith("androidmanifest.xml") -> 0
            p.contains("/layout") -> 1
            p.contains("/values") -> 2
            p.contains("/drawable") || p.contains("/mipmap") -> 3
            p.endsWith(".smali") && LIB.any { p.contains(it) } -> 5
            p.endsWith(".smali") -> 4
            p.contains("/assets/") || p.startsWith("root/") -> 6
            else -> 7
        }
    }

    private fun features(query: Set<String>, c: Candidate, touchedBefore: Boolean): FloatArray {
        val pathTok = tokens(c.path)
        val overlap = if (query.isEmpty() || pathTok.isEmpty()) 0f else query.count { it in pathTok } / sqrt(query.size.toFloat() * pathTok.size)
        val f = FloatArray(FEATS)
        f[0] = overlap
        f[1] = ln(1f + c.keywordHits) / 3f
        f[2] = 1f / (1f + c.heuristicRank)
        f[3] = if (touchedBefore) 1f else 0f
        f[4 + fileType(c.path)] = 1f   // 8 posiciones: 4..11
        f[12] = c.semantic
        f[13] = c.graph
        return f
    }

    private fun wideIdx(query: Set<String>, path: String): IntArray {
        val t = fileType(path)
        val ptoks = tokens(path)
        return (query.map { h("$it|t$t") } + query.filter { it in ptoks }.map { h("$it|in") }).toIntArray()
    }

    private fun forward(x: FloatArray, wide: IntArray): Pair<Float, FloatArray> {
        val hid = FloatArray(HIDDEN)
        for (j in 0 until HIDDEN) {
            var s = w.b1[j]
            for (i in 0 until FEATS) s += w.w1[j * FEATS + i] * x[i]
            hid[j] = kotlin.math.tanh(s)
        }
        var z = w.b2
        for (j in 0 until HIDDEN) z += w.w2[j] * hid[j]
        for (i in wide) z += w.wide[i]
        return sigmoid(z) to hid
    }

    // ───────────── uso ─────────────

    /** Ordena los candidatos por probabilidad de ser los archivos a tocar. */
    fun rank(query: String, candidates: List<Candidate>, touched: Set<String> = emptySet()): List<Scored> {
        val q = tokens(query)
        return candidates.map { c -> Scored(c.path, forward(features(q, c, c.path in touched), wideIdx(q, c.path)).first) }
            .sortedByDescending { it.score }
    }

    /** Aprende de lo que hizo la IA: [positives] = archivos que editó; el resto de candidatos son negativos. */
    @Synchronized
    fun learn(query: String, candidates: List<Candidate>, positives: Set<String>, touched: Set<String> = emptySet(), epochs: Int = 8) {
        if (positives.isEmpty() || candidates.isEmpty()) return
        val q = tokens(query)
        val all = (candidates + positives.filter { p -> candidates.none { it.path == p } }.map { Candidate(it) })
        val pos = all.filter { it.path in positives }
        val neg = all.filter { it.path !in positives }.shuffled(Random(query.hashCode())).take(pos.size * 4 + 4)
        val data = pos.map { it to 1f } + neg.map { it to 0f }
        repeat(epochs) {
            data.shuffled(Random(it)).forEach { (c, y) ->
                val x = features(q, c, c.path in touched); val wi = wideIdx(q, c.path)
                val (p, hid) = forward(x, wi)
                val g = (p - y) * (if (y > 0f) POS_WEIGHT else 1f)       // gradiente de la pérdida logística
                for (i in wi) w.wide[i] -= LR_WIDE * (g + L2 * w.wide[i])
                for (j in 0 until HIDDEN) {
                    val gh = g * w.w2[j] * (1 - hid[j] * hid[j])
                    w.w2[j] -= LR * g * hid[j]
                    for (i in 0 until FEATS) w.w1[j * FEATS + i] -= LR * gh * x[i]
                    w.b1[j] -= LR * gh
                }
                w.b2 -= LR * g
            }
        }
        w.examples += pos.size
        save()
    }

    /** Olvida todo lo aprendido. */
    @Synchronized
    fun reset() { w = Weights(); save() }

    // ───────────── persistencia ─────────────

    private fun load(): Weights = runCatching {
        store?.takeIf { it.isFile }?.let { Gson().fromJson(it.readText(), Weights::class.java) }
            ?.takeIf { it.wide.size == WIDE && it.w1.size == HIDDEN * FEATS }
    }.getOrNull() ?: Weights()

    private fun save() { runCatching { store?.apply { parentFile?.mkdirs(); writeText(Gson().toJson(w)) } } }

    companion object {
        private const val WIDE = 1 shl 14
        private const val FEATS = 14
        private const val HIDDEN = 16
        private const val LR = 0.05f
        private const val LR_WIDE = 0.3f
        private const val L2 = 1e-4f
        private const val POS_WEIGHT = 2f
        private val LIB = listOf("/androidx/", "/android/support/", "/kotlin/", "/kotlinx/", "/com/google/", "/okhttp3/", "/retrofit2/", "/io/reactivex/")
        private val STOP = setOf("para", "como", "que", "los", "las", "una", "del", "con", "por", "the", "and", "this", "quiero", "puedes",
            "hacer", "smali", "classes", "resources", "package", "res", "xml", "com", "org", "java", "app", "apk")

        /** Global de la app: la app le da un archivo donde guardar lo aprendido. */
        @Volatile var shared: NeuralEngine = NeuralEngine()

        private fun sigmoid(z: Float) = (1.0 / (1.0 + exp(-z.toDouble().coerceIn(-30.0, 30.0)))).toFloat()
        private fun h(s: String) = (s.hashCode() and 0x7fffffff) % WIDE

        /** Palabras de un texto o ruta: separa camelCase, snake_case y rutas, y traduce es→en las típicas de UI. */
        fun tokens(text: String): Set<String> {
            val spaced = text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            val words = Regex("[\\p{L}][\\p{L}0-9]{2,}").findAll(spaced).map { it.value.lowercase() }.filter { it !in STOP }.toList()
            val out = LinkedHashSet<String>()
            words.forEach { wd ->
                val stem = when { wd.endsWith("es") && wd.length > 5 -> wd.dropLast(2); wd.endsWith("s") && wd.length > 4 -> wd.dropLast(1); else -> wd }
                out += stem
                ContextFinder.translate(stem)?.let { out += it }
                ContextFinder.translate(wd)?.let { out += it }
            }
            return out
        }
    }
}
