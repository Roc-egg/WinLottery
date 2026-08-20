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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import roc.win.lottery.domain.DrawResult
import roc.win.lottery.domain.DrawStatus
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
 * @property clock 为发布窗口和证据时间提供可测试时钟。
 * @property ruleVersionSelector 根据期号选择规则版本。
 * @property superLottoPdfTextExtractor 当前移动平台的官方 PDF 文本层提取能力。
 */
class OfficialDrawRepository(
    private val httpClient: HttpClient = createPlatformHttpClient(),
    private val clock: Clock = Clock.System,
    private val ruleVersionSelector: RuleVersionSelector = RuleVersionSelector(),
    private val superLottoPdfTextExtractor: SuperLottoPdfTextExtractor? = null,
) : DrawRepository {
    /** 当前进程内每个精确期号的规范化观察状态。 */
    private val observations = mutableMapOf<DrawKey, DrawObservation>()

    /** 保护刷新修订和发布窗口候选状态。 */
    private val observationMutex = Mutex()

    /**
     * 按用户确认的彩种和期号查询主、辅助两个官网数据面。
     *
     * 方法不会猜测最新期，也不会在调用结束后自动重试或轮询。
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
        val key = DrawKey(lotteryType, issue.value)

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
                    return if (markMissingAsConflict(key)) {
                        unavailable(DrawStatus.CONFLICT, "已观察到的开奖记录在主动刷新后消失")
                    } else {
                        unavailable(DrawStatus.NOT_PUBLISHED, "官网尚未发布该期开奖结果查询")
                    }
                }

                is SourceParseResult.Publishing -> {
                    return if (markRegressionAsConflict(key)) {
                        unavailable(DrawStatus.CONFLICT, "已观察到的开奖记录在主动刷新后完整性回退")
                    } else {
                        unavailable(DrawStatus.PUBLISHING, parsed.message)
                    }
                }

                is SourceParseResult.SourceUnavailable -> {
                    return unavailable(DrawStatus.SOURCE_UNAVAILABLE, parsed.message)
                }

                is SourceParseResult.Conflict -> {
                    markConflict(key)
                    return unavailable(DrawStatus.CONFLICT, parsed.message)
                }
            }

        if (!main.publicationFieldsComplete) {
            return unavailable(DrawStatus.PUBLISHING, "开奖公告和必要发布字段尚未完整")
        }
        val fetchedAtMillis = clock.now().toEpochMilliseconds()
        if (!isDrawDateReasonable(main.drawDate, fetchedAtMillis)) {
            return unavailable(DrawStatus.SOURCE_UNAVAILABLE, "开奖日期晚于当前北京时间")
        }
        if (observeMain(key, main) == MainObservationDecision.Conflict) {
            return unavailable(DrawStatus.CONFLICT, "同一期规范化开奖内容在主动刷新后发生变化")
        }

        val supportingBody =
            when (val fetched = fetch(endpoints.supportingUrl, "辅助")) {
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
        val selectedSupporting =
            when (
                val parsed =
                    parseSupporting(
                        lotteryType = lotteryType,
                        rawJson = supportingBody,
                        targetIssue = issue.value,
                        sourceUrl = endpoints.supportingUrl,
                    )
            ) {
                is SourceParseResult.Success -> {
                    SelectedSupportingSnapshot(
                        snapshot = parsed.value,
                        isLatestAggregate = lotteryType == LotteryType.SUPER_LOTTO,
                    )
                }

                SourceParseResult.NotPublished -> {
                    if (lotteryType != LotteryType.SUPER_LOTTO) {
                        return unavailable(DrawStatus.PUBLISHING, "双色球详情数据尚未发布")
                    }
                    when (val pdf = loadSuperLottoPdfSupporting(main, issue.value)) {
                        is SuperLottoPdfSupportingResult.Success -> {
                            SelectedSupportingSnapshot(
                                snapshot = pdf.snapshot,
                                isLatestAggregate = false,
                            )
                        }

                        is SuperLottoPdfSupportingResult.Unavailable -> {
                            return unavailable(pdf.status, pdf.message)
                        }
                    }
                }

                is SourceParseResult.Publishing -> {
                    return unavailable(DrawStatus.PUBLISHING, parsed.message)
                }

                is SourceParseResult.SourceUnavailable -> {
                    return unavailable(DrawStatus.SOURCE_UNAVAILABLE, parsed.message)
                }

                is SourceParseResult.Conflict -> {
                    markConflict(key)
                    return unavailable(DrawStatus.CONFLICT, parsed.message)
                }
            }
        val supporting = selectedSupporting.snapshot

        val snapshotConsistency = compareSnapshots(main, supporting)
        if (snapshotConsistency == SnapshotConsistency.CONFLICT) {
            markConflict(key)
            return unavailable(DrawStatus.CONFLICT, "主、辅助官网数据面内容不一致")
        }
        if (snapshotConsistency == SnapshotConsistency.INCOMPLETE) {
            return unavailable(DrawStatus.PUBLISHING, "主、辅助官网数据面的奖级金额尚未同步完整")
        }
        val history = isPastHistoricalCutoff(main.drawDate, fetchedAtMillis)
        var additionalPdfSupporting: SupportingDrawSnapshot? = null
        if (
            !history &&
            lotteryType == LotteryType.SUPER_LOTTO &&
            selectedSupporting.isLatestAggregate
        ) {
            when (val pdf = loadSuperLottoPdfSupporting(main, issue.value)) {
                is SuperLottoPdfSupportingResult.Success -> {
                    when (compareSnapshots(main, pdf.snapshot)) {
                        SnapshotConsistency.CONSISTENT -> {
                            additionalPdfSupporting = pdf.snapshot
                        }

                        SnapshotConsistency.INCOMPLETE -> {}

                        SnapshotConsistency.CONFLICT -> {
                            markConflict(key)
                            return unavailable(DrawStatus.CONFLICT, "最新聚合数据与同期开奖公告内容不一致")
                        }
                    }
                }

                is SuperLottoPdfSupportingResult.Unavailable -> {
                    if (pdf.status == DrawStatus.CONFLICT) {
                        markConflict(key)
                        return unavailable(pdf.status, pdf.message)
                    }
                }
            }
        }
        val stableDecision =
            observeConsistentSupporting(
                key = key,
                canonicalSupportingContent = supporting.evidence.canonicalContent,
                fetchedAtMillis = fetchedAtMillis,
                bypassRefreshDelay = history || additionalPdfSupporting != null,
            )
        val revision =
            when (stableDecision) {
                SupportingObservationDecision.Conflict -> {
                    return unavailable(DrawStatus.CONFLICT, "辅助官网证据在主动刷新后发生变化")
                }

                is SupportingObservationDecision.Publishing -> {
                    return unavailable(
                        DrawStatus.PUBLISHING,
                        "开奖发布窗口内已取得一次候选结果，请至少 60 秒后主动刷新复核",
                    )
                }

                is SupportingObservationDecision.Ready -> {
                    stableDecision.revision
                }
            }
        val status = if (main.payoutFieldsComplete) DrawStatus.FINAL_PAYOUT else DrawStatus.FINAL_NUMBERS
        return DrawQueryResult.Success(
            DrawResult(
                lotteryType = lotteryType,
                issue = issue,
                drawDate = main.drawDate,
                primaryNumbers = main.primaryNumbers,
                secondaryNumbers = main.secondaryNumbers,
                status = status,
                revision = revision,
                ruleVersion = ruleVersion.code,
                policy = requireNotNull(main.policy),
                prizeTiers = main.prizeTiers,
                evidence = main.evidence.toSourceEvidence(fetchedAtMillis),
                supportingEvidence =
                    buildList {
                        add(supporting.evidence.toSourceEvidence(fetchedAtMillis))
                        additionalPdfSupporting?.let { add(it.evidence.toSourceEvidence(fetchedAtMillis)) }
                    },
            ),
        )
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
    ): FetchResult =
        try {
            val response =
                httpClient.get(url) {
                    header(HttpHeaders.Accept, "application/json")
                    header(HttpHeaders.CacheControl, "no-cache, no-store")
                    header(HttpHeaders.UserAgent, USER_AGENT)
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
     * 下载受限大小的官方 PDF 公告。
     *
     * 响应最多读取共享解析器上限加一字节，用于发现超限后立即失败，避免无界缓冲。
     */
    private suspend fun fetchPdf(url: String): BinaryFetchResult =
        try {
            val response =
                httpClient.get(url) {
                    header(HttpHeaders.Accept, PDF_CONTENT_TYPE)
                    header(HttpHeaders.CacheControl, "no-cache, no-store")
                    header(HttpHeaders.UserAgent, USER_AGENT)
                }
            if (response.request.url.toString() != url) {
                BinaryFetchResult.SourceUnavailable("官方 PDF 公告发生非预期重定向")
            } else if (!response.status.isSuccess()) {
                BinaryFetchResult.SourceUnavailable("官方 PDF 公告返回 HTTP ${response.status.value}")
            } else {
                val contentType =
                    response.headers[HttpHeaders.ContentType]
                        ?.substringBefore(';')
                        ?.trim()
                        ?.lowercase()
                val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                when {
                    contentType != PDF_CONTENT_TYPE -> {
                        BinaryFetchResult.SourceUnavailable("官方公告响应类型不是 PDF")
                    }

                    declaredLength != null && declaredLength > SuperLottoAnnouncementParser.MAXIMUM_PDF_BYTES -> {
                        BinaryFetchResult.SourceUnavailable("官方 PDF 公告大小超出安全上限")
                    }

                    else -> {
                        val bytes =
                            response
                                .bodyAsChannel()
                                .readRemaining(SuperLottoAnnouncementParser.MAXIMUM_PDF_BYTES.toLong() + 1L)
                                .readByteArray()
                        if (bytes.size > SuperLottoAnnouncementParser.MAXIMUM_PDF_BYTES) {
                            BinaryFetchResult.SourceUnavailable("官方 PDF 公告大小超出安全上限")
                        } else {
                            BinaryFetchResult.Success(bytes)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            BinaryFetchResult.NetworkUnavailable("当前无法下载官方 PDF 公告，请检查网络后主动重试")
        } catch (_: Exception) {
            BinaryFetchResult.SourceUnavailable("官方 PDF 公告请求失败或响应结构异常")
        }

    /** 加载并解析主记录绑定的同一期大乐透官方 PDF。 */
    private suspend fun loadSuperLottoPdfSupporting(
        main: MainDrawSnapshot,
        targetIssue: String,
    ): SuperLottoPdfSupportingResult {
        val extractor =
            superLottoPdfTextExtractor
                ?: return SuperLottoPdfSupportingResult.Unavailable(
                    DrawStatus.PUBLISHING,
                    "该期已不是聚合接口的最新期，当前平台尚未接入独立公告解析",
                )
        val sourceUrl =
            main.detailUrl
                ?: return SuperLottoPdfSupportingResult.Unavailable(
                    DrawStatus.PUBLISHING,
                    "该期独立开奖公告地址尚未发布",
                )
        val pdfBytes =
            when (val fetched = fetchPdf(sourceUrl)) {
                is BinaryFetchResult.Success -> {
                    fetched.body
                }

                is BinaryFetchResult.NetworkUnavailable -> {
                    return SuperLottoPdfSupportingResult.Unavailable(
                        DrawStatus.NETWORK_UNAVAILABLE,
                        fetched.message,
                    )
                }

                is BinaryFetchResult.SourceUnavailable -> {
                    return SuperLottoPdfSupportingResult.Unavailable(
                        DrawStatus.SOURCE_UNAVAILABLE,
                        fetched.message,
                    )
                }
            }
        return when (
            val parsed =
                SuperLottoAnnouncementParser.parse(
                    pdfBytes = pdfBytes,
                    targetIssue = targetIssue,
                    sourceUrl = sourceUrl,
                    textExtractor = extractor,
                )
        ) {
            is SourceParseResult.Success -> {
                SuperLottoPdfSupportingResult.Success(parsed.value)
            }

            SourceParseResult.NotPublished -> {
                SuperLottoPdfSupportingResult.Unavailable(DrawStatus.PUBLISHING, "独立开奖公告尚未发布")
            }

            is SourceParseResult.Publishing -> {
                SuperLottoPdfSupportingResult.Unavailable(DrawStatus.PUBLISHING, parsed.message)
            }

            is SourceParseResult.SourceUnavailable -> {
                SuperLottoPdfSupportingResult.Unavailable(DrawStatus.SOURCE_UNAVAILABLE, parsed.message)
            }

            is SourceParseResult.Conflict -> {
                SuperLottoPdfSupportingResult.Unavailable(DrawStatus.CONFLICT, parsed.message)
            }
        }
    }

    /** 比较主、辅助快照的期号、日期、号码、详情链接和基础奖级。 */
    private fun compareSnapshots(
        main: MainDrawSnapshot,
        supporting: SupportingDrawSnapshot,
    ): SnapshotConsistency {
        if (
            main.issue != supporting.issue ||
            main.drawDate != supporting.drawDate ||
            main.primaryNumbers != supporting.primaryNumbers ||
            main.secondaryNumbers != supporting.secondaryNumbers ||
            main.detailUrl != supporting.detailUrl
        ) {
            return SnapshotConsistency.CONFLICT
        }
        val mainBasicTiers = main.prizeTiers.filter { it.code != PrizeTierCodes.FORTUNE }.associateBy { it.code }
        val supportingTiers = supporting.prizeTiers.associateBy { it.code }
        if (mainBasicTiers.keys != supportingTiers.keys) return SnapshotConsistency.CONFLICT
        var result = SnapshotConsistency.CONSISTENT
        for ((code, mainTier) in mainBasicTiers) {
            when (comparePrizeTiers(mainTier, requireNotNull(supportingTiers[code]))) {
                SnapshotConsistency.CONFLICT -> return SnapshotConsistency.CONFLICT
                SnapshotConsistency.INCOMPLETE -> result = SnapshotConsistency.INCOMPLETE
                SnapshotConsistency.CONSISTENT -> Unit
            }
        }
        return result
    }

    /** 比较一个基础奖级，并将单边金额缺失与双边金额冲突分开。 */
    private fun comparePrizeTiers(
        main: PrizeTier,
        supporting: PrizeTier,
    ): SnapshotConsistency {
        if (
            main.winnerCount != supporting.winnerCount ||
            main.additionalWinnerCount != supporting.additionalWinnerCount
        ) {
            return SnapshotConsistency.CONFLICT
        }
        val baseResult = compareOptionalAmounts(main.singlePrizeFen, supporting.singlePrizeFen)
        val additionalResult = compareOptionalAmounts(main.additionalPrizeFen, supporting.additionalPrizeFen)
        return when {
            baseResult == SnapshotConsistency.CONFLICT || additionalResult == SnapshotConsistency.CONFLICT -> {
                SnapshotConsistency.CONFLICT
            }

            baseResult == SnapshotConsistency.INCOMPLETE || additionalResult == SnapshotConsistency.INCOMPLETE -> {
                SnapshotConsistency.INCOMPLETE
            }

            else -> {
                SnapshotConsistency.CONSISTENT
            }
        }
    }

    /** 比较两个可空金额：单边为空表示尚未同步，双边非空且不同才是冲突。 */
    private fun compareOptionalAmounts(
        main: Long?,
        supporting: Long?,
    ): SnapshotConsistency =
        when {
            main == supporting -> SnapshotConsistency.CONSISTENT
            main == null || supporting == null -> SnapshotConsistency.INCOMPLETE
            else -> SnapshotConsistency.CONFLICT
        }

    /** 记录主数据面；已观察内容变化时生成新修订并进入冲突。 */
    private suspend fun observeMain(
        key: DrawKey,
        main: MainDrawSnapshot,
    ): MainObservationDecision =
        observationMutex.withLock {
            val hash = sha256Hex(main.evidence.canonicalContent)
            val fingerprint = MainFingerprint.from(main)
            val previous = observations[key]
            if (previous?.conflicted == true) return@withLock MainObservationDecision.Conflict
            if (previous != null && previous.mainHash != hash) {
                if (previous.mainFingerprint.canProgressTo(fingerprint)) {
                    val progressed =
                        previous.copy(
                            mainHash = hash,
                            mainFingerprint = fingerprint,
                            supportingHash = null,
                            revision = previous.revision + 1,
                            firstConsistentAtMillis = null,
                        )
                    observations[key] = progressed
                    return@withLock MainObservationDecision.Accepted(progressed.revision)
                } else {
                    observations[key] =
                        previous.copy(
                            mainHash = hash,
                            mainFingerprint = fingerprint,
                            revision = previous.revision + 1,
                            conflicted = true,
                        )
                    return@withLock MainObservationDecision.Conflict
                }
            }
            val observation = previous ?: DrawObservation(mainHash = hash, mainFingerprint = fingerprint)
            observations[key] = observation
            MainObservationDecision.Accepted(observation.revision)
        }

    /** 记录交叉一致的辅助证据并执行 60 秒主动刷新稳定性判断。 */
    private suspend fun observeConsistentSupporting(
        key: DrawKey,
        canonicalSupportingContent: String,
        fetchedAtMillis: Long,
        bypassRefreshDelay: Boolean,
    ): SupportingObservationDecision =
        observationMutex.withLock {
            val previous = requireNotNull(observations[key])
            if (previous.conflicted) return@withLock SupportingObservationDecision.Conflict
            val hash = sha256Hex(canonicalSupportingContent)
            if (previous.supportingHash != null && previous.supportingHash != hash) {
                observations[key] = previous.copy(revision = previous.revision + 1, conflicted = true)
                return@withLock SupportingObservationDecision.Conflict
            }
            val firstConsistentAt = previous.firstConsistentAtMillis ?: fetchedAtMillis
            val updated =
                previous.copy(
                    supportingHash = hash,
                    firstConsistentAtMillis = firstConsistentAt,
                )
            observations[key] = updated
            if (bypassRefreshDelay || fetchedAtMillis - firstConsistentAt >= MINIMUM_REFRESH_INTERVAL_MILLIS) {
                SupportingObservationDecision.Ready(updated.revision)
            } else {
                SupportingObservationDecision.Publishing(updated.revision)
            }
        }

    /** 主记录从已存在变为明确无记录时标记冲突。 */
    private suspend fun markMissingAsConflict(key: DrawKey): Boolean =
        observationMutex.withLock {
            val previous = observations[key] ?: return@withLock false
            if (!previous.conflicted) {
                observations[key] = previous.copy(revision = previous.revision + 1, conflicted = true)
            }
            true
        }

    /** 主记录从完整变为发布中时标记冲突。 */
    private suspend fun markRegressionAsConflict(key: DrawKey): Boolean = markMissingAsConflict(key)

    /** 将当前期号观察状态标记为冲突。 */
    private suspend fun markConflict(key: DrawKey) {
        observationMutex.withLock {
            val previous = observations[key]
            if (previous == null) {
                observations[key] =
                    DrawObservation(
                        mainHash = "",
                        mainFingerprint = MainFingerprint.EMPTY,
                        conflicted = true,
                    )
            } else if (!previous.conflicted) {
                observations[key] = previous.copy(revision = previous.revision + 1, conflicted = true)
            }
        }
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

    /** 当前进程内的规范化开奖观察状态。 */
    private data class DrawObservation(
        /** 主数据面规范化哈希。 */
        val mainHash: String,
        /** 主数据面用于区分正常奖金补全和内容冲突的规范化字段。 */
        val mainFingerprint: MainFingerprint,
        /** 辅助数据面规范化哈希，尚未一致时为 `null`。 */
        val supportingHash: String? = null,
        /** 同一期内容修订号。 */
        val revision: Int = 1,
        /** 首次取得主、辅助一致候选的 Unix 毫秒值。 */
        val firstConsistentAtMillis: Long? = null,
        /** 是否已检测到不可自动恢复的内容冲突。 */
        val conflicted: Boolean = false,
    )

    /** 用于判断刷新变化类型的主数据面规范化字段。 */
    private data class MainFingerprint(
        /** 彩票玩法。 */
        val lotteryType: LotteryType?,
        /** 目标期号。 */
        val issue: String,
        /** 开奖日期。 */
        val drawDate: String,
        /** 主号码。 */
        val primaryNumbers: List<Int>,
        /** 次号码。 */
        val secondaryNumbers: List<Int>,
        /** 当期特别政策编码。 */
        val policyName: String,
        /** 官方详情地址。 */
        val detailUrl: String?,
        /** 按奖级编码保存的奖金字段。 */
        val tiers: Map<String, TierFingerprint>,
        /** 当前奖级金额是否已经完整。 */
        val payoutFieldsComplete: Boolean,
    ) {
        /** 只允许号码、政策、注数和已有金额不变时从缺失奖金单向补全。 */
        fun canProgressTo(next: MainFingerprint): Boolean {
            if (
                lotteryType != next.lotteryType ||
                issue != next.issue ||
                drawDate != next.drawDate ||
                primaryNumbers != next.primaryNumbers ||
                secondaryNumbers != next.secondaryNumbers ||
                policyName != next.policyName ||
                detailUrl != next.detailUrl ||
                tiers.keys != next.tiers.keys ||
                (payoutFieldsComplete && !next.payoutFieldsComplete)
            ) {
                return false
            }
            var enriched = false
            for ((code, currentTier) in tiers) {
                val nextTier = requireNotNull(next.tiers[code])
                if (
                    currentTier.winnerCount != nextTier.winnerCount ||
                    currentTier.additionalWinnerCount != nextTier.additionalWinnerCount
                ) {
                    return false
                }
                when {
                    currentTier.singlePrizeFen == nextTier.singlePrizeFen -> Unit
                    currentTier.singlePrizeFen == null && nextTier.singlePrizeFen != null -> enriched = true
                    else -> return false
                }
                when {
                    currentTier.additionalPrizeFen == nextTier.additionalPrizeFen -> Unit
                    currentTier.additionalPrizeFen == null && nextTier.additionalPrizeFen != null -> enriched = true
                    else -> return false
                }
            }
            return enriched
        }

        /** 主开奖快照转换。 */
        companion object {
            /** 尚未取得可比较主记录时使用的空指纹。 */
            val EMPTY = MainFingerprint(null, "", "", emptyList(), emptyList(), "", null, emptyMap(), false)

            /** 从严格校验后的主快照提取必要比较字段。 */
            fun from(main: MainDrawSnapshot): MainFingerprint =
                MainFingerprint(
                    lotteryType = main.lotteryType,
                    issue = main.issue,
                    drawDate = main.drawDate,
                    primaryNumbers = main.primaryNumbers,
                    secondaryNumbers = main.secondaryNumbers,
                    policyName = main.policy?.name.orEmpty(),
                    detailUrl = main.detailUrl,
                    tiers = main.prizeTiers.associate { it.code to TierFingerprint.from(it) },
                    payoutFieldsComplete = main.payoutFieldsComplete,
                )
        }
    }

    /** 单个奖级参与刷新比较的规范化字段。 */
    private data class TierFingerprint(
        /** 基本投注单注奖金。 */
        val singlePrizeFen: Long?,
        /** 追加单注奖金。 */
        val additionalPrizeFen: Long?,
        /** 基本投注中奖注数。 */
        val winnerCount: Long?,
        /** 追加投注中奖注数。 */
        val additionalWinnerCount: Long?,
    ) {
        /** 统一奖级转换。 */
        companion object {
            /** 从领域奖级提取比较字段。 */
            fun from(tier: PrizeTier): TierFingerprint =
                TierFingerprint(
                    singlePrizeFen = tier.singlePrizeFen,
                    additionalPrizeFen = tier.additionalPrizeFen,
                    winnerCount = tier.winnerCount,
                    additionalWinnerCount = tier.additionalWinnerCount,
                )
        }
    }

    /** 主数据面观察结果。 */
    private sealed interface MainObservationDecision {
        /** 主内容与此前观察一致。 */
        data class Accepted(
            /** 当前修订号。 */
            val revision: Int,
        ) : MainObservationDecision

        /** 主内容已发生变化或此前已经冲突。 */
        data object Conflict : MainObservationDecision
    }

    /** 辅助证据稳定性观察结果。 */
    private sealed interface SupportingObservationDecision {
        /** 发布窗口内仍需要用户稍后主动刷新。 */
        data class Publishing(
            /** 当前修订号。 */
            val revision: Int,
        ) : SupportingObservationDecision

        /** 已满足历史期或 60 秒稳定性条件。 */
        data class Ready(
            /** 当前修订号。 */
            val revision: Int,
        ) : SupportingObservationDecision

        /** 辅助证据发生变化或此前已经冲突。 */
        data object Conflict : SupportingObservationDecision
    }

    /** 主、辅助快照的字段一致性。 */
    private enum class SnapshotConsistency {
        /** 所有必要字段一致。 */
        CONSISTENT,

        /** 号码等身份字段一致，但至少一个奖级只有单边取得金额。 */
        INCOMPLETE,

        /** 号码、注数或双边已发布金额不一致。 */
        CONFLICT,
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

    /** 单次受限二进制 HTTP 获取结果。 */
    private sealed interface BinaryFetchResult {
        /** HTTP、类型和受限响应正文均已取得。 */
        data class Success(
            /** 只在当前调用栈中使用的 PDF 字节。 */
            val body: ByteArray,
        ) : BinaryFetchResult

        /** 当前设备网络不可用或连接超时。 */
        data class NetworkUnavailable(
            /** 面向用户的恢复说明。 */
            val message: String,
        ) : BinaryFetchResult

        /** 上游 HTTP、响应类型、大小或未知异常。 */
        data class SourceUnavailable(
            /** 面向用户的恢复说明。 */
            val message: String,
        ) : BinaryFetchResult
    }

    /** 大乐透独立 PDF 公告加载结果。 */
    private sealed interface SuperLottoPdfSupportingResult {
        /** 已解析为可与主记录交叉核对的快照。 */
        data class Success(
            /** 官方 PDF 规范化辅助快照。 */
            val snapshot: SupportingDrawSnapshot,
        ) : SuperLottoPdfSupportingResult

        /** 当前无法安全形成独立证据。 */
        data class Unavailable(
            /** 对外的保守开奖状态。 */
            val status: DrawStatus,
            /** 不包含公告原文的恢复说明。 */
            val message: String,
        ) : SuperLottoPdfSupportingResult
    }

    /** 本次主辅助证据选择结果。 */
    private data class SelectedSupportingSnapshot(
        /** 已通过适配器校验的辅助开奖快照。 */
        val snapshot: SupportingDrawSnapshot,
        /** 该快照是否来自大乐透最新开奖聚合接口。 */
        val isLatestAggregate: Boolean,
    )

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

    /** 官网地址、发布窗口和格式常量。 */
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

        /** 用户触发刷新之间的最低稳定性间隔。 */
        const val MINIMUM_REFRESH_INTERVAL_MILLIS = 60_000L

        /** 大乐透期号长度。 */
        const val SUPER_LOTTO_ISSUE_LENGTH = 5

        /** 双色球期号长度。 */
        const val DOUBLE_COLOR_BALL_ISSUE_LENGTH = 7

        /** 明确标识应用用途且不包含设备信息的请求标识。 */
        const val USER_AGENT = "WinLottery/0.1 (user-initiated draw lookup)"

        /** 官方公告必须使用的响应类型。 */
        const val PDF_CONTENT_TYPE = "application/pdf"
    }
}

/**
 * 判断目标期开奖是否已超过次日北京时间 09:00。
 *
 * 超过该时间的历史期可以在同一次用户操作中完成双数据面核对，不要求 60 秒刷新等待。
 */
internal fun isPastHistoricalCutoff(
    drawDate: String,
    fetchedAtMillis: Long,
): Boolean {
    val date = LocalDate.parse(drawDate)
    val cutoff =
        date
            .plus(1, DateTimeUnit.DAY)
            .atTime(CUTOFF_HOUR, CUTOFF_MINUTE)
            .toInstant(CHINA_TIME_ZONE)
    return Instant.fromEpochMilliseconds(fetchedAtMillis) >= cutoff
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

/** 历史期免等待核对的北京时间小时。 */
private const val CUTOFF_HOUR = 9

/** 历史期免等待核对的北京时间分钟。 */
private const val CUTOFF_MINUTE = 0
