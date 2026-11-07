package dev.musicandroid.net

import dev.musicandroid.data.Item
import dev.musicandroid.data.Section
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/** Internet radio from Radio Browser: a free, community-run directory of about 50,000 stations. */
object RadioBrowser {
    private val servers = listOf(
        "https://de1.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
    )

    val genres = listOf(
        "Jazz" to "jazz", "Classical" to "classical", "Rock" to "rock", "Electronic" to "electronic",
        "Hip hop" to "hip hop", "Lo-fi" to "lofi", "Ambient" to "ambient", "News" to "news", "Talk" to "talk",
    )

    private const val LIST = "hidebroken=true&order=clickcount&reverse=true&limit=60"

    private fun get(path: String): JSONArray {
        var last: Exception? = null
        for (s in servers) {
            try {
                return Http.jsonArray("$s/json/$path")
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Radio Browser: ${last?.message}")
    }

    fun station(v: JSONObject): Track? {
        val url = v.str("url_resolved").ifEmpty { v.str("url") }
        if (!url.startsWith("http")) return null
        val name = v.str("name").trim().ifEmpty { return null }
        val tags = v.str("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3)
        val about = mutableListOf<String>()
        v.str("countrycode").takeIf { it.isNotEmpty() }?.let { about += it }
        val codec = v.str("codec")
        val bitrate = v.optInt("bitrate")
        if (bitrate > 0) about += "$codec ${bitrate}k".trim() else if (codec.isNotEmpty()) about += codec
        val id = v.str("stationuuid").ifEmpty { url.hashCode().toString(16) }
        return Track(
            key = "radio:$id",
            title = name,
            artist = if (tags.isEmpty()) "Radio" else tags.joinToString(", "),
            album = about.joinToString(" · "),
            art = v.str("favicon"),
            uri = url,
        )
    }

    private fun stations(path: String): List<Track> = runCatching {
        val seen = HashSet<String>()
        // Mirrors of one station often share a name or a stream.
        get(path).objects().mapNotNull(::station).filter { seen.add(it.title.lowercase()) and seen.add(it.uri) }
    }.getOrDefault(emptyList()).also { Store.remember(it) }

    fun search(q: String): List<Section> {
        val out = mutableListOf<Section>()
        stations("stations/search?name=${Http.enc(q)}&$LIST").takeIf { it.isNotEmpty() }?.let { out += Section("Stations: $q", it.map(Item::Song)) }
        stations("stations/bytagexact/${Http.segment(q.lowercase())}?$LIST").takeIf { it.isNotEmpty() }?.let { out += Section("Genre: $q", it.map(Item::Song)) }
        return out
    }

    private val pool = Executors.newFixedThreadPool(6)

    /** Popular, popular in your country, most voted, then genres; fetched in parallel. */
    fun home(): List<Section> {
        val cc = Locale.getDefault().country.uppercase().takeIf { it.length == 2 }
        val wanted = buildList {
            add("Popular" to "stations/topclick/60?hidebroken=true")
            if (cc != null) add("Popular in ${Locale.Builder().setRegion(cc).build().displayCountry}" to "stations/bycountrycodeexact/$cc?$LIST")
            add("Most voted" to "stations/topvote/60?hidebroken=true")
            genres.forEach { (title, tag) -> add(title to "stations/bytagexact/${Http.segment(tag)}?$LIST") }
        }
        return wanted.map { (title, path) -> title to pool.submit<List<Track>> { stations(path) } }
            .map { (title, f) -> Section(title, f.get().map(Item::Song)) }
            .filter { it.items.isNotEmpty() }
    }

    /** Radio Browser ranks stations by plays and asks clients to report them. */
    fun countPlay(t: Track) {
        val id = t.key.removePrefix("radio:")
        if (id.length < 30) return
        pool.execute { runCatching { Http.text("${servers.first()}/json/url/$id") } }
    }
}
