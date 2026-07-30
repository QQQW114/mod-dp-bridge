package io.github.moddpbridge.cli

import io.github.moddpbridge.converter.BridgeConverter
import io.github.moddpbridge.converter.ConversionException
import io.github.moddpbridge.converter.ConversionRequest
import io.github.moddpbridge.converter.SecurityLimits
import io.github.moddpbridge.model.ConversionReportJson
import io.github.moddpbridge.model.ConversionStatus
import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.ReportSummary
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import io.github.moddpbridge.model.ValidationStageResult
import io.github.moddpbridge.model.ValidationStatus
import io.github.moddpbridge.model.toMarkdown
import io.github.moddpbridge.target.v1597.Mindustry1597ContentApplyValidator
import io.github.moddpbridge.target.v1597.Mindustry1597ServerValidator
import io.github.moddpbridge.target.v1597.Mindustry1597StructuralValidator
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import kotlin.io.path.absolutePathString
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Command(
    name = "dpbridge",
    mixinStandardHelpOptions = true,
    version = ["mod-dp-bridge 0.1.0-SNAPSHOT"],
    description = ["Convert Mindustry mods/CP/data packs into v159.7 Data Assets."],
    subcommands = [ConvertCommand::class],
)
class RootCommand : Runnable {
    override fun run() {
        CommandLine(this).usage(System.out)
    }
}

@Command(
    name = "convert",
    mixinStandardHelpOptions = true,
    description = ["Convert one directory, archive, JSON/HJSON CP, or existing data pack."],
)
class ConvertCommand : Callable<Int> {
    @Parameters(index = "0", paramLabel = "INPUT", description = ["Input directory, ZIP/JAR, JSON or HJSON file."])
    lateinit var input: Path

    @Option(
        names = ["-o", "--output"],
        paramLabel = "DIRECTORY",
        description = ["Output directory. Default: ./out/<input-name>"],
    )
    var output: Path? = null

    @Option(names = ["--name"], description = ["Override the output archive base name."])
    var outputName: String? = null

    @Option(names = ["--overwrite"], description = ["Replace existing generated artifacts."])
    var overwrite: Boolean = false

    @Option(names = ["--max-input-mib"], description = ["Maximum archive/input file size in MiB."])
    var maxInputMiB: Long = 64

    @Option(names = ["--max-expanded-mib"], description = ["Maximum expanded archive size in MiB."])
    var maxExpandedMiB: Long = 128

    @Option(names = ["--max-entries"], description = ["Maximum number of input archive entries."])
    var maxEntries: Int = 2048

    @Option(
        names = ["--server-jar"],
        paramLabel = "JAR",
        description = [
            "Optional v159.7/B480 server JAR used for isolated ContentParser/DataPatcher apply validation. " +
                "This does not load a map carrying the DP.",
        ],
    )
    var serverJar: Path? = null

    @Option(names = ["--server-timeout"], description = ["Timeout for each Mindustry validation process, in seconds."])
    var serverTimeoutSeconds: Long = 30

