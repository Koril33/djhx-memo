package app.djhx.memo

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** 常用操作使用离线 Lucide 24dp 图标；可访问名称由外层控件提供。 */
@Composable
internal fun MemoIcon(
    @DrawableRes resource: Int,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(painterResource(resource), contentDescription = null, modifier = modifier.size(24.dp), tint = tint)
}

/** 48dp 点击区域及统一长按提示；不以字符字形代替操作图标。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoIconButton(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState()
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp)
        ) {
            Box(
                Modifier.size(40.dp).background(
                    if (danger && enabled) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                    RoundedCornerShape(10.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(icon),
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = if (danger && enabled) MaterialTheme.colorScheme.onErrorContainer else LocalContentColor.current
                )
            }
        }
    }
}

@Composable
internal fun MemoMenuItem(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            MemoIcon(
                icon,
                tint = if (danger && enabled) MaterialTheme.colorScheme.onErrorContainer else LocalContentColor.current
            )
        },
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).heightIn(min = 48.dp).background(
            if (danger && enabled) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
            RoundedCornerShape(8.dp)
        )
    )
}

/** Material 对话框的独立窗口不能覆盖应用选择的语言；所有插槽均继承当前配置。 */
@Composable
internal fun MemoAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                content = title
            )
        },
        text = {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                content = text
            )
        },
        confirmButton = {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                content = confirmButton
            )
        },
        dismissButton = if (dismissButton == null) {
            null
        } else {
            {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides configuration,
                    content = dismissButton
                )
            }
        }
    )
}
