package roc.win.lottery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import roc.win.lottery.app.MainDestination
import roc.win.lottery.app.theme.LotteryThemeTokens

/**
 * 彩票核对一级工作区。
 *
 * @param supportsCamera 当前平台是否支持拍摄彩票。
 * @param isDemo 是否展示开发阶段能力边界。
 * @param usesRealImageAcquisition 是否已接入真实图片采集。
 * @param usesRealRecognition 是否已接入真实本地 OCR。
 * @param usesRealDrawData 是否已接入真实官网开奖查询。
 * @param availableMainDestinations 当前平台可进入的一级目的地。
 * @param onMainDestinationSelected 一级目的地切换操作。
 * @param onCamera 拍照操作。
 * @param onImport 图片导入操作。
 * @param onManualEntry 手动录入彩票操作。
 * @param onAbout 关于与隐私操作。
 */
@Composable
fun HomeScreen(
    supportsCamera: Boolean,
    isDemo: Boolean,
    usesRealImageAcquisition: Boolean,
    usesRealRecognition: Boolean,
    usesRealDrawData: Boolean,
    availableMainDestinations: List<MainDestination>,
    onMainDestinationSelected: (MainDestination) -> Unit,
    onCamera: () -> Unit,
    onImport: () -> Unit,
    onManualEntry: () -> Unit,
    onAbout: () -> Unit,
) {
    AppShell(
        title = "核对",
        actionIcon = LotteryIcons.Info,
        actionDescription = "关于与隐私",
        onAction = onAbout,
        mainDestination = MainDestination.VERIFICATION,
        availableMainDestinations = availableMainDestinations,
        onMainDestinationSelected = onMainDestinationSelected,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                if (isDemo) {
                    DemoNotice(usesRealImageAcquisition, usesRealRecognition, usesRealDrawData)
                    Spacer(Modifier.height(16.dp))
                }
                VerificationHero(
                    primaryLabel = if (supportsCamera) "拍照" else "导入图片",
                    primaryIcon = if (supportsCamera) LotteryIcons.Camera else LotteryIcons.Image,
                    onPrimary = if (supportsCamera) onCamera else onImport,
                )
                Spacer(Modifier.height(SectionSpacing))
                SectionHeader(title = "其他方式")
                Spacer(Modifier.height(SectionTitleSpacing))
                TicketSourceTiles(
                    showsImport = supportsCamera,
                    showsManualEntry = usesRealDrawData,
                    onImport = onImport,
                    onManualEntry = onManualEntry,
                )
                Spacer(Modifier.height(SectionSpacing))
                SectionHeader(title = "隐私与结果边界")
                Spacer(Modifier.height(SectionTitleSpacing))
                LotteryCard(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrivacyPoint(
                        icon = LotteryIcons.Privacy,
                        title = "票面只在本机处理",
                        body = "图片不上传，确认后删除；结构化票据信息只保存在本机。",
                    )
                    HairlineDivider()
                    PrivacyPoint(
                        icon = LotteryIcons.Evidence,
                        title = "以官方结果为准",
                        body = "测算结果以发行机构公告和实体彩票兑奖结果为准。",
                    )
                }
            }
        }
    }
}

/**
 * 首页品牌主卡：红色渐变背景、号码球装饰和主采集操作。
 *
 * @param primaryLabel 主操作文字。
 * @param primaryIcon 主操作图标。
 * @param onPrimary 主操作。
 */
