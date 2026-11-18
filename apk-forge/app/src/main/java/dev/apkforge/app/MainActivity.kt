package dev.apkforge.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.apkforge.app.ui.ApkForgeTheme
import dev.apkforge.app.ui.TaskConsole
import dev.apkforge.app.ui.apk.ApkInfoScreen
import dev.apkforge.app.ui.apps.InstalledAppsScreen
import dev.apkforge.app.ui.editor.EditorScreen
import dev.apkforge.app.ui.files.FileBrowserScreen
import dev.apkforge.app.ui.java.JavaBrowserScreen
import dev.apkforge.app.ui.java.JavaClassScreen
import dev.apkforge.app.ui.java.TextViewerHolder
import dev.apkforge.app.ui.java.TextViewerScreen
import dev.apkforge.app.ui.agent.AgentScreen
import dev.apkforge.app.ui.binary.BinaryViewerScreen
import dev.apkforge.app.ui.scan.ScannerScreen
import dev.apkforge.app.ui.search.ProjectSearchScreen
import dev.apkforge.app.ui.settings.SettingsScreen
import java.io.File

class MainActivity : ComponentActivity() {

    private var pendingApk by mutableStateOf<File?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            ApkForgeTheme {
                val nav = rememberNavController()
                AppNav(nav)
                LaunchedEffect(pendingApk) {
                    pendingApk?.let { nav.navigate(Routes.apk(it)); pendingApk = null }
                }
                TaskConsole(
                    onOpenDir = { nav.navigate(Routes.files(it)) },
                    onOpenFile = { nav.navigate(Routes.editor(it)) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Abrir con ApkForge" / "Compartir con ApkForge" desde otras apps. */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return
        Thread {
            runCatching {
                val file = if (uri.scheme == "file") File(uri.path!!) else {
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "recibido.apk"
                    val dir = File(cacheDir, "incoming").apply { mkdirs() }
                    File(dir, name.replace('/', '_')).also { out ->
                        contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
                    }
                }
                runOnUiThread { pendingApk = file }
            }
        }.start()
    }
}

object Routes {
    private fun enc(f: File) = Uri.encode(f.absolutePath)
    fun files(dir: File) = "files?path=${enc(dir)}"
    fun apk(f: File) = "apk?path=${enc(f)}"
    fun editor(f: File, line: Int = 0) = "editor?path=${enc(f)}&line=$line"
    fun java(f: File) = "java?path=${enc(f)}"
    fun javaClass(f: File, cls: String) = "javaclass?path=${enc(f)}&cls=${Uri.encode(cls)}"
    fun search(root: File) = "search?path=${enc(root)}"
    fun scan(target: File) = "scan?path=${enc(target)}"
    fun binary(f: File) = "binary?path=${enc(f)}"
    fun agent(target: File, prompt: String? = null): String {
        val auto = prompt ?: if (target.isFile && !target.extension.equals("apk", true))
            "Analiza el archivo '${target.name}': qué es, qué contiene, si está cifrado (y si lo está, busca la clave y descífralo) y para qué lo usa la app." else null
        return "agent?path=${enc(target)}" + (auto?.let { "&prompt=${Uri.encode(it)}" } ?: "")
    }
    const val APPS = "apps"
    const val SETTINGS = "settings"
    const val VIEWER = "viewer"
}

@Composable
private fun AppNav(nav: NavHostController) {
    val pathArg = navArgument("path") { type = NavType.StringType; nullable = true; defaultValue = null }
    fun NavHostController.back() { if (!popBackStack()) navigate("files") }

    NavHost(nav, startDestination = "files?path={path}") {
        composable("files?path={path}", arguments = listOf(pathArg)) { e ->
            FileBrowserScreen(
                startPath = e.arguments?.getString("path"),
                onOpenApk = { nav.navigate(Routes.apk(it)) },
                onOpenEditor = { nav.navigate(Routes.editor(it)) },
                onOpenBinary = { nav.navigate(Routes.binary(it)) },
                onOpenJava = { nav.navigate(Routes.java(it)) },
                onSearch = { nav.navigate(Routes.search(it)) },
                onScan = { nav.navigate(Routes.scan(it)) },
                onAgent = { nav.navigate(Routes.agent(it)) },
                onApps = { nav.navigate(Routes.APPS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable("apk?path={path}", arguments = listOf(pathArg)) { e ->
            ApkInfoScreen(
                apk = File(e.arguments?.getString("path")!!),
                onBack = { nav.back() },
                onOpenDir = { nav.navigate(Routes.files(it)) },
                onOpenJava = { nav.navigate(Routes.java(it)) },
                onScan = { nav.navigate(Routes.scan(it)) },
                onAgent = { nav.navigate(Routes.agent(it)) },
            )
        }
        composable("scan?path={path}", arguments = listOf(pathArg)) { e ->
            ScannerScreen(
                target = File(e.arguments?.getString("path")!!),
                onBack = { nav.back() },
                onOpen = { f, line -> nav.navigate(Routes.editor(f, line)) },
            )
        }
        composable("binary?path={path}", arguments = listOf(pathArg)) { e ->
            BinaryViewerScreen(File(e.arguments?.getString("path")!!), onBack = { nav.back() }, onAnalyzeAi = { nav.navigate(Routes.agent(it)) })
        }
        composable(
            "agent?path={path}&prompt={prompt}",
            arguments = listOf(pathArg, navArgument("prompt") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { e ->
            AgentScreen(
                File(e.arguments?.getString("path")!!), onBack = { nav.back() }, onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                initialPrompt = e.arguments?.getString("prompt"),
            )
        }
        composable(
            "editor?path={path}&line={line}",
            arguments = listOf(pathArg, navArgument("line") { type = NavType.IntType; defaultValue = 0 }),
        ) { e ->
            EditorScreen(
                file = File(e.arguments?.getString("path")!!),
                initialLine = e.arguments?.getInt("line") ?: 0,
                onBack = { nav.back() },
                onShowText = { title, text, fileName ->
                    TextViewerHolder.title = title; TextViewerHolder.text = text; TextViewerHolder.fileName = fileName
                    nav.navigate(Routes.VIEWER)
                },
            )
        }
        composable("java?path={path}", arguments = listOf(pathArg)) { e ->
            val src = File(e.arguments?.getString("path")!!)
            JavaBrowserScreen(src, onBack = { nav.back() }, onOpenClass = { nav.navigate(Routes.javaClass(src, it)) })
        }
        composable(
            "javaclass?path={path}&cls={cls}",
            arguments = listOf(pathArg, navArgument("cls") { type = NavType.StringType; defaultValue = "" }),
        ) { e ->
            val jsrc = File(e.arguments?.getString("path")!!)
            JavaClassScreen(jsrc, e.arguments?.getString("cls")!!, onBack = { nav.back() }, onOpenClass = { nav.navigate(Routes.javaClass(jsrc, it)) })
        }
        composable("search?path={path}", arguments = listOf(pathArg)) { e ->
            ProjectSearchScreen(
                root = File(e.arguments?.getString("path")!!),
                onBack = { nav.back() },
                onOpen = { f, line -> nav.navigate(Routes.editor(f, line)) },
            )
        }
        composable(Routes.APPS) { InstalledAppsScreen(onBack = { nav.back() }, onOpenApk = { nav.navigate(Routes.apk(it)) }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.back() }) }
        composable(Routes.VIEWER) { TextViewerScreen(onBack = { nav.back() }) }
    }
}
