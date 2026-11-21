package me.winterkind.multitool.wifi

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.winterkind.multitool.clock.formatTime
import me.winterkind.multitool.ui.LAutoText
import me.winterkind.multitool.ui.LText
import me.winterkind.multitool.ui.Lcars
import me.winterkind.multitool.ui.LcarsMeter
import me.winterkind.multitool.ui.LcarsPill
import me.winterkind.multitool.ui.LcarsReadout
import me.winterkind.multitool.ui.LcarsSectionHeader
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

fun bandColor(band: Band): Color = when (band) {
    Band.B24 -> Lcars.Peach
    Band.B5 -> Lcars.Lilac
    Band.B6 -> Lcars.Sky
    Band.ALL -> Lcars.Tan
}

private fun mbps(v: Double) = when {
    v >= 1000 -> "%.2f Gbit/s".format(Locale.GERMAN, v / 1000)
    v >= 100 -> "%.0f Mbit/s".format(Locale.GERMAN, v)
    else -> "%.1f Mbit/s".format(Locale.GERMAN, v)
}

private fun channelOf(freq: Int, band: Band): Int = when (band) {
    Band.B24 -> if (freq == 2484) 14 else (freq - 2407) / 5
    Band.B5 -> (freq - 5000) / 5
    Band.B6 -> (freq - 5950) / 5
    Band.ALL -> 0
}

/** Free-space estimate with an assumed 20 dBm EIRP. Walls make the real distance shorter. */
private fun distanceMeters(rssi: Int, freqMhz: Int): Double {
    val pathLoss = 20.0 - rssi
    return 10.0.pow((pathLoss - 20 * log10(freqMhz.toDouble()) + 27.55) / 20.0)
}

