package roc.win.lottery.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 提供四端一致的顶部栏、内容宽度和安全区域。
 *
 * @param title 顶部栏标题。
 * @param navigationIcon 可选的返回图标。
 * @param onNavigate 返回操作。
 * @param actionIcon 可选的右侧操作图标。
 * @param actionDescription 右侧图标的无障碍说明。
 * @param onAction 右侧操作。
 * @param actions 可选的自定义右侧操作区。
 * @param scrollableContent 是否由外壳提供纵向滚动；惰性列表页面应设为 `false`。
 * @param content 页面正文。
 */
@Composable
fun AppShell(
    title: String,
    navigationIcon: ImageVector? = null,
    onNavigate: () -> Unit = {},
    actionIcon: ImageVector? = null,
    actionDescription: String = "",
    onAction: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollableContent: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.widthIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    navigationIcon?.let { icon ->
                        IconButton(onClick = onNavigate) {
                            Icon(icon, contentDescription = "返回")
                        }
                    }
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                Box(modifier = Modifier.widthIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        actions()
                        actionIcon?.let { icon ->
                            IconButton(onClick = onAction) {
                                Icon(icon, contentDescription = actionDescription)
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (scrollableContent) {
                    Column(
                        modifier =
                            Modifier
                                .widthIn(max = 960.dp)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        Spacer(Modifier.height(28.dp))
                        content()
                        Spacer(Modifier.height(32.dp))
                    }
                } else {
                    Box(
                        modifier =
                            Modifier
                                .widthIn(max = 960.dp)
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
