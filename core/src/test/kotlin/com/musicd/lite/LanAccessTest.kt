package com.musicd.lite

import com.musicd.lite.http.LanAccess
import com.musicd.lite.http.LoginThrottle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that stands between a home network and 79 unauthenticated routes.
 *
 * This is the one part of the app where being wrong is not a bug but a
 * compromise: the routes behind it control somebody's music system and hold
 * their Discogs token and fanart.tv key. So every branch is exercised here,
 * including the ones that are only reachable when something has gone wrong —
 * a forged cookie, a clock that moved, an address hammering the PIN.
 */
class LanAccessTest {

    private val secret = LanAccess.newSecret()
    private val now = 1_700_000_000_000L
    private val lan = "192.168.1.44"

    private fun decide(
        remote: String = lan,
        enabled: Boolean = true,
        cookie: String? = null,
        locked: Long = 0,
        at: Long = now
    ) = LanAccess.decide(remote, enabled, secret, cookie, locked, at)

    private fun goodCookie(at: Long = now) = "${LanAccess.COOKIE}=${LanAccess.mint(secret, at)}"

    // ------------------------------------------------------------- loopback

    /**
     * The app's own WebView is the reason the server exists, and it must keep
     * working exactly as it did before any of this — including with the
     * feature off, when there is no PIN to present.
     */
    @Test
    fun theAppsOwnWebViewIsNeverChallenged() {
        val selves = listOf(
            "127.0.0.1", "/127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1"
        )
        for (self in selves) {
            assertEquals(
                "$self is this phone and must not be asked for a PIN",
                LanAccess.Decision.Allow,
                LanAccess.decide(self, false, secret, null, 0, now)
            )
        }
    }

    /**
     * A dual-stack phone hands back an IPv6 scope id on the loopback address.
     * Comparing the raw string would lock the owner out of their own app.
     */
    @Test
    fun aScopedLoopbackAddressIsStillLoopback() {
        assertTrue(LanAccess.isLoopback("::1%1"))
        assertTrue(LanAccess.isLoopback("127.0.0.53"))
    }

    @Test
    fun anAddressOnTheNetworkIsNotLoopback() {
        for (other in listOf("192.168.1.44", "10.0.0.9", "::ffff:192.168.1.44", "fe80::1")) {
            assertFalse("$other is not this phone", LanAccess.isLoopback(other))
        }
    }

    // ------------------------------------------------------------- the gate

    /**
     * Off means off: not "ask for a PIN", which would advertise that there is
     * something here worth logging into.
     */
    @Test
    fun withTheFeatureOffTheNetworkIsRefusedRatherThanPrompted() {
        assertEquals(LanAccess.Decision.Refused, decide(enabled = false))
        assertEquals(LanAccess.Decision.Refused, decide(enabled = false, cookie = goodCookie()))
    }

    @Test
    fun aBrowserWithNoCookieIsAskedToLogIn() {
        assertEquals(LanAccess.Decision.LoginRequired, decide())
    }

    @Test
    fun aBrowserWithOurCookieIsLetThrough() {
        assertEquals(LanAccess.Decision.Allow, decide(cookie = goodCookie()))
    }

    @Test
    fun aCookieWeDidNotIssueIsNotEnough() {
        val forged = "${LanAccess.COOKIE}=$now.${"0".repeat(64)}"
        val junk = "${LanAccess.COOKIE}=nonsense"
        assertEquals(LanAccess.Decision.LoginRequired, decide(cookie = forged))
        assertEquals(LanAccess.Decision.LoginRequired, decide(cookie = junk))
        assertEquals(LanAccess.Decision.LoginRequired, decide(cookie = "${LanAccess.COOKIE}="))
    }

    /** Somebody else's cookie, signed with a different key, is not ours. */
    @Test
    fun aCookieSignedWithAnotherSecretIsRejected() {
        val theirs = LanAccess.mint(LanAccess.newSecret(), now)
        assertFalse(LanAccess.valid(secret, theirs, now))
    }

    @Test
    fun aLockedOutAddressIsToldToWaitRatherThanChallenged() {
        val d = decide(locked = 4_200)
        assertTrue(d is LanAccess.Decision.Throttled)
        // Rounded UP: telling somebody to wait 4 seconds when it is 4.2 gets
        // them a second refusal and a confusing message.
        assertEquals(5L, (d as LanAccess.Decision.Throttled).retryAfterSeconds)
    }

    // ------------------------------------------------------------- lifetime

    @Test
    fun aTokenSurvivesItsTermAndThenDoesNot() {
        val t = LanAccess.mint(secret, now)
        assertTrue(LanAccess.valid(secret, t, now + LanAccess.TOKEN_TTL_MS - 1))
        assertFalse(LanAccess.valid(secret, t, now + LanAccess.TOKEN_TTL_MS + 1))
    }

    /**
     * A token stamped in the future is either a forged one or a clock that
     * moved. Honouring it would make a token valid for its whole term PLUS
     * however far ahead it claimed to be.
     */
    @Test
    fun aTokenFromTheFutureIsNotHonoured() {
        assertFalse(LanAccess.valid(secret, LanAccess.mint(secret, now + 60_000), now))
    }

