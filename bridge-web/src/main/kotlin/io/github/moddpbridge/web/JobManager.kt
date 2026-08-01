package io.github.moddpbridge.web

import io.github.moddpbridge.model.ConversionReportJson
import io.github.moddpbridge.model.OutputArtifactKind
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlin.io.path.isRegularFile

private const val MAX_LIVE_LOG_LINE_CHARS = 16_384
private const val TERMINAL_EVENT_HISTORY = 1_000
private const val MAX_REQUEST_ID_CLAIMS = 10_000
private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

internal enum class JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    val apiName: String get() = name.lowercase()
    val terminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

internal data class UploadReservation(val id: String, val requestId: String, val root: Path, val inputDirectory: Path)

internal data class JobSnapshot(
    val id: String,
    val requestId: String,
    val fileName: String,
    val sourceFileName: String?,
    val mode: ConversionMode,
    val status: JobStatus,
    val progress: Int,
    val phase: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val exitCode: Int?,
    val message: String?,
    val resultAvailable: Boolean,
    val reportAvailable: Boolean,
    val logsAvailable: Boolean,
) {
    fun toJson(): String = jsonObject(
        "id" to jsonString(id),
        "requestId" to jsonString(requestId),
        "fileName" to jsonString(fileName),
        "sourceFileName" to jsonString(sourceFileName),
        "mode" to jsonString(mode.apiName),
        "status" to jsonString(status.apiName),
        "progress" to progress.toString(),
        "phase" to jsonString(phase),
        "createdAt" to jsonString(createdAt.toString()),
        "startedAt" to jsonString(startedAt?.toString()),
        "finishedAt" to jsonString(finishedAt?.toString()),
        "exitCode" to (exitCode?.toString() ?: "null"),
        "message" to jsonString(message),
        "resultAvailable" to resultAvailable.toString(),
        "reportAvailable" to reportAvailable.toString(),
        "logsAvailable" to logsAvailable.toString(),
    )
}

internal data class ServerEvent(val sequence: Long, val name: String, val data: String)

internal data class EventSubscription(
    val replay: List<ServerEvent>,
    val queue: BlockingQueue<ServerEvent>,
)

internal class JobEventBus(private val maxHistory: Int = 10_000) {
    private val lock = Any()
    private val sequence = AtomicLong()
    private val history = ArrayDeque<ServerEvent>()
    private val subscribers = mutableSetOf<BlockingQueue<ServerEvent>>()

    fun publish(name: String, data: String) {
        synchronized(lock) {
            emitLocked(ServerEvent(sequence.incrementAndGet(), name, data))
        }
    }

    fun publishLog(line: String) {
        synchronized(lock) {
            val next = sequence.incrementAndGet()
            emitLocked(
                ServerEvent(
                    next,
                    "log",
                    jsonObject("sequence" to next.toString(), "line" to jsonString(line)),
                ),
            )
        }
    }

    /** Caller must hold [lock] so sequence allocation and delivery stay in the same order. */
    private fun emitLocked(event: ServerEvent) {
        history.addLast(event)
        while (history.size > maxHistory) history.removeFirst()
        val failed = mutableListOf<BlockingQueue<ServerEvent>>()
        subscribers.forEach { queue -> if (!queue.offer(event)) failed += queue }
        failed.forEach(subscribers::remove)
    }

    fun subscribe(afterSequence: Long?): EventSubscription = synchronized(lock) {
        val queue = ArrayBlockingQueue<ServerEvent>(4096)
        subscribers += queue
        val replay = if (afterSequence == null) history.toList() else history.filter { it.sequence > afterSequence }
        EventSubscription(replay, queue)
    }

    fun unsubscribe(queue: BlockingQueue<ServerEvent>) {
        synchronized(lock) { subscribers.remove(queue) }
    }

    fun compact(maxEntries: Int) {
        synchronized(lock) {
            while (history.size > maxEntries.coerceAtLeast(0)) history.removeFirst()
        }
    }
}

