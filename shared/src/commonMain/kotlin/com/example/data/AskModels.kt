package com.example.data

/**
 * A model the reader can pick. Prices are dollars per million tokens;
 * [cacheRead] is the fraction of the input price a cached read costs.
 */
data class AskModel(
    val id: String,
    val label: String,
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val fallbacks: Boolean,
    val effort: String? = null
) {
    val shortName: String get() = label.substringBefore(" —")
}

/**
 * The same choices as the dashboard. Opus 5.5 is the default: the newest
 * Opus, about 20% cheaper than Opus 5, at its "medium" thinking depth (set
 * explicitly, since that is its default and a change should not surprise).
 */
val ASK_MODELS = listOf(
    AskModel("claude-opus-5-5", "Claude Opus 5.5 — newest, about 20% cheaper than Opus 5", 4.0, 20.0, 0.05, true, "medium"),
    AskModel("claude-opus-5", "Claude Opus 5 — previous Opus", 5.0, 25.0, 0.1, true),
    AskModel("claude-sonnet-5", "Claude Sonnet 5 — about half the cost of Opus 5.5", 2.0, 10.0, 0.1, false),
)
val DEFAULT_ASK_MODEL = ASK_MODELS.first()
