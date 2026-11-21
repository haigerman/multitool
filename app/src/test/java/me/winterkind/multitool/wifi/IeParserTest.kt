package me.winterkind.multitool.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IeParserTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** WPS TLV: type (2 bytes BE), length (2 bytes BE), value. */
    private fun tlv(type: Int, value: ByteArray) =
        bytes(type shr 8, type and 0xFF, value.size shr 8, value.size and 0xFF) + value

    /** A typical Wi-Fi 6 router beacon (FRITZ!Box-like, Broadcom radio). */
    fun sampleIes(): List<RawIe> {
        val wps = bytes(0x00, 0x50, 0xF2, 0x04) +
            tlv(0x1044, bytes(0x02)) +
            tlv(0x1041, bytes(0x00)) +
            tlv(0x1021, "AVM".toByteArray()) +
            tlv(0x1023, "FRITZ!Box".toByteArray()) +
            tlv(0x1024, "7590 AX".toByteArray()) +
            tlv(0x1011, "FRITZ!Box 7590 AX".toByteArray())
        val rsn = bytes(
            0x01, 0x00,                   // version
            0x00, 0x0F, 0xAC, 0x04,       // group CCMP
            0x01, 0x00, 0x00, 0x0F, 0xAC, 0x04, // 1 pairwise: CCMP
            0x02, 0x00, 0x00, 0x0F, 0xAC, 0x02, 0x00, 0x0F, 0xAC, 0x08, // AKM: PSK, SAE
            0x80, 0x00,                   // RSN caps: MFPC
        )
        val htCap = ByteArray(26).also { it[3] = 0xFF.toByte(); it[4] = 0xFF.toByte() } // 2 streams
        val vhtCap = bytes(0x00, 0x00, 0x00, 0x00, 0xFA, 0xFF, 0, 0, 0, 0, 0, 0) // MCS 0-9, 2 SS
        val heCap = ByteArray(21).also {
            it[6] = 0x0C // 40/80 + 160 MHz in 5 GHz
            it[17] = 0xFA.toByte(); it[18] = 0xFF.toByte() // 2 SS
        }
        return listOf(
            RawIe(0, 0, "Intrepid".toByteArray()),
            RawIe(7, 0, "DE ".toByteArray()),
            RawIe(11, 0, bytes(0x05, 0x00, 0x40, 0x00, 0x00)), // 5 stations, 64/255 busy
            RawIe(45, 0, htCap),
            RawIe(48, 0, rsn),
            RawIe(54, 0, bytes(0x12, 0x34, 0x00)),
            RawIe(70, 0, bytes(0, 0, 0, 0, 0)),
            RawIe(127, 0, bytes(0x00, 0x00, 0x08, 0x00)),
            RawIe(191, 0, vhtCap),
            RawIe(255, 35, heCap),
            RawIe(255, 36, bytes(0x00, 0x00, 0x00, 0x2A)),
            RawIe(221, 0, wps),
            RawIe(221, 0, bytes(0x00, 0x10, 0x18, 0x02, 0x00)), // Broadcom
            RawIe(221, 0, bytes(0x00, 0x50, 0xF2, 0x02, 0x01)), // WMM
        )
    }

    @Test
    fun parsesTypicalRouterBeacon() {
        val info = IeParser.parse(sampleIes())
        assertTrue(info.available)
        assertEquals("DE", info.country)
        assertEquals(5, info.stationCount)
        assertEquals(64 / 255f, info.channelUtilization!!, 0.001f)
        assertEquals(2, info.htStreams)
        assertEquals(2, info.vhtStreams)
        assertEquals(9, info.vhtMaxMcs)
        assertEquals(2, info.heStreams)
        assertEquals(2, info.apStreams)
        assertTrue(info.hasHe)
        assertFalse(info.hasEht)
        assertTrue(info.supports160)
        assertEquals(42, info.bssColor)
        assertTrue(info.fastRoaming11r)
        assertTrue(info.radioMeasurement11k)
        assertTrue(info.bssTransition11v)
        assertTrue(info.wmm)

        val wps = assertNotNull(info.wps).let { info.wps!! }
        assertEquals("AVM", wps.manufacturer)
        assertEquals("FRITZ!Box", wps.modelName)
        assertEquals("7590 AX", wps.modelNumber)
        assertEquals("FRITZ!Box 7590 AX", wps.deviceName)
        assertEquals(true, wps.configured)
        assertFalse(wps.selectedRegistrar)

        val rsn = info.rsn!!
        assertEquals("CCMP-128 (AES)", rsn.groupCipher)
        assertEquals(listOf("CCMP-128 (AES)"), rsn.pairwiseCiphers)
        assertEquals(listOf("PSK (WPA2-Personal)", "SAE (WPA3-Personal)"), rsn.akms)
        assertTrue(rsn.mfpCapable)
        assertFalse(rsn.mfpRequired)

        assertTrue("001018" in info.vendorOuis)
        assertEquals("Broadcom", IeParser.CHIPSET_OUIS["001018"])
    }

    @Test
    fun emptyAndBrokenElementsDoNotCrash() {
        assertFalse(IeParser.parse(emptyList()).available)
        val broken = listOf(
            RawIe(11, 0, bytes(0x01)),
            RawIe(48, 0, bytes(0x01)),
            RawIe(191, 0, bytes()),
            RawIe(221, 0, bytes(0x00, 0x50, 0xF2, 0x04, 0x10, 0x21, 0x00, 0x50)), // length past end
            RawIe(255, 35, bytes(1, 2, 3)),
        )
        val info = IeParser.parse(broken)
        assertTrue(info.available)
        assertEquals(null, info.stationCount)
    }

    @Test
    fun throughputEstimateIsPlausible() {
        val ie = IeParser.parse(sampleIes())
        val n = WifiNetwork(
            "Intrepid", "38:10:d5:00:00:01", -52, 5500, 100, Band.B5, 80, "WPA2/WPA3", "Wi-Fi 6",
            centerFreq0 = 5530, ie = ie,
        )
        val est = ThroughputEstimator.estimate(n, listOf(n))
        assertEquals("Wi-Fi 6 (802.11ax)", est.generation)
        assertEquals(1201.0, est.apPhyMbps, 0.1)   // 2 × 600.5
        assertEquals(1201.0, est.linkPhyMbps, 0.1)
        // 1201 × 0.85 (−52 dBm) × 0.70 (MAC) × (1 − 0.25 busy) ≈ 536
        assertEquals(536.0, est.expectedMbps, 5.0)

        val weak = n.copy(rssi = -78)
        assertTrue(ThroughputEstimator.estimate(weak, listOf(weak)).expectedMbps < 120)
    }

    @Test
    fun overlapUsesChannelWidth() {
        val a = WifiNetwork("A", "aa:00:00:00:00:01", -50, 5500, 100, Band.B5, 80, "WPA2", null, centerFreq0 = 5530)
        val b = WifiNetwork("B", "aa:00:00:00:00:02", -60, 5580, 116, Band.B5, 20, "WPA2", null) // 5570–5590, outside 5490–5570
        val c = WifiNetwork("C", "aa:00:00:00:00:03", -60, 5540, 108, Band.B5, 20, "WPA2", null) // inside
        assertEquals(listOf(c), ThroughputEstimator.overlapping(a, listOf(a, b, c)))
    }

    @Test
    fun locallyAdministeredMac() {
        assertTrue(OuiDatabase.isLocallyAdministered("3a:10:d5:00:00:01"))
        assertFalse(OuiDatabase.isLocallyAdministered("38:10:d5:00:00:01"))
        assertEquals("3810D5", OuiDatabase.ouiOf("38:10:d5:00:00:01"))
    }
}
