# Keep model classes that are (de)serialized by Firestore.
-keepclassmembers class com.nullsec.nullchat.model.** { *; }
-keep class com.nullsec.nullchat.model.** { *; }

# Media3
-dontwarn androidx.media3.**

# ffmpeg-kit
-keep class com.arthenica.ffmpegkit.** { *; }
