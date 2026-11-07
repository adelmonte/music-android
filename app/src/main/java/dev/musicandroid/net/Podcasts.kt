package dev.musicandroid.net

import dev.musicandroid.data.Item
import dev.musicandroid.data.Section
import dev.musicandroid.data.Show
import dev.musicandroid.data.Store
import dev.musicandroid.data.Track
import org.xmlpull.v1.XmlPullParser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** Podcasts: Apple's directory for search and the top chart, each show's RSS feed for episodes. */
object Podcasts {
    private fun country() = Locale.getDefault().country.lowercase().takeIf { it.length == 2 } ?: "us"

    private fun shows(results: org.json.JSONArray?) = results.objects().mapNotNull { r ->
        val feed = r.str("feedUrl").ifEmpty { return@mapNotNull null }
        Show(feed, r.str("collectionName").ifEmpty { r.str("trackName") }, r.str("artistName"), r.str("artworkUrl600").ifEmpty { r.str("artworkUrl100") })
    }

    fun search(q: String): List<Section> = runCatching {
        val v = Http.json("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=40&country=${country()}&term=${Http.enc(q)}")
        val s = shows(v.optJSONArray("results"))
        if (s.isEmpty()) emptyList() else listOf(Section("Podcasts: $q", s.map(Item::Podcast)))
    }.getOrDefault(emptyList())

    fun top(): Section? = runCatching { topOnce() }.recoverCatching { topOnce() }.getOrNull()

    /** The chart's show ids: iTunes' feed (quick and steady), else the newer marketing-tools one. */
    private fun chartIds(): List<String> = runCatching {
        Http.json("https://itunes.apple.com/${country()}/rss/toppodcasts/limit=50/json").optJSONObject("feed")?.optJSONArray("entry").objects()
            .mapNotNull { it.optJSONObject("id")?.optJSONObject("attributes")?.str("im:id") }
    }.getOrDefault(emptyList()).ifEmpty {
        Http.json("https://rss.marketingtools.apple.com/api/v2/${country()}/podcasts/top/50/podcasts.json")
            .optJSONObject("feed")?.optJSONArray("results").objects().map { it.str("id") }
    }.filter { it.isNotEmpty() }

    private fun topOnce(): Section {
        val ids = chartIds()
        val looked = Http.json("https://itunes.apple.com/lookup?id=${ids.joinToString(",")}&entity=podcast")
        val byId = looked.optJSONArray("results").objects().associateBy { it.optLong("collectionId").toString() }
        val s = shows(org.json.JSONArray(ids.mapNotNull { byId[it] }))
        if (s.isEmpty()) throw java.io.IOException("empty chart")
        return Section("Top podcasts", s.map(Item::Podcast))
    }

    data class Feed(val show: Show, val episodes: List<Track>)

    /** A show's feed: its details and episodes, newest first. */
    fun feed(known: Show): Feed {
        val bytes = Http.bytes(known.feed)
        val p = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
                p.setInput(bytes.inputStream(), null)
        var title = ""; var author = ""; var art = ""; var desc = ""
        val eps = mutableListOf<Track>()
        var inItem = false
        var e = mutableMapOf<String, String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (val n = p.name) {
                    "item" -> { inItem = true; e = mutableMapOf() }
                    "enclosure" -> if (inItem) { e["url"] = p.getAttributeValue(null, "url") ?: ""; e["type"] = p.getAttributeValue(null, "type") ?: "" }
                    "itunes:image" -> { val h = p.getAttributeValue(null, "href") ?: ""; if (inItem) e["image"] = h else if (art.isEmpty()) art = h }
                    "title", "pubDate", "itunes:duration", "itunes:author", "description", "itunes:summary", "itunes:episode" -> {
                        val text = runCatching { p.nextText() }.getOrDefault("").trim()
                        if (inItem) { if (!e.containsKey(n)) e[n] = text }
                        else when (n) {
                            "title" -> if (title.isEmpty()) title = text
                            "itunes:author" -> author = text
                            "description", "itunes:summary" -> if (desc.isEmpty()) desc = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (p.name == "item") {
                    inItem = false
                    val url = e["url"].orEmpty()
                    if (url.startsWith("http")) eps += Track(
                        key = "ep:$url",
                        title = e["title"].orEmpty(),
                        artist = title.ifEmpty { known.title },
                        albumArtist = author,
                        durationMs = parseDuration(e["itunes:duration"].orEmpty()) * 1000,
                        art = e["image"].orEmpty().ifEmpty { known.art.ifEmpty { art } },
                        uri = url,
                        feed = known.feed,
                        date = parseDate(e["pubDate"].orEmpty()),
                    )
                }
            }
        }
        val show = Show(known.feed, title.ifEmpty { known.title }, author.ifEmpty { known.author }, known.art.ifEmpty { art }, stripHtml(desc))
        Store.remember(eps)
        return Feed(show, eps.sortedByDescending { it.date })
    }

    private val pool = Executors.newFixedThreadPool(4)

    /** The latest episodes across subscriptions. */
    fun newEpisodes(shows: List<Show>): List<Track> = shows.map { s -> pool.submit<List<Track>> { runCatching { feed(s).episodes.take(10) }.getOrDefault(emptyList()) } }
        .flatMap { it.get() }.sortedByDescending { it.date }.take(60)

    fun parseDuration(s: String): Long {
        if (s.isBlank()) return 0
        if (!s.contains(':')) return s.toDoubleOrNull()?.toLong() ?: 0
        return s.split(':').fold(0L) { acc, part -> acc * 60 + (part.trim().toDoubleOrNull()?.toLong() ?: 0) }
    }

    fun parseDate(s: String): Long = runCatching {
        ZonedDateTime.parse(s.trim().replace(Regex("\\s+"), " "), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.recoverCatching {
        ZonedDateTime.parse(s.trim(), DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)).toInstant().toEpochMilli()
    }.getOrDefault(0)

    fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&quot;", "\"").replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()
}
