package org.example.mcp.finance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Serializable
data class ExchangeRateSample(
    val base: String,
    val quote: String,
    val rate: Double,
    val providerDate: String,
    val collectedAtEpochMillis: Long
)

data class ExchangeRateWatchConfig(
    val base: String,
    val quote: String,
    val intervalSeconds: Long
)

class ExchangeRateSampleStore(
    private val filePath: String = "assistant_memory/mcp/exchange_rate_samples.json"
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val file = File(filePath)

    init {
        ensureFile()
    }

    @Synchronized
    fun append(sample: ExchangeRateSample, maxSamples: Int = 500) {
        ensureFile()
        val samples = loadMutable()
        samples.add(sample)
        val trimmed = samples.takeLast(maxSamples.coerceAtLeast(1))
        file.writeText(json.encodeToString(ListSerializer(ExchangeRateSample.serializer()), trimmed) + "\n")
    }

    @Synchronized
    fun load(base: String? = null, quote: String? = null): List<ExchangeRateSample> {
        ensureFile()
        return loadMutable()
            .asSequence()
            .filter { base == null || it.base == base }
            .filter { quote == null || it.quote == quote }
            .sortedBy { it.collectedAtEpochMillis }
            .toList()
    }

    fun path(): String {
        ensureFile()
        return file.path
    }

    private fun ensureFile() {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText("[]\n")
        }
    }

    private fun loadMutable(): MutableList<ExchangeRateSample> {
        ensureFile()
        val raw = file.readText().trim()
        if (raw.isBlank()) return mutableListOf()
        return runCatching {
            json.decodeFromString<List<ExchangeRateSample>>(raw).toMutableList()
        }.getOrElse {
            mutableListOf()
        }
    }
}

class ExchangeRateMonitor(
    private val api: ExchangeRateApi = ExchangeRateApi(),
    private val store: ExchangeRateSampleStore = ExchangeRateSampleStore(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private var job: Job? = null
    private var config: ExchangeRateWatchConfig? = null
    private var lastError: String? = null

    @Synchronized
    fun start(baseCurrency: String, quoteCurrency: String, intervalSeconds: Long): String {
        val base = normalizeCurrency(baseCurrency)
        val quote = normalizeCurrency(quoteCurrency)
        require(base != quote) { "base and quote currencies must be different." }
        val interval = intervalSeconds.coerceAtLeast(10)

        stop()
        config = ExchangeRateWatchConfig(base = base, quote = quote, intervalSeconds = interval)
        lastError = null
        collectOnce(base, quote)

        job = scope.launch {
            while (isActive) {
                delay(interval * 1000)
                collectOnce(base, quote)
            }
        }

        return "Started exchange rate watch: $base/$quote every $interval seconds. JSON: ${store.path()}"
    }

    @Synchronized
    fun stop(): String {
        val existed = job != null
        job?.cancel()
        job = null
        config = null
        return if (existed) "Exchange rate watch stopped." else "No exchange rate watch was running."
    }

    fun summary(baseCurrency: String, quoteCurrency: String, last: Int = 20): String {
        val base = normalizeCurrency(baseCurrency)
        val quote = normalizeCurrency(quoteCurrency)
        val samples = store.load(base = base, quote = quote).takeLast(last.coerceIn(1, 500))
        if (samples.isEmpty()) {
            return "No collected samples for $base/$quote yet. Start the periodic watch first."
        }

        val rates = samples.map { it.rate }
        val latest = samples.last()
        val first = samples.first()
        val average = rates.average()
        val minRate = rates.fold(Double.POSITIVE_INFINITY) { acc, value -> min(acc, value) }
        val maxRate = rates.fold(Double.NEGATIVE_INFINITY) { acc, value -> max(acc, value) }
        val change = latest.rate - first.rate
        val changePercent = if (first.rate == 0.0) 0.0 else (change / first.rate) * 100.0
        val watch = currentStatusLine()

        return """
            Exchange rate summary for $base/$quote
            Samples: ${samples.size}
            Latest: ${latest.rate} at ${Instant.ofEpochMilli(latest.collectedAtEpochMillis)}
            Provider date: ${latest.providerDate}
            Average: ${"%.6f".format(Locale.US, average)}
            Min: $minRate
            Max: $maxRate
            Change over window: ${"%.6f".format(Locale.US, change)} (${"%.4f".format(Locale.US, changePercent)}%)
            Watch: $watch
            Last error: ${lastError ?: "none"}
            Storage: ${store.path()}
        """.trimIndent()
    }

    @Synchronized
    fun currentStatusLine(): String {
        val current = config ?: return "stopped"
        val running = job?.isActive == true
        return "${if (running) "running" else "stopped"} ${current.base}/${current.quote} every ${current.intervalSeconds}s"
    }

    private fun collectOnce(base: String, quote: String) {
        runCatching {
            val result = api.getRate(baseCurrency = base, quoteCurrency = quote, amount = 1.0)
            store.append(
                ExchangeRateSample(
                    base = result.base,
                    quote = result.quote,
                    rate = result.rate,
                    providerDate = result.date,
                    collectedAtEpochMillis = System.currentTimeMillis()
                )
            )
            lastError = null
        }.onFailure {
            lastError = it.message ?: it::class.simpleName ?: "unknown error"
        }
    }

    private fun normalizeCurrency(value: String): String {
        val normalized = value.trim().uppercase(Locale.US)
        require(Regex("[A-Z]{3}").matches(normalized)) {
            "Currency code must be a 3-letter ISO code, for example EUR, USD, GBP."
        }
        return normalized
    }
}
