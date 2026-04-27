# ---------------------------------------------------------------------------
# ProGuard configuration for the release Compose Desktop distribution.
#
# Everything in this file is either "silence a known-optional reference" or
# "preserve a class that's accessed reflectively at runtime". We don't use
# ProGuard for aggressive optimisation — minification keeps the binary
# reasonable and that's about it.
# ---------------------------------------------------------------------------

# --- OkHttp optional SSL providers ------------------------------------------
# OkHttp compiles support for BouncyCastle, Conscrypt and OpenJSSE but only
# uses them if those libraries are on the classpath. We don't ship any of
# them — the JVM's default JSSE is fine — so ProGuard's unresolved-reference
# checks would fail without these suppressions.
-dontwarn okhttp3.internal.platform.BouncyCastlePlatform
-dontwarn okhttp3.internal.platform.BouncyCastlePlatform$*
-dontwarn okhttp3.internal.platform.ConscryptPlatform
-dontwarn okhttp3.internal.platform.ConscryptPlatform$*
-dontwarn okhttp3.internal.platform.OpenJSSEPlatform
-dontwarn okhttp3.internal.platform.OpenJSSEPlatform$*
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# OkHttp also ships GraalVM native-image substitutions in
# `okhttp3.internal.graal.*` that reference `org.graalvm.*` and
# `com.oracle.svm.*`. We're building a regular JVM JAR, not a GraalVM
# native image, so those classes are dead code for us.
-dontwarn okhttp3.internal.graal.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**

# --- JNA --------------------------------------------------------------------
# JNA discovers native types via reflection, so we can't let ProGuard rename
# or strip any of its classes or the Library interfaces our code defines on
# top of it.
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-keep interface com.sun.jna.Library
-keep interface com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    public <fields>;
    public <methods>;
}
-dontwarn com.sun.jna.**

# Our own JNA Library interfaces — loaded by name from JNA's Native.load
-keep interface fr.gunduz.awstoken.desktop.chrome.DockIconVisibility$ObjC { *; }
-keep class fr.gunduz.awstoken.desktop.chrome.DockIconVisibility { *; }
-keep class fr.gunduz.awstoken.desktop.chrome.DockIconVisibility$* { *; }
-keep interface fr.gunduz.awstoken.desktop.chrome.NativeWindowDrag$ObjC { *; }
-keep class fr.gunduz.awstoken.desktop.chrome.NativeWindowDrag { *; }
-keep class fr.gunduz.awstoken.desktop.chrome.NativeWindowDrag$* { *; }

# --- AppIcons ---------------------------------------------------------------
# The hand-inlined Material Icons in `fr.gunduz.awstoken.ui.icons.AppIcons`
# use `by lazy` to initialise each `ImageVector`, which generates synthetic
# `AppIcons$Refresh$2`-style inner lambda classes. ProGuard's default
# renaming rules can rewrite or merge these in ways that break the lazy
# initialisation at runtime (symptom: a `VectorComposablesKt$Path$2.invoke`
# stack overflow when the first icon is composed in a release build).
# Keeping the whole icons package verbatim is essentially free — it's
# ~1 KB of path data in class form — and dodges the whole problem.
-keep class fr.gunduz.awstoken.ui.icons.** { *; }
-keepclassmembers class fr.gunduz.awstoken.ui.icons.** { *; }

# --- Okio / OkHttp / Ktor ---------------------------------------------------
# These libraries have Kotlin code with covariant return types (the `buffer`
# helpers in Okio return `BufferedSource` but internally construct
# `RealBufferedSource`). ProGuard's optimizer occasionally specialises those
# return types, producing bytecode that fails the JVM verifier at runtime:
#
#   java.lang.VerifyError: Bad return type
#   'okio/BufferedSource' is not assignable to 'okio/RealBufferedSource'
#
# Keeping the whole package intact is the cheapest, most reliable fix — these
# libraries don't shrink much anyway because almost every class is live.
-keep class okio.** { *; }
-keep interface okio.** { *; }
-keepclassmembers class okio.** { *; }
-dontwarn okio.**

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepclassmembers class okhttp3.** { *; }
-dontwarn okhttp3.**

-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.io.**
-dontwarn kotlinx.coroutines.**

# Ksoup's HTML / XML parser also relies on covariant returns for the
# Element / Node hierarchy — keep it whole to avoid the same class of
# VerifyError we saw in Okio.
-keep class com.fleeksoft.ksoup.** { *; }
-keep interface com.fleeksoft.ksoup.** { *; }
-keepclassmembers class com.fleeksoft.ksoup.** { *; }
-dontwarn com.fleeksoft.ksoup.**

# --- SLF4J ------------------------------------------------------------------
# A handful of Ktor / OkHttp transitive code paths reference org.slf4j
# lazily. We don't ship an SLF4J backend — Kermit prints to stdout — so
# these references are always dead-code eliminable.
-dontwarn org.slf4j.**

# --- Kermit -----------------------------------------------------------------
-keep class co.touchlab.kermit.** { *; }
-dontwarn co.touchlab.kermit.**

# --- Our main entry point ---------------------------------------------------
# Compose Desktop launches via `MainKt#main`. ProGuard defaults to keeping
# `public static void main` but belt-and-braces.
-keep class MainKt { *; }
-keepclassmembers class MainKt {
    public static void main(java.lang.String[]);
}

# Keep Compose runtime classes loaded by reflection during resource
# inflation. Compose already ships with its own consumer ProGuard rules but
# we have to opt in for the packaged release.
-keep class androidx.compose.** { *; }
-keep class org.jetbrains.compose.** { *; }
-dontwarn androidx.compose.**
-dontwarn org.jetbrains.compose.**

# --- Navigation 3 -----------------------------------------------------------
# Same covariant-return-type trap as Okio / OkHttp / Ksoup: ProGuard's
# optimizer specialises `SceneInfo`-typed return values inside
# `NavDisplayKt` to a narrower `NavigationEventInfo`, producing bytecode
# that fails the JVM verifier with
#
#   java.lang.VerifyError: Bad type on operand stack
#   'androidx/navigationevent/NavigationEventInfo' is not assignable to
#   'androidx/navigation3/scene/SceneInfo'
#
# Navigation 3 also uses reflection (SavedState / serialization) for its
# back-stack persistence, so keeping the whole package is the cheapest
# reliable fix.
-keep class androidx.navigation3.** { *; }
-keep interface androidx.navigation3.** { *; }
-keepclassmembers class androidx.navigation3.** { *; }
-dontwarn androidx.navigation3.**

-keep class androidx.navigationevent.** { *; }
-keep interface androidx.navigationevent.** { *; }
-keepclassmembers class androidx.navigationevent.** { *; }
-dontwarn androidx.navigationevent.**

-keep class org.jetbrains.androidx.navigation3.** { *; }
-keep class org.jetbrains.androidx.navigationevent.** { *; }
-dontwarn org.jetbrains.androidx.navigation3.**
-dontwarn org.jetbrains.androidx.navigationevent.**

# --- Serialization ----------------------------------------------------------
# kotlinx.serialization generates companion objects at compile time whose
# names are looked up reflectively via `Companion.serializer()`.
-keepattributes InnerClasses, EnclosingMethod, Signature
-keep,includedescriptorclasses class fr.gunduz.awstoken.**$$serializer { *; }
-keepclassmembers class fr.gunduz.awstoken.** {
    *** Companion;
}
-keepclasseswithmembers class fr.gunduz.awstoken.** {
    kotlinx.serialization.KSerializer serializer(...);
}
