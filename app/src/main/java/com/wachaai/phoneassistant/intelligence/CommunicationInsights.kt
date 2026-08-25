package com.wachaai.phoneassistant.intelligence

import com.wachaai.phoneassistant.data.CapturedMessage
import com.wachaai.phoneassistant.data.MessageSource
import com.wachaai.phoneassistant.finance.FinanceParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val KAMPALA_ZONE: ZoneId = ZoneId.of("Africa/Kampala")

data class SenderActivity(
    val sender: String,
    val messageCount: Int,
)

data class PriorityMessage(
    val sender: String,
    val text: String,
    val postedAt: Long,
    val reasons: List<String>,
)

data class CommunicationBrief(
    val date: LocalDate,
    val totalMessages: Int,
    val whatsappMessages: Int,
    val smsMessages: Int,
    val distinctSenders: Int,
    val financialMessages: Int,
    val priorityMessages: List<PriorityMessage>,
    val topSenders: List<SenderActivity>,
)

object CommunicationInsights {
    private val priorityCategories = linkedMapOf(
        "urgent wording" to listOf("urgent", "asap", "immediately", "emergency", "right away"),
        "deadline or appointment" to listOf("deadline", "today", "tomorrow", "meeting", "appointment", "due"),
        "money or payment" to listOf("payment", "pay", "money", "invoice", "balance", "loan", "momo", "airtel money"),
        "security" to listOf("otp", "verification code", "password", "security code", "fraud", "blocked", "suspended"),
    )

    fun generate(
        messages: List<CapturedMessage>,
        date: LocalDate = LocalDate.now(KAMPALA_ZONE),
    ): CommunicationBrief {
        val start = date.atStartOfDay(KAMPALA_ZONE).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(KAMPALA_ZONE).toInstant().toEpochMilli()
        val today = messages.filter { it.postedAt in start until end }

        val priorities = today.mapNotNull { message ->
            val lower = message.text.lowercase()
            val reasons = priorityCategories
                .filterValues { terms -> terms.any(lower::contains) }
                .keys
                .toList()
            if (reasons.isEmpty()) null else PriorityMessage(
                sender = message.sender,
                text = message.text.take(180),
                postedAt = message.postedAt,
                reasons = reasons,
            )
        }.sortedByDescending { it.postedAt }

        val topSenders = today
            .groupingBy { it.sender.trim() }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .take(5)
            .map { SenderActivity(it.key, it.value) }

        return CommunicationBrief(
            date = date,
            totalMessages = today.size,
            whatsappMessages = today.count { it.source != MessageSource.SMS },
            smsMessages = today.count { it.source == MessageSource.SMS },
            distinctSenders = today.map { it.sender.trim().lowercase() }.filter { it.isNotBlank() }.distinct().size,
            financialMessages = today.count(FinanceParser::looksFinancial),
            priorityMessages = priorities,
            topSenders = topSenders,
        )
    }

    fun renderPlainText(brief: CommunicationBrief): String = buildString {
        appendLine("TODAY'S COMMUNICATION BRIEF — ${brief.date}")
        appendLine("Messages: ${brief.totalMessages} • WhatsApp: ${brief.whatsappMessages} • SMS: ${brief.smsMessages}")
        appendLine("People/senders: ${brief.distinctSenders} • Financial alerts: ${brief.financialMessages}")
        appendLine()
        appendLine("PRIORITY (${brief.priorityMessages.size})")
        if (brief.priorityMessages.isEmpty()) {
            appendLine("No priority wording detected.")
        } else {
            brief.priorityMessages.take(10).forEach { item ->
                val time = Instant.ofEpochMilli(item.postedAt).atZone(KAMPALA_ZONE).toLocalTime().toString().take(5)
                appendLine("$time • ${item.sender} • ${item.reasons.joinToString()} • ${item.text}")
            }
        }
        appendLine()
        appendLine("MOST ACTIVE SENDERS")
        if (brief.topSenders.isEmpty()) appendLine("No messages today.")
        brief.topSenders.forEach { appendLine("${it.sender}: ${it.messageCount}") }
    }.trim()
}
