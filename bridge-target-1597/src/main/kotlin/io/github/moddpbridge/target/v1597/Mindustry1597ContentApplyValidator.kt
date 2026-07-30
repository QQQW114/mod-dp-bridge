package io.github.moddpbridge.target.v1597

import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ContentApplyValidationResult(
    /** True only when the harness emitted its final result after DataManager.load/DataPatcher.apply returned. */
    val applyCompleted: Boolean,
    val passed: Boolean,
    val exitCode: Int?,
    val timedOut: Boolean,
    val totalAssets: Int?,
    val contentAssets: Int?,
    val patchAssets: Int?,
    val externalAssets: Int?,
    val failedAssets: Int?,
    val warningCount: Int?,
    val addedContent: Int?,
    val outputLines: List<String>,
    val diagnostics: List<Diagnostic>,
)

/**
 * Runs generated assets through the real v159.7/B480 DataManager and DataPatcher in an isolated
 * headless JVM. Only the fixed, bundled harness source is launched; input Mod Java/classes are
 * never compiled, loaded, reflected or executed.
 *
 * This proves parser/apply completion, not map import and not loading a map carrying the DP on a
 * dedicated server.
 */
class Mindustry1597ContentApplyValidator {
    fun validate(
        serverAssets: Path,
        serverJar: Path,
        timeout: Duration = Duration.ofSeconds(60),
        logSink: ((String) -> Unit)? = null,
    ): ContentApplyValidationResult {
        val assets = serverAssets.toAbsolutePath().normalize()
        val jar = serverJar.toAbsolutePath().normalize()
        require(Files.isDirectory(assets)) { "Server assets directory does not exist: $assets" }
        require(Files.isRegularFile(jar)) { "Mindustry server JAR does not exist: $jar" }
        require(!timeout.isNegative && !timeout.isZero) { "Timeout must be positive." }

        val workspace = Files.createTempDirectory("mod-dp-bridge-apply-v1597-")
        val harnessSource = workspace.resolve(HARNESS_FILE)
        val dataDirectory = workspace.resolve("data")
        val output = CopyOnWriteArrayList<String>()
        var process: Process? = null

        try {
            Files.createDirectories(dataDirectory)
            val resource = javaClass.getResourceAsStream(HARNESS_RESOURCE)
                ?: return startFailure(
                    output,
                    "Bundled DataPatcher apply harness resource is missing: $HARNESS_RESOURCE",
                    jar,
                )
            resource.use { input -> Files.copy(input, harnessSource) }

            logSink?.invoke("Starting v159.7/B480 DataPatcher apply validator in $workspace")
            val started = try {
                ProcessBuilder(
                    javaExecutable(),
                    "-cp",
                    jar.toString(),
                    harnessSource.toString(),
                    assets.toString(),
                    dataDirectory.toString(),
                )
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .start()
            } catch (error: Throwable) {
                return startFailure(
                    output,
                    error.message ?: "Failed to start the DataPatcher apply harness JVM.",
                    jar,
                    error.stackTraceToString(),
                )
            }
            process = started

            val readerFailure = AtomicReference<Throwable?>()
            val reader = Thread({
                try {
                    started.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            output += line
                            logSink?.invoke(line)
                        }
                    }
                } catch (error: Throwable) {
                    readerFailure.set(error)
                }
            }, "dpbridge-apply-log").apply { start() }

