package dev.musicandroid.net

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * YouTube and YouTube Music through NewPipeExtractor: the on-device stand-in
 * for yt-dlp. Search (songs or videos), the audio stream behind a video, and
 * YouTube Music's "start radio" mix. Anonymous: no account, no API key.
 */
object YouTube {
    data class Candidate(
        val id: String,
        val title: String,
        val channel: String,
        val durationSecs: Long?,
        /// From a YouTube Music songs search: an audio upload, not a music video.
        val song: Boolean,
        val thumbnail: String = "",
    )

    data class Audio(val url: String, val mime: String, val ext: String, val bitrate: Int, val expiresAt: Long)

    private class OkDownloader : Downloader() {
        override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
            val body = request.dataToSend()?.toRequestBody()
            val builder = Request.Builder().url(request.url()).method(request.httpMethod(), body)
            builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            Http.client.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                return Response(
                    resp.code,
                    resp.message,
                    resp.headers.toMultimap(),
                    resp.body.string(),
                    resp.request.url.toString(),
                )
            }
        }
    }

    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(OkDownloader(), Localization("en", "US"), ContentCountry("US"))
                ready = true
            }
        }
    }

    private fun search(query: String, filter: String, count: Int): List<Candidate> {
        init()
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>().take(count).mapNotNull { it.candidate(filter == SONGS) }
    }

    private const val SONGS = YoutubeSearchQueryHandlerFactory.MUSIC_SONGS

    fun searchSongs(query: String, count: Int = 5): List<Candidate> =
        runCatching { search(query, SONGS, count) }.getOrDefault(emptyList())

    fun searchVideos(query: String, count: Int = 5): List<Candidate> =
        runCatching { search(query, YoutubeSearchQueryHandlerFactory.VIDEOS, count) }.getOrDefault(emptyList())

    private fun StreamInfoItem.candidate(song: Boolean): Candidate? {
        val id = videoId(url) ?: return null
        return Candidate(
            id = id,
            title = name ?: "",
            channel = uploaderName ?: "",
            durationSecs = duration.takeIf { it > 0 },
            song = song,
            thumbnail = thumbnails.maxByOrNull { it.width }?.url ?: "",
        )
    }

    fun videoId(url: String?): String? {
        if (url == null) return null
        Regex("(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
        return url.takeIf { it.length == 11 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
    }

    /** The best audio-only stream: AAC in MP4 first (it can be tagged and plays everywhere), else Opus. */
    fun audio(videoId: String): Audio {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        val streams = info.audioStreams.filter { it.isUrl && it.content.isNotEmpty() }
        // Original-language tracks over dubbed ones, when a video has several.
        val original = streams.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }.ifEmpty { streams }
        val pick: AudioStream = original.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: original.maxByOrNull { it.averageBitrate }
            ?: throw java.io.IOException("no audio stream for $videoId")
        val m4a = pick.format == MediaFormat.M4A
        val expire = Regex("[?&]expire=(\\d+)").find(pick.content)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
            ?: (System.currentTimeMillis() + 3 * 3600_000L)
        return Audio(
            url = pick.content,
            mime = if (m4a) "audio/mp4" else "audio/webm",
            ext = if (m4a) "m4a" else "webm",
            bitrate = pick.averageBitrate,
            expiresAt = expire,
        )
    }

    /** YouTube Music's radio for a video: songs with a similar sound and mood. */
    fun mix(videoId: String, count: Int): List<Candidate> = runCatching {
        init()
        val ex = ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/watch?v=$videoId&list=RDAMVM$videoId")
        ex.fetchPage()
        val out = ex.initialPage.items.filterIsInstance<StreamInfoItem>().mapNotNull { it.candidate(true) }.toMutableList()
        var page = ex.initialPage
        while (out.size < count + 1 && page.hasNextPage()) {
            page = ex.getPage(page.nextPage)
            val more = page.items.filterIsInstance<StreamInfoItem>().mapNotNull { it.candidate(true) }
            if (more.isEmpty()) break
            out += more
        }
        out.filter { it.id != videoId }.distinctBy { it.id }.take(count)
    }.getOrDefault(emptyList())
}
