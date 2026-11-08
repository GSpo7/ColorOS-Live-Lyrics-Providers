package io.github.andrealtb.coloroslyrics.provider.universal.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrcRedSourceTest {
    private val searchJson = """
        {"hits":[
          {"isrc":"GBAYE9300465","title":"Creep (Acoustic)","artist":"Radiohead","album":"Creep - EP","duration":258.64,"cover":"c1","byModel":true},
          {"isrc":"GBAYE9200070","title":"Creep","artist":"Radiohead","album":"Pablo Honey","duration":238.64,"cover":"c2"},
          {"isrc":"","title":"No isrc","artist":"X","duration":1}
        ],"next":"p24"}
    """.trimIndent()

    private val lrcBody = "[ti:Creep]\n[ar:Radiohead]\n[al:Pablo Honey]\n[length:03:58]\n\n" +
        "[00:00.00]<00:00.00>When <00:00.46>you <00:00.81>were <00:01.22>here<00:02.06>\n" +
        "[00:03.73]<00:03.73>Before <00:04.14>you <00:04.53>went<00:06.37>"

    private class Recorder {
        val urls = mutableListOf<String>()
        val userAgents = mutableListOf<String?>()
    }

    private fun client(recorder: Recorder = Recorder(), respond: (String) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            recorder.urls += request.url.toString()
            recorder.userAgents += request.header("User-Agent")
            val (code, body) = respond(request.url.encodedPath)
            Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("text/plain".toMediaType()))
                .build()
        }).build()

    private val query = TrackQuery("Creep", "Radiohead", "Pablo Honey", 238_000L)

    @Test
    fun picksTheMatchingHitAndReturnsWordTimedLrcWithoutHeaderTags() {
        val recorder = Recorder()
        val source = LrcRedSource(client(recorder) { path ->
            when {
                path == "/search.json" -> 200 to searchJson
                path == "/s/GBAYE9200070.lrc" -> 200 to lrcBody
                else -> 404 to ""
            }
        })

        val result = assertNotNull(source.fetchLyrics(query))

        assertEquals("universal/lrc_red", result.source)
        assertEquals("GBAYE9200070", result.sourceTrackId)
        assertFalse(result.lyric.contains("[ti:"))
        assertFalse(result.lyric.contains("[length:"))
        assertTrue(result.lyric.startsWith("[00:00.00]"))
        assertTrue(WordTimedLyricConverter.hasRealWordTiming(result.rawLyric))
        // The terms require every request to identify the client.
        assertTrue(recorder.userAgents.all { !it.isNullOrBlank() && it.contains(UniversalHttpIdentity.PROJECT_URL) })
    }

    @Test
    fun returnsNullWhenNoHitIsConfidentOrTheTrackIsMissing() {
        val unrelated = LrcRedSource(client { path ->
            if (path == "/search.json") 200 to searchJson else 404 to ""
        })
        assertNull(unrelated.fetchLyrics(TrackQuery("Completely Different Song", "Someone Else", null, 100_000L)))

        val missingLrc = LrcRedSource(client { path ->
            if (path == "/search.json") 200 to searchJson else 404 to ""
        })
        assertNull(missingLrc.fetchLyrics(query))
    }

    @Test
    fun serverErrorsAreTransientNotAMiss() {
        val source = LrcRedSource(client { 503 to "overloaded" })
        assertFailsWith<IOException> { source.fetchLyrics(query) }
    }

    @Test
    fun aggregatorReportsAnLrcRedOutageAsTransientAndAHitAsPublishable() {
        val down = LyricsSourceAggregator(client { 503 to "" })
        val failed = down.fetchFirst(query, listOf(LyricsSourceAggregator.SourceId.LRC_RED))
        assertNull(failed.result)
        assertTrue(failed.transientFailure, "an outage must not become a cached 'no lyrics' verdict")

        val up = LyricsSourceAggregator(client { path ->
            when (path) {
                "/search.json" -> 200 to searchJson
                "/s/GBAYE9200070.lrc" -> 200 to lrcBody
                else -> 404 to ""
            }
        })
        val hit = up.fetchFirst(query, listOf(LyricsSourceAggregator.SourceId.LRC_RED))
        assertEquals("universal/lrc_red", hit.result?.source)
        assertFalse(hit.transientFailure)
    }

    @Test
    fun betterLyricsRoutesThroughTheAggregatorAndSurfacesOutages() {
        val ttml = """<tt><body><div><p begin="1.0" end="3.0"><span begin="1.0" end="1.5">Hello</span> <span begin="1.5" end="2.0">there</span> <span begin="2.0" end="3.0">friend</span></p></div></body></tt>"""
        val payload = JSONObject().put("ttml", ttml).toString()
        val up = LyricsSourceAggregator(client { path ->
            // The Unison translation call shares the client; answer it with a miss.
            if (path == "/getLyrics") 200 to payload else 404 to ""
        })
        val hit = up.fetchFirst(query, listOf(LyricsSourceAggregator.SourceId.BETTER_LYRICS))
        assertEquals("universal/better_lyrics", hit.result?.source)
        assertTrue(WordTimedLyricConverter.hasRealWordTiming(hit.result?.rawLyric))

        val notFound = LyricsSourceAggregator(client { 404 to "" })
            .fetchFirst(query, listOf(LyricsSourceAggregator.SourceId.BETTER_LYRICS))
        assertNull(notFound.result)
        assertFalse(notFound.transientFailure)

        val down = LyricsSourceAggregator(client { 502 to "" })
            .fetchFirst(query, listOf(LyricsSourceAggregator.SourceId.BETTER_LYRICS))
        assertTrue(down.transientFailure)
    }

    @Test
    fun cleanLrcOnlyDropsHeaderTags() {
        val cleaned = LrcRedResponseParser.cleanLrc("﻿[ti:A]\n[by:me]\n[00:01.00]hello [ar:not a header]\n[00:02.00]world")
        assertEquals("[00:01.00]hello [ar:not a header]\n[00:02.00]world", cleaned)
    }

    @Test
    fun searchCandidatesCarryIsrcAndDuration() {
        val source = LrcRedSource(client { 200 to searchJson })
        val candidates = source.searchCandidates("creep")
        assertEquals(2, candidates.size, "hits without an ISRC are unusable")
        assertEquals("GBAYE9200070", candidates[1].id)
        assertEquals(238_640L, candidates[1].durationMs)
        assertEquals(LrcRedSource.SOURCE_LABEL, candidates[1].source)
    }
}
