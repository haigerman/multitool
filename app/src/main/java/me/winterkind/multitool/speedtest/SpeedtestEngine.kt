package me.winterkind.multitool.speedtest

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import me.winterkind.multitool.BuildConfig

/** Test server. See server/README.md for the nginx setup on home.winterkind.me. */
object SpeedtestServer {
    const val BASE_URL = "https://home.winterkind.me/speedtest"
    const val HOST = "home.winterkind.me"
    const val LOCATION = "Oracle Cloud, Niederlande"
}

class SpeedtestException(message: String) : Exception(message)

data class PingResult(val pingMs: Double, val jitterMs: Double)

/**
 * HTTP based speed test against our own nginx endpoint:
 *  - GET  /ping          → empty 204, used for latency (HTTP round trip on a warm connection)
 *  - GET  /download.bin  → large random file, streamed over several parallel connections
 *  - POST /upload        → nginx discards the body and answers 204
 */
class SpeedtestEngine(
    private val streams: Int = 4,
    private val durationMs: Long = 10_000,
    private val warmupMs: Long = 2_000,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        // Every request carries the access key; without it the server answers 404.
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-Intrepid-Key", BuildConfig.SPEEDTEST_KEY).build())
        }
        .build()

    private fun url(path: String) = "${SpeedtestServer.BASE_URL}/$path?r=${Random.nextLong().toULong()}"

    suspend fun ping(samples: Int = 12, onSample: (Double) -> Unit): PingResult = withContext(Dispatchers.IO) {
        val times = mutableListOf<Double>()
        // First request opens TCP+TLS; it is not counted.
        repeat(samples + 1) { i ->
            coroutineContext.ensureActive()
            val req = Request.Builder().url(url("ping")).header("Cache-Control", "no-cache").build()
            val start = SystemClock.elapsedRealtimeNanos()
            client.newCall(req).execute().use { resp ->
                checkResponse(resp.code, "Ping")
                resp.body?.bytes()
            }
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            if (i > 0) {
                times += ms
                onSample(ms)
            }
        }
        val jitter = times.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average()
        PingResult(times.sorted()[times.size / 2], if (jitter.isNaN()) 0.0 else jitter)
    }

    /** Returns Mbit/s; [onProgress] receives (current Mbit/s, progress 0..1) roughly every 200 ms. */
    suspend fun download(onProgress: (Double, Float) -> Unit): Double =
        measure(onProgress) { counter, calls ->
            while (coroutineContext.isActive) {
                val req = Request.Builder().url(url("download.bin"))
                    .header("Accept-Encoding", "identity")
                    .header("Cache-Control", "no-cache")
                    .build()
                val call = client.newCall(req)
                calls += call
                try {
                    call.execute().use { resp ->
                        if (resp.code == 429) {
                            // Server limit (4 connections per IP); old connections are still closing.
                            delay(300)
                            return@use
                        }
                        checkResponse(resp.code, "Download")
                        val source = resp.body!!.source()
                        val buf = ByteArray(64 * 1024)
                        val stream = source.inputStream()
                        while (coroutineContext.isActive) {
                            val n = stream.read(buf)
                            if (n < 0) break
                            counter.addAndGet(n.toLong())
                        }
                    }
                } finally {
                    calls -= call
                }
            }
        }

    suspend fun upload(onProgress: (Double, Float) -> Unit): Double =
        measure(onProgress) { counter, calls ->
            val chunk = Random.nextBytes(256 * 1024)
            val job = coroutineContext[Job]!!
            while (coroutineContext.isActive) {
                // Moderate request size: some servers (Caddy, Apache) answer without reading the
                // whole body and close the connection; a new request then simply follows.
                val total = 16L * 1024 * 1024
                var sent = 0L
                val body = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = total
                    override fun writeTo(sink: BufferedSink) {
                        var left = total
                        while (left > 0) {
                            if (!job.isActive) throw IOException("cancelled")
                            val n = minOf(left, chunk.size.toLong()).toInt()
                            sink.write(chunk, 0, n)
                            sink.flush()
                            counter.addAndGet(n.toLong())
                            sent += n
                            left -= n
                        }
                    }
                }
                val req = Request.Builder().url(url("upload")).post(body).build()
                val call = client.newCall(req)
                calls += call
                try {
                    call.execute().use { resp ->
                        if (resp.code == 429) delay(300) else checkResponse(resp.code, "Upload")
                    }
                } catch (e: IOException) {
                    // Server closed early after receiving data: fine, start the next request.
                    if (sent == 0L || !job.isActive) throw e
                } finally {
                    calls -= call
                }
            }
        }

    private fun checkResponse(code: Int, what: String) {
        when {
            code in 200..299 -> Unit
            code == 404 -> throw SpeedtestException("Zugriff verweigert – Schlüssel passt nicht zum Server")
            code == 429 -> throw SpeedtestException("Server-Limit erreicht – bitte kurz warten und erneut testen")
            else -> throw SpeedtestException("$what: HTTP $code")
        }
    }

    /**
     * Runs [streams] workers for [durationMs]. The first [warmupMs] (TCP slow start) are excluded
     * from the final value; the live value is a sliding one-second average.
     */
    private suspend fun measure(
        onProgress: (Double, Float) -> Unit,
        worker: suspend (AtomicLong, ConcurrentLinkedQueue<Call>) -> Unit,
    ): Double = withContext(Dispatchers.IO) {
        val counter = AtomicLong()
        val calls = ConcurrentLinkedQueue<Call>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        var result = 0.0
        coroutineScope {
            val jobs = List(streams) {
                launch {
                    try {
                        worker(counter, calls)
                    } catch (t: Throwable) {
                        if (isActive) failures += t
                    }
                }
            }
            try {
            val start = SystemClock.elapsedRealtime()
            val history = ArrayDeque<Pair<Long, Long>>()
            var warmBytes = -1L
            var warmTime = 0L
            while (true) {
                delay(200)
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - start
                val bytes = counter.get()
                history.addLast(now to bytes)
                while (history.size > 1 && now - history.first().first > 1000) history.removeFirst()
                val (t0, b0) = history.first()
                val live = if (now > t0) (bytes - b0) * 8.0 / ((now - t0) / 1000.0) / 1e6 else 0.0
                onProgress(live, (elapsed.toFloat() / durationMs).coerceAtMost(1f))
                if (warmBytes < 0 && elapsed >= warmupMs) {
                    warmBytes = bytes; warmTime = now
                }
                if (failures.size >= streams) throw failures.first()
                if (elapsed >= durationMs) {
                    result = if (warmBytes >= 0 && now > warmTime) {
                        (bytes - warmBytes) * 8.0 / ((now - warmTime) / 1000.0) / 1e6
                    } else 0.0
                    break
                }
            }
            } finally {
                jobs.forEach { it.cancel() }
                calls.forEach { it.cancel() }
            }
        }
        if (result == 0.0 && failures.isNotEmpty()) throw failures.first()
        result
    }
}
