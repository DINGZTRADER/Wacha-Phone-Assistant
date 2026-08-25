package com.wachaai.phoneassistant.finance

import com.wachaai.phoneassistant.data.CapturedMessage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val KAMPALA_ZONE: ZoneId = ZoneId.of("Africa/Kampala")

data class DailyFinanceReport(
    val date: LocalDate,
    val trustedTransactions: List<FinanceTransaction>,
    val unverifiedFinancialMessages: List<CapturedMessage>,
    val receipts: BigDecimal,
    val loansReceived: BigDecimal,
    val interestEarned: BigDecimal,
    val interestPaid: BigDecimal,
    val moneySent: BigDecimal,
    val merchantPayments: BigDecimal,
    val cashWithdrawals: BigDecimal,
    val billPayments: BigDecimal,
    val airtimeOut: BigDecimal,
    val airtimeIn: BigDecimal,
    val savingsDeposited: BigDecimal,
    val savingsWithdrawn: BigDecimal,
    val loanRepayments: BigDecimal,
    val deductions: BigDecimal,
    val fees: BigDecimal,
    val reversals: BigDecimal,
    val closingBalances: Map<FinanceNetwork, BigDecimal>,
) {
    val totalLiquidityIn: BigDecimal
        get() = receipts + loansReceived + interestEarned + airtimeIn + savingsWithdrawn + reversals

    val totalExternalOut: BigDecimal
        get() = moneySent + merchantPayments + cashWithdrawals + billPayments + airtimeOut + loanRepayments + interestPaid + deductions + fees

    val netWalletMovementBeforeSavings: BigDecimal
        get() = totalLiquidityIn - totalExternalOut
}

object DailyReportGenerator {
    fun generate(
        messages: List<CapturedMessage>,
        trustedSenderKeys: Set<String>,
        date: LocalDate = LocalDate.now(KAMPALA_ZONE),
    ): DailyFinanceReport {
        val start = date.atStartOfDay(KAMPALA_ZONE).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(KAMPALA_ZONE).toInstant().toEpochMilli()

        val financial = messages.asSequence()
            .filter { it.postedAt in start until end }
            .filter(FinanceParser::looksFinancial)
            .toList()

        val trustedMessages = financial.filter { it.senderTrustKey() in trustedSenderKeys }
        val unverified = financial.filterNot { it.senderTrustKey() in trustedSenderKeys }
        val transactions = trustedMessages.mapNotNull(FinanceParser::parse).sortedBy { it.timestamp }

        fun sum(type: FinanceType): BigDecimal = transactions.asSequence()
            .filter { it.type == type }
            .mapNotNull { it.amount }
            .fold(BigDecimal.ZERO, BigDecimal::add)

        val fees = transactions.asSequence()
            .mapNotNull { it.fee }
            .fold(BigDecimal.ZERO, BigDecimal::add)

        val closingBalances = transactions
            .filter { it.balance != null }
            .groupBy { it.network }
            .mapValues { (_, items) -> items.maxBy { it.timestamp }.balance ?: BigDecimal.ZERO }

        return DailyFinanceReport(
            date = date,
            trustedTransactions = transactions,
            unverifiedFinancialMessages = unverified,
            receipts = sum(FinanceType.MONEY_RECEIVED),
            loansReceived = sum(FinanceType.LOAN_RECEIVED),
            interestEarned = sum(FinanceType.INTEREST_CREDIT),
            interestPaid = sum(FinanceType.INTEREST_PAID),
            moneySent = sum(FinanceType.MONEY_SENT),
            merchantPayments = sum(FinanceType.MERCHANT_PAYMENT),
            cashWithdrawals = sum(FinanceType.CASH_WITHDRAWAL),
            billPayments = sum(FinanceType.BILL_PAYMENT),
            airtimeOut = sum(FinanceType.AIRTIME_PURCHASE),
            airtimeIn = sum(FinanceType.AIRTIME_RECEIVED),
            savingsDeposited = sum(FinanceType.SAVINGS_DEPOSIT),
            savingsWithdrawn = sum(FinanceType.SAVINGS_WITHDRAWAL),
            loanRepayments = sum(FinanceType.LOAN_REPAYMENT),
            deductions = sum(FinanceType.DEDUCTION),
            fees = fees,
            reversals = sum(FinanceType.REVERSAL),
            closingBalances = closingBalances,
        )
    }

