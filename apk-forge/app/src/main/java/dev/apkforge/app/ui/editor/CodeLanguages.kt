package dev.apkforge.app.ui.editor

import android.content.Context
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource

/** Registro de gramáticas TextMate (smali, Java, XML, JSON) y del tema oscuro del editor. */
object CodeLanguages {
    @Volatile private var ready = false

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(ctx.applicationContext.assets))
        val themePath = "textmate/forge-dark.json"
        val input = FileProviderRegistry.getInstance().tryGetInputStream(themePath)
            ?: error("No se encontró el tema del editor")
        val model = ThemeModel(IThemeSource.fromInputStream(input, themePath, null), "forge-dark").apply { isDark = true }
        ThemeRegistry.getInstance().loadTheme(model)
        ThemeRegistry.getInstance().setTheme("forge-dark")
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        ready = true
    }

    fun scopeFor(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "smali" -> "source.smali"
        "java", "kt" -> "source.java"
        "xml", "html", "svg", "plist" -> "text.xml"
        "json" -> "source.json"
        else -> null
    }

    fun languageFor(ctx: Context, fileName: String): Language {
        val scope = scopeFor(fileName) ?: return EmptyLanguage()
        return runCatching {
            init(ctx)
            TextMateLanguage.create(scope, false)
        }.getOrElse { EmptyLanguage() }
    }

    fun colorScheme(ctx: Context): EditorColorScheme = runCatching {
        init(ctx)
        TextMateColorScheme.create(ThemeRegistry.getInstance())
    }.getOrElse { EditorColorScheme() }
}
