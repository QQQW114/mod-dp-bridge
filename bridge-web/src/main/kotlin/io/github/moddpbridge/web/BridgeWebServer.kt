package io.github.moddpbridge.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension

class BridgeWebServer(private val config: WebConfig) : AutoCloseable {
    private val httpExecutor = Executors.newFixedThreadPool(
        config.maxSseClients + 8,
        namedThreadFactory("bridge-http", daemon = true),
    )
    private val ssePermits = Semaphore(config.maxSseClients)
    private val jobs = JobManager(config)
    private val server = HttpServer.create(config.address, 0).apply {
        executor = httpExecutor
        createContext("/") { exchange -> dispatch(exchange) }
    }

    val address: InetSocketAddress get() = server.address

    fun start() = server.start()

    private fun dispatch(exchange: HttpExchange) {
        addSecurityHeaders(exchange)
        try {
            if (!requestHostAllowed(exchange)) {
                sendError(exchange, 421, "host_not_allowed", "The HTTP Host header is not allowed by this server.")
                return
            }
            if (exchange.requestMethod == "OPTIONS") {
                exchange.responseHeaders.set("Allow", "GET, HEAD, POST, DELETE, OPTIONS")
                exchange.sendResponseHeaders(204, -1)
                return
            }
            if (exchange.requestMethod == "POST" || exchange.requestMethod == "DELETE") {
                if (!mutationOriginAllowed(exchange)) {
                    sendError(exchange, 403, "cross_site_request_rejected", "Cross-site state-changing requests are not allowed.")
                    return
                }
            }
            val path = exchange.requestURI.path
            if (path == "/api/health") return handleHealth(exchange)
            if (path == "/api/jobs") return handleJobs(exchange)
            if (path.startsWith("/api/jobs/")) return handleJobRoute(exchange, path.removePrefix("/api/jobs/"))
            if (path.startsWith("/api/")) return sendError(exchange, 404, "not_found", "API route not found.")
            handleStatic(exchange)
        } catch (error: UploadException) {
            if (exchange.responseCode < 0) sendError(exchange, error.statusCode, "upload_rejected", error.message ?: "Upload rejected.")
        } catch (error: RejectedExecutionException) {
            if (exchange.responseCode < 0) sendError(exchange, 429, "queue_full", "The conversion queue is full.")
        } catch (error: Throwable) {
            if (exchange.responseCode < 0) {
                sendError(exchange, 500, "internal_error", "The server could not complete this request.")
            } else if (error !is IOException) {
                System.err.println("Web request failed after headers were sent: ${error.stackTraceToString()}")
            }
        } finally {
            exchange.close()
        }
    }

    private fun handleHealth(exchange: HttpExchange) {
        requireMethod(exchange, "GET") ?: return
        val snapshots = jobs.list()
        sendJson(
            exchange,
            200,
            jsonObject(
                "status" to jsonString("ok"),
                "time" to jsonString(Instant.now().toString()),
                "activeJobs" to snapshots.count { !it.status.terminal }.toString(),
                "maxConcurrentJobs" to config.maxConcurrentJobs.toString(),
                "maxUploadBytes" to config.maxUploadBytes.toString(),
            ),
        )
    }

    private fun handleJobs(exchange: HttpExchange) {
        when (exchange.requestMethod) {
            "POST" -> createJob(exchange)
            "GET" -> sendJson(
                exchange,
                200,
                jsonObject("jobs" to jobs.list().joinToString(prefix = "[", postfix = "]") { it.toJson() }),
            )
            else -> methodNotAllowed(exchange, "GET, POST")
        }
    }

    private fun createJob(exchange: HttpExchange) {
        val reservation = jobs.reserveUpload()
            ?: return sendError(exchange, 429, "queue_full", "The conversion queue is full; try again later.")
        var reservationOwned = true
        try {
            val upload = MultipartUpload.receive(exchange, reservation.inputDirectory, config.maxUploadBytes)
            // JobManager.submit consumes the reservation whether executor submission
            // succeeds or is rejected; do not decrement it a second time here.
            reservationOwned = false
            val job = jobs.submit(reservation, upload)
            exchange.responseHeaders.set("Location", "/api/jobs/${job.id}")
            sendJson(exchange, 201, job.snapshot().toJson())
        } catch (error: Throwable) {
            if (reservationOwned) jobs.abandonUpload(reservation)
            throw error
        }
    }

