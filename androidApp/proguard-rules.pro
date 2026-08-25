# 发布包保留代码裁剪和优化，仅关闭类与成员名称混淆。
-dontobfuscate

# PDFBox 的 JPEG 2000 编解码属于可选能力，本项目仅提取官方公告文本层。
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
