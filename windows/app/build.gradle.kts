import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(project(":scrapers"))
    implementation(project(":player"))
    implementation(compose.desktop.currentOs)
    // JNA: SelfCheck (selfcheck/) and the title page's hero trailer (trailer/BackdropVideo.kt) load libmpv like the player.
    implementation("net.java.dev.jna:jna:5.15.0")
    testImplementation(kotlin("test"))
}

// Live tests (network, libmpv) run only when asked: ./gradlew :app:test -Pdream.live=true. Tests log and keep their
// data under build/, never in the viewer's %APPDATA% / %LOCALAPPDATA%, and never open an audio device.
tasks.test {
    val scratch = layout.buildDirectory.dir("test-data").get().asFile
    systemProperty("dream.mpv.dir", "${rootProject.projectDir}/third_party/mpv")
    systemProperty("dream.log.dir", File(scratch, "logs").path)
    systemProperty("dream.trailer.silent", "true")
    systemProperty("java.awt.headless", "true")
    environment("APPDATA", File(scratch, "appdata").path)
    environment("LOCALAPPDATA", File(scratch, "localappdata").path)
    listOf("dream.live", "dream.trailerloop.rounds", "dream.url").forEach { k -> (project.findProperty(k) as String?)?.let { systemProperty(k, it) } }
    testLogging { showStandardStreams = true }
}

val dreamVersion = rootProject.extra["dreamVersion"] as String

// Release builds: -Pdream.channel=PUBLIC (tools/build-installer.sh). Everything else is DEV; About shows "(DEV)".
val dreamChannel = (findProperty("dream.channel") as String?)?.uppercase() ?: "DEV"
require(dreamChannel in setOf("PUBLIC", "DEV")) { "dream.channel must be PUBLIC or DEV, not $dreamChannel" }

// ---- Packaging ----------------------------------------------------------------------------------------------------
// Bundled binaries (tools/fetch-third-party.sh) go in the app's resources folder, which the packaged app sees as
// compose.application.resources.dir (<install>\app\resources) and `./gradlew :app:run` sees as a build copy:
//   libmpv-2.dll          at the top (player: LibMpv.api looks for it there)
//   python/, python-site/ side by side (scrapers: PythonRuntime.locate())
val thirdParty = rootProject.layout.projectDirectory.dir("third_party")
val appResourcesRoot = layout.buildDirectory.dir("app-resources")
val stageAppResources by tasks.registering(Sync::class) {
    description = "Copies libmpv and the bundled Python from third_party/ into the app's resources."
    into(appResourcesRoot.map { it.dir("windows") })
    from(thirdParty.dir("mpv")) { include("libmpv-2.dll") }
    from(thirdParty.dir("python")) { into("python"); exclude(".dream-fetched", "**/__pycache__/**") }
    from(thirdParty.dir("python-site")) { into("python-site"); exclude(".dream-fetched", "**/__pycache__/**") }
}

// An installer without them would install a Dream OS that can't play or find sources: fail instead.
val checkThirdParty by tasks.registering {
    description = "Fails when libmpv or the bundled Python is missing from third_party/."
    val need = listOf("mpv/libmpv-2.dll", "python/pythonw.exe", "python/python313.zip", "python-site/requests/__init__.py")
        .map { thirdParty.file(it).asFile }
    val root = rootDir
    doLast {
        val missing = need.filterNot { it.isFile }
        if (missing.isNotEmpty()) throw GradleException(
            "Missing ${missing.joinToString { it.relativeTo(root).path }}. Run tools/fetch-third-party.sh first."
        )
    }
}

// The JDK the app is built for and shipped with: the toolchain's JDK 17 (the Microsoft Build of OpenJDK on this PC,
// GPLv2 with the Classpath Exception), not whichever JDK runs Gradle. Its jpackage and jmods make the installer.
val jdk17 = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }

// The trimmed Java runtime shipped with the app: :app:suggestRuntimeModules (java.instrument, java.net.http,
// jdk.httpserver, jdk.unsupported on top of the base), plus what jdeps can't see from the code: jdk.crypto.ec (the
// TLS key exchange of nearly every HTTPS server), jdk.localedata (dates and numbers outside English), jdk.charsets
// (sites that answer in older encodings), jdk.accessibility (screen readers), jdk.zipfs and java.management (looked
// up by libraries at run time). Check again when a module adds a JDK API: tools/build-installer.sh runs SelfCheck.
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.logging", "java.xml", "java.instrument", "java.net.http", "java.management",
    "jdk.httpserver", "jdk.unsupported", "jdk.crypto.ec", "jdk.localedata", "jdk.charsets", "jdk.accessibility",
    "jdk.zipfs", "java.scripting",
)

