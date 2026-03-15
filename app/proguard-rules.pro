# SonosWidget ProGuard Rules
# ProGuard/R8 obfuscation is optional for personal use but recommended.

# Keep Hilt-generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Keep Glance widget classes
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# Keep Retrofit interfaces
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep data classes used for serialization
-keep class com.sycamorecreek.sonoswidget.sonos.SonosModels$* { *; }
