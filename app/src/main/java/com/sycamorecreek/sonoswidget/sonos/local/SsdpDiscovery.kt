package com.sycamorecreek.sonoswidget.sonos.local

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Discovers Sonos speakers via SSDP M-SEARCH (UPnP).
 *
 * Sends a multicast M-SEARCH for urn:schemas-upnp-org:device:ZonePlayer:1,
 * collects LOCATION responses, then fetches each device description XML
 * to extract speaker identity (name, UDN, model).
 *
 * Requires a WifiManager.MulticastLock to be held during the scan,
 * as Android aggressively restricts background multicast on Wi-Fi.
 */
class SsdpDiscovery {

    companion object {
        private const val TAG = "SsdpDiscovery"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"
        private const val INITIAL_TIMEOUT_MS = 1500
        private const val SUBSEQUENT_TIMEOUT_MS = 500
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .build()

    suspend fun discover(context: Context): List<DiscoveredSpeaker> = withContext(Dispatchers.IO) {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("SonosWidgetSsdp")

        try {
            multicastLock.setReferenceCounted(true)
            multicastLock.acquire()
            Log.d(TAG, "MulticastLock acquired, sending M-SEARCH")

            val locations = collectSsdpResponses()
            Log.d(TAG, "Collected ${locations.size} unique LOCATION(s)")

            if (locations.isEmpty()) return@withContext emptyList()

            // Fetch device descriptions in parallel (fast on LAN, ~50ms each)
            coroutineScope {
                locations.map { url ->
                    async { fetchDeviceDescription(url) }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSDP discovery error", e)
            emptyList()
        } finally {
            if (multicastLock.isHeld) {
                multicastLock.release()
                Log.d(TAG, "MulticastLock released")
            }
        }
    }

    /**
     * Sends M-SEARCH and collects unique LOCATION URLs from responses.
     * After the first response arrives, the socket timeout is tightened
     * so we don't wait the full initial window once speakers start replying.
     */
    private fun collectSsdpResponses(): List<String> {
        val locations = mutableSetOf<String>()
        val socket = DatagramSocket(null)

        try {
            socket.reuseAddress = true
            socket.soTimeout = INITIAL_TIMEOUT_MS
            socket.bind(InetSocketAddress(0))

            val message = buildMSearchMessage()
            val group = InetAddress.getByName(SSDP_ADDRESS)
            val packet = DatagramPacket(message.toByteArray(), message.length, group, SSDP_PORT)
            socket.send(packet)
            Log.d(TAG, "M-SEARCH sent to $SSDP_ADDRESS:$SSDP_PORT")

            val buffer = ByteArray(4096)
            var receivedFirst = false

            while (true) {
                try {
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    val text = String(response.data, 0, response.length)

                    val location = parseLocation(text)
                    if (location != null && locations.add(location)) {
                        Log.d(TAG, "SSDP response: LOCATION=$location")

                        if (!receivedFirst) {
                            receivedFirst = true
                            socket.soTimeout = SUBSEQUENT_TIMEOUT_MS
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: SocketException) {
                    break
                }
            }
        } finally {
            socket.close()
        }

        return locations.toList()
    }

    private fun buildMSearchMessage(): String {
        return "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $SEARCH_TARGET\r\n" +
            "\r\n"
    }

    private fun parseLocation(response: String): String? {
        return response.lines()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
    }

    /**
     * Fetches the UPnP device description XML from a speaker's LOCATION URL
     * (e.g. http://192.168.1.10:1400/xml/device_description.xml) and parses
     * out the speaker identity fields.
     */
    private fun fetchDeviceDescription(locationUrl: String): DiscoveredSpeaker? {
        return try {
            val request = Request.Builder().url(locationUrl).build()
            httpClient.newCall(request).execute().use { response ->
                val xml = response.body?.string() ?: return null
                val url = URL(locationUrl)
                parseDeviceXml(xml, url.host, url.port.takeIf { it > 0 } ?: 1400)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch device description from $locationUrl", e)
            null
        }
    }

    private fun parseDeviceXml(xml: String, ip: String, port: Int): DiscoveredSpeaker? {
        val friendlyName = extractXmlValue(xml, "friendlyName") ?: return null
        val udn = extractXmlValue(xml, "UDN") ?: return null
        val modelName = extractXmlValue(xml, "modelName")
        val modelNumber = extractXmlValue(xml, "modelNumber")

        // Sonos friendlyName format: "Room Name - Model Name"
        val displayName = friendlyName.substringBefore(" - ").trim()
            .ifEmpty { friendlyName }

        return DiscoveredSpeaker(
            ip = ip,
            port = port,
            id = udn,
            displayName = displayName,
            modelName = modelName,
            modelNumber = modelNumber
        )
    }

    private fun extractXmlValue(xml: String, tagName: String): String? {
        val pattern = "<$tagName>([^<]+)</$tagName>"
        return Regex(pattern).find(xml)?.groupValues?.get(1)?.trim()
    }
}
