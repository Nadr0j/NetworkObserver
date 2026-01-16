package networkobserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Serializable
data class Config(
    val window_seconds: Int = 60,
    val probe_interval_seconds: Int = 1,
    val targets: Map<String, String> = emptyMap(),
    val thresholds: Thresholds = Thresholds(),
    val throughput: ThroughputConfig = ThroughputConfig()
)

@Serializable
data class Thresholds(
    val loss_pct_max: Double = 2.0,
    val rtt_ms_p90_max: Double = 100.0,
    val jitter_ms_p90_max: Double = 30.0,
    val rtt_spike_factor: Double = 2.0
)

@Serializable
data class ThroughputConfig(
    val enabled: Boolean = false,
    val interval_minutes: Int = 30,
    val test_duration_s: Int = 10,
    val source: String = "iperf3",
    val server: String? = null,
    val port: Int? = null,
    val port_range: List<Int>? = null,
    val retries: Int = 2,
    val retry_delay_ms: Int = 1000
)

@Serializable
data class TargetMetrics(
    val sent: Int,
    val received: Int,
    val loss_pct: Double,
    val rtt_ms_min: Double?,
    val rtt_ms_p50: Double?,
    val rtt_ms_p90: Double?,
    val rtt_ms_p99: Double?,
    val rtt_ms_max: Double?,
    val rtt_ms_mean: Double?,
    val rtt_ms_stddev: Double?,
    val jitter_ms_p50: Double?,
    val jitter_ms_p90: Double?,
    val rtt_spike_count: Int,
    val outage_seconds: Int
)

@Serializable
data class ThroughputResult(
    val sampled: Boolean,
    val dl_mbps: Double? = null,
    val ul_mbps: Double? = null,
    val test_duration_s: Int? = null,
    val test_source: String? = null
)

@Serializable
data class Availability(
    val window_available: Boolean,
    val quality_score: Int,
    val quality_state: String
)

private val json = Json {
    encodeDefaults = true
    explicitNulls = true
}

private val timestampFormat = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

fun main(args: Array<String>) {
    val configPath = parseConfigPath(args)
    val config = loadConfig(configPath)
    if (config.targets.isEmpty()) {
        println("No targets configured. Add targets in config.json.")
        return
    }

    val logsDir = Paths.get("logs")
    Files.createDirectories(logsDir)
    val logFile = logsDir.resolve("network_observer.jsonl").toFile()
    val appLogFile = logsDir.resolve("network_observer_app.log").toFile()
    val logger = AppLogger(appLogFile)
    logger.info("Starting NetworkObserver with config at ${configPath.toAbsolutePath()}")
    logger.info("Targets: ${config.targets}")
    if (config.throughput.enabled && config.throughput.server == null) {
        logger.warn("Throughput enabled but no server configured; skipping throughput tests.")
    }

    val interfaceInfo = detectInterfaceInfo()
    logger.info("Interface: ${interfaceInfo.name} (${interfaceInfo.linkType})")
    val webConfig = parseWebConfig(args)
    if (!webConfig.disabled) {
        startWebServer(webConfig, logsDir, logger)
    }
    var nextThroughputAt = Instant.now()

    while (true) {
        val windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val windowEnd = windowStart.plusSeconds(config.window_seconds.toLong())
        logger.info("Collecting window ${timestampFormat.format(windowStart)} to ${timestampFormat.format(windowEnd)}")
        val samples = collectSamples(config, windowStart, windowEnd)

        val throughputResult = if (config.throughput.enabled && Instant.now().isAfter(nextThroughputAt)) {
            val result = runThroughputTest(config.throughput)
            nextThroughputAt = Instant.now().plusSeconds(config.throughput.interval_minutes.toLong() * 60)
            result
        } else {
            ThroughputResult(sampled = false)
        }

        val windowJson = buildWindowJson(config, windowStart, windowEnd, samples, interfaceInfo, throughputResult)
        logFile.appendText(json.encodeToString(windowJson) + "\n")
        val availability = windowJson["availability"]?.jsonObject
        val qualityState = availability?.get("quality_state")?.jsonPrimitive?.contentOrNull ?: "Unknown"
        val qualityScore = availability?.get("quality_score")?.jsonPrimitive?.intOrNull?.toString() ?: "--"
        logger.info("Window written. Availability=$qualityState score=$qualityScore")
    }
}

