package com.sycamorecreek.sonoswidget.sonos.local

import android.util.Log
import kotlinx.coroutines.CancellationException
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
    baseClient: OkHttpClient = defaultBackgroundClient()
) {

    /**
     * Patient client for background reads (polling, browse, topology). A 3s read
     * tolerates a momentarily slow speaker without a false failure.
     */
    private val backgroundClient: OkHttpClient = baseClient

    /**
     * Snappy client for user-initiated commands (play/pause/skip/volume). Tighter
     * timeouts so a tap that won't land surfaces "tap to retry" in ~1.5s instead of
     * making the user wait out the patient timeout. Shares the connection pool and
     * dispatcher of [backgroundClient] via newBuilder() — no duplicate resources.
     */
    private val controlClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Patient client for inherently slow commands. Loading a large playlist
     * favorite into the queue (AddURIToQueue) makes the speaker fetch every track
     * from the music service — a multi-thousand-track Apple Music / YouTube Music
     * playlist can legitimately take 10-30s. The snappy [controlClient] timeout
     * would abort it and surface a false "command failed".
     */
    private val slowCommandClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "SonosSoapClient"
        private val SOAP_XML_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()

        private fun defaultBackgroundClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Latency profile for a SOAP call.
     *  - [CONTROL]: user-initiated commands that should fail fast.
     *  - [BACKGROUND]: polling/reads that can wait a few seconds.
     *  - [SLOW_COMMAND]: user-initiated but inherently slow (e.g. enqueuing a
     *    large playlist), so it gets a long read timeout.
     */
    enum class Priority { CONTROL, BACKGROUND, SLOW_COMMAND }

    /**
     * Keeps an explicit speaker rejection distinct from a response that was
     * lost after the speaker may already have acted.
     */
    sealed interface CallResult {
        data class Success(val xml: String) : CallResult
        data class Rejected(val httpCode: Int) : CallResult
        data object Unknown : CallResult
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
        GROUP_RENDERING_CONTROL(
            endpoint = "/MediaRenderer/GroupRenderingControl/Control",
            urn = "urn:schemas-upnp-org:service:GroupRenderingControl:1"
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
     * @param priority  Latency profile — [Priority.CONTROL] fails fast, [Priority.BACKGROUND] is patient
     * @return Raw XML response body, or null if the request failed
     */
    suspend fun invoke(
        ip: String,
        port: Int = 1400,
        service: Service,
        action: String,
        params: List<Pair<String, String>> = emptyList(),
        priority: Priority = Priority.BACKGROUND
    ): String? = when (val result = invokeResult(ip, port, service, action, params, priority)) {
        is CallResult.Success -> result.xml
        is CallResult.Rejected, CallResult.Unknown -> null
    }

    /** Same request as [invoke], retaining its transport outcome for commands. */
    suspend fun invokeResult(
        ip: String,
        port: Int = 1400,
        service: Service,
        action: String,
        params: List<Pair<String, String>> = emptyList(),
        priority: Priority = Priority.BACKGROUND
    ): CallResult = withContext(Dispatchers.IO) {
        val url = "http://$ip:$port${service.endpoint}"
        val soapAction = "\"${service.urn}#$action\""
        val body = buildEnvelope(service.urn, action, params)

        Log.d(TAG, "SOAP → $action @ $url")

        val client = when (priority) {
            Priority.CONTROL -> controlClient
            Priority.SLOW_COMMAND -> slowCommandClient
            Priority.BACKGROUND -> backgroundClient
        }

        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
                .addHeader("SOAPAction", soapAction)
                .post(body.toRequestBody(SOAP_XML_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
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
                    return@withContext CallResult.Rejected(response.code)
                }

                val xml = response.body?.string()
                Log.d(TAG, "SOAP ← $action OK (${xml?.length ?: 0} chars)")
                if (xml == null) CallResult.Unknown else CallResult.Success(xml)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "SOAP $action network error: ${e.message}")
            CallResult.Unknown
        } catch (e: Exception) {
            Log.e(TAG, "SOAP $action unexpected error", e)
            CallResult.Unknown
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
