package ro.davidmontei.loom.net

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ro.davidmontei.loom.data.settings.SettingsStore

/**
 * Whether the default network is unmetered, the same check WorkManager's `NetworkType.UNMETERED`
 * makes, so covers wait for the same networks the background refresh does. Mobile data and a
 * phone's hotspot are metered, and so is a VPN that doesn't say it isn't.
 */
fun ConnectivityManager.onUnmeteredNetwork(): Boolean =
    activeNetwork?.let(::getNetworkCapabilities)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

/** Whether covers may be fetched from RomM now: always, unless "Only on Wi-Fi" is on and the network is metered. */
class CoverNetwork(private val settings: SettingsStore, private val unmetered: () -> Boolean) {
    suspend fun allowed(): Boolean = !settings.snapshot().refreshOnWifiOnly || unmetered()
}
