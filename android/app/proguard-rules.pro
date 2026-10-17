# MapLibre and kotlinx.serialization ship their own consumer rules.

# valhalla-mobile reads its config and its errors with Moshi's reflection (KotlinJsonAdapterFactory),
# and its consumer rules only cover the JNI side. Keep the classes Moshi reflects on, and the
# Kotlin metadata it reads them through, or the phone's Valhalla fails to start in release builds.
-keep class com.valhalla.config.models.** { *; }
-keep class com.valhalla.valhalla.ErrorResponse { *; }
-keep class kotlin.Metadata { *; }
