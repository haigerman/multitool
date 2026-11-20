package me.winterkind.multitool.wifi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import me.winterkind.multitool.clock.formatTime
import me.winterkind.multitool.ui.LText
import me.winterkind.multitool.ui.Lcars
import me.winterkind.multitool.ui.LcarsPill
import me.winterkind.multitool.ui.LcarsSectionHeader
import me.winterkind.multitool.ui.LcarsSegmentSelector

private val requiredPermissions: Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
}.toTypedArray()

@Composable
fun WifiScreen(vm: WifiViewModel, state: WifiState) =
    WifiScreen(state, vm::selectBand, vm::scan, vm::onPermissionsGranted)

@Composable
fun WifiScreen(
    state: WifiState,
    onBand: (Band) -> Unit,
    onScan: () -> Unit,
    onPermissionsGranted: () -> Unit,
    permissionCheck: ((String) -> Boolean)? = null,
) {
    val context = LocalContext.current
    fun hasPermissions() = requiredPermissions.all {
        // The system dialog may grant only coarse location; scanning needs fine.
        it == Manifest.permission.ACCESS_COARSE_LOCATION ||
            permissionCheck?.invoke(it)
            ?: (ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED)
    }
    var granted by remember { mutableStateOf(hasPermissions()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions()
    }
    LaunchedEffect(granted) {
        if (granted) onPermissionsGranted() else launcher.launch(requiredPermissions)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LcarsSectionHeader("Sensorabtastung · WLAN", Lcars.Orange)
        LcarsSegmentSelector(
            options = Band.entries.map { it to it.label },
            selected = state.band,
            onSelect = onBand,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!granted) {
            LText(
                "Für den WLAN-Scan benötigt Android die Standort-Berechtigung. Es werden keine Standortdaten gespeichert.",
                size = 15.sp, color = Lcars.Tan,
            )
            LcarsPill("Berechtigung erteilen", Lcars.Peach, Modifier.fillMaxWidth()) {
                launcher.launch(requiredPermissions)
            }
            LcarsPill("App-Einstellungen", Lcars.Violet, Modifier.fillMaxWidth()) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.fromParts("package", context.packageName, null))
                )
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            LText(
                "${state.visible.size} Netze" + (state.lastScanMs?.let { " · ${formatTime(it)}" } ?: ""),
                size = 16.sp, color = Lcars.Lilac, modifier = Modifier.weight(1f),
            )
            LcarsPill(
                if (state.scanning) "Scanne…" else "Scannen",
                Lcars.Peach, enabled = !state.scanning, height = 36.dp, onClick = onScan,
            )
        }
        if (!state.locationEnabled) {
            LText("Standortdienste sind aus – Android liefert dann keine Scan-Ergebnisse.", size = 14.sp, color = Lcars.Mars)
            LcarsPill("Standort einschalten", Lcars.Gold, Modifier.fillMaxWidth(), height = 36.dp) {
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        state.message?.let { LText(it, size = 14.sp, color = Lcars.Gold) }

        if (state.band != Band.ALL && state.visible.isNotEmpty()) {
            ChannelChart(state.visible, state.band)
        }

        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.visible, key = { it.bssid + it.frequency }) { NetworkRow(it) }
        }
    }
}

private fun signalColor(rssi: Int): Color = when {
    rssi >= -55 -> Lcars.Green
    rssi >= -67 -> Lcars.Orange
    rssi >= -78 -> Lcars.Gold
    else -> Lcars.Mars
}

@Composable
private fun NetworkRow(n: WifiNetwork) {
    val bandColor = when (n.band) {
        Band.B24 -> Lcars.Peach
        Band.B5 -> Lcars.Lilac
        Band.B6 -> Lcars.Sky
        Band.ALL -> Lcars.Tan
    }
    Row(Modifier.fillMaxWidth().height(62.dp)) {
        Box(
            Modifier
                .width(14.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50))
                .background(bandColor)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            LText(n.ssid, size = 19.sp, color = Lcars.Tan, maxLines = 1)
            LText(
                "CH ${n.channel} · ${n.frequency} MHz · ${n.widthMhz} MHz",
                size = 13.sp, color = Lcars.Periwinkle, maxLines = 1,
            )
            LText(
                listOfNotNull(n.security, n.standard, n.bssid).joinToString(" · "),
                size = 12.sp, color = Lcars.Violet, maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 6.dp)) {
            LText("${n.rssi} dBm", size = 18.sp, color = signalColor(n.rssi), weight = FontWeight.Bold)
            SignalBars(n.rssi)
        }
    }
}

@Composable
private fun SignalBars(rssi: Int) {
    val bars = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        rssi >= -88 -> 1
        else -> 0
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(8.dp)
                    .height((6 + i * 5).dp)
                    .background(if (i <= bars) signalColor(rssi) else Lcars.Dim)
            )
        }
    }
}

/** Channel occupancy: one bar per network at its channel, height = signal strength. */
@Composable
private fun ChannelChart(networks: List<WifiNetwork>, band: Band) {
    val (minCh, maxCh) = when (band) {
        Band.B24 -> 1 to 14
        Band.B5 -> 32 to 177
        Band.B6 -> 1 to 233
        Band.ALL -> 0 to 1
    }
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(70.dp)
        ) {
            val span = (maxCh - minCh).toFloat()
            val barW = (size.width / (span + 1)).coerceIn(3f, 18.dp.toPx()).let {
                if (band == Band.B24) size.width / 16 else it.coerceAtLeast(4.dp.toPx())
            }
            networks.forEach { n ->
                val x = (n.channel - minCh) / span * (size.width - barW)
                val strength = ((n.rssi + 100).coerceIn(0, 70) / 70f)
                val h = strength * size.height
                drawRect(
                    signalColor(n.rssi).copy(alpha = 0.75f),
                    Offset(x, size.height - h),
                    Size(barW, h),
                )
            }
            drawRect(Lcars.Violet, Offset(0f, size.height - 2), Size(size.width, 2f))
        }
        Row(Modifier.fillMaxWidth()) {
            LText("CH $minCh", size = 12.sp, color = Lcars.Periwinkle, modifier = Modifier.weight(1f))
            LText("Kanalbelegung", size = 12.sp, color = Lcars.Periwinkle, modifier = Modifier.weight(1f))
            LText("CH $maxCh", size = 12.sp, color = Lcars.Periwinkle)
        }
    }
}
