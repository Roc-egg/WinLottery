package roc.win.lottery.app

import kotlinx.coroutines.CancellationException
import roc.win.lottery.data.AiAnalysisProvider
import roc.win.lottery.data.AiProviderConfiguration
import roc.win.lottery.data.AiProviderConfigurationResult
import roc.win.lottery.data.AiProviderConfigurationValidator
import roc.win.lottery.data.AiProviderPreviewResult
import roc.win.lottery.data.AiProviderRequestPreview
import roc.win.lottery.data.AiProviderResult
import roc.win.lottery.domain.AiAnalysisProtocol
import roc.win.lottery.domain.AiAnalysisRequest
import roc.win.lottery.domain.AiAnalysisResult
import roc.win.lottery.domain.AiAnalysisTemplate
import roc.win.lottery.domain.AiHistorySnapshotBuildResult
import roc.win.lottery.domain.AiHistorySnapshotBuilder
import roc.win.lottery.domain.HistoricalDraw
import roc.win.lottery.domain.LotteryType
import roc.win.lottery.domain.TrendSampleSize
import kotlin.time.Clock

/**
 * AI 分析页可编辑且不包含密钥的会话配置。
 *
 * @property providerName 用户填写的服务商展示名称。
 * @property endpointUrl 用户填写的完整 Responses 风格 HTTPS 地址。
 * @property model 用户填写的模型标识。
 * @property lotteryType 当前分析彩种。
 * @property sampleSize 当前真实历史开奖样本范围。
 * @property template 当前版本化分析模板。
 * @property candidateCount 本次要求返回的候选注数。
 */
data class AiAnalysisSettings(
    val providerName: String = "",
    val endpointUrl: String = "",
    val model: String = "",
    val lotteryType: LotteryType = LotteryType.SUPER_LOTTO,
    val sampleSize: TrendSampleSize = TrendSampleSize.LAST_50,
    val template: AiAnalysisTemplate = AiAnalysisTemplate.COMPREHENSIVE,
    val candidateCount: Int = DEFAULT_AI_CANDIDATE_COUNT,
) {
    init {
        require(candidateCount in AiAnalysisProtocol.MIN_CANDIDATE_COUNT..AiAnalysisProtocol.MAX_CANDIDATE_COUNT) {
            "AI 候选注数必须位于 ${AiAnalysisProtocol.MIN_CANDIDATE_COUNT}..${AiAnalysisProtocol.MAX_CANDIDATE_COUNT}"
        }
    }
}

/** AI 页当前彩种的会话级官方历史开奖状态。 */
sealed interface AiHistoryAvailability {
    /** 正在等待用户操作触发的官方历史开奖加载。 */
    data object Loading : AiHistoryAvailability

    /**
     * 当前彩种已有可用于快照构建的会话数据。
     *
     * @property sourceName 官方历史开奖来源展示名称。
     * @property availableDrawCount 当前会话可用的规范化开奖总数。
     * @property fetchedAtEpochMillis 本次历史开奖加载完成时间。
     */
    data class Ready(
        val sourceName: String,
        val availableDrawCount: Int,
        val fetchedAtEpochMillis: Long,
    ) : AiHistoryAvailability

    /**
     * 当前彩种无法形成可用历史样本。
     *
     * @property message 不包含官网原始响应的恢复说明。
     */
    data class Failed(
        val message: String,
    ) : AiHistoryAvailability
}

/** AI 单次分析操作状态。 */
sealed interface AiAnalysisOperation {
    /** 当前没有等待确认或正在执行的模型请求。 */
    data object Idle : AiAnalysisOperation

    /**
     * 精确发送预览已经形成，等待用户单独确认。
     *
     * @property preview 不含密钥和完整请求体的实际发送摘要。
     */
    data class AwaitingConfirmation(
        val preview: AiProviderRequestPreview,
    ) : AiAnalysisOperation

    /**
     * 用户已经消费本次确认，单次请求正在执行。
     *
     * @property preview 与实际请求绑定的已确认摘要。
     */
    data class Requesting(
        val preview: AiProviderRequestPreview,
    ) : AiAnalysisOperation
}

