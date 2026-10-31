// Dream Player for Windows: libmpv engine (JNA) and the player's own Compose controls.
plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    kotlin("plugin.serialization")
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    testImplementation(kotlin("test"))
}

// Live tests (network, libmpv) run only when asked: ./gradlew :player:test -Pdream.live=true. Tests log and keep their
// data under build/, never in the viewer's %APPDATA% / %LOCALAPPDATA%.
tasks.test {
    workingDir = projectDir
    val scratch = layout.buildDirectory.dir("test-data").get().asFile
    systemProperty("dream.mpv.dir", "${rootProject.projectDir}/third_party/mpv")
    systemProperty("dream.log.dir", File(scratch, "logs").path)
    environment("APPDATA", File(scratch, "appdata").path)
    environment("LOCALAPPDATA", File(scratch, "localappdata").path)
    listOf("dream.live", "dream.closeloop.rounds", "dream.url").forEach { k -> (project.findProperty(k) as String?)?.let { systemProperty(k, it) } }
    testLogging { showStandardStreams = true }
}

// Dev runners (not shipped): the engine spike and the player demo. libmpv comes from tools/fetch-mpv.sh.
fun devRun(name: String, main: String, defaultArgs: List<String>) = tasks.register<JavaExec>(name) {
    group = "dream player"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(main)
    workingDir = rootProject.projectDir
    jvmArgs("-Ddream.mpv.dir=${rootProject.projectDir}/third_party/mpv", "-Dsun.java2d.uiScale.enabled=true")
    // Dev runs log to build/dev-logs, never the viewer's %APPDATA%\Dream OS\logs (-Plogs=<dir> to choose).
    jvmArgs("-Ddream.log.dir=${(project.findProperty("logs") as String?) ?: layout.buildDirectory.dir("dev-logs").get().asFile.path}")
    val extra = (project.findProperty("args") as String?)?.split(" ")?.filter { it.isNotBlank() }
    args(extra ?: defaultArgs)
    // -PdevHome=<throwaway folder>: the run's APPDATA and LOCALAPPDATA live there (log, caches), never the owner's.
    (project.findProperty("devHome") as String?)?.let { home ->
        val dir = file(home)
        environment("APPDATA", dir.resolve("AppData/Roaming").apply { mkdirs() }.absolutePath)
        environment("LOCALAPPDATA", dir.resolve("AppData/Local").apply { mkdirs() }.absolutePath)
    }
}
devRun("spike", "com.dreamos.player.dev.EngineSpikeKt", listOf())
devRun("mediakeys", "com.dreamos.player.dev.MediaKeysCheckKt", listOf())
// The close loop with real windows (dev/CloseLoop.kt) never runs on the viewer's screen: this writes its java
// arguments and tools/closeloop.ps1 starts it on a desktop of its own, with a throwaway APPDATA and no sound.
tasks.register("closeloopArgs") {
    group = "dream player"
    dependsOn("classes")
    val out = layout.buildDirectory.file("closeloop/args.txt")
    val cp = sourceSets["main"].runtimeClasspath
    val mpv = "${rootProject.projectDir}/third_party/mpv"
    doLast {
        fun q(s: String) = "\"" + s.replace("\\", "/") + "\""
        val f = out.get().asFile.apply { parentFile.mkdirs() }
        f.writeText(listOf("-cp", q(cp.files.joinToString(File.pathSeparator)), q("-Ddream.mpv.dir=$mpv"), "-Ddream.player.silent=true",
            "com.dreamos.player.dev.CloseLoopKt").joinToString("\n"))
    }
}
devRun("demo", "com.dreamos.player.dev.PlayerDemoKt", listOf()).configure {
    (project.findProperty("shots") as String?)?.let { jvmArgs("-Ddream.shots=$it") }
    (project.findProperty("url") as String?)?.let { jvmArgs("-Ddream.url=$it") }
}
devRun("shots", "com.dreamos.player.dev.OffscreenShotsKt", listOf()).configure {
    (project.findProperty("shots") as String?)?.let { jvmArgs("-Ddream.shots=$it") }
    (project.findProperty("only") as String?)?.let { jvmArgs("-Ddream.only=$it") }
    jvmArgs("-Djava.awt.headless=false")
}
