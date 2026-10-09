package com.mobilecoder.ide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mobilecoder.ide.core.common.ui.isImeVisible
import com.mobilecoder.ide.core.storage.AppStorage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * 悬浮导航圆点（设置页「悬浮导航」可开关，默认开）。
 *
 * 可拖动的渐变圆点（位置持久化为容器千分比，重进应用保持原位）；点击以圆点为圆心
 * 展开全部 [AppDestination]：**靠边时扇形**（朝屏幕内侧展开）、**居中时整圈圆形**。
 * 两种模式都是外弧 8 个二级页 + 内弧 5 个底栏 tab 的嵌套双环，沿弧错峰飞出，
 * 外环一条 primary 色带、内环一块 secondary 底盘作主题背景。
 * 再点圆点 / 点遮罩 / 系统返回 / 切换页面 / 弹出键盘均收起。
 */
@Composable
fun FloatingNavDot(
    enabled: Boolean,
    currentRoute: String?,
    onNavigate: (AppDestination) -> Unit,
    showLabels: Boolean = true,

    /** 允许显示的菜单 route 集合；空 = 全部。 */
    visibleMenus: Set<String> = emptySet(),

    /** 各菜单项所在环：route → 0 内圈 / 1 外圈；空 = 默认（底栏内圈、二级页外圈）。 */
    ringOverrides: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val imeVisible = isImeVisible()

    var container by remember { mutableStateOf(IntSize.Zero) }
    var dotPos by remember { mutableStateOf<Offset?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // 菜单可见性与动画解耦：组合期不订阅 Animatable 值，动画期间只走布局 / 绘制阶段
    var menuVisible by remember { mutableStateOf(false) }

    // ---- 圆点位置：夹取到容器内（左右 36dp；上下留出整圈菜单高度） ----
    fun clampToContainer(p: Offset): Offset {
        if (container.width == 0 || container.height == 0) return p
        val xMargin = with(density) { 36.dp.toPx() }
        val yMargin = min(with(density) { 160.dp.toPx() }, container.height * 0.33f)
        return Offset(
            p.x.coerceIn(xMargin, max(xMargin, container.width - xMargin)),
            p.y.coerceIn(yMargin, max(yMargin, container.height - yMargin)),
        )
    }

    // ---- 位置持久化（拖拽结束后写千分比） ----
    fun persistPos() {
        val p = dotPos ?: return
        if (container.width == 0 || container.height == 0) return
        val rx = (p.x / container.width * 1000f).roundToInt().coerceIn(0, 1000)
        val ry = (p.y / container.height * 1000f).roundToInt().coerceIn(0, 1000)
        scope.launch { runCatching { AppStorage.preferences.setFloatingDotPos(rx, ry) } }
    }

    // ---- 展开进度 0→1（快开慢收；错峰由每项 startFrac 切分） ----
    val progress = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        if (expanded) menuVisible = true
        progress.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        )
        if (!expanded) menuVisible = false
    }

    // 换页 / 键盘弹出 / 设置里关掉 → 收起；展开时返回键先收菜单
    LaunchedEffect(currentRoute) { expanded = false }
    LaunchedEffect(imeVisible) { if (imeVisible) expanded = false }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    BackHandler(enabled = expanded) { expanded = false }

    // ---- 首帧从持久化读位置（失败用默认右下侧），容器尺寸变化后重新夹取 ----
    LaunchedEffect(container.width, container.height) {
        if (container.width == 0 || container.height == 0) return@LaunchedEffect
        val current = dotPos
        dotPos = if (current == null) {
            val saved = runCatching { AppStorage.preferences.floatingDotPos() }.getOrNull()
            val parsed = saved?.split(',')?.let { parts ->
                val rx = parts.getOrNull(0)?.toIntOrNull()
                val ry = parts.getOrNull(1)?.toIntOrNull()
                if (rx != null && ry != null) {
                    Offset(rx / 1000f * container.width, ry / 1000f * container.height)
                } else {
                    null
                }
            }
            clampToContainer(parsed ?: Offset(container.width * 0.88f, container.height * 0.55f))
        } else {
            clampToContainer(current)
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val primary = colorScheme.primary
    val secondary = colorScheme.secondary
    val geo = remember(container, dotPos, visibleMenus, showLabels, ringOverrides) {
        buildFanGeo(container, dotPos, density, visibleMenus, showLabels, ringOverrides)
    }

    // 外层 Box 始终参与组合（测量容器尺寸），菜单内容按展开 / 位置就绪情况渲染
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { container = it },
    ) {
        if (!enabled || imeVisible || dotPos == null || geo == null) return@Box
        val dot = dotPos ?: return@Box
        val itemWidth = 48.dp
        val itemWidthPx = with(density) { itemWidth.toPx() }
        val dotSize = 46.dp
        val dotRadiusPx = with(density) { dotSize.toPx() / 2f }
        if (menuVisible) {
            // 点遮罩收起（菜单项压在其上层，仍可正常点击）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { expanded = false } },
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val p = progress.value.coerceIn(0f, 1f)
                drawRect(Color.Black.copy(alpha = 0.45f * p))

                // 外环主题色：primary 色带，圆心线正好穿过外环图标章的圆心
                // → 图标在色带内径向居中；带宽 = 36dp 图标章 + 每侧 4dp。
                val outerBandW = 44.dp.toPx()
                val outerSweep = min(geo.outerSpan + 14f, 360f) * p
                drawArc(
                    color = primary.copy(alpha = 0.20f * p),
                    startAngle = geo.dirDeg - outerSweep / 2f,
                    sweepAngle = outerSweep,
                    useCenter = false,
                    topLeft = Offset(dot.x - geo.rOut, dot.y - geo.rOut),
                    size = Size(geo.rOut * 2f, geo.rOut * 2f),
                    style = Stroke(width = outerBandW, cap = StrokeCap.Round),
                )

                // 内环主题色：secondary 色带，圆心线同样穿过内环图标章的圆心
                // （单环排版 / 内环无项时不画）
                if (geo.hasInnerBand) {
                    val innerBandW = 36.dp.toPx()
                    val innerSweep = min(geo.innerSpan + 14f, 360f) * p
                    drawArc(
                        color = secondary.copy(alpha = 0.22f * p),
                        startAngle = geo.dirDeg - innerSweep / 2f,
                        sweepAngle = innerSweep,
                        useCenter = false,
                        topLeft = Offset(dot.x - geo.rIn, dot.y - geo.rIn),
                        size = Size(geo.rIn * 2f, geo.rIn * 2f),
                        style = Stroke(width = innerBandW, cap = StrokeCap.Round),
                    )
                }
            }

            // 扇形菜单项（沿弧错峰飞出；位置 / 缩放 / 透明度都在布局绘制阶段算，不触发重组）。
            // 渲染顺序：外环先、内环后——Compose 后画的子项先命中触摸，
            // 这样内环章压在外环标签之上，点击不会被外圈抢走。
            geo.items.reversed().forEach { entry ->
                val selected = currentRoute == entry.dest.route
                Box(
                    modifier = Modifier
                        .offset {
                            val ip = itemProgress(progress.value, entry, geo)
                            val pos = polar(dot, entry.radiusPx * ip, entry.angleDeg)
                            val chipHalf = with(density) { entry.chipDp.toPx() / 2f }
                            IntOffset(
                                (pos.x - itemWidthPx / 2f).roundToInt(),
                                (pos.y - chipHalf).roundToInt(),
                            )
                        }
                        .width(itemWidth)
                        .graphicsLayer {
                            val ip = itemProgress(progress.value, entry, geo)
                            alpha = ip
                            val scale = 0.45f + 0.55f * ip
                            scaleX = scale
                            scaleY = scale
                        }
                        .clickable(enabled = expanded) {
                            onNavigate(entry.dest)
                            expanded = false
                        },
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.TopCenter),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = when {
                                selected -> colorScheme.primaryContainer
                                entry.ring == 0 -> colorScheme.surfaceVariant
                                else -> colorScheme.surface
                            },
                            shadowElevation = 4.dp,
                            modifier = Modifier.size(entry.chipDp),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = entry.dest.icon,
                                    contentDescription = entry.dest.label,
                                    tint = if (selected) colorScheme.onPrimaryContainer else primary,
                                    modifier = Modifier.size(entry.iconDp),
                                )
                            }
                        }
                        // 内环（底栏 5 tab）不挂标签：图标与底栏一致，一眼可认，
                        // 少 5 条标签也少一圈挤在圆点旁的视觉噪音。
                        if (entry.withLabel) {
                            Text(
                                text = entry.dest.menuLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 3.dp)
                                    .background(
                                        color = if (selected) colorScheme.primaryContainer
                                        else colorScheme.surface.copy(alpha = 0.95f),
                                        shape = RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---- 圆点本体：点击展开 / 收起，按住拖动换位 ----
        val dotRotation by animateFloatAsState(
            targetValue = if (expanded) 90f else 0f,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
            label = "floatingDotRotation",
        )
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (dot.x - dotRadiusPx).roundToInt(),
                        (dot.y - dotRadiusPx).roundToInt(),
                    )
                }
                .size(dotSize)
                .pointerInput(container) {
                    detectDragGestures(
                        onDragStart = { expanded = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val current = dotPos ?: return@detectDragGestures
                            dotPos = clampToContainer(current + dragAmount)
                        },
                        onDragEnd = { persistPos() },
                        onDragCancel = { persistPos() },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { expanded = !expanded })
                }
                .shadow(10.dp, CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        listOf(colorScheme.primary, colorScheme.secondary),
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.Close else Icons.Default.Apps,
                contentDescription = if (expanded) "收起导航菜单" else "展开导航菜单",
                tint = colorScheme.onPrimary,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(dotRotation),
            )
        }
    }
}

