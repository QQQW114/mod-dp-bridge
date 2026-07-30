package io.github.moddpbridge.converter

import io.github.moddpbridge.model.ConversionInventory
import io.github.moddpbridge.model.ConversionReport
import io.github.moddpbridge.model.ConversionReportJson
import io.github.moddpbridge.model.ConversionStatus
import io.github.moddpbridge.model.ContentDisposition
import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.FileDisposition
import io.github.moddpbridge.model.FileResult
import io.github.moddpbridge.model.OutputArtifact
import io.github.moddpbridge.model.OutputArtifactKind
import io.github.moddpbridge.model.ReportSummary
import io.github.moddpbridge.model.SourceDescriptor
import io.github.moddpbridge.model.SourceKind
import io.github.moddpbridge.model.TargetDescriptor
import io.github.moddpbridge.model.ValidationStage
import io.github.moddpbridge.model.ValidationStageResult
import io.github.moddpbridge.model.ValidationStatus
import io.github.moddpbridge.model.toMarkdown
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class SecurityLimits(
    val maxInputBytes: Long = 64_000_000L,
    val maxEntries: Int = 2_048,
    val maxEntryBytes: Long = 32_000_000L,
    val maxExpandedBytes: Long = 128_000_000L,
    val maxCompressionRatio: Double = 200.0,
    val maxPathLength: Int = 512,
)

data class ConversionRequest(
    val input: Path,
    val outputDirectory: Path,
    val outputBaseName: String? = null,
    val overwrite: Boolean = false,
    val limits: SecurityLimits = SecurityLimits(),
    /** Receives the same ordered messages returned in [ConverterResult.logs]. */
    val logSink: ((String) -> Unit)? = null,
    /** Null discovers deterministic source exporters through ServiceLoader inside the audited run. */
    val staticSourceExporters: List<StaticSourceExporter>? = null,
)

enum class DetectedSourceKind {
    MOD,
    LEGACY_CP,
    DATA_PACK,
}

enum class ConvertedFileStatus {
    COPIED,
    NORMALIZED,
    EXCLUDED,
    UNSUPPORTED,
    FAILED,
}

