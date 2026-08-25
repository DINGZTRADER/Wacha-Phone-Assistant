package com.wachaai.phoneassistant.finance

import com.wachaai.phoneassistant.data.CapturedMessage
import com.wachaai.phoneassistant.data.MessageSource
import java.math.BigDecimal

enum class FinanceNetwork {
    MTN,
    AIRTEL,
    OTHER,
}

enum class FinanceType {
    MONEY_RECEIVED,
    MONEY_SENT,
    MERCHANT_PAYMENT,
    CASH_WITHDRAWAL,
    BILL_PAYMENT,
    AIRTIME_PURCHASE,
    AIRTIME_RECEIVED,
    SAVINGS_DEPOSIT,
    SAVINGS_WITHDRAWAL,
    INTEREST_CREDIT,
    INTEREST_PAID,
    LOAN_RECEIVED,
    LOAN_REPAYMENT,
    DEDUCTION,
    REVERSAL,
    OTHER,
}

data class FinanceTransaction(
    val messageId: String,
    val timestamp: Long,
    val sender: String,
    val network: FinanceNetwork,
    val type: FinanceType,
    val amount: BigDecimal?,
    val fee: BigDecimal?,
    val balance: BigDecimal?,
    val counterparty: String?,
    val description: String,
)

object FinanceParser {
    private val amountPatterns = listOf(
        Regex("(?i)(?:UGX|Ugx|Shs\\.?|UGShs\\.?)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"),
        Regex("(?i)([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:UGX|Ugx|Shs\\.?|UGShs\\.?)"),
    )
    private val feeRegex = Regex("(?i)(?:fee|charge|charges|transaction fee|tax|deduction)\\s*[:=-]?\\s*(?:UGX|Ugx|Shs\\.?|UGShs\\.?)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val balanceRegex = Regex("(?i)(?:new\\s+balance|available\\s+balance|wallet\\s+balance|balance|bal)\\s*(?:is|:|=)?\\s*(?:UGX|Ugx|Shs\\.?|UGShs\\.?)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val fromRegex = Regex("(?i)\\bfrom\\s+([^.;]+)")
    private val toRegex = Regex("(?i)\\bto\\s+([^.;]+)")

    fun looksFinancial(message: CapturedMessage): Boolean {
        if (message.source != MessageSource.SMS) return false
        val text = message.text.lowercase()
        return text.contains("ugx") || text.contains("ugsh") || text.contains("shs") ||
            text.contains("airtel money") || text.contains("momo") || text.contains("mobile money") ||
            text.contains("loan") || text.contains("savings") || text.contains("interest")
    }

    fun parse(message: CapturedMessage): FinanceTransaction? {
        if (!looksFinancial(message)) return null

        val text = message.text.trim()
        val lower = text.lowercase()
        val network = networkOf(message.sender, text)
        val type = classify(lower)
        val allAmounts = extractAmounts(text)
        val fee = feeRegex.find(text)?.groupValues?.getOrNull(1)?.toMoney()
        val balance = balanceRegex.find(text)?.groupValues?.getOrNull(1)?.toMoney()

        val excluded = setOfNotNull(fee, balance)
        val principal = allAmounts.firstOrNull { candidate -> excluded.none { it.compareTo(candidate) == 0 } }
            ?: allAmounts.firstOrNull()

        val counterparty = when (type) {
            FinanceType.MONEY_RECEIVED, FinanceType.LOAN_RECEIVED, FinanceType.AIRTIME_RECEIVED ->
                fromRegex.find(text)?.groupValues?.getOrNull(1)?.cleanCounterparty()
            FinanceType.MONEY_SENT,
            FinanceType.MERCHANT_PAYMENT,
            FinanceType.BILL_PAYMENT,
            FinanceType.AIRTIME_PURCHASE,
            FinanceType.SAVINGS_DEPOSIT,
            FinanceType.LOAN_REPAYMENT ->
                toRegex.find(text)?.groupValues?.getOrNull(1)?.cleanCounterparty()
            else -> null
        }

        return FinanceTransaction(
            messageId = message.id,
            timestamp = message.postedAt,
            sender = message.sender,
            network = network,
            type = type,
            amount = principal,
            fee = fee,
            balance = balance,
            counterparty = counterparty,
            description = text,
        )
    }

    private fun classify(lower: String): FinanceType = when {
        "interest" in lower && any(lower, "charged", "deducted", "debited", "interest due", "loan interest", "interest repayment") -> FinanceType.INTEREST_PAID
        ("interest" in lower || "reward" in lower) && any(lower, "credited", "received", "earned", "savings interest", "interest paid to you") -> FinanceType.INTEREST_CREDIT
        "loan" in lower && any(lower, "disbursed", "credited", "received", "approved") -> FinanceType.LOAN_RECEIVED
        "loan" in lower && any(lower, "repaid", "repayment", "paid", "deducted") -> FinanceType.LOAN_REPAYMENT
        "saving" in lower && any(lower, "deposit", "deposited", "saved", "transfer to") -> FinanceType.SAVINGS_DEPOSIT
        "saving" in lower && any(lower, "withdraw", "withdrawn", "released", "transfer from") -> FinanceType.SAVINGS_WITHDRAWAL
        "airtime" in lower && any(lower, "received", "credited") -> FinanceType.AIRTIME_RECEIVED
        "airtime" in lower && any(lower, "bought", "purchase", "purchased", "recharge", "top up", "topup") -> FinanceType.AIRTIME_PURCHASE
        any(lower, "reversal", "reversed", "refunded", "refund") -> FinanceType.REVERSAL
        any(lower, "withdrawn", "cash withdrawal", "withdrawal") -> FinanceType.CASH_WITHDRAWAL
        any(lower, "merchant", "payment of", "paid to", "purchase at") -> FinanceType.MERCHANT_PAYMENT
        any(lower, "bill payment", "paybill", "utility") -> FinanceType.BILL_PAYMENT
        any(lower, "you have received", "received ugx", "credited with", "money received") -> FinanceType.MONEY_RECEIVED
        any(lower, "sent to", "you have sent", "transferred to") -> FinanceType.MONEY_SENT
        any(lower, "deducted", "deduction", "levy", "tax charged") -> FinanceType.DEDUCTION
        else -> FinanceType.OTHER
    }

    private fun networkOf(sender: String, text: String): FinanceNetwork {
        val haystack = "$sender $text".lowercase()
        return when {
            "airtel" in haystack -> FinanceNetwork.AIRTEL
            "mtn" in haystack || "momo" in haystack -> FinanceNetwork.MTN
            else -> FinanceNetwork.OTHER
        }
    }

    private fun extractAmounts(text: String): List<BigDecimal> {
        return amountPatterns.asSequence()
            .flatMap { regex -> regex.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.toMoney() } }
            .toList()
            .distinct()
    }

    private fun any(text: String, vararg terms: String): Boolean = terms.any { it in text }

    private fun String.toMoney(): BigDecimal? = replace(",", "").trim().toBigDecimalOrNull()

    private fun String.cleanCounterparty(): String =
        replace(Regex("(?i)\\b(?:fee|charge|bal|balance|transaction id|ref)\\b.*$"), "")
            .trim(' ', '.', ',', ':', ';')
            .take(80)
}