@Composable
fun WifiDetailScreen(
    n: WifiNetwork,
    all: List<WifiNetwork>,
    inRange: Boolean,
    scanning: Boolean,
    onBack: () -> Unit,
    onScan: () -> Unit,
    /** Disables the intro animation (screenshot tests). */
    animate: Boolean = true,
) {
    val accent = bandColor(n.band)
    val progress = remember(n.bssid) { Animatable(if (animate) 0f else 1f) }
    val sweep = remember(n.bssid) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(n.bssid) {
        if (animate) {
            sweep.animateTo(1f, tween(700, easing = LinearEasing))
        }
    }
    LaunchedEffect(n.bssid) {
        if (animate) progress.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
    }

    val est = remember(n, all) { ThroughputEstimator.estimate(n, all) }
    val overlap = remember(n, all) { ThroughputEstimator.overlapping(n, all) }
    val oui = OuiDatabase.ouiOf(n.bssid)
    val vendor = OuiDatabase.lookup(oui)
    val local = OuiDatabase.isLocallyAdministered(n.bssid)
    val chipsets = n.ie.vendorOuis.mapNotNull { IeParser.CHIPSET_OUIS[it] }.distinct()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Title block
            Reveal(0, progress.value) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LcarsPill("‹ Zurück", Lcars.Violet, height = 34.dp, onClick = onBack)
                    Spacer(Modifier.width(6.dp))
                    LcarsPill(if (scanning) "Scanne…" else "Aktualisieren", accent, enabled = !scanning, height = 34.dp, onClick = onScan)
                }
            }
            Reveal(1, progress.value) {
                Row(Modifier.fillMaxWidth().height(64.dp)) {
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(64.dp)
                            .clip(RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50))
                            .background(accent)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        LAutoText(n.ssid, Modifier.fillMaxWidth(), maxSize = 30.sp, minSize = 14.sp, color = Lcars.Tan, weight = FontWeight.Bold)
                        LText(
                            if (inRange) "Objekt erfasst · ${n.bssid}" else "Signal verloren · letzte Daten · ${n.bssid}",
                            size = 13.sp, color = if (inRange) Lcars.Periwinkle else Lcars.Mars, maxLines = 1,
                        )
                    }
                }
            }

            Section(2, progress.value, "Signal", accent) {
                LcarsReadout("Signalstärke", "${n.rssi} dBm", signalColorOf(n.rssi))
                LcarsMeter(((n.rssi + 100).coerceIn(0, 70)) / 70f, Modifier.fillMaxWidth(), segments = 30, onColor = accent, height = 14.dp)
                LcarsReadout("Qualität", "${(ThroughputEstimator.signalFactor(n.rssi) * 100).toInt()} % der Maximalrate")
                LcarsReadout("Entfernung (grob, Freiraum)", "≈ %.0f m".format(Locale.GERMAN, distanceMeters(n.rssi, n.frequency)))
                n.lastSeenMs?.let { LcarsReadout("Zuletzt empfangen", formatTime(it)) }
            }

            Section(3, progress.value, "Funk", accent) {
                LcarsReadout("Band", n.band.label)
                LcarsReadout("Frequenz", "${n.frequency} MHz")
                LcarsReadout("Kanal", n.channel.toString())
                LcarsReadout("Kanalbreite", "${n.widthMhz} MHz")
                val (lo, hi) = n.frequencyRange()
                if (n.widthMhz > 20) {
                    LcarsReadout("Belegter Bereich", "$lo–$hi MHz · CH ${channelOf(lo + 10, n.band)}–${channelOf(hi - 10, n.band)}")
                    if (n.centerFreq0 > 0) LcarsReadout("Mittenfrequenz", "${n.centerFreq0} MHz")
                }
                if (n.centerFreq1 > 0) LcarsReadout("Zweites Segment", "${n.centerFreq1} MHz")
                LcarsReadout("Standard", est.generation)
                n.ie.apStreams?.let { LcarsReadout("Antennen-Streams (AP)", "${it}×$it MIMO") }
                if (n.ie.supports160) LcarsReadout("160 MHz", "unterstützt")
                n.ie.bssColor?.let { LcarsReadout("BSS-Color (Wi-Fi 6)", it.toString()) }
                n.ie.country?.let { LcarsReadout("Ländercode", it) }
                if (n.rttResponder) LcarsReadout("Wi-Fi RTT (802.11mc)", "ja, metergenaue Ortung")
            }

            Section(4, progress.value, "Kanalbelegung", accent) {
                val same = overlap.count { it.channel == n.channel }
                LcarsReadout("Netze auf demselben Kanal", same.toString())
                LcarsReadout("Überlappende Netze gesamt", overlap.size.toString())
                overlap.maxByOrNull { it.rssi }?.let { LcarsReadout("Stärkster Nachbar", "${it.ssid} (${it.rssi} dBm)") }
                n.ie.stationCount?.let { LcarsReadout("Verbundene Geräte", it.toString()) }
                n.ie.channelUtilization?.let { u ->
                    LcarsReadout("Kanalauslastung (AP)", "${(u * 100).toInt()} %")
                    LcarsMeter(u, Modifier.fillMaxWidth(), segments = 30, onColor = Lcars.Gold, height = 10.dp)
                }
            }

            Section(5, progress.value, "Bandbreite · Schätzung", accent) {
                LAutoText(
                    "≈ ${mbps(est.expectedMbps)}", Modifier.fillMaxWidth(),
                    maxSize = 44.sp, minSize = 20.sp, color = accent, weight = FontWeight.Bold,
                )
                LText("realistisch erwartbarer Durchsatz, jetzt und hier", size = 13.sp, color = Lcars.Periwinkle)
                LcarsReadout("Max. Linkrate AP (${est.apStreams} Streams)", mbps(est.apPhyMbps))
                LcarsReadout("Max. Linkrate Handy (${est.deviceStreams} Streams)", mbps(est.linkPhyMbps))
                LcarsReadout("Signal-Faktor", "${(est.signalFactor * 100).toInt()} %")
                LcarsReadout("Anteil Sendezeit", "${(est.airtimeShare * 100).toInt()} %")
                LText(
                    "Annahmen: ${est.generation}, ${n.widthMhz} MHz, Handy mit ${ThroughputEstimator.DEVICE_STREAMS} Antennen, " +
                        "Protokoll-Overhead eingerechnet; Sendezeit ${est.airtimeSource}.",
                    size = 12.sp, color = Lcars.Violet,
                )
            }

            Section(6, progress.value, "Sicherheit", accent) {
                LcarsReadout("Verfahren", n.security)
                n.ie.rsn?.let { r ->
                    if (r.akms.isNotEmpty()) LcarsReadout("Schlüsselverwaltung", r.akms.joinToString(", "))
                    if (r.pairwiseCiphers.isNotEmpty()) LcarsReadout("Verschlüsselung", r.pairwiseCiphers.joinToString(", "))
                    r.groupCipher?.let { LcarsReadout("Gruppenschlüssel", it) }
                    LcarsReadout(
                        "Management-Frame-Schutz",
                        when { r.mfpRequired -> "erforderlich"; r.mfpCapable -> "optional"; else -> "aus" },
                    )
                }
                val wps = n.ie.wps
                LcarsReadout(
                    "WPS",
                    when {
                        wps == null -> if (n.capabilities.contains("WPS")) "aktiv" else "aus"
                        wps.selectedRegistrar -> "aktiv · Kopplung läuft gerade!"
                        else -> "aktiv"
                    },
                    if (wps?.selectedRegistrar == true) Lcars.Mars else Lcars.Tan,
                )
                val roaming = listOfNotNull(
                    "802.11k".takeIf { n.ie.radioMeasurement11k },
                    "802.11r".takeIf { n.ie.fastRoaming11r },
                    "802.11v".takeIf { n.ie.bssTransition11v },
                )
                if (roaming.isNotEmpty()) LcarsReadout("Roaming / Mesh", roaming.joinToString(" · "))
                if (n.ie.easyMesh) LcarsReadout("EasyMesh", "Multi-AP-Netz")
                if (n.passpoint || n.ie.interworking) LcarsReadout("Hotspot 2.0 / Passpoint", if (n.passpoint) "ja" else "Interworking")
                n.operatorName?.let { LcarsReadout("Betreiber", it) }
                n.venueName?.let { LcarsReadout("Standort", it) }
            }

            Section(7, progress.value, "Hardware", accent) {
                LcarsReadout(
                    "Hersteller (MAC)",
                    when {
                        vendor != null -> vendor
                        local -> "virtuelle MAC"
                        else -> "unbekannt"
                    },
                )
                LcarsReadout("MAC-Typ", if (local) "lokal verwaltet (Gast-/Mesh-/virtuelles Netz)" else "weltweit eindeutig")
                n.ie.wps?.let { w ->
                    w.manufacturer?.let { LcarsReadout("Gerät · Hersteller", it) }
                    w.modelName?.let { LcarsReadout("Gerät · Modell", it) }
                    w.modelNumber?.let { LcarsReadout("Gerät · Modellnummer", it) }
                    w.deviceName?.let { LcarsReadout("Gerät · Name", it) }
                }
                LcarsReadout("Chipsatz (vermutet)", chipsets.joinToString(", ").ifEmpty { "nicht erkennbar" })
                if (n.ie.vendorOuis.isNotEmpty()) {
                    LText("Hersteller-Kennungen im Beacon:", size = 13.sp, color = Lcars.Periwinkle)
                    n.ie.vendorOuis.forEach { o ->
                        LText(
                            "· ${o.chunked(2).joinToString(":")}  ${IeParser.CHIPSET_OUIS[o] ?: OuiDatabase.lookup(o) ?: "?"}",
                            size = 13.sp, color = Lcars.Tan, maxLines = 1,
                        )
                    }
                }
                if (!n.ie.available) {
                    LText(
                        "Detaildaten aus dem Beacon liefert Android erst ab Version 11.",
                        size = 12.sp, color = Lcars.Violet,
                    )
                }
            }

            Section(8, progress.value, "Rohdaten", Lcars.Violet) {
                LText(n.capabilities.ifBlank { "—" }, size = 12.sp, color = Lcars.Violet)
            }
            Spacer(Modifier.height(16.dp))
        }

        // LCARS "scan" sweep running down the panel when the detail view opens.
        if (sweep.value < 1f) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height * sweep.value
                val band = 90.dp.toPx()
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.45f), accent),
                        startY = y - band, endY = y,
                    ),
                    topLeft = Offset(0f, y - band),
                    size = Size(size.width, band),
                )
                drawRect(Color.White, Offset(0f, y - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
            }
        }
    }
}

