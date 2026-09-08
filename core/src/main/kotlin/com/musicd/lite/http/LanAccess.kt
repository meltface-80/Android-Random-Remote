package com.musicd.lite.http

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Who is allowed to talk to this server, once it is no longer only loopback.
 *
 * WHY THIS FILE EXISTS AT ALL. The server used to bind 127.0.0.1 and that was
 * the whole security model: nothing off the phone could reach it, so none of
 * the 79 `/api/` routes needed to ask who was calling. Those routes include
 * full Roon control, the rescan endpoints, and the settings store — which
 * holds the owner's Discogs token and fanart.tv key. Putting that on a home
 * network unauthenticated would hand every device on the wifi the keys to
 * somebody's music system and two of their API credentials.
 *
 * So the rule is: loopback is trusted exactly as before, and anything else is
 * a stranger until it proves otherwise.
 *
 * ALL OF THE DECIDING IS HERE, IN :core, ON PURPOSE. Auth code that is only
 * exercised by pointing a phone at it is auth code nobody can check. Every
 * branch below is reachable from a plain JVM test: the loopback bypass, the
 * refusal when the feature is off, the cookie check, the clock, and the
 * lockout. The Android side does nothing but hand over a remote address and a
 * header.
 *
 * WHAT THIS IS NOT. It is a PIN over plain HTTP on a home LAN. It stops the
 * other devices on the wifi; it does not stop somebody who can already read
 * the wifi's traffic. That is a deliberate trade — a self-signed certificate
 * would mean a trust prompt on every device and no better answer to the threat
 * that actually matters here, which is the smart plug down the hall.
 */
object LanAccess {

    /** The cookie the browser gets once it has proved it knows the PIN. */
    const val COOKIE = "musicd_lan"

    /** How long a browser stays logged in. Long: this is a home remote. */
    const val TOKEN_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * No O/0 or I/1/L: this gets read off one screen and typed into another,
     * and the two characters people get wrong are worth more than the entropy
     * they cost. 30^8 is still ~6.5e11 — far past what the lockout allows.
     */
    private const val PIN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val PIN_LENGTH = 8

    private val random = SecureRandom()

    // ------------------------------------------------------------ decisions

    /** What to do with one request. */
    sealed class Decision {
        /** Loopback, or a browser with a valid cookie. */
        object Allow : Decision()

        /** Off-device while LAN access is switched off: say nothing useful. */
        object Refused : Decision()

        /** Off-device, feature on, no valid cookie yet. */
        object LoginRequired : Decision()

        /** Too many wrong PINs from this address. */
        data class Throttled(val retryAfterSeconds: Long) : Decision()
    }

    /**
     * The verdict for one request.
     *
     * [remote] is the peer address; [enabled] whether the owner has switched
     * LAN access on; [secret] the signing key; [cookieHeader] the raw Cookie
     * header, if any.
     */
    fun decide(
        remote: String,
        enabled: Boolean,
        secret: String,
        cookieHeader: String?,
        lockedForMs: Long,
        now: Long
    ): Decision {
        // The app's own WebView, and nothing else, arrives on loopback. It must
        // keep working exactly as it did before any of this existed — including
        // when LAN access is off and there is no PIN at all.
        if (isLoopback(remote)) return Decision.Allow
        if (!enabled) return Decision.Refused
        if (lockedForMs > 0) return Decision.Throttled((lockedForMs + 999) / 1000)
        val token = cookieValue(cookieHeader, COOKIE)
        return if (token != null && valid(secret, token, now)) Decision.Allow
        else Decision.LoginRequired
    }

    /**
     * True for every form of "this machine".
     *
     * Java hands back IPv6 for a socket on a dual-stack phone, and an
     * IPv4-mapped IPv6 address when the two are bridged, so a string compare
     * against "127.0.0.1" alone would treat the app's own WebView as a
     * stranger and lock the owner out of their own phone.
     */
    fun isLoopback(remote: String): Boolean {
        val a = remote.removePrefix("/").substringBefore('%').lowercase()
        return a == "::1" ||
            a == "0:0:0:0:0:0:0:1" ||
            a.startsWith("127.") ||
            a.removePrefix("::ffff:").startsWith("127.")
    }

