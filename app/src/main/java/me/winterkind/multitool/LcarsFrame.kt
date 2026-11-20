package me.winterkind.multitool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.winterkind.multitool.clock.formatTime
import me.winterkind.multitool.clock.rememberTickingTime
import me.winterkind.multitool.ui.LText
import me.winterkind.multitool.ui.Lcars
import me.winterkind.multitool.ui.LcarsBlock
import me.winterkind.multitool.ui.LcarsElbow
import java.time.LocalDate

/** The LCARS chrome: top elbow with title and clock, sidebar menu, bottom elbow. */
@Composable
fun LcarsFrame(
    section: Section,
    onSelect: (Section) -> Unit,
    offsetMs: Long,
    synced: Boolean,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Lcars.Black)
            .safeDrawingPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Header(section, offsetMs, synced)
        Spacer(Modifier.height(Lcars.Gap))
        Row(Modifier.weight(1f)) {
            Sidebar(section, onSelect)
            Spacer(Modifier.width(14.dp))
            Box(Modifier.weight(1f).padding(top = 6.dp)) { content() }
        }
        Spacer(Modifier.height(Lcars.Gap))
        Footer()
    }
}

@Composable
private fun Header(section: Section, offsetMs: Long, synced: Boolean) {
    val now = rememberTickingTime(offsetMs)
    Box(Modifier.fillMaxWidth().height(96.dp)) {
        LcarsElbow(
            Modifier.fillMaxSize(),
            color = Lcars.Orange,
            top = true,
            segments = listOf(0.55f to Lcars.Tan, 0.62f to Lcars.Lilac),
        )
        // Title sits in a black cut-out on the right end of the bar.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = (-6).dp)
                .background(Lcars.Black)
                .padding(start = 10.dp)
        ) {
            LText("Intrepid Multitool", size = 26.sp, color = Lcars.Orange, weight = FontWeight.Bold, maxLines = 1)
        }
        LText(
            "LCARS ${section.code}",
            size = 14.sp,
            color = Lcars.Black,
            weight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp),
        )
        Column(
            Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.End,
        ) {
            LText(
                if (synced) "PTB-synchron" else "Gerätezeit",
                size = 12.sp,
                color = if (synced) Lcars.Green else Lcars.Periwinkle,
            )
            LText(formatTime(now), size = 34.sp, color = Lcars.Tan, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Sidebar(selected: Section, onSelect: (Section) -> Unit) {
    val colors = listOf(Lcars.Peach, Lcars.Lilac, Lcars.Periwinkle)
    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Lcars.Gap)) {
        Section.entries.forEachIndexed { i, s ->
            LcarsBlock(
                label = s.title,
                color = colors[i % colors.size],
                selected = s == selected,
                height = 84.dp,
                onClick = { onSelect(s) },
            )
        }
        // Decorative filler blocks with "random" LCARS numbers.
        LcarsBlock("47-${LocalDate.now().dayOfYear}", Lcars.Violet, height = 40.dp)
        LcarsBlock("NCC-74656", Lcars.Orange, Modifier.weight(1f), height = 40.dp)
    }
}

@Composable
private fun Footer() {
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        LcarsElbow(
            Modifier.fillMaxSize(),
            color = Lcars.Orange,
            top = false,
            barHeight = 14.dp,
            innerRadius = 14.dp,
            segments = listOf(0.4f to Lcars.Periwinkle, 0.48f to Lcars.Peach),
        )
        LText(
            "Sternenflotte",
            size = 12.sp,
            color = Lcars.Black,
            weight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 4.dp),
        )
    }
}