    private fun handleJobRoute(exchange: HttpExchange, remainder: String) {
        val parts = remainder.split('/').filter(String::isNotBlank)
        val id = parts.firstOrNull()?.takeIf(::validJobId)
            ?: return sendError(exchange, 404, "job_not_found", "Conversion job not found.")
        val job = jobs.get(id)
            ?: return sendError(exchange, 404, "job_not_found", "Conversion job not found.")
        val action = parts.drop(1)
        when {
            action.isEmpty() && exchange.requestMethod == "GET" -> sendJson(exchange, 200, job.snapshot().toJson())
            action.isEmpty() && exchange.requestMethod == "DELETE" -> cancelJob(exchange, job)
            action == listOf("cancel") && exchange.requestMethod == "POST" -> cancelJob(exchange, job)
            action == listOf("events") && exchange.requestMethod == "GET" -> streamEvents(exchange, job)
            action == listOf("report") && exchange.requestMethod == "GET" -> sendReport(exchange, job)
            (action == listOf("download", "result") || action == listOf("result")) && exchange.requestMethod == "GET" -> {
                sendResult(exchange, job)
            }
            (action == listOf("download", "logs") || action == listOf("logs")) && exchange.requestMethod == "GET" -> {
                sendLogs(exchange, job)
            }
            action.isEmpty() -> methodNotAllowed(exchange, "GET, DELETE")
            action == listOf("cancel") -> methodNotAllowed(exchange, "POST")
            else -> sendError(exchange, 404, "route_not_found", "Job route not found.")
        }
    }

    private fun cancelJob(exchange: HttpExchange, job: ConversionJob) {
        val changed = jobs.cancel(job)
        sendJson(exchange, if (changed) 202 else 200, job.snapshot().toJson())
    }

