package org.example.agents.mcp

data class ExchangeRateRequest(
    val base: String,
    val quote: String,
    val amount: Double
)

object ExchangeRateIntentDetector {
    fun parseCommand(parts: List<String>): ExchangeRateRequest? {
        val base = parts.getOrNull(1)?.uppercase() ?: return null
        val quote = parts.getOrNull(2)?.uppercase() ?: return null
        val amount = parts.getOrNull(3)?.toDoubleOrNull() ?: 1.0
        if (!isCurrencyCode(base) || !isCurrencyCode(quote)) return null
        return ExchangeRateRequest(base, quote, amount)
    }

    fun detect(input: String): ExchangeRateRequest? {
        val normalized = input.lowercase()
        val hasRateIntent = listOf("курс", "валют", "exchange", "rate", "convert", "конверт")
            .any { normalized.contains(it) }
        if (!hasRateIntent) return null

        val currencies = Regex("\\b[A-Z]{3}\\b")
            .findAll(input.uppercase())
            .map { it.value }
            .distinct()
            .toList()

        if (currencies.size < 2) return null

        val amount = Regex("""\b\d+(?:[.,]\d)?\d*\b""")
            .find(input)
            ?.value
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?: 1.0

        return ExchangeRateRequest(
            base = currencies[0],
            quote = currencies[1],
            amount = amount
        )
    }

    private fun isCurrencyCode(value: String): Boolean {
        return Regex("[A-Z]{3}").matches(value)
    }
}