/** Panels slide in from the left and unfold one after another. */
@Composable
private fun Reveal(index: Int, progress: Float, content: @Composable () -> Unit) {
    val start = index * 0.07f
    val p = ((progress - start) / 0.35f).coerceIn(0f, 1f)
    Box(
        Modifier.graphicsLayer {
            alpha = p
            translationX = (1f - p) * -60.dp.toPx()
        }
    ) { content() }
}

@Composable
private fun Section(index: Int, progress: Float, title: String, color: Color, content: @Composable ColumnScope.() -> Unit) {
    val start = index * 0.07f
    val p = ((progress - start) / 0.35f).coerceIn(0f, 1f)
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LcarsSectionHeader(
            title, color,
            Modifier.graphicsLayer {
                scaleX = 0.15f + 0.85f * p
                transformOrigin = TransformOrigin(0f, 0.5f)
                alpha = (p * 2f).coerceAtMost(1f)
            },
        )
        Column(
            Modifier.graphicsLayer {
                val q = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
                alpha = q
                translationY = (1f - q) * 12.dp.toPx()
            },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}

fun signalColorOf(rssi: Int): Color = when {
    rssi >= -55 -> Lcars.Green
    rssi >= -67 -> Lcars.Orange
    rssi >= -78 -> Lcars.Gold
    else -> Lcars.Mars
}
