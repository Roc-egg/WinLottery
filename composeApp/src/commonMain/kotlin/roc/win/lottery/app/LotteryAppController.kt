package roc.win.lottery.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import roc.win.lottery.data.DrawQueryResult
import roc.win.lottery.domain.BetLine
import roc.win.lottery.domain.ConfirmedTicket
import roc.win.lottery.domain.ConfirmedValue
import roc.win.lottery.domain.Issue
import roc.win.lottery.domain.TicketFieldOrigin
import roc.win.lottery.recognition.ImageAcquisitionResult
import roc.win.lottery.recognition.ImageAcquisitionSource
import roc.win.lottery.recognition.ImageQualityResult
import roc.win.lottery.recognition.ImageRef
import roc.win.lottery.recognition.RecognitionResult
import roc.win.lottery.recognition.TicketParseResult

/** 驱动图片识别 PoC、保守票面解析和开发流程页面的应用状态持有者。 */
class LotteryAppController(
    /** 应用依赖容器。 */
    private val container: AppContainer,
) {
    /** 内部可变 UI 状态。 */
    private val mutableUiState = MutableStateFlow(AppUiState(isDemo = container.isDemo))

    /** 只读 UI 状态。 */
    val uiState: StateFlow<AppUiState> = mutableUiState.asStateFlow()

    /** 当前流程是否已被用户取消，用于丢弃迟到结果。 */
    private var flowGeneration: Long = 0L

    /** 当前流程持有的临时图片，确认或退出后立即清理。 */
    private var activeImageRef: ImageRef? = null

    /**
     * 从拍照或导图开始一次全新的本地分析。
     *
     * @param source 用户选择的图片来源。
     */
    suspend fun startAnalysis(source: ImageAcquisitionSource) {
        clearTemporaryImage()
        val generation = ++flowGeneration
        mutableUiState.value =
            AppUiState(
                screen = AppScreen.Analysis("准备图片", "图片只会进入应用私有临时目录", 0.12f),
                isDemo = container.isDemo,
            )

        when (val acquisition = container.imageAcquirer.acquire(source)) {
            ImageAcquisitionResult.Cancelled -> {
                navigateHome()
            }

            is ImageAcquisitionResult.Failure -> {
                showError("无法读取图片", acquisition.message, generation)
            }

            is ImageAcquisitionResult.Unavailable -> {
                showError("当前能力不可用", acquisition.message, generation)
            }

            is ImageAcquisitionResult.Success -> {
                if (generation == flowGeneration) {
                    activeImageRef = acquisition.imageRef
                    inspectImage(acquisition, generation)
                } else {
                    container.appPaths.deleteTemporaryImage(acquisition.imageRef)
                }
            }
        }
    }

    /** 在 OCR 前执行本地质量闸门，并在拒绝时立即清理临时图片。 */
    private suspend fun inspectImage(
        acquisition: ImageAcquisitionResult.Success,
        generation: Long,
    ) {
        if (generation != flowGeneration) return
        mutableUiState.update {
            it.copy(screen = AppScreen.Analysis("图片检查", "正在检查图片是否清晰可读", 0.32f))
        }

        when (val quality = container.imageQualityAnalyzer.analyze(acquisition.imageRef)) {
            is ImageQualityResult.Failure -> {
                showError("无法检查图片", quality.message, generation)
            }

            is ImageQualityResult.PoorImage -> {
                showError("图片质量不足", quality.issues.joinToString(separator = "；"), generation)
            }

            ImageQualityResult.Passed -> {
                recognize(acquisition, generation)
            }
        }
    }

    /** 取消当前流程并清除页面状态。 */
    suspend fun navigateHome() {
        flowGeneration += 1L
        clearTemporaryImage()
        mutableUiState.value = AppUiState(isDemo = container.isDemo)
    }

    /** 打开关于与隐私页。 */
    fun showAbout() {
        mutableUiState.update { it.copy(screen = AppScreen.About) }
    }

    /**
     * 接受演示草稿并验证确认后的不可变领域对象。
     *
     * 真实字段编辑器属于 B4；当前阶段只证明确认闸门和依赖方向可运行。
     */
    suspend fun confirmDemoTicket() {
        val review = mutableUiState.value.screen as? AppScreen.Review ?: return
        if (container.usesRealRecognition) {
            showError(
                "识别 PoC 已完成",
                "人工编辑和真实开奖尚未接入，本次票面不会用于中奖判断",
                flowGeneration,
            )
            return
        }
        val draft = review.draft
        val lotteryType = draft.lotteryType ?: return showError("票面信息不完整", "请重新识别彩票", flowGeneration)
        val ticket =
            ConfirmedTicket(
                lotteryType = ConfirmedValue(lotteryType, TicketFieldOrigin.OCR),
                issue = ConfirmedValue(Issue(draft.issue), TicketFieldOrigin.OCR),
                betLines =
                    draft.betLines.map { line ->
                        BetLine(
                            primaryNumbers = ConfirmedValue(line.primaryNumbers.sorted(), TicketFieldOrigin.OCR),
                            secondaryNumbers = ConfirmedValue(line.secondaryNumbers.sorted(), TicketFieldOrigin.OCR),
                            isAdditional = ConfirmedValue(line.isAdditional ?: false, TicketFieldOrigin.OCR),
                            originalText = line.originalText,
                        )
                    },
                multiplier = ConfirmedValue(draft.multiplier ?: 0, TicketFieldOrigin.OCR),
                periodCount = ConfirmedValue(draft.periodCount ?: 0, TicketFieldOrigin.OCR),
                paidAmountFen = ConfirmedValue(draft.paidAmountFen ?: -1L, TicketFieldOrigin.OCR),
            )
        val validation = container.ticketValidator.validate(ticket)
        if (!validation.canCalculate) {
            showError(
                "票面校验未通过",
                validation.problems.joinToString(separator = "；") { it.message },
                flowGeneration,
            )
            return
        }

        val generation = flowGeneration
        clearTemporaryImage()
        mutableUiState.update { it.copy(screen = AppScreen.DrawQuery(ticket)) }
        when (val result = container.drawRepository.getDraw(ticket.lotteryType.value, ticket.issue.value)) {
            is DrawQueryResult.Success -> {
                if (generation == flowGeneration) {
                    mutableUiState.update { it.copy(screen = AppScreen.DemoComplete(result.drawResult)) }
                }
            }

            is DrawQueryResult.Unavailable -> {
                showError("开奖数据不可用", result.message, generation)
            }
        }
    }

    /** 对已取得的临时图片执行本地 OCR 和结构解析。 */
    private suspend fun recognize(
        acquisition: ImageAcquisitionResult.Success,
        generation: Long,
    ) {
        if (generation != flowGeneration) return
        mutableUiState.update {
            it.copy(screen = AppScreen.Analysis("本地识别", "正在读取票面文字，不会上传图片", 0.52f))
        }

        when (val recognition = container.ticketRecognizer.recognize(acquisition.imageRef)) {
            is RecognitionResult.Failure -> {
                showError("识别失败", recognition.message, generation)
            }

            is RecognitionResult.PoorImage -> {
                showError("图片质量不足", recognition.issues.joinToString(separator = "；"), generation)
            }

            is RecognitionResult.Success -> {
                if (generation != flowGeneration) return
                mutableUiState.update {
                    it.copy(screen = AppScreen.Analysis("结构解析", "正在整理期号、号码和投注属性", 0.82f))
                }
                when (val parsed = container.ticketParser.parse(recognition.document)) {
                    is TicketParseResult.NeedsCorrection -> {
                        showError("需要人工修正", parsed.message, generation)
                    }

                    is TicketParseResult.Unsupported -> {
                        showError("暂不支持这张彩票", parsed.message, generation)
                    }

                    is TicketParseResult.ReadyForReview -> {
                        if (generation == flowGeneration) {
                            mutableUiState.update {
                                it.copy(
                                    screen = AppScreen.Review(parsed.draft, acquisition.imageRef),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** 只在当前流程仍有效时展示错误。 */
    private suspend fun showError(
        title: String,
        message: String,
        generation: Long,
    ) {
        if (generation == flowGeneration) {
            clearTemporaryImage()
            mutableUiState.update { it.copy(screen = AppScreen.Error(title, message)) }
        }
    }

    /** 删除当前流程持有的临时图片并清空引用。 */
    private suspend fun clearTemporaryImage() {
        activeImageRef?.let { container.appPaths.deleteTemporaryImage(it) }
        activeImageRef = null
    }
}