private data class TerminalArtifacts(
    val report: Path? = null,
    val reportSize: Long? = null,
    val result: Path? = null,
    val resultSize: Long? = null,
    val failure: String? = null,
)

internal class ConversionJob(
    val id: String,
    val requestId: String = id,
    val originalFileName: String,
    val input: Path,
    val root: Path,
    val mode: ConversionMode = ConversionMode.STATIC,
    val source: Path? = null,
    val sourceOriginalFileName: String? = null,
    val executionAcknowledged: Boolean = false,
    private val redactor: WebOutputRedactor = WebOutputRedactor(),
) {
    val outputDirectory: Path = root.resolve("output")
    val webLog: Path = root.resolve("logs/web-process.log")
    val events = JobEventBus()

    private val lock = Any()
    private var status = JobStatus.QUEUED
    private var progress = 0
    private var phase = "queued"
    private val createdAt = Instant.now()
    private var startedAt: Instant? = null
    private var finishedAt: Instant? = null
    private var exitCode: Int? = null
    private var message: String? = "正在等待可用的转换槽位。"
    private var terminalArtifacts: TerminalArtifacts? = null
    @Volatile var cancelRequested: Boolean = false
        private set
    @Volatile var process: Process? = null
    @Volatile var queuedTask: Runnable? = null

    fun snapshot(): JobSnapshot = synchronized(lock) {
        val terminal = status.terminal
        val artifactsVisible = terminal && status != JobStatus.CANCELLED
        val artifacts = terminalArtifacts
        val report = artifactsVisible && artifacts?.report != null
        val result = artifactsVisible && artifacts?.result != null
        JobSnapshot(
            id = id,
            requestId = requestId,
            fileName = originalFileName,
            sourceFileName = sourceOriginalFileName,
            mode = mode,
            status = status,
            progress = progress,
            phase = phase,
            createdAt = createdAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            exitCode = exitCode,
            message = message,
            resultAvailable = result,
            reportAvailable = report,
            logsAvailable = terminal && (
                safeJobFile(webLog) != null || safeJobDirectory(outputDirectory.resolve("logs")) != null
                ),
        )
    }

    fun markRunning(): Boolean {
        synchronized(lock) {
            if (status.terminal || cancelRequested) return false
            status = JobStatus.RUNNING
            progress = maxOf(progress, 5)
            phase = "starting"
            startedAt = Instant.now()
            message = "正在启动独立转换进程。"
        }
        publishState("status")
        return true
    }

    fun updateProgress(newProgress: Int, newPhase: String) {
        var changed = false
        synchronized(lock) {
            if (!status.terminal && (newProgress > progress || newPhase != phase)) {
                progress = maxOf(progress, newProgress.coerceIn(0, 99))
                phase = newPhase
                message = phaseMessage(newPhase)
                changed = true
            }
        }
        if (changed) publishState("progress")
    }

    fun finish(code: Int) {
        val validated = if (cancelRequested) TerminalArtifacts() else validateTerminalArtifacts()
        synchronized(lock) {
            exitCode = code
            finishedAt = Instant.now()
            progress = 100
            terminalArtifacts = if (cancelRequested) TerminalArtifacts() else validated
            when {
                cancelRequested -> {
                    status = JobStatus.CANCELLED
                    phase = "cancelled"
                    message = "转换任务已终止。"
                }
                code == 0 && validated.report != null && validated.result != null -> {
                    status = JobStatus.SUCCEEDED
                    phase = "completed"
                    message = "转换任务已完成。"
                }
                code == 0 -> {
                    status = JobStatus.FAILED
                    phase = "failed"
                    message = redact(
                        "转换器虽然返回退出码 0，但终态产物校验失败：" +
                            (validated.failure ?: "缺少有效的 report.json 或 Data Pack ZIP。"),
                    )
                }
                else -> {
                    status = JobStatus.FAILED
                    phase = "failed"
                    message = "转换器退出码：$code。"
                }
            }
        }
        publishState("status")
        events.compact(TERMINAL_EVENT_HISTORY)
    }

    fun fail(messageText: String) {
        synchronized(lock) {
            finishedAt = Instant.now()
            progress = 100
            status = if (cancelRequested) JobStatus.CANCELLED else JobStatus.FAILED
            phase = if (cancelRequested) "cancelled" else "failed"
            terminalArtifacts = TerminalArtifacts()
            message = redact(messageText)
        }
        publishState("status")
        events.compact(TERMINAL_EVENT_HISTORY)
    }

    fun requestCancellation(): JobStatus? {
        val previous: JobStatus
        synchronized(lock) {
            if (status.terminal) return null
            previous = status
            cancelRequested = true
            message = "已请求终止，正在停止转换进程。"
            phase = "cancelling"
        }
        publishState("status")
        return previous
    }

    fun cancelBeforeStart() {
        synchronized(lock) {
            if (status.terminal) return
            cancelRequested = true
            status = JobStatus.CANCELLED
            phase = "cancelled"
            progress = 100
            finishedAt = Instant.now()
            terminalArtifacts = TerminalArtifacts()
            message = "任务已在开始转换前终止。"
        }
        publishState("status")
        events.compact(TERMINAL_EVENT_HISTORY)
    }

    fun appendLog(line: String, writer: BufferedWriter) {
        val safeLine = redact(line)
        writer.write(safeLine)
        writer.newLine()
        writer.flush()
        val liveLine = if (safeLine.length <= MAX_LIVE_LOG_LINE_CHARS) safeLine else {
            safeLine.take(MAX_LIVE_LOG_LINE_CHARS) + " … [实时视图已截断；请下载完整日志]"
        }
        events.publishLog(liveLine)
        inferProgress(safeLine)?.let { (value, inferredPhase) -> updateProgress(value, inferredPhase) }
    }

    fun findResult(): Path? {
        if (!artifactsVisible()) return null
        val cached = synchronized(lock) { terminalArtifacts } ?: return null
        val path = cached.result ?: return null
        val safe = safeOutputFile(path) ?: return null
        return safe.takeIf { cached.resultSize == Files.size(it) }
    }

    fun findReport(): Path? {
        if (!artifactsVisible()) return null
        val cached = synchronized(lock) { terminalArtifacts } ?: return null
        val path = cached.report ?: return null
        val safe = safeOutputFile(path) ?: return null
        return safe.takeIf { cached.reportSize == Files.size(it) }
    }

    fun isTerminal(): Boolean = synchronized(lock) { status.terminal }

    fun isCancelled(): Boolean = synchronized(lock) { status == JobStatus.CANCELLED }

    fun redact(value: String): String = redactor.redact(value)

    fun safeJobFile(path: Path): Path? = safeContainedFile(root, path)

    fun safeJobDirectory(path: Path): Path? = safeContainedDirectory(root, path)

    private fun artifactsVisible(): Boolean = synchronized(lock) { status.terminal && status != JobStatus.CANCELLED }

    private fun validateTerminalArtifacts(): TerminalArtifacts = runCatching {
            val reportPath = safeOutputFile(outputDirectory.resolve("report.json"))
                ?: return TerminalArtifacts(failure = "report.json 不存在、位于输出目录外或包含符号链接。")
            val reportText = Files.newInputStream(
                reportPath,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val report = ConversionReportJson.decode(reportText)
            val artifacts = report.outputs.filter { it.kind == OutputArtifactKind.DATA_PACK_ZIP }
            if (artifacts.size != 1) {
                return TerminalArtifacts(
                    report = reportPath,
                    reportSize = Files.size(reportPath),
                    failure = "report.json 必须且只能声明一个 dataPackZip 产物。",
                )
            }
            val artifact = artifacts.single()
            val root = outputDirectory.toAbsolutePath().normalize()
            val reported = Path.of(artifact.path)
            val candidate = (if (reported.isAbsolute) reported else root.resolve(reported)).toAbsolutePath().normalize()
            val safeCandidate = safeOutputFile(candidate)
                ?: return TerminalArtifacts(
                    report = reportPath,
                    reportSize = Files.size(reportPath),
                    failure = "dataPackZip 位于输出目录外、不是常规文件或包含符号链接。",
                )
            val expectedSize = artifact.sizeBytes
                ?: return TerminalArtifacts(reportPath, Files.size(reportPath), failure = "dataPackZip 缺少 sizeBytes。")
            if (expectedSize != Files.size(safeCandidate)) {
                return TerminalArtifacts(reportPath, Files.size(reportPath), failure = "dataPackZip 的 sizeBytes 与实际文件不一致。")
            }
            val expectedHash = artifact.sha256?.takeIf { it.matches(SHA256_PATTERN) }
                ?: return TerminalArtifacts(reportPath, Files.size(reportPath), failure = "dataPackZip 缺少有效 SHA-256。")
            if (!sha256(safeCandidate).equals(expectedHash, ignoreCase = true)) {
                return TerminalArtifacts(reportPath, Files.size(reportPath), failure = "dataPackZip 的 SHA-256 与 report.json 不一致。")
            }
            if (!isReadableZip(safeCandidate)) {
                return TerminalArtifacts(reportPath, Files.size(reportPath), failure = "dataPackZip 不是可读取的非空 ZIP。")
            }
            TerminalArtifacts(
                report = reportPath,
                reportSize = Files.size(reportPath),
                result = safeCandidate,
                resultSize = Files.size(safeCandidate),
            )
        }.getOrElse { error ->
            TerminalArtifacts(failure = "report.json 或 dataPackZip 校验异常：${redact(error.message ?: error.javaClass.simpleName)}")
        }

    private fun safeOutputFile(path: Path): Path? = safeContainedFile(outputDirectory, path)

    private fun safeContainedFile(containmentRoot: Path, path: Path): Path? {
        val root = containmentRoot.toAbsolutePath().normalize()
        val candidate = path.toAbsolutePath().normalize()
        if (candidate == root || !candidate.startsWith(root) || !pathHasNoSymbolicLinks(root, candidate)) return null
        return candidate.takeIf {
            Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it)
        }
    }

    private fun safeContainedDirectory(containmentRoot: Path, path: Path): Path? {
        val root = containmentRoot.toAbsolutePath().normalize()
        val candidate = path.toAbsolutePath().normalize()
        if (!candidate.startsWith(root) || !pathHasNoSymbolicLinks(root, candidate)) return null
        return candidate.takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
    }

    private fun pathHasNoSymbolicLinks(root: Path, candidate: Path): Boolean {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false
        var current = root
        for (part in root.relativize(candidate)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return false
        }
        return true
    }

    private fun isReadableZip(path: Path): Boolean = runCatching {
        ZipFile(path.toFile()).use { zip -> zip.entries().hasMoreElements() }
    }.getOrDefault(false)

    private fun publishState(event: String) = events.publish(event, snapshot().toJson())

    private fun phaseMessage(value: String): String = when (value) {
        "reading" -> "正在读取上传的 Mod"
        "scanning" -> "正在扫描并校验输入文件"
        "detecting" -> "正在识别输入格式"
        "exporting" -> "正在静态提取可迁移内容"
        "runtime-preflight" -> "正在校验运行时转换输入与固定 Server JAR"
        "runtime-extraction" -> "正在独立 JVM 中加载可信 Mod 并提取运行时内容"
        "source-index" -> "正在索引可选源码与发布 JAR 的对应关系"
        "runtime-mapping" -> "正在将运行时注册映射为 Data Pack 声明"
        "hybrid-selection" -> "正在筛选源码辅助的方块与单位候选"
        "planning" -> "正在规划内容与资源转换"
        "writing-assets" -> "正在写入服务器 Data Assets"
        "writing-zip" -> "正在构建数据包压缩文件"
        "validating" -> "正在执行转换结果校验"
        "finalizing" -> "正在整理报告与诊断信息"
        else -> value
    }

    private fun inferProgress(line: String): Pair<Int, String>? = when {
        "mod-dp-bridge local runtime pipeline started" in line -> 7 to "runtime-preflight"
        "Starting isolated runtime extraction" in line -> 15 to "runtime-extraction"
        line.startsWith("[runtime-extractor]") -> 24 to "runtime-extraction"
        "Indexing optional source provenance" in line -> 35 to "source-index"
        "Mapping the earliest available typed registration snapshot" in line -> 46 to "runtime-mapping"
        "Preparing runtime-guided Java Block/Unit candidates" in line -> 58 to "hybrid-selection"
        "DataPatcher accepted" in line && "hybrid candidate" in line -> 68 to "hybrid-selection"
        "Reading input:" in line -> 10 to "reading"
        "Safely scanned " in line -> 20 to "scanning"
        "Detected source:" in line -> 25 to "detecting"
        "Running static source exporter:" in line -> 30 to "exporting"
        "Static exporter " in line -> 48 to "planning"
        "Planned " in line && " output files" in line -> 62 to "planning"
        "Writing server assets:" in line -> 72 to "writing-assets"
        "Writing deterministic data-pack ZIP:" in line -> 84 to "writing-zip"
        "Conversion completed with static validation" in line -> 90 to "validating"
        line.startsWith("[server-discovery]") -> 93 to "validating"
        line.startsWith("[data-patch-apply]") -> 96 to "validating"
        "Runtime pipeline status:" in line -> 98 to "finalizing"
        "] [INFO] Status:" in line -> 98 to "finalizing"
        else -> null
    }
}

