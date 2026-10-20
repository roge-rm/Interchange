# The game reads its own data, not by reflection, so the defaults are enough. Keep line numbers for crash reports.
-keepattributes SourceFile,LineNumberTable
# JNI looks this class up by name.
-keep class com.rm.interchange.audio.Synth { *; }
