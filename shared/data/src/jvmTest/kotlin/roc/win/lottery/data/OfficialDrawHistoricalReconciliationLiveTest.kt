package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.DrawPolicy
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryPrizeCalculator
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeCheckStatus
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.RuleVersionSelector
import roc.win.lottery.domain.SourceEvidence
import roc.win.lottery.domain.TicketFieldOrigin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** 显式启用后对两种彩票各 20 个真实历史期号执行 B2 机器首轮对账。 */
class OfficialDrawHistoricalReconciliationLiveTest {
    /**
     * 双色球复用移动端双 JSON 仓库，大乐透交叉比较历史 JSON 与逐期官方 PDF。
     *
     * 默认不联网；测试只写规范化字段和证据哈希，不保存或输出官网原始响应。
     */
    @Test
    fun explicitlyEnabledFortyHistoricalIssuesMatchIndependentOfficialEvidence() =
        runTest(timeout = LIVE_TEST_TIMEOUT) {
            if (System.getenv(ENABLE_ENVIRONMENT_VARIABLE) != ENABLED_VALUE) return@runTest
            requireTwentyUniqueIssues(SUPER_LOTTO_ISSUES, SUPER_LOTTO_ISSUE_PATTERN)
            requireTwentyUniqueIssues(DOUBLE_COLOR_BALL_ISSUES, DOUBLE_COLOR_BALL_ISSUE_PATTERN)
            clearPreviousReport()

            val httpClient = createPlatformHttpClient()
            val draws =
                try {
                    reconcileSuperLotto(httpClient) + reconcileDoubleColorBall(httpClient)
                } finally {
                    httpClient.close()
                }
            assertEquals(EXPECTED_TOTAL_ISSUES, draws.size)
            assertRequiredCoverage(draws)
            writeNormalizedReport(draws.map { it.toReportRow() })
        }

    /** 独立滚动双色球 20 期台账，避免另一彩种的上游漂移阻断本次政策边界核验。 */
    @Test
    fun explicitlyEnabledTwentyDoubleColorBallIssuesMatchOfficialEvidence() =
        runTest(timeout = LIVE_TEST_TIMEOUT) {
            if (System.getenv(ENABLE_DOUBLE_COLOR_BALL_ENVIRONMENT_VARIABLE) != ENABLED_VALUE) return@runTest
            requireTwentyUniqueIssues(DOUBLE_COLOR_BALL_ISSUES, DOUBLE_COLOR_BALL_ISSUE_PATTERN)

            val httpClient = createPlatformHttpClient()
            val draws =
                try {
                    reconcileDoubleColorBall(httpClient)
                } finally {
                    httpClient.close()
                }
            assertEquals(DOUBLE_COLOR_BALL_ISSUES, draws.map { it.issue.value })
        }

    /** 逐期比较中国体彩网历史 JSON 与独立官方 PDF。 */
    private suspend fun reconcileSuperLotto(httpClient: HttpClient): List<DrawResult> {
        val draws = mutableListOf<DrawResult>()
        for (issue in SUPER_LOTTO_ISSUES) {
            val mainUrl = superLottoMainUrl(issue)
            val mainBody = fetchText(httpClient, mainUrl, JSON_ACCEPT)
            val main =
                requireParsed(
                    SuperLottoSourceAdapter.parseMain(mainBody, issue, mainUrl),
                    "大乐透 $issue 历史 JSON 未通过严格解析",
                )
            assertTrue(main.publicationFieldsComplete, "大乐透 $issue 公告字段不完整")
            val pdfUrl = assertNotNull(main.detailUrl, "大乐透 $issue 缺少公告地址")
            assertEquals(expectedSuperLottoPdfUrl(issue), pdfUrl, "大乐透 $issue 公告地址不符合固定期号路径")
            delay(REQUEST_INTERVAL_MILLIS)

            val pdfBytes = fetchBytes(httpClient, pdfUrl, PDF_ACCEPT)
            val supporting =
                requireParsed(
                    SuperLottoAnnouncementPdfParser.parse(pdfBytes, issue, pdfUrl),
                    "大乐透 $issue 官方 PDF 未通过严格解析",
                )
            assertSnapshotsEqual(main, supporting, issue)
            val draw = main.toVerifiedDrawResult(supporting)
            assertWinningRulePath(draw)
            draws += draw
            delay(REQUEST_INTERVAL_MILLIS)
        }
        return draws
    }

