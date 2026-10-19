package eu.kanade.tachiyomi.extension.all.mangaball

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the network resilience rules. A 522 is Cloudflare saying that mangaball's own server did
 * not answer - the request was fine - so it is retried and reported as the site's problem rather
 * than as a bare "HTTP 522" on the reader's phone.
 */
class MangaBallRetryTest {

    @Test
    fun `cloudflare origin failures are worth retrying`() {
        listOf(520, 521, 522, 523, 524).forEach { code ->
            assertTrue("$code should be transient", isTransientStatus(code))
        }
        listOf(500, 502, 503, 504, 408, 429).forEach { code ->
            assertTrue("$code should be transient", isTransientStatus(code))
        }
    }

    @Test
    fun `a rejected request is not retried`() {
        listOf(400, 401, 403, 404, 422).forEach { code ->
            assertFalse("$code should not be retried", isTransientStatus(code))
        }
    }

    @Test
    fun `the pause grows and then stops growing`() {
        assertEquals(600L, retryDelayMillis(1))
        assertEquals(1_500L, retryDelayMillis(2))
        assertEquals(3_000L, retryDelayMillis(3))
        assertEquals(3_000L, retryDelayMillis(9))
    }

    @Test
    fun `a 522 tells the reader the site is at fault, not the phone`() {
        val message = friendlyHttpError(522)
        assertTrue(message, message.contains("522"))
        assertTrue(message, message.contains("mangaball.com"))
        assertTrue(message, message.contains("not your phone"))
    }

    @Test
    fun `a challenge page points at the bypass client`() {
        assertTrue(friendlyHttpError(403).contains("Cloudflare bypass client"))
    }

    @Test
    fun `a rate limit asks for a pause`() {
        val message = friendlyHttpError(429)
        assertTrue(message, message.contains("429"))
        assertTrue(message, message.contains("Wait"))
    }

    @Test
    fun `a missing title says it was removed`() {
        assertTrue(friendlyHttpError(404).contains("no longer exists"))
    }

    @Test
    fun `an unexpected status still names the code`() {
        assertTrue(friendlyHttpError(418).contains("418"))
    }

    @Test
    fun `a network hiccup is retried but a rejection is not`() {
        assertTrue(IOException("timeout").isWorthRetrying())
        assertTrue(HttpStatusException(522).isWorthRetrying())
        assertTrue(HttpStatusException(503).isWorthRetrying())
        assertFalse(HttpStatusException(404).isWorthRetrying())
        assertFalse(HttpStatusException(403).isWorthRetrying())
    }
}
