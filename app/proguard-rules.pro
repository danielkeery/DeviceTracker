# Default ProGuard rules for DeviceTracker
-keepattributes *Annotation*
-keep class com.devicetracker.data.model.** { *; }
-keep class com.devicetracker.data.db.** { *; }
-keep class org.eclipse.jgit.** { *; }

# Keep Gson serialization
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class com.devicetracker.data.model.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.* { *; }
