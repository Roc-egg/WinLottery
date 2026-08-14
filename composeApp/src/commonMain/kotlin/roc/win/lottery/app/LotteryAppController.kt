package roc.win.lottery.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import roc.win.lottery.data.DrawQueryResult
import roc.win.lottery.recognition.ImageAcquisitionResult
import roc.win.lottery.recognition.ImageAcquisitionSource
import roc.win.lottery.recognition.ImageQualityResult
import roc.win.lottery.recognition.ImageRef
import roc.win.lottery.recognition.RecognitionResult
import roc.win.lottery.recognition.TicketParseResult

/** 驱动本地图片识别、票面人工校正和开奖查询流程的应用状态持有者。 */
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

    /** 应用一次人工校正动作并立即刷新领域问题。 */
    fun updateTicketReview(action: TicketReviewAction) {
        val review = mutableUiState.value.screen as? AppScreen.Review ?: return
        val editor = review.editor.applyAction(action)
        mutableUiState.update {
            it.copy(
                screen =
                    review.copy(
                        editor = editor,
                        evaluation = editor.evaluate(container.ticketValidator),
                    ),
            )
        }
    }

    /** 用户确认当前合法票据后，清理临时图片并执行一次开奖查询。 */
    suspend fun confirmTicket() {
        val review = mutableUiState.value.screen as? AppScreen.Review ?: return
        val ticket = review.evaluation.ticket
        if (!review.evaluation.canConfirm || ticket == null) return

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
                        val draft = parsed.draft
                        if (draft == null) {
                            showError("需要人工修正", parsed.message, generation)
                        } else if (generation == flowGeneration) {
                            mutableUiState.update {
                                it.copy(
                                    screen = createReviewScreen(draft, acquisition.imageRef),
                                )
                            }
                        }
                    }

                    is TicketParseResult.Unsupported -> {
                        showError("暂不支持这张彩票", parsed.message, generation)
                    }

                    is TicketParseResult.ReadyForReview -> {
                        if (generation == flowGeneration) {
                            mutableUiState.update {
                                it.copy(
                                    screen = createReviewScreen(parsed.draft, acquisition.imageRef),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** 从解析草稿创建带实时领域评估的校正页状态。 */
    private fun createReviewScreen(
        draft: roc.win.lottery.domain.TicketDraft,
        imageRef: ImageRef,
    ): AppScreen.Review {
        val editor = TicketReviewState.fromDraft(draft)
        return AppScreen.Review(
            editor = editor,
            imageRef = imageRef,
            evaluation = editor.evaluate(container.ticketValidator),
        )
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