    fun renderPlainText(report: DailyFinanceReport): String {
        val lines = mutableListOf<String>()
        lines += "WACHA DAILY MONEY REPORT — ${report.date.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))}"
        lines += ""
        lines += "MONEY IN"
        lines += row("Receipts", report.receipts)
        lines += row("Loans received", report.loansReceived)
        lines += row("Interest/rewards earned", report.interestEarned)
        lines += row("Savings withdrawn to wallet", report.savingsWithdrawn)
        lines += row("Airtime received", report.airtimeIn)
        lines += row("Reversals/refunds", report.reversals)
        lines += row("TOTAL LIQUIDITY IN", report.totalLiquidityIn)
        lines += ""
        lines += "MONEY OUT"
        lines += row("Money sent", report.moneySent)
        lines += row("Merchant payments", report.merchantPayments)
        lines += row("Cash withdrawals", report.cashWithdrawals)
        lines += row("Bills", report.billPayments)
        lines += row("Airtime purchased", report.airtimeOut)
        lines += row("Loan repayments", report.loanRepayments)
        lines += row("Interest paid/charged", report.interestPaid)
        lines += row("Fees/charges", report.fees)
        lines += row("Other deductions", report.deductions)
        lines += row("TOTAL EXTERNAL OUT", report.totalExternalOut)
        lines += row("NET BEFORE SAVINGS MOVES", report.netWalletMovementBeforeSavings)
        lines += ""
        lines += "SAVINGS"
        lines += row("Moved into savings", report.savingsDeposited)
        lines += row("Moved out of savings", report.savingsWithdrawn)
        lines += ""
        lines += "CLOSING BALANCES FOUND IN SMS"
        if (report.closingBalances.isEmpty()) {
            lines += "No closing balance was detected."
        } else {
            report.closingBalances.toSortedMap(compareBy { it.name }).forEach { (network, balance) ->
                lines += row(network.name, balance)
            }
        }
        lines += ""
        lines += "TRANSACTIONS (${report.trustedTransactions.size})"
        report.trustedTransactions.forEach { tx ->
            val time = Instant.ofEpochMilli(tx.timestamp).atZone(KAMPALA_ZONE).format(DateTimeFormatter.ofPattern("HH:mm"))
            val amount = tx.amount?.let(::money) ?: "amount not parsed"
            val fee = tx.fee?.takeIf { it > BigDecimal.ZERO }?.let { ", fee ${money(it)}" }.orEmpty()
            val counterparty = tx.counterparty?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
            lines += "$time • ${tx.network.name} • ${tx.type.name.replace('_', ' ')} • $amount$fee$counterparty"
        }
        lines += ""
        lines += "NEEDS REVIEW"
        lines += if (report.unverifiedFinancialMessages.isEmpty()) {
            "No unverified financial-looking SMS were excluded."
        } else {
            "${report.unverifiedFinancialMessages.size} financial-looking SMS were excluded because their sender has not yet been trusted in Wacha Phone Assistant."
        }
        lines += ""
        lines += "Note: This report is derived from phone notifications/SMS text and is a personal bookkeeping aid, not a bank or mobile-money statement."
        return lines.joinToString("\n")
    }

    private fun row(label: String, value: BigDecimal): String =
        "${label.padEnd(29)} ${money(value)}"

    private fun money(value: BigDecimal): String {
        val normalized = value.setScale(0, RoundingMode.HALF_UP).toPlainString()
        val sign = if (normalized.startsWith("-")) "-" else ""
        val digits = normalized.removePrefix("-")
        val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
        return "${sign}UGX $grouped"
    }
}

fun CapturedMessage.senderTrustKey(): String = sender.trim().lowercase()
