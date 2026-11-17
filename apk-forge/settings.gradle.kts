pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        maven("https://repo1.maven.org/maven2")
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.REAndroid") }
        }
    }
}
rootProject.name = "ApkForge"
include(":engine", ":app")
