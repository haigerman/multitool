package me.winterkind.multitool.wifi

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Band(val label: String) { ALL("Alle"), B24("2,4 GHz"), B5("5 GHz"), B6("6 GHz") }

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val channel: Int,
    val band: Band,
    val widthMhz: Int,
    val security: String,
    val standard: String?,
)

data class WifiState(
    val networks: List<WifiNetwork> = emptyList(),
    val band: Band = Band.ALL,
    val scanning: Boolean = false,
    val lastScanMs: Long? = null,
    val wifiEnabled: Boolean = true,
    val locationEnabled: Boolean = true,
    val message: String? = null,
) {
    val visible get() = if (band == Band.ALL) networks else networks.filter { it.band == band }
}

class WifiViewModel(app: Application) : AndroidViewModel(app) {
    private val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val location = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _state = MutableStateFlow(WifiState())
    val state: StateFlow<WifiState> = _state.asStateFlow()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
            readResults(if (updated) null else "Android hat den Scan gedrosselt – zeige letzte Ergebnisse")
        }
    }

    fun selectBand(band: Band) = _state.update { it.copy(band = band) }

    /** Called once permissions are granted. */
    fun onPermissionsGranted() {
        if (!registered) {
            ContextCompat.registerReceiver(
                getApplication(), receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
        readResults(null)
        scan()
    }

    @Suppress("DEPRECATION")
    fun scan() {
        val wifiOn = wifi.isWifiEnabled
        val locOn = LocationManagerCompat.isLocationEnabled(location)
        _state.update { it.copy(wifiEnabled = wifiOn, locationEnabled = locOn) }
        if (!wifiOn) {
            _state.update { it.copy(message = "WLAN ist ausgeschaltet") }
            return
        }
        // startScan() is deprecated but still the only way to request a fresh scan. Android limits
        // foreground apps to 4 scans per 2 minutes; when throttled it returns false.
        val started = wifi.startScan()
        _state.update {
            it.copy(
                scanning = started,
                message = if (started) null else "Scan gedrosselt (max. 4 Scans / 2 Min.) – zeige letzte Ergebnisse",
            )
        }
        if (!started) readResults(_state.value.message)
    }

    @SuppressLint("MissingPermission")
    private fun readResults(message: String?) {
        val results = try {
            wifi.scanResults
        } catch (e: SecurityException) {
            _state.update { it.copy(scanning = false, message = "Berechtigung fehlt") }
            return
        }
        val list = results.map { it.toNetwork() }.sortedByDescending { it.rssi }
        _state.update {
            it.copy(
                networks = list,
                scanning = false,
                lastScanMs = System.currentTimeMillis(),
                message = message ?: it.message.takeIf { _ -> list.isEmpty() },
            )
        }
    }

    override fun onCleared() {
        if (registered) getApplication<Application>().unregisterReceiver(receiver)
    }
}

@Suppress("DEPRECATION")
private fun ScanResult.toNetwork(): WifiNetwork {
    val name = if (Build.VERSION.SDK_INT >= 33) {
        wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
    } else SSID ?: ""
    val f = frequency
    val band = when (f) {
        in 2400..2500 -> Band.B24
        in 5925..7125 -> Band.B6
        in 4900..5900 -> Band.B5
        else -> Band.ALL
    }
    val channel = when (band) {
        Band.B24 -> if (f == 2484) 14 else (f - 2407) / 5
        Band.B5 -> (f - 5000) / 5
        Band.B6 -> if (f == 5935) 2 else (f - 5950) / 5
        Band.ALL -> 0
    }
    val width = when (channelWidth) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> 20
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
        5 -> 320 // CHANNEL_WIDTH_320MHZ (API 33)
        else -> 20
    }
    val caps = capabilities ?: ""
    val security = when {
        caps.contains("SAE") && caps.contains("PSK") -> "WPA2/WPA3"
        caps.contains("SAE") -> "WPA3"
        caps.contains("OWE") -> "OWE"
        caps.contains("EAP") -> "WPA-Enterprise"
        caps.contains("RSN") || caps.contains("WPA2") -> "WPA2"
        caps.contains("WPA") -> "WPA"
        caps.contains("WEP") -> "WEP"
        else -> "Offen"
    }
    val standard = if (Build.VERSION.SDK_INT >= 30) {
        when (wifiStandard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11a/b/g"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
            ScanResult.WIFI_STANDARD_11AX -> if (band == Band.B6) "Wi-Fi 6E" else "Wi-Fi 6"
            8 -> "Wi-Fi 7" // WIFI_STANDARD_11BE (API 33)
            else -> null
        }
    } else null
    return WifiNetwork(
        ssid = name.ifBlank { "‹verborgen›" },
        bssid = BSSID ?: "",
        rssi = level,
        frequency = f,
        channel = channel,
        band = band,
        widthMhz = width,
        security = security,
        standard = standard,
    )
}
