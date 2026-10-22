package roc.win.lottery.recognition

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/** 一次识别中各模型的推理次数与累计耗时，只用于性能诊断，不含任何票面内容。 */
internal class PpOcrInferenceStats {
    /** 按模型序号累计的推理次数。 */
    private val counts = IntArray(PpOcrModel.entries.size)

    /** 按模型序号累计的推理微秒数。 */
    private val microseconds = LongArray(PpOcrModel.entries.size)

    /** 记录一次推理。 */
    fun record(
        model: PpOcrModel,
        elapsedMicroseconds: Long,
    ) {
        counts[model.ordinal] += 1
        microseconds[model.ordinal] += elapsedMicroseconds
    }

    /** 返回指定模型的推理次数。 */
    fun count(model: PpOcrModel): Int = counts[model.ordinal]

    /** 返回指定模型的累计推理微秒数。 */
    fun microseconds(model: PpOcrModel): Long = microseconds[model.ordinal]
}

/**
 * 推理耗时采集开关。
 *
 * 只有验收或回归工具显式开启时才记录；未开启时生产路径只多一次空判断。识别流程逐框串行执行，
 * 同一时刻只有一个采集对象。
 */
internal object PpOcrInferenceDiagnostics {
    /** 当前正在采集的统计对象。 */
    @Volatile
    private var active: PpOcrInferenceStats? = null

    /** 在采集开启期间执行 [block]，返回其结果和本次统计。 */
    suspend fun <T> collect(block: suspend () -> T): Pair<T, PpOcrInferenceStats> {
        val stats = PpOcrInferenceStats()
        active = stats
        try {
            return block() to stats
        } finally {
            active = null
        }
    }

    /** 执行一次推理；采集开启时累计该模型的次数与耗时。 */
    fun <T> measure(
        model: PpOcrModel,
        block: () -> T,
    ): T {
        val stats = active ?: return block()
        val start = TimeSource.Monotonic.markNow()
        try {
            return block()
        } finally {
            stats.record(model, start.elapsedNow().inWholeMicroseconds)
        }
    }
}
