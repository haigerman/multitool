package me.winterkind.multitool.clock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.winterkind.multitool.ui.LText
import me.winterkind.multitool.ui.Lcars
import me.winterkind.multitool.ui.LcarsPill
import me.winterkind.multitool.ui.LcarsReadout
import me.winterkind.multitool.ui.LcarsSectionHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dateFmt = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", Locale.GERMAN)
private val zoneFmt = DateTimeFormatter.ofPattern("zzz", Locale.GERMAN)

/**
 * Wall-clock milliseconds, corrected by [offsetMs], updated exactly at each second boundary of
 * the corrected time so the displayed seconds flip at the right moment.
 */
@Composable
fun rememberTickingTime(offsetMs: Long, intervalMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() + offsetMs) }
    LaunchedEffect(offsetMs, intervalMs) {
        while (true) {
            val t = System.currentTimeMillis() + offsetMs
            now = t
            delay(intervalMs - t % intervalMs + 1)
        }
    }
    return now
}

fun formatTime(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
fun ClockScreen(vm: ClockViewModel, state: ClockState) {
    val corrected = rememberTickingTime(state.offsetMs, 100)
    val device = rememberTickingTime(0, 100)
    val zoned = Instant.ofEpochMilli(corrected).atZone(ZoneId.systemDefault())

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LcarsSectionHeader("Atomuhr · PTB", Lcars.Orange)

        LText(
            if (state.synced) "Referenzzeit (synchronisiert)" else "Gerätezeit (nicht synchronisiert)",
            size = 15.sp,
            color = if (state.synced) Lcars.Green else Lcars.Mars,
        )
        LText(timeFmt.format(zoned), size = 72.sp, color = Lcars.Orange, weight = FontWeight.Bold)
        LText("${dateFmt.format(zoned)}  ${zoneFmt.format(zoned)}", size = 18.sp, color = Lcars.Tan)

        LcarsPill(
            label = if (state.syncing) "Abgleich läuft…" else "Mit Atomuhr abgleichen",
            color = Lcars.Peach,
            enabled = !state.syncing,
            modifier = Modifier.fillMaxWidth(),
            height = 52.dp,
            onClick = vm::sync,
        )

        state.error?.let {
            LText("Fehler: $it", size = 15.sp, color = Lcars.Mars)
        }

        Spacer(Modifier.height(4.dp))
        LcarsSectionHeader("Abweichung", Lcars.Lilac)
        LcarsReadout("Gerätezeit", formatTime(device))
        val last = state.last
        if (last == null) {
            LcarsReadout("Offset", "—")
            LText(
                "Noch kein Abgleich. Die Uhr in der Kopfzeile zeigt die Gerätezeit.",
                size = 14.sp, color = Lcars.Periwinkle,
            )
        } else {
            val sign = if (last.offsetMs >= 0) "+" else "−"
            val verdict = when {
                abs(last.offsetMs) < 500 -> "Gerät geht sekundengenau" to Lcars.Green
                last.offsetMs > 0 -> "Gerät geht nach" to Lcars.Gold
                else -> "Gerät geht vor" to Lcars.Gold
            }
            LcarsReadout("Offset", "$sign${"%,d".format(Locale.GERMAN, abs(last.offsetMs))} ms", verdict.second)
            LText(verdict.first, size = 15.sp, color = verdict.second)
            LcarsReadout("Laufzeit (RTT)", "${last.roundTripMs} ms")
            LcarsReadout("Genauigkeit", "± ${(last.roundTripMs + 1) / 2} ms")
            LcarsReadout("Letzter Abgleich", state.lastSyncWallMs?.let(::formatTime) ?: "—")

            Spacer(Modifier.height(4.dp))
            LcarsSectionHeader("Gegenstelle", Lcars.Sky)
            LcarsReadout("Server", last.server)
            LcarsReadout("Adresse", last.address)
            LcarsReadout("Stratum", last.stratum.toString())
            LcarsReadout("Referenz", last.referenceId.ifBlank { "—" })
            LcarsReadout("Betreiber", "PTB Braunschweig")

            if (state.samples.size > 1) {
                Spacer(Modifier.height(4.dp))
                LcarsSectionHeader("Messungen", Lcars.Periwinkle)
                state.samples.forEach { s ->
                    Row(Modifier.fillMaxWidth()) {
                        LText(s.server.substringBefore('.'), size = 14.sp, color = Lcars.Periwinkle, modifier = Modifier.weight(1f))
                        LText("${s.offsetMs} ms / RTT ${s.roundTripMs} ms", size = 14.sp, color = Lcars.Tan)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