    private fun streamEvents(exchange: HttpExchange, job: ConversionJob) {
        if (!ssePermits.tryAcquire()) {
            sendError(exchange, 429, "too_many_streams", "Too many live log streams are open.")
            return
        }
        val lastEventId = exchange.requestHeaders.getFirst("Last-Event-ID")?.toLongOrNull()
        val subscription = job.events.subscribe(lastEventId)
        try {
            exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-cache, no-transform")
            exchange.responseHeaders.set("Connection", "keep-alive")
            exchange.responseHeaders.set("X-Accel-Buffering", "no")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write("retry: 2000\n\n")
                subscription.replay.forEach { event -> writeEvent(writer, event) }
                // Replay logs/lifecycle first. Sending a terminal snapshot before replay
                // makes browsers close EventSource immediately and lose the audit log.
                writeEvent(writer, ServerEvent(0, "snapshot", job.snapshot().toJson()), includeId = false)
                writer.flush()

                while (true) {
                    // A fast job can finish while replay and the snapshot are being
                    // written. Its tail events then live in the subscription queue,
                    // so a terminal snapshot must not make us return before draining
                    // them. The short terminal grace also closes the tiny gap between
                    // updating the job state and publishing its final status event.
                    val terminal = job.snapshot().status.terminal
                    val event = if (terminal) {
                        subscription.queue.poll(100, TimeUnit.MILLISECONDS)
                    } else {
                        subscription.queue.poll(15, TimeUnit.SECONDS)
                    }
                    if (event == null) {
                        if (terminal || job.snapshot().status.terminal) break
                        writer.write(": keep-alive\n\n")
                        writer.flush()
                    } else {
                        writeEvent(writer, event)
                        writer.flush()
                    }
                }
            }
        } finally {
            job.events.unsubscribe(subscription.queue)
            ssePermits.release()
        }
    }

    private fun writeEvent(writer: Appendable, event: ServerEvent, includeId: Boolean = true) {
        if (includeId) writer.append("id: ").append(event.sequence.toString()).append('\n')
        writer.append("event: ").append(event.name).append('\n')
        writer.append("data: ").append(event.data).append("\n\n")
    }

    private fun sendReport(exchange: HttpExchange, job: ConversionJob) {
        val report = job.findReport()
            ?: return sendError(exchange, 404, "report_not_ready", "The completed conversion report is not available.")
        sendFile(exchange, report, "application/json; charset=utf-8", null)
    }

    private fun sendResult(exchange: HttpExchange, job: ConversionJob) {
        val result = job.findResult()
            ?: return sendError(exchange, 404, "result_not_ready", "The converted data-pack archive is not available.")
        sendFile(exchange, result, "application/zip", result.fileName.toString())
    }

    private fun sendLogs(exchange: HttpExchange, job: ConversionJob) {
        if (!job.snapshot().logsAvailable) {
            sendError(exchange, 404, "logs_not_ready", "Conversion logs are not available yet.")
            return
        }
        val filename = "mod-dp-bridge-${job.id}-logs.zip"
        exchange.responseHeaders.set("Content-Type", "application/zip")
        exchange.responseHeaders.set("Content-Disposition", contentDisposition(filename))
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, 0)
        ZipOutputStream(exchange.responseBody.buffered()).use { zip ->
            val entries = mutableSetOf<String>()
            addZipFile(zip, job.webLog, "web-process.log", entries)
            val converterLogs = job.outputDirectory.resolve("logs")
            if (Files.isDirectory(converterLogs)) {
                Files.walk(converterLogs).use { paths ->
                    paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                        .sorted()
                        .forEach { path ->
                            val relative = converterLogs.relativize(path).joinToString("/")
                            addZipFile(zip, path, "converter/$relative", entries)
                        }
                }
            }
            listOf("report.json", "report.md", "failure-report.txt", "failure-diagnostics.json").forEach { name ->
                addZipFile(zip, job.outputDirectory.resolve(name), name, entries)
            }
        }
    }

    private fun addZipFile(zip: ZipOutputStream, file: Path, entryName: String, entries: MutableSet<String>) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !entries.add(entryName)) return
        val entry = ZipEntry(entryName).apply { time = 0L }
        zip.putNextEntry(entry)
        Files.newInputStream(file, StandardOpenOption.READ).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun sendFile(exchange: HttpExchange, file: Path, contentType: String, downloadName: String?) {
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        downloadName?.let { exchange.responseHeaders.set("Content-Disposition", contentDisposition(it)) }
        exchange.sendResponseHeaders(200, Files.size(file))
        Files.newInputStream(file).use { input -> exchange.responseBody.use(input::copyTo) }
    }

    private fun handleStatic(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
            methodNotAllowed(exchange, "GET, HEAD")
            return
        }
        val requested = exchange.requestURI.path
        if (requested.split('/').any { it == ".." }) {
            sendError(exchange, 400, "invalid_path", "Invalid resource path.")
            return
        }
        val direct = if (requested == "/") "/web/index.html" else "/web$requested"
        var resource = javaClass.getResourceAsStream(direct)
        var resourceName = direct
        if (resource == null && !requested.substringAfterLast('/').contains('.')) {
            resourceName = "/web/index.html"
            resource = javaClass.getResourceAsStream(resourceName)
        }
        if (resource == null) {
            sendError(exchange, 404, "not_found", "Resource not found.")
            return
        }
        resource.use { input ->
            val bytes = input.readAllBytes()
            exchange.responseHeaders.set("Content-Type", contentType(resourceName))
            // Resource names are intentionally simple (app.js/styles.css), not
            // content-hashed. Revalidate them so an upgraded backend never serves
            // a stale frontend against a newer API contract.
            exchange.responseHeaders.set("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, if (exchange.requestMethod == "HEAD") -1 else bytes.size.toLong())
            if (exchange.requestMethod != "HEAD") exchange.responseBody.write(bytes)
        }
    }

    private fun contentType(path: String): String = when (Path.of(path).extension.lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun validJobId(value: String): Boolean = value.length == 36 && value.all { it.isLetterOrDigit() || it == '-' }

    private fun requireMethod(exchange: HttpExchange, method: String): Unit? {
        if (exchange.requestMethod == method) return Unit
        methodNotAllowed(exchange, method)
        return null
    }

    private fun methodNotAllowed(exchange: HttpExchange, allow: String) {
        exchange.responseHeaders.set("Allow", allow)
        sendError(exchange, 405, "method_not_allowed", "Method not allowed.")
    }

    private fun sendJson(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun sendError(exchange: HttpExchange, status: Int, code: String, message: String) {
        sendJson(exchange, status, errorJson(code, message))
    }

    private fun contentDisposition(filename: String): String {
        val fallback = filename.map { character ->
            if (character.code in 0x20..0x7e && character != '"' && character != '\\') character else '_'
        }.joinToString("").ifBlank { "download.zip" }
        val encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20")
        return "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded"
    }

    private fun addSecurityHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
        exchange.responseHeaders.set("X-Frame-Options", "DENY")
        exchange.responseHeaders.set(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; " +
                "img-src 'self' data:; font-src 'self'; object-src 'none'; base-uri 'none'; " +
                "frame-ancestors 'none'; form-action 'self'",
        )
        exchange.responseHeaders.set(
            "Permissions-Policy",
            "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
        )
    }

    private fun mutationOriginAllowed(exchange: HttpExchange): Boolean {
        if (exchange.requestHeaders.getFirst("Sec-Fetch-Site")?.equals("cross-site", ignoreCase = true) == true) {
            return false
        }
        val origin = exchange.requestHeaders.getFirst("Origin") ?: return true
        if (origin.equals("null", ignoreCase = true)) return false
        val authority = runCatching { URI(origin).rawAuthority }.getOrNull() ?: return false
        val host = exchange.requestHeaders.getFirst("Host") ?: return false
        return authority.equals(host, ignoreCase = true)
    }

    private fun requestHostAllowed(exchange: HttpExchange): Boolean {
        val requestHost = exchange.requestHeaders.getFirst("Host")?.let(WebConfig::canonicalHost) ?: return false
        return requestHost in config.allowedHosts
    }

    override fun close() {
        server.stop(1)
        jobs.close()
        httpExecutor.shutdownNow()
    }
}
