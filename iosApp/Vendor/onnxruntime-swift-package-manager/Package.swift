// swift-tools-version: 5.9

// 改编自微软 onnxruntime-swift-package-manager 1.24.2（提交 b7fb7f7d，MIT 许可，见 LICENSE）。
// 上游仓库已于 2026-11 从 GitHub 移除，这里只保留应用用到的 Objective-C 绑定，
// 去掉扩展库、训练接口和测试；ONNX Runtime 本体改用本地 xcframework，
// 由 tools/ios/fetch_onnxruntime.py 从官方 NuGet 包中取出并校验。

import PackageDescription

let package = Package(
    name: "onnxruntime",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "onnxruntime",
                 type: .static,
                 targets: ["OnnxRuntimeBindings"]),
    ],
    targets: [
        .target(name: "OnnxRuntimeBindings",
                dependencies: ["onnxruntime"],
                path: "objectivec",
                cxxSettings: [
                    .define("SPM_BUILD"),
                ]),
        .binaryTarget(name: "onnxruntime",
                      path: "Frameworks/onnxruntime.xcframework"),
    ],
    cxxLanguageStandard: .cxx17
)
