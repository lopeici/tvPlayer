# Minify is disabled for this sideloaded build; rules kept minimal.
# Keep Media3 / Cast classes referenced by reflection if minify is later enabled.
-keep class androidx.media3.** { *; }
-keep class com.google.android.gms.cast.** { *; }
