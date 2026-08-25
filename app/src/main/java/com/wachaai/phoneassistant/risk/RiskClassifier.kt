package com.wachaai.phoneassistant.risk

enum class RiskLevel {
    NORMAL,
    SENSITIVE,
}

data class RiskAssessment(
    val level: RiskLevel,
    val reasons: List<String>,
)

object RiskClassifier {
    private val categories = linkedMapOf(
        "account security" to listOf(
            "otp", "one time password", "one-time password", "verification code",
            "security code", "pin", "passcode", "password",
        ),
        "payments or banking" to listOf(
            "mobile money", "momo", "airtel money", "mtn money", "bank",
            "account number", "transfer", "payment", "pay now", "send money",
            "withdraw", "deposit",
        ),
        "legal commitment" to listOf(
            "contract", "agreement", "lawyer", "advocate", "court", "lawsuit",
            "legal notice", "sign this",
        ),
        "medical or health" to listOf(
            "doctor", "hospital", "medical", "medicine", "diagnosis", "health result",
        ),
    )

    fun assess(incomingMessage: String, proposedReply: String): RiskAssessment {
        val combined = "$incomingMessage\n$proposedReply".lowercase()
        val reasons = categories
            .filterValues { terms -> terms.any(combined::contains) }
            .keys
            .toList()

        return RiskAssessment(
            level = if (reasons.isEmpty()) RiskLevel.NORMAL else RiskLevel.SENSITIVE,
            reasons = reasons,
        )
    }
}
