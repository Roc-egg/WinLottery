package roc.win.lottery.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.ChartNoAxesCombined
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ClipboardCheck
import com.composables.icons.lucide.Dices
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.FileDown
import com.composables.icons.lucide.FileUp
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X

/** 从 Lucide 图标集集中暴露应用使用的图标。 */
object LotteryIcons {
    /** 返回箭头。 */
    val Back: ImageVector = Lucide.ArrowLeft

    /** 信息图标。 */
    val Info: ImageVector = Lucide.Info

    /** 相机图标。 */
    val Camera: ImageVector = Lucide.Camera

    /** 图片导入图标。 */
    val Image: ImageVector = Lucide.Image

    /** 手动录入图标。 */
    val Edit: ImageVector = Lucide.Pencil

    /** 本机记录图标。 */
    val History: ImageVector = Lucide.History

    /** 彩票核对一级目的地图标。 */
    val Verification: ImageVector = Lucide.ClipboardCheck

    /** 随机选号一级目的地图标。 */
    val NumberPicker: ImageVector = Lucide.Dices

    /** 基本走势图一级目的地图标。 */
    val Trends: ImageVector = Lucide.ChartNoAxesCombined

    /** 查询和搜索图标。 */
    val Search: ImageVector = Lucide.Search

    /** 清除输入图标。 */
    val Clear: ImageVector = Lucide.X

    /** 隐私盾牌图标。 */
    val Privacy: ImageVector = Lucide.ShieldCheck

    /** 减少数值图标。 */
    val Minus: ImageVector = Lucide.Minus

    /** 增加数值图标。 */
    val Plus: ImageVector = Lucide.Plus

    /** 删除投注行图标。 */
    val Delete: ImageVector = Lucide.Trash2

    /** 更多操作图标。 */
    val More: ImageVector = Lucide.EllipsisVertical

    /** 逻辑票据包导入图标。 */
    val ImportFile: ImageVector = Lucide.FileUp

    /** 逻辑票据包导出图标。 */
    val ExportFile: ImageVector = Lucide.FileDown

    /** 完成输入图标。 */
    val Done: ImageVector = Lucide.Check
}
