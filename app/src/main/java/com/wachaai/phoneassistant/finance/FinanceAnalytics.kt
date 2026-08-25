package com.wachaai.phoneassistant.finance

import com.wachaai.phoneassistant.data.CapturedMessage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

private val ANALYTICS_ZONE: ZoneId = ZoneId.of("Africa/Kampala")

data class PeriodFinanceSummary(
    val label: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val transactionCount: Int,
    val liquidityIn: BigDecimal,
    val externalOut: BigDecimal,
    val savingsDeposited: BigDecimal,
    val savingsWithdrawn: BigDecimal,
    val loansReceived: BigDecimal,
    val loanRepayments: BigDecimal,
    val interestEarned: BigDecimal,
    val interestPaid: BigDecimal,
    val fees: BigDecimal,
    val deductions: BigDecimal,
) {
    val netBeforeSavings: BigDecimal get() = liquidityIn - externalOut
}

enum class AnomalySeverity {
    REVIEW,
    WARNING,
}

data class FinanceAnomaly(
    val severity: AnomalySeverity,
    val title: String,
    val detail: String,
    val amount: BigDecimal? = null,
)

object FinanceAnalytics {
    private val externalOutTypes = setOf(
        FinanceType.MONEY_SENT,
        FinanceType.MERCHANT_PAYMENT,
        FinanceType.CASH_WITHDRAWAL,
        FinanceType.BILL_PAYMENT,
        FinanceType.AIRTIME_PURCHASE,
        FinanceType.LOAN_REPAYMENT,
        FinanceType.INTEREST_DEBIT,
        FinanceType.DEDUCTION,
    )

    fun weekly(
        messages: List<CapturedMessage>,
        trustedSenderKeys: Set<String>,
        date: LocalDate = LocalDate.now(ANALYTICS_ZONE),
    ): PeriodFinanceSummary = summary(
        messages,
        trustedSenderKeys,
        label = "Last 7 days",
        startDate = date.minusDays(6),
        endDate = date,
    )

    fun monthly(
        messages: List<CapturedMessage>,
        trustedSenderKeys: Set<String>,
        date: LocalDate = LocalDate.now(ANALYTICS_ZONE),
    ): PeriodFinanceSummary = summary(
        messages,
        trustedSenderKeys,
        label = "Month to date",
        startDate = date.withDayOfMonth(1),
        endDate = date,
    )

    fun summary(
        messages: List<CapturedMessage>,
        trustedSenderKeys: Set<String>,
        label: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): PeriodFinanceSummary {
        val transactions = trustedTransactions(messages, trustedSenderKeys, startDate, endDate)

        fun sumType(type: FinanceType): BigDecimal = transactions.asSequence()
            .filter { it.type == type }
            .mapNotNull { it.amount }
            .fold(BigDecimal.ZERO, BigDecimal::add)

        val fees = transactions.asSequence()
            .mapNotNull { it.fee }
            .fold(BigDecimal.ZERO, BigDecimal::add)

        val liquidityIn = sumType(FinanceType.MONEY_RECEIVED) +
            sumType(FinanceType.LOAN_RECEIVED) +
            sumType(FinanceType.INTEREST_CREDIT) +
            sumType(FinanceType.AIRTIME_RECEIVED) +
            sumType(FinanceType.SAVINGS_WITHDRAWAL) +
            sumType(FinanceType.REVERSAL)

        val externalOut = transactions.asSequence()
            .filter { it.type in externalOutTypes }
            .mapNotNull { it.amount }
            .fold(BigDecimal.ZERO, BigDecimal::add) + fees

        return PeriodFinanceSummary(
            label = label,
            startDate = startDate,
            endDate = endDate,
            transactionCount = transactions.size,
            liquidityIn = liquidityIn,
            externalOut = externalOut,
            savingsDeposited = sumType(FinanceType.SAVINGS_DEPOSIT),
            savingsWithdrawn = sumType(FinanceType.SAVINGS_WITHDRAWAL),
            loansReceived = sumType(FinanceType.LOAN_RECEIVED),
            loanRepayments = sumType(FinanceType.LOAN_REPAYMENT),
            interestEarned = sumType(FinanceType.INTEREST_CREDIT),
            interestPaid = sumType(FinanceType.INTEREST_DEBIT),
            fees = fees,
            deductions = sumType(FinanceType.DEDUCTION),
        )
    }

