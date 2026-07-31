package io.github.moddpbridge.cli

import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class RuntimeExtractorRequest(
    val modJar: Path,
    val serverJar: Path,
    val snapshot: Path,
    val workDirectory: Path,
    val logFile: Path,
    val commandLogFile: Path,
    val modId: String?,
    val timeout: Duration,
    val allowModExecution: Boolean,
)

internal data class RuntimeExtractorResult(
    val exitCode: Int?,
    val timedOut: Boolean,
    val duration: Duration,
    val command: List<String>,
) {
    val succeeded: Boolean get() = !timedOut && exitCode == 0
}

/**
 * Boundary used by [RuntimeConversionPipeline]. Implementations must leave the runtime snapshot on
 * disk and must not return live Mindustry/Mod objects to the CLI JVM.
 */
internal fun interface RuntimeExtractorRunner {
    fun extract(request: RuntimeExtractorRequest, logger: BridgeLogger): RuntimeExtractorResult
}

/**
 * Starts bridge-runtime-extractor in its own JVM. The extractor then starts the isolated Mindustry
 * worker, so neither Mindustry nor supplied Mod classes are loaded into the CLI process.
 */
internal class RuntimeExtractorProcess(
    private val javaExecutable: Path = defaultJavaExecutable(),
    private val classPath: String = System.getProperty("java.class.path"),
    private val extractorMainClass: String = EXTRACTOR_MAIN_CLASS,
) : RuntimeExtractorRunner {
    override fun extract(request: RuntimeExtractorRequest, logger: BridgeLogger): RuntimeExtractorResult {
        require(request.allowModExecution) {
            "Runtime extraction requires explicit consent to execute the trusted Mod and Server JAR."
        }
        require(!request.timeout.isZero && !request.timeout.isNegative) {
            "Runtime extraction timeout must be positive."
        }

        request.snapshot.parent?.let(Files::createDirectories)
        Files.createDirectories(request.workDirectory)
        request.logFile.parent?.let(Files::createDirectories)
        request.commandLogFile.parent?.let(Files::createDirectories)

        val command = buildCommand(request)
        Files.writeString(
            request.commandLogFile,
            renderCommand(command) + System.lineSeparator(),
            StandardCharsets.UTF_8,
        )

        val startedAt = System.nanoTime()
        val process = ProcessBuilder(command)
            .directory(request.snapshot.parent.toFile())
            .redirectErrorStream(true)
            .start()
        val shutdownHook = Thread(
            { if (process.isAlive) destroyProcessTree(process) },
            "runtime-extractor-shutdown",
        )
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        val pumpFailure = AtomicReference<Throwable?>()
        val outputPump = Thread(
            {
                try {
                    Files.newBufferedWriter(request.logFile, StandardCharsets.UTF_8).use { writer ->
                        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                            pumpLines(reader) { line ->
                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                                logger.raw("[runtime-extractor] $line")
                            }
                        }
                    }
                } catch (error: Throwable) {
                    pumpFailure.set(error)
                }
            },
            "runtime-extractor-output",
        ).apply {
            isDaemon = true
            start()
        }

        // The extractor has its own worker timeout. This outer deadline covers launcher cleanup,
        // compiler startup and a stuck launcher process without weakening the inner timeout.
        val outerTimeout = request.timeout.plusSeconds(PROCESS_CLEANUP_GRACE_SECONDS)
        val finished = try {
            try {
                process.waitFor(outerTimeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (interrupted: InterruptedException) {
                destroyProcessTree(process)
                Thread.currentThread().interrupt()
                throw interrupted
            }
        } finally {
            // removeShutdownHook throws once JVM shutdown has already started; in that case the hook
            // is intentionally left registered to clean up this exact process tree.
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }

        if (!finished) {
            destroyProcessTree(process)
        }
        outputPump.join(OUTPUT_PUMP_JOIN_MILLIS)
        pumpFailure.get()?.let { throw IllegalStateException("Failed to retain runtime extractor output.", it) }

        return RuntimeExtractorResult(
            exitCode = if (finished) process.exitValue() else null,
            timedOut = !finished,
            duration = Duration.ofNanos(System.nanoTime() - startedAt),
            command = command,
        )
    }

    internal fun buildCommand(request: RuntimeExtractorRequest): List<String> = buildList {
        add(javaExecutable.toAbsolutePath().normalize().toString())
        add("-Dfile.encoding=UTF-8")
        add("-Dstdout.encoding=UTF-8")
        add("-Dstderr.encoding=UTF-8")
        add("-cp")
        add(classPath)
        add(extractorMainClass)
        add("extract")
        add("--server-jar")
        add(request.serverJar.toAbsolutePath().normalize().toString())
        add("--mod-jar")
        add(request.modJar.toAbsolutePath().normalize().toString())
        add("--output")
        add(request.snapshot.toAbsolutePath().normalize().toString())
        add("--work-dir")
        add(request.workDirectory.toAbsolutePath().normalize().toString())
        add("--timeout-seconds")
        add(request.timeout.seconds.toString())
        request.modId?.takeIf(String::isNotBlank)?.let { modId ->
            add("--mod-id")
            add(modId)
        }
        if (request.allowModExecution) add("--allow-mod-execution")
    }

    private fun pumpLines(reader: BufferedReader, sink: (String) -> Unit) {
        while (true) {
            val line = reader.readLine() ?: return
            sink(line)
        }
    }

    private fun destroyProcessTree(process: Process) {
        val handle = process.toHandle()
        val descendants = handle.descendants().use { stream -> stream.toList() }.asReversed()
        descendants.forEach { child ->
            runCatching { child.destroy() }
        }
        runCatching { handle.destroy() }
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }

        // Do not key forcible descendant cleanup on the launcher still being alive: the launcher can
        // exit first while its Mindustry worker survives or is still shutting down.
        descendants.filter { it.isAlive }.forEach { child ->
            runCatching { child.destroyForcibly() }
        }
        if (handle.isAlive) runCatching { handle.destroyForcibly() }
        runCatching { process.waitFor(2, TimeUnit.SECONDS) }
    }

    private fun renderCommand(command: List<String>): String = command.joinToString(" ") { argument ->
        if (argument.none { it.isWhitespace() || it == '\"' }) argument
        else "\"${argument.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    private companion object {
        const val EXTRACTOR_MAIN_CLASS = "io.github.moddpbridge.runtimeextractor.RuntimeExtractorMain"
        const val PROCESS_CLEANUP_GRACE_SECONDS = 30L
        const val OUTPUT_PUMP_JOIN_MILLIS = 5_000L

        fun defaultJavaExecutable(): Path = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
        )
    }
}
