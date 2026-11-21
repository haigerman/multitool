package me.winterkind.multitool.wifi

import kotlin.math.max
import kotlin.math.min

/**
 * Rough throughput estimate for a network. All values in Mbit/s.
 *
 * 1. PHY rate: highest MCS of the Wi-Fi generation × channel width × spatial streams
 *    (limited to 2 streams, typical for a smartphone).
 * 2. Signal: the usable MCS drops with the RSSI.
 * 3. MAC overhead: real TCP throughput is roughly 60–70 % of the PHY rate.
 * 4. Airtime: shared with other networks on the same channel (BSS Load if the AP announces it,
 *    otherwise estimated from the overlapping networks in the scan).
 */
data class ThroughputEstimate(
    val generation: String,
    val apPhyMbps: Double,
    val linkPhyMbps: Double,
    val signalFactor: Double,
    val airtimeShare: Double,
    val expectedMbps: Double,
    val apStreams: Int,
    val deviceStreams: Int,
    val airtimeSource: String,
)

object ThroughputEstimator {
    const val DEVICE_STREAMS = 2

    /** Per-stream PHY rate at the top MCS, short guard interval. */
    private fun perStream(gen: Gen, width: Int): Double = when (gen) {
        Gen.EHT -> when (width) { 20 -> 172.1; 40 -> 344.1; 80 -> 720.6; 160 -> 1441.2; else -> 2882.4 }
        Gen.HE -> when (width) { 20 -> 143.4; 40 -> 286.8; 80 -> 600.5; else -> 1201.0 }
        Gen.VHT -> when (width) { 20 -> 86.7; 40 -> 200.0; 80 -> 433.3; else -> 866.7 }
        Gen.HT -> if (width >= 40) 150.0 else 72.2
        Gen.LEGACY -> 54.0
    }

    enum class Gen(val label: String, val macEfficiency: Double) {
        LEGACY("802.11a/g", 0.55),
        HT("Wi-Fi 4 (802.11n)", 0.62),
        VHT("Wi-Fi 5 (802.11ac)", 0.65),
        HE("Wi-Fi 6 (802.11ax)", 0.70),
        EHT("Wi-Fi 7 (802.11be)", 0.72),
    }

    fun generationOf(n: WifiNetwork): Gen = when {
        n.ie.hasEht || n.standard == "Wi-Fi 7" -> Gen.EHT
        n.ie.hasHe || n.standard?.startsWith("Wi-Fi 6") == true -> Gen.HE
        (n.ie.hasVht && n.band != Band.B24) || n.standard == "Wi-Fi 5" -> Gen.VHT
        n.ie.hasHt || n.standard == "Wi-Fi 4" -> Gen.HT
        else -> Gen.LEGACY
    }

    /** Fraction of the top MCS rate still usable at this signal level. */
    fun signalFactor(rssi: Int): Double = when {
        rssi >= -50 -> 1.0
        rssi >= -55 -> 0.85
        rssi >= -60 -> 0.70
        rssi >= -65 -> 0.55
        rssi >= -70 -> 0.40
        rssi >= -75 -> 0.25
        rssi >= -80 -> 0.12
        rssi >= -85 -> 0.06
        else -> 0.02
    }

    /** Networks in the scan whose channels overlap with [n] (excluding [n] itself). */
    fun overlapping(n: WifiNetwork, all: List<WifiNetwork>): List<WifiNetwork> {
        val (lo, hi) = n.frequencyRange()
        return all.filter { o ->
            o.bssid != n.bssid && o.band == n.band && o.frequencyRange().let { (l, h) -> l < hi && h > lo }
        }
    }

    fun estimate(n: WifiNetwork, all: List<WifiNetwork>): ThroughputEstimate {
        val gen = generationOf(n)
        val width = if (gen == Gen.LEGACY) 20 else n.widthMhz
        val apStreams = (n.ie.apStreams ?: if (gen == Gen.LEGACY) 1 else 2).coerceIn(1, 8)
        val perStream = perStream(gen, width)
        val apPhy = perStream * (if (gen == Gen.LEGACY) 1 else apStreams)
        val linkStreams = if (gen == Gen.LEGACY) 1 else min(apStreams, DEVICE_STREAMS)
        val linkPhy = perStream * linkStreams

        val util = n.ie.channelUtilization
        val (share, source) = if (util != null) {
            max(0.1, 1.0 - util) to "BSS-Load des Access Points (${(util * 100).toInt()} % belegt)"
        } else {
            // Strong neighbours cost more airtime than weak ones.
            val load = overlapping(n, all).sumOf { o -> if (o.rssi >= -70) 0.35 else if (o.rssi >= -82) 0.15 else 0.05 }
            (1.0 / (1.0 + load)) to "geschätzt aus ${overlapping(n, all).size} überlappenden Netzen"
        }
        val sig = signalFactor(n.rssi)
        val expected = linkPhy * sig * gen.macEfficiency * share
        return ThroughputEstimate(gen.label, apPhy, linkPhy, sig, share, expected, apStreams, linkStreams, source)
    }
}

/** Occupied frequency range in MHz, from the centre frequency and channel width. */
fun WifiNetwork.frequencyRange(): Pair<Int, Int> {
    val center = if (centerFreq0 > 0 && widthMhz > 20) centerFreq0 else frequency
    val half = widthMhz / 2
    return (center - half) to (center + half)
}
