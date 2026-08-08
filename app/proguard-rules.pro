# Keep Shizuku API classes
-keep class rikka.shizuku.** { *; }

# Keep serialization
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    <fields>;
}

# Keep model classes
-keep class com.mobileclaw.app.model.** { *; }
