import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// API keys and app ids live in local.properties (never committed; see local.properties.example).
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Release signing: a keystore kept OUTSIDE the project. Its location and passwords come from a
// properties file (storeFile, storePassword, keyAlias, keyPassword), by default
// ~/.android/dreamos-release.properties, or the path in local.properties "release.signing" or the
// DREAMOS_SIGNING environment variable. Without it, release builds fall back to the debug key (with a
// warning) so anyone can build; such builds can't update an install signed with the real key.
val signingProps: Properties? = (System.getenv("DREAMOS_SIGNING") ?: localProps.getProperty("release.signing")
    ?: "${System.getProperty("user.home")}/.android/dreamos-release.properties")
    .let { file(it) }.takeIf { it.isFile }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    ?.takeIf { p -> listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !p.getProperty(it).isNullOrBlank() } }

/** Dream OS's version (major.minor[.patch]): the one place to change it for a release. See versionCode below. */
val dreamVersion = "4.3.2"

/** A local.properties value as a Java string literal for BuildConfig (quotes and backslashes escaped). */
fun localString(key: String): String =
    "\"" + localProps.getProperty(key, "").trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.firehome.launcher"
    // 35 only for Media3 1.5 (the FFmpeg audio decoder's line); targetSdk stays 34.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.firehome.launcher"
        minSdk = 25
        targetSdk = 34
        // Dream OS's version: bump dreamVersion (below) for every release; tools/release.sh tags it "v<version>".
        // The code follows from it (4.1 -> 40100, 4.1.2 -> 40102), so it always rises with the version and
        // the self-updater (DreamUpdate) can install the new build over the old one.
        versionName = dreamVersion
        versionCode = dreamVersion.split('.').map { it.toInt() }.let { p -> p[0] * 10000 + p.getOrElse(1) { 0 } * 100 + p.getOrElse(2) { 0 } }
        // Self-updater: GitHub repository whose Releases carry Dream OS (local.properties update.repo=owner/name).
        // Empty = the updater hides itself (Settings › Updates and the daily check).
        buildConfigField("String", "UPDATE_REPO", localString("update.repo"))
        // Build channel: "PUBLIC" only for builds published by dream-os-public's tools/release.sh (it writes
        // build.channel=PUBLIC into its local.properties); every other build is "DEV". Shown after the version on the
        // boot intro and in Settings › About.
        buildConfigField("String", "BUILD_CHANNEL",
            "\"" + (if (localProps.getProperty("build.channel", "").trim().equals("PUBLIC", ignoreCase = true)) "PUBLIC" else "DEV") + "\"")
        // Optional defaults for the user's own build. Empty = Dream OS asks for them in Settings (TMDB)
        // or simply hides Rotten Tomatoes / IMDb scores (OMDb). Anything set here ends up inside the APK.
        buildConfigField("String", "TMDB_KEY", localString("tmdb.key"))
        buildConfigField("String", "OMDB_KEY", localString("omdb.key"))
        // Premiumize device sign-in needs Dream OS's own registered client id (premiumize.me). Empty =
        // Premiumize is linked with the account's API key instead (DebridLink).
        buildConfigField("String", "PREMIUMIZE_CLIENT_ID", localString("premiumize.client_id"))
        // Trakt sync (Trakt.kt): an app registered at trakt.tv/oauth/applications. Empty = the feature hides itself.
        buildConfigField("String", "TRAKT_CLIENT_ID", localString("trakt.client_id"))
        buildConfigField("String", "TRAKT_CLIENT_SECRET", localString("trakt.client_secret"))
        // Only native code in the APK is the embedded Python (Scrapers). Fire TV sticks run a 32-bit ARM
        // userland (Fire OS 7: armeabi-v7a only; 64-bit Fire OS 8 devices still run 32-bit apps), so one ABI keeps it small.
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        signingProps?.let { p ->
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingProps != null) signingConfigs.getByName("release") else {
                logger.warn("WARNING: Dream OS release signing key not found (~/.android/dreamos-release.properties); " +
                    "this release build is signed with the DEBUG key and can't update an install signed with the real key.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false }
}

// Embedded Python 3.11 (Chaquopy) for the scraper modules users add (Scrapers.kt): 3.11 is what current
// Kodi ships, so Kodi-style scraper modules run unchanged, and the last version with armeabi-v7a builds.
// The build needs a Python 3.11 on this PC to install the packages and precompile them: local.properties
// "python.build" (path to python.exe), else the PYTHON311 environment variable, else Chaquopy's own lookup (py -3.11).
chaquopy {
    defaultConfig {
        version = "3.11"
        (localProps.getProperty("python.build") ?: System.getenv("PYTHON311"))?.takeIf { it.isNotBlank() }?.let { buildPython(it) }
        // What Kodi scraper modules expect from Kodi's own module repository (script.module.requests and friends).
        pip { install("requests==2.32.3") }
    }
}

dependencies {
    // Dream Player engine (needs compileSdk 35). media3-ui is only used for its SubtitleView (we draw our own controls).
    val media3 = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // FFmpeg audio decoders (GPL-3.0, Jellyfin's build for this Media3 version): Dolby Digital Plus, TrueHD and DTS
    // on sticks and TVs that can't decode or pass them through (the Fire TV Stick HD has no DD+ decoder).
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    // Background hero trailers: resolves a title's YouTube trailer to its stream URLs (TrailerEngine).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    // Unlock Dream OS Features: an ADB client (Apache-2.0) for the stick's own ADB at 127.0.0.1 (DreamUnlock.kt).
    implementation("dev.mobile:dadb:1.2.10")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
