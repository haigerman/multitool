package me.winterkind.multitool

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import me.winterkind.multitool.clock.ClockScreen
import me.winterkind.multitool.clock.ClockState
import me.winterkind.multitool.clock.ClockViewModel
import me.winterkind.multitool.clock.SntpResult
import me.winterkind.multitool.speedtest.Phase
import me.winterkind.multitool.speedtest.PingResult
import me.winterkind.multitool.speedtest.SpeedtestScreen
import me.winterkind.multitool.speedtest.SpeedtestState
import me.winterkind.multitool.speedtest.SpeedtestViewModel
import me.winterkind.multitool.wifi.Band
import me.winterkind.multitool.wifi.WifiNetwork
import me.winterkind.multitool.wifi.WifiScreen
import me.winterkind.multitool.wifi.WifiState
import org.junit.Rule
import org.junit.Test

/** Renders the screens with fixed sample data (run: ./gradlew recordPaparazziDebug). */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false)

    @Test
    fun speedtest() = paparazzi.snapshot {
        LcarsFrame(Section.SPEEDTEST, {}, 0, false) {
            SpeedtestScreen(
                SpeedtestViewModel(),
                SpeedtestState(
                    phase = Phase.UPLOAD, progress = 0.6f, liveMbps = 38.4,
                    pingSamples = listOf(21.0, 19.5, 22.3, 20.1, 25.0, 19.9, 20.4, 21.7, 20.0, 19.8, 23.1, 20.6),
                    ping = PingResult(20.5, 1.8), downloadMbps = 247.3,
                ),
            )
        }
    }

    @Test
    fun wifi() = paparazzi.snapshot {
        val nets = listOf(
            WifiNetwork("Intrepid-Bridge", "aa:bb:cc:00:11:22", -48, 5500, 100, Band.B5, 80, "WPA3", "Wi-Fi 6"),
            WifiNetwork("FRITZ!Box 7590", "aa:bb:cc:00:11:23", -61, 2437, 6, Band.B24, 20, "WPA2", "Wi-Fi 4"),
            WifiNetwork("Holodeck", "aa:bb:cc:00:11:24", -70, 6115, 33, Band.B6, 160, "WPA3", "Wi-Fi 6E"),
            WifiNetwork("‹verborgen›", "aa:bb:cc:00:11:25", -84, 2462, 11, Band.B24, 40, "Offen", null),
        )
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) = Unit
            }
        }
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        LcarsFrame(Section.WIFI, {}, 0, false) {
            WifiScreen(
                WifiState(networks = nets, band = Band.ALL, lastScanMs = 1_700_000_000_000),
                {}, {}, {}, permissionCheck = { true },
            )
        }
        }
    }

    @Test
    fun clock() = paparazzi.snapshot {
        val r = SntpResult("ptbtime1.ptb.de", "192.53.103.108", 1234, 18, 1, "PTB")
        val state = ClockState(offsetMs = 1234, synced = true, last = r, lastSyncWallMs = 1_700_000_000_000,
            samples = listOf(r, r.copy(server = "ptbtime2.ptb.de", offsetMs = 1236, roundTripMs = 21)))
        LcarsFrame(Section.CLOCK, {}, state.offsetMs, true) { ClockScreen(ClockViewModel(), state) }
    }
}
