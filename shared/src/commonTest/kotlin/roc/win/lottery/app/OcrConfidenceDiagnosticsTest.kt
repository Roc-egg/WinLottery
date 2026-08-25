package roc.win.lottery.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** 匿名 OCR 字段置信度诊断测试。 */
class OcrConfidenceDiagnosticsTest {
    /** 日志只能包含样本序号、解析状态、清理后的引擎名、字段类型和合法分数。 */
    @Test
    fun diagnosticLineContainsOnlyAnonymousFieldConfidenceData() {
        val lines = mutableListOf<String>()
        val diagnostics = LogOcrConfidenceDiagnostics(lines::add)

        diagnostics.record(
            engineName = "Vision\t26.5\nSimulator",
            sample =
                OcrConfidenceSample(
                    outcome = OcrConfidenceOutcome.CORRECTION,
                    fields =
                        listOf(
                            OcrFieldConfidence(OcrConfidenceField.LOTTERY_TYPE, 0.91f),
                            OcrFieldConfidence(OcrConfidenceField.BET_LINE, null),
                            OcrFieldConfidence(OcrConfidenceField.PAID_AMOUNT, 1.2f),
                        ),
                ),
        )

        assertEquals(
            "WINLOTTERY_OCR_CONFIDENCE\tsample=1\toutcome=correction\t" +
                "engine=Vision 26.5 Simulator\t" +
                "fields=lotteryType:0.91,betLine:unknown,paidAmount:unknown",
            lines.single(),
        )
    }

    /** 平台日志接口异常不得中断用户的 OCR 和人工校正流程。 */
    @Test
    fun loggerFailureDoesNotEscapeDiagnostics() {
        val diagnostics = LogOcrConfidenceDiagnostics { error("测试日志不可用") }

        diagnostics.record(
            engineName = "测试引擎",
            sample = OcrConfidenceSample(OcrConfidenceOutcome.UNSUPPORTED, emptyList()),
        )
    }
}
