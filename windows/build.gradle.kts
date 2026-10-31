plugins {
    kotlin("jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

// Dream OS's version: one version for every platform (fire-home dreamVersion, iOS Info.plist). tools/set-version.sh.
extra["dreamVersion"] = "4.3.6"
