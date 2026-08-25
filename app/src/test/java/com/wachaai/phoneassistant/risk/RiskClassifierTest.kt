package com.wachaai.phoneassistant.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskClassifierTest {
    @Test
    fun ordinaryBusinessMessageIsNormal() {
        val result = RiskClassifier.assess(
            incomingMessage = "Are you available for a meeting tomorrow afternoon?",
            proposedReply = "Yes, tomorrow afternoon works for me.",
        )

        assertEquals(RiskLevel.NORMAL, result.level)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun otpMessageRequiresSensitiveConfirmation() {
        val result = RiskClassifier.assess(
            incomingMessage = "Please send me the OTP you just received.",
            proposedReply = "I will review this personally.",
        )

        assertEquals(RiskLevel.SENSITIVE, result.level)
        assertTrue(result.reasons.contains("account security"))
    }

    @Test
    fun mobileMoneyMessageRequiresSensitiveConfirmation() {
        val result = RiskClassifier.assess(
            incomingMessage = "Please make the Mobile Money payment now.",
            proposedReply = "I will review the payment personally.",
        )

        assertEquals(RiskLevel.SENSITIVE, result.level)
        assertTrue(result.reasons.contains("payments or banking"))
    }
}
