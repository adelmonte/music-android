package dev.musicandroid

import dev.musicandroid.data.Item
import dev.musicandroid.data.Show
import dev.musicandroid.data.Track
import dev.musicandroid.media.Downloads
import dev.musicandroid.media.Mp4Tags
import dev.musicandroid.net.Deezer
import dev.musicandroid.net.Http
import dev.musicandroid.net.Matcher
import dev.musicandroid.net.Podcasts
import dev.musicandroid.net.RadioBrowser
import dev.musicandroid.net.Spotify
import dev.musicandroid.net.YouTube
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/** Against the real services: `LIVE=1 ./gradlew testDebugUnitTest --tests '*LiveTest*'`. */
class LiveTest {
    @Before
    fun live() = Assume.assumeTrue(System.getenv("LIVE") != null)

    private fun <T> timed(label: String, f: () -> T): T {
        val t = System.currentTimeMillis()
        val v = f()
        println("$label: ${System.currentTimeMillis() - t} ms")
        return v
    }

    @Test
    fun deezer() {
        val charts = timed("charts") { Deezer.charts() }
        println("charts: " + charts.joinToString { "${it.title} (${it.items.size})" })
        val s = timed("search") { Deezer.search("daft punk") }
        println("search: " + s.joinToString { "${it.title} (${it.items.size})" })
        val artist = (s.first { it.title == "Artists" }.items.first() as Item.Artist)
        val page = timed("artist") { Deezer.artist(artist.id) }
        println("artist ${page.info.name} ${page.info.fans} fans, picture ${page.info.picture.isNotEmpty()}, top ${page.top.size}, albums ${page.albums.size}: ${page.albums.take(3).map { "${it.title} [${it.sub}]" }}, related ${page.related.size}")
        val album = timed("album") { Deezer.album(page.albums.first().id) }
        println("album ${album.title} ${album.year}: ${album.tracks.take(3).map { "${it.trackNo} ${it.title} ${it.albumArtist}" }}")
        val fy = timed("for you") { Deezer.forYou(listOf("Daft Punk", "Justice"), emptySet()) }
        println("for you: ${fy?.items?.size} ${fy?.tracks?.take(4)?.map { it.artist + " - " + it.title }}")
        val nr = timed("new releases") { Deezer.newReleases(listOf("Taylor Swift", "Drake", "Billie Eilish"), 365) }
        println("new releases: ${nr?.items?.size} ${nr?.items?.take(3)}")
        val seed = page.top.first()
        val radio = timed("song radio") { Deezer.radio(seed, 20) }
        println("radio from ${seed.title}: ${radio?.tracks?.size} ${radio?.tracks?.take(6)?.map { it.key.take(3) + " " + it.artist + " - " + it.title }}")
        println("lookup: ${Deezer.lookup(Track("sp:x", "Get Lucky", "Daft Punk, Pharrell Williams", isrc = "USQX91300108"))}")
        println("locate artist: ${Deezer.locate(seed, false)} album: ${Deezer.locate(seed, true)}")
        println("artist picture: ${Deezer.artistPicture("Radiohead").take(60)}")
    }

    @Test
    fun radioAndPodcasts() {
        val home = timed("radio home") { RadioBrowser.home() }
        println("radio: " + home.joinToString { "${it.title} (${it.items.size})" })
        println("kexp: " + RadioBrowser.search("kexp").joinToString { s -> s.title + " " + s.tracks.take(2).map { it.title + " " + it.uri } })
        val top = timed("top podcasts") { Podcasts.top() }
        println("top: ${top?.items?.size} ${(top?.items?.firstOrNull() as? Item.Podcast)?.show}")
        val found = Podcasts.search("hardcore history")
        val show = (found.first().items.first() as Item.Podcast).show
        val feed = timed("feed") { Podcasts.feed(show) }
        println("feed ${feed.show.title} by ${feed.show.author}: ${feed.episodes.size} episodes; newest ${feed.episodes.first().title} ${feed.episodes.first().durationMs / 1000}s ${java.util.Date(feed.episodes.first().date)}")
        println("description: ${feed.show.description.take(100)}")
        val eps = timed("new episodes") { Podcasts.newEpisodes(listOf(show, Show("https://feeds.simplecast.com/Sl5CSM3S", "The Daily"))) }
        println("new episodes ${eps.size}: ${eps.take(3).map { it.artist + ": " + it.title }}")
    }

    @Test
    fun spotifyLink() {
        val p = timed("linked") { Spotify.fetchLinked("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M") }
        println("linked ${p.name}: ${p.tracks.size} ${p.tracks.take(3).map { it.key + " " + it.artist + " - " + it.title }} cover ${Spotify.linkedCovers[p.id]?.take(40)}")
        println("oembed cover: ${Spotify.cover(p.tracks.first().key)}")
    }

    @Test
    fun downloadAndTag() {
        val t = Track("sp:0DiWol3AO6WpXZgp0goxAV", "One More Time", "Daft Punk", durationMs = 320_000, isrc = "GBDUW0000053")
        val video = timed("match") { Matcher.videoFor(t)!! }
        val enriched = timed("enrich") { Downloads.enrich(t) }
        println("video $video, enriched: ${enriched.albumArtist} / ${enriched.album} / ${enriched.year} / #${enriched.trackNo} disc ${enriched.discNo} art ${enriched.art.take(40)}")
        val audio = YouTube.audio(video)
        val dir = File(System.getenv("SCRATCH") ?: "/tmp").apply { mkdirs() }
        val part = File(dir, "one-more-time.part").apply { delete() }
        // Fetch half, as if cut off, then resume.
        runCatching { Downloads.fetch(audio.url, part) { if (it > 0.4f) throw InterruptedException("cut") } }
        val cut = part.length()
        timed("download") { Downloads.fetch(audio.url, part) {} }
        println("resumed from $cut to ${part.length()} bytes (${audio.mime} ${audio.bitrate} kbps)")
        val cover = Http.bytes(enriched.art)
        val tagged = Mp4Tags.write(part.readBytes(), Mp4Tags.Tags(enriched.title, enriched.artist, enriched.albumArtist, enriched.album, enriched.year, enriched.trackNo, enriched.discNo, cover))
        val out = File(dir, "one-more-time.m4a")
        out.writeBytes(tagged)
        println("tagged: ${out.absolutePath} ${tagged.size} bytes")
    }
}
