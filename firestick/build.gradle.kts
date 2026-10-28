plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Embedded Python for the scraper modules users add (Scrapers.kt). 16.1 is the last line for AGP 8.5 + armeabi-v7a.
    id("com.chaquo.python") version "16.1.0" apply false
}
