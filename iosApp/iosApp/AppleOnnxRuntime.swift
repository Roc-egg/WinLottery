import Foundation
import OnnxRuntimeBindings
import Shared

/// 使用微软官方 ONNX Runtime Swift Package 执行 PP-OCRv5 模型。
final class AppleOnnxRuntime: NSObject, RecognitionIOSOnnxRuntime {
    /// 整个应用生命周期内唯一的 ONNX Runtime 环境。
    private let environment: ORTEnv?

    /// 三段模型按文件名复用的推理 Session。
    private var sessions: [String: ORTSession] = [:]

    /// 保护 Session 延迟创建与推理，避免共享可变状态并发访问。
    private let lock = NSLock()

    /// 创建只输出错误日志的本地推理环境。
    override init() {
        environment = try? ORTEnv(loggingLevel: .error)
        super.init()
    }

    /// 执行单输入、单输出 Float32 模型；任何本地运行时错误都返回空，由共享层转换为可重试状态。
    func run(
        modelFileName: String,
        inputData: Data,
        inputShape: [NSNumber]
    ) -> RecognitionIOSOnnxTensorOutput? {
        lock.lock()
        defer { lock.unlock() }
        guard let session = session(modelFileName: modelFileName) else { return nil }
        do {
            let input = try ORTValue(
                tensorData: NSMutableData(data: inputData),
                elementType: .float,
                shape: inputShape
            )
            let outputs = try session.run(
                withInputs: [Self.inputName: input],
                outputNames: [Self.outputName],
                runOptions: nil
            )
            guard let output = outputs[Self.outputName] else { return nil }
            let outputData = try output.tensorData()
            let outputInfo = try output.tensorTypeAndShapeInfo()
            guard outputInfo.elementType == .float else { return nil }
            return RecognitionIOSOnnxTensorOutput(
                data: outputData as Data,
                shape: outputInfo.shape
            )
        } catch {
            return nil
        }
    }

    /// 从主 Bundle 根目录加载并缓存一个模型 Session。
    private func session(modelFileName: String) -> ORTSession? {
        if let existing = sessions[modelFileName] { return existing }
        guard
            let environment,
            let modelURL = Bundle.main.url(forResource: modelFileName, withExtension: nil)
        else {
            return nil
        }
        do {
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(Self.intraOpThreadCount)
            try options.setGraphOptimizationLevel(.all)
            let created = try ORTSession(
                env: environment,
                modelPath: modelURL.path,
                sessionOptions: options
            )
            sessions[modelFileName] = created
            return created
        } catch {
            return nil
        }
    }

    /// PP-OCRv5 ONNX 图固定输入名称。
    private static let inputName = "x"

    /// PP-OCRv5 ONNX 图固定输出名称。
    private static let outputName = "fetch_name_0"

    /// 限制单个 Session 的 CPU 并行度，控制移动端峰值功耗。
    private static let intraOpThreadCount: Int32 = 2
}
