package dev.apkforge.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.gson.Gson
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.sqrt

/*
 * MOTOR NEURONAL DE APKFORGE (en el móvil, sin internet)
 *
 *  1. Red de embeddings (MiniLM, 6 capas transformer, cuantizada) ejecutada con ONNX Runtime en
 *     la GPU/NPU (NNAPI) o en todos los núcleos de la CPU (XNNPACK): elige sola la más rápida.
 *     Entiende el SIGNIFICADO: "pantalla de canales" encuentra ChannelsFragment aunque no coincida el texto.
 *  2. Razonamiento sobre el grafo del proyecto: quién llama a quién, qué clase infla qué layout,
 *     qué es pantalla, adaptador, lista… y deduce cadenas como
 *     "ChannelsFragment (pantalla) → crea lista vertical → ChannelAdapter → infla item_channel.xml".
 *  3. Aprende de sus errores y de las IAs: si sugirió mal los archivos, se corrige con lo que la IA
 *     editó de verdad; si un cambio rompió la compilación o el revisor lo rechazó, aprende a
 *     reconocer cambios arriesgados y avisa antes.
 */

// ───────────────────────────── Tokenizador WordPiece (BERT) ─────────────────────────────

class WordPiece(vocab: List<String>) {
    private val ids = HashMap<String, Int>(vocab.size * 2).apply { vocab.forEachIndexed { i, t -> put(t, i) } }
    val cls = ids["[CLS]"] ?: 101
    val sep = ids["[SEP]"] ?: 102
    val pad = ids["[PAD]"] ?: 0
    private val unk = ids["[UNK]"] ?: 100

    fun encode(text: String, maxLen: Int): IntArray {
        val out = ArrayList<Int>(maxLen); out += cls
        for (word in basic(text)) {
            if (out.size >= maxLen - 1) break
            out += piece(word)
        }
        val list = out.take(maxLen - 1) + sep
        return list.toIntArray()
    }

    private fun basic(text: String): List<String> {
        val norm = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val words = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { words += sb.toString(); sb.clear() } }
        for (c in norm) when {
            c.isWhitespace() || c.isISOControl() -> flush()
            !c.isLetterOrDigit() -> { flush(); words += c.toString() }
            else -> sb.append(c)
        }
        flush()
        return words
    }

    private fun piece(word: String): List<Int> {
        if (word.length > 100) return listOf(unk)
        val out = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                ids[sub]?.let { found = it }
                if (found != null) break
                end--
            }
            if (found == null) return listOf(unk)
            out += found!!; start = end
        }
        return out
    }
}

// ───────────────────────────── Red de embeddings en CPU/GPU ─────────────────────────────

