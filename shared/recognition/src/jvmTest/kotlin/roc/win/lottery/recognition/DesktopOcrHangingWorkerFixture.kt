package roc.win.lottery.recognition

import kotlin.system.exitProcess

/** 供取消测试使用、接受工作参数后故意不读取匿名管道的独立进程入口。 */
fun main(arguments: Array<String>) {
    if (!DesktopOcrWorker.isRequested(arguments)) {
        exitProcess(3)
    }
    Thread.sleep(HANG_DURATION_MILLISECONDS)
}

/** 保证测试有足够时间确认客户端主动终止子进程。 */
private const val HANG_DURATION_MILLISECONDS = 30_000L
