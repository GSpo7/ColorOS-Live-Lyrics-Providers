package io.github.andrealtb.coloroslyrics.provider.universal.api

import io.github.andrealtb.coloroslyrics.provider.universal.BuildConfig

/**
 * Client identity sent to third-party lyric APIs.
 *
 * lrc.red's terms require a User-Agent that names the app with a link and a contact email.
 * The email is not hard-coded: the build injects it from `-PlyricsContactEmail=...` or the
 * `LYRICS_CONTACT_EMAIL` environment variable (see universal-provider/build.gradle.kts).
 * When neither is set the header carries only the project link and [hasContact] is false.
 */
internal object UniversalHttpIdentity {
    private const val APP = "ColorOSLyricsUniversalProvider"
    const val PROJECT_URL = "https://github.com/Andrea-lyz/ColorOS-Live-Lyrics-Providers"

    val hasContact: Boolean get() = BuildConfig.LYRICS_CONTACT_EMAIL.isNotBlank()

    val userAgent: String
        get() = buildString {
            append(APP).append('/').append(BuildConfig.VERSION_NAME)
            append(" (+").append(PROJECT_URL)
            if (hasContact) append("; ").append(BuildConfig.LYRICS_CONTACT_EMAIL)
            append(')')
        }
}
