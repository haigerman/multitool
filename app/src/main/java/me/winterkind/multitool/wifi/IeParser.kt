package me.winterkind.multitool.wifi

/** One 802.11 information element as delivered by ScanResult (API 30+). */
class RawIe(val id: Int, val idExt: Int, val bytes: ByteArray)

data class WpsInfo(
    val manufacturer: String? = null,
    val modelName: String? = null,
    val modelNumber: String? = null,
    val deviceName: String? = null,
    val configured: Boolean? = null,
    /** WPS push button / PIN currently active on the router. */
    val selectedRegistrar: Boolean = false,
)

data class RsnInfo(
    val groupCipher: String? = null,
    val pairwiseCiphers: List<String> = emptyList(),
    val akms: List<String> = emptyList(),
    val mfpRequired: Boolean = false,
    val mfpCapable: Boolean = false,
)

/** Everything we can learn from the information elements of a beacon / probe response. */
data class IeInfo(
    val available: Boolean = false,
    val country: String? = null,
    val stationCount: Int? = null,
    /** 0..1, from the BSS Load element. */
    val channelUtilization: Float? = null,
    val htStreams: Int? = null,
    val vhtStreams: Int? = null,
    val heStreams: Int? = null,
    val hasHt: Boolean = false,
    val hasVht: Boolean = false,
    val hasHe: Boolean = false,
    val hasEht: Boolean = false,
    val vhtMaxMcs: Int? = null,
    val supports160: Boolean = false,
    val bssColor: Int? = null,
    val fastRoaming11r: Boolean = false,
    val radioMeasurement11k: Boolean = false,
    val bssTransition11v: Boolean = false,
    val interworking: Boolean = false,
    val easyMesh: Boolean = false,
    val wmm: Boolean = false,
    val wps: WpsInfo? = null,
    val rsn: RsnInfo? = null,
    /** Distinct OUIs of vendor-specific elements (hex, upper case, no separators). */
    val vendorOuis: List<String> = emptyList(),
) {
    /** Highest stream count announced by any capability element. */
    val apStreams: Int? get() = listOfNotNull(heStreams, vhtStreams, htStreams).maxOrNull()
}

object IeParser {
    private const val OUI_MICROSOFT = "0050F2"
    private const val OUI_WFA = "506F9A"

    /** Vendor-IE OUIs that usually reveal the radio chipset of the access point. */
    val CHIPSET_OUIS = mapOf(
        "001018" to "Broadcom",
        "00037F" to "Qualcomm Atheros",
        "00A0C6" to "Qualcomm",
        "8CFDF0" to "Qualcomm",
        "000CE7" to "MediaTek",
        "000C43" to "MediaTek (Ralink)",
        "00E04C" to "Realtek",
        "005043" to "Marvell",
        "002686" to "Quantenna",
    )

    fun parse(elements: List<RawIe>): IeInfo {
        if (elements.isEmpty()) return IeInfo()
        var info = IeInfo(available = true)
        val ouis = linkedSetOf<String>()
        for (ie in elements) {
            val b = ie.bytes
            runCatching {
                when (ie.id) {
                    7 -> if (b.size >= 2) {
                        val cc = String(b, 0, 2, Charsets.US_ASCII).trim()
                        if (cc.all { it.isLetter() }) info = info.copy(country = cc.uppercase())
                    }
                    11 -> if (b.size >= 3) info = info.copy(
                        stationCount = u16le(b, 0),
                        channelUtilization = u8(b, 2) / 255f,
                    )
                    45 -> if (b.size >= 7) info = info.copy(
                        hasHt = true,
                        htStreams = (3..6).count { b[it].toInt() != 0 }.takeIf { it > 0 },
                    )
                    48 -> info = info.copy(rsn = parseRsn(b))
                    54 -> info = info.copy(fastRoaming11r = true)
                    70 -> info = info.copy(radioMeasurement11k = true)
                    127 -> {
                        if (b.size >= 3 && (u8(b, 2) shr 3) and 1 == 1) info = info.copy(bssTransition11v = true)
                        if (b.size >= 4 && (u8(b, 3) shr 7) and 1 == 1) info = info.copy(interworking = true)
                    }
                    191 -> if (b.size >= 6) {
                        val capInfo = u8(b, 0)
                        val widthSet = (capInfo shr 2) and 0x3
                        val map = u16le(b, 4)
                        val streams = (0 until 8).count { (map shr (it * 2)) and 0x3 != 3 }
                        val firstMcs = map and 0x3
                        info = info.copy(
                            hasVht = true,
                            vhtStreams = streams.takeIf { it > 0 },
                            vhtMaxMcs = if (firstMcs == 3) null else 7 + firstMcs,
                            supports160 = info.supports160 || widthSet != 0,
                        )
                    }
                    221 -> if (b.size >= 4) {
                        val oui = hex(b, 0, 3)
                        val type = u8(b, 3)
                        when {
                            oui == OUI_MICROSOFT && type == 4 -> info = info.copy(wps = parseWps(b, 4))
                            oui == OUI_MICROSOFT && type == 2 -> info = info.copy(wmm = true)
                            oui == OUI_WFA && type == 0x1B -> info = info.copy(easyMesh = true)
                        }
                        ouis += oui
                    }
                    255 -> when (ie.idExt) {
                        35 -> if (b.size >= 19) {
                            val phy0 = u8(b, 6)
                            val map = u16le(b, 17)
                            val streams = (0 until 8).count { (map shr (it * 2)) and 0x3 != 3 }
                            info = info.copy(
                                hasHe = true,
                                heStreams = streams.takeIf { it > 0 },
                                supports160 = info.supports160 || (phy0 shr 3) and 1 == 1,
                            )
                        }
                        36 -> if (b.size >= 4) info = info.copy(hasHe = true, bssColor = u8(b, 3) and 0x3F)
                        108 -> info = info.copy(hasEht = true)
                    }
                }
            }
        }
        return info.copy(vendorOuis = ouis.toList())
    }

