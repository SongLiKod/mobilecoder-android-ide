# MobileCoder 移动码匠 混淆/收缩规则
#
# 当前 app/build.gradle.kts 中 release 为 isMinifyEnabled = false，
# R8 不会执行，本文件仅作为 build.gradle.kts 中 proguardFiles(...) 的
# 既有引用目标；一旦未来开启混淆，下列规则保证功能不被破坏。

# --- JNI 原生桥接 ---
# native 层符号为 Java_com_mobilecoder_ide_core_nativebridge_*，
# 类名/方法名一旦被重命名或裁剪，System.loadLibrary 后将抛出 UnsatisfiedLinkError。
-keep class com.mobilecoder.ide.core.nativebridge.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}

# --- 反射与序列化所需属性 ---
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# --- Compose 相关（开启混淆时保留）---
-dontwarn org.jetbrains.annotations.**
