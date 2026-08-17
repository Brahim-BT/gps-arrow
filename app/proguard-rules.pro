# Tier 1 is plain Kotlin with no reflection, so R8 needs almost nothing kept.

# Keep the core data model names readable in crash reports.
-keepnames class dev.gpsarrow.core.** { *; }

# v1: MapLibre Native uses JNI callbacks into Java. Uncomment when :maps is added.
# -keep class org.maplibre.android.** { *; }
# -keep class com.mapbox.** { *; }
# -dontwarn org.maplibre.**

# v2: BRouter loads routing profiles reflectively by class name.
# -keep class btools.** { *; }
