package com.wachaai.phoneassistant.report

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportDeliveryClientTest {
    @Test
    fun hmacSignatureMatchesKnownVector() {
        val signature = ReportDeliveryClient.hmacSha256Hex(
            secret = "key",
            value = "The quick brown fox jumps over the lazy dog",
        )

        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            signature,
        )
    }
}
