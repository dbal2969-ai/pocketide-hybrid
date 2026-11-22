package dev.pocketide.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Calls [onChange] when the phone switches to another network (mobile data to Wi-Fi or back) or loses its network.
 * A request open at that moment is left hanging on the old connection, so the caller ends it and offers Retry.
 */
class NetWatch(ctx: Context, private val onChange: () -> Unit) {
    private val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var last: Network? = null
    private val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val before = last
            last = network
            if (before != null && before != network) onChange()
        }
        override fun onLost(network: Network) { onChange() }
    }

    fun start() { try { cm?.registerDefaultNetworkCallback(cb) } catch (e: Exception) { /* no network permission or no service: plain requests still work */ } }

    fun stop() { try { cm?.unregisterNetworkCallback(cb) } catch (e: Exception) { /* already removed */ } }

    companion object {
        /**
         * True when the current network may cost per byte (mobile data, a metered hotspot), as Android reports it. Background
         * model calls (next-edit suggestions, 1.5.125) stay off there unless the person turns them on. When the answer cannot be
         * read, say metered: an unasked cost is worse than a missed suggestion.
         */
        fun metered(ctx: Context): Boolean =
            try { ctx.applicationContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true } catch (e: Exception) { true }
    }
}
