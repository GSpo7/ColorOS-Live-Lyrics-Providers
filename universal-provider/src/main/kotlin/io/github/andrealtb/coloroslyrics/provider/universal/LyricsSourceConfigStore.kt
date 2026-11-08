package io.github.andrealtb.coloroslyrics.provider.universal

import android.content.Context
import android.content.Intent
import org.json.JSONArray

internal object LyricsSourceConfigStore {
    private const val PREFS = "lyrics_sources"
    private const val KEY = "priority"
    private const val KEY_KNOWN = "known"

    /** Every source the app can use, in default priority order (also the fresh-install config). */
    val all = listOf("QQ", "NETEASE", "APPLE_MUSIC", "LRC_RED", "BETTER_LYRICS")
    private val defaults = all

    /** Sources that existed before [KEY_KNOWN] was introduced; saved configs predate the rest. */
    private val legacyKnown = setOf("QQ", "NETEASE", "APPLE_MUSIC")

    fun read(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY, null)?.let(::decode) ?: return defaults
        return withNewSources(saved, knownFrom(prefs.getString(KEY_KNOWN, null)))
    }

    fun write(context: Context, values: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, JSONArray(values).toString())
            .putString(KEY_KNOWN, JSONArray(all).toString())
            .apply()
    }

    /**
     * Existing installs saved their list before LRC_RED / BETTER_LYRICS existed, so those never
     * ran. Append sources the saved config has never seen (once; [write] records them as known),
     * leaving anything the user deliberately removed removed.
     */
    fun withNewSources(configured: List<String>, known: Set<String>): List<String> =
        configured + all.filter { it !in known && it !in configured }

    private fun knownFrom(value: String?): Set<String> =
        value?.let { runCatching { JSONArray(it).let { a -> (0 until a.length()).map(a::getString) }.toSet() }.getOrNull() }
            ?: legacyKnown

    /**
     * Run once per app start: when an upgraded install is missing the new sources, persist the
     * migrated list and push it to system_server, which keeps its own copy of the priority.
     */
    fun migrateAndPublish(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedRaw = prefs.getString(KEY, null) ?: return
        val saved = decode(savedRaw)
        val migrated = withNewSources(saved, knownFrom(prefs.getString(KEY_KNOWN, null)))
        if (migrated == saved) {
            if (prefs.getString(KEY_KNOWN, null) == null) {
                prefs.edit().putString(KEY_KNOWN, JSONArray(all).toString()).apply()
            }
            return
        }
        write(context, migrated)
        publish(context, migrated)
    }

    /** Sends the priority plus the unchanged processing switches to the system_server module. */
    fun publish(context: Context, selected: List<String>) {
        val prefs = context.getSharedPreferences(UniversalSettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        context.sendBroadcast(Intent(UniversalSnapshotStore.ACTION_UPDATE_SOURCE_CONFIG).apply {
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            putExtra(UniversalSnapshotStore.EXTRA_SOURCE_PRIORITY, encode(selected))
            putExtra(UniversalSnapshotStore.EXTRA_WORD_TIMING_ENABLED, prefs.getBoolean(UniversalSettingsConstants.KEY_WORD_TIMING, false))
            putExtra(UniversalSnapshotStore.EXTRA_TRANSLATION_ENABLED, prefs.getBoolean(UniversalSettingsConstants.KEY_TRANSLATION, true))
            putExtra(UniversalSnapshotStore.EXTRA_RAW_LYRIC_ENABLED, prefs.getBoolean(UniversalSettingsConstants.KEY_RAW_LYRIC, true))
            putExtra(UniversalSnapshotStore.EXTRA_DEBUG_ENABLED, prefs.getBoolean(UniversalSettingsConstants.KEY_DEBUG, false))
            putExtra(
                UniversalSnapshotStore.EXTRA_CLOCK_LINE_FALLBACK_ENABLED,
                prefs.getBoolean(UniversalSettingsConstants.KEY_CLOCK_LINE_FALLBACK, true)
            )
        })
    }

    fun encode(values: List<String>): String = JSONArray(values).toString()

    fun decode(value: String): List<String> = runCatching {
        val array = JSONArray(value)
        (0 until array.length()).map { array.getString(it) }
            .map { if (it == "PAX_APPLE_MUSIC") "APPLE_MUSIC" else it }
            .filter { it in all }
            .distinct()
    }.getOrDefault(defaults)
}
