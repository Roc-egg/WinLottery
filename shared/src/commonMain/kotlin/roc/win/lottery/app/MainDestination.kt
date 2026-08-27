package roc.win.lottery.app

/** 应用可长期扩展的一级目的地。 */
enum class MainDestination {
    /** 纸质彩票录入、确认与中奖核对工作区。 */
    VERIFICATION,

    /** 合法随机号码生成工作区。 */
    NUMBER_PICKER,

    /** 历史开奖基本走势工作区。 */
    TRENDS,

    /** 用户逐次确认的 AI 历史数据分析工作区。 */
    AI_ANALYSIS,

    /** 本机结构化票据记录工作区。 */
    RECORDS,
}