@Composable
private fun VerificationHero(
    primaryLabel: String,
    primaryIcon: ImageVector,
    onPrimary: () -> Unit,
) {
    val colors = LotteryThemeTokens.colors
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(Brush.linearGradient(listOf(colors.heroStart, colors.heroEnd)))
                .drawBehind {
                    // 右上角叠放几枚半透明号码球，呼应品牌图标。
                    val unit = size.minDimension
                    drawCircle(
                        Color.White.copy(alpha = 0.08f),
                        radius = unit * 0.42f,
                        center =
                            Offset(size.width * 0.98f, 0f),
                    )
                    drawCircle(
                        Color.White.copy(alpha = 0.10f),
                        radius = unit * 0.16f,
                        center =
                            Offset(size.width * 0.80f, unit * 0.30f),
                    )
                    drawCircle(
                        Color.White.copy(alpha = 0.14f),
                        radius = unit * 0.07f,
                        center =
                            Offset(size.width * 0.93f, unit * 0.50f),
                    )
                },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LotteryIcons.Scan, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("纸质彩票核对", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                "支持超级大乐透、双色球和快乐8，票面信息由你确认后再查询官方开奖数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.86f),
            )
            Spacer(Modifier.height(24.dp))
            PrimaryActionButton(
                text = primaryLabel,
                onClick = onPrimary,
                modifier = Modifier.widthIn(max = 420.dp),
                icon = primaryIcon,
                containerColor = Color.White,
                contentColor = colors.heroEnd,
            )
        }
    }
}

/** 根据可用宽度排列其余票面来源。 */
@Composable
private fun TicketSourceTiles(
    showsImport: Boolean,
    showsManualEntry: Boolean,
    onImport: () -> Unit,
    onManualEntry: () -> Unit,
) {
    val tiles =
        buildList<@Composable (Modifier) -> Unit> {
            if (showsImport) {
                add { modifier ->
                    TicketSourceTile(
                        label = "导入图片",
                        detail = "选择已拍好的票面照片",
                        icon = LotteryIcons.Image,
                        accent = MaterialTheme.colorScheme.secondary,
                        accentContainer = MaterialTheme.colorScheme.secondaryContainer,
                        onClick = onImport,
                        modifier = modifier,
                    )
                }
            }
            if (showsManualEntry) {
                add { modifier ->
                    TicketSourceTile(
                        label = "手动录入",
                        detail = "逐项填写彩种、期号和号码",
                        icon = LotteryIcons.Edit,
                        accent = LotteryThemeTokens.colors.warning,
                        accentContainer = LotteryThemeTokens.colors.warningContainer,
                        onClick = onManualEntry,
                        modifier = modifier,
                    )
                }
            }
        }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 480.dp) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.forEach { tile -> tile(Modifier.weight(1f)) }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.forEach { tile -> tile(Modifier.fillMaxWidth()) }
            }
        }
    }
}

/** 绘制单个票面来源磁贴：图标底座、标题、说明和进入箭头。 */
@Composable
private fun TicketSourceTile(
    label: String,
    detail: String,
    icon: ImageVector,
    accent: Color,
    accentContainer: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LotteryCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon = icon, tint = accent, container = accentContainer)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                LotteryIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 隐私说明中的一条要点。 */
@Composable
private fun PrivacyPoint(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        IconTile(
            icon = icon,
            tint = LotteryThemeTokens.colors.success,
            container = LotteryThemeTokens.colors.successContainer,
            size = 36.dp,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 开发阶段能力边界提示。
 *
 * @param usesRealImageAcquisition 是否已接入真实图片采集。
 * @param usesRealRecognition 是否已接入真实本地 OCR。
 * @param usesRealDrawData 是否已接入真实官网开奖查询。
 */
@Composable
private fun DemoNotice(
    usesRealImageAcquisition: Boolean,
    usesRealRecognition: Boolean,
    usesRealDrawData: Boolean,
) {
    Notice(
        message =
            when {
                usesRealRecognition && usesRealDrawData -> {
                    "本地识别验证版：图片导入、本地 OCR、人工确认、官网开奖结果查询和本地中奖测算已贯通；正式对账与数据授权尚未完成。"
                }

                usesRealRecognition -> {
                    "识别 PoC：图片导入、本地 OCR 和保守解析已在本机运行；开奖结果仍为固定演示数据。"
                }

                usesRealImageAcquisition -> {
                    "导入 PoC：系统文件导入和私有图片副本已在本机运行；桌面 OCR 尚未接入。"
                }

                else -> {
                    "开发演示：票面解析使用保守真实逻辑，图片采集、OCR 和开奖结果仍为本地固定数据。"
                }
            },
    )
}
