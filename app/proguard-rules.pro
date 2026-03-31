# Castla ProGuard Rules

# NanoHTTPD — uses reflection internally
-keep class fi.iki.elonen.** { *; }

# Shizuku — AIDL + reflection-based service
-keep class com.castla.mirror.shizuku.IPrivilegedService { *; }
-keep class com.castla.mirror.shizuku.IPrivilegedService$* { *; }
-keep class com.castla.mirror.shizuku.PrivilegedService { *; }

# ZXing QR code
-keep class com.google.zxing.** { *; }

# Shizuku SDK
-keep class rikka.shizuku.** { *; }

# Keep AIDL-generated Stub/Proxy
-keep class * extends android.os.IInterface { *; }
-keep class * extends android.os.Binder { *; }

# Google AdMob / Play Services Ads
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.ads.** { *; }

# Strip debug logs in release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
