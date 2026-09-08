package com.sycamorecreek.sonoswidget.sonos.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R4 acceptance at the transport boundary.
 *
 * The distinction that matters is not visible from a fake controller: whether
 * the speaker actually received a command before its response went missing.
 * These drive the real client over a real socket, so the outcome comes from
 * how the connection behaved rather than from a stubbed return value.
 */
class SonosSoapClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SonosSoapClient

    @Before fun start() {
        server = MockWebServer()
        server.start()
        client = SonosSoapClient()
    }

    @After fun stop() {
        runCatching { server.shutdown() }
    }

    private suspend fun play(
        priority: SonosSoapClient.Priority = SonosSoapClient.Priority.CONTROL
    ) = client.invokeResult(
        ip = server.hostName,
        port = server.port,
        service = SonosSoapClient.Service.AV_TRANSPORT,
        action = "Play",
        params = listOf("InstanceID" to "0", "Speed" to "1"),
        priority = priority
    )

    @Test fun `an accepted command returns its response body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<PlayResponse/>"))

        val result = play()

        assertTrue("expected Success, got $result", result is SonosSoapClient.CallResult.Success)
        assertEquals("<PlayResponse/>", (result as SonosSoapClient.CallResult.Success).xml)
    }

    @Test fun `the envelope carries the SOAP action the speaker expects`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<PlayResponse/>"))

        play()

        val request = server.takeRequest()
        assertEquals("/MediaRenderer/AVTransport/Control", request.path)
        assertEquals(
            "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            request.getHeader("SOAPAction")
        )
        val body = request.body.readUtf8()
        assertTrue("body was $body", body.contains("<u:Play"))
        assertTrue("body was $body", body.contains("<InstanceID>0</InstanceID>"))
    }

    @Test fun `an explicit rejection is a definite failure, not an unknown`() = runBlocking {
        // A satellite speaker answers AVTransport commands with HTTP 500.
        server.enqueue(
            MockResponse().setResponseCode(500).setBody(
                "<s:Fault><errorCode>701</errorCode>" +
                    "<errorDescription>Transition not available</errorDescription></s:Fault>"
            )
        )

        val result = play()

        assertEquals(SonosSoapClient.CallResult.Rejected(500), result)
    }

    @Test fun `a command the speaker received but never answered stays unknown`() = runBlocking {
        // The speaker accepts the request and then goes quiet. It may well have
        // acted on it, so this must not be reported as a failure to roll back.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = play()

        assertEquals(SonosSoapClient.CallResult.Unknown, result)
        // The command really did reach the speaker — that is what makes the
        // outcome unknown rather than a clean miss.
        assertNotNull("the speaker never received the command", server.takeRequest())
    }

    @Test fun `a connection dropped mid-command stays unknown`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertEquals(SonosSoapClient.CallResult.Unknown, play())
    }

    @Test fun `an unreachable speaker is unknown rather than a rejection`() = runBlocking {
        val port = server.port
        server.shutdown()

        val result = client.invokeResult(
            ip = "127.0.0.1",
            port = port,
            service = SonosSoapClient.Service.AV_TRANSPORT,
            action = "Play",
            priority = SonosSoapClient.Priority.CONTROL
        )

        assertEquals(SonosSoapClient.CallResult.Unknown, result)
    }

    @Test fun `cancellation does not become an ordinary failure`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        var outcome: SonosSoapClient.CallResult? = null
        var cancelled: CancellationException? = null

        val job = launch(Dispatchers.IO) {
            try {
                outcome = play()
            } catch (e: CancellationException) {
                cancelled = e
                throw e
            }
        }
        // Let the request reach the speaker before withdrawing interest in it.
        server.takeRequest()
        job.cancel()
        job.join()

        assertNotNull("cancellation was swallowed", cancelled)
        assertNull("a cancelled command reported a transport outcome", outcome)
    }

    @Test fun `a slow accepted command is not cut short by the control timeout`() = runBlocking {
        // Loading a large playlist legitimately takes many seconds; the snappy
        // control timeout would abort it and report a false failure.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<AddURIToQueueResponse/>")
                .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS)
        )

        val result = client.invokeResult(
            ip = server.hostName,
            port = server.port,
            service = SonosSoapClient.Service.AV_TRANSPORT,
            action = "AddURIToQueue",
            priority = SonosSoapClient.Priority.SLOW_COMMAND
        )

        assertTrue("expected Success, got $result", result is SonosSoapClient.CallResult.Success)
    }
}
