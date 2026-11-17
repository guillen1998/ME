package dev.apkforge.app.ui.editor

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher

/** Control imperativo del editor desde Compose (guardar, buscar, saltar a línea…). */
class CodeController {
    var editor: CodeEditor? = null
        internal set

    fun text(): String = editor?.text?.toString().orEmpty()
    fun undo() = editor?.undo()
    fun redo() = editor?.redo()

    fun jumpTo(line: Int) {
        val e = editor ?: return
        val l = (line - 1).coerceIn(0, maxOf(0, e.text.lineCount - 1))
        e.post {
            e.jumpToLine(l)
            e.setSelection(l, 0)
        }
    }

    fun search(query: String, regex: Boolean, matchCase: Boolean) {
        val e = editor ?: return
        if (query.isEmpty()) { e.searcher.stopSearch(); return }
        val type = if (regex) EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION else EditorSearcher.SearchOptions.TYPE_NORMAL
        runCatching { e.searcher.search(query, EditorSearcher.SearchOptions(type, !matchCase)) }
    }

    fun next() = runCatching { editor?.searcher?.gotoNext() }
    fun previous() = runCatching { editor?.searcher?.gotoPrevious() }
    fun replace(with: String) = runCatching { editor?.searcher?.replaceCurrentMatch(with) }
    fun replaceAll(with: String) = runCatching { editor?.searcher?.replaceAll(with) }
    fun stopSearch() = runCatching { editor?.searcher?.stopSearch() }
    fun matchInfo(): Pair<Int, Int> = editor?.searcher?.let {
        runCatching { (it.currentMatchedPositionIndex + 1) to it.matchedPositionCount }.getOrNull()
    } ?: (0 to 0)
}

@Composable
fun CodeView(
    text: String,
    fileName: String,
    modifier: Modifier = Modifier,
    controller: CodeController = remember { CodeController() },
    editable: Boolean = true,
    wordWrap: Boolean = false,
    fontSize: Float = 13f,
    initialLine: Int = 0,
    onChanged: () -> Unit = {},
) {
    val ctx = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { c ->
            CodeEditor(c).apply {
                setColorScheme(CodeLanguages.colorScheme(ctx))
                setEditorLanguage(CodeLanguages.languageFor(ctx, fileName))
                typefaceText = Typeface.MONOSPACE
                typefaceLineNumber = Typeface.MONOSPACE
                setLineNumberEnabled(true)
                setPinLineNumber(true)
                setHighlightCurrentLine(true)
                isCursorAnimationEnabled = false
                setText(text)
                subscribeEvent(ContentChangeEvent::class.java) { ev, _ ->
                    if (ev.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) onChanged()
                }
                controller.editor = this
                if (initialLine > 0) controller.jumpTo(initialLine)
            }
        },
        update = { e ->
            e.isEditable = editable
            e.setWordwrap(wordWrap)
            e.setTextSize(fontSize)
        },
    )
    LaunchedEffect(text) {
        val e = controller.editor ?: return@LaunchedEffect
        if (e.text.toString() != text) e.setText(text)
    }
    DisposableEffect(Unit) {
        onDispose {
            controller.editor?.release()
            controller.editor = null
        }
    }
}
