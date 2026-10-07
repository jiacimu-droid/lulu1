# Voice SDK model reflection and native callbacks must survive shrinking.
-keep class io.elevenlabs.** { *; }
-keep class io.livekit.** { *; }
-keep class org.webrtc.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault

# WorkManager restores persisted class names, including work queued by older APKs.
-keep class * extends androidx.work.ListenableWorker { *; }
