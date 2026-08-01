# ---------------------------------------------------------------------------
# DebridTV — conservative R8 keep rules
# Goal: shrink/optimize safely without breaking reflection-driven libraries
# (kotlinx.serialization, Retrofit, OkHttp, Media3/ExoPlayer, Coil, Compose).
# ---------------------------------------------------------------------------

# Keep annotations, signatures and generics needed at runtime by the libs below.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# --- kotlinx.serialization ------------------------------------------------
-dontnote kotlinx.serialization.**
# Keep generated serializers.
-keepclassmembers class **$$serializer { *; }
# Keep the companion serializer() accessor on our @Serializable model classes.
-keepclasseswithmembers class io.debridtv.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep all of our model/domain classes and their members (serialized by name).
-keep class io.debridtv.app.domain.** { *; }
-keep class io.debridtv.app.data.** { *; }
-keep class io.debridtv.app.update.** { *; }
# Enum handling for serialization.
-keepclassmembers enum io.debridtv.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Retrofit / OkHttp / kotlinx-serialization-converter ------------------
# (Retrofit & OkHttp ship consumer rules, these are belt-and-suspenders.)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
# Keep Retrofit service interfaces (methods are invoked reflectively).
-keep,allowobfuscation interface io.debridtv.app.** { @retrofit2.http.* <methods>; }
-keepclasseswithmembers interface io.debridtv.app.** {
    @retrofit2.http.* <methods>;
}

# --- Coroutines -----------------------------------------------------------
-dontwarn kotlinx.coroutines.**

# --- Media3 / ExoPlayer ---------------------------------------------------
# Media3 ships consumer rules; suppress warnings for optional integrations.
-dontwarn androidx.media3.**

# --- Coil -----------------------------------------------------------------
-dontwarn coil.**

# --- Compose --------------------------------------------------------------
# Compose is R8-friendly; no keep rules required beyond the defaults.
