plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

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
}
