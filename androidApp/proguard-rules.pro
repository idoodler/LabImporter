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
