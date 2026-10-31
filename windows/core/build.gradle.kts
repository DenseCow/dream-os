// Dream OS logic with no UI: TMDB, rows and taste, availability, Dream Core resolver, debrid, Trakt, history, profiles.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin { jvmToolchain(17) }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Windows DPAPI for secrets at rest (Platform.secretCipher); the same version the player uses.
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    // Trailers: a title's trailer video resolved to its streams (core/trailer). Same version as fire-home.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    testImplementation(kotlin("test"))
}
// Live tests (network) run only when asked: ./gradlew :core:test -Pdream.live=true
tasks.test { (project.findProperty("dream.live") as String?)?.let { systemProperty("dream.live", it) } }
