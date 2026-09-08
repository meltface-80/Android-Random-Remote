package com.musicd.lite.http

import com.musicd.lite.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The addresses another device on the same network could reach this phone on.
 *
 * Only used to tell the owner what to type into the other device. Getting it
 * wrong costs a wrong URL on a settings screen, never access — the gate makes
 * its decision from the socket's peer address and never from anything here.
 *
 * IPv4 ONLY, and that is not laziness. The point is a string somebody types on
 * an iPhone keyboard, and a link-local IPv6 address with a scope id is neither
 * typeable nor reachable from another machine.
 */
object Network {

    private const val TAG = "Network"

    /**
     * Every usable IPv4 address, most likely first.
     *
     * A phone on wifi with a VPN or a hotspot up has several, and there is no
     * way from here to know which one the iPad is on — so they are all offered
     * rather than one being guessed at. A private address sorts first because
     * that is what a home network hands out.
     */
    fun hostAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { isPrivate(it) }
    }.onFailure { Log.w(TAG, "could not list interfaces: ${it.message}") }
        .getOrDefault(emptyList())

    /** The ranges a home router hands out. */
    fun isPrivate(address: String): Boolean =
        address.startsWith("192.168.") ||
            address.startsWith("10.") ||
            (address.startsWith("172.") && address.substringAfter('.').substringBefore('.')
                .toIntOrNull()?.let { it in 16..31 } == true)
}
