package com.wachaai.phoneassistant.intelligence

enum class ReplyStyle(
    val displayName: String,
    val instruction: String,
) {
    NATURAL(
        "Natural",
        "Reply naturally and conversationally, matching the sender's level of formality without copying slang excessively.",
    ),
    BRIEF(
        "Brief",
        "Keep the reply very short and direct, normally one or two sentences.",
    ),
    WARM(
        "Warm",
        "Reply warmly and politely, with a friendly human tone while remaining concise.",
    ),
    PROFESSIONAL(
        "Professional",
        "Use a polished professional tone suitable for clients, suppliers, colleagues, and formal business communication.",
    ),
    BUSINESS(
        "Business",
        "Be commercially clear and action-oriented. Confirm practical next steps, dates, prices, or deliverables only when they are explicitly present in the conversation.",
    ),
    ;

    fun next(): ReplyStyle {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromStored(value: String?): ReplyStyle =
            entries.firstOrNull { it.name == value } ?: NATURAL
    }
}