    override fun call(): Int {
        val normalizedInput = input.toAbsolutePath().normalize()
        val outputDirectory = (output ?: defaultOutput(normalizedInput)).toAbsolutePath().normalize()
        Files.createDirectories(outputDirectory.resolve("logs"))

        BridgeLogger(outputDirectory.resolve("logs/conversion.log")).use { logger ->
            logger.info("mod-dp-bridge conversion started")
            logger.info("Input: $normalizedInput")
            logger.info("Output: $outputDirectory")

            return try {
                val result = BridgeConverter.convert(
                    ConversionRequest(
                        input = normalizedInput,
                        outputDirectory = outputDirectory,
                        outputBaseName = outputName,
                        overwrite = overwrite,
                        limits = SecurityLimits(
                            maxInputBytes = mib(maxInputMiB),
                            maxEntries = maxEntries,
                            maxExpandedBytes = mib(maxExpandedMiB),
                        ),
                        logSink = logger::info,
                    ),
                )

                var finalReport = result.report
                val structural = Mindustry1597StructuralValidator().validate(result.dpZip)
                structural.report.diagnostics.forEach { diagnostic -> logger.diagnostic(diagnostic) }
                finalReport = finalReport.copy(
                    status = if (structural.status == ConversionStatus.REJECTED) {
                        ConversionStatus.REJECTED
                    } else {
                        finalReport.status
                    },
                    diagnostics = (finalReport.diagnostics + structural.report.diagnostics).distinctBy {
                        Triple(it.code, it.location?.path, it.message)
                    },
                    validationStages = mergeStages(finalReport.validationStages, structural.report.validationStages),
                )

                if (serverJar != null) {
                    val configuredJar = serverJar!!.toAbsolutePath().normalize()
                    val timeout = Duration.ofSeconds(serverTimeoutSeconds)

                    val discoveryLog = outputDirectory.resolve("logs/server-asset-discovery.log")
                    val discoveryLines = mutableListOf<String>()
                    val discovery = Mindustry1597ServerValidator().validate(
                        serverAssets = result.serverAssets,
                        serverJar = configuredJar,
                        timeout = timeout,
                    ) { line ->
                        discoveryLines += line
                        logger.raw("[server-discovery] $line")
                    }
                    Files.write(discoveryLog, discoveryLines, StandardCharsets.UTF_8)
                    discovery.diagnostics.forEach { diagnostic -> logger.diagnostic(diagnostic) }

                    val applyLog = outputDirectory.resolve("logs/data-patch-apply.log")
                    val applyLines = mutableListOf<String>()
                    val apply = Mindustry1597ContentApplyValidator().validate(
                        serverAssets = result.serverAssets,
                        serverJar = configuredJar,
                        timeout = timeout,
                    ) { line ->
                        applyLines += line
                        logger.raw("[data-patch-apply] $line")
                    }
                    Files.write(applyLog, applyLines, StandardCharsets.UTF_8)
                    apply.diagnostics.forEach { diagnostic -> logger.diagnostic(diagnostic) }

                    val validationDiagnostics = (discovery.diagnostics + apply.diagnostics).distinctBy {
                        Triple(it.code, it.location?.path, it.message)
                    }
                    val runtimeStatus = if (apply.passed) ValidationStatus.PASSED else ValidationStatus.FAILED
                    finalReport = finalReport.copy(
                        status = if (discovery.passed && apply.passed) finalReport.status else ConversionStatus.REJECTED,
                        diagnostics = (finalReport.diagnostics + validationDiagnostics).distinctBy {
                            Triple(it.code, it.location?.path, it.message)
                        },
                        validationStages = mergeStages(
                            finalReport.validationStages,
                            listOf(
                                ValidationStageResult(
                                    ValidationStage.RUNTIME,
                                    runtimeStatus,
                                    if (apply.applyCompleted) {
                                        "v159.7 DataManager.load/DataPatcher.apply completed: " +
                                            "assets=${apply.totalAssets ?: "unknown"}, " +
                                            "content=${apply.contentAssets ?: "unknown"}, " +
                                            "addedContent=${apply.addedContent ?: "unknown"}, " +
                                            "failed=${apply.failedAssets ?: "unknown"}, " +
                                            "warnings=${apply.warningCount ?: "unknown"}."
                                    } else {
                                        "v159.7 DataManager.load/DataPatcher.apply did not complete."
                                    },
                                    apply.diagnostics.map { it.code }.distinct(),
                                ),
                                ValidationStageResult(
                                    ValidationStage.SERVER_LOAD,
                                    ValidationStatus.NOT_RUN,
                                    "Server cold start discovered files, but no map/save carrying the DP was loaded; " +
                                        "server gameplay load remains unverified.",
                                ),
                            ),
                        ),
                        metadata = finalReport.metadata + mapOf(
                            "serverJar" to configuredJar.toString(),
                            "serverAssetDiscovery" to if (discovery.passed) "passed" else "failed",
                            "serverDiscoveredAssetFiles" to (discovery.discoveredAssetFiles?.toString() ?: "unknown"),
                            "serverAssetDiscoveryExitCode" to (discovery.exitCode?.toString() ?: "unknown"),
                            "serverAssetDiscoveryLog" to discoveryLog.toString(),
                            "dataPatchApply" to when {
                                apply.passed -> "passed"
                                apply.applyCompleted -> "failed"
                                else -> "notCompleted"
                            },
                            "dataPatchApplyExitCode" to (apply.exitCode?.toString() ?: "unknown"),
                            "dataPatchApplyTotalAssets" to (apply.totalAssets?.toString() ?: "unknown"),
                            "dataPatchApplyContentAssets" to (apply.contentAssets?.toString() ?: "unknown"),
                            "dataPatchApplyPatchAssets" to (apply.patchAssets?.toString() ?: "unknown"),
                            "dataPatchApplyExternalAssets" to (apply.externalAssets?.toString() ?: "unknown"),
                            "dataPatchApplyFailedAssets" to (apply.failedAssets?.toString() ?: "unknown"),
                            "dataPatchApplyWarnings" to (apply.warningCount?.toString() ?: "unknown"),
                            "dataPatchApplyAddedContent" to (apply.addedContent?.toString() ?: "unknown"),
                            "dataPatchApplyLog" to applyLog.toString(),
                            "serverLoadValidation" to "notRun:no-map-or-save-loaded",
                        ),
                    )
                } else {
                    val notRun = Diagnostic(
                        code = "DATA_PATCH_APPLY_NOT_RUN",
                        severity = DiagnosticSeverity.WARNING,
                        message = "Real v159.7 ContentParser/DataPatcher apply validation was not run; supply --server-jar.",
                        stage = ValidationStage.RUNTIME,
                        location = SourceLocation(result.serverAssets.toString()),
                    )
                    finalReport = finalReport.copy(
                        diagnostics = (finalReport.diagnostics + notRun).distinctBy {
                            Triple(it.code, it.location?.path, it.message)
                        },
                        validationStages = mergeStages(
                            finalReport.validationStages,
                            listOf(
                                ValidationStageResult(
                                    ValidationStage.RUNTIME,
                                    ValidationStatus.NOT_RUN,
                                    "Real v159.7 ContentParser/DataPatcher apply validation was not invoked.",
                                    listOf(notRun.code),
                                ),
                                ValidationStageResult(
                                    ValidationStage.SERVER_LOAD,
                                    ValidationStatus.NOT_RUN,
                                    "No map/save carrying the DP was loaded by a server.",
                                ),
                            ),
                        ),
                        metadata = finalReport.metadata + mapOf(
                            "dataPatchApply" to "notRun",
                            "serverAssetDiscovery" to "notRun",
                            "serverLoadValidation" to "notRun:no-map-or-save-loaded",
                        ),
                    )
                }

                finalReport = finalReport.copy(
                    summary = finalReport.summary.withDiagnosticCounts(finalReport.diagnostics),
                )

                Files.writeString(result.reportJson, ConversionReportJson.encode(finalReport) + "\n", StandardCharsets.UTF_8)
                Files.writeString(result.reportMarkdown, finalReport.toMarkdown(), StandardCharsets.UTF_8)

                finalReport.diagnostics.forEach { diagnostic -> logger.diagnostic(diagnostic) }
                logger.info("Status: ${finalReport.status}")
                logger.info("DP ZIP: ${result.dpZip}")
                logger.info("Server assets: ${result.serverAssets}")
                logger.info("JSON report: ${result.reportJson}")
                logger.info("Markdown report: ${result.reportMarkdown}")
                if (serverJar == null) {
                    logger.warn("ContentParser/DataPatcher apply validation was not run; supply --server-jar to enable it.")
                }
                logger.warn("Server-load validation was not run: no map/save carrying the DP was loaded.")
                logger.warn("Map-editor import validation still requires the real client/Desktop Worker.")

                if (finalReport.status == ConversionStatus.REJECTED ||
                    finalReport.diagnostics.any { it.severity == DiagnosticSeverity.ERROR }
                ) 2 else 0
            } catch (error: ConversionException) {
                logger.error("Conversion failed: ${error.message}")
                error.logs.forEach(logger::raw)
                error.diagnostics.forEach { diagnostic -> logger.diagnostic(diagnostic) }
                writeFailureFiles(outputDirectory, normalizedInput, error, logger)
                3
            } catch (error: Throwable) {
                logger.error("Unexpected failure: ${error.stackTraceToString()}")
                writeUnexpectedFailure(outputDirectory, normalizedInput, error)
                4
            }
        }
    }

