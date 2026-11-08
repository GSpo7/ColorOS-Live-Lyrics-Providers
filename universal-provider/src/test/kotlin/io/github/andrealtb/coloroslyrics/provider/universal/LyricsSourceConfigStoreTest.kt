package io.github.andrealtb.coloroslyrics.provider.universal

import io.github.andrealtb.coloroslyrics.provider.universal.api.LyricsSourceAggregator
import org.junit.Test
import kotlin.test.assertEquals

class LyricsSourceConfigStoreTest {
    private val legacy = setOf("QQ", "NETEASE", "APPLE_MUSIC")

    @Test
    fun `upgraded installs gain the new sources once at the end`() {
        val result = LyricsSourceConfigStore.withNewSources(listOf("NETEASE", "QQ", "APPLE_MUSIC"), legacy)
        assertEquals(listOf("NETEASE", "QQ", "APPLE_MUSIC", "LRC_RED", "BETTER_LYRICS"), result)
    }

    @Test
    fun `a source the user removed before stays removed`() {
        val result = LyricsSourceConfigStore.withNewSources(listOf("QQ", "APPLE_MUSIC"), legacy)
        assertEquals(listOf("QQ", "APPLE_MUSIC", "LRC_RED", "BETTER_LYRICS"), result)
    }

    @Test
    fun `once the new sources are known a disabled one is not re-added`() {
        val known = LyricsSourceConfigStore.all.toSet()
        val result = LyricsSourceConfigStore.withNewSources(listOf("QQ", "LRC_RED"), known)
        assertEquals(listOf("QQ", "LRC_RED"), result)
    }

    @Test
    fun `decode keeps new ids drops unknown ids and maps the legacy Apple id`() {
        val decoded = LyricsSourceConfigStore.decode("""["PAX_APPLE_MUSIC","BETTER_LYRICS","LRC_RED","BOGUS","LRC_RED"]""")
        assertEquals(listOf("APPLE_MUSIC", "BETTER_LYRICS", "LRC_RED"), decoded)
    }

    @Test
    fun `every stored id is a real aggregator source`() {
        val ids = LyricsSourceAggregator.SourceId.values().map { it.name }
        assertEquals(ids.sorted(), LyricsSourceConfigStore.all.sorted())
    }
}