    /** 逐期复用正式 Repository 核对中国福彩网两份官方 JSON。 */
    private suspend fun reconcileDoubleColorBall(httpClient: HttpClient): List<DrawResult> {
        val draws = mutableListOf<DrawResult>()
        val repository = OfficialDrawRepository(httpClient = httpClient)
        for (issue in DOUBLE_COLOR_BALL_ISSUES) {
            val draw =
                assertIs<DrawQueryResult.Success>(
                    repository.getDraw(LotteryType.DOUBLE_COLOR_BALL, Issue(issue)),
                    "双色球 $issue 未取得双源一致结果",
                ).drawResult
            assertTrue(draw.status in VERIFIED_STATUSES, "双色球 $issue 未达到可验证状态")
            assertEquals(1, draw.supportingEvidence.size, "双色球 $issue 辅助证据数量异常")
            assertWinningRulePath(draw)
            draws += draw
            delay(REQUEST_INTERVAL_MILLIS)
        }
        return draws
    }

    /** 强制验证 B2 固定台账覆盖规则边界和奖金语义。 */
    private fun assertRequiredCoverage(draws: List<DrawResult>) {
        val byLottery = draws.groupBy { it.lotteryType }
        for (lotteryType in LotteryType.entries) {
            val lotteryDraws = byLottery[lotteryType].orEmpty()
            assertEquals(REQUIRED_ISSUES_PER_LOTTERY, lotteryDraws.size, "$lotteryType 对账期数不足")
            assertTrue(
                lotteryDraws.any { draw ->
                    draw.prizeTiers.any { tier ->
                        tier.code in FLOATING_TIER_CODES && tier.singlePrizeFen != null
                    }
                },
                "$lotteryType 未覆盖已发布的浮动奖",
            )
            assertTrue(
                lotteryDraws.any { draw ->
                    draw.prizeTiers.any { tier ->
                        tier.code in FLOATING_TIER_CODES &&
                            (tier.winnerCount == 0L || tier.additionalWinnerCount == 0L)
                    }
                },
                "$lotteryType 未覆盖一、二等奖或追加奖无人中出期次",
            )
        }

        val superLotto = byLottery.getValue(LotteryType.SUPER_LOTTO)
        assertIssueCoverage(superLotto, SUPER_LOTTO_FIRST_SUPPORTED_ISSUE, SUPER_LOTTO_LATEST_ISSUE)
        assertTrue(
            superLotto.any { draw ->
                draw.prizeTiers.any { tier ->
                    tier.additionalWinnerCount?.let { it > 0L } == true && tier.additionalPrizeFen != null
                }
            },
            "大乐透未覆盖实际中出的追加奖",
        )

        val doubleColorBall = byLottery.getValue(LotteryType.DOUBLE_COLOR_BALL)
        assertIssueCoverage(doubleColorBall, DOUBLE_COLOR_BALL_FIRST_SUPPORTED_ISSUE, DOUBLE_COLOR_BALL_LATEST_ISSUE)
        assertEquals(
            DrawPolicy.DOUBLE_COLOR_BALL_FORTUNE,
            doubleColorBall.single { it.issue.value == DOUBLE_COLOR_BALL_SPECIAL_LAST_ISSUE }.policy,
            "双色球特别规定末期政策异常",
        )
        assertEquals(
            DrawPolicy.STANDARD,
            doubleColorBall.single { it.issue.value == DOUBLE_COLOR_BALL_STANDARD_FIRST_ISSUE }.policy,
            "双色球特别规定退出边界异常",
        )
    }

    /** 验证一个彩种同时包含规则首期和本轮最新期。 */
    private fun assertIssueCoverage(
        draws: List<DrawResult>,
        firstSupportedIssue: String,
        latestIssue: String,
    ) {
        val issues = draws.map { it.issue.value }.toSet()
        assertTrue(firstSupportedIssue in issues, "对账台账缺少规则生效首期 $firstSupportedIssue")
        assertTrue(latestIssue in issues, "对账台账缺少本轮最新期 $latestIssue")
    }

