package app.djhx.memo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A release and half-row distance are both required; velocity alone never deletes. */
internal fun swipeCommits(offset: Float, width: Float): Boolean = width > 0 && offset <= -width * 0.5f

@Composable
internal fun SwipeNote(
    enabled: Boolean,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var width by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val edge = with(LocalDensity.current) { 24.dp.toPx() }
    val currentTrash by rememberUpdatedState(onTrash)
    val label = stringResource(R.string.move_trash)
    val animated by animateFloatAsState(offset, if (dragging) snap() else spring(), label = "swipe-note")
    LaunchedEffect(enabled) {
        if (!enabled) {
            offset = 0f
            dragging = false
        }
    }
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).onSizeChanged { width = it.width.toFloat() }
            .semantics {
                if (enabled) {
                    customActions = listOf(
                        CustomAccessibilityAction(label) {
                            currentTrash()
                            true
                        }
                    )
                }
            }
            .pointerInput(enabled, width) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Leave the system back edge and vertical scroll to their owners.
                    if (down.position.x < edge || down.position.x > width - edge) return@awaitEachGesture
                    val start = down.position.x
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                        val dx = change.position.x - start
                        val dy = change.position.y - down.position.y
                        if (change.id == down.id && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            dragging = true
                            offset = dx.coerceIn(-width, 0f)
                            change.consume()
                        }
                    }
                    if (drag != null) {
                        val released = horizontalDrag(drag.id) { change ->
                            offset = (offset + change.positionChange().x).coerceIn(-width, 0f)
                            change.consume()
                        }
                        if (released && swipeCommits(offset, width)) currentTrash()
                    }
                    dragging = false
                    offset = 0f
                }
            }
    ) {
        if (offset < 0f) {
            val reached = swipeCommits(offset, width)
            val background = if (reached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.errorContainer
            val foreground = if (reached) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onErrorContainer
            Box(Modifier.matchParentSize().background(background)) {
                Row(
                    Modifier.align(Alignment.CenterEnd).padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MemoIcon(R.drawable.ic_ui_trash_2, tint = foreground)
                    Text(label, color = foreground)
                }
            }
        }
        Box(
            Modifier.offset { IntOffset(animated.roundToInt(), 0) }.background(MaterialTheme.colorScheme.background)
        ) { content() }
    }
}
