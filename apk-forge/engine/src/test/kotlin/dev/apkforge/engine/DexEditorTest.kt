package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DexEditorTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun editOneClassFast() {
        assumeTrue(sample != null)
        val t0 = System.currentTimeMillis()
        DexEditor.open(sample!!).use { ed ->
            println("Clases en el APK: ${ed.classes.size} (abierto en ${System.currentTimeMillis() - t0} ms)")
            val target = ed.classes.first { it.javaName.endsWith("MainActivity") || it.javaName.startsWith("org.fdroid") }
            val t1 = System.currentTimeMillis()
            val src = ed.smali(target.type)
            println("smali de ${target.javaName} (${target.dex}) en ${System.currentTimeMillis() - t1} ms, ${src.lines().size} líneas")

            // Error de sintaxis -> debe rechazarlo sin aplicar
            assertFailsWith<EngineException> { ed.replace(target.type, src.replaceFirst(".method", ".methxd")) }
            assertTrue(ed.modifiedCount == 0)

            // Cambio válido: añadir un campo
            val edited = src.replaceFirst("\n# ", "\n.field public static apkforgeMarker:I\n\n# ")
            ed.replace(target.type, edited)
            val out = File(System.getProperty("java.io.tmpdir"), "dexedit.apk")
            val t2 = System.currentTimeMillis()
            ed.save(out)
            println("Guardado + firmado en ${System.currentTimeMillis() - t2} ms")
            assertTrue(ApkInspector.verify(out).verified)

            // Comprobar que el campo está en el APK resultante
            DexEditor.open(out).use { ed2 ->
                assertTrue(ed2.smali(target.type).contains("apkforgeMarker"), "El cambio debe estar en el nuevo APK")
                assertTrue(ed2.classes.size == ed.classes.size)
            }
        }
    }
}
