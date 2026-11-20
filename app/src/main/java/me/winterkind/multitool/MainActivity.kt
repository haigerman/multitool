package me.winterkind.multitool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.winterkind.multitool.clock.ClockScreen
import me.winterkind.multitool.clock.ClockViewModel
import me.winterkind.multitool.speedtest.SpeedtestScreen
import me.winterkind.multitool.speedtest.SpeedtestViewModel
import me.winterkind.multitool.wifi.WifiScreen
import me.winterkind.multitool.wifi.WifiViewModel

enum class Section(val code: String, val title: String) {
    SPEEDTEST("01-Speed", "Speedtest"),
    WIFI("02-WLAN", "WiFi Scanner"),
    CLOCK("03-Zeit", "Atomuhr"),
}

class MainActivity : ComponentActivity() {
    private val clockVm: ClockViewModel by viewModels()
    private val speedVm: SpeedtestViewModel by viewModels()
    private val wifiVm: WifiViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    @Composable
    private fun App() {
        var section by rememberSaveable { mutableStateOf(Section.SPEEDTEST) }
        BackHandler(section != Section.SPEEDTEST) { section = Section.SPEEDTEST }
        val clock by clockVm.state.collectAsStateWithLifecycle()

        LcarsFrame(section, { section = it }, clock.offsetMs, clock.synced) {
            when (section) {
                Section.SPEEDTEST -> {
                    val s by speedVm.state.collectAsStateWithLifecycle()
                    SpeedtestScreen(speedVm, s)
                }
                Section.WIFI -> {
                    val s by wifiVm.state.collectAsStateWithLifecycle()
                    WifiScreen(wifiVm, s)
                }
                Section.CLOCK -> ClockScreen(clockVm, clock)
            }
        }
    }
}