    private fun defaultOutput(input: Path): Path {
        val name = input.fileName?.toString()?.substringBeforeLast('.')?.ifBlank { "conversion" } ?: "conversion"
        return Path.of("out").resolve(name)
    }

    private fun mib(value: Long): Long {
        require(value > 0) { "Size limits must be positive." }
        return Math.multiplyExact(value, 1024L * 1024L)
    }
}

private fun mergeStages(
    original: List<ValidationStageResult>,
    updates: List<ValidationStageResult>,
): List<ValidationStageResult> {
    val byStage = original.associateBy { it.stage }.toMutableMap()
    updates.forEach { update ->
        val previous = byStage[update.stage]
        byStage[update.stage] = if (previous?.status == ValidationStatus.FAILED && update.status != ValidationStatus.FAILED) {
            previous.copy(
                summary = listOfNotNull(previous.summary, update.summary).distinct().joinToString(" "),
                diagnosticCodes = (previous.diagnosticCodes + update.diagnosticCodes).distinct(),
            )
        } else {
            update
        }
    }
    return ValidationStage.entries.mapNotNull(byStage::get)
}

private fun ReportSummary.withDiagnosticCounts(diagnostics: List<Diagnostic>): ReportSummary = copy(
    infoCount = diagnostics.count { it.severity == DiagnosticSeverity.INFO },
    warningCount = diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
    errorCount = diagnostics.count { it.severity == DiagnosticSeverity.ERROR },
)