    fun detectAnomalies(
        messages: List<CapturedMessage>,
        trustedSenderKeys: Set<String>,
        date: LocalDate = LocalDate.now(ANALYTICS_ZONE),
    ): List<FinanceAnomaly> {
        val anomalies = mutableListOf<FinanceAnomaly>()
        val todayStart = date.atStartOfDay(ANALYTICS_ZONE).toInstant().toEpochMilli()
        val tomorrowStart = date.plusDays(1).atStartOfDay(ANALYTICS_ZONE).toInstant().toEpochMilli()
        val todayFinancial = messages.filter {
            it.postedAt in todayStart until tomorrowStart && FinanceParser.looksFinancial(it)
        }
        val unverifiedCount = todayFinancial.count { it.sender.trim().lowercase() !in trustedSenderKeys }
        if (unverifiedCount > 0) {
            anomalies += FinanceAnomaly(
                severity = AnomalySeverity.REVIEW,
                title = "Unverified finance sender",
                detail = "$unverifiedCount financial-looking SMS ${if (unverifiedCount == 1) "was" else "were"} excluded from totals until the sender is trusted.",
            )
        }

        val today = trustedTransactions(messages, trustedSenderKeys, date, date)
        val baseline = trustedTransactions(messages, trustedSenderKeys, date.minusDays(30), date.minusDays(1))
        val todayOut = today.filter { it.type in externalOutTypes }
        val baselineOut = baseline.filter { it.type in externalOutTypes }.mapNotNull { it.amount }

        if (baselineOut.size >= 3) {
            val average = baselineOut.fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal(baselineOut.size), 2, RoundingMode.HALF_UP)
            val dynamicThreshold = average.multiply(BigDecimal("2.5"))
            val threshold = if (dynamicThreshold > BigDecimal("100000")) dynamicThreshold else BigDecimal("100000")
            todayOut.filter { (it.amount ?: BigDecimal.ZERO) > threshold }.forEach { tx ->
                anomalies += FinanceAnomaly(
                    severity = AnomalySeverity.WARNING,
                    title = "Unusually large outgoing transaction",
                    detail = "${tx.type.name.replace('_', ' ')} is above 2.5× the recent average outgoing transaction.",
                    amount = tx.amount,
                )
            }
        }

        val todayFees = today.mapNotNull { it.fee }.fold(BigDecimal.ZERO, BigDecimal::add)
        val todayPrincipalOut = todayOut.mapNotNull { it.amount }.fold(BigDecimal.ZERO, BigDecimal::add)
        if (todayPrincipalOut > BigDecimal.ZERO && todayFees > todayPrincipalOut.multiply(BigDecimal("0.05"))) {
            anomalies += FinanceAnomaly(
                severity = AnomalySeverity.WARNING,
                title = "High transaction fees",
                detail = "Today's detected fees exceed 5% of detected outgoing principal.",
                amount = todayFees,
            )
        }

        val deductions = today.count { it.type == FinanceType.DEDUCTION }
        if (deductions >= 3) {
            anomalies += FinanceAnomaly(
                severity = AnomalySeverity.REVIEW,
                title = "Repeated deductions",
                detail = "$deductions separate deductions were detected today. Review whether each is expected.",
            )
        }

        val prior14 = summary(
            messages,
            trustedSenderKeys,
            label = "Prior 14 days",
            startDate = date.minusDays(14),
            endDate = date.minusDays(1),
        )
        val todaySummary = summary(messages, trustedSenderKeys, "Today", date, date)
        if (prior14.transactionCount >= 5) {
            val averageDailyOut = prior14.externalOut.divide(BigDecimal(14), 2, RoundingMode.HALF_UP)
            val threshold = maxOf(BigDecimal("200000"), averageDailyOut.multiply(BigDecimal("2")))
            if (todaySummary.externalOut > threshold) {
                anomalies += FinanceAnomaly(
                    severity = AnomalySeverity.WARNING,
                    title = "Daily outflow spike",
                    detail = "Today's detected external outflow is more than twice the recent daily baseline.",
                    amount = todaySummary.externalOut,
                )
            }
        }

        return anomalies.distinctBy { listOf(it.title, it.detail, it.amount).joinToString("|") }
    }

    private fun trustedTransactions(
        messages: List<CapturedMessage>,
        trustedSenderKeys: Set<String>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<FinanceTransaction> {
        val start = startDate.atStartOfDay(ANALYTICS_ZONE).toInstant().toEpochMilli()
        val end = endDate.plusDays(1).atStartOfDay(ANALYTICS_ZONE).toInstant().toEpochMilli()
        return messages.asSequence()
            .filter { it.postedAt in start until end }
            .filter { it.sender.trim().lowercase() in trustedSenderKeys }
            .mapNotNull(FinanceParser::parse)
            .sortedBy { it.timestamp }
            .toList()
    }
}
