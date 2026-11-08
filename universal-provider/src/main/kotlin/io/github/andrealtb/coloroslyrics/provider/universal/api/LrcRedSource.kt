package io.github.andrealtb.coloroslyrics.provider.universal.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * lrc.red catalog (https://lrc.red/api). Free API; terms require an identifying User-Agent and a
 * visible credit/link to lrc.red wherever its lyrics are shown (the priority and search screens
 * carry that credit).
 *
 * Flow: `/search.json?q=` -> best hit by [SongMatchScorer] -> `/s/{isrc}.lrc`, which is
 * enhanced LRC with `<mm:ss.xx>` word stamps for word-synced tracks.
 *
 * Network errors and HTTP 5xx/429 throw so the aggregator reports a transient failure and the
 * engine does not cache a "no lyrics" verdict during an lrc.red outage. A missing track (404) or
 * no confident match returns null.
 */
class LrcRedSource(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://lrc.red"
) : LyricsSource {
    override val name: String = "lrc_red"

    override fun fetchLyrics(query: TrackQuery): LyricResult? {
        val hits = search("${query.title} ${query.artist}".trim())
        val hit = LrcRedResponseParser.bestHit(hits, query) ?: return null
        return fetchByIsrc(hit.isrc, hit.title.ifBlank { query.title }, hit.artist.ifBlank { query.artist })
    }

    override fun searchCandidates(query: String): List<SearchCandidate> {
        if (query.isBlank()) return emptyList()
        return runCatching { search(query.trim()) }.getOrDefault(emptyList()).map {
            SearchCandidate(
                id = it.isrc,
                title = it.title,
                artist = it.artist,
                album = it.album,
                source = SOURCE_LABEL,
                coverUrl = it.coverUrl,
                durationMs = it.durationMs
            )
        }
    }

    override fun fetchLyricByCandidate(candidate: SearchCandidate): LyricResult? =
        fetchByIsrc(candidate.id, candidate.title, candidate.artist)

    private fun fetchByIsrc(isrc: String, title: String, artist: String): LyricResult? {
        val body = get("/s/${isrc.uppercase()}.lrc") ?: return null
        val lrc = LrcRedResponseParser.cleanLrc(body)
        if (lrc.isBlank()) return null
        return LyricResult(
            title = title,
            artist = artist,
            lyric = lrc,
            rawLyric = lrc,
            source = "universal/lrc_red",
            sourceTrackId = isrc
        )
    }

    private fun search(term: String): List<LrcRedResponseParser.Hit> {
        if (term.isBlank()) return emptyList()
        val url = "$baseUrl/search.json".toHttpUrl().newBuilder().addQueryParameter("q", term).build()
        val body = get(url.toString()) ?: return emptyList()
        return LrcRedResponseParser.hits(JSONObject(body))
    }

    /** Returns the body, null for 404, and throws for transient failures. */
    private fun get(pathOrUrl: String): String? {
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else baseUrl + pathOrUrl
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UniversalHttpIdentity.userAgent)
            .header("X-Client-ID", UniversalHttpIdentity.userAgent)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("lrc.red HTTP ${response.code}")
            return response.body?.string()
        }
    }

    companion object {
        const val SOURCE_LABEL = "lrc.red"
    }
}

internal object LrcRedResponseParser {
    /** Below this a hit is a different song; mirrors the Apple Music threshold. */
    private const val MIN_SCORE = 0.72

    data class Hit(
        val isrc: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val coverUrl: String,
        val byModel: Boolean
    )

    private val HEADER_TAG = Regex("""^\[(?:ti|ar|al|au|by|length|offset|re|ve|tool):[^]]*]\s*$""", RegexOption.IGNORE_CASE)

    fun hits(root: JSONObject): List<Hit> {
        val rows = root.optJSONArray("hits") ?: return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val isrc = row.optString("isrc").trim()
                if (isrc.isEmpty()) continue
                add(
                    Hit(
                        isrc = isrc,
                        title = row.optString("title"),
                        artist = row.optString("artist"),
                        album = row.optString("album"),
                        durationMs = (row.optDouble("duration", 0.0) * 1000.0).toLong(),
                        coverUrl = row.optString("cover"),
                        byModel = row.optBoolean("byModel", false)
                    )
                )
            }
        }
    }

    fun bestHit(hits: List<Hit>, query: TrackQuery): Hit? {
        var best: Hit? = null
        var bestScore = 0.0
        for (hit in hits) {
            var score = SongMatchScorer.calculate(
                query.title, query.artist, query.durationMs,
                hit.title, hit.artist, hit.durationMs.takeIf { it > 0L }
            )
            // Prefer human-synced entries over model-synced ones when otherwise equal.
            if (hit.byModel) score -= 0.02
            if (score > bestScore) {
                bestScore = score
                best = hit
            }
        }
        return best.takeIf { bestScore >= MIN_SCORE }
    }

    /** Drops `[ti:]`/`[ar:]`-style header lines; the timeline lines pass through untouched. */
    fun cleanLrc(raw: String): String =
        raw.replace("﻿", "").lineSequence()
            .map { it.trimEnd() }
            .filterNot { HEADER_TAG.matches(it) }
            .joinToString("\n")
            .trim()
}
