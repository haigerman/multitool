package me.winterkind.multitool.clock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ClockState(
    /** Correction applied to the displayed clock; 0 until synchronised. */
    val offsetMs: Long = 0,
    val synced: Boolean = false,
    val syncing: Boolean = false,
    val last: SntpResult? = null,
    val lastSyncWallMs: Long? = null,
    val samples: List<SntpResult> = emptyList(),
    val error: String? = null,
)

/** Activity-scoped, so the header clock and the Atomuhr screen share one offset. */
class ClockViewModel : ViewModel() {
    private val _state = MutableStateFlow(ClockState())
    val state: StateFlow<ClockState> = _state.asStateFlow()

    fun sync() {
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true, error = null) }
        viewModelScope.launch {
            val results = mutableListOf<SntpResult>()
            val errors = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                // Two rounds across the PTB servers; the sample with the shortest round trip
                // is the most accurate one (least asymmetric network delay).
                repeat(2) {
                    for (host in SntpClient.PTB_SERVERS) {
                        runCatching { SntpClient.query(host) }
                            .onSuccess { results += it }
                            .onFailure { errors += "$host: ${it.message ?: it.javaClass.simpleName}" }
                    }
                }
            }
            val best = results.minByOrNull { it.roundTripMs }
            _state.update {
                if (best == null) {
                    it.copy(syncing = false, error = errors.firstOrNull() ?: "Keine Antwort von der PTB")
                } else {
                    it.copy(
                        syncing = false,
                        synced = true,
                        offsetMs = best.offsetMs,
                        last = best,
                        lastSyncWallMs = System.currentTimeMillis() + best.offsetMs,
                        samples = results.sortedBy { r -> r.roundTripMs },
                        error = null,
                    )
                }
            }
        }
    }
}
