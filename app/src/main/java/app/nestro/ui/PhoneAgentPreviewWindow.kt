package app.nestro.ui

import app.nestro.phone.virtualdisplay.mika.MikaVirtualDisplayController
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 虚拟屏小窗（步骤 b：单对话主界面骨架）。
 * 执行时嵌在对话里的小尺寸实时画面：可拖动、可点按放大、拖角缩放；
 * onExpand 不为空时右上角出现「展开」，点开进完整手机任务页。
 * 对话页与手机任务页共用同一份实现，行为一致。
 */
@Composable
internal fun PhoneAgentPreviewWindow(
    session: MikaVirtualDisplayController.Session,
    onExpand: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val virtualFrame by session.frames.collectAsState()
    var previewScale by remember(session) { mutableFloatStateOf(0.68f) }
    var windowOffset by remember(session) { mutableStateOf(Offset.Zero) }
    val previewWidthDp = 180.dp * previewScale
    val frameAspect = virtualFrame?.let { it.height.toFloat() / it.width.toFloat() } ?: (320f / 180f)
    val previewHeightDp = previewWidthDp * frameAspect

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        val density = LocalDensity.current
        val previewWidthPx = with(density) { previewWidthDp.toPx() }
        val previewHeightPx = with(density) { previewHeightDp.toPx() }
        val edgePx = with(density) { 12.dp.toPx() }
        val maxLeft = (constraints.maxWidth - previewWidthPx - edgePx).coerceAtLeast(0f)
        val maxTop = (constraints.maxHeight - previewHeightPx - edgePx).coerceAtLeast(0f)

        Surface(
            color = Card,
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 8.dp,
            modifier = Modifier
                .offset { IntOffset(windowOffset.x.roundToInt(), windowOffset.y.roundToInt()) }
                .padding(end = 12.dp, bottom = 12.dp)
                .width(previewWidthDp)
                .height(previewHeightDp)
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶部拖动条：无展开入口时保持原来 16dp 的细条；
                // 对话里的小窗多一个「展开」，点开进完整手机任务页。
                if (onExpand == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .pointerInput(session, maxLeft, maxTop) {
                                detectDragGestures(
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        windowOffset = clampPhoneAgentPreviewOffset(
                                            windowOffset + dragAmount,
                                            maxLeft,
                                            maxTop
                                        )
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .width(28.dp)
                                .height(3.dp)
                                .background(Muted, RoundedCornerShape(2.dp))
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .pointerInput(session, maxLeft, maxTop) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            windowOffset = clampPhoneAgentPreviewOffset(
                                                windowOffset + dragAmount,
                                                maxLeft,
                                                maxTop
                                            )
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .width(28.dp)
                                    .height(3.dp)
                                    .background(Muted, RoundedCornerShape(2.dp))
                            )
                        }
                        TextButton(
                            onClick = onExpand,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) { Text("展开", color = Mint, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val bitmap = virtualFrame
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "隔离虚拟屏实时画面",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(session, previewScale) {
                                    detectTapGestures(
                                        onTap = {
                                            previewScale = if (previewScale < 1f) 1.4f else 0.68f
                                        },
                                        onDoubleTap = { offset ->
                                            scope.launch {
                                                session.pipInput.tap(
                                                    (offset.x / size.width).coerceIn(0f, 1f),
                                                    (offset.y / size.height).coerceIn(0f, 1f)
                                                )
                                            }
                                        },
                                        onLongPress = { offset ->
                                            scope.launch {
                                                session.pipInput.tap(
                                                    (offset.x / size.width).coerceIn(0f, 1f),
                                                    (offset.y / size.height).coerceIn(0f, 1f)
                                                )
                                            }
                                        }
                                    )
                                }
                                .pointerInput(session, previewScale) {
                                    var resizing = false
                                    val points = mutableListOf<Pair<Float, Float>>()
                                    val corner = 26.dp.toPx()
                                    detectDragGestures(
                                        onDragStart = { start ->
                                            resizing =
                                                (start.x <= corner || start.x >= size.width - corner) &&
                                                (start.y <= corner || start.y >= size.height - corner)
                                            points.clear()
                                            if (!resizing) {
                                                points += (start.x / size.width).coerceIn(0f, 1f) to
                                                    (start.y / size.height).coerceIn(0f, 1f)
                                            }
                                        },
                                        onDragEnd = {
                                            if (!resizing && points.size >= 2) {
                                                val path = points.toList()
                                                scope.launch { session.pipInput.swipe(path, 500L) }
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            if (resizing) {
                                                previewScale = (previewScale + dragAmount.y / 420f).coerceIn(0.45f, 1.4f)
                                            } else {
                                                points += (change.position.x / size.width).coerceIn(0f, 1f) to
                                                    (change.position.y / size.height).coerceIn(0f, 1f)
                                            }
                                            change.consume()
                                        }
                                    )
                                }
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("等待虚拟屏画面…", color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

internal fun clampPhoneAgentPreviewOffset(offset: Offset, maxLeft: Float, maxTop: Float): Offset =
    Offset(
        x = offset.x.coerceIn(-maxLeft.coerceAtLeast(0f), 0f).normalizeZero(),
        y = offset.y.coerceIn(-maxTop.coerceAtLeast(0f), 0f).normalizeZero()
    )

// Offset 是 packed Long 值类，-0f 与 +0f 打包值不同：maxLeft/maxTop 为 0 时
// coerceIn 下限为 -0f，会得到 Offset(-0f, -0f) != Offset.Zero。归一化 -0f 为 +0f。
private fun Float.normalizeZero(): Float = if (this == 0f) 0f else this
