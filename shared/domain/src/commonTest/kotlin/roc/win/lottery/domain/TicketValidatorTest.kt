package roc.win.lottery.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1 已确认彩票领域校验测试。 */
class TicketValidatorTest {
    /** 被测校验器。 */
    private val validator = TicketValidator()

    /** 合法大乐透追加单式票应通过严格金额校验。 */
    @Test
    fun validSuperLottoAdditionalTicketPasses() {
        val result = validator.validate(superLottoTicket())

        assertEquals(TicketValidationStatus.VALID, result.status)
        assertTrue(result.canCalculate)
        assertTrue(result.problems.isEmpty())
    }

    /** 合法双色球多注倍投票应通过校验。 */
    @Test
    fun validDoubleColorBallMultipleLinesPasses() {
        val ticket =
            confirmedTicket(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = "2026091",
                lines =
                    listOf(
                        betLine(listOf(1, 6, 11, 19, 25, 31), listOf(9)),
                        betLine(listOf(2, 7, 14, 21, 27, 33), listOf(16)),
                    ),
                multiplier = 3,
                paidAmountFen = 1_200L,
            )

        val result = validator.validate(ticket)

        assertEquals(TicketValidationStatus.VALID, result.status)
        assertTrue(result.canCalculate)
    }

    /** 复式形态必须被识别为 V1 不支持，不能截断为单式。 */
    @Test
    fun tooManyNumbersIsUnsupported() {
        val ticket =
            superLottoTicket(
                lines =
                    listOf(
                        betLine(
                            primary = listOf(2, 7, 14, 21, 29, 33),
                            secondary = listOf(4, 9),
                            additional = true,
                        ),
                    ),
            )

        val result = validator.validate(ticket)

        assertEquals(TicketValidationStatus.UNSUPPORTED, result.status)
        assertFalse(result.canCalculate)
        assertTrue(result.problems.any { it.field.endsWith("primaryNumbers") })
    }

    /** 号码越界属于无效输入。 */
    @Test
    fun outOfRangeNumberIsInvalid() {
        val ticket =
            superLottoTicket(
                lines =
                    listOf(
                        betLine(
                            primary = listOf(2, 7, 14, 21, 36),
                            secondary = listOf(4, 9),
                            additional = true,
                        ),
                    ),
            )

        val result = validator.validate(ticket)

        assertEquals(TicketValidationStatus.INVALID, result.status)
        assertFalse(result.canCalculate)
    }

    /** 同一区域的重复号码属于无效输入。 */
    @Test
    fun duplicatedNumberIsInvalid() {
        val ticket =
            superLottoTicket(
                lines =
                    listOf(
                        betLine(
                            primary = listOf(2, 7, 14, 21, 33),
                            secondary = listOf(4, 4),
                            additional = true,
                        ),
                    ),
            )

        val result = validator.validate(ticket)

        assertEquals(TicketValidationStatus.INVALID, result.status)
        assertTrue(result.problems.any { it.message.contains("重复") })
    }

    /** 双色球出现追加属性时必须阻断。 */
    @Test
    fun doubleColorBallAdditionalFlagIsInvalid() {
        val ticket =
            confirmedTicket(
                lotteryType = LotteryType.DOUBLE_COLOR_BALL,
                issue = "2026091",
                lines = listOf(betLine(listOf(1, 6, 11, 19, 25, 31), listOf(9), additional = true)),
                multiplier = 1,
                paidAmountFen = 200L,
            )

        val result = validator.validate(ticket)

        assertEquals(TicketValidationStatus.INVALID, result.status)
        assertTrue(result.problems.any { it.field.endsWith("isAdditional") })
    }

    /** 年中安全范围内的多期票应按全部期次严格核对金额。 */
    @Test
    fun multiplePeriodsWithExactAmountPass() {
        val ticket =
            superLottoTicket().copy(
                periodCount = confirmed(10),
                paidAmountFen = confirmed(3_000L),
            )

        val result = validator.validate(ticket)

        assertEquals(TicketValidationStatus.VALID, result.status)
        assertTrue(result.canCalculate)
    }

    /** 多期票金额仍只要少算一期就必须阻断。 */
    @Test
    fun multiplePeriodsWithSinglePeriodAmountAreInvalid() {
        val ticket = superLottoTicket().copy(periodCount = confirmed(10))

        val result = validator.validate(ticket)

        assertEquals(TicketValidationStatus.INVALID, result.status)
        assertTrue(result.problems.any { it.field == "paidAmountFen" })
    }

    /** 超过移动首版批量查询上限的期数必须明确阻断。 */
    @Test
    fun tooManyPeriodsAreUnsupported() {
        val ticket =
            superLottoTicket().copy(
                periodCount = confirmed(21),
                paidAmountFen = confirmed(6_300L),
            )

        val result = validator.validate(ticket)

        assertEquals(TicketValidationStatus.UNSUPPORTED, result.status)
        assertFalse(result.canCalculate)
    }

    /** 投注倍数边界之外的值属于无效输入。 */
    @Test
    fun multiplierOutsideRangeIsInvalid() {
        val ticket = superLottoTicket().copy(multiplier = confirmed(100))

        val result = validator.validate(ticket)

        assertEquals(TicketValidationStatus.INVALID, result.status)
    }

    /** 支付金额必须与逐行价格严格一致。 */
    @Test
    fun mismatchedPaidAmountIsInvalid() {
        val ticket = superLottoTicket().copy(paidAmountFen = confirmed(600L))

        val result = validator.validate(ticket)

        assertEquals(TicketValidationStatus.INVALID, result.status)
        assertTrue(result.problems.any { it.field == "paidAmountFen" })
    }

    /** 大乐透期号必须保留为五位纯数字文本。 */
    @Test
    fun malformedIssueIsInvalid() {
        val ticket = superLottoTicket().copy(issue = confirmed(Issue("2026091")))

        val result = validator.validate(ticket)

        assertEquals(TicketValidationStatus.INVALID, result.status)
        assertTrue(result.problems.any { it.field == "issue" })
    }

    /** 创建一张合法大乐透追加票。 */
    private fun superLottoTicket(
        lines: List<BetLine> = listOf(betLine(listOf(2, 7, 14, 21, 33), listOf(4, 9), additional = true)),
    ): ConfirmedTicket =
        confirmedTicket(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = "26091",
            lines = lines,
            multiplier = 1,
            paidAmountFen = 300L,
        )

    /** 创建指定字段的已确认彩票。 */
    private fun confirmedTicket(
        lotteryType: LotteryType,
        issue: String,
        lines: List<BetLine>,
        multiplier: Int,
        paidAmountFen: Long,
    ): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = confirmed(lotteryType),
            issue = confirmed(Issue(issue)),
            betLines = lines,
            multiplier = confirmed(multiplier),
            periodCount = confirmed(1),
            paidAmountFen = confirmed(paidAmountFen),
        )

    /** 创建一行已确认单式投注。 */
    private fun betLine(
        primary: List<Int>,
        secondary: List<Int>,
        additional: Boolean = false,
    ): BetLine =
        BetLine(
            primaryNumbers = confirmed(primary),
            secondaryNumbers = confirmed(secondary),
            isAdditional = confirmed(additional),
            originalText = "测试票面文本",
        )

    /** 创建由用户确认的字段。 */
    private fun <T> confirmed(value: T): ConfirmedValue<T> = ConfirmedValue(value, TicketFieldOrigin.USER)
}
