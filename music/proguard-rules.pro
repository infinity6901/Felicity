# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontobfuscate
-keep class app.simple.felicity.activities.** { *; }
-keepattributes Signature

# NanoHTTPD — embedded HTTP server used by the local WiFi server mode
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

-dontwarn java.awt.Graphics2D
-dontwarn java.awt.Image
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.image.BufferedImage
-dontwarn java.awt.image.ImageObserver
-dontwarn java.awt.image.RenderedImage
-dontwarn javax.imageio.ImageIO
-dontwarn javax.imageio.ImageWriter
-dontwarn javax.imageio.stream.ImageInputStream
-dontwarn javax.imageio.stream.ImageOutputStream
-dontwarn javax.swing.filechooser.FileFilter
# youtubedl-android (yt-dlp / ffmpeg wrapper) and the JSON models it (de)serializes via
# reflection. Without these, the release build's shrinker strips them and YouTube
# streaming/downloading crashes only in release builds.
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class app.simple.felicity.repository.youtube.** { *; }

# ---------------------------------------------------------------------------------------------
# Release-only crash hardening (the foss release APK closed immediately on launch).
#
# Felicity is open source and already built with -dontobfuscate, so shrinking its OWN code buys
# very little size but is risky: the app leans on things R8 cannot see through —
#   * C++ that looks classes/constructors up by name (TagLibMetadata via FindClass/GetMethodID),
#   * Gson/Jackson/Room/Hilt/WorkManager reflection, fragments recreated by name,
#   * classes instantiated only from XML, manifests, widgets and service bindings.
# So keep every class in the app's own modules and let R8 shrink only third-party libraries.
-keep class app.simple.felicity.** { *; }

# Any class with native methods keeps them (and the class) for System.loadLibrary / RegisterNatives.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# Readable release stack traces (names aren't obfuscated, so lines are enough).
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature,Exceptions

# Gson TypeToken subclasses need their generic signature at runtime.
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn com.google.gson.**

# yt-dlp's unzip (youtubedl-android -> commons-compress) builds its zip "extra field" classes
# reflectively. Without this R8 turned AsiExtraField into a non-concrete class and yt-dlp could
# not initialise in release builds ("AsiExtraField is not a concrete class").
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.io.**
