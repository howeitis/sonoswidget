package com.sycamorecreek.sonoswidget.sonos.local

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Low-level SOAP transport for Sonos UPnP control.
 *
 * Builds SOAP XML envelopes, sends HTTP POST requests to speaker endpoints,
 * and returns the raw XML response body. All network I/O runs on Dispatchers.IO.
 *
 * Sonos speakers expose UPnP services at fixed paths on port 1400:
 *   - AVTransport:        /MediaRenderer/AVTransport/Control
 *   - RenderingControl:   /MediaRenderer/RenderingControl/Control
 *   - ZoneGroupTopology:  /ZoneGroupTopology/Control
 *   - ContentDirectory:   /MediaServer/ContentDirectory/Control
 *
 * Each request requires a SOAPAction header of the form:
 *   "urn:schemas-upnp-org:service:{ServiceType}:1#{ActionName}"
 */
class SonosSoapClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "SonosSoapClient"
        private val SOAP_XML_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
    }

    /**
     * UPnP service descriptors for Sonos speakers.
     * Each defines the endpoint path and service URN.
     */
    enum class Service(val endpoint: String, val urn: String) {
        AV_TRANSPORT(
            endpoint = "/MediaRenderer/AVTransport/Control",
            urn = "urn:schemas-upnp-org:service:AVTransport:1"
        ),
        RENDERING_CONTROL(
            endpoint = "/MediaRenderer/RenderingControl/Control",
            urn = "urn:schemas-upnp-org:service:RenderingControl:1"
        ),
        ZONE_GROUP_TOPOLOGY(
            endpoint = "/ZoneGroupTopology/Control",
            urn = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
        ),
        CONTENT_DIRECTORY(
            endpoint = "/MediaServer/ContentDirectory/Control",
            urn = "urn:schemas-upnp-org:service:ContentDirectory:1"
        )
    }

    /**
     * Sends a SOAP action to a Sonos speaker and returns the raw XML response.
     *
     * @param ip        Speaker IP address
     * @param port      Speaker port (typically 1400)
     * @param service   UPnP service to target
     * @param action    SOAP action name (e.g., "Play", "GetTransportInfo")
     * @param params    Ordered list of (name, value) pairs for the SOAP body
     * @return Raw XML response body, or null if the request failed
     */
    suspend fun invoke(
        ip: String,
        port: Int = 1400,
        service: Service,
        action: String,
        params: List<Pair<String, String>> = emptyList()
    ): String? = withContext(Dispatchers.IO) {
        val url = "http://$ip:$port${service.endpoint}"
        val soapAction = "\"${service.urn}#$action\""
        val body = buildEnvelope(service.urn, action, params)

        Log.d(TAG, "SOAP → $action @ $url")

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
                .addHeader("SOAPAction", soapAction)
                .post(body.toRequestBody(SOAP_XML_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "SOAP ← $action HTTP ${response.code}: $errorBody")
                    // Parse UPnP error code if present
                    if (errorBody != null) {
                        val errorCode = extractXmlValue(errorBody, "errorCode")
                        val errorDesc = extractXmlValue(errorBody, "errorDescription")
                        if (errorCode != null) {
                            Log.e(TAG, "UPnP error $errorCode: $errorDesc")
                        }
                    }
                    return@withContext null
                }

                val xml = response.body?.string()
                Log.d(TAG, "SOAP ← $action OK (${xml?.length ?: 0} chars)")
                xml
            }
        } catch (e: IOException) {
            Log.e(TAG, "SOAP $action network error: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "SOAP $action unexpected error", e)
            null
        }
    }

    /**
     * Builds a SOAP XML envelope for a UPnP action.
     *
     * Example output for Play:
     * ```xml
     * <?xml version="1.0" encoding="utf-8"?>
     * <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
     *     s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
     *   <s:Body>
     *     <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
     *       <InstanceID>0</InstanceID>
     *       <Speed>1</Speed>
     *     </u:Play>
     *   </s:Body>
     * </s:Envelope>
     * ```
     */
    private fun buildEnvelope(
        serviceUrn: String,
        action: String,
        params: List<Pair<String, String>>
    ): String {
        val paramXml = params.joinToString("") { (name, value) ->
            "<$name>${escapeXml(value)}</$name>"
        }

        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:$action xmlns:u="$serviceUrn">$paramXml</u:$action>
  </s:Body>
</s:Envelope>"""
    }

    /**
     * Escapes special XML characters in parameter values.
     */
    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

/**
 * Extracts a text value from an XML element by tag name.
 * Works for simple non-nested elements. Used across the SOAP layer.
 */
internal fun extractXmlValue(xml: String, tagName: String): String? {
    val pattern = "<$tagName>([^<]*)</$tagName>"
    return Regex(pattern).find(xml)?.groupValues?.get(1)?.trim()
}

/**
 * Extracts a text value that may contain encoded XML (e.g., DIDL-Lite metadata).
 * Captures everything between the open and close tags, including nested content.
 */
internal fun extractXmlValueGreedy(xml: String, tagName: String): String? {
    val pattern = "<$tagName>(.*?)</$tagName>"
    return Regex(pattern, RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
}

/**
 * Decodes XML/HTML entities back to plain text.
 * Used to decode DIDL-Lite metadata and ZoneGroupState that arrive entity-encoded.
 */
internal fun decodeXmlEntities(text: String): String = text
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")

/**
 * Extracts an XML attribute value from an element.
 * Example: extractXmlAttribute(xml, "ZoneGroupMember", "ZoneName") for
 *   <ZoneGroupMember ZoneName="Living Room" .../>
 */
internal fun extractXmlAttribute(element: String, attrName: String): String? {
    val pattern = """$attrName="([^"]*)""""
    return Regex(pattern).find(element)?.groupValues?.get(1)
}