    /** 比较两份大乐透官方证据中的身份字段和全部必要奖级。 */
    private fun assertSnapshotsEqual(
        main: MainDrawSnapshot,
        supporting: SupportingDrawSnapshot,
        issue: String,
    ) {
        assertEquals(main.issue, supporting.issue, "大乐透 $issue 期号冲突")
        assertEquals(main.drawDate, supporting.drawDate, "大乐透 $issue 开奖日期冲突")
        assertEquals(main.primaryNumbers, supporting.primaryNumbers, "大乐透 $issue 前区号码冲突")
        assertEquals(main.secondaryNumbers, supporting.secondaryNumbers, "大乐透 $issue 后区号码冲突")
        assertEquals(main.detailUrl, supporting.detailUrl, "大乐透 $issue 公告地址冲突")
        val mainTiers = main.prizeTiers.associateBy { it.code }
        val supportingTiers = supporting.prizeTiers.associateBy { it.code }
        assertEquals(mainTiers.keys, supportingTiers.keys, "大乐透 $issue 奖级集合冲突")
        for ((code, mainTier) in mainTiers) {
            val supportingTier = assertNotNull(supportingTiers[code], "大乐透 $issue 缺少奖级 $code")
            assertEquals(mainTier.winnerCount, supportingTier.winnerCount, "大乐透 $issue 奖级 $code 注数冲突")
            assertEquals(mainTier.singlePrizeFen, supportingTier.singlePrizeFen, "大乐透 $issue 奖级 $code 奖金冲突")
            assertEquals(
                mainTier.additionalWinnerCount,
                supportingTier.additionalWinnerCount,
                "大乐透 $issue 奖级 $code 追加注数冲突",
            )
            assertEquals(
                mainTier.additionalPrizeFen,
                supportingTier.additionalPrizeFen,
                "大乐透 $issue 奖级 $code 追加奖金冲突",
            )
        }
    }

    /** 将已交叉核对的大乐透快照转换为规则引擎输入。 */
    private fun MainDrawSnapshot.toVerifiedDrawResult(supporting: SupportingDrawSnapshot): DrawResult {
        val lotteryIssue = Issue(issue)
        val ruleVersion =
            assertNotNull(
                RuleVersionSelector().select(LotteryType.SUPER_LOTTO, lotteryIssue),
                "大乐透 $issue 不在已验证规则范围内",
            )
        val fetchedAt =
            kotlin.time.Clock.System
                .now()
                .toEpochMilliseconds()
        return DrawResult(
            lotteryType = LotteryType.SUPER_LOTTO,
            issue = lotteryIssue,
            drawDate = drawDate,
            primaryNumbers = primaryNumbers,
            secondaryNumbers = secondaryNumbers,
            status = if (payoutFieldsComplete) DrawStatus.FINAL_PAYOUT else DrawStatus.FINAL_NUMBERS,
            revision = 1,
            ruleVersion = ruleVersion.code,
            policy = assertNotNull(policy),
            prizeTiers = prizeTiers,
            evidence = evidence.toEvidence(fetchedAt),
            supportingEvidence = listOf(supporting.evidence.toEvidence(fetchedAt)),
        )
    }

    /** 使用每期开奖号码构造内存头奖票，验证规范化结果能驱动同一规则引擎。 */
    private fun assertWinningRulePath(draw: DrawResult) {
        val result = LotteryPrizeCalculator().calculate(winningTicket(draw), draw)
        assertEquals(PrizeCheckStatus.WIN, result.status, "${draw.lotteryType} ${draw.issue.value} 未识别为中奖")
        assertEquals(
            PrizeTierCodes.FIRST,
            result.lineResults.single().prizeTierCode,
            "${draw.lotteryType} ${draw.issue.value} 奖级异常",
        )
    }

    /** 使用规范化开奖号码创建单倍基本投注。 */
    private fun winningTicket(draw: DrawResult): ConfirmedTicket =
        ConfirmedTicket(
            lotteryType = confirmed(draw.lotteryType),
            issue = confirmed(draw.issue),
            betLines =
                listOf(
                    BetLine(
                        primaryNumbers = confirmed(draw.primaryNumbers),
                        secondaryNumbers = confirmed(draw.secondaryNumbers),
                        isAdditional = confirmed(false),
                        originalText = "",
                    ),
                ),
            multiplier = confirmed(1),
            periodCount = confirmed(1),
            paidAmountFen = confirmed(SINGLE_BET_AMOUNT_FEN),
        )

    /** 创建标记为测试内存输入的已确认字段。 */
    private fun <T> confirmed(value: T): ConfirmedValue<T> = ConfirmedValue(value, TicketFieldOrigin.USER)

    /** 将证据草稿补充本次获取时间和规范化哈希。 */
    private fun EvidenceDraft.toEvidence(fetchedAt: Long): SourceEvidence =
        SourceEvidence(sourceName, sourceUrl, fetchedAt, sha256Hex(canonicalContent))

    /** 将开奖结果转换为不含原始响应的报告行。 */
    private fun DrawResult.toReportRow(): ReconciliationRow =
        ReconciliationRow(
            lotteryType = lotteryType.name,
            issue = issue.value,
            drawDate = drawDate,
            numbers = (primaryNumbers + secondaryNumbers).joinToString(" ") { it.toString().padStart(2, '0') },
            primaryEvidenceSha256 = evidence.contentSha256,
            supportingEvidenceSha256 = supportingEvidence.single().contentSha256,
        )