private fun parseConfigPath(args: Array<String>): Path {
    val index = args.indexOf("--config")
    return if (index >= 0 && index + 1 < args.size) {
        Paths.get(args[index + 1])
    } else {
        Paths.get("config.json")
    }
}

private data class WebConfig(
    val host: String,
    val port: Int,
    val frontendDir: Path,
    val disabled: Boolean
)

private fun parseWebConfig(args: Array<String>): WebConfig {
    val disabled = args.contains("--no-web")
    val host = findArgValue(args, "--host") ?: "0.0.0.0"
    val port = findArgValue(args, "--port")?.toIntOrNull() ?: 8080
    val frontendDir = findArgValue(args, "--frontend-dir")?.let { Paths.get(it) }
        ?: Paths.get("..", "frontend", "dist").normalize()
    return WebConfig(host, port, frontendDir, disabled)
}

private fun findArgValue(args: Array<String>, flag: String): String? {
    val index = args.indexOf(flag)
    return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}

private fun loadConfig(path: Path): Config {
    val content = Files.readString(path)
    return json.decodeFromString(Config.serializer(), content)
}

private fun collectSamples(config: Config, windowStart: Instant, windowEnd: Instant): Map<String, List<ProbeSample>> {
    val samples = config.targets.keys.associateWith { mutableListOf<ProbeSample>() }
    val intervalMs = config.probe_interval_seconds * 1000L
    val executor = Executors.newFixedThreadPool(max(1, config.targets.size))

    try {
        while (Instant.now().isBefore(windowEnd)) {
            val cycleStart = System.currentTimeMillis()
            val futures = config.targets.map { (name, target) ->
                name to executor.submit<ProbeSample> { probeTarget(name, target) }
            }
            futures.forEach { (name, future) ->
                val sample = try {
                    future.get(intervalMs, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    ProbeSample(name, null)
                }
                samples[name]?.add(sample)
            }

            val elapsed = System.currentTimeMillis() - cycleStart
            val sleepMs = intervalMs - elapsed
            if (sleepMs > 0) {
                Thread.sleep(sleepMs)
            }
        }
    } finally {
        executor.shutdownNow()
    }

    return samples
}

private data class ProbeSample(val name: String, val rttMs: Double?)

private fun probeTarget(name: String, target: String): ProbeSample {
    val os = System.getProperty("os.name").lowercase(Locale.getDefault())
    val timeoutMs = 1000
    val command = when {
        os.contains("mac") -> listOf("ping", "-n", "-c", "1", "-W", timeoutMs.toString(), target)
        os.contains("linux") -> listOf("ping", "-n", "-c", "1", "-W", "1", target)
        else -> listOf("ping", "-n", "-c", "1", target)
    }

    val result = runCommand(command, timeoutMs + 500)
    val rtt = parsePingTimeMs(result.output)
    return ProbeSample(name, rtt)
}

private data class CommandResult(
    val exitCode: Int?,
    val output: String,
    val error: String? = null,
    val timedOut: Boolean = false
)

private fun runCommand(command: List<String>, timeoutMs: Int): CommandResult {
    return try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            CommandResult(null, "", timedOut = true)
        } else {
            val output = process.inputStream.bufferedReader().readText()
            CommandResult(process.exitValue(), output)
        }
    } catch (e: Exception) {
        CommandResult(null, "", error = e.message)
    }
}

private fun parsePingTimeMs(output: String): Double? {
    val regex = Regex("time[=<]([0-9.]+) ?ms")
    val match = regex.find(output) ?: return null
    return match.groupValues[1].toDoubleOrNull()
}

