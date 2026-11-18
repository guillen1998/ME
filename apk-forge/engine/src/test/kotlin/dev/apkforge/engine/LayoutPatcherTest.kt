package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/** Pasar una lista de filas a cuadrícula (filas y columnas) y que el APK compile. */
class LayoutPatcherTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun listBecomesGridAndCompiles() {
        assumeTrue(sample != null)
        val root = File(System.getProperty("java.io.tmpdir"), "gridtest").apply { deleteRecursively(); mkdirs() }
        val proj = File(root, "proj")
        ApkProject.decompile(sample!!, proj, DecompileOptions())
        // Pantalla de canales simulada: una lista creada en código (como la del usuario) y otra en XML
        File(proj, "smali/classes/com/demo/ChannelsActivity.smali").apply { parentFile.mkdirs() }.writeText("""
.class public Lcom/demo/ChannelsActivity;
.super Ljava/lang/Object;

.method public static setup(Landroid/content/Context;Landroidx/recyclerview/widget/RecyclerView;)V
    .locals 2

    new-instance v0, Landroidx/recyclerview/widget/LinearLayoutManager;

    invoke-direct {v0, p0}, Landroidx/recyclerview/widget/LinearLayoutManager;-><init>(Landroid/content/Context;)V

    invoke-virtual {p1, v0}, Landroidx/recyclerview/widget/RecyclerView;->setLayoutManager(Landroidx/recyclerview/widget/RecyclerView${'$'}LayoutManager;)V

    new-instance v0, Landroidx/recyclerview/widget/LinearLayoutManager;

    const/4 v1, 0x1

    invoke-direct {v0, p0, v1, v1}, Landroidx/recyclerview/widget/LinearLayoutManager;-><init>(Landroid/content/Context;IZ)V

    return-void
.end method
""".trimStart())
        File(proj, "resources/package_1/res/layout/demo_channels.xml").writeText("""<?xml version="1.0" encoding="utf-8"?>
<androidx.recyclerview.widget.RecyclerView xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent" android:layout_height="match_parent"
    app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager" />""")
        val sites = LayoutPatcher.find(proj)
        println("Listas: ${sites.size}"); sites.take(8).forEach { println("  ${it.owner} ${it.method} ${it.file.name}:${it.line} ${it.kind}") }
        assertTrue(sites.isNotEmpty())
        var done = 0
        repeat(sites.size) {
            val s = LayoutPatcher.find(proj).firstOrNull() ?: return@repeat // las líneas se mueven tras cada cambio
            val r = runCatching { LayoutPatcher.toGrid(proj, s, 3) }
            println("  → ${r.getOrElse { "✖ " + it.message }}")
            if (r.isSuccess) done++
        }
        assertTrue(done == 3, "Se esperaban 3 listas convertidas")
        val out = File(root, "out.apk")
        ApkProject.build(proj, out)
        assertTrue(ApkInspector.verify(out).verified)
        val d = JavaDecompiler.open(out)
        val code = d.javaCode("com.demo.ChannelsActivity")
        assertTrue(code.contains("GridLayoutManager"), "Java tras exportar no muestra GridLayoutManager")
        println(code.lines().filter { it.contains("GridLayoutManager") }.take(3).joinToString("\n"))
    }
}