/** Convierte textos en vectores de significado (384 dimensiones, normalizados). */
class OnnxEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tok: WordPiece,
    /** "GPU/NPU (NNAPI)", "CPU ×8 (XNNPACK)" o "CPU ×8". */
    val backend: String,
    /** Milisegundos por texto medidos al elegir el acelerador. */
    val msPerText: Double,
    private val fixedLen: Int?,
) : Closeable {
    private val hasTypeIds = "token_type_ids" in session.inputNames

    @Synchronized
    fun embed(texts: List<String>, maxLen: Int = 128): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val enc = texts.map { tok.encode(it, fixedLen ?: maxLen) }
        val len = fixedLen ?: enc.maxOf { it.size }
        val b = enc.size
        val ids = LongArray(b * len); val mask = LongArray(b * len)
        enc.forEachIndexed { i, e -> e.forEachIndexed { j, t -> ids[i * len + j] = t.toLong(); mask[i * len + j] = 1 } }
        val shape = longArrayOf(b.toLong(), len.toLong())
        val tensors = LinkedHashMap<String, OnnxTensor>()
        try {
            tensors["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            tensors["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
            if (hasTypeIds) tensors["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(b * len)), shape)
            session.run(tensors).use { res ->
                @Suppress("UNCHECKED_CAST")
                val hidden = res[0].value as Array<Array<FloatArray>>
                return List(b) { i ->
                    val dim = hidden[i][0].size
                    val v = FloatArray(dim); var n = 0
                    for (j in 0 until len) if (mask[i * len + j] == 1L) { n++; val h = hidden[i][j]; for (d in 0 until dim) v[d] += h[d] }
                    var norm = 0f
                    for (d in 0 until dim) { v[d] /= maxOf(n, 1); norm += v[d] * v[d] }
                    norm = sqrt(norm).coerceAtLeast(1e-6f)
                    for (d in 0 until dim) v[d] /= norm
                    v
                }
            }
        } finally { tensors.values.forEach { it.close() } }
    }

    override fun close() = runCatching { session.close() }.let { }

    companion object {
        /**
         * Carga el modelo probando GPU/NPU (NNAPI), CPU multinúcleo (XNNPACK) y CPU estándar; mide
         * la velocidad real de cada uno en este móvil y se queda con el más rápido.
         */
        fun load(model: File, vocab: List<String>, tryAccelerators: Boolean = true, log: (String) -> Unit = {}): OnnxEmbedder {
            val env = OrtEnvironment.getEnvironment()
            val tok = WordPiece(vocab)
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            data class Cand(val name: String, val fixed: Int?, val opts: () -> OrtSession.SessionOptions)
            val cands = buildList {
                if (tryAccelerators) {
                    add(Cand("GPU/NPU (NNAPI)", 128) { OrtSession.SessionOptions().apply { addNnapi() } })
                    add(Cand("CPU ×$cores (XNNPACK)", null) {
                        OrtSession.SessionOptions().apply { setIntraOpNumThreads(1); addXnnpack(mapOf("intra_op_num_threads" to "$cores")) }
                    })
                }
                add(Cand("CPU ×$cores", null) { OrtSession.SessionOptions().apply { setIntraOpNumThreads(cores) } })
            }
            val sample = listOf("pantalla de canales con lista de filas", "class ChannelAdapter onBindViewHolder", "layout item channel image text", "color primary")
            var best: OnnxEmbedder? = null
            for (c in cands) {
                val e = runCatching {
                    val s = env.createSession(model.absolutePath, c.opts())
                    val probe = OnnxEmbedder(env, s, tok, c.name, 0.0, c.fixed)
                    probe.embed(sample.take(1))                                   // calentamiento
                    val t0 = System.nanoTime(); repeat(2) { probe.embed(sample) }
                    val ms = (System.nanoTime() - t0) / 1e6 / (2 * sample.size)
                    OnnxEmbedder(env, s, tok, c.name, ms, c.fixed)
                }.onFailure { log("  ${c.name}: no disponible (${it.message?.take(80)})") }.getOrNull() ?: continue
                log("  ${c.name}: %.1f ms por texto".format(e.msPerText))
                if (best == null || e.msPerText < best.msPerText) { best?.close(); best = e } else e.close()
            }
            return best ?: throw EngineException("No se pudo cargar el motor neuronal")
        }
    }
}

// ───────────────────────────── Índice semántico del proyecto ─────────────────────────────

class SemanticIndex(private val project: File) {
    data class Chunk(val path: String, val title: String, val text: String, val mtime: Long, var vec: FloatArray? = null)
    data class Hit(val path: String, val title: String, val score: Float)

    private val file = File(project, "$META_DIR/neural/index.bin")
    private var chunks: List<Chunk> = emptyList()

    val size get() = chunks.size

    /** Construye o actualiza el índice (solo recalcula lo que cambió). */
    fun build(embedder: OnnxEmbedder, listener: ProgressListener = ProgressListener.NONE, isCancelled: () -> Boolean = { false }) {
        val fresh = ProjectReader.chunks(project)
        val old = load().associateBy { it.path + "#" + it.title }
        fresh.forEach { c -> old[c.path + "#" + c.title]?.takeIf { it.mtime == c.mtime && it.vec != null }?.let { c.vec = it.vec } }
        val todo = fresh.filter { it.vec == null }
        if (todo.isNotEmpty()) listener.onProgress("🧠 Motor neuronal (${embedder.backend}): indexando ${todo.size} elementos…")
        todo.chunked(16).forEachIndexed { i, batch ->
            if (isCancelled()) return
            embedder.embed(batch.map { it.text }).forEachIndexed { j, v -> batch[j].vec = v }
            listener.onPercent(((i + 1) * 16 * 100 / todo.size).coerceAtMost(100))
        }
        chunks = fresh.filter { it.vec != null }
        save()
    }

    fun ensureLoaded() { if (chunks.isEmpty()) chunks = load() }

    fun search(embedder: OnnxEmbedder, query: String, k: Int = 12): List<Hit> {
        ensureLoaded()
        if (chunks.isEmpty()) return emptyList()
        val q = embedder.embed(listOf(expand(query)), 64).first()
        return chunks.map { c -> Hit(c.path, c.title, dot(q, c.vec!!)) }
            .groupBy { it.path }.map { (_, l) -> l.maxBy { it.score } }
            .sortedByDescending { it.score }.take(k)
    }

    private fun expand(q: String) = NeuralBrain.queryText(q)

    private fun save() = runCatching {
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { o ->
            o.writeInt(2); o.writeInt(chunks.size)
            chunks.forEach { c ->
                o.writeUTF(c.path); o.writeUTF(c.title.take(200)); o.writeLong(c.mtime)
                val v = c.vec!!; o.writeShort(v.size); v.forEach { o.writeByte((it * 127f).toInt().coerceIn(-127, 127)) }
            }
        }
    }

    private fun load(): List<Chunk> = runCatching {
        if (!file.isFile) return emptyList()
        DataInputStream(file.inputStream().buffered()).use { i ->
            if (i.readInt() != 2) return emptyList()
            List(i.readInt()) {
                val path = i.readUTF(); val title = i.readUTF(); val mt = i.readLong()
                val n = i.readShort().toInt(); val v = FloatArray(n) { i.readByte() / 127f }
                Chunk(path, title, "", mt, v)
            }
        }
    }.getOrDefault(emptyList())

    private fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
}

// ───────────────────────────── Lectura del proyecto (texto para la red + grafo) ─────────────────────────────

internal object ProjectReader {
    val LIB = listOf("/androidx/", "/android/", "/kotlin/", "/kotlinx/", "/com/google/", "/okhttp3/", "/okio/", "/retrofit2/",
        "/io/reactivex/", "/org/intellij/", "/org/jetbrains/", "/com/squareup/", "/dagger/", "/javax/", "/j$/", "/coil/", "/com/bumptech/")

    /** Paquete propio de la app (del manifest): org.fdroid.fdroid → "org/fdroid". */
    fun ownPrefix(project: File): String? = runCatching {
        val pkg = Regex("""package="([\w.]+)"""").find(File(project, "AndroidManifest.xml").readText())?.groupValues?.get(1) ?: return null
        pkg.split('.').take(2).joinToString("/")
    }.getOrNull()

    /** Código de la app, sin librerías ni clases sintéticas. Si el paquete propio no existe (ofuscada), todo menos librerías conocidas. */
    fun ownSmali(project: File): Sequence<File> {
        val all = File(project, "smali").walkTopDown().filter {
            it.isFile && it.extension == "smali" && !it.name.startsWith("R$") && it.name != "R.smali" && it.name != "BuildConfig.smali" &&
                !it.name.contains("$$") && !it.name.contains("ExternalSynthetic") && !Regex("\\$\\d").containsMatchIn(it.name)
        }
        val prefix = ownPrefix(project)
        if (prefix != null && all.any { it.path.contains("/$prefix/") }) return all.filter { it.path.contains("/$prefix/") }
        return all.filter { f -> LIB.none { f.path.contains(it) } }
    }

    fun words(s: String) = s.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace(Regex("[_$/.;]+"), " ").trim()

    fun chunks(project: File, limit: Int = 6000): List<SemanticIndex.Chunk> {
        val out = ArrayList<SemanticIndex.Chunk>()
        val real = runCatching { Deobfuscator.classNames(project) }.getOrDefault(emptyMap())
        val rel = { f: File -> f.relativeTo(project).path }
        ownSmali(project).take(limit).forEach { f ->
            val t = runCatching { f.readText() }.getOrNull() ?: return@forEach
            val cls = t.lineSequence().first().substringAfterLast(' ').removePrefix("L").removeSuffix(";")
            val sup = Regex("""\.super L([^;]+);""").find(t)?.groupValues?.get(1)?.substringAfterLast('/') ?: ""
            val methods = Regex("""\.method [^\n]*?(\w+)\(""").findAll(t).map { it.groupValues[1] }.filter { it != "<init>" && it != "<clinit>" }.distinct().take(20)
            val strings = Regex("""const-string(?:/jumbo)? [vp]\d+, "([^"]{2,60})"""").findAll(t).map { it.groupValues[1] }.distinct().take(12)
            val uses = Regex("""L([\w/$]+);""").findAll(t).map { it.groupValues[1].substringAfterLast('/') }
                .filter { it.length > 3 && !it.startsWith("String") && !it.startsWith("Object") }.distinct().take(15)
            val realName = real["L$cls;"]?.name
            val text = (realName?.let { "${words(it)} (real name). " } ?: "") +
                "${words(cls.substringAfterLast('/'))} (${words(sup)}) in ${words(cls.substringBeforeLast('/', ""))}. " +
                "methods: ${methods.joinToString { words(it) }}. uses: ${uses.joinToString { words(it) }}. texts: ${strings.joinToString(" | ")}"
            out += SemanticIndex.Chunk(rel(f), realName ?: cls.substringAfterLast('/'), text.take(600), f.lastModified() + (realName?.hashCode() ?: 0))
        }
        File(project, "resources").listFiles().orEmpty().mapNotNull { File(it, "res").takeIf { r -> r.isDirectory } }.forEach { res ->
            res.listFiles { d -> d.isDirectory && (d.name == "layout" || d.name.startsWith("layout-") || d.name == "menu" || d.name == "navigation") }.orEmpty()
                .flatMap { it.listFiles().orEmpty().toList() }.distinctBy { it.name }.forEach { f ->
                    val t = runCatching { f.readText() }.getOrNull() ?: return@forEach
                    val views = Regex("""<([\w.]+)""").findAll(t).map { it.groupValues[1].substringAfterLast('.') }.filter { it != "xml" }.distinct().take(15)
                    val ids = Regex("""@\+id/(\w+)""").findAll(t).map { words(it.groupValues[1]) }.distinct().take(15)
                    val texts = Regex("""android:(?:text|hint|contentDescription)="([^"@]{2,50})"""").findAll(t).map { it.groupValues[1] }.distinct().take(8)
                    out += SemanticIndex.Chunk(rel(f), f.nameWithoutExtension,
                        "${f.parentFile.name} ${words(f.nameWithoutExtension)}: views ${views.joinToString()}; ids ${ids.joinToString()}; texts ${texts.joinToString(" | ")}".take(600), f.lastModified())
                }
            File(res, "values/strings.xml").takeIf { it.isFile }?.let { f ->
                Regex("""<string name="(\w+)"[^>]*>([^<]{1,80})</string>""").findAll(f.readText()).chunked(20).forEachIndexed { i, group ->
                    out += SemanticIndex.Chunk(rel(f), "strings#$i", "texts: " + group.joinToString("; ") { "${words(it.groupValues[1])} = ${it.groupValues[2]}" }.take(700), f.lastModified())
                }
            }
        }
        File(project, "AndroidManifest.xml").takeIf { it.isFile }?.let { f ->
            val comps = Regex("""<(activity|service|receiver|provider)[^>]*android:name="([^"]+)"""").findAll(f.readText()).map { "${it.groupValues[1]} ${words(it.groupValues[2].substringAfterLast('.'))}" }.take(40)
            out += SemanticIndex.Chunk(rel(f), "manifest", "manifest components: ${comps.joinToString()}".take(800), f.lastModified())
        }
        return out
    }
}

// ───────────────────────────── Razonamiento sobre el grafo del proyecto ─────────────────────────────

class ProjectGraph private constructor(
    private val edges: Map<String, List<Pair<String, String>>>,   // nodo -> (destino, relación)
    private val roles: Map<String, List<String>>,                  // nodo -> roles deducidos
    private val realNames: Map<String, String> = emptyMap(),      // archivo -> nombre real deducido (desofuscación)
) {
    fun neighbors(n: String) = edges[n].orEmpty()
    fun rolesOf(n: String) = roles[n].orEmpty()
    val nodeCount get() = (edges.keys + edges.values.flatten().map { it.first }).distinct().size

    /**
     * Razona a partir de unos archivos semilla: recorre el grafo (2 saltos), puntúa lo que conecta
     * y explica en cadenas legibles por qué cada archivo importa.
     */
    fun reason(seeds: List<Pair<String, Float>>, maxChains: Int = 8): Pair<List<String>, Map<String, Float>> {
        val score = HashMap<String, Float>()
        val why = LinkedHashMap<String, String>()
        seeds.forEach { (n, s) -> score[n] = maxOf(score[n] ?: 0f, s) }
        seeds.forEach { (n, s) ->
            neighbors(n).forEach { (m, rel) ->
                score[m] = (score[m] ?: 0f) + s * 0.6f
                why.putIfAbsent(m, "${label(n)} —$rel→ ${label(m)}")
                neighbors(m).forEach { (k, rel2) ->
                    score[k] = (score[k] ?: 0f) + s * 0.3f
                    why.putIfAbsent(k, "${label(n)} —$rel→ ${label(m)} —$rel2→ ${label(k)}")
                }
            }
        }
        val chains = ArrayList<String>()
        seeds.take(5).forEach { (n, _) ->
            val r = rolesOf(n)
            if (r.isNotEmpty()) chains += "${label(n)} es ${r.joinToString(", ")}"
        }
        why.entries.sortedByDescending { score[it.key] ?: 0f }.take(maxChains).forEach { (k, chain) ->
            val r = rolesOf(k); chains += chain + if (r.isNotEmpty()) "  (${label(k)}: ${r.joinToString(", ")})" else ""
        }
        return chains.distinct().take(maxChains + 5) to score
    }

    private fun label(path: String) = path.substringAfterLast('/').let { f -> realNames[path]?.let { "$f ($it)" } ?: f }

    companion object {
        private val cache = HashMap<String, Pair<Long, ProjectGraph>>()

        fun of(project: File): ProjectGraph {
            val stamp = File(project, "$META_DIR/changes.jsonl").lastModified() + File(project, "smali").lastModified() +
                Deobfuscator.mappingFile(project).lastModified()
            cache[project.absolutePath]?.takeIf { it.first == stamp }?.let { return it.second }
            return build(project).also { cache[project.absolutePath] = stamp to it }
        }

        fun build(project: File): ProjectGraph {
            val edges = HashMap<String, MutableList<Pair<String, String>>>()
            val roles = HashMap<String, MutableList<String>>()
            fun edge(a: String, b: String, rel: String) { if (a != b) edges.getOrPut(a) { mutableListOf() }.let { if (it.none { e -> e.first == b }) it += b to rel } }
            fun back(a: String, b: String, rel: String) = edge(b, a, rel)
            val rel = { f: File -> f.relativeTo(project).path }

            // ids de recursos (0x7f…) -> tipo/nombre, y layout -> archivo
            val idName = HashMap<String, Pair<String, String>>()
            val layoutFile = HashMap<String, String>()
            val idsInLayout = HashMap<String, MutableList<String>>()
            File(project, "resources").listFiles().orEmpty().mapNotNull { File(it, "res").takeIf { r -> r.isDirectory } }.forEach { res ->
                File(res, "values/public.xml").takeIf { it.isFile }?.readText()?.let { t ->
                    Regex("""id="0x([0-9a-fA-F]{8})"\s+type="([^"]+)"\s+name="([^"]+)"""").findAll(t)
                        .forEach { idName["0x" + it.groupValues[1].lowercase()] = it.groupValues[2] to it.groupValues[3] }
                }
                res.listFiles { d -> d.isDirectory && d.name.startsWith("layout") }.orEmpty().flatMap { it.listFiles().orEmpty().toList() }.forEach { f ->
                    val name = f.nameWithoutExtension
                    if (name !in layoutFile || f.parentFile.name == "layout") layoutFile[name] = rel(f)
                    val t = runCatching { f.readText() }.getOrDefault("")
                    Regex("""@\+id/(\w+)""").findAll(t).forEach { idsInLayout.getOrPut(it.groupValues[1]) { mutableListOf() } += rel(f) }
                    Regex("""layout="@layout/(\w+)"""").findAll(t).forEach { m -> layoutFile[m.groupValues[1]]?.let { edge(rel(f), it, "incluye") } }
                    if (t.contains("RecyclerView")) roles.getOrPut(rel(f)) { mutableListOf() } += "layout con lista (RecyclerView)"
                    if (t.contains("GridView") || t.contains("GridLayoutManager")) roles.getOrPut(rel(f)) { mutableListOf() } += "cuadrícula"
                }
            }
            // segunda pasada para includes que aparecieron después
            val classFile = HashMap<String, String>()
            val smali = ProjectReader.ownSmali(project).take(20000).toList()
            smali.forEach { f -> runCatching { f.useLines { it.first() } }.getOrNull()?.substringAfterLast(' ')?.let { classFile[it] = rel(f) } }

            smali.forEach { f ->
                val t = runCatching { f.readText() }.getOrNull() ?: return@forEach
                val me = rel(f)
                val r = roles.getOrPut(me) { mutableListOf() }
                val sup = Regex("""\.super (L[^;]+;)""").find(t)?.groupValues?.get(1) ?: ""
                when {
                    sup.contains("Activity") -> r += "una pantalla (Activity)"
                    sup.contains("Fragment") -> r += "una pantalla/sección (Fragment)"
                    sup.contains("Adapter") -> r += "un adaptador de lista (crea cada fila/celda)"
                    sup.contains("ViewHolder") -> r += "el contenedor de una fila/celda (ViewHolder)"
                    sup.contains("Service") -> r += "un servicio en segundo plano"
                    sup.contains("BroadcastReceiver") -> r += "un receptor de eventos"
                    sup.contains("Dialog") -> r += "un diálogo"
                    sup.contains("Application") -> r += "la clase Application (arranque)"
                    sup.contains("View;") || sup.endsWith("Layout;") -> r += "una vista personalizada"
                }
                if (t.contains("LinearLayoutManager;-><init>")) r += "crea una lista vertical (LinearLayoutManager)"
                if (t.contains("GridLayoutManager;-><init>")) r += "crea una cuadrícula (GridLayoutManager)"
                if (t.contains("Landroid/webkit/WebView;")) r += "usa un WebView"
                if (t.contains("Ljava/net/URL;") || t.contains("Lokhttp3/") || t.contains("Lretrofit2/")) r += "hace peticiones de red"
                if (t.contains("Ljavax/crypto/")) r += "usa cifrado"
                if (t.contains("ExoPlayer") || t.contains("Landroid/media/MediaPlayer;")) r += "reproduce vídeo/audio"
                classFile[sup]?.let { edge(me, it, "hereda de") }
                Regex("""(L[\w/$]+;)""").findAll(t).map { it.groupValues[1] }.distinct().forEach { c ->
                    classFile[c]?.let { if (it != me) edge(me, it, "usa") }
                }
                Regex("""const(?:/high16)?\s+[vp]\d+,\s+(0x7f[0-9a-fA-F]{6})""").findAll(t).forEach { m ->
                    val (type, name) = idName[m.groupValues[1].lowercase()] ?: return@forEach
                    when (type) {
                        "layout" -> layoutFile[name]?.let { edge(me, it, "infla"); back(me, it, "lo infla") }
                        "id" -> idsInLayout[name]?.forEach { edge(me, it, "controla la vista $name de") }
                        else -> {}
                    }
                }
            }
            val real = runCatching { Deobfuscator.classNames(project) }.getOrDefault(emptyMap())
            val pathNames = classFile.mapNotNull { (desc, path) -> real[desc]?.let { path to it.name } }.toMap()
            return ProjectGraph(edges, roles.filterValues { it.isNotEmpty() }, pathNames)
        }
    }
}

// ───────────────────────────── Detector de cambios arriesgados (aprende de errores) ─────────────────────────────

/** Regresión logística sobre rasgos del diff. Aprende de compilaciones fallidas y del revisor IA. */
class RiskModel(private val store: File?) {
    private class W(var w: FloatArray = FloatArray(DIM), var b: Float = -1.5f, var examples: Int = 0, var errors: Int = 0)
    private var m: W = runCatching { store?.takeIf { it.isFile }?.let { Gson().fromJson(it.readText(), W::class.java) }?.takeIf { it.w.size == DIM } }.getOrNull() ?: W()

    val examples get() = m.examples
    val errorsSeen get() = m.errors

    private fun feats(e: ChangeEntry): IntArray {
        val ext = e.file.substringAfterLast('.')
        val dir = e.file.substringBeforeLast('/').substringAfterLast('/').substringBefore('-')
        val added = e.diff.lines().filter { it.startsWith("+") && !it.startsWith("+++") }
        val toks = added.flatMap { l ->
            Regex("""[a-z\-/]+(?:/\w+)?|\.\w+|v\d+|p\d+|@\w+/|<\w+""").findAll(l.lowercase()).map { it.value }.toList()
        }.map { if (Regex("v\\d+").matches(it)) (if (it.drop(1).toInt() > 15) "v>15" else "v<=15") else it }.toSet()
        val f = mutableListOf("ext:$ext", "dir:$dir", "who:${e.who.substringBefore(' ')}",
            "add:" + bucket(e.added), "rem:" + bucket(e.removed), "ratio:" + (if (e.removed == 0) "new" else bucket(e.added / maxOf(1, e.removed))))
        f += toks.map { "tok:$ext:$it" }
        return f.map { (it.hashCode() and 0x7fffffff) % DIM }.distinct().toIntArray()
    }

    private fun bucket(n: Int) = when { n <= 0 -> "0"; n <= 2 -> "1-2"; n <= 10 -> "3-10"; n <= 50 -> "11-50"; else -> "50+" }

    fun risk(e: ChangeEntry): Float { var z = m.b; feats(e).forEach { z += m.w[it] }; return (1.0 / (1.0 + exp(-z.toDouble()))).toFloat() }

    /** [broke] = true si ese cambio rompió la compilación o el revisor lo rechazó. */
    @Synchronized
    fun learn(entries: List<ChangeEntry>, broke: Boolean) {
        if (entries.isEmpty()) return
        repeat(4) {
            entries.forEach { e ->
                val x = feats(e); val p = risk(e)
                val g = (p - (if (broke) 1f else 0f)) * (if (broke) 2f else 1f)
                x.forEach { m.w[it] -= 0.25f * g }
                m.b -= 0.05f * g
            }
        }
        m.examples += entries.size; if (broke) m.errors += entries.size
        runCatching { store?.apply { parentFile?.mkdirs(); writeText(Gson().toJson(m)) } }
    }

    companion object { private const val DIM = 1 shl 13 }
}

// ───────────────────────────── Memoria de experiencias (aprende de sus errores al instante) ─────────────────────────────

/**
 * Cada vez que la IA termina un pedido, se guarda: el pedido (y su vector de significado) y los
 * archivos que de verdad se editaron. Ante un pedido PARECIDO, esos archivos suben arriba aunque la
 * red todavía no haya generalizado. Es lo que corrige sus errores desde la primera vez.
 */
class ExperienceMemory(private val file: File) {
    data class Exp(val query: String, val vec: FloatArray?, val files: List<String>, val time: Long, var uses: Int = 0)
    private val gson = Gson()
    private var list: MutableList<Exp> = runCatching { gson.fromJson(file.readText(), Array<Exp>::class.java).toMutableList() }.getOrNull() ?: mutableListOf()
    val size get() = list.size

    fun add(query: String, vec: FloatArray?, files: Collection<String>) {
        list += Exp(query.take(300), vec, files.toList().take(20), System.currentTimeMillis())
        if (list.size > 300) list = list.takeLast(300).toMutableList()
        runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(list)) }
    }

    /** Archivos recordados para pedidos parecidos, con su similitud (0..1). */
    fun recall(query: String, vec: FloatArray?): Map<String, Float> {
        val out = HashMap<String, Float>()
        val qt = NeuralEngine.tokens(query)
        list.forEach { e ->
            val sim = if (vec != null && e.vec != null) vec.indices.sumOf { (vec[it] * e.vec[it]).toDouble() }.toFloat()
                else { val et = NeuralEngine.tokens(e.query); if (qt.isEmpty() || et.isEmpty()) 0f else (qt intersect et).size.toFloat() / (qt union et).size }
            val threshold = if (vec != null && e.vec != null) 0.55f else 0.34f
            if (sim >= threshold) e.files.forEach { f -> out[f] = maxOf(out[f] ?: 0f, sim) }
        }
        return out
    }
}

// ───────────────────────────── Fachada: el cerebro ─────────────────────────────

object NeuralBrain {
    data class Thought(
        val semantic: List<SemanticIndex.Hit>,
        val chains: List<String>,
        val ranked: List<Pair<String, Float>>,
        val risks: List<Pair<String, Float>>,
        val backend: String?,
    ) {
        fun format(): String = buildString {
            if (semantic.isEmpty() && chains.isEmpty() && risks.isEmpty()) return ""
            appendLine("RAZONAMIENTO DEL MOTOR NEURONAL DE LA APP${backend?.let { " ($it)" } ?: ""}:")
            if (ranked.isNotEmpty()) {
                appendLine("Archivos más probables para este pedido (significado + conexiones + lo aprendido):")
                ranked.take(8).forEach { (p, s) -> appendLine("  • $p  (${(s * 100).toInt()}%)") }
            }
            if (chains.isNotEmpty()) { appendLine("Lo que deduje de cómo está conectado el proyecto:"); chains.forEach { appendLine("  → $it") } }
            if (risks.isNotEmpty()) { appendLine("Cambios recientes que parecen arriesgados (aprendido de compilaciones fallidas):"); risks.forEach { (f, r) -> appendLine("  ⚠ $f  (riesgo ${(r * 100).toInt()}%)") } }
        }
    }

    data class Stats(val backend: String?, val msPerText: Double, val rankerExamples: Int, val precision: Int?, val mistakesFixed: Int,
                     val riskExamples: Int, val riskErrors: Int, val recent: List<String>)

    /** La app lo configura: carpeta donde guardar lo aprendido y cómo cargar el modelo. */
    @Volatile var storeDir: File? = null
        set(v) { field = v; risk = RiskModel(v?.let { File(it, "risk.json") }); statsFile = v?.let { File(it, "stats.json") }; loadStats() }
    @Volatile var modelLoader: (() -> OnnxEmbedder?)? = null
    /** Carga el modelo que razona (si el usuario lo descargó). */
    @Volatile var reasonerLoader: (() -> LocalReasoner?)? = null
    @Volatile private var reasoner: LocalReasoner? = null
    @Volatile private var reasonerFailed = false

    @Synchronized
    fun reasoner(): LocalReasoner? {
        if (reasoner == null && !reasonerFailed) reasoner = runCatching { reasonerLoader?.invoke() }.getOrNull().also { if (it == null && reasonerLoader != null) reasonerFailed = true }
        return reasoner
    }
    fun setReasonerForTests(r: LocalReasoner?) { reasoner = r; reasonerFailed = r == null }
    /** Al descargar otro modelo, se vuelve a cargar. */
    @Synchronized fun resetReasoner() { runCatching { reasoner?.close() }; reasoner = null; reasonerFailed = false }

    data class Deliberation(val thinking: String, val conclusion: String, val backend: String, val millis: Long) {
        fun format() = "CONCLUSIÓN DEL RAZONADOR LOCAL (modelo que piensa en el móvil, $backend; verifícala con las herramientas antes de actuar):\n$conclusion"
    }

    /**
     * El razonador local PIENSA sobre el pedido con los hechos que preparó el resto del motor
     * (significado, grafo, nombres desofuscados, recetas). Devuelve su razonamiento y su conclusión.
     */
    fun deliberate(query: String, facts: String, maxOutput: Int = 900): Deliberation? {
        val r = reasoner() ?: return null
        val res = runCatching {
            r.reason(
                "Eres el razonador local de ApkForge, experto en apps Android decompiladas (smali y recursos XML). Piensa paso a paso " +
                    "usando SOLO los hechos dados. Responde en español, breve: 1) qué quiere el usuario, 2) qué archivos concretos tocar y por qué, " +
                    "3) el cambio exacto en cada uno, 4) riesgos. Si falta información, di qué hay que buscar.",
                "HECHOS DEL PROYECTO:\n${facts.take(5000)}\n\nPEDIDO DEL USUARIO:\n${query.take(1500)}",
                think = true, budget = 700, maxOutput = maxOutput,
            )
        }.getOrNull() ?: return null
        if (res.answer.isBlank()) return null
        history.recent += "🧠 Razonó “${query.take(40)}” en ${res.millis / 1000}s"
        saveStats()
        return Deliberation(res.thinking, res.answer, r.backend, res.millis)
    }

    /**
     * La red entiende mejor inglés: el pedido se traduce con el diccionario de la app y se ponen
     * primero las palabras traducidas (lo que más pesa para el significado).
     */
    fun queryText(q: String): String {
        val words = Regex("[\\p{L}0-9]+").findAll(q).map { it.value }.toList()
        val en = words.mapNotNull { ContextFinder.translate(it) ?: it.takeIf { w -> w.all { c -> c.code < 128 } && w.length > 2 && ContextFinder.translate(w) == null && w.lowercase() !in ES_STOP } }
        return (en.joinToString(" ") + " | " + q).trim()
    }
    private val ES_STOP = setOf("que", "los", "las", "una", "uno", "del", "con", "por", "para", "como", "esta", "este", "quiero", "pon", "hay", "sea", "mas", "la", "el", "de")

    private fun queryVec(q: String) = runCatching { embedder()?.embed(listOf(queryText(q)), 64)?.first() }.getOrNull()

    private val memories = java.util.concurrent.ConcurrentHashMap<String, ExperienceMemory>()
    fun memory(project: File) = memories.getOrPut(project.absolutePath) { ExperienceMemory(File(project, "$META_DIR/neural/memory.json")) }

    /** Lo que predijo ANTES de que la IA trabajara (para saber si acertó o se equivocó). */
    private val lastPrediction = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    @Volatile private var embedder: OnnxEmbedder? = null
    @Volatile private var loadFailed = false
    var risk = RiskModel(null); private set

    private data class History(var hits: MutableList<Boolean> = mutableListOf(), var mistakes: Int = 0, var recent: MutableList<String> = mutableListOf())
    private var statsFile: File? = null
    private var history = History()
    private fun loadStats() { history = runCatching { statsFile?.takeIf { it.isFile }?.let { Gson().fromJson(it.readText(), History::class.java) } }.getOrNull() ?: History() }
    private fun saveStats() = runCatching { statsFile?.apply { parentFile?.mkdirs(); writeText(Gson().toJson(history)) } }

    @Synchronized
    fun embedder(): OnnxEmbedder? {
        if (embedder == null && !loadFailed) embedder = runCatching { modelLoader?.invoke() }.getOrNull().also { if (it == null) loadFailed = true }
        return embedder
    }

    fun setEmbedderForTests(e: OnnxEmbedder?) { embedder = e; loadFailed = e == null }

    fun stats() = Stats(embedder?.backend, embedder?.msPerText ?: 0.0, NeuralEngine.shared.examples,
        history.hits.takeLast(20).takeIf { it.size >= 3 }?.let { l -> l.count { it } * 100 / l.size }, history.mistakes,
        risk.examples, risk.errorsSeen, history.recent.takeLast(8).reversed())

    fun reset() {
        storeDir?.let { File(it, "risk.json").delete(); File(it, "stats.json").delete() }
        risk = RiskModel(storeDir?.let { File(it, "risk.json") }); history = History()
        NeuralEngine.shared.reset()
    }

    /** Prepara el índice semántico del proyecto (incremental). */
    fun index(project: File, listener: ProgressListener = ProgressListener.NONE): Int {
        val e = embedder() ?: return 0
        return SemanticIndex(project).apply { build(e, listener) }.size
    }

    /** Piensa sobre un pedido: significado + grafo + aprendizaje + riesgos. */
    fun think(project: File, query: String, keywordHits: List<ContextFinder.Hit> = emptyList(), listener: ProgressListener = ProgressListener.NONE): Thought {
        val e = embedder()
        val semantic = if (e != null) runCatching {
            val idx = SemanticIndex(project)
            idx.build(e, listener)
            idx.search(e, query, 12)
        }.getOrDefault(emptyList()) else emptyList()
        val graph = runCatching { ProjectGraph.of(project) }.getOrNull()
        val seeds = (semantic.map { it.path to it.score } +
            keywordHits.map { it.path }.distinct().take(8).map { it to 0.5f }).groupBy { it.first }.map { (p, l) -> p to l.maxOf { it.second } }
            .sortedByDescending { it.second }.take(10)
        val (chains, graphScore) = graph?.reason(seeds) ?: (emptyList<String>() to emptyMap())
        val sem = semantic.associate { it.path to it.score }
        val qv = queryVec(query)
        val remembered = memory(project).recall(query, qv)
        val candidates = (MessagePreparer.candidates(project, keywordHits) + semantic.map { NeuralEngine.Candidate(it.path) } +
            graphScore.keys.map { NeuralEngine.Candidate(it) } + remembered.keys.map { NeuralEngine.Candidate(it) }).distinctBy { it.path }
            .map { it.copy(semantic = sem[it.path] ?: 0f, graph = (graphScore[it.path] ?: 0f).coerceAtMost(1f)) }
        val touched = ChangeLog.all(project).map { it.file }.toSet()
        // Mezcla: lo que entiende por significado y el grafo pesan más al principio; lo aprendido
        // va ganando peso a medida que la red acumula ejemplos de la IA.
        val learned = NeuralEngine.shared.rank(query, candidates, touched).associate { it.path to it.score }
        val alpha = (NeuralEngine.shared.examples / 15f).coerceIn(0f, 0.6f)
        val kw = keywordHits.groupingBy { it.path }.eachCount()
        val ranked = candidates.map { c ->
            val prior = 0.65f * c.semantic + 0.2f * c.graph + 0.15f * ((kw[c.path] ?: 0) / 3f).coerceAtMost(1f)
            val mem = remembered[c.path] ?: 0f                       // experiencia: lo que se editó en pedidos parecidos
            c.path to ((1 - alpha) * prior + alpha * (learned[c.path] ?: 0f) + 0.8f * mem).coerceAtMost(1f)
        }.sortedByDescending { it.second }
        val recalled = remembered.entries.sortedByDescending { it.value }.take(3).map { it.key }
        lastPrediction[project.absolutePath] = ranked.take(5).map { it.first }
        val risks = ChangeLog.sinceLastExport(project).groupBy { it.file }.mapNotNull { (f, l) ->
            val r = l.maxOf { risk.risk(it) }; if (r >= 0.6f && risk.examples >= 5) f to r else null
        }.sortedByDescending { it.second }.take(5)
        val allChains = (recalled.map { "Por experiencia: en un pedido parecido la IA editó ${it.substringAfterLast('/')}" } + chains)
        return Thought(semantic, allChains, ranked, risks, e?.backend)
    }

    /**
     * Aprende de lo que hizo la IA. Antes de entrenar comprueba si había acertado (¿estaban los
     * archivos editados entre sus 5 primeras sugerencias?) para medir cómo mejora.
     */
    fun learnFromEdit(project: File, query: String, since: Long, keywordHits: List<ContextFinder.Hit> = emptyList()): Int {
        val changed = ChangeLog.all(project).filter { it.time >= since && it.who.startsWith("IA") }.map { it.file }.toSet()
        if (changed.isEmpty()) return 0
        val predicted = lastPrediction.remove(project.absolutePath)
        val t = runCatching { think(project, query, keywordHits) }.getOrNull()
        val top = predicted ?: t?.ranked?.take(5)?.map { it.first }.orEmpty()
        val hit = top.any { it in changed }
        history.hits += hit; if (history.hits.size > 200) history.hits = history.hits.takeLast(200).toMutableList()
        if (!hit) {
            history.mistakes++
            history.recent += "✖ “${query.take(50)}”: sugerí ${top.firstOrNull()?.substringAfterLast('/') ?: "nada"}, la IA editó ${changed.first().substringAfterLast('/')} → corregido"
        } else history.recent += "✔ “${query.take(50)}”: acerté ${changed.first { it in top }.substringAfterLast('/')}"
        if (history.recent.size > 30) history.recent = history.recent.takeLast(30).toMutableList()
        saveStats()
        val sem = t?.semantic?.associate { it.path to it.score }.orEmpty()
        val cands = (MessagePreparer.candidates(project, keywordHits) + (t?.ranked?.map { NeuralEngine.Candidate(it.first) } ?: emptyList()))
            .distinctBy { it.path }.map { it.copy(semantic = sem[it.path] ?: 0f) }
        memory(project).add(query, queryVec(query), changed)
        NeuralEngine.shared.learn(query, cands, changed, ChangeLog.all(project).filter { it.time < since }.map { it.file }.toSet(), epochs = if (hit) 4 else 12)
        return changed.size
    }

    /** Aprende de la compilación: lo que falló era arriesgado; lo que compiló, no. */
    fun learnFromBuild(project: File, brokenFiles: Set<String>, pending: List<ChangeEntry>) {
        val (bad, good) = pending.partition { it.file in brokenFiles }
        if (bad.isNotEmpty()) { risk.learn(bad, true); history.recent += "✖ Compilación: aprendí que cambios como ${bad.first().file.substringAfterLast('/')} rompen"; saveStats() }
        if (good.isNotEmpty()) risk.learn(good, false)
    }

    /** Aprende del revisor IA (destilación): su veredicto enseña al detector de riesgo. */
    fun learnFromReview(entries: List<ChangeEntry>, ok: Boolean) {
        risk.learn(entries, !ok)
        if (!ok) { history.recent += "🔍 El revisor rechazó cambios en ${entries.map { it.file.substringAfterLast('/') }.distinct().take(2).joinToString()} → aprendido"; saveStats() }
    }
}
