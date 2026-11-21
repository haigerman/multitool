package me.winterkind.multitool.wifi

import android.content.Context
import java.util.zip.GZIPInputStream

/**
 * Manufacturer lookup by MAC prefix (IEEE MA-L registry, ~40,000 entries, from the npm package
 * oui-data, BSD-2-Clause). Loaded lazily from assets on first use.
 */
object OuiDatabase {
    @Volatile
    private var map: Map<String, String>? = null

    /** Call from a background thread. */
    fun load(context: Context): Map<String, String> {
        map?.let { return it }
        synchronized(this) {
            map?.let { return it }
            val m = HashMap<String, String>(48_000)
            context.assets.open("oui.tsv.gz").use { raw ->
                GZIPInputStream(raw).bufferedReader().forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab == 6) m[line.substring(0, 6)] = line.substring(7)
                }
            }
            map = m
            return m
        }
    }

    fun lookup(oui: String): String? = map?.get(oui.uppercase())

    /** "AA:BB:CC:.." → "AABBCC" */
    fun ouiOf(mac: String): String = mac.replace(":", "").replace("-", "").take(6).uppercase()

    /** Locally administered bit: set for virtual / randomized BSSIDs (guest networks, mesh). */
    fun isLocallyAdministered(mac: String): Boolean =
        ouiOf(mac).take(2).toIntOrNull(16)?.let { it and 0x02 != 0 } ?: false
}
