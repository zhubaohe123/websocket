# Add project specific ProGuard rules here.
# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
# Keep Gson
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*
# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }
