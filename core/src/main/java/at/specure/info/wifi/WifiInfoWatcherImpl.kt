/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package at.specure.info.wifi

import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import at.specure.info.band.WifiBand
import at.specure.info.network.WifiNetworkInfo
import java.net.InetAddress
import java.net.UnknownHostException

private const val UNKNOWN_SSID = "<unknown ssid>"
private const val DUMMY_MAC_ADDRESS = "02:00:00:00:00:00"

/**
 * Basic [WifiManager] implementation to receive WIFI network info using [android.net.wifi.WifiManager]
 */
class WifiInfoWatcherImpl(private val wifiManager: WifiManager) : WifiInfoWatcher {

    override val activeWifiInfo: WifiNetworkInfo?
        get() {
            val info = wifiManager.connectionInfo ?: return null

            val address = try {
                val ipAddress = info.ipAddress.toBigInteger().toByteArray()
                InetAddress.getByAddress(ipAddress).hostAddress
            } catch (ex: UnknownHostException) {
                null
            }

            if (info.supplicantState == SupplicantState.DISCONNECTED || info.frequency == -1) {
                return null
            }

            val ssid = if (info.ssid == UNKNOWN_SSID || info.hiddenSSID) {
                ""
            } else {
                info.ssid.removeQuotation() ?: ""
            }

            return WifiNetworkInfo(
                bssid = if (info.bssid == DUMMY_MAC_ADDRESS) null else info.bssid,
                band = WifiBand.fromFrequency(info.frequency),
                isSSIDHidden = info.hiddenSSID,
                ipAddress = address,
                linkSpeed = info.linkSpeed,
                networkId = if (info.bssid == DUMMY_MAC_ADDRESS) -1 else info.networkId,
                rssi = info.rssi,
                signalLevel = WifiManager.calculateSignalLevel(info.rssi, 5),
                ssid = ssid,
                supplicantState = (info.supplicantState ?: SupplicantState.UNINITIALIZED).name,
                supplicantDetailedState = (WifiInfo.getDetailedStateOf(info.supplicantState) ?: android.net.NetworkInfo.DetailedState.IDLE).name
            )
        }

    private fun String?.removeQuotation(): String? {
        if (this != null && startsWith("\"") && endsWith("\""))
            return substring(1, length - 1)
        return this
    }
}