private fun buildWindowJson(
    config: Config,
    windowStart: Instant,
    windowEnd: Instant,
    samples: Map<String, List<ProbeSample>>,
    interfaceInfo: InterfaceInfo,
    throughputResult: ThroughputResult
): JsonObject {
    val targetsJson = buildJsonObject {
        config.targets.forEach { (name, target) ->
            put(name, JsonPrimitive(target))
        }
    }

    val targetMetrics = samples.mapValues { (name, sampleList) ->
        computeTargetMetrics(sampleList.map { it.rttMs }, config, config.probe_interval_seconds)
    }

    val availability = computeAvailability(config, targetMetrics)

    return buildJsonObject {
        val windowEndInclusive = if (config.window_seconds > 0) windowEnd.minusSeconds(1) else windowEnd
        put("timestamp", JsonPrimitive(timestampFormat.format(windowEnd)))
        put("window_start", JsonPrimitive(timestampFormat.format(windowStart)))
        put("window_end", JsonPrimitive(timestampFormat.format(windowEndInclusive)))
        put("interface", JsonPrimitive(interfaceInfo.name))
        put("link_type", JsonPrimitive(interfaceInfo.linkType))
        if (interfaceInfo.ssid == null) {
            put("ssid", JsonNull)
        } else {
            put("ssid", JsonPrimitive(interfaceInfo.ssid))
        }
        put("targets", targetsJson)

        targetMetrics.forEach { (name, metrics) ->
            put(name, json.encodeToJsonElement(TargetMetrics.serializer(), metrics))
        }

        put("throughput", json.encodeToJsonElement(ThroughputResult.serializer(), throughputResult))
        put("availability", json.encodeToJsonElement(Availability.serializer(), availability))
    }
}

private fun computeTargetMetrics(rtts: List<Double?>, config: Config, intervalSeconds: Int): TargetMetrics {
    val sent = rtts.size
    val receivedValues = rtts.filterNotNull()
    val received = receivedValues.size
    val lossPct = if (sent == 0) 100.0 else ((sent - received).toDouble() / sent.toDouble()) * 100.0

    val rttMin = receivedValues.minOrNull()
    val rttMax = receivedValues.maxOrNull()
    val rttMean = receivedValues.averageOrNull()
    val rttStdDev = receivedValues.stdDevOrNull(rttMean)
    val rttP50 = receivedValues.percentileOrNull(50.0)
    val rttP90 = receivedValues.percentileOrNull(90.0)
    val rttP99 = receivedValues.percentileOrNull(99.0)

    val jitterValues = computeJitter(receivedValues)
    val jitterP50 = jitterValues.percentileOrNull(50.0)
    val jitterP90 = jitterValues.percentileOrNull(90.0)

    val spikeThreshold = if (rttP50 != null) rttP50 * config.thresholds.rtt_spike_factor else null
    val spikeCount = if (spikeThreshold == null) 0 else receivedValues.count { it > spikeThreshold }

    val outageSeconds = (rtts.count { it == null } * intervalSeconds)

    return TargetMetrics(
        sent = sent,
        received = received,
        loss_pct = roundToPlaces(lossPct, 2),
        rtt_ms_min = rttMin,
        rtt_ms_p50 = rttP50,
        rtt_ms_p90 = rttP90,
        rtt_ms_p99 = rttP99,
        rtt_ms_max = rttMax,
        rtt_ms_mean = rttMean,
        rtt_ms_stddev = rttStdDev,
        jitter_ms_p50 = jitterP50,
        jitter_ms_p90 = jitterP90,
        rtt_spike_count = spikeCount,
        outage_seconds = outageSeconds
    )
}

private fun computeAvailability(config: Config, metrics: Map<String, TargetMetrics>): Availability {
    val targetScores = metrics.mapValues { (_, metric) ->
        val available = metric.loss_pct <= config.thresholds.loss_pct_max &&
            metric.rtt_ms_p90 != null && metric.rtt_ms_p90 <= config.thresholds.rtt_ms_p90_max &&
            metric.jitter_ms_p90 != null && metric.jitter_ms_p90 <= config.thresholds.jitter_ms_p90_max

        val score = computeQualityScore(metric, config)
        TargetAvailability(available, score)
    }

    val windowAvailable = targetScores.values.all { it.available }
    val averageScore = if (targetScores.isEmpty()) 0 else {
        targetScores.values.map { it.score }.average().roundToInt()
    }

    val state = when {
        averageScore >= 80 && windowAvailable -> "Good"
        averageScore >= 50 -> "Warning"
        else -> "Bad"
    }

    return Availability(windowAvailable, averageScore, state)
}

private data class TargetAvailability(val available: Boolean, val score: Int)

