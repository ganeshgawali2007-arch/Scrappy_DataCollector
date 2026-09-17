# scrappy R8 rules (P13.4). Minify + shrink are on for release.

# --- JNI bridges (P7/P10) ---------------------------------------------------
# Native methods are resolved by name; renaming the class or its methods breaks
# the C++ symbol lookup in libscraper_native.so.
-keepclasseswithmembernames class com.scraper.classroomcapture.asr.JniWhisperBridge { native <methods>; }
-keepclasseswithmembernames class com.scraper.classroomcapture.llm.JniLlamaBridge { native <methods>; }

# --- kotlinx.serialization --------------------------------------------------
# Keep generated serializers + @Serializable companions (wire names must not
# change: snake_case @SerialName pins the dataset schema, D16/D18).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.scraper.classroomcapture.** {
    *** Companion;
}
-keepclasseswithmembers class com.scraper.classroomcapture.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.scraper.classroomcapture.**$$serializer { *; }
-keepclassmembers class **$$serializer { *** deserialize(...); *** serialize(...); }

# --- Room -------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Compose / lifecycle ----------------------------------------------------
-dontwarn org.jetbrains.annotations.**