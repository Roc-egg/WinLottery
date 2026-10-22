import Accelerate
import Foundation
import OnnxRuntimeBindings
import Shared

/// 使用微软官方 ONNX Runtime Swift Package 执行本地 OCR 模型。
final class AppleOnnxRuntime: NSObject, RecognitionIOSOnnxRuntime {
    /// 整个应用生命周期内唯一的 ONNX Runtime 环境。
    private let environment: ORTEnv?

    /// 各模型按文件名复用的推理 Session。
    private var sessions: [String: ORTSession] = [:]

    /// 保护 Session 延迟创建与推理，避免共享可变状态并发访问。
    private let lock = NSLock()

    /// 创建只输出错误日志的本地推理环境。
    override init() {
        environment = try? ORTEnv(loggingLevel: .error)
        super.init()
    }

    /// 执行单输入、单输出 Float32 模型；任何本地运行时错误都返回空，由共享层转换为可重试状态。
    ///
    /// 识别在后台线程连续执行上百次推理，每次都在独立的自动释放池中完成，避免输出张量累积到进程内存上限。
    func run(
        modelFileName: String,
        inputData: NSMutableData,
        inputShape: [NSNumber]
    ) -> RecognitionIOSOnnxTensorOutput? {
        lock.lock()
        defer { lock.unlock() }
        return autoreleasepool { runTensor(modelFileName: modelFileName, inputData: inputData, inputShape: inputShape) }
    }

    /// 执行文字识别模型，并直接在输出缓冲区内逐时间步取最大概率类别，不复制完整概率张量。
    func runCtc(
        modelFileName: String,
        inputData: NSMutableData,
        inputShape: [NSNumber]
    ) -> RecognitionIOSOnnxCtcOutput? {
        lock.lock()
        defer { lock.unlock() }
        return autoreleasepool { reduceCtc(modelFileName: modelFileName, inputData: inputData, inputShape: inputShape) }
    }

    /// 执行模型并复制完整 Float 输出；调用方须持有锁并提供自动释放池。
    private func runTensor(
        modelFileName: String,
        inputData: NSMutableData,
        inputShape: [NSNumber]
    ) -> RecognitionIOSOnnxTensorOutput? {
        do {
            guard let output = try runOutput(modelFileName: modelFileName, inputData: inputData, inputShape: inputShape)
            else { return nil }
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

    /// 执行文字识别模型并逐时间步归约；调用方须持有锁并提供自动释放池。
    private func reduceCtc(
        modelFileName: String,
        inputData: NSMutableData,
        inputShape: [NSNumber]
    ) -> RecognitionIOSOnnxCtcOutput? {
        do {
            guard let output = try runOutput(modelFileName: modelFileName, inputData: inputData, inputShape: inputShape)
            else { return nil }
            let outputInfo = try output.tensorTypeAndShapeInfo()
            let shape = outputInfo.shape.map(\.intValue)
            guard outputInfo.elementType == .float, shape.count == Self.ctcOutputRank else { return nil }
            let (batchSize, timeSteps, classCount) = (shape[0], shape[1], shape[2])
            let rowCount = batchSize * timeSteps
            let outputData = try output.tensorData()
            guard classCount > 0, outputData.length == rowCount * classCount * MemoryLayout<Float>.size else {
                return nil
            }
            var classIndices = [Int32](repeating: 0, count: rowCount)
            var scores = [Float](repeating: 0, count: rowCount)
            let probabilities = outputData.bytes.assumingMemoryBound(to: Float.self)
            for row in 0..<rowCount {
                var maximum: Float = 0
                var index: vDSP_Length = 0
                vDSP_maxvi(probabilities + row * classCount, 1, &maximum, &index, vDSP_Length(classCount))
                classIndices[row] = Int32(index)
                scores[row] = maximum
            }
            return RecognitionIOSOnnxCtcOutput(
                classIndices: classIndices.withUnsafeBytes { Data($0) },
                scores: scores.withUnsafeBytes { Data($0) },
                batchSize: Int32(batchSize),
                timeSteps: Int32(timeSteps),
                classCount: Int32(classCount)
            )
        } catch {
            return nil
        }
    }

    /// 把输入内存直接包装为张量并执行推理，返回唯一输出；调用方须持有锁。
    private func runOutput(
        modelFileName: String,
        inputData: NSMutableData,
        inputShape: [NSNumber]
    ) throws -> ORTValue? {
        guard let session = session(modelFileName: modelFileName) else { return nil }
        let input = try ORTValue(tensorData: inputData, elementType: .float, shape: inputShape)
        let outputs = try session.run(
            withInputs: [Self.inputName: input],
            outputNames: [Self.outputName],
            runOptions: nil
        )
        return outputs[Self.outputName]
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

    /// 模型固定输入名称。
    private static let inputName = "x"

    /// 模型固定输出名称。
    private static let outputName = "fetch_name_0"

    /// 文字识别输出固定为批次、时间步、类别三维。
    private static let ctcOutputRank = 3

    /// 限制单个 Session 的 CPU 并行度，控制移动端峰值功耗。
    private static let intraOpThreadCount: Int32 = 2
}
