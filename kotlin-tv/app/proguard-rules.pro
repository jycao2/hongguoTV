-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
# ByteVC2 NDK 解码器：JNI_OnLoad 用 FindClass + RegisterNatives 按「类名 + 方法名」注册，
# R8 不能重命名或移除 Bvc2Decoder / native 方法。
-keep class com.hongguotv.nativeapp.Bvc2Decoder { *; }
# 融合方案：自建 Extractor/Renderer 由 Vc2ExtractorsFactory/Vc2RenderersFactory 显式引用，
# 且 JNI 按类名注册，R8 一并保护整个 decoder 包避免反射/裁剪问题。
-keep class com.hongguotv.nativeapp.decoder.** { *; }
# 编译开关：Vc2.api 按类名反射加载真实实现（仅 -Pvc2test 构建存在该类，无解码器构建为无害空规则）。
-keep class com.hongguotv.nativeapp.vc2.Vc2ApiProvider { *; }
-keepclasseswithmembernames class * { native <methods>; }
