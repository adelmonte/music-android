package dev.musicandroid.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "music-android/0.1"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val lastCall = HashMap<String, Long>()

    // Deezer allows 50 requests per 5 seconds; spacing calls to one host keeps
    // parallel lookups (radios, recommendations) inside that.
    private fun throttle(host: String) {
        val gap = if (host.endsWith("deezer.com")) 110L else 0L
        if (gap == 0L) return
        val wait: Long
        synchronized(lastCall) {
            val now = System.currentTimeMillis()
            val next = maxOf(now, (lastCall[host] ?: 0L) + gap)
            lastCall[host] = next
            wait = next - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    fun bytes(url: String, headers: Map<String, String> = emptyMap(), limit: Long = 64L shl 20): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> req.header(k, v) }
        val built = req.build()
        throttle(built.url.host)
        client.newCall(built).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${built.url.host}")
            val body = resp.body
            if (body.contentLength() > limit) throw IOException("response too large")
            return body.bytes()
        }
    }

    fun text(url: String, headers: Map<String, String> = emptyMap()): String = String(bytes(url, headers))

    fun json(url: String, headers: Map<String, String> = emptyMap()): JSONObject = JSONObject(text(url, headers))

    fun jsonArray(url: String, headers: Map<String, String> = emptyMap()): JSONArray = JSONArray(text(url, headers))

    fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /// A URL path segment: spaces as %20, not +.
    fun segment(s: String): String = enc(s).replace("+", "%20")
}

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")