private fun computeQualityScore(metric: TargetMetrics, config: Config): Int {
    val lossPenalty = penalty(metric.loss_pct, config.thresholds.loss_pct_max, 40.0)
    val rttPenalty = penalty(metric.rtt_ms_p90, config.thresholds.rtt_ms_p90_max, 30.0)
    val jitterPenalty = penalty(metric.jitter_ms_p90, config.thresholds.jitter_ms_p90_max, 30.0)
    val score = 100.0 - (lossPenalty + rttPenalty + jitterPenalty)
    return max(0, min(100, score.roundToInt()))
}

private fun penalty(value: Double?, threshold: Double, weight: Double): Double {
    if (value == null) return weight
    if (threshold <= 0) return if (value > 0) weight else 0.0
    if (value <= threshold) return 0.0
    val ratio = (value / threshold) - 1.0
    return min(weight, ratio * weight)
}

private fun computeJitter(values: List<Double>): List<Double> {
    if (values.size < 2) return emptyList()
    return values.zipWithNext { a, b -> kotlin.math.abs(b - a) }
}

private fun List<Double>.averageOrNull(): Double? {
    return if (isEmpty()) null else average()
}

private fun List<Double>.stdDevOrNull(mean: Double?): Double? {
    if (isEmpty() || mean == null) return null
    val variance = map { (it - mean) * (it - mean) }.average()
    return sqrt(variance)
}

private fun List<Double>.percentileOrNull(percentile: Double): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val rank = ceil((percentile / 100.0) * sorted.size).toInt().coerceAtLeast(1)
    return sorted[rank - 1]
}

private fun roundToPlaces(value: Double, places: Int): Double {
    val factor = 10.0.pow(places.toDouble())
    return (value * factor).roundToInt() / factor
}

private data class InterfaceInfo(val name: String, val linkType: String, val ssid: String?)

private fun detectInterfaceInfo(): InterfaceInfo {
    val interfaces = NetworkInterface.getNetworkInterfaces().toList()
    val active = interfaces.firstOrNull { it.isUp && !it.isLoopback && it.inetAddresses.toList().isNotEmpty() }
    val name = active?.name ?: "unknown"
    val linkType = if (name.startsWith("wl") || name.contains("wi")) "wifi" else "wired"
    return InterfaceInfo(name, linkType, null)
}

private fun <T> java.util.Enumeration<T>.toList(): List<T> {
    val result = mutableListOf<T>()
    while (hasMoreElements()) {
        result.add(nextElement())
    }
    return result
}

private fun runThroughputTest(config: ThroughputConfig): ThroughputResult {
    if (config.source.lowercase(Locale.getDefault()) != "iperf3") {
        return ThroughputResult(sampled = false)
    }
    val server = config.server ?: return ThroughputResult(sampled = false)

    val ports = resolveThroughputPorts(config)
    val attempts = max(1, config.retries + 1)

    var lastResult: CommandResult? = null
    var lastPort: Int? = null
    repeat(attempts) { attempt ->
        val port = ports[attempt % ports.size]
        lastPort = port
        val command = mutableListOf("iperf3", "-c", server, "-t", config.test_duration_s.toString(), "-J")
        if (port != null) {
            command.addAll(listOf("-p", port.toString()))
        }

        val result = runCommand(command, (config.test_duration_s + 5) * 1000)
        lastResult = result
        val parsed = parseIperf3(result.output, config)
        if (parsed.sampled) {
            return parsed
        }

        if (attempt < attempts - 1) {
            val portLabel = port?.toString() ?: "default"
            AppLogger.global.warn(
                "Throughput test failed on port $portLabel; exit=${result.exitCode} timeout=${result.timedOut} error=${result.error}"
            )
            AppLogger.global.warn("iperf3 command: ${command.joinToString(" ")}")
            AppLogger.global.warn("iperf3 output (debug): ${result.output}")
            Thread.sleep(config.retry_delay_ms.toLong())
        }
    }
    val finalPortLabel = lastPort?.toString() ?: "default"
    AppLogger.global.warn(
        "Throughput test failed after retries on port $finalPortLabel; exit=${lastResult?.exitCode} timeout=${lastResult?.timedOut} error=${lastResult?.error}"
    )
    AppLogger.global.warn("iperf3 output (debug): ${lastResult?.output ?: ""}")
    return ThroughputResult(sampled = false)
}