compose.desktop {
    application {
        mainClass = "com.dreamos.desktop.MainKt"
        javaHome = jdk17.get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf(
            "-Ddream.version=$dreamVersion",
            "-Ddream.channel=$dreamChannel",
            "-Dfile.encoding=UTF-8",
            // The startup archive (startupArchive below, app/.../startup/StartArchive.kt). Missing or out of date, Java
            // runs without it. Under `./gradlew :app:run` $APPDIR isn't replaced and the option finds nothing (harmless).
            "-XX:SharedArchiveFile=\$APPDIR/dream.jsa",
            // A native crash (libmpv, Skia, a driver) leaves the JVM's report in <install folder>\logs; the next start
            // moves it next to dream.log and logs a summary (AppLog.nativeCrashReports). The launcher only expands
            // $APPDIR, never %APPDATA% / %LOCALAPPDATA%; an unwritable folder falls back to the working folder, then %TEMP%.
            // No heap dump on out of memory: its size has no bound (the heap can reach a quarter of the RAM).
            "-XX:ErrorFile=\$APPDIR/../logs/hs_err_%p.log",
        )
        // No ProGuard: JNA and kotlinx.serialization reach classes by reflection, and the jar bytes saved are
        // small next to libmpv and the runtime.
        nativeDistributions {
            // Only the app image (:app:createDistributable: Dream OS.exe, app\, runtime\). The installer around it is
            // Inno Setup's (installer/DreamOS.iss, tools/build-installer.sh): a real wizard with a folder choice,
            // which jpackage's MSI and exe couldn't give.
            packageName = "Dream OS"
            packageVersion = dreamVersion
            description = "Dream OS"
            vendor = "Dream OS"
            copyright = "Dream OS contributors. GPL-3.0."
            appResourcesRootDir.set(appResourcesRoot)
            modules(*runtimeModules.toTypedArray())
            windows {
                // The icon of Dream OS.exe (and through it the shortcuts and the Apps entry).
                iconFile.set(project.file("icons/dream_os.ico"))
            }
        }
    }
}

// Compose adds its tasks after this script runs, hence matching rather than named.
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageAppResources) }
tasks.matching { it.name == "createDistributable" }.configureEach { dependsOn(checkThirdParty); finalizedBy(startupArchive) }

// The startup archive (app/.../startup/StartArchive.kt), two parts in the app image:
// - app\dream.jsa: the Java runtime's own classes (-Xshare:dump with the runtime's default list), made with the
//   toolchain's java.exe briefly copied into the image's runtime (same jvm.dll and modules). It works in any folder,
//   so every launch has it from the first one.
// - app\dream.classlist: every class one headless launch of this image loads (the launch probe, DREAM_PROBE: services,
//   the intro, Home's hero and first rows, then exit; no window, no sound). The viewer's PC turns it into the full
//   archive (Java 17 ties that one to the install folder). The launch runs on a throwaway data folder kept in
//   build/startup-train, so later builds train on a relaunch; the developer's TMDB key (local.properties or
//   DREAM_TMDB_KEY) lets Home's rows load (without one the list covers the welcome page only).
// A failed step leaves that part out; the app then starts without it, as before.
val startupArchive by tasks.registering {
    description = "Adds the startup archive's Java part (dream.jsa) and class list (dream.classlist) to the app image."
    val image = layout.buildDirectory.dir("compose/binaries/main/app/Dream OS")
    val train = layout.buildDirectory.dir("startup-train")
    val localProps = rootProject.file("local.properties")
    val javaExe = jdk17.map { it.executablePath.asFile }
    doLast {
        val root = image.get().asFile
        val app = File(root, "app")
        val list = File(app, "dream.classlist").apply { delete() }
        val jsa = File(app, "dream.jsa").apply { if (isFile) setWritable(true); delete() }
        val data = train.get().asFile.apply { mkdirs() }
        // 1. The class list, from a launch with no archive at all.
        val key = System.getenv("DREAM_TMDB_KEY")?.takeIf { it.isNotBlank() }
            ?: localProps.takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("tmdb.key") }
        val pb = ProcessBuilder(File(root, "Dream OS.exe").path).directory(app)
            .redirectErrorStream(true).redirectOutput(File(data, "probe-output.txt"))
        pb.environment().apply {
            put("APPDATA", File(data, "Roaming").apply { mkdirs() }.path)
            put("LOCALAPPDATA", File(data, "Local").apply { mkdirs() }.path)
            put("DREAM_PROBE", File(data, "probe.txt").path)
            put("_JAVA_OPTIONS", "-Xshare:off -XX:DumpLoadedClassList=dream.classlist")
            if (key != null) put("DREAM_TMDB_KEY", key) else remove("DREAM_TMDB_KEY")
        }
        val p = pb.start()
        if (!p.waitFor(90, TimeUnit.SECONDS)) { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly() }
        if (!list.isFile || list.length() < 10_000) {
            list.delete()
            logger.warn("startupArchive: no class list (the launch probe failed; see ${File(data, "probe-output.txt")})")
        } else {
            logger.lifecycle("startupArchive: class list of ${list.readLines().count { !it.startsWith("#") }} classes" +
                if (key == null) " (no TMDB key: welcome page only)" else "")
        }
        // 2. The Java part of the archive.
        val java = File(root, "runtime/bin/java.exe")
        try {
            javaExe.get().copyTo(java, overwrite = true)
            val d = ProcessBuilder(java.path, "-Xshare:dump", "-XX:SharedArchiveFile=${jsa.path}")
                .redirectErrorStream(true).redirectOutput(File(data, "base-dump.txt")).start()
            if (!d.waitFor(120, TimeUnit.SECONDS)) { d.descendants().forEach { it.destroyForcibly() }; d.destroyForcibly() }
        } finally {
            java.delete()
        }
        if (jsa.isFile) jsa.setWritable(true)
        if (!jsa.isFile || jsa.length() < 1_000_000) {
            jsa.delete()
            logger.warn("startupArchive: no Java archive (see ${File(data, "base-dump.txt")})")
        } else logger.lifecycle("startupArchive: Java archive ${jsa.length() shr 20} MB")
    }
}

