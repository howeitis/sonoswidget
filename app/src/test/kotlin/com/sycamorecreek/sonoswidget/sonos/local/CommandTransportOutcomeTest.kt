package com.sycamorecreek.sonoswidget.sonos.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandTransportOutcomeTest {

    @Test
    fun `lost response remains unknown rather than becoming a rejection`() {
        assertEquals(
            CommandTransportOutcome.UNKNOWN,
            commandOutcomeFor(SonosSoapClient.CallResult.Unknown)
        )
    }

    @Test
    fun `explicit HTTP rejection remains distinct from a successful command`() {
        assertEquals(
            CommandTransportOutcome.REJECTED,
            commandOutcomeFor(SonosSoapClient.CallResult.Rejected(500))
        )
        assertEquals(
            CommandTransportOutcome.ACKNOWLEDGED,
            commandOutcomeFor(SonosSoapClient.CallResult.Success("<ok/>"))
        )
    }
}
