package me.winterkind.multitool.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.winterkind.multitool.R

/** Classic LCARS palette (TNG / Voyager era). */
object Lcars {
    val Black = Color(0xFF000000)
    val Orange = Color(0xFFFF9900)
    val Gold = Color(0xFFFFAA00)
    val Tan = Color(0xFFFFCC99)
    val Peach = Color(0xFFFF9966)
    val Lilac = Color(0xFFCC99CC)
    val Violet = Color(0xFF9977AA)
    val Periwinkle = Color(0xFF9999FF)
    val Sky = Color(0xFF99CCFF)
    val Red = Color(0xFFCC4444)
    val Mars = Color(0xFFFF5555)
    val Green = Color(0xFF99DD66)
    val Dim = Color(0xFF332211)

    val Font = FontFamily(
        Font(R.font.antonio_regular, FontWeight.Normal),
        Font(R.font.antonio_bold, FontWeight.Bold),
    )

    val SidebarWidth: Dp = 104.dp
    val Gap: Dp = 4.dp
}

fun lcarsText(size: TextUnit = 18.sp, color: Color = Lcars.Orange, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Lcars.Font, fontSize = size, color = color, fontWeight = weight, letterSpacing = 1.sp)

@Composable
fun LText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 18.sp,
    color: Color = Lcars.Orange,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = lcarsText(size, color, weight),
        textAlign = align,
        maxLines = maxLines,
    )
}

/**
 * Single-line text that shrinks its font size (down to [minSize]) until it fits the available
 * width, so long labels and the big clock never wrap on narrow screens.
 */
@Composable
fun LAutoText(
    text: String,
    modifier: Modifier = Modifier,
    maxSize: TextUnit = 18.sp,
    minSize: TextUnit = 10.sp,
    color: Color = Lcars.Orange,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
    /**
     * If set, the size is fitted to the widest of these strings instead of [text] and therefore
     * stays constant while the text changes (e.g. a ticking clock).
     */
    fitTexts: List<String>? = null,
) {
    val measurer = rememberTextMeasurer()
    val upper = text.uppercase()
    val candidates = fitTexts?.map { it.uppercase() } ?: listOf(upper)
    BoxWithConstraints(modifier) {
        val maxPx = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val size = remember(maxPx, bounded, candidates, maxSize, minSize, weight) {
            var s = maxSize
            if (bounded) {
                while (s.value > minSize.value) {
                    val style = lcarsText(s, color, weight)
                    val w = candidates.maxOf {
                        measurer.measure(it, style, maxLines = 1, softWrap = false).size.width
                    }
                    if (w <= maxPx) break
                    s = (s.value - 1f).sp
                }
            }
            s
        }
        Text(
            text = upper,
            style = lcarsText(size, color, weight),
            textAlign = align,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * The typical LCARS "elbow": a vertical sidebar joined to a horizontal bar by a large rounded
 * outer corner and a small concave inner corner.
 */
@Composable
fun LcarsElbow(
    modifier: Modifier,
    color: Color,
    top: Boolean,
    sidebarWidth: Dp = Lcars.SidebarWidth,
    barHeight: Dp = 20.dp,
    innerRadius: Dp = 18.dp,
    segments: List<Pair<Float, Color>> = emptyList(),
) {
    Canvas(modifier) {
        val sw = sidebarWidth.toPx()
        val bar = barHeight.toPx()
        val r = innerRadius.toPx()
        val outer = minOf(size.height, sw) * 0.75f
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, h)
            lineTo(0f, outer)
            arcTo(Rect(0f, 0f, outer * 2, outer * 2), 180f, 90f, false)
            lineTo(w, 0f)
            lineTo(w, bar)
            lineTo(sw + r, bar)
            arcTo(Rect(sw, bar, sw + 2 * r, bar + 2 * r), 270f, -90f, false)
            lineTo(sw, h)
            close()
        }
        val draw: () -> Unit = {
            drawPath(path, color)
            // Optional coloured segments inside the horizontal bar, separated by black gaps.
            segments.forEach { (startFraction, c) ->
                val x = sw + r * 3 + (w - sw - r * 3) * startFraction
                drawRect(Color.Black, Offset(x - 3.dp.toPx(), 0f), Size(3.dp.toPx(), bar))
                drawRect(c, Offset(x, 0f), Size(w - x, bar))
            }
        }
        if (top) {
            draw()
        } else {
            // Mirror vertically for the bottom elbow.
            drawContext.transform.scale(1f, -1f, Offset(w / 2, h / 2))
            draw()
        }
    }
}

/** Sidebar block / button: rectangle with the label in the bottom-right corner, LCARS style. */
@Composable
fun LcarsBlock(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        pressed -> Color.White
        selected -> Lcars.Tan
        else -> color
    }
    Box(
        modifier
            .width(Lcars.SidebarWidth)
            .height(height)
            .background(bg)
            .let { if (onClick != null) it.clickable(interaction, null, onClick = onClick) else it }
            .padding(end = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        LText(label, size = 15.sp, color = Lcars.Black, weight = FontWeight.Bold, align = TextAlign.End)
    }
}

/** Rounded "pill" button. */
@Composable
fun LcarsPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 44.dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when {
        !enabled -> color.copy(alpha = 0.35f)
        pressed -> Color.White
        else -> color
    }
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interaction, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 2.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        LAutoText(label, maxSize = 18.sp, color = Lcars.Black, weight = FontWeight.Bold, align = TextAlign.End)
    }
}

