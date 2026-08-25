package com.wachaai.phoneassistant.intelligence

import com.wachaai.phoneassistant.data.CapturedMessage
import com.wachaai.phoneassistant.data.MessageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CommunicationInsightsTest {
    private val zone = ZoneId.of("Africa/Kampala")
    private val date = LocalDate.of(2026, 8, 25)

    @Test
    fun summarizesTodayAndFlagsPriorityWording() {
        val messages = listOf(
            message("a", "Client A", "Please send the invoice today", MessageSource.WHATSAPP, 9),
            message("b", "Client A", "Meeting tomorrow at 10", MessageSource.WHATSAPP, 10),
            message("c", "MTN MoMo", "You have received UGX 50,000", MessageSource.SMS, 11),
        )

        val brief = CommunicationInsights.generate(messages, date)

        assertEquals(3, brief.totalMessages)
        assertEquals(2, brief.whatsappMessages)
        assertEquals(1, brief.smsMessages)
        assertEquals(2, brief.distinctSenders)
        assertEquals(1, brief.financialMessages)
        assertEquals("Client A", brief.topSenders.first().sender)
        assertTrue(brief.priorityMessages.isNotEmpty())
    }

    @Test
    fun replyStylesCyclePredictably() {
        assertEquals(ReplyStyle.BRIEF, ReplyStyle.NATURAL.next())
        assertEquals(ReplyStyle.NATURAL, ReplyStyle.BUSINESS.next())
        assertEquals(ReplyStyle.PROFESSIONAL, ReplyStyle.fromStored("PROFESSIONAL"))
        assertEquals(ReplyStyle.NATURAL, ReplyStyle.fromStored("UNKNOWN"))
    }

    private fun message(
        id: String,
        sender: String,
        text: String,
        source: MessageSource,
        hour: Int,
    ): CapturedMessage {
        val time = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return CapturedMessage(
            id = id,
            notificationKey = "key-$id",
            packageName = if (source == MessageSource.SMS) "com.google.android.apps.messaging" else "com.whatsapp",
            source = source,
            accountFingerprint = if (source == MessageSource.SMS) "sms|messages" else "wa|account",
            accountHint = null,
            sender = sender,
            text = text,
            postedAt = time,
            capturedAt = time,
            hasReplyAction = source != MessageSource.SMS,
        )
    }
}