    /** 获取官网文本响应，错误信息绝不包含响应正文。 */
    private suspend fun fetchText(
        httpClient: HttpClient,
        url: String,
        accept: String,
    ): String {
        val response = httpClient.get(url) { officialHeaders(accept) }
        check(response.status.isSuccess()) { "官网数据源返回 HTTP ${response.status.value}" }
        return response.bodyAsText()
    }

    /** 获取官网二进制响应，错误信息绝不包含响应正文。 */
    private suspend fun fetchBytes(
        httpClient: HttpClient,
        url: String,
        accept: String,
    ): ByteArray {
        val response = httpClient.get(url) { officialHeaders(accept) }
        check(response.status.isSuccess()) { "官网数据源返回 HTTP ${response.status.value}" }
        return response.bodyAsBytes()
    }

    /** 设置低频验收请求的固定请求头。 */
    private fun io.ktor.client.request.HttpRequestBuilder.officialHeaders(accept: String) {
        header(HttpHeaders.Accept, accept)
        header(HttpHeaders.CacheControl, "no-cache, no-store")
        header(HttpHeaders.UserAgent, USER_AGENT)
    }

    /** 构建包含精确单期参数的中国体彩网历史 JSON 地址。 */
    private fun superLottoMainUrl(issue: String): String =
        URLBuilder(SUPER_LOTTO_MAIN_URL)
            .apply {
                parameters.append("gameNo", "85")
                parameters.append("provinceId", "0")
                parameters.append("pageSize", "1")
                parameters.append("pageNo", "1")
                parameters.append("isVerify", "1")
                parameters.append("startTerm", issue)
                parameters.append("endTerm", issue)
            }.buildString()

    /** 返回与期号严格绑定的中国体彩网 PDF 地址。 */
    private fun expectedSuperLottoPdfUrl(issue: String): String = "https://pdf.sporttery.cn/33800/$issue/$issue.pdf"

    /** 验证固定台账恰好包含 20 个唯一且格式合法的期号。 */
    private fun requireTwentyUniqueIssues(
        issues: List<String>,
        pattern: Regex,
    ) {
        require(issues.size == REQUIRED_ISSUES_PER_LOTTERY) { "每种彩票必须恰好配置 20 个期号" }
        require(issues.distinct().size == REQUIRED_ISSUES_PER_LOTTERY) { "真实对账期号不得重复" }
        require(issues.all(pattern::matches)) { "真实对账期号格式不合法" }
    }

    /** 提取严格解析成功值，并只暴露不含原始响应的受控错误原因。 */
    private fun <T> requireParsed(
        result: SourceParseResult<T>,
        context: String,
    ): T =
        when (result) {
            is SourceParseResult.Success -> result.value
            SourceParseResult.NotPublished -> error("$context：官网未发布目标期号")
            is SourceParseResult.Publishing -> error("$context：${result.message}")
            is SourceParseResult.SourceUnavailable -> error("$context：${result.message}")
            is SourceParseResult.Conflict -> error("$context：${result.message}")
        }

