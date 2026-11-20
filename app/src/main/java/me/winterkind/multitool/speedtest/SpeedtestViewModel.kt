package me.winterkind.multitool.speedtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.UnknownHostException

enum class Phase { IDLE, PING, DOWNLOAD, UPLOAD, DONE, ERROR }

data class SpeedtestState(
    val phase: Phase = Phase.IDLE,
    val progress: Float = 0f,
    val liveMbps: Double = 0.0,
    val pingSamples: List<Double> = emptyList(),
    val ping: PingResult? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val error: String? = null,
) {
    val running get() = phase == Phase.PING || phase == Phase.DOWNLOAD || phase == Phase.UPLOAD
}

class SpeedtestViewModel : ViewModel() {
    private val engine = SpeedtestEngine()
    private val _state = MutableStateFlow(SpeedtestState())
    val state: StateFlow<SpeedtestState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (_state.value.running) return
        _state.value = SpeedtestState(phase = Phase.PING)
        job = viewModelScope.launch {
            try {
                val ping = engine.ping { ms ->
                    _state.update { it.copy(pingSamples = it.pingSamples + ms, progress = (it.pingSamples.size + 1) / 12f) }
                }
                _state.update { it.copy(ping = ping, phase = Phase.DOWNLOAD, progress = 0f, liveMbps = 0.0) }

                val down = engine.download { mbps, p -> _state.update { it.copy(liveMbps = mbps, progress = p) } }
                _state.update { it.copy(downloadMbps = down, phase = Phase.UPLOAD, progress = 0f, liveMbps = 0.0) }

                val up = engine.upload { mbps, p -> _state.update { it.copy(liveMbps = mbps, progress = p) } }
                _state.update { it.copy(uploadMbps = up, phase = Phase.DONE, progress = 1f, liveMbps = 0.0) }
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = Phase.IDLE, liveMbps = 0.0) }
                throw e
            } catch (e: Throwable) {
                val msg = when (e) {
                    is UnknownHostException -> "Server ${SpeedtestServer.HOST} nicht erreichbar"
                    else -> e.message ?: e.javaClass.simpleName
                }
                _state.update { it.copy(phase = Phase.ERROR, error = msg, liveMbps = 0.0) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