/** 扇形菜单几何（展开时按圆点位置实时求解）。*/
private class FanGeo(
    val dot: Offset,
    val dirDeg: Float,
    val rOut: Float,
    val rIn: Float,
    val outerSpan: Float,
    val innerSpan: Float,
    val spanFrac: Float,
    val items: List<FanEntry>,

    /** 内环（secondary 色带）是否有项；单环排版时为 false。*/
    val hasInnerBand: Boolean,
)

/** 单个菜单项：目标页 + 极坐标（角度 / 半径）+ 图标章尺寸 + 错峰起点。*/
private data class FanEntry(
    val dest: AppDestination,
    val angleDeg: Float,
    val radiusPx: Float,
    val order: Int = 0,
    val startFrac: Float = 0f,
    val chipDp: Dp = 36.dp,
    val iconDp: Dp = 18.dp,

    /** 0 = 内环（底栏 tab，图标即含义，不挂标签更清爽）；1 = 外环（二级页，带标签）。*/
    val ring: Int = 1,
    val withLabel: Boolean = true,
)

/**
 * 求解菜单布局——按圆点位置自动二选一：
 * - **不靠边**（四周到屏边的余量都够摆下一整圈）：**圆形**，各项绕圆点铺满 360°，
 *   自 12 点方向顺时针展开；
 * - **靠边**：**扇形**，朝屏幕内侧展开（在右半屏向左），外弧 180°、内弧 150°，
 *   半径随贴边程度收窄保证不裁切。
 * 两种模式下都是外弧二级页（36dp 图标章）、内弧底栏 tab（28dp 小章），
 * 内外弧半径比固定 0.55；外环一条 primary 色带、内环一块 secondary 底盘。
 *
 * @param visibleMenus 允许显示的 route 集合；空 = 全部。
 * @param showLabels 外圈（及单环排版项）是否挂 menuLabel 胶囊标签。
 * @param ringOverrides 各项所在环（route → 0 内圈 / 1 外圈）；空 = 默认底栏内圈、二级页外圈。
 *   外圈被清空时单环排版：内圈项顶到外半径、挂标签、用外圈色带。
 */