// Dev runner (not shipped): the title page's hero trailer, offscreen and silent. libmpv comes from tools/fetch-mpv.sh.
tasks.register<JavaExec>("trailerShots") {
    group = "dream trailers"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.dreamos.desktop.trailer.dev.TrailerShotsKt")
    workingDir = rootProject.projectDir
    jvmArgs("-Ddream.mpv.dir=${rootProject.projectDir}/third_party/mpv", "-Ddream.trailer.silent=true")
    (project.findProperty("shots") as String?)?.let { jvmArgs("-Ddream.shots=$it") }
    (project.findProperty("movie") as String?)?.let { jvmArgs("-Ddream.movie=$it") }
    (project.findProperty("size") as String?)?.let { jvmArgs("-Ddream.size=$it") }
}
tasks.register<JavaExec>("trailerSources") {
    group = "dream trailers"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.dreamos.desktop.trailer.dev.TrailerSourcesKt")
    workingDir = rootProject.projectDir
    jvmArgs("-Ddream.mpv.dir=${rootProject.projectDir}/third_party/mpv", "-Ddream.trailer.silent=true")
    (project.findProperty("movie") as String?)?.let { jvmArgs("-Ddream.movie=$it") }
}
// Dev runner (not shipped): page open to the hero trailer's picture, one title per JVM, in a throwaway data folder.
tasks.register<JavaExec>("trailerTiming") {
    group = "dream trailers"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.dreamos.desktop.trailer.dev.TrailerTimingKt")
    workingDir = rootProject.projectDir
    val data = layout.buildDirectory.dir("trailer-timing-data").get().asFile
    doFirst { data.deleteRecursively(); File(data, "Roaming").mkdirs(); File(data, "Local").mkdirs() }
    environment("APPDATA", File(data, "Roaming").absolutePath)
    environment("LOCALAPPDATA", File(data, "Local").absolutePath)
    jvmArgs("-Ddream.mpv.dir=${rootProject.projectDir}/third_party/mpv", "-Ddream.trailer.silent=true", "-Djava.awt.headless=true")
    (project.findProperty("title") as String?)?.let { jvmArgs("-Ddream.title=$it") }
    (project.findProperty("mode") as String?)?.let { jvmArgs("-Ddream.mode=$it") }
    (project.findProperty("home") as String?)?.let { jvmArgs("-Ddream.home=$it") }
    (project.findProperty("hover") as String?)?.let { jvmArgs("-Ddream.hover=$it") }
    (project.findProperty("size") as String?)?.let { jvmArgs("-Ddream.size=$it") }
}
tasks.register<JavaExec>("trailerStart") {
    group = "dream trailers"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.dreamos.desktop.trailer.dev.TrailerStartKt")
    workingDir = rootProject.projectDir
    val data = layout.buildDirectory.dir("trailer-start-data").get().asFile
    doFirst { data.deleteRecursively(); File(data, "Roaming").mkdirs(); File(data, "Local").mkdirs() }
    environment("APPDATA", File(data, "Roaming").absolutePath)
    environment("LOCALAPPDATA", File(data, "Local").absolutePath)
    jvmArgs("-Ddream.mpv.dir=${rootProject.projectDir}/third_party/mpv", "-Ddream.trailer.silent=true", "-Djava.awt.headless=true")
    (project.findProperty("title") as String?)?.let { jvmArgs("-Ddream.title=$it") }
}
// The real title page with its hero trailer: shots before, playing, after a pointer move, sound on, idle, after Play.
tasks.register<JavaExec>("titleTrailerShots") {
    group = "dream trailers"
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.dreamos.desktop.details.dev.TitleTrailerShotsKt")
    workingDir = rootProject.projectDir
    val data = layout.buildDirectory.dir("trailer-shots-data").get().asFile
    doFirst { data.deleteRecursively(); File(data, "Roaming").mkdirs(); File(data, "Local").mkdirs() }
    environment("APPDATA", File(data, "Roaming").absolutePath)
    environment("LOCALAPPDATA", File(data, "Local").absolutePath)
    jvmArgs("-Ddream.mpv.dir=${rootProject.projectDir}/third_party/mpv", "-Ddream.trailer.silent=true", "-Djava.awt.headless=true")
    (project.findProperty("shots") as String?)?.let { jvmArgs("-Ddream.shots=$it") }
    (project.findProperty("size") as String?)?.let { jvmArgs("-Ddream.size=$it") }
}
