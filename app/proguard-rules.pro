# NanoHTTPD
-keep class org.nanohttpd.** { *; }
# Keep reflection-facing names stable (we don't minify in release anyway)
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# LSPosed loads this class by its name in assets/xposed_init.
-keep class com.phonerouter.hotspot.xposed.** { *; }
