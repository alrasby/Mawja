# Mawja release shrinking rules.
# AndroidX/Compose/Media3 publish their own consumer rules.

# NewPipe discovers extractor services and model classes through reflection.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn org.jsoup.**
-dontwarn javax.annotation.**

# Kotlin serialization uses generated serializers for persisted Track values.
-keep class com.mawja.player.data.Track { *; }
-keep class com.mawja.player.data.Track$$serializer { *; }
-keep class com.mawja.player.data.SourceType { *; }
-keep class com.mawja.player.data.SourceType$$serializer { *; }

# Keep the application entry point and Android components discoverable by the OS.
-keep class com.mawja.player.MawjaApp { *; }
-keep class com.mawja.player.MainActivity { *; }
-keep class com.mawja.player.playback.PlaybackService { *; }

-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
