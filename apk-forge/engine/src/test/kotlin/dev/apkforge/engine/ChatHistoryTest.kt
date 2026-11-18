package dev.apkforge.engine

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatHistoryTest {
    @Test fun saveLoadArchiveResume() {
        val proj = File(System.getProperty("java.io.tmpdir"), "chat-hist").apply { deleteRecursively(); mkdirs() }
        File(proj, ".apkforge/project.properties").apply { parentFile.mkdirs(); writeText("x=1") }
        val f = ChatHistory.fileFor(proj, "MODDER", File(proj, "appdir"))
        assertTrue(f.path.contains(".apkforge/chats"), "En un proyecto el chat se guarda dentro del proyecto")
        val msgs = listOf(
            ChatHistory.Msg("user", "pon los canales en cuadrícula"),
            ChatHistory.Msg("tool", tool = "find_lists", summary = ""),
            ChatHistory.Msg("assistant", "Encontré la lista en ChannelsFragment"),
        )
        ChatHistory.save(f, ChatHistory.Chat("Modder", running = true, updated = 1000, messages = msgs))
        // "se apagó el móvil": otra lectura desde cero
        val back = ChatHistory.load(f)!!
        assertEquals(3, back.messages.size); assertTrue(back.running)
        val ctx = ChatHistory.resumeContext(back.messages, back.running)
        println(ctx)
        assertTrue(ctx.contains("CONTINÚA") && ctx.contains("cuadrícula") && ctx.contains("find_lists"))
        // Nueva conversación: la anterior queda en el historial
        ChatHistory.archive(f)
        assertTrue(!f.exists())
        assertEquals(1, ChatHistory.archives(f).size)
    }
}
