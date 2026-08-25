package com.wachaai.phoneassistant.finance

import com.wachaai.phoneassistant.data.CapturedMessage
import com.wachaai.phoneassistant.data.MessageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.math.BigDecimal

class FinanceParserTest {
    @Test
    fun parsesAirtelMoneySentMessage() {
        val tx = FinanceParser.parse(
            sms(
                sender = "Airtel Money",
                text = "UGX 70,500 sent to PROSSY NABISUBI. Fee UGX 500. Bal UGX 12,300.",
            ),
        )

        assertNotNull(tx)
        assertEquals(FinanceNetwork.AIRTEL, tx!!.network)
        assertEquals(FinanceType.MONEY_SENT, tx.type)
        assertEquals(BigDecimal("70500"), tx.amount)
        assertEquals(BigDecimal("500"), tx.fee)
        assertEquals(BigDecimal("12300"), tx.balance)
    }

    @Test
    fun parsesMtnWithdrawal() {
        val tx = FinanceParser.parse(
            sms(
                sender = "MTN MoMo",
                text = "You have withdrawn UGX 280,000. Charge UGX 1,000. New balance UGX 4,120.",
            ),
        )

        assertNotNull(tx)
        assertEquals(FinanceNetwork.MTN, tx!!.network)
        assertEquals(FinanceType.CASH_WITHDRAWAL, tx.type)
        assertEquals(BigDecimal("280000"), tx.amount)
        assertEquals(BigDecimal("1000"), tx.fee)
        assertEquals(BigDecimal("4120"), tx.balance)
    }

    @Test
    fun parsesInterestCreditSeparately() {
        val tx = FinanceParser.parse(
            sms(
                sender = "Airtel Money",
                text = "Interest earned UGX 1,250 has been credited to your savings account. Balance UGX 101,250.",
            ),
        )

        assertNotNull(tx)
        assertEquals(FinanceType.INTEREST_CREDIT, tx!!.type)
        assertEquals(BigDecimal("1250"), tx.amount)
    }

    @Test
    fun parsesLoanInterestAsMoneyOut() {
        val tx = FinanceParser.parse(
            sms(
                sender = "MTN MoMo",
                text = "Loan interest UGX 3,500 has been deducted from your wallet. Balance UGX 45,000.",
            ),
        )

        assertNotNull(tx)
        assertEquals(FinanceType.INTEREST_PAID, tx!!.type)
        assertEquals(BigDecimal("3500"), tx.amount)
    }

    private fun sms(sender: String, text: String): CapturedMessage = CapturedMessage(
        id = "test",
        notificationKey = "key",
        packageName = "com.google.android.apps.messaging",
        source = MessageSource.SMS,
        accountFingerprint = "sms|messages",
        accountHint = null,
        sender = sender,
        text = text,
        postedAt = 1L,
        capturedAt = 1L,
        hasReplyAction = false,
    )
}