internal class JobManager(private val config: WebConfig) : AutoCloseable {
    private val jobs = ConcurrentHashMap<String, ConversionJob>()
    private val reservationLock = Any()
    private var uploadReservations = 0
    private val pendingUploads = mutableMapOf<String, UploadReservation>()
    private val requestIdClaims = mutableMapOf<String, Instant>()
    private val cancelledRequestIds = mutableMapOf<String, Instant>()
    private val jobExecutor = ThreadPoolExecutor(
        config.maxConcurrentJobs,
        config.maxConcurrentJobs,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(config.maxQueuedJobs),
        namedThreadFactory("bridge-job", daemon = true),
    )
    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor(namedThreadFactory("bridge-cleanup", daemon = true))

    init {
        cleanupExecutor.scheduleWithFixedDelay(::cleanupExpired, 1, 1, TimeUnit.HOURS)
    }

    fun reserveUpload(requestId: String): UploadReservation? = synchronized(reservationLock) {
        pruneRequestIdsLocked()
        val active = jobs.values.count { !it.snapshot().status.terminal }
        if (active + uploadReservations >= config.maxConcurrentJobs + config.maxQueuedJobs) return null
        val id = canonicalRequestId(requestId)
        if (id in requestIdClaims || id in pendingUploads || jobs.containsKey(id)) {
            throw UploadException(409, "This upload request ID was already used.")
        }
        if (requestIdClaims.size >= MAX_REQUEST_ID_CLAIMS) {
            throw UploadException(429, "Too many recent upload request IDs are retained; try again later.")
        }
        val root = config.workDirectory.resolve(id).normalize()
        check(root.startsWith(config.workDirectory))
        val inputDirectory = root.resolve("input")
        var rootCreated = false
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                throw UploadException(409, "This upload request workspace already exists.")
            }
            Files.createDirectory(root)
            rootCreated = true
            Files.createDirectory(inputDirectory)
            UploadReservation(id, id, root, inputDirectory).also { reservation ->
                uploadReservations++
                pendingUploads[id] = reservation
                requestIdClaims[id] = Instant.now()
            }
        } catch (error: Throwable) {
            if (rootCreated) deleteRecursively(root)
            throw error
        }
    }

    fun abandonUpload(reservation: UploadReservation) {
        synchronized(reservationLock) {
            if (pendingUploads.remove(reservation.requestId) != null) {
                uploadReservations = (uploadReservations - 1).coerceAtLeast(0)
            }
        }
        deleteRecursively(reservation.root)
    }

    fun prepare(reservation: UploadReservation, upload: ConversionUpload): ConversionJob {
        val job = ConversionJob(
            id = reservation.id,
            requestId = reservation.requestId,
            originalFileName = upload.input.originalName,
            input = upload.input.path,
            root = reservation.root,
            mode = upload.mode,
            source = upload.source?.path,
            sourceOriginalFileName = upload.source?.originalName,
            executionAcknowledged = upload.executionAcknowledged,
            redactor = WebOutputRedactor(config.serverJar, reservation.root),
        )
        val cancelled = synchronized(reservationLock) {
            check(pendingUploads.remove(reservation.requestId) == reservation) { "Upload reservation is no longer pending." }
            uploadReservations = (uploadReservations - 1).coerceAtLeast(0)
            jobs[job.id] = job
            reservation.requestId in cancelledRequestIds
        }
        if (cancelled) job.cancelBeforeStart()
        return job
    }

    fun start(job: ConversionJob) {
        if (job.snapshot().status.terminal) return
        try {
            val task = Runnable { execute(job) }
            job.queuedTask = task
            jobExecutor.execute(task)
        } catch (error: RejectedExecutionException) {
            job.queuedTask = null
            job.fail("转换队列在响应创建后拒绝了任务，请重试。")
        }
    }

    fun submit(reservation: UploadReservation, upload: ConversionUpload): ConversionJob =
        prepare(reservation, upload).also(::start)

    fun discardPrepared(job: ConversionJob) {
        synchronized(reservationLock) { jobs.remove(job.id, job) }
        deleteRecursively(job.root)
    }

    fun get(id: String): ConversionJob? = jobs[id]

    fun cancelRequest(requestId: String): ConversionJob? {
        val canonical = canonicalRequestId(requestId)
        val job = synchronized(reservationLock) {
            pruneRequestIdsLocked()
            val now = Instant.now()
            if (canonical !in requestIdClaims && requestIdClaims.size >= MAX_REQUEST_ID_CLAIMS) {
                throw UploadException(429, "Too many recent upload request IDs are retained; try again later.")
            }
            requestIdClaims.putIfAbsent(canonical, now)
            cancelledRequestIds[canonical] = now
            jobs[canonical]
        }
        job?.let(::cancel)
        return job
    }

    fun list(): List<JobSnapshot> = jobs.values.map(ConversionJob::snapshot).sortedByDescending { it.createdAt }

    fun cancel(job: ConversionJob): Boolean {
        val previousStatus = job.requestCancellation() ?: return false
        if (previousStatus == JobStatus.QUEUED) {
            job.queuedTask?.let { task -> jobExecutor.remove(task) }
            job.queuedTask = null
            job.cancelBeforeStart()
            return true
        }
        val process = job.process
        process?.let(::terminateProcessTree)
        return true
    }

    private fun execute(job: ConversionJob) {
        job.queuedTask = null
        if (job.cancelRequested) {
            job.cancelBeforeStart()
            return
        }
        if (!job.markRunning()) {
            job.cancelBeforeStart()
            return
        }
        try {
            Files.createDirectories(job.outputDirectory)
            Files.createDirectories(job.webLog.parent)
            Files.newBufferedWriter(
                job.webLog,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { writer ->
                job.appendLog("[web] Starting isolated mod-dp-bridge CLI process.", writer)
                val command = buildCommand(job)
                val process = ProcessBuilder(command)
                    .directory(job.root.toFile())
                    .redirectErrorStream(true)
                    .start()
                job.process = process
                if (job.cancelRequested) terminateProcessTree(process)

                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { line -> job.appendLog(line, writer) }
                }
                val exit = process.waitFor()
                job.process = null
                job.appendLog("[web] Converter process exited with code $exit.", writer)
                job.finish(exit)
            }
        } catch (error: Throwable) {
            job.process?.let(::terminateProcessTree)
            job.process = null
            val detail = "[web] Converter process failed: ${error.javaClass.simpleName}: ${error.message ?: "unknown error"}"
            appendEmergencyLog(job, detail)
            if (!job.isTerminal()) {
                job.fail(
                    if (job.cancelRequested) "转换任务已终止。"
                    else "转换进程启动或执行失败：${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    private fun appendEmergencyLog(job: ConversionJob, line: String) {
        val safeLine = job.redact(line)
        val written = runCatching {
            Files.createDirectories(job.webLog.parent)
            Files.writeString(
                job.webLog,
                safeLine + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE,
            )
        }.isSuccess
        if (written) {
            job.events.publishLog(
                if (safeLine.length <= MAX_LIVE_LOG_LINE_CHARS) safeLine
                else safeLine.take(MAX_LIVE_LOG_LINE_CHARS) + " … [实时视图已截断；请下载完整日志]",
            )
        } else {
            System.err.println(safeLine)
        }
    }

    private fun buildCommand(job: ConversionJob): List<String> = buildList {
        add(config.javaCommand)
        add("-Dfile.encoding=UTF-8")
        add("-Dsun.stdout.encoding=UTF-8")
        add("-Dsun.stderr.encoding=UTF-8")
        add("-cp")
        add(config.cliClasspath)
        add("io.github.moddpbridge.cli.MainKt")
        when (job.mode) {
            ConversionMode.STATIC -> {
                add("convert")
                add(job.input.toString())
                add("--output")
                add(job.outputDirectory.toString())
                add("--overwrite")
                add("--max-input-mib")
                add(((config.maxUploadBytes + 1024L * 1024L - 1) / (1024L * 1024L)).toString())
                add("--max-expanded-mib")
                add(config.maxExpandedMiB.toString())
                add("--max-entries")
                add(config.maxArchiveEntries.toString())
                config.serverJar?.let { jar ->
                    add("--server-jar")
                    add(jar.toString())
                    add("--server-timeout")
                    add(config.serverTimeoutSeconds.toString())
                }
            }

            ConversionMode.RUNTIME -> {
                require(config.runtimeReady) { "Runtime conversion is not ready: ${config.runtimeReason}" }
                require(job.executionAcknowledged) { "Runtime Mod execution was not acknowledged." }
                val serverJar = requireNotNull(config.serverJar) { "Runtime Server JAR is not configured." }
                add("runtime-convert")
                add("--mod-jar")
                add(job.input.toString())
                job.source?.let { source ->
                    add("--source")
                    add(source.toString())
                }
                add("--server-jar")
                add(serverJar.toString())
                add("--allow-mod-execution")
                add("--output")
                add(job.outputDirectory.toString())
                add("--overwrite")
                add("--runtime-timeout")
                add(config.runtimeTimeoutSeconds.toString())
                add("--server-timeout")
                add(config.serverTimeoutSeconds.toString())
                add("--hybrid-max-rounds")
                add(config.hybridMaxRounds.toString())
            }
        }
    }

    private fun terminateProcessTree(process: Process) {
        val root = process.toHandle()
        val known = linkedMapOf<Long, ProcessHandle>()
        fun refreshTree() {
            known[root.pid()] = root
            runCatching { root.descendants().use { stream -> stream.forEach { known[it.pid()] = it } } }
        }
        fun aliveDeepestFirst(): List<ProcessHandle> = known.values
            .filter(ProcessHandle::isAlive)
            .sortedByDescending { handle -> processDepth(handle) }

        repeat(8) {
            refreshTree()
            val alive = aliveDeepestFirst()
            if (alive.isEmpty()) return
            alive.forEach { handle -> runCatching { handle.destroy() } }
            runCatching { process.waitFor(200, TimeUnit.MILLISECONDS) }
            if (alive.none(ProcessHandle::isAlive)) return
        }
        repeat(8) {
            refreshTree()
            val alive = aliveDeepestFirst()
            if (alive.isEmpty()) return
            alive.forEach { handle -> runCatching { handle.destroyForcibly() } }
            runCatching { process.waitFor(200, TimeUnit.MILLISECONDS) }
        }
    }

    private fun processDepth(handle: ProcessHandle): Int {
        var depth = 0
        var current = handle.parent()
        while (current.isPresent && depth < 128) {
            depth++
            current = current.get().parent()
        }
        return depth
    }

    private fun cleanupExpired() {
        val cutoff = Instant.now().minus(config.retention)
        jobs.entries.removeIf { (_, job) ->
            val snapshot = job.snapshot()
            if (snapshot.status.terminal && (snapshot.finishedAt ?: snapshot.createdAt).isBefore(cutoff)) {
                deleteRecursively(job.root)
                true
            } else false
        }
        val pendingRoots = synchronized(reservationLock) {
            pruneRequestIdsLocked()
            pendingUploads.values.mapTo(mutableSetOf()) { it.root }
        }
        runCatching {
            Files.list(config.workDirectory).use { roots ->
                roots.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }.forEach { root ->
                    if (
                        root !in pendingRoots &&
                        jobs.values.none { it.root == root } &&
                        Files.getLastModifiedTime(root, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)
                    ) {
                        deleteRecursively(root)
                    }
                }
            }
        }
    }

    override fun close() {
        val abandoned = synchronized(reservationLock) {
            pendingUploads.values.toList().also {
                pendingUploads.clear()
                uploadReservations = 0
            }
        }
        abandoned.forEach { deleteRecursively(it.root) }
        jobs.values.filter { !it.snapshot().status.terminal }.forEach(::cancel)
        jobExecutor.shutdownNow()
        cleanupExecutor.shutdownNow()
    }

    private fun canonicalRequestId(value: String): String {
        val candidate = value.trim()
        val uuid = runCatching { UUID.fromString(candidate) }.getOrNull()
            ?: throw UploadException(400, "X-Mod-DP-Bridge-Request-ID must be a canonical UUID.")
        if (!uuid.toString().equals(candidate, ignoreCase = true)) {
            throw UploadException(400, "X-Mod-DP-Bridge-Request-ID must be a canonical UUID.")
        }
        return uuid.toString()
    }

    /** Caller must hold [reservationLock]. */
    private fun pruneRequestIdsLocked() {
        val cutoff = Instant.now().minus(config.retention)
        cancelledRequestIds.entries.removeIf { (id, time) ->
            time.isBefore(cutoff) && id !in pendingUploads && !jobs.containsKey(id)
        }
        requestIdClaims.entries.removeIf { (id, time) ->
            time.isBefore(cutoff) && id !in pendingUploads && !jobs.containsKey(id)
        }
    }

    private fun deleteRecursively(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(config.workDirectory) || normalized == config.workDirectory) return
        runCatching {
            Files.walk(normalized).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}

internal fun namedThreadFactory(prefix: String, daemon: Boolean): ThreadFactory {
    val counter = AtomicLong()
    return ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = daemon }
    }
}

internal class WebOutputRedactor(
    serverJar: Path? = null,
    jobRoot: Path? = null,
) {
    private val replacements: List<Pair<Regex, String>> = buildList {
        serverJar?.let { path -> addPath(path, "[operator-server-v159.7.jar]") }
        jobRoot?.let { path -> addPath(path, "[job]") }
    }.sortedByDescending { (pattern, _) -> pattern.pattern.length }

    fun redact(value: String): String = replacements.fold(value) { current, (pattern, replacement) ->
        pattern.replace(current, replacement)
    }

    private fun MutableList<Pair<Regex, String>>.addPath(path: Path, replacement: String) {
        val normalized = path.toAbsolutePath().normalize().toString()
        setOf(
            normalized,
            normalized.replace('\\', '/'),
            normalized.replace("\\", "\\\\"),
        ).filter(String::isNotBlank).forEach { value ->
            add(Regex(Regex.escape(value), RegexOption.IGNORE_CASE) to replacement)
        }
    }
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