/** Row of selectable pills, used e.g. for the WiFi band selector. */
@Composable
fun <T> LcarsSegmentSelector(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Lcars.Gap)) {
        options.forEachIndexed { i, (value, label) ->
            val shape = when (i) {
                0 -> RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                options.lastIndex -> RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                else -> RoundedCornerShape(0)
            }
            val isSel = value == selected
            Box(
                Modifier
                    .weight(if (i == 0) 0.8f else 1f)
                    .height(38.dp)
                    .clip(shape)
                    .background(if (isSel) Lcars.Orange else Lcars.Violet)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                LText(label, size = 15.sp, color = Lcars.Black, weight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** Section header: a short coloured bar, the title, and a long bar filling the rest. */
@Composable
fun LcarsSectionHeader(title: String, color: Color = Lcars.Lilac, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(24.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50))
                .background(color)
        )
        Spacer(Modifier.width(8.dp))
        LText(title, size = 20.sp, color = color, weight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(16.dp)
                .background(color)
        )
    }
}

/** Label/value pair as used in LCARS readouts; long values move below the label. */
@Composable
fun LcarsReadout(label: String, value: String, valueColor: Color = Lcars.Tan, modifier: Modifier = Modifier) {
    if (label.length + value.length > 34) {
        Column(modifier.fillMaxWidth()) {
            LText(label, size = 15.sp, color = Lcars.Periwinkle, maxLines = 1)
            LText(value, size = 18.sp, color = valueColor, align = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        LText(label, size = 15.sp, color = Lcars.Periwinkle, modifier = Modifier.weight(1f), maxLines = 1)
        LText(value, size = 18.sp, color = valueColor, align = TextAlign.End, maxLines = 1)
    }
}

/** Segmented horizontal meter, fraction in 0..1. */
@Composable
fun LcarsMeter(
    fraction: Float,
    modifier: Modifier = Modifier,
    segments: Int = 24,
    onColor: Color = Lcars.Orange,
    offColor: Color = Lcars.Dim,
    height: Dp = 22.dp,
) {
    val lit = (fraction.coerceIn(0f, 1f) * segments).let { kotlin.math.ceil(it).toInt() }
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(segments) { i ->
            val c = when {
                i >= lit -> offColor
                i > segments * 0.85 -> Lcars.Mars
                i > segments * 0.6 -> Lcars.Gold
                else -> onColor
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(height)
                    .background(c)
            )
        }
    }
}

/** A column filling the remaining sidebar space with decorative blocks. */
@Composable
fun LcarsSidebarFiller(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Column(modifier.width(Lcars.SidebarWidth)) { content() }
}

@Composable
fun RowScope.LcarsBarSegment(color: Color, weight: Float, height: Dp = 20.dp) {
    Box(
        Modifier
            .weight(weight)
            .height(height)
            .background(color)
    )
}
