# ---- NewPipe Extractor (background trailers on the details page, TrailerEngine) ----

# Time-ago patterns are looked up by class name per language.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }

# Rhino runs YouTube's player JavaScript (interpreted mode) and loads much of itself by name.
# The bytecode optimizer is never used on Android (it can't define classes there).
-keep class !org.mozilla.javascript.optimizer.**,org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**

# protobuf-javalite reads generated message fields reflectively.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# Annotations only (jsr305).
-dontwarn javax.annotation.**

# ---- Release logging ----
# Verbose and debug logs never ship. Warnings and info stay (they are how a stick in the field is
# diagnosed over adb) and are kept free of personal data: no queries, titles, stream links or tokens.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# Scrapers: the embedded Python calls back into these by name (ScraperEngine.Sink, from dreamhost/runner.py).
-keep interface com.firehome.launcher.ScraperEngine$Sink { *; }
-keep class * implements com.firehome.launcher.ScraperEngine$Sink { *; }
