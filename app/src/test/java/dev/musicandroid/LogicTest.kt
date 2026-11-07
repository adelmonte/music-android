package dev.musicandroid

import dev.musicandroid.data.Playlist
import dev.musicandroid.data.Track
import dev.musicandroid.media.Downloads
import dev.musicandroid.net.Matcher
import dev.musicandroid.net.Podcasts
import dev.musicandroid.net.Spotify
import dev.musicandroid.net.YouTube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test
    fun csvParsesQuotesCommasAndNewlines() {
        val rows = Spotify.parseCsv("﻿a,\"b, c\",\"say \"\"hi\"\"\"\r\n1,\"two\nlines\",3\n")
        assertEquals(listOf(listOf("a", "b, c", "say \"hi\""), listOf("1", "two\nlines", "3")), rows)
    }

    @Test
    fun exportifyImportAndMerge() {
        val liked = """
            "Track URI","Track Name","Artist Name(s)","Album Name","Album Release Date","Track Number","Disc Number","Track Duration (ms)","Album Image URL","ISRC","Added At"
            "spotify:track:4uLU6hMCjMI75M1A2tKUQC","Never Gonna Give You Up","Rick Astley","Whenever You Need Somebody","1987-11-12","1","1","213573","https://i.scdn.co/image/x","GBARL9300135","2024-01-02T10:00:00Z"
            "spotify:track:7GhIk7Il098yCjg4BQjzvb","Sweet Child O' Mine","Guns N' Roses;Slash","Appetite for Destruction","1987-07-21","9","1","356066","","USGF18714809","2023-05-01T10:00:00Z"
        """.trimIndent()
        val found = LinkedHashMap<String, MutableList<Pair<Long, Track>>>()
        Spotify.importCsv("Liked_Songs.csv", liked, found)
        Spotify.importCsv("Road_Trip.csv", liked.lines().take(2).joinToString("\n"), found)
        val lists = Spotify.lists(found)
        assertEquals(listOf("Liked Songs", "Road Trip"), lists.map { it.first })
        val likedTracks = lists[0].second
        // Oldest added first.
        assertEquals("Sweet Child O' Mine", likedTracks[0].title)
        assertEquals("Guns N' Roses, Slash", likedTracks[0].artist)
        assertEquals("sp:4uLU6hMCjMI75M1A2tKUQC", likedTracks[1].key)
        assertEquals(213573L, likedTracks[1].durationMs)
        assertEquals(1987, likedTracks[1].year)

        val store = listOf(Playlist(Playlist.LIKED, "Liked Songs", listOf(Track("yt:abc", "Mine", "Me")), Playlist.LIKED))
        val (merged, added) = Spotify.merge(store, lists)
        assertEquals(3, added)
        assertEquals(3, merged[0].tracks.size)
        assertEquals("yt:abc", merged[0].tracks[0].key)
        assertEquals(Playlist.SPOTIFY, merged[1].source)
        val (again, addedAgain) = Spotify.merge(merged, lists)
        assertEquals(0, addedAgain)
        assertEquals(merged.map { it.tracks.size }, again.map { it.tracks.size })
    }

    @Test
    fun playlistLinks() {
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", Spotify.playlistId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", Spotify.playlistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(null, Spotify.playlistId("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"))
    }

    @Test
    fun youtubeTitles() {
        assertEquals("Radiohead - Creep", Matcher.stripNoise("Radiohead - Creep (Official Music Video) [HD]"))
        assertEquals("Song (Live at Wembley)", Matcher.stripNoise("Song (Live at Wembley) [Official Audio]"))
        val label = YouTube.Candidate("x", "Daft Punk - One More Time (Official Video)", "Warner Records", 320, true)
        assertEquals("Daft Punk" to "One More Time", Matcher.splitTitle(label))
        val topic = YouTube.Candidate("x", "Around the World", "Daft Punk - Topic", 429, true)
        assertEquals("Daft Punk" to "Around the World", Matcher.splitTitle(topic))
        val fan = YouTube.Candidate("x", "Queen \"Bohemian Rhapsody\"", "fan", 355, false)
        assertEquals("Queen" to "Bohemian Rhapsody", Matcher.splitTitle(fan))
    }

    @Test
    fun scoringPrefersTheRecording() {
        val t = Track("sp:x", "Creep", "Radiohead", durationMs = 238_000)
        val audio = YouTube.Candidate("a", "Creep", "Radiohead - Topic", 238, true)
        val video = YouTube.Candidate("v", "Radiohead - Creep (Official Music Video)", "Radiohead", 240, false)
        val live = YouTube.Candidate("l", "Creep (Live)", "Radiohead - Topic", 300, true)
        val ranked = listOf(live, video, audio).sortedBy { Matcher.score(it, 0, t) }
        assertEquals(listOf("a", "l", "v"), ranked.map { it.id })
    }

    @Test
    fun episodeDatesAndDurations() {
        assertEquals(3723L, Podcasts.parseDuration("01:02:03"))
        assertEquals(125L, Podcasts.parseDuration("2:05"))
        assertEquals(4480L, Podcasts.parseDuration("4480"))
        assertEquals(1785499200000L, Podcasts.parseDate("Fri, 31 Jul 2026 12:00:00 GMT"))
        assertEquals(1785499200000L, Podcasts.parseDate("Fri, 31 Jul 2026 08:00:00 -0400"))
    }

    @Test
    fun libraryLayout() {
        val t = Track("dz:1", "Song: Part 2/3", "A, B", albumArtist = "A", album = "Album.", year = 2001, trackNo = 3, discNo = 2)
        assertEquals("A/[2001] Album" to "2-03 Song_ Part 2⁄3.m4a", Downloads.layout(t, "m4a"))
        val single = Track("yt:1", "Solo", "C", albumArtist = "Various Artists")
        assertEquals("Compilations and Soundtracks/Singles" to "Solo.webm", Downloads.layout(single, "webm"))
        assertTrue(Downloads.layout(t.copy(albumArtist = ""), "m4a").first.startsWith("A/"))
    }
}
