package dev.apkforge.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.core.JavaSessions
import dev.apkforge.app.core.Workspace
import dev.apkforge.app.ui.SectionCard
import dev.apkforge.app.ui.apk.CheckRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val p = ApkForgeApp.prefs
    var signMode by remember { mutableStateOf(p.signMode) }
    var ksPath by remember { mutableStateOf(p.keystorePath) }
    var ksPass by remember { mutableStateOf(p.keystorePass) }
    var alias by remember { mutableStateOf(p.keyAlias) }
    var keyPass by remember { mutableStateOf(p.keyPass) }
    var deobf by remember { mutableStateOf(p.deobfuscate) }
    var inconsistent by remember { mutableStateOf(p.showInconsistent) }
    var kotlinNames by remember { mutableStateOf(p.kotlinNames) }
    var hideMeta by remember { mutableStateOf(p.hideMetadata) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Firma de APKs") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(signMode == "test", { signMode = "test"; p.signMode = "test" })
                    Column {
                        Text("Clave de prueba de ApkForge")
                        Text("Para probar mods en tu móvil.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(signMode == "keystore", { signMode = "keystore"; p.signMode = "keystore" })
                    Column {
                        Text("Mi keystore")
                        Text("PKCS12 (.p12/.pfx) o BKS. Para JKS, conviértelo a PKCS12 con keytool.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (signMode == "keystore") {
                    Spacer(Modifier.size(6.dp))
                    OutlinedTextField(ksPath, { ksPath = it; p.keystorePath = it }, label = { Text("Ruta del keystore") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("${Workspace.root.absolutePath}/mi-clave.p12") })
                    OutlinedTextField(ksPass, { ksPass = it; p.keystorePass = it }, label = { Text("Contraseña del keystore") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(alias, { alias = it; p.keyAlias = it }, label = { Text("Alias (vacío = el primero)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(keyPass, { keyPass = it; p.keyPass = it }, label = { Text("Contraseña de la clave (vacío = la misma)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    "Una app recompilada lleva otra firma: para instalarla encima de la original, primero desinstala la original.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("Decompilador Java (jadx)") {
                CheckRow("Mostrar código aunque sea inconsistente", "Muestra lo que se pudo reconstruir en vez de “dump skipped”", inconsistent) {
                    inconsistent = it; p.showInconsistent = it; JavaSessions.close()
                }
                CheckRow("Desofuscar nombres", "Renombra a, b, c… a nombres únicos (C0123a, m45b…) para seguir el código", deobf) {
                    deobf = it; p.deobfuscate = it; JavaSessions.close()
                }
                CheckRow("Nombres reales desde Kotlin", "Usa metadatos de Kotlin para nombrar variables", kotlinNames) {
                    kotlinNames = it; p.kotlinNames = it; JavaSessions.close()
                }
                CheckRow("Ocultar @Metadata de Kotlin", "Quita el bloque binario ilegible de las clases Kotlin", hideMeta) {
                    hideMeta = it; p.hideMetadata = it; JavaSessions.close()
                }
            }

            SectionCard("Carpetas") {
                Text("Proyectos: ${Workspace.root.absolutePath}/projects", fontSize = 13.sp)
                Text("APKs compilados: ${Workspace.root.absolutePath}/builds", fontSize = 13.sp)
                Text("APKs extraídos: ${Workspace.root.absolutePath}/extracted", fontSize = 13.sp)
                Text("Java exportado: ${Workspace.root.absolutePath}/java", fontSize = 13.sp)
            }

            SectionCard("Acerca de") {
                Text("ApkForge 0.1.0", fontSize = 14.sp)
                Text(
                    "Motor: ARSCLib (recursos sin aapt), smali/baksmali, apksig y jadx.\n" +
                        "Úsalo con apps propias, de código abierto o con permiso de su autor.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
