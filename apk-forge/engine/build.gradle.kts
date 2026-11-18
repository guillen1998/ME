import java.security.MessageDigest
import java.net.URI
import java.io.File as JFile

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17); freeCompilerArgs.add("-Xskip-metadata-version-check") } }

configurations.all {
    exclude(group = "org.antlr", module = "antlr")
    // Misma variante que usa la app en Android
    resolutionStrategy.force("com.google.guava:guava:33.4.8-android")
}

dependencies {
    // Recursos/manifest binarios <-> XML sin aapt (Java puro, funciona en Android)
    api("com.github.REAndroid:ARSCLib:V1.4.0")
    // dex <-> smali
    api("com.android.tools.smali:smali-baksmali:3.0.9")
    api("com.android.tools.smali:smali:3.0.9")
    // Firma v1/v2/v3/v4 + verificación + alineación
    api("com.android.tools.build:apksig:8.13.0")
    // Decompilador a Java legible
    api("io.github.skylot:jadx-core:1.5.3")
    api("io.github.skylot:jadx-dex-input:1.5.3")
    // SDK oficial de Claude (agentes de IA)
    api("com.anthropic:anthropic-java:3.9.0")
    // JSON para el cliente compatible con OpenAI (OpenRouter, Groq, Ollama…)
    api("com.google.code.gson:gson:2.13.1")
    // Diferencias línea a línea para mostrar qué cambió la IA
    api("io.github.java-diff-utils:java-diff-utils:4.12")

    // Motor neuronal: ONNX Runtime (en el móvil la app usa onnxruntime-android: mismo API, con NNAPI → GPU/NPU)
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.31.0")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")
    // Modelo que RAZONA en el móvil (Qwen3 con modo "thinking") con LiteRT-LM: GPU, NPU o CPU.
    // En el móvil la app usa litertlm-android (mismo API).
    // (sin su Kotlin 2.2: usamos el nuestro)
    compileOnly("com.google.ai.edge.litertlm:litertlm-jvm:0.18.1") { exclude(group = "org.jetbrains.kotlin"); exclude(group = "org.jetbrains.kotlinx") }
    testImplementation("com.google.ai.edge.litertlm:litertlm-jvm:0.18.1") { exclude(group = "org.jetbrains.kotlin"); exclude(group = "org.jetbrains.kotlinx") }
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test { useJUnit(); maxHeapSize = "2g"; testLogging { showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }

tasks.register("printCp") {
    val cp = configurations.runtimeClasspath
    doLast { println("CP=" + cp.get().files.joinToString(":")) }
}

tasks.test {
    System.getProperty("apkforge.sampleApk")?.let { systemProperty("apkforge.sampleApk", it) }
    System.getProperty("apkforge.live")?.let { systemProperty("apkforge.live", it) }
    System.getProperty("apkforge.reasonerModel")?.let { systemProperty("apkforge.reasonerModel", it) }
}

// Modelo de embeddings para el motor neuronal (all-MiniLM-L6-v2 cuantizado, Apache-2.0). Se descarga
// una vez y se verifica su huella; la app lo empaqueta como asset.
val neuralDir = layout.buildDirectory.dir("neural")
val downloadNeuralModel by tasks.registering {
    val out = neuralDir
    outputs.dir(out)
    doLast {
        val base = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/"
        val files = mapOf(
            "neural/minilm.onnx" to Pair(base + "onnx/model_qint8_arm64.onnx", "4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474"),
            "neural/vocab.txt" to Pair(base + "vocab.txt", "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"),
        )
        files.forEach { (name, src) ->
            val f = out.get().file(name).asFile
            fun sha(file: JFile) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            if (f.isFile && sha(f) == src.second) return@forEach
            f.parentFile.mkdirs()
            URI(src.first).toURL().openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            check(sha(f) == src.second) { "Huella incorrecta de ${f.name}" }
        }
    }
}
tasks.test {
    dependsOn(downloadNeuralModel)
    systemProperty("apkforge.neuralDir", neuralDir.get().dir("neural").asFile.absolutePath)
}