    // --------------------------------------------------------------- tokens

    /** A new signing key. Kept in settings; never leaves the phone. */
    fun newSecret(): String = ByteArray(32).also { random.nextBytes(it) }.toHex()

    /** A new PIN, in the shape a person can read aloud and type once. */
    fun newPin(): String = buildString(PIN_LENGTH) {
        repeat(PIN_LENGTH) { append(PIN_ALPHABET[random.nextInt(PIN_ALPHABET.length)]) }
    }

    /**
     * The cookie value: when it was issued, and proof we issued it.
     *
     * Signed rather than stored, so logging every device out is a matter of
     * rolling the secret rather than tracking a list of them.
     */
    fun mint(secret: String, issuedAt: Long): String = "$issuedAt.${sign(secret, issuedAt)}"

    /** Whether [token] is one of ours and has not expired. */
    fun valid(secret: String, token: String, now: Long, ttl: Long = TOKEN_TTL_MS): Boolean {
        val dot = token.indexOf('.')
        if (dot <= 0) return false
        val issuedAt = token.substring(0, dot).toLongOrNull() ?: return false
        // A token from the future is a clock change or a forgery; either way it
        // is not something to honour.
        if (issuedAt > now || now - issuedAt > ttl) return false
        return constantTimeEquals(token.substring(dot + 1), sign(secret, issuedAt))
    }

    private fun sign(secret: String, issuedAt: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(issuedAt.toString().toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Compares without leaking where two strings first differ.
     *
     * Overkill for a PIN behind a lockout, and it costs one line.
     */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** One cookie out of a Cookie header, or null. */
    fun cookieValue(header: String?, name: String): String? {
        if (header.isNullOrEmpty()) return null
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            if (part.substring(0, eq).trim() == name) {
                return part.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /**
     * The Set-Cookie for a fresh login.
     *
     * HttpOnly so a script cannot read it; SameSite=Lax because this page is
     * only ever opened directly. NOT Secure: there is no certificate here, and
     * a Secure cookie over http:// is simply discarded, which would leave the
     * browser logging in forever.
     */
    fun setCookie(token: String, ttl: Long = TOKEN_TTL_MS): String =
        "$COOKIE=$token; Path=/; Max-Age=${ttl / 1000}; HttpOnly; SameSite=Lax"

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) sb.append("%02x".format(b.toInt() and 0xff))
        return sb.toString()
    }
}

/**
 * How many wrong PINs an address gets before it is made to wait.
 *
 * An eight-character PIN is not guessable by hand, but something sitting on the
 * wifi can try it all night. This is what makes the difference between "not
 * guessable" and "not guessable in any useful time": failures are counted per
 * address, and past the allowance that address is refused outright for the
 * lockout, whether or not it gets the PIN right.
 */
class LoginThrottle(
    private val allowance: Int = 5,
    private val lockoutMs: Long = 5 * 60 * 1000L,
    private val forgetAfterMs: Long = 15 * 60 * 1000L
) {
    private class Record(var failures: Int, var last: Long, var lockedUntil: Long)

    private val byAddress = HashMap<String, Record>()

    /** Milliseconds this address must wait, or 0 when it may try. */
    @Synchronized
    fun lockedForMs(remote: String, now: Long): Long {
        val r = byAddress[remote] ?: return 0
        // A quiet address is forgotten, so a lockout cannot outlive its cause.
        if (now - r.last > forgetAfterMs && now >= r.lockedUntil) {
            byAddress.remove(remote)
            return 0
        }
        return (r.lockedUntil - now).coerceAtLeast(0)
    }

    @Synchronized
    fun onFailure(remote: String, now: Long) {
        val r = byAddress.getOrPut(remote) { Record(0, now, 0) }
        r.failures++
        r.last = now
        if (r.failures >= allowance) {
            r.lockedUntil = now + lockoutMs
            r.failures = 0
        }
    }

    /** A correct PIN clears the slate for that address. */
    @Synchronized
    fun onSuccess(remote: String) {
        byAddress.remove(remote)
    }
}
