package eu.kanade.tachiyomi.extension.all.mangaball

import java.io.IOException

/**
 * Network resilience helpers, kept free of Android and okhttp types so that they are fully
 * covered by the unit tests.
 *
 * The site sits behind Cloudflare. A 522 / 523 / 524 is Cloudflare saying *it* could not reach
 * mangaball's own servers: the request was fine, the origin was not. Those failures usually clear
 * up within seconds, and a text search now fetches up to three pages, so one of them hiccuping
 * used to throw the whole search away. Retrying turns most of them into a slightly slower search
 * instead of an error the reader has to understand.
 */

/** How many times one request is attempted before its error reaches the reader. */
internal const val RETRY_ATTEMPTS = 3

/** Pause before the retry that follows the given attempt number (1-based). */
internal fun retryDelayMillis(attempt: Int): Long = when {
    attempt <= 1 -> 600L
    attempt == 2 -> 1_500L
    else -> 3_000L
}

/**
 * Statuses worth retrying: the origin hiccuped, the request itself was fine. The 52x family is
 * Cloudflare's "origin unreachable" group; 403 is not here on purpose - it means a challenge
 * page, and the cure for that is the bypass client, not a second identical request.
 */
internal fun isTransientStatus(code: Int): Boolean = when (code) {
    408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524 -> true
    else -> false
}

/** The message a reader sees, written so that it says what actually happened. */
internal fun friendlyHttpError(code: Int): String = when (code) {
    520, 521, 522, 523, 524 ->
        "The site's own server did not answer (Cloudflare $code). That is mangaball.com, not " +
            "your phone or this extension - it usually clears up within minutes. Wait a moment " +
            "and search again."
    403 ->
        "The site refused the request (HTTP 403), which usually means a Cloudflare challenge. " +
            "Turn on \"Use Cloudflare bypass client\" in this source's settings."
    429 ->
        "The site is asking for a slower pace (HTTP 429). Wait a minute, then search again."
    503 ->
        "The site is briefly unavailable (HTTP 503). If it keeps happening with a Cloudflare " +
            "page, turn on \"Use Cloudflare bypass client\"."
    404 ->
        "The site reports that this title no longer exists (HTTP 404)."
    in 500..599 ->
        "The site had a server error (HTTP $code). Try again in a minute."
    else ->
        "The site answered with HTTP $code."
}

/** An HTTP status the site returned that is not a success. [message] is already readable. */
internal class HttpStatusException(val code: Int) : IOException(friendlyHttpError(code))

/** A failed request is retried, unless the site clearly rejected the request itself. */
internal fun IOException.isWorthRetrying(): Boolean =
    this !is HttpStatusException || isTransientStatus(code)
