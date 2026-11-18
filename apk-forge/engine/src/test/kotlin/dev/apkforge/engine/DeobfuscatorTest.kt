package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** App ofuscada simulada: la app deduce nombres reales, la IA completa, y el Java los muestra. */
class DeobfuscatorTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun deduceAndApplyNames() {
        assumeTrue(sample != null)
        val root = File(System.getProperty("java.io.tmpdir"), "deobf").apply { deleteRecursively(); mkdirs() }
        val proj = File(root, "proj")
        ApkProject.decompile(sample!!, proj, DecompileOptions())
        val res = File(proj, "resources/package_1/res")
        File(res, "layout/item_channel.xml").writeText("""<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="wrap_content">
  <TextView android:id="@+id/channel_name" android:layout_width="wrap_content" android:layout_height="wrap_content" /></LinearLayout>""")
        ResourceIds.registerNew(proj)
        val pub = File(res, "values/public.xml").readText()
        fun id(type: String, name: String) = Regex("""id="(0x[0-9a-f]{8})" type="$type" name="$name"""").find(pub)!!.groupValues[1]
        val layoutId = id("layout", "item_channel"); val nameId = id("id", "channel_name")
        val dir = File(proj, "smali/classes/zz").apply { mkdirs() }
        // data class ofuscada: toString revela nombre y campos
        File(dir, "a.smali").writeText("""
.class public final Lzz/a;
.super Ljava/lang/Object;
.source "SourceFile"

.field public final a:Ljava/lang/String;
.field public final b:I

.method public constructor <init>(Ljava/lang/String;I)V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    iput-object p1, p0, Lzz/a;->a:Ljava/lang/String;
    iput p2, p0, Lzz/a;->b:I
    return-void
.end method

.method public final c()Ljava/lang/String;
    .locals 1
    iget-object v0, p0, Lzz/a;->a:Ljava/lang/String;
    return-object v0
.end method

.method public toString()Ljava/lang/String;
    .locals 3
    new-instance v0, Ljava/lang/StringBuilder;
    const-string v1, "Channel(name="
    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    iget-object v1, p0, Lzz/a;->a:Ljava/lang/String;
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    const-string v1, ", number="
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    iget v1, p0, Lzz/a;->b:I
    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method
""".trimStart())
        // ViewHolder ofuscado: findViewById(R.id.channel_name) → campo
        File(dir, "b.smali").writeText("""
.class public final Lzz/b;
.super Landroidx/recyclerview/widget/RecyclerView${'$'}ViewHolder;
.source "SourceFile"

.field public final a:Landroid/widget/TextView;

.method public constructor <init>(Landroid/view/View;)V
    .locals 1
    invoke-direct {p0, p1}, Landroidx/recyclerview/widget/RecyclerView${'$'}ViewHolder;-><init>(Landroid/view/View;)V
    const v0, $nameId
    invoke-virtual {p1, v0}, Landroid/view/View;->findViewById(I)Landroid/view/View;
    move-result-object v0
    check-cast v0, Landroid/widget/TextView;
    iput-object v0, p0, Lzz/b;->a:Landroid/widget/TextView;
    return-void
.end method
""".trimStart())
        // Adapter ofuscado que infla item_channel
        File(dir, "c.smali").writeText("""
.class public final Lzz/c;
.super Landroidx/recyclerview/widget/RecyclerView${'$'}Adapter;
.source "SourceFile"

.field public final a:Ljava/util/List;

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroidx/recyclerview/widget/RecyclerView${'$'}Adapter;-><init>()V
    return-void
.end method

.method public final d(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;)Landroid/view/View;
    .locals 2
    const v0, $layoutId
    const/4 v1, 0x0
    invoke-virtual {p1, v0, p2, v1}, Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;Z)Landroid/view/View;
    move-result-object v0
    return-object v0
.end method
""".trimStart())
        val rep = Deobfuscator.analyze(proj, ProgressListener { println("  $it") })
        val m = Deobfuscator.load(proj)
        m.filter { it.cls.startsWith("Lzz/") }.forEach { println("  ${it.kind} ${it.cls}.${it.obf} → ${it.name} (${it.conf}) ${it.evidence}") }
        fun nameOf(kind: String, cls: String, obf: String) = m.firstOrNull { it.kind == kind && it.cls == cls && it.obf == obf }?.name
        assertEquals("Channel", nameOf("class", "Lzz/a;", "a"))
        assertEquals("name", nameOf("field", "Lzz/a;", "a"))
        assertEquals("number", nameOf("field", "Lzz/a;", "b"))
        assertEquals("getName", nameOf("method", "Lzz/a;", "c"))
        assertEquals("ChannelViewHolder", nameOf("class", "Lzz/b;", "b"))
        assertEquals("channelName", nameOf("field", "Lzz/b;", "a"))
        assertEquals("ChannelAdapter", nameOf("class", "Lzz/c;", "c"))

        // La IA completa un método que la app no pudo nombrar
        val fakeAi = object : AiTextClient {
            override fun complete(system: String, user: String) =
                """{"classes":[],"methods":[{"class":"Lzz/c;","method":"d(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;)Landroid/view/View;","name":"inflateRow","why":"infla item_channel"}],"fields":[{"class":"Lzz/c;","field":"a:Ljava/util/List;","name":"channels","why":"lista del adaptador"}]}"""
        }
        // forzar que la clase c esté pendiente para la IA (bajando su confianza) y refinar
        Deobfuscator.save(proj, Deobfuscator.load(proj).map { if (it.cls == "Lzz/c;" && it.kind == "class") it.copy(conf = 0.5f) else it })
        val added = Deobfuscator.refineWithAi(proj, fakeAi, ProgressListener { if (!it.startsWith("🤖")) println("  $it") }, limit = 2000)
        assertTrue(added >= 2)
        // Tú mandas
        Deobfuscator.rename(proj, "class", "zz.c", "", "ChannelListAdapter", "Tú", "lo sé yo")

        // Java legible con los nombres
        val out = File(root, "out.apk")
        ApkProject.build(proj, out)
        val java = JavaDecompiler.open(out, JavaDecompilerOptions(renames = Deobfuscator.jadxRenames(proj)))
        val codeA = java.javaCode("zz.a"); val codeC = java.javaCode("zz.c"); val codeB = java.javaCode("zz.b")
        println(codeA.lines().filter { it.contains("class") || it.contains("String name") || it.contains("getName") }.joinToString("\n"))
        println(codeC.lines().filter { it.contains("class") || it.contains("inflateRow") || it.contains("channels") }.joinToString("\n"))
        println(codeB.lines().filter { it.contains("class") || it.contains("channelName") }.joinToString("\n"))
        assertTrue(codeA.contains("class Channel") && codeA.contains("name") && codeA.contains("getName()"))
        assertTrue(codeC.contains("ChannelListAdapter") && codeC.contains("inflateRow") && codeC.contains("channels"))
        assertTrue(codeB.contains("channelName"))
        println(Deobfuscator.contextFor(proj, listOf("smali/classes/zz/c.smali", "smali/classes/zz/a.smali")))
        println("Informe: $rep")
    }
}