    /** 将成功结果写入构建目录，便于复核且不进入版本库。 */
    private fun writeNormalizedReport(rows: List<ReconciliationRow>) {
        val report = File(REPORT_PATH)
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                appendLine("lotteryType\tissue\tdrawDate\tnumbers\tprimarySha256\tsupportingSha256")
                rows.forEach { appendLine(it.toTsv()) }
            },
        )
    }

    /** 清除该测试自身的旧报告，避免失败重跑后误读历史成功结果。 */
    private fun clearPreviousReport() {
        val report = File(REPORT_PATH)
        check(!report.exists() || report.delete()) { "无法清除上一轮 B2 对账报告" }
    }

    /** 一期规范化对账报告行。 */
    private data class ReconciliationRow(
        /** 彩票类型稳定编码。 */
        val lotteryType: String,
        /** 开奖期号。 */
        val issue: String,
        /** ISO 开奖日期。 */
        val drawDate: String,
        /** 规范化开奖号码。 */
        val numbers: String,
        /** 主证据规范化哈希。 */
        val primaryEvidenceSha256: String,
        /** 辅助证据规范化哈希。 */
        val supportingEvidenceSha256: String,
    ) {
        /** 返回不包含官网原始响应的 TSV 行。 */
        fun toTsv(): String =
            listOf(
                lotteryType,
                issue,
                drawDate,
                numbers,
                primaryEvidenceSha256,
                supportingEvidenceSha256,
            ).joinToString("\t")
    }

    /** 真实对账开关、固定期号和网络约束。 */
    private companion object {
        /** 显式启用真实历史对账的环境变量。 */
        const val ENABLE_ENVIRONMENT_VARIABLE = "WINLOTTERY_LIVE_RECONCILIATION"

        /** 显式启用双色球独立滚动对账的环境变量。 */
        const val ENABLE_DOUBLE_COLOR_BALL_ENVIRONMENT_VARIABLE = "WINLOTTERY_LIVE_SSQ_RECONCILIATION"

        /** 真实历史对账的启用值。 */
        const val ENABLED_VALUE = "1"

        /** 每种彩票必须对账的期数。 */
        const val REQUIRED_ISSUES_PER_LOTTERY = 20

        /** 两种彩票合计期数。 */
        const val EXPECTED_TOTAL_ISSUES = REQUIRED_ISSUES_PER_LOTTERY * 2

        /** 单倍基本投注金额，单位为分。 */
        const val SINGLE_BET_AMOUNT_FEN = 200L

        /** 相邻官网请求之间的最低间隔。 */
        const val REQUEST_INTERVAL_MILLIS = 250L

        /** 中国体彩网历史开奖接口。 */
        const val SUPER_LOTTO_MAIN_URL =
            "https://webapi.sporttery.cn/gateway/lottery/getHistoryPageListV1.qry"

        /** 只包含应用用途的固定请求标识。 */
        const val USER_AGENT = "WinLottery/0.1 (explicit B2 historical reconciliation)"

        /** JSON 响应类型。 */
        const val JSON_ACCEPT = "application/json"

        /** PDF 响应类型。 */
        const val PDF_ACCEPT = "application/pdf"

        /** 构建目录中的规范化报告路径。 */
        const val REPORT_PATH = "build/reports/b2/live-reconciliation.tsv"

        /** 真实网络测试最大时长。 */
        val LIVE_TEST_TIMEOUT = 5.minutes

        /** 大乐透固定 20 期台账，覆盖规则首期和本轮最新期。 */
        val SUPER_LOTTO_ISSUES =
            listOf(
                "26014",
                "26073",
                "26074",
                "26075",
                "26076",
                "26077",
                "26078",
                "26079",
                "26080",
                "26081",
                "26082",
                "26083",
                "26084",
                "26085",
                "26086",
                "26087",
                "26088",
                "26089",
                "26090",
                "26091",
            )

        /** 双色球固定 20 期台账，覆盖规则首期、特别规定退出边界和本轮最新期。 */
        val DOUBLE_COLOR_BALL_ISSUES =
            listOf(
                "2026014",
                "2026075",
                "2026076",
                "2026079",
                "2026080",
                "2026081",
                "2026082",
                "2026083",
                "2026084",
                "2026085",
                "2026086",
                "2026087",
                "2026088",
                "2026089",
                "2026090",
                "2026091",
                "2026092",
                "2026093",
                "2026094",
                "2026095",
            )

        /** 大乐透期号格式。 */
        val SUPER_LOTTO_ISSUE_PATTERN = Regex("^\\d{5}$")

        /** 双色球期号格式。 */
        val DOUBLE_COLOR_BALL_ISSUE_PATTERN = Regex("^\\d{7}$")

        /** 可以进入规则计算的开奖状态。 */
        val VERIFIED_STATUSES = setOf(DrawStatus.FINAL_NUMBERS, DrawStatus.FINAL_PAYOUT)

        /** 浮动奖金奖级。 */
        val FLOATING_TIER_CODES = setOf(PrizeTierCodes.FIRST, PrizeTierCodes.SECOND)

        /** 大乐透规则生效首期。 */
        const val SUPER_LOTTO_FIRST_SUPPORTED_ISSUE = "26014"

        /** 大乐透本轮最新期。 */
        const val SUPER_LOTTO_LATEST_ISSUE = "26091"

        /** 双色球规则生效首期。 */
        const val DOUBLE_COLOR_BALL_FIRST_SUPPORTED_ISSUE = "2026014"

        /** 双色球本轮最新期。 */
        const val DOUBLE_COLOR_BALL_LATEST_ISSUE = "2026095"

        /** 双色球特别规定最后一期。 */
        const val DOUBLE_COLOR_BALL_SPECIAL_LAST_ISSUE = "2026075"

        /** 双色球恢复普通规则首期。 */
        const val DOUBLE_COLOR_BALL_STANDARD_FIRST_ISSUE = "2026076"
    }
}