data class ConvertedFile(
    val sourcePath: String,
    val outputPath: String? = null,
    val outputPaths: List<String> = outputPath?.let { listOf(it) } ?: emptyList(),
    val status: ConvertedFileStatus,
    val reason: String? = null,
    val diagnosticCodes: List<String> = emptyList(),
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

data class ConverterResult(
    val sourceKind: SourceKind,
    val detectedKind: DetectedSourceKind,
    val status: ConversionStatus,
    val dpZip: Path,
    val serverAssets: Path,
    val reportJson: Path,
    val reportMarkdown: Path,
    val inventory: ConversionInventory,
    val diagnostics: List<Diagnostic>,
    val files: List<ConvertedFile>,
    val logs: List<String>,
    val report: ConversionReport,
)

class ConversionException(
    message: String,
    val diagnostics: List<Diagnostic> = emptyList(),
    val logs: List<String> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Deterministic, offline converter. It never executes Java/JS code from the input. */
object BridgeConverter {
    fun convert(request: ConversionRequest): ConverterResult {
        val logger = ConverterLogger(request.logSink)
        val diagnostics = mutableListOf<Diagnostic>()
        try {
            validateRequest(request)
            logger.log("Reading input: ${request.input.toAbsolutePath().normalize()}")
            val snapshot = SafeSourceReader.read(request.input, request.limits, logger, diagnostics)
            val detection = SourceDetector.detect(snapshot)
            logger.log("Detected source: ${detection.kind.name.lowercase()}")

            val configuredExporters = request.staticSourceExporters ?: StaticSourceExporters.discover(
                onFailure = { error ->
                    diagnostics += Diagnostic(
                        code = "STATIC_EXPORTER_DISCOVERY_FAILED",
                        severity = DiagnosticSeverity.ERROR,
                        message = "A configured static source exporter could not be loaded.",
                        stage = ValidationStage.STRUCTURE,
                        details = "${error.javaClass.name}: ${error.message.orEmpty()}",
                        suggestion = "Repair the provider class/META-INF/services entry; ordinary assets were still processed.",
                    )
                    logger.log("Static source exporter discovery failed: ${error.message ?: error.javaClass.simpleName}")
                },
            )
            val staticExport = runStaticExporters(
                configuredExporters,
                snapshot,
                detection,
                diagnostics,
                logger,
            )
            val plan = ConversionPlanner.plan(snapshot, detection, staticExport, diagnostics, logger)
            if (plan.outputFiles.isEmpty()) {
                fail(
                    code = "NO_SUPPORTED_ASSETS",
                    message = "The input did not contain any supported data assets.",
                    diagnostics = diagnostics,
                    logger = logger,
                )
            }

            val outputRoot = request.outputDirectory.toAbsolutePath().normalize()
            Files.createDirectories(outputRoot)
            val serverAssets = outputRoot.resolve("server-assets")
            val archiveBase = request.outputBaseName?.let(::safeSlug)
                ?.takeIf { it.isNotBlank() }
                ?: plan.slug
            val dpZip = outputRoot.resolve("$archiveBase-dp-v159.7.zip")
            val reportJson = outputRoot.resolve("report.json")
            val reportMarkdown = outputRoot.resolve("report.md")

            prepareOutput(serverAssets, dpZip, reportJson, reportMarkdown, outputRoot, request.overwrite)
            logger.log("Writing server assets: $serverAssets")
            DeterministicPackager.writeServerAssets(serverAssets, plan.outputFiles)
            logger.log("Writing deterministic data-pack ZIP: $dpZip")
            DeterministicPackager.writeZip(dpZip, plan.outputFiles)

            val sourceKind = modelSourceKind(snapshot, detection.kind)
            val outputs = listOf(
                OutputArtifact(
                    kind = OutputArtifactKind.DATA_PACK_ZIP,
                    path = dpZip.toString(),
                    sizeBytes = Files.size(dpZip),
                    sha256 = sha256(dpZip),
                ),
                OutputArtifact(
                    kind = OutputArtifactKind.SERVER_ASSETS,
                    path = serverAssets.toString(),
                    sizeBytes = plan.outputFiles.sumOf { it.bytes.size.toLong() },
                    sha256 = DeterministicPackager.treeHash(plan.outputFiles),
                ),
            )
            val report = buildReport(
                snapshot = snapshot,
                sourceKind = sourceKind,
                detection = detection,
                plan = plan,
                diagnostics = diagnostics,
                outputs = outputs,
            )
            Files.writeString(reportJson, ConversionReportJson.encode(report) + "\n", StandardCharsets.UTF_8)
            Files.writeString(reportMarkdown, report.toMarkdown(), StandardCharsets.UTF_8)
            logger.log("Conversion completed with static validation; runtime validation was not run.")

            return ConverterResult(
                sourceKind = sourceKind,
                detectedKind = detection.kind,
                status = report.status,
                dpZip = dpZip,
                serverAssets = serverAssets,
                reportJson = reportJson,
                reportMarkdown = reportMarkdown,
                inventory = plan.inventory,
                diagnostics = diagnostics.toList(),
                files = plan.convertedFiles,
                logs = logger.messages.toList(),
                report = report,
            )
        } catch (error: ConversionException) {
            val combined = (diagnostics + error.diagnostics).distinct()
            val structured = if (combined.isEmpty()) {
                listOf(
                    Diagnostic(
                        code = "INPUT_REJECTED",
                        severity = DiagnosticSeverity.ERROR,
                        message = error.message ?: "Input was rejected.",
                        stage = ValidationStage.STRUCTURE,
                    ),
                )
            } else {
                combined
            }
            val message = error.message ?: "Conversion failed"
            if (logger.messages.lastOrNull() != message) logger.log(message)
            throw ConversionException(
                message = message,
                diagnostics = structured,
                logs = (logger.messages + error.logs).distinct(),
                cause = error.cause,
            )
        } catch (error: Throwable) {
            val diagnostic = Diagnostic(
                code = "CONVERSION_FAILED",
                severity = DiagnosticSeverity.ERROR,
                message = error.message ?: error.javaClass.simpleName,
                stage = ValidationStage.STRUCTURE,
                details = error.javaClass.name,
            )
            diagnostics += diagnostic
            logger.log("Conversion failed: ${diagnostic.message}")
            throw ConversionException(diagnostic.message, diagnostics.toList(), logger.messages.toList(), error)
        }
    }

    private fun buildReport(
        snapshot: SourceSnapshot,
        sourceKind: SourceKind,
        detection: SourceDetection,
        plan: ConversionPlan,
        diagnostics: List<Diagnostic>,
        outputs: List<OutputArtifact>,
    ): ConversionReport {
        val infos = diagnostics.count { it.severity == DiagnosticSeverity.INFO }
        val warnings = diagnostics.count { it.severity == DiagnosticSeverity.WARNING }
        val errors = diagnostics.count { it.severity == DiagnosticSeverity.ERROR }
        val fileResults = plan.convertedFiles.map { file ->
            FileResult(
                sourcePath = file.sourcePath,
                disposition = when (file.status) {
                    ConvertedFileStatus.COPIED -> FileDisposition.COPIED
                    ConvertedFileStatus.NORMALIZED -> FileDisposition.CONVERTED
                    ConvertedFileStatus.EXCLUDED -> FileDisposition.EXCLUDED
                    ConvertedFileStatus.UNSUPPORTED -> FileDisposition.UNSUPPORTED
                    ConvertedFileStatus.FAILED -> FileDisposition.FAILED
                },
                outputPath = file.outputPath,
                outputPaths = file.outputPaths,
                reason = file.reason ?: when (file.status) {
                    ConvertedFileStatus.COPIED -> "Copied byte-for-byte."
                    ConvertedFileStatus.NORMALIZED -> "Parsed and normalized to deterministic formatted text."
                    ConvertedFileStatus.EXCLUDED,
                    ConvertedFileStatus.UNSUPPORTED,
                    ConvertedFileStatus.FAILED -> null
                },
                diagnosticCodes = (
                    file.diagnosticCodes + diagnostics.asSequence()
                        .filter { it.location?.path == file.sourcePath }
                        .map { it.code }
                        .toList()
                    ).distinct(),
            )
        }
        return ConversionReport(
            toolVersion = "0.1.0-SNAPSHOT",
            // Kept deterministic. The caller may add a publication timestamp later.
            generatedAt = null,
            target = TargetDescriptor(
                id = "mindustry-v159.7",
                gameVersion = "159.7",
                commit = "c9686eb5d0ae5dd47ee02c40f99f7d5018ccbc8c",
                dataPatchFormatVersion = 2,
                description = "Static Data Assets conversion; runtime validation pending.",
            ),
            source = SourceDescriptor(
                kind = sourceKind,
                name = snapshot.originalName,
                path = snapshot.input.toString(),
                sizeBytes = snapshot.totalBytes,
                sha256 = snapshot.sha256,
            ),
            status = ConversionStatus.PARTIAL,
            summary = ReportSummary(
                scannedFiles = plan.inventory.scannedFiles,
                contentFiles = plan.inventory.contents.size,
                assetFiles = plan.inventory.assets.size,
                infoCount = infos,
                warningCount = warnings,
                errorCount = errors,
                convertedContents = plan.contentResults.count { it.disposition == ContentDisposition.CONVERTED },
                degradedContents = plan.contentResults.count { it.disposition == ContentDisposition.DEGRADED },
                excludedContents = plan.contentResults.count { it.disposition == ContentDisposition.EXCLUDED },
                unsupportedContents = plan.contentResults.count { it.disposition == ContentDisposition.UNSUPPORTED },
                failedContents = plan.contentResults.count { it.disposition == ContentDisposition.FAILED },
            ),
            inventory = plan.inventory,
            fileResults = fileResults,
            contentResults = plan.contentResults,
            diagnostics = diagnostics,
            validationStages = listOf(
                ValidationStageResult(
                    stage = ValidationStage.STRUCTURE,
                    status = if (errors == 0) ValidationStatus.PASSED else ValidationStatus.FAILED,
                    summary = if (errors == 0) {
                        "Input was safely scanned, converted and deterministically packaged."
                    } else {
                        "Artifacts were generated, but one or more conversion/reference checks failed."
                    },
                    diagnosticCodes = diagnostics.map { it.code }.distinct(),
                ),
                ValidationStageResult(ValidationStage.RUNTIME, ValidationStatus.NOT_RUN),
                ValidationStageResult(ValidationStage.MAP_IMPORT, ValidationStatus.NOT_RUN),
                ValidationStageResult(ValidationStage.SERVER_LOAD, ValidationStatus.NOT_RUN),
            ),
            outputs = outputs,
            metadata = mapOf(
                "detectedSourceKind" to detection.kind.name.lowercase(),
                "strippedRoot" to (snapshot.strippedRoot ?: ""),
                "normalizedTextFiles" to plan.normalizedTextFiles.toString(),
                // Preserve the original aggregate meaning for existing report consumers.
                "excludedFiles" to plan.inventory.ignored.size.toString(),
                "ignoredFiles" to plan.inventory.ignored.size.toString(),
                "copiedFiles" to fileResults.count { it.disposition == FileDisposition.COPIED }.toString(),
                "convertedFiles" to fileResults.count { it.disposition == FileDisposition.CONVERTED }.toString(),
                "policyExcludedFiles" to fileResults.count { it.disposition == FileDisposition.EXCLUDED }.toString(),
                "unsupportedFiles" to fileResults.count { it.disposition == FileDisposition.UNSUPPORTED }.toString(),
                "failedFiles" to fileResults.count { it.disposition == FileDisposition.FAILED }.toString(),
            ) + plan.metadata,
        )
    }

    private fun modelSourceKind(snapshot: SourceSnapshot, detected: DetectedSourceKind): SourceKind = when (detected) {
        DetectedSourceKind.MOD -> if (snapshot.physicalKind == SourceKind.DIRECTORY) {
            SourceKind.DIRECTORY
        } else {
            SourceKind.MOD_ARCHIVE
        }

        DetectedSourceKind.DATA_PACK -> SourceKind.DATA_PACK_ARCHIVE
        DetectedSourceKind.LEGACY_CP -> SourceKind.LEGACY_CP
    }

    private fun validateRequest(request: ConversionRequest) {
        require(request.limits.maxInputBytes > 0)
        require(request.limits.maxEntries > 0)
        require(request.limits.maxEntryBytes > 0)
        require(request.limits.maxExpandedBytes > 0)
        require(request.limits.maxCompressionRatio >= 1.0)
        require(request.limits.maxPathLength > 0)
        if (!Files.exists(request.input)) {
            throw ConversionException("Input does not exist: ${request.input}")
        }
        if (Files.exists(request.outputDirectory) && !Files.isDirectory(request.outputDirectory)) {
            throw ConversionException("Output path is not a directory: ${request.outputDirectory}")
        }
    }

    private fun prepareOutput(
        serverAssets: Path,
        dpZip: Path,
        reportJson: Path,
        reportMarkdown: Path,
        outputRoot: Path,
        overwrite: Boolean,
    ) {
        listOf(serverAssets, dpZip, reportJson, reportMarkdown).forEach { path ->
            if (!Files.exists(path)) return@forEach
            if (!overwrite) {
                throw ConversionException("Output already exists: $path")
            }
            deleteSafely(path, outputRoot)
        }
    }

    private fun deleteSafely(path: Path, outputRoot: Path) {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(outputRoot) && normalized != outputRoot) {
            "Refusing to delete outside output directory: $normalized"
        }
        if (Files.isDirectory(normalized)) {
            Files.walk(normalized).use { stream ->
                stream.sorted { left, right -> right.compareTo(left) }.forEach { Files.deleteIfExists(it) }
            }
        } else {
            Files.deleteIfExists(normalized)
        }
    }

    private fun fail(
        code: String,
        message: String,
        diagnostics: MutableList<Diagnostic>,
        logger: ConverterLogger,
    ): Nothing {
        diagnostics += Diagnostic(
            code = code,
            severity = DiagnosticSeverity.ERROR,
            message = message,
            stage = ValidationStage.STRUCTURE,
        )
        logger.log(message)
        throw ConversionException(message, diagnostics.toList(), logger.messages.toList())
    }
}

internal class ConverterLogger(private val sink: ((String) -> Unit)?) {
    val messages = mutableListOf<String>()

    fun log(message: String) {
        messages += message
        sink?.invoke(message)
    }
}

internal fun safeSlug(value: String): String {
    val normalized = value.trim()
        .replace(Regex("[\\s]+"), "-")
        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "-")
        .trim('-', '.', '_')
        .take(80)
    return normalized.ifBlank { "data-pack" }
}

internal fun sha256(path: Path): String = Files.newInputStream(path).use(::sha256)

internal fun sha256(bytes: ByteArray): String = sha256(bytes.inputStream())

private fun sha256(input: java.io.InputStream): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
