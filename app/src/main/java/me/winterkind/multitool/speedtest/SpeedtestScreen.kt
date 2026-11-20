package me.winterkind.multitool.speedtest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.winterkind.multitool.ui.LText
import me.winterkind.multitool.ui.Lcars
import me.winterkind.multitool.ui.LcarsMeter
import me.winterkind.multitool.ui.LcarsPill
import me.winterkind.multitool.ui.LcarsReadout
import me.winterkind.multitool.ui.LcarsSectionHeader
import java.util.Locale
import kotlin.math.log10

private fun fmt(v: Double, digits: Int = 1) = "%.${digits}f".format(Locale.GERMAN, v)

/** Logarithmic scale for the meter: 1 Mbit/s … 1000 Mbit/s. */
private fun meterFraction(mbps: Double) = if (mbps <= 0) 0f else (log10(mbps.coerceAtLeast(1.0)) / 3.0).toFloat()

@Composable
fun SpeedtestScreen(vm: SpeedtestViewModel, state: SpeedtestState) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LcarsSectionHeader("Datenverbindung", Lcars.Orange)
        LcarsReadout("Gegenstelle", SpeedtestServer.LOCATION, Lcars.Sky)
        LcarsReadout("Server", SpeedtestServer.HOST)

        val phaseLabel = when (state.phase) {
            Phase.IDLE -> "Bereit"
            Phase.PING -> "Messe Latenz"
            Phase.DOWNLOAD -> "Messe Download"
            Phase.UPLOAD -> "Messe Upload"
            Phase.DONE -> "Test abgeschlossen"
            Phase.ERROR -> "Fehler"
        }
        LText(phaseLabel, size = 20.sp, color = if (state.phase == Phase.ERROR) Lcars.Mars else Lcars.Lilac)

        // Big live readout
        val big = when (state.phase) {
            Phase.PING -> state.pingSamples.lastOrNull()?.let { fmt(it, 0) } ?: "—"
            Phase.DOWNLOAD, Phase.UPLOAD -> fmt(state.liveMbps)
            Phase.DONE -> state.downloadMbps?.let { fmt(it) } ?: "—"
            else -> "—"
        }
        val unit = if (state.phase == Phase.PING) "ms" else "Mbit/s"
        Row(verticalAlignment = Alignment.Bottom) {
            LText(big, size = 64.sp, color = Lcars.Orange, weight = FontWeight.Bold)
            Spacer(Modifier.height(1.dp))
            LText("  $unit", size = 22.sp, color = Lcars.Tan, modifier = Modifier.padding(bottom = 12.dp))
        }
        LcarsMeter(
            fraction = if (state.phase == Phase.PING) state.progress else meterFraction(state.liveMbps),
            modifier = Modifier.fillMaxWidth(),
            onColor = if (state.phase == Phase.UPLOAD) Lcars.Lilac else Lcars.Orange,
        )
        Row(Modifier.fillMaxWidth()) {
            listOf("1", "10", "100", "1000").forEachIndexed { i, s ->
                LText(s, size = 12.sp, color = Lcars.Periwinkle, modifier = Modifier.weight(if (i == 3) 0.4f else 1f))
            }
        }
        LcarsMeter(state.progress, Modifier.fillMaxWidth(), segments = 40, onColor = Lcars.Periwinkle, height = 6.dp)

        if (state.running) {
            LcarsPill("Abbrechen", Lcars.Red, Modifier.fillMaxWidth(), height = 52.dp, onClick = vm::cancel)
        } else {
            LcarsPill(
                if (state.phase == Phase.IDLE) "Test starten" else "Erneut testen",
                Lcars.Peach, Modifier.fillMaxWidth(), height = 52.dp, onClick = vm::start,
            )
        }
        state.error?.let { LText(it, size = 15.sp, color = Lcars.Mars) }

        Spacer(Modifier.height(4.dp))
        LcarsSectionHeader("Ergebnis", Lcars.Lilac)
        LcarsReadout("Ping", state.ping?.let { "${fmt(it.pingMs)} ms" } ?: "—")
        LcarsReadout("Jitter", state.ping?.let { "${fmt(it.jitterMs)} ms" } ?: "—")
        LcarsReadout("Download", state.downloadMbps?.let { "${fmt(it)} Mbit/s" } ?: "—", Lcars.Orange)
        LcarsReadout("Upload", state.uploadMbps?.let { "${fmt(it)} Mbit/s" } ?: "—", Lcars.Lilac)

        if (state.pingSamples.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            LcarsSectionHeader("Latenzprofil", Lcars.Sky)
            PingBars(state.pingSamples)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PingBars(samples: List<Double>) {
    val max = (samples.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
    ) {
        val n = 12
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        samples.take(n).forEachIndexed { i, v ->
            val h = (v / max).toFloat() * size.height
            drawRect(
                color = if (v == samples.max()) Lcars.Gold else Lcars.Sky,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
            )
        }
        drawRect(Color(0x33FFFFFF), Offset(0f, size.height - 1), Size(size.width, 1f))
    }
}
