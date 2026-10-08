package roc.win.lottery.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.PrizeTier
import roc.win.lottery.domain.PrizeTierCodes
import roc.win.lottery.domain.RuleVersionSelector
import roc.win.lottery.domain.SourceEvidence
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 低频、用户操作触发的官网开奖仓库。
 *
 * 原始响应只在单次调用内存中解析；该对象只保存当前进程的规范化哈希和修订状态，不持久化任何响应。
 *
 * @property httpClient 使用当前平台网络引擎的 Ktor 客户端。
 * @property clock 为证据时间和开奖日期合理性提供可测试时钟。
 * @property ruleVersionSelector 根据期号选择规则版本。
 * @property superLottoPdfTextExtractor 当前平台的官方 PDF 文本层提取能力。
 */
class OfficialDrawRepository(
    private val httpClient: HttpClient = createPlatformHttpClient(),
    private val clock: Clock = Clock.System,
    private val ruleVersionSelector: RuleVersionSelector = RuleVersionSelector(),
    private val superLottoPdfTextExtractor: SuperLottoPdfTextExtractor? = null,
) : DrawRepository,
    HistoricalDrawRepository {
    /** 当前进程内每个精确期号最近一次主数据面内容与修订号。 */
    private val revisions = mutableMapOf<DrawKey, DrawRevision>()

    /** 保护修订状态。 */
    private val revisionMutex = Mutex()

    /**
     * 按用户确认的彩种和期号查询官网开奖结果。
     *
     * 主数据面给出已审核发布的开奖号码即可形成结果；辅助数据面只用于交叉核对，暂不可用时不阻断结果，
     * 仅在两个官方数据面的开奖号码互相矛盾时返回冲突。方法不会猜测最新期，也不会自动重试或轮询。
     */
    override suspend fun getDraw(
        lotteryType: LotteryType,
        issue: Issue,
    ): DrawQueryResult {
        if (!isValidIssueFormat(lotteryType, issue.value)) {
            return unavailable(DrawStatus.SOURCE_UNAVAILABLE, "期号格式不合法，未发起官网查询")
        }
        val ruleVersion =
            ruleVersionSelector.select(lotteryType, issue)
                ?: return unavailable(DrawStatus.PUBLISHING, "该期号不在 V1 已验证规则范围内")
        val endpoints = DrawEndpoints.forIssue(lotteryType, issue.value)

        val mainBody =
            when (val fetched = fetch(endpoints.mainUrl, "主")) {
                is FetchResult.Success -> {
                    fetched.body
                }

                is FetchResult.NetworkUnavailable -> {
                    return unavailable(DrawStatus.NETWORK_UNAVAILABLE, fetched.message)
                }

                is FetchResult.SourceUnavailable -> {
                    return unavailable(DrawStatus.SOURCE_UNAVAILABLE, fetched.message)
                }
            }
        val main =
            when (
                val parsed =
                    parseMain(
                        lotteryType = lotteryType,
                        rawJson = mainBody,
                        targetIssue = issue.value,
                        sourceUrl = endpoints.mainUrl,
                    )
            ) {
                is SourceParseResult.Success -> {
                    parsed.value
                }

                SourceParseResult.NotPublished -> {
                    return unavailable(DrawStatus.NOT_PUBLISHED, "官网尚未发布该期开奖结果查询")
                }

                is SourceParseResult.Publishing -> {
                    return unavailable(DrawStatus.PUBLISHING, parsed.message)
                }

                is SourceParseResult.SourceUnavailable -> {
                    return unavailable(DrawStatus.SOURCE_UNAVAILABLE, parsed.message)
                }

                is SourceParseResult.Conflict -> {
                    return unavailable(DrawStatus.CONFLICT, parsed.message)
                }
            }

        val fetchedAtMillis = clock.now().toEpochMilliseconds()
        if (!isDrawDateReasonable(main.drawDate, fetchedAtMillis)) {
            return unavailable(DrawStatus.SOURCE_UNAVAILABLE, "开奖日期晚于当前北京时间")
        }
        val supporting = loadSupporting(lotteryType, endpoints.supportingUrl, main, issue.value)
        val consistency = supporting?.let { compareSnapshots(main, it) }
        if (consistency == SnapshotConsistency.NUMBER_CONFLICT) {
            return unavailable(DrawStatus.CONFLICT, "两个官方数据面给出的开奖号码不一致")
        }
        val payoutConfirmed = main.payoutFieldsComplete && consistency != SnapshotConsistency.PRIZE_MISMATCH
        return DrawQueryResult.Success(
            DrawResult(
                lotteryType = lotteryType,
                issue = issue,
                drawDate = main.drawDate,
                primaryNumbers = main.primaryNumbers,
                secondaryNumbers = main.secondaryNumbers,
                status = if (payoutConfirmed) DrawStatus.FINAL_PAYOUT else DrawStatus.FINAL_NUMBERS,
                revision = recordRevision(DrawKey(lotteryType, issue.value), main),
                ruleVersion = ruleVersion.code,
                policy = requireNotNull(main.policy),
                prizeTiers = main.prizeTiers,
                evidence = main.evidence.toSourceEvidence(fetchedAtMillis),
                supportingEvidence = listOfNotNull(supporting?.evidence?.toSourceEvidence(fetchedAtMillis)),
            ),
        )
    }

    /**
     * 查询从起始期号开始、官网已开奖的连续期号。
     *
     * 先查本年度内的目标区间；本年度已开奖期号不足时再查新年度首期起的区间，新年度已有开奖说明本年度已经结束，
     * 由此按官网实际期号跨越年度换期。任一区间请求或校验失败时返回 `null`，由调用方按本年度序号顺延。
     */
    override suspend fun getDrawnIssues(
        lotteryType: LotteryType,
        firstIssue: Issue,
        maxCount: Int,
    ): List<Issue>? {
        if (!isValidIssueFormat(lotteryType, firstIssue.value) || maxCount !in 1..SUPER_LOTTO_HISTORY_PAGE_SIZE) {
            return null
        }
        val prefixLength = firstIssue.value.length - ISSUE_ORDINAL_LENGTH
        val yearPrefix = firstIssue.value.take(prefixLength)
        val firstOrdinal = firstIssue.value.takeLast(ISSUE_ORDINAL_LENGTH).toInt()
        if (firstOrdinal < 1) return null
        val lastOrdinal = minOf(firstOrdinal + maxCount - 1, MAXIMUM_ISSUE_ORDINAL)
        val currentYear = loadConsecutiveIssues(lotteryType, yearPrefix, firstOrdinal, lastOrdinal) ?: return null
        // 起始期尚未开奖时后续期次也不可能开奖；本年度已取满时无需再查新年度。
        if (currentYear.isEmpty() || currentYear.size == maxCount) return currentYear
        val nextYearPrefix = (yearPrefix.toInt() + 1).toString().padStart(prefixLength, '0')
        if (nextYearPrefix.length != prefixLength) return currentYear
        val nextYear =
            loadConsecutiveIssues(lotteryType, nextYearPrefix, 1, maxCount - currentYear.size) ?: return null
        return currentYear + nextYear
    }

    /** 查询同一年度连续序号区间内官网已开奖的期号，结果必须是从区间首期开始的连续前缀。 */
    private suspend fun loadConsecutiveIssues(
        lotteryType: LotteryType,
        yearPrefix: String,
        firstOrdinal: Int,
        lastOrdinal: Int,
    ): List<Issue>? {
        val expected =
            (firstOrdinal..lastOrdinal).map { ordinal ->
                Issue(yearPrefix + ordinal.toString().padStart(ISSUE_ORDINAL_LENGTH, '0'))
            }
        val startIssue = expected.first().value
        val endIssue = expected.last().value
        val fetched =
            when (lotteryType) {
                LotteryType.SUPER_LOTTO -> {
                    fetch(HistoricalDrawEndpoints.superLottoRange(startIssue, endIssue, expected.size), "期次列表")
                }

                LotteryType.DOUBLE_COLOR_BALL -> {
                    fetch(
                        HistoricalDrawEndpoints.doubleColorBallRange(startIssue, endIssue, expected.size),
                        "期次列表",
                        referer = CHINA_WELFARE_LOTTERY_REFERER,
                    )
                }
            }
        val rawJson = (fetched as? FetchResult.Success)?.body ?: return null
        val parsed =
            when (lotteryType) {
                LotteryType.SUPER_LOTTO -> {
                    HistoricalDrawSourceAdapter.parseSuperLottoPage(rawJson, expectedPageNo = 1)
                }

                LotteryType.DOUBLE_COLOR_BALL -> {
                    HistoricalDrawSourceAdapter.parseDoubleColorBallPage(rawJson, expectedPageNo = 1)
                }
            }
        val issues =
            (parsed as? HistoricalPageParseResult.Success)
                ?.drawsNewestFirst
                ?.asReversed()
                ?.map { it.issue }
                ?: return null
        // 接口忽略区间参数或期号断档时不采用，避免把不相关期次当作票面期次。
        return issues.takeIf { it == expected.take(it.size) }
    }

    /**
     * 以官网最新已发布期为锚点，向前取得指定数量的规范化历史开奖。
     *
     * 体彩接口每页最多返回 100 条，因此大乐透按需分页；福彩接口可在单页返回 500 条。
     */
    override suspend fun getLatestDraws(
        lotteryType: LotteryType,
        count: Int,
    ): HistoricalDrawQueryResult {
        if (count !in 1..HistoricalDrawRepository.MAXIMUM_DRAW_COUNT) {
            return historicalUnavailable(
                HistoricalDrawFailureReason.SOURCE_UNAVAILABLE,
                "历史开奖查询期数必须在 1 至 ${HistoricalDrawRepository.MAXIMUM_DRAW_COUNT} 之间",
            )
        }
        val drawsNewestFirst =
            when (lotteryType) {
                LotteryType.SUPER_LOTTO -> loadSuperLottoHistory(count)
                LotteryType.DOUBLE_COLOR_BALL -> loadDoubleColorBallHistory(count)
            }
        val loaded =
            when (drawsNewestFirst) {
                is HistoricalDrawLoadResult.Success -> drawsNewestFirst.drawsNewestFirst
                is HistoricalDrawLoadResult.Unavailable -> return drawsNewestFirst.result
            }
        if (loaded.size < count) {
            return historicalUnavailable(
                HistoricalDrawFailureReason.SOURCE_UNAVAILABLE,
                "官网可用历史开奖不足 $count 期",
            )
        }
        val selected = loaded.take(count)
        if (
            selected.map { it.issue.value }.distinct().size != selected.size ||
            !selected.isStrictlyNewestFirst()
        ) {
            return historicalUnavailable(
                HistoricalDrawFailureReason.SOURCE_UNAVAILABLE,
                "官网历史开奖存在重复期号或排序异常",
            )
        }
        val fetchedAtMillis = clock.now().toEpochMilliseconds()
        if (selected.any { !isDrawDateReasonable(it.drawDate, fetchedAtMillis) }) {
            return historicalUnavailable(
                HistoricalDrawFailureReason.SOURCE_UNAVAILABLE,
                "官网历史开奖包含晚于当前北京时间的日期",
            )
        }
        return HistoricalDrawQueryResult.Success(
            draws = selected.asReversed(),
            sourceName = lotteryType.historicalSourceName(),
            fetchedAtEpochMillis = fetchedAtMillis,
        )
    }

    /** 按体彩每页最多 100 条的契约加载大乐透历史开奖。 */
    private suspend fun loadSuperLottoHistory(count: Int): HistoricalDrawLoadResult {
        val collected = mutableListOf<HistoricalDraw>()
        var pageNo = 1
        while (collected.size < count) {
            val url = HistoricalDrawEndpoints.superLotto(pageNo)
            val rawJson =
                when (val fetched = fetch(url, "大乐透历史")) {
                    is FetchResult.Success -> {
                        fetched.body
                    }

                    is FetchResult.NetworkUnavailable -> {
                        return historicalLoadUnavailable(
                            HistoricalDrawFailureReason.NETWORK_UNAVAILABLE,
                            fetched.message,
                        )
                    }

                    is FetchResult.SourceUnavailable -> {
                        return historicalLoadUnavailable(
                            HistoricalDrawFailureReason.SOURCE_UNAVAILABLE,
                            fetched.message,
                        )
                    }
                }
            val page =
                when (val parsed = HistoricalDrawSourceAdapter.parseSuperLottoPage(rawJson, pageNo)) {
                    is HistoricalPageParseResult.Success -> {
                        parsed
                    }

                    is HistoricalPageParseResult.Failure -> {
                        return historicalLoadUnavailable(
                            HistoricalDrawFailureReason.SOURCE_UNAVAILABLE,
                            parsed.message,
                        )
                    }
                }
            collected += page.drawsNewestFirst
            if (collected.size >= page.total || page.drawsNewestFirst.size < page.pageSize) break
            pageNo += 1
        }
        return HistoricalDrawLoadResult.Success(collected)
    }

    /** 使用福彩单页能力加载双色球历史开奖。 */
    private suspend fun loadDoubleColorBallHistory(count: Int): HistoricalDrawLoadResult {
        val url = HistoricalDrawEndpoints.doubleColorBall(pageSize = count)
        val rawJson =
            when (val fetched = fetch(url, "双色球历史", referer = CHINA_WELFARE_LOTTERY_REFERER)) {
                is FetchResult.Success -> {
                    fetched.body
                }

                is FetchResult.NetworkUnavailable -> {
                    return historicalLoadUnavailable(
                        HistoricalDrawFailureReason.NETWORK_UNAVAILABLE,
                        fetched.message,
                    )
                }

                is FetchResult.SourceUnavailable -> {
                    return historicalLoadUnavailable(
                        HistoricalDrawFailureReason.SOURCE_UNAVAILABLE,
                        fetched.message,
                    )
                }
            }
        return when (val parsed = HistoricalDrawSourceAdapter.parseDoubleColorBallPage(rawJson, expectedPageNo = 1)) {
            is HistoricalPageParseResult.Success -> {
                HistoricalDrawLoadResult.Success(parsed.drawsNewestFirst)
            }

            is HistoricalPageParseResult.Failure -> {
                historicalLoadUnavailable(HistoricalDrawFailureReason.SOURCE_UNAVAILABLE, parsed.message)
            }
        }
    }

    /** 使用指定彩票适配器解析主响应。 */
    private fun parseMain(
        lotteryType: LotteryType,
        rawJson: String,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<MainDrawSnapshot> =
        when (lotteryType) {
            LotteryType.SUPER_LOTTO -> SuperLottoSourceAdapter.parseMain(rawJson, targetIssue, sourceUrl)
            LotteryType.DOUBLE_COLOR_BALL -> DoubleColorBallSourceAdapter.parseMain(rawJson, targetIssue, sourceUrl)
        }

    /** 使用指定彩票适配器解析辅助响应。 */
    private fun parseSupporting(
        lotteryType: LotteryType,
        rawJson: String,
        targetIssue: String,
        sourceUrl: String,
    ): SourceParseResult<SupportingDrawSnapshot> =
        when (lotteryType) {
            LotteryType.SUPER_LOTTO -> {
                SuperLottoSourceAdapter.parseSupporting(rawJson, targetIssue, sourceUrl)
            }

            LotteryType.DOUBLE_COLOR_BALL -> {
                DoubleColorBallSourceAdapter.parseSupporting(rawJson, targetIssue, sourceUrl)
            }
        }

    /** 执行一个 JSON GET，并将网络不可用与上游 HTTP 失败分开映射。 */
    private suspend fun fetch(
        url: String,
        sourceLabel: String,
        referer: String? = null,
    ): FetchResult =
        try {
            val response =
                httpClient.get(url) {
                    header(HttpHeaders.Accept, "application/json")
                    header(HttpHeaders.CacheControl, "no-cache, no-store")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    referer?.let { header(HttpHeaders.Referrer, it) }
                }
            if (!response.status.isSuccess()) {
                FetchResult.SourceUnavailable("官网数据源返回 HTTP ${response.status.value}")
            } else {
                FetchResult.Success(response.bodyAsText())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            FetchResult.NetworkUnavailable("当前无法连接官网${sourceLabel}数据源，请检查网络后主动重试")
        } catch (_: Exception) {
            FetchResult.SourceUnavailable("官网${sourceLabel}数据源请求失败或响应结构异常")
        }

    /**
     * 尽力取得同一期的官方辅助数据面。
     *
     * 网络、接口、公告或解析问题只表示本次无法交叉核对并返回 `null`，不影响主数据面结果。
     */
    private suspend fun loadSupporting(
        lotteryType: LotteryType,
        supportingUrl: String,
        main: MainDrawSnapshot,
        targetIssue: String,
    ): SupportingDrawSnapshot? {
        val body = (fetch(supportingUrl, "辅助") as? FetchResult.Success)?.body
        val parsed = body?.let { parseSupporting(lotteryType, it, targetIssue, supportingUrl) }
        if (parsed is SourceParseResult.Success) return parsed.value
        // 大乐透聚合接口只返回最新一期，历史期或聚合接口不可用时改用同期官方 PDF 公告。
        return if (lotteryType == LotteryType.SUPER_LOTTO) loadSuperLottoPdfSupporting(main, targetIssue) else null
    }

    /**
     * 下载受限大小的官方 PDF 公告。
     *
     * 响应最多读取共享解析器上限加一字节，用于发现超限后立即失败，避免无界缓冲。
     *
     * @return PDF 字节；网络、重定向、类型或大小不符合要求时为 `null`。
     */
    private suspend fun fetchPdf(url: String): ByteArray? =
        try {
            val response =
                httpClient.get(url) {
                    header(HttpHeaders.Accept, PDF_CONTENT_TYPE)
                    header(HttpHeaders.CacheControl, "no-cache, no-store")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                }
            val contentType =
                response.headers[HttpHeaders.ContentType]
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (
                response.request.url.toString() != url ||
                !response.status.isSuccess() ||
                contentType != PDF_CONTENT_TYPE ||
                (declaredLength != null && declaredLength > SuperLottoAnnouncementParser.MAXIMUM_PDF_BYTES)
            ) {
                null
            } else {
                response
                    .bodyAsChannel()
                    .readRemaining(SuperLottoAnnouncementParser.MAXIMUM_PDF_BYTES.toLong() + 1L)
                    .readByteArray()
                    .takeIf { it.size <= SuperLottoAnnouncementParser.MAXIMUM_PDF_BYTES }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /** 加载并解析主记录绑定的同一期大乐透官方 PDF；任何失败都只表示本次无法用公告交叉核对。 */
    private suspend fun loadSuperLottoPdfSupporting(
        main: MainDrawSnapshot,
        targetIssue: String,
    ): SupportingDrawSnapshot? {
        val extractor = superLottoPdfTextExtractor ?: return null
        val sourceUrl = main.detailUrl ?: return null
        val pdfBytes = fetchPdf(sourceUrl) ?: return null
        val parsed =
            SuperLottoAnnouncementParser.parse(
                pdfBytes = pdfBytes,
                targetIssue = targetIssue,
                sourceUrl = sourceUrl,
                textExtractor = extractor,
            )
        return (parsed as? SourceParseResult.Success)?.value
    }

    /**
     * 比较主、辅助快照。
     *
     * 期号、日期或号码不同属于官方数据面互相矛盾；奖级注数或双边均已发布的金额不同只让金额待定。
     */
    private fun compareSnapshots(
        main: MainDrawSnapshot,
        supporting: SupportingDrawSnapshot,
    ): SnapshotConsistency {
        if (
            main.issue != supporting.issue ||
            main.drawDate != supporting.drawDate ||
            main.primaryNumbers != supporting.primaryNumbers ||
            main.secondaryNumbers != supporting.secondaryNumbers
        ) {
            return SnapshotConsistency.NUMBER_CONFLICT
        }
        val mainBasicTiers = main.prizeTiers.filter { it.code != PrizeTierCodes.FORTUNE }.associateBy { it.code }
        val supportingTiers = supporting.prizeTiers.associateBy { it.code }
        if (mainBasicTiers.keys != supportingTiers.keys) return SnapshotConsistency.PRIZE_MISMATCH
        val tiersAgree =
            mainBasicTiers.all { (code, mainTier) -> prizeTiersAgree(mainTier, supportingTiers.getValue(code)) }
        return if (tiersAgree) SnapshotConsistency.CONSISTENT else SnapshotConsistency.PRIZE_MISMATCH
    }

    /** 判断一个基础奖级的注数和双边均已发布的金额是否一致；单边金额缺失不视为矛盾。 */
    private fun prizeTiersAgree(
        main: PrizeTier,
        supporting: PrizeTier,
    ): Boolean =
        main.winnerCount == supporting.winnerCount &&
            main.additionalWinnerCount == supporting.additionalWinnerCount &&
            amountsAgree(main.singlePrizeFen, supporting.singlePrizeFen) &&
            amountsAgree(main.additionalPrizeFen, supporting.additionalPrizeFen)

    /** 两个可空金额只有双边都已发布且不同才算不一致。 */
    private fun amountsAgree(
        main: Long?,
        supporting: Long?,
    ): Boolean = main == null || supporting == null || main == supporting

    /** 记录主数据面内容；同一期内容变化时递增修订号，并始终以最新官方内容为准。 */
    private suspend fun recordRevision(
        key: DrawKey,
        main: MainDrawSnapshot,
    ): Int =
        revisionMutex.withLock {
            val hash = sha256Hex(main.evidence.canonicalContent)
            val previous = revisions[key]
            val current =
                when {
                    previous == null -> DrawRevision(mainHash = hash, revision = 1)
                    previous.mainHash == hash -> previous
                    else -> DrawRevision(mainHash = hash, revision = previous.revision + 1)
                }
            revisions[key] = current
            current.revision
        }

    /** 验证直接调用仓库时的期号格式，避免无意义官网请求。 */
    private fun isValidIssueFormat(
        lotteryType: LotteryType,
        issue: String,
    ): Boolean {
        val expectedLength =
            when (lotteryType) {
                LotteryType.SUPER_LOTTO -> SUPER_LOTTO_ISSUE_LENGTH
                LotteryType.DOUBLE_COLOR_BALL -> DOUBLE_COLOR_BALL_ISSUE_LENGTH
            }
        return issue.length == expectedLength && issue.all(Char::isDigit)
    }

    /** 创建明确不可用的封闭查询结果。 */
    private fun unavailable(
        status: DrawStatus,
        message: String,
    ): DrawQueryResult.Unavailable = DrawQueryResult.Unavailable(status, message)

    /** 创建历史开奖查询不可用结果。 */
    private fun historicalUnavailable(
        reason: HistoricalDrawFailureReason,
        message: String,
    ): HistoricalDrawQueryResult.Unavailable = HistoricalDrawQueryResult.Unavailable(reason, message)

    /** 创建历史开奖内部加载不可用结果。 */
    private fun historicalLoadUnavailable(
        reason: HistoricalDrawFailureReason,
        message: String,
    ): HistoricalDrawLoadResult.Unavailable =
        HistoricalDrawLoadResult.Unavailable(historicalUnavailable(reason, message))

    /** 将规范化证据草稿补充本次获取时间和 SHA-256。 */
    private fun EvidenceDraft.toSourceEvidence(fetchedAtMillis: Long): SourceEvidence =
        SourceEvidence(
            sourceName = sourceName,
            sourceUrl = sourceUrl,
            fetchedAtEpochMillis = fetchedAtMillis,
            contentSha256 = sha256Hex(canonicalContent),
        )

    /** 精确期号的内存观察键。 */
    private data class DrawKey(
        /** 彩票玩法。 */
        val lotteryType: LotteryType,
        /** 目标期号。 */
        val issue: String,
    )

    /** 当前进程内某一期最近一次主数据面内容与修订号。 */
    private data class DrawRevision(
        /** 主数据面规范化哈希。 */
        val mainHash: String,
        /** 同一期内容修订号。 */
        val revision: Int,
    )

    /** 主、辅助快照的字段一致性。 */
    private enum class SnapshotConsistency {
        /** 号码与已发布奖级字段一致。 */
        CONSISTENT,

        /** 号码一致，但奖级注数或双边已发布金额不一致，金额需等待官方数据一致。 */
        PRIZE_MISMATCH,

        /** 期号、日期或开奖号码不一致。 */
        NUMBER_CONFLICT,
    }

    /** 单次 HTTP 获取结果。 */
    private sealed interface FetchResult {
        /** HTTP 和响应正文均已取得。 */
        data class Success(
            /** 只在当前调用栈中使用的响应文本。 */
            val body: String,
        ) : FetchResult

        /** 当前设备网络不可用或连接超时。 */
        data class NetworkUnavailable(
            /** 面向用户的恢复说明。 */
            val message: String,
        ) : FetchResult

        /** 上游 HTTP 状态或未知异常。 */
        data class SourceUnavailable(
            /** 面向用户的恢复说明。 */
            val message: String,
        ) : FetchResult
    }

    /** 历史开奖多页加载结果。 */
    private sealed interface HistoricalDrawLoadResult {
        /**
         * 已加载并完成单页校验的开奖记录。
         *
         * @property drawsNewestFirst 所有页面按最新期在前拼接的记录。
         */
        data class Success(
            val drawsNewestFirst: List<HistoricalDraw>,
        ) : HistoricalDrawLoadResult

        /**
         * 加载过程中发生网络或来源错误。
         *
         * @property result 对外的封闭不可用结果。
         */
        data class Unavailable(
            val result: HistoricalDrawQueryResult.Unavailable,
        ) : HistoricalDrawLoadResult
    }

    /** 官网端点集合。 */
    private data class DrawEndpoints(
        /** 精确单期主查询地址。 */
        val mainUrl: String,
        /** 官方辅助数据面地址。 */
        val supportingUrl: String,
    ) {
        /** 按彩种构建结构化查询参数。 */
        companion object {
            /** 返回指定彩种和期号的两个官网地址。 */
            fun forIssue(
                lotteryType: LotteryType,
                issue: String,
            ): DrawEndpoints =
                when (lotteryType) {
                    LotteryType.SUPER_LOTTO -> {
                        DrawEndpoints(
                            mainUrl =
                                buildUrl(SUPER_LOTTO_MAIN_URL) {
                                    append("gameNo", "85")
                                    append("provinceId", "0")
                                    append("pageSize", "1")
                                    append("pageNo", "1")
                                    append("isVerify", "1")
                                    append("startTerm", issue)
                                    append("endTerm", issue)
                                },
                            supportingUrl =
                                buildUrl(SUPER_LOTTO_SUPPORTING_URL) {
                                    append("param", "85,0")
                                    append("isVerify", "1")
                                },
                        )
                    }

                    LotteryType.DOUBLE_COLOR_BALL -> {
                        DrawEndpoints(
                            mainUrl =
                                buildUrl(DOUBLE_COLOR_BALL_MAIN_URL) {
                                    append("name", "ssq")
                                    append("issueStart", issue)
                                    append("issueEnd", issue)
                                    append("pageNo", "1")
                                    append("pageSize", "1")
                                    append("systemType", "PC")
                                },
                            supportingUrl =
                                buildUrl(DOUBLE_COLOR_BALL_SUPPORTING_URL) {
                                    append("name", "ssq")
                                    append("code", issue)
                                },
                        )
                    }
                }

            /** 使用 Ktor URL 构建器追加查询参数。 */
            private fun buildUrl(
                baseUrl: String,
                configure: io.ktor.http.ParametersBuilder.() -> Unit,
            ): String = URLBuilder(baseUrl).apply { parameters.apply(configure) }.buildString()
        }
    }

    /** 历史开奖分页与期号区间端点。 */
    private object HistoricalDrawEndpoints {
        /** 构建中国体彩网大乐透历史开奖地址。 */
        fun superLotto(pageNo: Int): String =
            buildUrl(SUPER_LOTTO_MAIN_URL) {
                append("gameNo", "85")
                append("provinceId", "0")
                append("pageSize", SUPER_LOTTO_HISTORY_PAGE_SIZE.toString())
                append("pageNo", pageNo.toString())
                append("isVerify", "1")
            }

        /** 构建中国体彩网大乐透闭区间期号列表地址。 */
        fun superLottoRange(
            startIssue: String,
            endIssue: String,
            pageSize: Int,
        ): String =
            buildUrl(SUPER_LOTTO_MAIN_URL) {
                append("gameNo", "85")
                append("provinceId", "0")
                append("pageSize", pageSize.toString())
                append("pageNo", "1")
                append("isVerify", "1")
                append("startTerm", startIssue)
                append("endTerm", endIssue)
            }

        /** 构建中国福彩网双色球闭区间期号列表地址。 */
        fun doubleColorBallRange(
            startIssue: String,
            endIssue: String,
            pageSize: Int,
        ): String =
            buildUrl(DOUBLE_COLOR_BALL_MAIN_URL) {
                append("name", "ssq")
                append("issueStart", startIssue)
                append("issueEnd", endIssue)
                append("pageNo", "1")
                append("pageSize", pageSize.toString())
                append("systemType", "PC")
            }

        /** 构建中国福彩网双色球历史开奖地址。 */
        fun doubleColorBall(pageSize: Int): String =
            buildUrl(DOUBLE_COLOR_BALL_MAIN_URL) {
                append("name", "ssq")
                append("pageNo", "1")
                append("pageSize", pageSize.toString())
                append("systemType", "PC")
            }

        /** 使用 Ktor URL 构建器追加查询参数。 */
        private fun buildUrl(
            baseUrl: String,
            configure: io.ktor.http.ParametersBuilder.() -> Unit,
        ): String = URLBuilder(baseUrl).apply { parameters.apply(configure) }.buildString()
    }

    /** 官网地址和格式常量。 */
    private companion object {
        /** 大乐透精确历史期主接口。 */
        const val SUPER_LOTTO_MAIN_URL =
            "https://webapi.sporttery.cn/gateway/lottery/getHistoryPageListV1.qry"

        /** 大乐透最新开奖辅助接口。 */
        const val SUPER_LOTTO_SUPPORTING_URL =
            "https://webapi.sporttery.cn/gateway/lottery/getDigitalDrawInfoV1.qry"

        /** 双色球精确期号列表主接口。 */
        const val DOUBLE_COLOR_BALL_MAIN_URL =
            "https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findDrawNotice"

        /** 双色球详情辅助接口。 */
        const val DOUBLE_COLOR_BALL_SUPPORTING_URL =
            "https://www.cwl.gov.cn/cwl_admin/front/cwlkj/search/kjxx/findKjxx/forIssue"

        /** 中国福彩网接口需要的官方站点来源页。 */
        const val CHINA_WELFARE_LOTTERY_REFERER = "https://www.cwl.gov.cn/"

        /** 中国体彩网历史接口实际允许的最大页大小。 */
        const val SUPER_LOTTO_HISTORY_PAGE_SIZE = 100

        /** 大乐透期号长度。 */
        const val SUPER_LOTTO_ISSUE_LENGTH = 5

        /** 双色球期号长度。 */
        const val DOUBLE_COLOR_BALL_ISSUE_LENGTH = 7

        /** 两种彩票的年度期次序号长度。 */
        const val ISSUE_ORDINAL_LENGTH = 3

        /** 三位年度期次的最大序号。 */
        const val MAXIMUM_ISSUE_ORDINAL = 999

        /** 明确标识应用用途且不包含设备信息的请求标识。 */
        const val USER_AGENT = "WinLottery/0.1 (user-initiated draw lookup)"

        /** 官方公告必须使用的响应类型。 */
        const val PDF_CONTENT_TYPE = "application/pdf"
    }
}

/** 判断开奖日期是否不晚于本次查询时的北京时间日期。 */
internal fun isDrawDateReasonable(
    drawDate: String,
    fetchedAtMillis: Long,
): Boolean {
    val date = LocalDate.parse(drawDate)
    val currentChinaDate = Instant.fromEpochMilliseconds(fetchedAtMillis).toLocalDateTime(CHINA_TIME_ZONE).date
    return date <= currentChinaDate
}

/** 北京时区用于开奖发布窗口判断。 */
private val CHINA_TIME_ZONE = TimeZone.of("Asia/Shanghai")

/** 返回走势图使用的官方历史开奖来源名称。 */
private fun LotteryType.historicalSourceName(): String =
    when (this) {
        LotteryType.SUPER_LOTTO -> "中国体彩网历史开奖"
        LotteryType.DOUBLE_COLOR_BALL -> "中国福彩网开奖公告"
    }