    private fun parseRsn(b: ByteArray): RsnInfo {
        var p = 2 // version
        fun suite(): Pair<String, Int>? {
            if (p + 4 > b.size) return null
            val oui = hex(b, p, 3)
            val type = u8(b, p + 3)
            p += 4
            return oui to type
        }
        val group = suite()?.let { cipherName(it) }
        val pairwise = mutableListOf<String>()
        val akms = mutableListOf<String>()
        if (p + 2 <= b.size) {
            val n = u16le(b, p); p += 2
            repeat(n) { suite()?.let { pairwise += cipherName(it) } }
        }
        if (p + 2 <= b.size) {
            val n = u16le(b, p); p += 2
            repeat(n) { suite()?.let { akms += akmName(it) } }
        }
        var mfpr = false
        var mfpc = false
        if (p + 2 <= b.size) {
            val caps = u16le(b, p)
            mfpr = (caps shr 6) and 1 == 1
            mfpc = (caps shr 7) and 1 == 1
        }
        return RsnInfo(group, pairwise.distinct(), akms.distinct(), mfpr, mfpc)
    }

    private fun cipherName(s: Pair<String, Int>) = if (s.first != "000FAC") "Hersteller-spezifisch" else when (s.second) {
        1 -> "WEP-40"; 2 -> "TKIP"; 4 -> "CCMP-128 (AES)"; 5 -> "WEP-104"
        6 -> "BIP-CMAC-128"; 8 -> "GCMP-128"; 9 -> "GCMP-256"; 10 -> "CCMP-256"
        else -> "Typ ${s.second}"
    }

    private fun akmName(s: Pair<String, Int>) = if (s.first != "000FAC") "Hersteller-spezifisch" else when (s.second) {
        1 -> "802.1X (Enterprise)"; 2 -> "PSK (WPA2-Personal)"; 3 -> "FT-802.1X"; 4 -> "FT-PSK"
        5 -> "802.1X-SHA256"; 6 -> "PSK-SHA256"; 8 -> "SAE (WPA3-Personal)"; 9 -> "FT-SAE"
        11 -> "802.1X Suite-B"; 12 -> "802.1X Suite-B-192"; 18 -> "OWE (Enhanced Open)"
        24 -> "SAE-EXT-KEY (WPA3)"; 25 -> "FT-SAE-EXT-KEY"
        else -> "Typ ${s.second}"
    }

    /** WPS attributes are big-endian TLVs (type 2 bytes, length 2 bytes). */
    private fun parseWps(b: ByteArray, start: Int): WpsInfo {
        var w = WpsInfo()
        var p = start
        while (p + 4 <= b.size) {
            val type = (u8(b, p) shl 8) or u8(b, p + 1)
            val len = (u8(b, p + 2) shl 8) or u8(b, p + 3)
            p += 4
            if (p + len > b.size) break
            fun str() = String(b, p, len, Charsets.UTF_8).trim { it <= ' ' }.takeIf { it.isNotEmpty() }
            when (type) {
                0x1021 -> w = w.copy(manufacturer = str())
                0x1023 -> w = w.copy(modelName = str())
                0x1024 -> w = w.copy(modelNumber = str())
                0x1011 -> w = w.copy(deviceName = str())
                0x1044 -> if (len >= 1) w = w.copy(configured = u8(b, p) == 2)
                0x1041 -> if (len >= 1) w = w.copy(selectedRegistrar = u8(b, p) == 1)
            }
            p += len
        }
        return w
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16le(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun hex(b: ByteArray, from: Int, n: Int) = (from until from + n).joinToString("") { "%02X".format(u8(b, it)) }
}