private fun resolveThroughputPorts(config: ThroughputConfig): List<Int?> {
    val range = config.port_range
    if (range != null && range.size == 2) {
        val start = range[0]
        val end = range[1]
        if (start <= end) {
            return (start..end).toList()
        }
        return (end..start).toList()
    }
    return listOf(config.port ?: 5201)
}

private fun startWebServer(config: WebConfig, logsDir: Path, logger: AppLogger) {
    val server = HttpServer.create(InetSocketAddress(config.host, config.port), 0)
    server.executor = Executors.newFixedThreadPool(4)

    if (!Files.exists(config.frontendDir.resolve("index.html"))) {
        logger.warn("Frontend build not found at ${config.frontendDir.toAbsolutePath()}. Run 'npm run build' in frontend/.")
    }

    server.createContext("/logs") { exchange ->
        if (exchange.requestMethod != "GET") {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain")
            return@createContext
        }
        val requestedPath = exchange.requestURI.path.removePrefix("/logs").removePrefix("/")
        if (requestedPath.isEmpty()) {
            sendResponse(exchange, 404, "Not Found", "text/plain")
            return@createContext
        }
        val target = logsDir.resolve(requestedPath).normalize()
        if (!target.startsWith(logsDir)) {
            sendResponse(exchange, 403, "Forbidden", "text/plain")
            return@createContext
        }
        serveFile(exchange, target)
    }

    server.createContext("/") { exchange ->
        if (exchange.requestMethod != "GET") {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain")
            return@createContext
        }
        val path = exchange.requestURI.path.removePrefix("/").ifEmpty { "index.html" }
        val frontendPath = config.frontendDir.resolve(path).normalize()
        if (Files.exists(frontendPath) && Files.isRegularFile(frontendPath)) {
            serveFile(exchange, frontendPath)
            return@createContext
        }
        val fallback = config.frontendDir.resolve("index.html")
        if (Files.exists(fallback)) {
            serveFile(exchange, fallback)
            return@createContext
        }
        sendResponse(exchange, 404, "Not Found", "text/plain")
    }

    server.start()
    logger.info("Web server started on http://${config.host}:${config.port}")
    logger.info("Serving frontend from ${config.frontendDir.toAbsolutePath()}")
    logger.info("Serving logs from ${logsDir.toAbsolutePath()} at /logs/")
}

private fun serveFile(exchange: HttpExchange, path: Path) {
    if (!Files.exists(path) || !Files.isRegularFile(path)) {
        sendResponse(exchange, 404, "Not Found", "text/plain")
        return
    }
    val bytes = Files.readAllBytes(path)
    val contentType = contentTypeFor(path.fileName.toString())
    exchange.responseHeaders.set("Content-Type", contentType)
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun sendResponse(exchange: HttpExchange, status: Int, body: String, contentType: String) {
    val bytes = body.toByteArray()
    exchange.responseHeaders.set("Content-Type", contentType)
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun contentTypeFor(filename: String): String {
    return when (filename.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "jsonl" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }
}

private class AppLogger(private val file: java.io.File) {
    companion object {
        lateinit var global: AppLogger
            private set
    }

    init {
        global = this
    }

    fun info(message: String) = log("INFO", message)
    fun warn(message: String) = log("WARN", message)
    fun error(message: String) = log("ERROR", message)

    private fun log(level: String, message: String) {
        val line = "${timestampFormat.format(Instant.now())} [$level] $message"
        println(line)
        file.appendText(line + "\n")
    }
}

private fun parseIperf3(output: String, config: ThroughputConfig): ThroughputResult {
    return try {
        val root = json.parseToJsonElement(output).jsonObject
        val end = root["end"]?.jsonObject ?: return ThroughputResult(sampled = false)
        val sumReceived = end["sum_received"]?.jsonObject
        val sumSent = end["sum_sent"]?.jsonObject
        val dlBits = sumReceived?.get("bits_per_second")?.jsonPrimitive?.doubleOrNull
        val ulBits = sumSent?.get("bits_per_second")?.jsonPrimitive?.doubleOrNull
        ThroughputResult(
            sampled = true,
            dl_mbps = dlBits?.div(1_000_000.0),
            ul_mbps = ulBits?.div(1_000_000.0),
            test_duration_s = config.test_duration_s,
            test_source = "iperf3"
        )
    } catch (e: Exception) {
        ThroughputResult(sampled = false)
    }
}
