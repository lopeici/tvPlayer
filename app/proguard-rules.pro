# R8 rules for the release build. Media3, Cast, Coil, OkHttp and kotlinx.serialization ship their
# own consumer rules, so only reflection the libraries can't see needs keeping here.

# The Cast framework instantiates this by name from the manifest's OPTIONS_PROVIDER_CLASS_NAME
# meta-data, which R8 doesn't trace.
-keep class androidx.media3.cast.DefaultCastOptionsProvider { <init>(); }

# Keep line numbers so crash_log.txt stack traces stay readable (with the mapping file).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