/**
 * 当前进程会话内最近一次通过本地校验的 AI 分析结果。
 *
 * @property preview 生成本结果的服务商、模型和历史快照摘要。
 * @property sourceName 本次历史快照所使用的官方来源名称。
 * @property generatedAtEpochMillis 模型结果通过本地校验的时间。
 * @property analysis 已通过严格协议与彩票规则复核的分析内容。
 */
data class AiAnalysisSessionResult(
    val preview: AiProviderRequestPreview,
    val sourceName: String,
    val generatedAtEpochMillis: Long,
    val analysis: AiAnalysisResult,
)

/**
 * AI 分析共享界面状态，不包含会话密钥、完整请求体或完整响应。
 *
 * @property settings 当前非敏感配置。
 * @property history 当前彩种的官方历史开奖可用性。
 * @property operation 当前精确预览或单次请求状态。
 * @property lastResult 当前进程内最近一次合法结果。
 * @property errorMessage 最近一次本地阻断或单次请求失败说明。
 */
data class AiAnalysisWorkspaceState(
    val settings: AiAnalysisSettings = AiAnalysisSettings(),
    val history: AiHistoryAvailability = AiHistoryAvailability.Loading,
    val operation: AiAnalysisOperation = AiAnalysisOperation.Idle,
    val lastResult: AiAnalysisSessionResult? = null,
    val errorMessage: String? = null,
)

/**
 * 驱动 AI 配置、快照、精确预览、单次确认和迟到结果隔离的共享工作流。
 *
 * 密钥只存在于私有待确认请求中，预览被取消、请求结束或配置变化时立即解除引用。
 *
 * @property provider 可配置且不会自动重试的 AI 数据提供方。
 * @property configurationValidator 用户自备服务配置安全校验器。
 * @property snapshotBuilder 复用 V1.3 历史完整性规则的快照构建器。
 * @property clock 为结果提供可测试的当前会话时间。
 * @property onStateChanged 每次状态变化后同步应用顶层页面的回调。
 */
