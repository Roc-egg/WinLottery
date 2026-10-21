# 发布包保留代码裁剪和优化，仅关闭类与成员名称混淆。
-dontobfuscate

# ONNX Runtime 原生层按类名和构造签名回调 Java 对象，AAR 自带规则只保留遥测类；
# 裁剪、合并或改写这些类会让推理时 JNI 找不到构造方法并直接终止进程。
-keep class ai.onnxruntime.** { *; }

# PDFBox 的 JPEG 2000 编解码属于可选能力，本项目仅提取官方公告文本层。
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
