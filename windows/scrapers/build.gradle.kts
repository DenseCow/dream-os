// Scraper repositories run in a bundled CPython (Kodi API shims shared with fire-home's app/src/main/python).
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
tasks.test {
    // The end-to-end test (ScraperE2ETest) installs a real repository and scrapes over the internet: only with
    // -Pe2e (./gradlew :scrapers:test -Pe2e --tests '*E2E*'). It reads tmdb.key from local.properties at run time.
    systemProperty("dreamos.e2e", if (project.hasProperty("e2e")) "1" else "0")
    systemProperty("dreamos.repo", rootDir.absolutePath)
    testLogging { showStandardStreams = true; events("passed", "skipped", "failed") }
}