internal class AiAnalysisWorkflow(
    private val provider: AiAnalysisProvider,
    private val configurationValidator: AiProviderConfigurationValidator = AiProviderConfigurationValidator(),
    private val snapshotBuilder: AiHistorySnapshotBuilder = AiHistorySnapshotBuilder(),
    private val clock: Clock = Clock.System,
    private val onStateChanged: (AiAnalysisWorkspaceState) -> Unit = {},
) {
    /** 当前不包含密钥的共享界面状态。 */
    var state: AiAnalysisWorkspaceState = AiAnalysisWorkspaceState()
        private set

    /** 当前彩种在进程会话中的规范化历史开奖副本。 */
    private var historicalDraws: List<HistoricalDraw>? = null

    /** 等待单次确认且私有持有密钥的精确请求。 */
    private var pendingRequest: PendingAiRequest? = null

    /** 用于丢弃取消后迟到模型响应的单调代次。 */
    private var requestGeneration: Long = 0L

    /** 更新非敏感配置；任何变化都会使旧预览失效。 */
    fun updateSettings(settings: AiAnalysisSettings) {
        if (state.operation is AiAnalysisOperation.Requesting || settings == state.settings) return
        val lotteryChanged = settings.lotteryType != state.settings.lotteryType
        clearPendingRequest()
        if (lotteryChanged) {
            historicalDraws = null
        }
        publish(
            state.copy(
                settings = settings,
                history = if (lotteryChanged) AiHistoryAvailability.Loading else state.history,
                operation = AiAnalysisOperation.Idle,
                errorMessage = null,
            ),
        )
    }

    /** 标记当前彩种正在加载历史开奖，并使旧预览或请求代次失效。 */
    fun showHistoryLoading() {
        invalidateTransientRequest()
        historicalDraws = null
        publish(
            state.copy(
                history = AiHistoryAvailability.Loading,
                operation = AiAnalysisOperation.Idle,
                errorMessage = null,
            ),
        )
    }

    /**
     * 保存当前彩种的会话级规范化历史开奖，不把它放入可打印 UI 状态。
     *
     * @param draws 已由官网来源层规范化的当前彩种开奖。
     * @param sourceName 官方来源展示名称。
     * @param fetchedAtEpochMillis 本次用户操作完成加载的时间。
     */
    fun showHistoryReady(
        draws: List<HistoricalDraw>,
        sourceName: String,
        fetchedAtEpochMillis: Long,
    ) {
        invalidateTransientRequest()
        historicalDraws =
            draws.map { draw ->
                draw.copy(
                    primaryNumbers = draw.primaryNumbers.toList(),
                    secondaryNumbers = draw.secondaryNumbers.toList(),
                )
            }
        publish(
            state.copy(
                history =
                    AiHistoryAvailability.Ready(
                        sourceName = sourceName.ifBlank { "官方历史开奖" },
                        availableDrawCount = draws.size,
                        fetchedAtEpochMillis = fetchedAtEpochMillis,
                    ),
                operation = AiAnalysisOperation.Idle,
                errorMessage = null,
            ),
        )
    }

    /**
     * 发布当前彩种历史开奖失败，并使旧预览或请求代次失效。
     *
     * @param message 不包含官网原始响应的恢复说明。
     */
    fun showHistoryFailure(message: String) {
        invalidateTransientRequest()
        historicalDraws = null
        publish(
            state.copy(
                history = AiHistoryAvailability.Failed(message),
                operation = AiAnalysisOperation.Idle,
                errorMessage = null,
            ),
        )
    }

    /**
     * 使用当前非敏感配置、会话密钥和历史缓存构建精确发送预览。
     *
     * @param apiKey 只进入私有待确认配置的当前会话密钥。
     * @return 是否已经形成可供用户确认的预览。
     */
    fun preparePreview(apiKey: String): Boolean {
        if (state.operation is AiAnalysisOperation.Requesting) return false
        clearPendingRequest()
        val draws = historicalDraws ?: return failPreparation("当前没有可用于 AI 分析的完整历史开奖")
        val configuration =
            when (
                val result =
                    configurationValidator.validate(
                        displayName = state.settings.providerName,
                        endpointUrl = state.settings.endpointUrl,
                        model = state.settings.model,
                        apiKey = apiKey,
                    )
            ) {
                is AiProviderConfigurationResult.Success -> result.configuration
                is AiProviderConfigurationResult.Invalid -> return failPreparation(result.message)
            }
        val snapshot =
            when (
                val result =
                    snapshotBuilder.build(
                        lotteryType = state.settings.lotteryType,
                        sampleSize = state.settings.sampleSize,
                        draws = draws,
                    )
            ) {
                is AiHistorySnapshotBuildResult.Success -> result.snapshot
                is AiHistorySnapshotBuildResult.Invalid -> return failPreparation(result.message)
            }
        val request =
            AiAnalysisRequest(
                snapshot = snapshot,
                template = state.settings.template,
                candidateCount = state.settings.candidateCount,
            )
        val preview =
            when (val result = provider.preview(configuration, request)) {
                is AiProviderPreviewResult.Success -> result.preview
                is AiProviderPreviewResult.Invalid -> return failPreparation(result.message)
            }
        val history =
            state.history as? AiHistoryAvailability.Ready
                ?: return failPreparation("当前历史开奖状态已变化，请重新加载")
        pendingRequest =
            PendingAiRequest(
                configuration = configuration,
                request = request,
                preview = preview,
                sourceName = history.sourceName,
            )
        publish(
            state.copy(
                operation = AiAnalysisOperation.AwaitingConfirmation(preview),
                errorMessage = null,
            ),
        )
        return true
    }

    /** 取消尚未消费的发送预览并立即解除会话密钥引用。 */
    fun dismissPreview() {
        if (state.operation !is AiAnalysisOperation.AwaitingConfirmation) return
        clearPendingRequest()
        publish(state.copy(operation = AiAnalysisOperation.Idle, errorMessage = null))
    }

    /**
     * 消费一次匹配指纹的确认并执行至多一个 Provider 请求。
     *
     * @param requestFingerprint 用户确认页实际展示的请求指纹。
     * @return 本次确认是否完成并仍属于当前有效请求代次。
     */
    suspend fun confirmPreview(requestFingerprint: String): Boolean {
        val operation = state.operation as? AiAnalysisOperation.AwaitingConfirmation ?: return false
        val pending = pendingRequest
        if (pending == null || operation.preview.requestFingerprint != requestFingerprint ||
            pending.preview != operation.preview
        ) {
            return failPreparation("发送信息已经变化，请重新预览后确认")
        }
        pendingRequest = null
        val generation = ++requestGeneration
        publish(state.copy(operation = AiAnalysisOperation.Requesting(pending.preview), errorMessage = null))
        val result =
            try {
                provider.analyze(pending.configuration, pending.request)
            } catch (cancelled: CancellationException) {
                if (generation == requestGeneration) {
                    publish(
                        state.copy(
                            operation = AiAnalysisOperation.Idle,
                            errorMessage = AI_REQUEST_CANCELLED_MESSAGE,
                        ),
                    )
                }
                throw cancelled
            }
        if (generation != requestGeneration) return false
        when (result) {
            is AiProviderResult.Success -> {
                publish(
                    state.copy(
                        operation = AiAnalysisOperation.Idle,
                        lastResult =
                            AiAnalysisSessionResult(
                                preview = pending.preview,
                                sourceName = pending.sourceName,
                                generatedAtEpochMillis = clock.now().toEpochMilliseconds().coerceAtLeast(0L),
                                analysis = result.analysis,
                            ),
                        errorMessage = null,
                    ),
                )
            }

            is AiProviderResult.Failure -> {
                publish(
                    state.copy(
                        operation = AiAnalysisOperation.Idle,
                        errorMessage = result.message,
                    ),
                )
            }
        }
        return true
    }

    /** 逻辑取消正在执行的单次请求，使任何迟到响应都不能进入界面。 */
    fun cancelActiveRequest() {
        val operation = state.operation
        invalidateTransientRequest()
        when (operation) {
            AiAnalysisOperation.Idle -> {}

            is AiAnalysisOperation.AwaitingConfirmation -> {
                publish(state.copy(operation = AiAnalysisOperation.Idle, errorMessage = null))
            }

            is AiAnalysisOperation.Requesting -> {
                publish(
                    state.copy(
                        operation = AiAnalysisOperation.Idle,
                        errorMessage = AI_REQUEST_CANCELLED_MESSAGE,
                    ),
                )
            }
        }
    }

    /** 离开 AI 工作区时清除预览和请求授权，不清除当前会话合法结果。 */
    fun clearTransientRequest() {
        val hadTransientOperation = state.operation !is AiAnalysisOperation.Idle
        invalidateTransientRequest()
        if (hadTransientOperation) {
            publish(state.copy(operation = AiAnalysisOperation.Idle, errorMessage = null))
        }
    }

    /** 清除私有待确认配置与其密钥引用。 */
    private fun clearPendingRequest() {
        pendingRequest = null
    }

    /** 使待确认或正在执行的请求失效，并解除私有密钥引用。 */
    private fun invalidateTransientRequest() {
        requestGeneration += 1L
        clearPendingRequest()
    }

    /** 发布预览前的明确本地阻断。 */
    private fun failPreparation(message: String): Boolean {
        clearPendingRequest()
        publish(
            state.copy(
                operation = AiAnalysisOperation.Idle,
                errorMessage = message,
            ),
        )
        return false
    }

    /** 保存状态并同步给应用顶层控制器。 */
    private fun publish(updated: AiAnalysisWorkspaceState) {
        state = updated
        onStateChanged(updated)
    }

    /**
     * 等待一次确认的私有请求，唯一敏感成员是配置内部的会话密钥。
     *
     * @property configuration 已校验且调试文本固定脱敏的 Provider 配置。
     * @property request 已绑定规范化历史快照的领域请求。
     * @property preview 与实际请求一致的无密钥摘要。
     * @property sourceName 本次快照所使用的官方历史来源名称。
     */
    private data class PendingAiRequest(
        val configuration: AiProviderConfiguration,
        val request: AiAnalysisRequest,
        val preview: AiProviderRequestPreview,
        val sourceName: String,
    )
}

/** 默认要求模型返回的候选注数。 */
private const val DEFAULT_AI_CANDIDATE_COUNT = 2

/** 用户取消单次模型请求后的固定反馈。 */
private const val AI_REQUEST_CANCELLED_MESSAGE = "已取消本次 AI 请求，未自动重试"
