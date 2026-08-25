package com.wachaai.phoneassistant.finance

import com.wachaai.phoneassistant.data.CapturedMessage
import com.wachaai.phoneassistant.data.MessageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

class FinanceAnalyticsTest {
    private val zone = ZoneId.of("Africa/Kampala")
    private val date = LocalDate.of(2026, 8, 25)
    private val trusted = setOf("mtn momo")

    @Test
    fun producesSevenDayAndMonthToDateSummaries() {
        val messages = listOf(
            sms("a", date.minusDays(2), "You have received UGX 500,000 from CLIENT A. Balance UGX 600,000."),
            sms("b", date.minusDays(1), "You have sent UGX 100,000 to SUPPLIER. Fee UGX 1,000. Balance UGX 499,000."),
            sms("c", date, "Interest earned UGX 2,000 has been credited to your savings account."),
            sms("d", date, "Loan interest charged UGX 5,000 and deducted from your account."),
        )

        val weekly = FinanceAnalytics.weekly(messages, trusted, date)
        val monthly = FinanceAnalytics.monthly(messages, trusted, date)

        assertEquals(BigDecimal("502000"), weekly.liquidityIn)
        assertEquals(BigDecimal("106000"), weekly.externalOut)
        assertEquals(BigDecimal("5000"), weekly.interestPaid)
        assertEquals(weekly.liquidityIn, monthly.liquidityIn)
    }

    @Test
    fun flagsLargeOutgoingAgainstRecentBaseline() {
        val messages = mutableListOf<CapturedMessage>()
        messages += sms("p1", date.minusDays(10), "You have sent UGX 20,000 to A.")
        messages += sms("p2", date.minusDays(8), "You have sent UGX 30,000 to B.")
        messages += sms("p3", date.minusDays(5), "You have sent UGX 25,000 to C.")
        messages += sms("today", date, "You have sent UGX 300,000 to D.")

        val anomalies = FinanceAnalytics.detectAnomalies(messages, trusted, date)

        assertTrue(anomalies.any { it.title == "Unusually large outgoing transaction" })
    }

    private fun sms(id: String, day: LocalDate, text: String): CapturedMessage {
        val time = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return CapturedMessage(
            id = id,
            notificationKey = "key-$id",
            packageName = "com.google.android.apps.messaging",
            source = MessageSource.SMS,
            accountFingerprint = "sms|messages",
            accountHint = null,
            sender = "MTN MoMo",
            text = text,
            postedAt = time,
            capturedAt = time,
            hasReplyAction = false,
        )
    }
}
