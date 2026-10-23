# kotlinx.serialization: keep generated serializers for @Serializable types.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }
-keep class dev.idoodler.labimporter.core.** { *; }
# androidx.sqlite bundled driver calls into Kotlin/JNI by name.
-keep class androidx.sqlite.driver.bundled.** { *; }
# ML Kit resolves its internal services (e.g. SharedPrefManager) through
# Firebase-style component registrars named in the manifest. R8's optimisation
# of that plumbing made the GenAI Prompt API client fail to construct in release
# builds (NullPointerException in Generation.getClient), so the app reported
# "unsupported" without ever asking AICore. Keep ML Kit's component machinery intact.
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.mlkit.common.** { *; }

# LiteRT-LM (Gemma) ships no R8 rules of its own: its native library calls back into
# these classes by name (messages, contents, configs), so they must keep their names.
-keep class com.google.ai.edge.litertlm.** { *; }
