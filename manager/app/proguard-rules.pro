# ============================================================
# Silent mode: strip ALL android.util.Log calls at build time.
# R8 (full mode) removes these calls entirely from the release
# APK, so the manager never writes anything to logcat.
# ============================================================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
}

# Keep the bug-report / log-collection helpers (they collect system
# logs on demand via shell, they do NOT emit logs themselves).
-keep class com.android.video.ui.util.LogEvent { *; }

# Protobuf
-shrinkunusedprotofields

# Commons-compress
-dontwarn com.github.luben.zstd.**