package io.github.boomboom.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Whether the phone is on a metered network (mobile data, a metered hotspot) or in Data Saver. On one, only the next
 * song is fetched ahead and downloads wait unless the listener allowed them there (the iPhone app's "expensive or
 * constrained" path). [onChange] is called on the main thread.
 */
class NetworkWatch(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    var isMetered: Boolean = computeMetered(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
        private set

    var onChange: (() -> Unit)? = null

    /** Called when the phone gets a network to use (also once at the start, if it has one). */
    var onAvailable: (() -> Unit)? = null

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { onAvailable?.invoke() }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val metered = computeMetered(capabilities)
                main.post { update(metered) }
            }

            override fun onLost(network: Network) {
                main.post { update(computeMetered(connectivity.getNetworkCapabilities(connectivity.activeNetwork))) }
            }
        })
    }

    private fun update(metered: Boolean) {
        if (metered == isMetered) return
        isMetered = metered
        BoomTrace.note("network ${if (metered) "metered" else "not metered"}")
        onChange?.invoke()
    }

    private fun computeMetered(capabilities: NetworkCapabilities?): Boolean {
        val unmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: true
        val saver = connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        return !unmetered || saver
    }
}
