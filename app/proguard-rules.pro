# R8/ProGuard rules
-dontwarn io.github.libxposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Only keep what is accessed reflectively or by name from the Xposed framework.
-keep class com.zkincamcam.xcam.xposed.XCamModule { *; }
-keep class com.zkincamcam.xcam.hooks.** { *; }
-keep class com.zkincamcam.xcam.data.Constants { *; }

-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }
-keep interface io.github.libxposed.service.** { *; }

# Media3 effect classes are constructed via reflection by the Transformer pipeline.
-keep class com.zkincamcam.xcam.core.capture.DeepLivenessShader { *; }
-keep class com.zkincamcam.xcam.core.capture.LivenessMotionEffect { *; }

-keepattributes *Annotation*,Signature,EnclosingMethod,InnerClasses,SourceFile,LineNumberTable
