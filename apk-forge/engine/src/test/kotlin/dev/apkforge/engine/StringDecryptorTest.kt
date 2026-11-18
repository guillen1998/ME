package dev.apkforge.engine

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringDecryptorTest {
    private val sample: File? = System.getProperty("apkforge.sampleApk")?.let(::File)?.takeIf { it.isFile }

    private fun xorB64(s: String, key: String) = Base64.getEncoder().encodeToString(s.toByteArray().mapIndexed { i, b -> (b.toInt() xor key[i % key.length].code).toByte() }.toByteArray())
    private fun esc(s: String) = s.map { c -> when {
        c == '\\' -> "\\\\"; c == '"' -> "\\\""; c.code < 32 || c.code > 126 -> "\\u%04x".format(c.code); else -> c.toString() } }.joinToString("")

    @Test fun decryptsStringsTogether() {
        assumeTrue(sample != null)
        val root = File(System.getProperty("java.io.tmpdir"), "strdec").apply { deleteRecursively(); mkdirs() }
        val proj = File(root, "proj")
        ApkProject.decompile(sample!!, proj, DecompileOptions(decodeResources = false))
        // En una app real sin cifrado no debe inventar descifradores
        val t0 = System.currentTimeMillis()
        val clean = StringDecryptor.findDecryptors(proj)
        println("App limpia: ${clean.size} descifrador(es) en ${System.currentTimeMillis() - t0} ms " + clean.take(5).joinToString { it.label + "×" + it.sites.size })
        val cleanRun = StringDecryptor.run(proj)
        println("Resultado en app limpia: $cleanRun")
        assertTrue(StringDecryptor.load(proj).isEmpty(), "No debía 'descifrar' nada en una app sin cifrado")
        val dir = File(proj, "smali/classes/yy").apply { mkdirs() }
        val plainA = listOf("https://api.ejemplo.com/canales", "token_secreto_123", "Bienvenido a la app")
        val plainB = listOf("https://cdn.ejemplo.com/logos/", "premium_activado", "Error de conexión")
        val plainC = listOf("https://stream.ejemplo.com/live.m3u8", "Usuario no válido", "clave_api_456")
        val plainD = listOf("https://pagos.ejemplo.com/v2", "Suscripción caducada", "id_dispositivo")

        // A) Base64 + XOR con clave en un campo estático (<clinit>) → lo ejecuta el intérprete
        File(dir, "s.smali").writeText("""
.class public final Lyy/s;
.super Ljava/lang/Object;

.field private static final a:Ljava/lang/String;

.method static constructor <clinit>()V
    .locals 1
    const-string v0, "k3y"
    sput-object v0, Lyy/s;->a:Ljava/lang/String;
    return-void
.end method

.method public static a(Ljava/lang/String;)Ljava/lang/String;
    .locals 6
    const/4 v0, 0x0
    invoke-static {p0, v0}, Landroid/util/Base64;->decode(Ljava/lang/String;I)[B
    move-result-object v1
    sget-object v2, Lyy/s;->a:Ljava/lang/String;
    const/4 v3, 0x0
    :loop
    array-length v4, v1
    if-ge v3, v4, :done
    aget-byte v4, v1, v3
    invoke-virtual {v2}, Ljava/lang/String;->length()I
    move-result v5
    rem-int v5, v3, v5
    invoke-virtual {v2, v5}, Ljava/lang/String;->charAt(I)C
    move-result v5
    xor-int/2addr v4, v5
    int-to-byte v4, v4
    aput-byte v4, v1, v3
    add-int/lit8 v3, v3, 0x1
    goto :loop
    :done
    new-instance v0, Ljava/lang/String;
    const-string v2, "UTF-8"
    invoke-direct {v0, v1, v2}, Ljava/lang/String;-><init>([BLjava/lang/String;)V
    return-object v0
.end method
""".trimStart())
        // B) Tabla por índice: b(I) descifra char a char con XOR 7 usando StringBuilder
        val tableB = plainB.map { p -> p.map { (it.code xor 7).toChar() }.joinToString("") }
        File(dir, "t.smali").writeText("""
.class public final Lyy/t;
.super Ljava/lang/Object;

.field private static final a:[Ljava/lang/String;

.method static constructor <clinit>()V
    .locals 3
    const/4 v0, ${"0x" + tableB.size.toString(16)}
    new-array v0, v0, [Ljava/lang/String;
${tableB.mapIndexed { i, s -> "    const/4 v1, 0x${i.toString(16)}\n    const-string v2, \"${esc(s)}\"\n    aput-object v2, v0, v1" }.joinToString("\n")}
    sput-object v0, Lyy/t;->a:[Ljava/lang/String;
    return-void
.end method

.method public static b(I)Ljava/lang/String;
    .locals 5
    sget-object v0, Lyy/t;->a:[Ljava/lang/String;
    aget-object v0, v0, p0
    new-instance v1, Ljava/lang/StringBuilder;
    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V
    const/4 v2, 0x0
    :loop
    invoke-virtual {v0}, Ljava/lang/String;->length()I
    move-result v3
    if-ge v2, v3, :done
    invoke-virtual {v0, v2}, Ljava/lang/String;->charAt(I)C
    move-result v3
    xor-int/lit8 v3, v3, 0x7
    int-to-char v3, v3
    invoke-virtual {v1, v3}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;
    add-int/lit8 v2, v2, 0x1
    goto :loop
    :done
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method
""".trimStart())
        // C) Usa una API no permitida (Context) → el intérprete se detiene; es Base64+XOR("Zq9") → lo encuentra la app probando
        File(dir, "c.smali").writeText("""
.class public final Lyy/c;
.super Ljava/lang/Object;

.method public static c(Ljava/lang/String;)Ljava/lang/String;
    .locals 2
    const-string v0, "Zq9"
    invoke-static {}, Lyy/c;->ctx()Landroid/content/Context;
    move-result-object v1
    invoke-virtual {v1}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
    return-object p0
.end method

.method public static ctx()Landroid/content/Context;
    .locals 1
    const/4 v0, 0x0
    return-object v0
.end method
""".trimStart())
        // D) Invertir + XOR por carácter con "Q" + bloqueado para el intérprete y raro para las pruebas → la IA
        fun encD(s: String) = s.map { (it.code xor "Wz".let { k -> k[s.indexOf(it) % 2].code }).toChar() }.joinToString("")
        fun encD2(s: String) = s.mapIndexed { i, ch -> (ch.code xor "Wz"[i % 2].code).toChar() }.joinToString("").reversed()
        File(dir, "d.smali").writeText("""
.class public final Lyy/d;
.super Ljava/lang/Object;

.method public static d(Ljava/lang/String;)Ljava/lang/String;
    .locals 2
    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
    return-object p0
.end method
""".trimStart())
        // Quien los usa
        val calls = StringBuilder()
        plainA.forEach { calls.append("    const-string v0, \"${esc(xorB64(it, "k3y"))}\"\n    invoke-static {v0}, Lyy/s;->a(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v0\n") }
        plainB.indices.forEach { calls.append("    const/4 v1, 0x${it.toString(16)}\n    invoke-static {v1}, Lyy/t;->b(I)Ljava/lang/String;\n    move-result-object v0\n") }
        plainC.forEach { calls.append("    const-string v0, \"${esc(xorB64(it, "Zq9"))}\"\n    invoke-static {v0}, Lyy/c;->c(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v0\n") }
        plainD.forEach { calls.append("    const-string v0, \"${esc(encD2(it))}\"\n    invoke-static {v0}, Lyy/d;->d(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v0\n") }
        File(dir, "u.smali").writeText(".class public final Lyy/u;\n.super Ljava/lang/Object;\n\n.method public static run()V\n    .locals 2\n$calls    return-void\n.end method\n")

        val fakeAi = object : AiTextClient {
            override fun complete(system: String, user: String) = """{"steps":[{"op":"reverse"},{"op":"xorchars","key":"Wz"}]}"""
        }
        val rep = StringDecryptor.run(proj, fakeAi, ProgressListener { println("  $it") })
        println("Informe: $rep")
        val out = StringDecryptor.load(proj).filter { it.file.contains("/yy/") }
        out.forEach { println("  ${it.file.substringAfterLast('/')}:${it.line} → \"${it.output}\"  [${it.how}]") }
        (plainA + plainB + plainC + plainD).forEach { p -> assertTrue(out.any { it.output == p }, "Faltó descifrar \"$p\"") }
        assertTrue(out.first { it.output == plainA[0] }.how.contains("intérprete"))
        assertTrue(out.first { it.output == plainB[0] }.how.contains("intérprete"))
        assertTrue(out.first { it.output == plainC[0] }.how.contains("típico"))
        assertTrue(out.first { it.output == plainD[0] }.how.startsWith("IA"))
        println(StringDecryptor.contextFor(proj, listOf("smali/classes/yy/u.smali")))

        // El Java muestra los textos como comentario
        val apk = File(root, "out.apk"); ApkProject.build(proj, apk)
        val java = JavaDecompiler.open(apk, JavaDecompilerOptions(comments = StringDecryptor.jadxComments(proj)))
        val code = java.javaCode("yy.u")
        println(code.lines().take(30).joinToString("\n"))
        assertTrue(code.contains("api.ejemplo.com/canales"))
    }
}
