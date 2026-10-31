pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        // NewPipe Extractor (trailers) and its nanojson fork are only published on JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } }
    }
}
rootProject.name = "dream-os-windows"
include(":core", ":scrapers", ":player", ":app")
