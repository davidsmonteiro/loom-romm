package ro.davidmontei.loom.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import ro.davidmontei.loom.data.settings.SettingsStore
import java.io.File

/**
 * "Only on Wi-Fi" holds covers back too: on mobile data or a phone's hotspot (metered) none are
 * fetched, as no background refresh runs there.
 */
@RunWith(AndroidJUnit4::class)
class MeteredNetworkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivity = ApplicationProvider.getApplicationContext<Context>().getSystemService(ConnectivityManager::class.java)
    private val settings by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") })
    }
    private val covers by lazy { CoverNetwork(settings) { connectivity.onUnmeteredNetwork() } }

    @After fun tearDown() = scope.cancel()

    /** The default network is Wi-Fi with [capabilities]. */
    private fun onWifi(vararg capabilities: Int) {
        val network = connectivity.activeNetwork!!
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        (capabilities.toList() + NetworkCapabilities.NET_CAPABILITY_INTERNET).forEach { shadowOf(caps).addCapability(it) }
        shadowOf(connectivity).setNetworkCapabilities(network, caps)
    }

    private fun offline() = shadowOf(connectivity).setActiveNetworkInfo(null)

    @Test fun coversAlwaysComeWithWifiOnlyOff() = runBlocking<Unit> {
        onWifi() // a phone's hotspot
        assertThat(covers.allowed()).isTrue()
        offline() // and they fail as they always did, without being held
        assertThat(covers.allowed()).isTrue()
    }

    @Test fun coversComeOnAnUnmeteredNetwork() = runBlocking<Unit> {
        settings.setRefreshOnWifiOnly(true)
        onWifi(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        assertThat(covers.allowed()).isTrue()
    }

    @Test fun coversWaitOnAMeteredNetwork() = runBlocking<Unit> {
        settings.setRefreshOnWifiOnly(true)
        onWifi()
        assertThat(covers.allowed()).isFalse()
    }

    @Test fun coversWaitWithNoNetwork() = runBlocking<Unit> {
        settings.setRefreshOnWifiOnly(true)
        offline()
        assertThat(connectivity.onUnmeteredNetwork()).isFalse()
        assertThat(covers.allowed()).isFalse()
    }
}
