package roc.win.lottery.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 一次应用会话持有的依赖、流程控制器和界面操作协程作用域。
 *
 * 平台宿主决定会话寿命：Android 由 ViewModel 跨配置变更保留，iOS 与 Desktop 跟随根组合。
 *
 * @property container 会话独占的应用依赖，会话关闭时一并释放。
 * @property scope 执行界面发起操作的协程作用域；界面重建时不得随组合一起取消。
 */
class LotteryAppSession(
    val container: AppContainer,
    val scope: CoroutineScope,
) {
    /** 会话内唯一的流程控制器。 */
    val controller: LotteryAppController = LotteryAppController(container)

    /** 用户确认后正在执行的 AI 请求，用于响应页面取消。 */
    internal var aiRequestJob: Job? = null

    /** 取消进行中的 AI 请求，并释放容器持有的识别、网络和数据库资源。 */
    fun close() {
        controller.cancelAiAnalysisRequest()
        aiRequestJob?.cancel()
        aiRequestJob = null
        container.close()
    }
}
