package io.github.moddpbridge.target.v1597

import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ServerValidationResult(
    val passed: Boolean,
    val exitCode: Int?,
    /** Number printed by ServerControl after scanning config/assets; this is not an apply count. */
    val discoveredAssetFiles: Int?,
    val outputLines: List<String>,
    val diagnostics: List<Diagnostic>,
)

/**
 * Cold-starts a supplied v159.7/B480 server JAR against generated server assets and verifies only
 * ServerControl's file discovery. A normal cold start does not invoke DataPatcher.apply: that occurs
 * when a map/save carrying data assets is read. Use [Mindustry1597ContentApplyValidator] for real
 * ContentParser/DataPatcher validation.
 */
class Mindustry1597ServerValidator {
    fun validate(
        serverAssets: Path,
        serverJar: Path,
        timeout: Duration = Duration.ofSeconds(30),
        logSink: ((String) -> Unit)? = null,
    ): ServerValidationResult {
        val assets = serverAssets.toAbsolutePath().normalize()
        val jar = serverJar.toAbsolutePath().normalize()
        require(Files.isDirectory(assets)) { "Server assets directory does not exist: $assets" }
        require(Files.isRegularFile(jar)) { "Mindustry server JAR does not exist: $jar" }
        require(!timeout.isNegative && !timeout.isZero) { "Timeout must be positive." }

        val workspace = Files.createTempDirectory("mod-dp-bridge-v1597-")
        val diagnostics = mutableListOf<Diagnostic>()
        val output = CopyOnWriteArrayList<String>()
        var process: Process? = null
        try {
            copyTree(assets, workspace.resolve("config/assets"))
            logSink?.invoke("Starting v159.7/B480 server validator in $workspace")
            val startedProcess = ProcessBuilder(javaExecutable(), "-jar", jar.toString())
                .directory(workspace.toFile())
                .redirectErrorStream(true)
                .start()
            process = startedProcess

            startedProcess.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("exit")
                writer.flush()
            }

            val readerFailure = AtomicReference<Throwable?>()
            val reader = Thread({
                try {
                    startedProcess.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            output += line
                            logSink?.invoke(line)
                        }
                    }
                } catch (error: Throwable) {
                    readerFailure.set(error)
                }
            }, "dpbridge-server-log").apply { start() }

            val exited = startedProcess.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!exited) {
                startedProcess.destroyForcibly()
                startedProcess.waitFor(5, TimeUnit.SECONDS)
                diagnostics += diagnostic(
                    "SERVER_DISCOVERY_TIMEOUT",
                    "Server asset-discovery process did not exit within ${timeout.seconds} seconds.",
                    jar,
                )
            }
            reader.join(5_000)
            readerFailure.get()?.let { error ->
                diagnostics += diagnostic(
                    "SERVER_DISCOVERY_LOG_READ_FAILED",
                    error.message ?: "Failed to read server output.",
                    jar,
                )
            }

            val exitCode = if (startedProcess.isAlive) null else startedProcess.exitValue()
            val loaded = output.firstNotNullOfOrNull { line ->
                LOADED_ASSETS.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            val serverLoaded = output.any { "Server loaded." in it }
            val errorLines = output.filter(::looksLikeAssetError)

            if (exitCode != 0) {
                diagnostics += diagnostic(
                    "SERVER_DISCOVERY_PROCESS_FAILED",
                    "Server asset-discovery process exited with code ${exitCode ?: "unknown"}.",
                    jar,
                )
            }
            if (!serverLoaded) {
                diagnostics += diagnostic(
                    "SERVER_DISCOVERY_NOT_READY",
                    "Server asset-discovery process did not reach the ready state.",
                    jar,
                )
            }
            if (loaded == null) {
                diagnostics += Diagnostic(
                    code = "ASSET_DISCOVERY_COUNT_NOT_FOUND",
                    severity = DiagnosticSeverity.WARNING,
                    message = "Server output did not report the number of discovered data asset files.",
                    location = SourceLocation(jar.toString()),
                )
            }
            errorLines.forEach { line ->
                diagnostics += diagnostic(
                    "SERVER_ASSET_DISCOVERY_ERROR",
                    line.take(2_000),
                    jar,
                )
            }

            val passed = exitCode == 0 && serverLoaded && diagnostics.none { it.severity == DiagnosticSeverity.ERROR }
            return ServerValidationResult(passed, exitCode, loaded, output.toList(), diagnostics)
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            deleteTree(workspace)
        }
    }

    private fun javaExecutable(): String {
        val executable = if (System.getProperty("os.name").contains("windows", ignoreCase = true)) "java.exe" else "java"
        val javaHome = Path.of(System.getProperty("java.home"))
        val candidate = javaHome.resolve("bin").resolve(executable)
        return if (Files.isRegularFile(candidate)) candidate.toString() else executable
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.sorted().forEach { input ->
                val relative = source.relativize(input)
                val output = target.resolve(relative.toString()).normalize()
                require(output.startsWith(target)) { "Refusing to copy outside validation target: $relative" }
                if (Files.isDirectory(input)) {
                    Files.createDirectories(output)
                } else {
                    output.parent?.let(Files::createDirectories)
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    private fun looksLikeAssetError(line: String): Boolean {
        val lower = line.lowercase()
        if ("[e]" in lower || "exception" in lower || "failed to parse" in lower) return true
        return ("content" in lower || "asset" in lower || "patch" in lower) &&
            (" error" in lower || "errored" in lower || "failed" in lower)
    }

    private fun diagnostic(code: String, message: String, path: Path): Diagnostic = Diagnostic(
        code = code,
        severity = DiagnosticSeverity.ERROR,
        message = message,
        location = SourceLocation(path.toString()),
    )

    private companion object {
        val LOADED_ASSETS = Regex("Loaded\\s+(\\d+)\\s+data asset files\\.", RegexOption.IGNORE_CASE)
    }
}
