plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.apkforge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.apkforge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Firma fija para que las actualizaciones se instalen encima sin desinstalar.
        create("forge") {
            storeFile = rootProject.file("app/apkforge-release.p12")
            storePassword = "apkforge"
            keyAlias = "apkforge"
            keyPassword = "apkforge"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("forge")
        }
        debug {
            signingConfig = signingConfigs.getByName("forge")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "META-INF/INDEX.LIST",
            )
        }
    }
}

configurations.all {
    // La variante -android de Guava está pensada para Android.
    resolutionStrategy.force("com.google.guava:guava:33.4.8-android")
    exclude(group = "org.antlr", module = "antlr")
}

dependencies {
    implementation(project(":engine"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Editor de código de alto rendimiento (resaltado, números de línea, búsqueda, undo)
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
    implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.6")
}