private fun BridgeLogger.diagnostic(diagnostic: Diagnostic) {
    val text = buildString {
        append("${diagnostic.code}: ${diagnostic.message}")
        diagnostic.location?.let { location ->
            append(" [${location.path}")
            location.line?.let { append(":$it") }
            location.column?.let { append(":$it") }
            append(']')
        }
        diagnostic.details?.takeIf(String::isNotBlank)?.let { append(" | details: ${it.forLog()}") }
        diagnostic.suggestion?.takeIf(String::isNotBlank)?.let { append(" | suggestion: ${it.forLog()}") }
    }
    when (diagnostic.severity) {
        DiagnosticSeverity.INFO -> info(text)
        DiagnosticSeverity.WARNING -> warn(text)
        DiagnosticSeverity.ERROR -> error(text)
    }
}

private fun String.forLog(): String = lineSequence().joinToString("\\n") { it.trimEnd() }

private fun writeFailureFiles(
    outputDirectory: Path,
    input: Path,
    error: ConversionException,
    logger: BridgeLogger,
) {
    val text = buildString {
        appendLine("Conversion failed")
        appendLine("Input: ${input.absolutePathString()}")
        appendLine("Message: ${error.message}")
        appendLine()
        appendLine("Diagnostics:")
        if (error.diagnostics.isEmpty()) appendLine("- No structured diagnostics were produced.")
        error.diagnostics.forEach { diagnostic ->
            appendLine("- [${diagnostic.severity}] ${diagnostic.code}: ${diagnostic.message}")
            diagnostic.location?.let { appendLine("  location: ${it.path}:${it.line ?: "?"}:${it.column ?: "?"}") }
            diagnostic.details?.let { appendLine("  details: $it") }
            diagnostic.suggestion?.let { appendLine("  suggestion: $it") }
        }
        appendLine()
        appendLine("Logs:")
        error.logs.forEach { appendLine(it) }
    }
    Files.writeString(outputDirectory.resolve("failure-report.txt"), text, StandardCharsets.UTF_8)

    if (error.diagnostics.isNotEmpty()) {
        val json = ConversionReportJson.format.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Diagnostic.serializer()),
            error.diagnostics,
        )
        Files.writeString(outputDirectory.resolve("failure-diagnostics.json"), json + "\n", StandardCharsets.UTF_8)
    }
    logger.info("Failure details written under $outputDirectory")
}

private fun writeUnexpectedFailure(outputDirectory: Path, input: Path, error: Throwable) {
    Files.writeString(
        outputDirectory.resolve("failure-report.txt"),
        "Input: ${input.absolutePathString()}\n\n${error.stackTraceToString()}",
        StandardCharsets.UTF_8,
    )
}

fun main(args: Array<String>) {
    val exitCode = CommandLine(RootCommand()).execute(*args)
    if (exitCode != 0) {
        kotlin.system.exitProcess(exitCode)
    }
}
