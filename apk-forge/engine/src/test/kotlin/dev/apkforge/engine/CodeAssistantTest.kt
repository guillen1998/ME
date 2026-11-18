package dev.apkforge.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeAssistantTest {
    private val original = """
        .class public Lcom/demo/Check;
        .super Ljava/lang/Object;

        .method public static isPremium()Z
            .locals 1
            const/4 v0, 0x0
            return v0
        .end method
    """.trimIndent()

    @Test fun smaliEditWithAutoFix() {
        val broken = original.replace("const/4 v0, 0x0\n    return v0", "const/4 v5, 0x1\n    return v5") // v5 > .locals 1
        val fixed = original.replace("const/4 v0, 0x0", "const/4 v0, 0x1")
        val calls = mutableListOf<String>()
        val fake = object : AiTextClient {
            override fun complete(system: String, user: String): String {
                calls += user
                return if (calls.size == 1) "```smali\n$broken\n```\nCambiado a true." else "```smali\n$fixed\n```\nCorregido el registro."
            }
        }
        val r = CodeAssistant.edit(fake, original, "Check.smali", "haz que isPremium devuelva true en mi app de pruebas")
        println("Llamadas a la IA: ${calls.size}\nDiff:\n${r.diff}\nNotas: ${r.notes}")
        assertEquals(2, calls.size, "Debe detectar el smali roto y pedir corrección")
        assertTrue(calls[1].contains("errores de smali"))
        assertEquals(true, r.validated)
        assertEquals(fixed, r.newCode)
        assertTrue(r.added == 1 && r.removed == 1)
        assertTrue(r.diff.contains("-    const/4 v0, 0x0") && r.diff.contains("+    const/4 v0, 0x1"))
    }

    @Test fun validateSmaliDetectsErrors() {
        assertEquals(null, CodeAssistant.validateSmali(original))
        assertTrue(CodeAssistant.validateSmali(original.replace(".end method", "")) != null)
    }

    @Test fun extractLongestBlock() {
        assertEquals("b\nb", CodeAssistant.extractCode("x ```\na\n``` y ```java\nb\nb\n``` z"))
    }
}
