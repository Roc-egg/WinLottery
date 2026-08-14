package roc.win.lottery.app.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.ShieldCheck

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

    /** 隐私盾牌图标。 */
    val Privacy: ImageVector = Lucide.ShieldCheck

    /** 减少数值图标。 */
    val Minus: ImageVector = Lucide.Minus

    /** 增加数值图标。 */
    val Plus: ImageVector = Lucide.Plus
}