    @Test
    fun rollingTheSecretLogsEveryDeviceOut() {
        val issued = LanAccess.mint(secret, now)
        assertTrue(LanAccess.valid(secret, issued, now))
        assertFalse("a new secret must invalidate what the old one signed",
            LanAccess.valid(LanAccess.newSecret(), issued, now))
    }

    // --------------------------------------------------------------- pieces

    @Test
    fun aPinIsReadableAndUnguessable() {
        val pins = (1..200).map { LanAccess.newPin() }
        for (pin in pins) {
            assertEquals(8, pin.length)
            // The characters people mistype are deliberately absent.
            assertFalse("\"$pin\" contains a character that is read wrong",
                pin.any { it in "O0I1L" })
            assertTrue(pin.all { it.isLetterOrDigit() })
        }
        assertTrue("200 PINs should not repeat", pins.toSet().size > 190)
    }

    @Test
    fun twoSecretsAreNotTheSameSecret() {
        assertNotEquals(LanAccess.newSecret(), LanAccess.newSecret())
        assertEquals(64, LanAccess.newSecret().length)
    }

    @Test
    fun oneCookieIsFoundAmongOthers() {
        val among = "theme=dark; ${LanAccess.COOKIE}=abc; x=1"
        assertEquals("abc", LanAccess.cookieValue(among, LanAccess.COOKIE))
        assertEquals("abc", LanAccess.cookieValue("${LanAccess.COOKIE}=abc", LanAccess.COOKIE))
        assertNull(LanAccess.cookieValue("theme=dark", LanAccess.COOKIE))
        assertNull(LanAccess.cookieValue(null, LanAccess.COOKIE))
        assertNull(LanAccess.cookieValue("", LanAccess.COOKIE))
    }

    /**
     * A Secure cookie is dropped by the browser over http://, which would mean
     * logging in on every single page load and never understanding why.
     */
    @Test
    fun theCookieIsNotMarkedSecureBecauseThereIsNoCertificate() {
        val header = LanAccess.setCookie("t")
        assertFalse(header.contains("Secure"))
        assertTrue(header.contains("HttpOnly"))
        assertTrue(header.contains("SameSite=Lax"))
    }

    @Test
    fun constantTimeComparisonStillComparesCorrectly() {
        assertTrue(LanAccess.constantTimeEquals("abc", "abc"))
        assertFalse(LanAccess.constantTimeEquals("abc", "abd"))
        assertFalse(LanAccess.constantTimeEquals("abc", "abcd"))
        assertFalse(LanAccess.constantTimeEquals("", "a"))
    }

    // ------------------------------------------------------------- throttle

    @Test
    fun anAddressGetsAFewTriesAndThenAWait() {
        val t = LoginThrottle(allowance = 3, lockoutMs = 60_000)
        repeat(2) { t.onFailure(lan, now) }
        assertEquals("still inside the allowance", 0, t.lockedForMs(lan, now))
        t.onFailure(lan, now)
        assertTrue("the third failure locks it", t.lockedForMs(lan, now) > 0)
        assertEquals(60_000, t.lockedForMs(lan, now))
    }

    @Test
    fun theLockoutEndsOnItsOwn() {
        val t = LoginThrottle(allowance = 1, lockoutMs = 60_000)
        t.onFailure(lan, now)
        assertTrue(t.lockedForMs(lan, now) > 0)
        assertEquals(0, t.lockedForMs(lan, now + 60_001))
    }

    /** One address failing must not lock out the rest of the house. */
    @Test
    fun oneAddressIsLockedOutWithoutTakingTheOthersWithIt() {
        val t = LoginThrottle(allowance = 1, lockoutMs = 60_000)
        t.onFailure(lan, now)
        assertTrue(t.lockedForMs(lan, now) > 0)
        assertEquals(0, t.lockedForMs("192.168.1.99", now))
    }

    @Test
    fun gettingItRightClearsWhatCameBefore() {
        val t = LoginThrottle(allowance = 3, lockoutMs = 60_000)
        repeat(2) { t.onFailure(lan, now) }
        t.onSuccess(lan)
        repeat(2) { t.onFailure(lan, now) }
        assertEquals(
            "the failures before the correct PIN should not still be counting",
            0, t.lockedForMs(lan, now)
        )
    }

    /**
     * Somebody who mistyped it twice last week should not find themselves one
     * slip from a lockout today.
     */
    @Test
    fun aQuietAddressIsForgotten() {
        val t = LoginThrottle(allowance = 2, lockoutMs = 60_000, forgetAfterMs = 10_000)
        // Two failures would lock it; the first is 20s old and forgotten.
        t.onFailure(lan, now)
        assertEquals(0, t.lockedForMs(lan, now + 20_000))
        t.onFailure(lan, now + 20_000)
        assertEquals(
            "the forgotten failure must not count towards the lockout",
            0, t.lockedForMs(lan, now + 20_000)
        )
    }
}