private fun buildFanGeo(
    container: IntSize,
    dot: Offset?,
    density: Density,
    visibleMenus: Set<String> = emptySet(),
    showLabels: Boolean = true,
    ringOverrides: Map<String, Int> = emptyMap(),
): FanGeo? {
    if (dot == null || container.width == 0 || container.height == 0) return null
    val w = container.width.toFloat()
    val h = container.height.toFloat()
    val px = with(density) { 1.dp.toPx() }

    // 到最近屏边的余量：扣除外环色带后仍能容纳 ≥86dp 外弧 → 圆形
    val minRoom = min(min(dot.x, w - dot.x), min(dot.y, h - dot.y))
    val circular = minRoom - 40f * px >= 86f * px

    val dirDeg: Float
    val outerSpan: Float
    val innerSpan: Float
    val rOut: Float
    if (circular) {
        rOut = min(minRoom - 40f * px, 120f * px)
        dirDeg = -90f // 12 点方向起步，顺时针
        outerSpan = 360f
        innerSpan = 360f
    } else {
        val opensLeft = dot.x >= w / 2f
        dirDeg = if (opensLeft) 180f else 0f
        val inward = if (opensLeft) dot.x else w - dot.x
        val vertical = min(dot.y, h - dot.y)
        rOut = min(inward - 42f * px, vertical - 44f * px).coerceIn(96f * px, 120f * px)
        outerSpan = 180f
        innerSpan = 150f
    }
    val rIn = rOut * 0.55f

    // 环上角度：整圆用 i/n（首尾不重复），扇形用 i/(n-1)（铺满两端）；扇形单项时居中朝内
    fun outerAngle(i: Int, n: Int): Float = if (circular) {
        dirDeg + 360f * i / max(n, 1)
    } else if (n <= 1) {
        dirDeg
    } else {
        dirDeg + outerSpan * (i / (n - 1f) - 0.5f)
    }

    fun innerAngle(i: Int, n: Int): Float = if (circular) {
        dirDeg + 360f * (i + 0.5f) / max(n, 1)
    } else if (n <= 1) {
        dirDeg
    } else {
        dirDeg + innerSpan * ((i + 0.5f) / n - 0.5f)
    }

    val allowed: (AppDestination) -> Boolean =
        { visibleMenus.isEmpty() || it.route in visibleMenus }
    val ringOf: (AppDestination) -> Int =
        { ringOverrides[it.route] ?: if (it.inBottomBar) 0 else 1 }
    // 按所选环分组（理论上设置页保证至少显示一项，仍兜底显示全部）
    val visible = AppDestination.entries.filter(allowed)
        .ifEmpty { AppDestination.entries }
    val innerList = visible.filter { ringOf(it) == 0 }
    val outerList = visible.filter { ringOf(it) == 1 }
    // 外圈被清空 → 单环排版：内圈项顶到外半径，挂标签、用外圈色带
    val singleRing = outerList.isEmpty() && innerList.isNotEmpty()

    val raw = buildList {
        innerList.forEachIndexed { i, dest ->
            add(
                FanEntry(
                    dest = dest,
                    angleDeg = if (singleRing) outerAngle(i, innerList.size) else innerAngle(i, innerList.size),
                    radiusPx = if (singleRing) rOut else rIn,
                    chipDp = if (singleRing) 36.dp else 28.dp,
                    iconDp = if (singleRing) 18.dp else 15.dp,
                    ring = if (singleRing) 1 else 0,
                    withLabel = singleRing && showLabels,
                ),
            )
        }
        outerList.forEachIndexed { i, dest ->
            add(
                FanEntry(
                    dest = dest,
                    angleDeg = outerAngle(i, outerList.size),
                    radiusPx = rOut,
                    withLabel = showLabels,
                ),
            )
        }
    }
        // 错峰顺序：先内圈后外圈（由内向外「绽放」），同圈内沿弧依次展开
        .sortedWith(compareBy({ it.ring }, { it.angleDeg }))

    val stagger = 0.045f
    return FanGeo(
        dot = dot,
        dirDeg = dirDeg,
        rOut = rOut,
        rIn = rIn,
        outerSpan = outerSpan,
        innerSpan = innerSpan,
        spanFrac = 1f - (raw.size - 1) * stagger,
        items = raw.mapIndexed { i, entry -> entry.copy(order = i, startFrac = i * stagger) },
        hasInnerBand = innerList.isNotEmpty() && outerList.isNotEmpty(),
    )
}

/** 单项展开进度：全局进度按 [FanEntry.startFrac] 错峰切分后再过缓动。*/
private fun itemProgress(global: Float, entry: FanEntry, geo: FanGeo): Float {
    val raw = ((global - entry.startFrac) / geo.spanFrac).coerceIn(0f, 1f)
    return FastOutSlowInEasing.transform(raw)
}

/** 极坐标：角度 0° 指向 +x（屏幕右），y 向下为正。*/
private fun polar(center: Offset, radiusPx: Float, angleDeg: Float): Offset {
    val rad = angleDeg * (PI / 180.0)
    return Offset(
        center.x + (radiusPx * cos(rad)).toFloat(),
        center.y + (radiusPx * sin(rad)).toFloat(),
    )
}
