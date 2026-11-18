package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class ApkArchiveTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    @Test fun browseEditSave() {
        assumeTrue(sample != null)
        ApkArchive.open(sample!!).use { a ->
            val root = a.list("")
            println("Raíz (${a.totalEntries} entradas): " + root.joinToString { (if (it.isDir) "${it.name}/(${it.count})" else it.name) })
            assertTrue(root.any { it.name == "AndroidManifest.xml" } && root.any { it.isDir && it.name == "res" })
            assertTrue(a.isBinaryXml("AndroidManifest.xml"))

            val xml = a.decodeXml("AndroidManifest.xml")
            println(xml.take(500))
            assertTrue(xml.contains("<manifest") && xml.contains("android:versionName"))

            // Editar el manifest binario directamente dentro del APK
            val edited = xml.replace(Regex("android:versionName=\"[^\"]*\""), "android:versionName=\"9.9.9-forge\"")
            a.encodeXml("AndroidManifest.xml", edited)
            a.replace("assets/forge.txt", "hola desde ApkForge".toByteArray())
            val victim = a.list("").first { !it.isDir && it.name.endsWith(".properties") || it.name == "DebugProbesKt.bin" }
            a.delete(victim.path)
            assertTrue(a.pendingCount == 3)

            val out = File(System.getProperty("java.io.tmpdir"), "archive-mod.apk")
            a.save(out)
            val info = ApkInspector.inspect(out)
            println("Tras guardar: versionName=${info.versionName} firma=${info.signature?.verified} borrado=${victim.path}")
            assertTrue(info.versionName == "9.9.9-forge")
            assertTrue(info.signature?.verified == true)
            ApkArchive.open(out).use { b ->
                assertTrue(String(b.read("assets/forge.txt")) == "hola desde ApkForge")
                assertTrue(!b.exists(victim.path))
            }
        }
    }
}
