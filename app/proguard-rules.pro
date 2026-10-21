# Voice SDK model reflection and native callbacks must survive shrinking.
-keep class io.livekit.** { *; }
-keep class org.webrtc.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault

# WorkManager restores persisted class names, including work queued by older APKs.
-keep class * extends androidx.work.ListenableWorker { *; }

# Stable public response entrypoint: Android 16 rejected its R8-rewritten
# coroutine method (15 parameters) with Undefined high register.
# This only affects minified variants (release). The phone variant is
# intentionally D8-only until a device-tested refactor lands.
-keep class com.jiacimu.lulu.system.LuluDeviceToolBridge { *; }
