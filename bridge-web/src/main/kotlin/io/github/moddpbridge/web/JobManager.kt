package io.github.moddpbridge.web

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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

internal enum class JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    val apiName: String get() = name.lowercase()
    val terminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

internal data class UploadReservation(val id: String, val root: Path, val inputDirectory: Path)

internal data class JobSnapshot(
    val id: String,
    val fileName: String,
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
        "fileName" to jsonString(fileName),
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

internal class ConversionJob(
    val id: String,
    val originalFileName: String,
    val input: Path,
    val root: Path,
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
    @Volatile var cancelRequested: Boolean = false
        private set
    @Volatile var process: Process? = null
    @Volatile var queuedTask: Runnable? = null

    fun snapshot(): JobSnapshot = synchronized(lock) {
        val terminal = status.terminal
        val report = terminal && Files.isRegularFile(outputDirectory.resolve("report.json"))
        val result = if (terminal && report) findResultFile() else null
        JobSnapshot(
            id = id,
            fileName = originalFileName,
            status = status,
            progress = progress,
            phase = phase,
            createdAt = createdAt,
            startedAt = startedAt,
            finishedAt = finishedAt,
            exitCode = exitCode,
            message = message,
            resultAvailable = result != null,
            reportAvailable = report,
            logsAvailable = terminal && (
                Files.isRegularFile(webLog) || Files.isDirectory(outputDirectory.resolve("logs"))
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
        synchronized(lock) {
            exitCode = code
            finishedAt = Instant.now()
            progress = 100
            when {
                cancelRequested -> {
                    status = JobStatus.CANCELLED
                    phase = "cancelled"
                    message = "转换任务已终止。"
                }
                code == 0 -> {
                    status = JobStatus.SUCCEEDED
                    phase = "completed"
                    message = "转换任务已完成。"
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
            message = messageText
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
            message = "任务已在开始转换前终止。"
        }
        publishState("status")
        events.compact(TERMINAL_EVENT_HISTORY)
    }

    fun appendLog(line: String, writer: BufferedWriter) {
        writer.write(line)
        writer.newLine()
        writer.flush()
        val liveLine = if (line.length <= MAX_LIVE_LOG_LINE_CHARS) line else {
            line.take(MAX_LIVE_LOG_LINE_CHARS) + " … [实时视图已截断；请下载完整日志]"
        }
        events.publishLog(liveLine)
        inferProgress(line)?.let { (value, inferredPhase) -> updateProgress(value, inferredPhase) }
    }

    fun findResult(): Path? {
        if (!isTerminal() || findReport() == null) return null
        return findResultFile()
    }

    fun findReport(): Path? {
        if (!isTerminal()) return null
        return outputDirectory.resolve("report.json").takeIf { Files.isRegularFile(it) }
    }

    fun isTerminal(): Boolean = synchronized(lock) { status.terminal }

    private fun findResultFile(): Path? {
        if (!Files.isDirectory(outputDirectory)) return null
        return runCatching {
            Files.list(outputDirectory).use { paths ->
                paths.filter { it.isRegularFile() && it.fileName.toString().endsWith(".zip", ignoreCase = true) }
                    .sorted()
                    .filter(::isReadableZip)
                    .findFirst()
                    .orElse(null)
            }
        }.getOrNull()
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
        "planning" -> "正在规划内容与资源转换"
        "writing-assets" -> "正在写入服务器 Data Assets"
        "writing-zip" -> "正在构建数据包压缩文件"
        "validating" -> "正在执行转换结果校验"
        "finalizing" -> "正在整理报告与诊断信息"
        else -> value
    }

    private fun inferProgress(line: String): Pair<Int, String>? = when {
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
        "] [INFO] Status:" in line -> 98 to "finalizing"
        else -> null
    }
}

internal class JobManager(private val config: WebConfig) : AutoCloseable {
    private val jobs = ConcurrentHashMap<String, ConversionJob>()
    private val reservationLock = Any()
    private var uploadReservations = 0
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

    fun reserveUpload(): UploadReservation? = synchronized(reservationLock) {
        val active = jobs.values.count { !it.snapshot().status.terminal }
        if (active + uploadReservations >= config.maxConcurrentJobs + config.maxQueuedJobs) return null
        uploadReservations++
        val id = UUID.randomUUID().toString()
        val root = config.workDirectory.resolve(id).normalize()
        check(root.startsWith(config.workDirectory))
        val inputDirectory = root.resolve("input")
        try {
            Files.createDirectories(inputDirectory)
            UploadReservation(id, root, inputDirectory)
        } catch (error: Throwable) {
            uploadReservations = (uploadReservations - 1).coerceAtLeast(0)
            deleteRecursively(root)
            throw error
        }
    }

    fun abandonUpload(reservation: UploadReservation) {
        synchronized(reservationLock) { uploadReservations = (uploadReservations - 1).coerceAtLeast(0) }
        deleteRecursively(reservation.root)
    }

    fun submit(reservation: UploadReservation, upload: UploadedFile): ConversionJob {
        val job = ConversionJob(reservation.id, upload.originalName, upload.path, reservation.root)
        jobs[job.id] = job
        synchronized(reservationLock) { uploadReservations = (uploadReservations - 1).coerceAtLeast(0) }
        try {
            val task = Runnable { execute(job) }
            job.queuedTask = task
            jobExecutor.execute(task)
        } catch (error: RejectedExecutionException) {
            jobs.remove(job.id)
            deleteRecursively(job.root)
            throw error
        }
        return job
    }

    fun get(id: String): ConversionJob? = jobs[id]

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
        val written = runCatching {
            Files.createDirectories(job.webLog.parent)
            Files.writeString(
                job.webLog,
                line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE,
            )
        }.isSuccess
        if (!written) System.err.println(line)
    }

    private fun buildCommand(job: ConversionJob): List<String> = buildList {
        add(config.javaCommand)
        add("-Dfile.encoding=UTF-8")
        add("-Dsun.stdout.encoding=UTF-8")
        add("-Dsun.stderr.encoding=UTF-8")
        add("-cp")
        add(config.cliClasspath)
        add("io.github.moddpbridge.cli.MainKt")
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

    private fun terminateProcessTree(process: Process) {
        val handle = process.toHandle()
        val descendants = runCatching { handle.descendants().toList().asReversed() }.getOrDefault(emptyList())
        descendants.forEach { child -> runCatching { child.destroy() } }
        runCatching { handle.destroy() }
        runCatching { process.waitFor(1500, TimeUnit.MILLISECONDS) }
        descendants.filter(ProcessHandle::isAlive).forEach { child -> runCatching { child.destroyForcibly() } }
        if (handle.isAlive) runCatching { handle.destroyForcibly() }
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
        runCatching {
            Files.list(config.workDirectory).use { roots ->
                roots.filter(Files::isDirectory).forEach { root ->
                    if (jobs.values.none { it.root == root } && Files.getLastModifiedTime(root).toInstant().isBefore(cutoff)) {
                        deleteRecursively(root)
                    }
                }
            }
        }
    }

    override fun close() {
        jobs.values.filter { !it.snapshot().status.terminal }.forEach(::cancel)
        jobExecutor.shutdownNow()
        cleanupExecutor.shutdownNow()
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