            val exited = started.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!exited) {
                started.destroyForcibly()
                started.waitFor(5, TimeUnit.SECONDS)
            }
            reader.join(5_000)

            val exitCode = if (started.isAlive) null else started.exitValue()
            val parsed = parseOutput(
                lines = output.toList(),
                exitCode = exitCode,
                timedOut = !exited,
                assetsRoot = assets,
            )
            val readerDiagnostic = readerFailure.get()?.let { error ->
                diagnostic(
                    code = "DATA_PATCH_LOG_READ_FAILED",
                    severity = DiagnosticSeverity.ERROR,
                    message = error.message ?: "Failed to read DataPatcher apply harness output.",
                    path = jar,
                    details = error.stackTraceToString(),
                )
            }
            return if (readerDiagnostic == null) {
                parsed
            } else {
                parsed.copy(
                    passed = false,
                    diagnostics = parsed.diagnostics + readerDiagnostic,
                )
            }
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            deleteTree(workspace)
        }
    }

    private fun startFailure(
        output: List<String>,
        message: String,
        jar: Path,
        details: String? = null,
    ): ContentApplyValidationResult = ContentApplyValidationResult(
        applyCompleted = false,
        passed = false,
        exitCode = null,
        timedOut = false,
        totalAssets = null,
        contentAssets = null,
        patchAssets = null,
        externalAssets = null,
        failedAssets = null,
        warningCount = null,
        addedContent = null,
        outputLines = output,
        diagnostics = listOf(
            diagnostic(
                code = "DATA_PATCH_HARNESS_START_FAILED",
                severity = DiagnosticSeverity.ERROR,
                message = message,
                path = jar,
                details = details,
            ),
        ),
    )

    private fun javaExecutable(): String {
        val executable = if (System.getProperty("os.name").contains("windows", ignoreCase = true)) "java.exe" else "java"
        val candidate = Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable)
        return if (Files.isRegularFile(candidate)) candidate.toString() else executable
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    internal companion object {
        private const val HARNESS_FILE = "DpApplyHarness.java"
        private const val HARNESS_RESOURCE = "/io/github/moddpbridge/target/v1597/$HARNESS_FILE"

        private data class HarnessResult(
            val totalAssets: Int,
            val contentAssets: Int,
            val patchAssets: Int,
            val externalAssets: Int,
            val failedAssets: Int,
            val warningCount: Int,
            val addedContent: Int,
        )

        internal fun parseOutput(
            lines: List<String>,
            exitCode: Int?,
            timedOut: Boolean,
            assetsRoot: Path,
        ): ContentApplyValidationResult {
            val diagnostics = mutableListOf<Diagnostic>()
            var protocolSeen = false
            var result: HarnessResult? = null
            var warningRecords = 0
            var fatalSeen = false

            lines.forEach { line ->
                if (!line.startsWith("DPBRIDGE_")) return@forEach
                val fields = line.split('\t')
                try {
                    when (fields.firstOrNull()) {
                        "DPBRIDGE_PROTOCOL" -> {
                            require(fields.size == 2 && fields[1] == "1") { "Unsupported harness protocol record." }
                            protocolSeen = true
                        }

                        "DPBRIDGE_ASSET" -> {
                            require(fields.size == 7) { "Content asset record must have 7 fields." }
                            val relative = decode(fields[1])
                            val failed = fields[3].toBooleanStrict()
                            if (failed) {
                                val contentName = decode(fields[5])
                                val reason = decode(fields[6])
                                diagnostics += diagnostic(
                                    code = "DATA_PATCH_CONTENT_FAILED",
                                    severity = DiagnosticSeverity.ERROR,
                                    message = "Mindustry v159.7 rejected content asset '$relative'" +
                                        contentName.takeIf { it.isNotBlank() }?.let { " (content '$it')" }.orEmpty() + ".",
                                    path = assetsRoot.resolveSafe(relative),
                                    details = reason.ifBlank { null },
                                    suggestion = "Inspect the linked ContentParser warnings and replace unsupported fields/types.",
                                )
                            }
                        }

                        "DPBRIDGE_PATCH" -> {
                            require(fields.size == 4) { "Patch record must have 4 fields." }
                            val relative = decode(fields[1])
                            if (fields[2].toBooleanStrict()) {
                                diagnostics += diagnostic(
                                    code = "DATA_PATCH_PATCH_FAILED",
                                    severity = DiagnosticSeverity.ERROR,
                                    message = "Mindustry v159.7 failed to apply patch '$relative'.",
                                    path = assetsRoot.resolveSafe(relative),
                                    suggestion = "Inspect the linked patch warning and rewrite the unsupported patch operation.",
                                )
                            }
                        }

                        "DPBRIDGE_WARNING" -> {
                            require(fields.size == 3) { "Warning record must have 3 fields." }
                            warningRecords++
                            val relative = decode(fields[1])
                            diagnostics += diagnostic(
                                code = "DATA_PATCH_APPLY_WARNING",
                                severity = DiagnosticSeverity.WARNING,
                                message = decode(fields[2]),
                                path = assetsRoot.resolveSafe(relative),
                            )
                        }

                        "DPBRIDGE_READ_ERROR" -> {
                            require(fields.size == 3) { "Read-error record must have 3 fields." }
                            val relative = decode(fields[1])
                            diagnostics += diagnostic(
                                code = "DATA_ASSET_READ_FAILED",
                                severity = DiagnosticSeverity.ERROR,
                                message = "Mindustry could not read '$relative': ${decode(fields[2])}",
                                path = assetsRoot.resolveSafe(relative),
                            )
                        }

                        "DPBRIDGE_RESULT" -> {
                            require(fields.size == 8) { "Result record must have 8 fields." }
                            require(result == null) { "Duplicate result record." }
                            result = HarnessResult(
                                totalAssets = fields[1].nonNegativeInt(),
                                contentAssets = fields[2].nonNegativeInt(),
                                patchAssets = fields[3].nonNegativeInt(),
                                externalAssets = fields[4].nonNegativeInt(),
                                failedAssets = fields[5].nonNegativeInt(),
                                warningCount = fields[6].nonNegativeInt(),
                                addedContent = fields[7].nonNegativeInt(),
                            )
                        }

                        "DPBRIDGE_FATAL" -> {
                            require(fields.size == 2) { "Fatal record must have 2 fields." }
                            fatalSeen = true
                            diagnostics += diagnostic(
                                code = "DATA_PATCH_HARNESS_FATAL",
                                severity = DiagnosticSeverity.ERROR,
                                message = "Mindustry v159.7 terminated while applying generated data assets.",
                                path = assetsRoot,
                                details = decode(fields[1]),
                            )
                        }

                        else -> diagnostics += malformedDiagnostic(line, assetsRoot, "Unknown protocol record.")
                    }
                } catch (error: Throwable) {
                    diagnostics += malformedDiagnostic(line, assetsRoot, error.message ?: "Malformed protocol record.")
                }
            }

            if (timedOut) {
                diagnostics += diagnostic(
                    code = "DATA_PATCH_APPLY_TIMEOUT",
                    severity = DiagnosticSeverity.ERROR,
                    message = "DataPatcher apply validation timed out.",
                    path = assetsRoot,
                )
            }
            if (!protocolSeen) {
                diagnostics += diagnostic(
                    code = "DATA_PATCH_PROTOCOL_NOT_STARTED",
                    severity = DiagnosticSeverity.ERROR,
                    message = "The bundled apply harness did not start its machine-readable protocol.",
                    path = assetsRoot,
                )
            }
            if (result == null && !timedOut && !fatalSeen) {
                diagnostics += diagnostic(
                    code = "DATA_PATCH_APPLY_NOT_COMPLETED",
                    severity = DiagnosticSeverity.ERROR,
                    message = "The Mindustry process exited before DataPatcher.apply produced a result.",
                    path = assetsRoot,
                )
            }
            result?.let { parsed ->
                if (warningRecords != parsed.warningCount) {
                    diagnostics += diagnostic(
                        code = "DATA_PATCH_WARNING_COUNT_MISMATCH",
                        severity = DiagnosticSeverity.WARNING,
                        message = "Harness reported ${parsed.warningCount} warnings but emitted $warningRecords warning records.",
                        path = assetsRoot,
                    )
                }
                if (parsed.externalAssets > 0) {
                    diagnostics += diagnostic(
                        code = "HEADLESS_EXTERNAL_ASSET_DECODE_NOT_RUN",
                        severity = DiagnosticSeverity.INFO,
                        message = "Discovered ${parsed.externalAssets} bundle/sprite/audio assets; headless apply validates registration and hashes, not client-side image/audio decoding.",
                        path = assetsRoot,
                    )
                }
            }

            val knownValidationFailure = result?.failedAssets?.let { it > 0 } == true && exitCode == 10
            if (exitCode != null && exitCode != 0 && !knownValidationFailure && !fatalSeen) {
                diagnostics += diagnostic(
                    code = "DATA_PATCH_PROCESS_FAILED",
                    severity = DiagnosticSeverity.ERROR,
                    message = "DataPatcher apply harness exited with code $exitCode.",
                    path = assetsRoot,
                )
            }

            val passed = result?.failedAssets == 0 && exitCode == 0 && !timedOut &&
                diagnostics.none { it.severity == DiagnosticSeverity.ERROR }
            return ContentApplyValidationResult(
                applyCompleted = result != null,
                passed = passed,
                exitCode = exitCode,
                timedOut = timedOut,
                totalAssets = result?.totalAssets,
                contentAssets = result?.contentAssets,
                patchAssets = result?.patchAssets,
                externalAssets = result?.externalAssets,
                failedAssets = result?.failedAssets,
                warningCount = result?.warningCount,
                addedContent = result?.addedContent,
                outputLines = lines,
                diagnostics = diagnostics.distinctBy { Triple(it.code, it.location?.path, it.message) },
            )
        }

        private fun String.nonNegativeInt(): Int = toInt().also { require(it >= 0) { "Negative result count." } }

        private fun decode(value: String): String = Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)

        private fun Path.resolveSafe(relative: String): Path {
            val normalized = resolve(relative.replace('/', java.io.File.separatorChar)).normalize()
            return if (normalized.startsWith(this.normalize())) normalized else this.normalize()
        }

        private fun malformedDiagnostic(line: String, path: Path, reason: String): Diagnostic = diagnostic(
            code = "DATA_PATCH_PROTOCOL_MALFORMED",
            severity = DiagnosticSeverity.ERROR,
            message = reason,
            path = path,
            details = line.take(2_000),
        )

        private fun diagnostic(
            code: String,
            severity: DiagnosticSeverity,
            message: String,
            path: Path,
            details: String? = null,
            suggestion: String? = null,
        ): Diagnostic = Diagnostic(
            code = code,
            severity = severity,
            message = message,
            stage = ValidationStage.RUNTIME,
            location = SourceLocation(path.toString()),
            details = details,
            suggestion = suggestion,
        )
    }
}
