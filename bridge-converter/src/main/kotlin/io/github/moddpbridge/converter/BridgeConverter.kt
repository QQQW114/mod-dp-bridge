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
import java.util.Locale

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
    fun convert(request: ConversionRequest): ConverterResult = convertInternal(request, runtime = null)

    /**
     * Packages an already-mapped runtime snapshot without loading Mod code or invoking static
     * exporters. The release JAR is still read through [SafeSourceReader].
     */
    fun convertRuntimePrepared(
        request: ConversionRequest,
        runtime: RuntimePreparedConversion,
    ): ConverterResult = convertInternal(request, runtime)

    private fun convertInternal(
        request: ConversionRequest,
        runtime: RuntimePreparedConversion?,
    ): ConverterResult {
        val logger = ConverterLogger(request.logSink)
        val diagnostics = mutableListOf<Diagnostic>()
        try {
            validateRequest(request)
            logger.log("Reading input: ${request.input.toAbsolutePath().normalize()}")
            val snapshot = SafeSourceReader.read(request.input, request.limits, logger, diagnostics)
            val detection = SourceDetector.detect(snapshot)
            logger.log("Detected source: ${detection.kind.name.lowercase()}")

            val staticExport = if (runtime == null) {
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
                runStaticExporters(configuredExporters, snapshot, detection, diagnostics, logger)
            } else {
                logger.log("Using inert runtime-prepared declarations; static source exporters are disabled.")
                prepareRuntimeAggregate(runtime, snapshot, detection, diagnostics, logger)
            }
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
                runtimePrepared = runtime != null,
            )
            Files.writeString(reportJson, ConversionReportJson.encode(report) + "\n", StandardCharsets.UTF_8)
            Files.writeString(reportMarkdown, report.toMarkdown(), StandardCharsets.UTF_8)
            logger.log(
                if (runtime == null) {
                    "Conversion completed with static validation; runtime validation was not run."
                } else {
                    "Runtime-prepared conversion was deterministically planned and packaged; DP runtime validation was not run."
                },
            )

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

    private fun prepareRuntimeAggregate(
        runtime: RuntimePreparedConversion,
        snapshot: SourceSnapshot,
        detection: SourceDetection,
        diagnostics: MutableList<Diagnostic>,
        logger: ConverterLogger,
    ): StaticExportAggregate {
        if (detection.kind != DetectedSourceKind.MOD ||
            !snapshot.originalName.endsWith(".jar", ignoreCase = true)
        ) {
            fail(
                code = "RUNTIME_PREPARED_REQUIRES_MOD_JAR",
                message = "Runtime-prepared conversion requires the published Mod .jar as input.",
                diagnostics = diagnostics,
                logger = logger,
            )
        }

        diagnostics += runtime.diagnostics
        runtime.logs.forEach { logger.log("[runtime-prepared] $it") }

        val entriesByPath = snapshot.entries.associateBy { it.path }
        val generated = mutableListOf<StaticGeneratedFile>()
        val emittedBySource = linkedMapOf<String, MutableList<String>>()
        val jarCopiedSources = hashSetOf<String>()
        val preparedPathRewrites = linkedMapOf<String, String>()
        val originalPatchOutputs = snapshot.entries.asSequence()
            .filter { isOriginalPatchPath(it.path) }
            .map { runtimeAssetRelativePath(it.path) }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()

        runtime.files.sortedBy { it.outputPath }.forEach { file ->
            val sourcePaths = file.sourcePaths.map { source ->
                canonicalRuntimeSourcePath(source, snapshot, entriesByPath, diagnostics, logger)
            }.distinct().sorted()
            if (sourcePaths.isEmpty()) {
                fail(
                    code = "RUNTIME_PREPARED_PROVENANCE_MISSING",
                    message = "Runtime-prepared output '${file.outputPath}' has no release-JAR provenance.",
                    diagnostics = diagnostics,
                    logger = logger,
                )
            }

            val rawOutputPath = normalizeRuntimeOutputPath(file.outputPath, diagnostics, logger)
            if (rawOutputPath.lowercase(Locale.ROOT) in originalPatchOutputs) {
                fail(
                    code = "RUNTIME_PREPARED_PATCH_REPLACEMENT_FORBIDDEN",
                    message = "Runtime-prepared output may not replace an existing patch entry: '$rawOutputPath'.",
                    diagnostics = diagnostics,
                    logger = logger,
                )
            }
            val (outputPath, assetRewriteReason) = if (file.bytes == null) {
                if (sourcePaths.size != 1) {
                    fail(
                        code = "RUNTIME_PREPARED_JAR_COPY_AMBIGUOUS",
                        message = "A release-JAR byte copy must identify exactly one source entry: '$rawOutputPath'.",
                        diagnostics = diagnostics,
                        logger = logger,
                    )
                }
                ConversionPlanner.applyPreparedModAssetPathRules(rawOutputPath, detection.modNamespace)
            } else {
                rawOutputPath to null
            }
            preparedPathRewrites[rawOutputPath] = outputPath

            val bytes = file.bytes?.copyOf() ?: entriesByPath.getValue(sourcePaths.single()).bytes.copyOf()
            val namespace = when (file.namespace) {
                RuntimePreparedOutputNamespace.SOURCE -> StaticOutputNamespace.SOURCE
                RuntimePreparedOutputNamespace.TARGET -> StaticOutputNamespace.TARGET
            }
            generated += StaticGeneratedFile(
                outputPath = outputPath,
                bytes = bytes,
                sourcePaths = sourcePaths,
                namespace = namespace,
                reason = listOfNotNull(file.reason, assetRewriteReason).distinct().joinToString(" "),
            )
            sourcePaths.filterNot(::isOriginalPatchPath).forEach { source ->
                emittedBySource.getOrPut(source, ::mutableListOf) += outputPath
                if (file.bytes == null) jarCopiedSources += source
            }
        }

        val outputPaths = generated.map { it.outputPath }.toSet()
        val outcomes = linkedMapOf<String, StaticSourceOutcome>()
        runtime.fileResults.sortedBy { it.sourcePath }.forEach { result ->
            val sourcePath = canonicalRuntimeSourcePath(
                result.sourcePath,
                snapshot,
                entriesByPath,
                diagnostics,
                logger,
            )
            if (isOriginalPatchPath(sourcePath)) {
                fail(
                    code = "RUNTIME_PREPARED_PATCH_REPLACEMENT_FORBIDDEN",
                    message = "Existing patch entries are preserved and may not be claimed by runtime-prepared results.",
                    diagnostics = diagnostics,
                    logger = logger,
                )
            }
            if (sourcePath in outcomes) {
                fail(
                    code = "RUNTIME_PREPARED_SOURCE_CONFLICT",
                    message = "More than one runtime-prepared result claims '$sourcePath'.",
                    diagnostics = diagnostics,
                    logger = logger,
                )
            }
            val declaredOutputs = result.outputPaths.map { path ->
                val normalized = normalizeRuntimeOutputPath(path, diagnostics, logger)
                preparedPathRewrites[normalized] ?: normalized
            }
            val linkedOutputs = (declaredOutputs + emittedBySource[sourcePath].orEmpty()).distinct().sorted()
            val missing = linkedOutputs.firstOrNull { it !in outputPaths }
            if (missing != null) {
                fail(
                    code = "RUNTIME_PREPARED_OUTPUT_MISSING",
                    message = "Runtime-prepared file result for '$sourcePath' refers to an output that was not emitted: '$missing'.",
                    diagnostics = diagnostics,
                    logger = logger,
                )
            }
            outcomes[sourcePath] = StaticSourceOutcome(
                sourcePath = sourcePath,
                status = result.status,
                reason = result.reason,
                outputPaths = linkedOutputs,
                diagnosticCodes = result.diagnosticCodes.distinct(),
            )
        }

        emittedBySource.forEach { (sourcePath, paths) ->
            val existing = outcomes[sourcePath]
            if (existing != null) {
                outcomes[sourcePath] = existing.copy(outputPaths = (existing.outputPaths + paths).distinct().sorted())
            } else {
                outcomes[sourcePath] = StaticSourceOutcome(
                    sourcePath = sourcePath,
                    status = if (sourcePath in jarCopiedSources) ConvertedFileStatus.COPIED else ConvertedFileStatus.NORMALIZED,
                    reason = if (sourcePath in jarCopiedSources) {
                        "Selected from the authoritative release JAR by the runtime asset stage."
                    } else {
                        "Runtime-observed content was mapped to inert target data assets."
                    },
                    outputPaths = paths.distinct().sorted(),
                )
            }
        }

        snapshot.entries.forEach { entry ->
            val replacementReason = when {
                runtime.replaceOriginalContent && isOriginalContentPath(entry.path) ->
                    "Original declarative content was replaced by the authoritative runtime snapshot."
                isOriginalRuntimeAssetPath(entry.path) ->
                    "The authoritative filtered runtime asset list did not select this JAR entry."
                else -> null
            }
            if (replacementReason != null && !isOriginalPatchPath(entry.path)) {
                outcomes.putIfAbsent(
                    entry.path,
                    StaticSourceOutcome(
                        sourcePath = entry.path,
                        status = ConvertedFileStatus.EXCLUDED,
                        reason = replacementReason,
                    ),
                )
            }
        }

        val contentResults = runtime.contentResults.map(RuntimePreparedContentResult::toModel)
        validateRuntimePreparedReportContract(generated, outcomes, contentResults, diagnostics)
        logger.log(
            "Accepted ${generated.size} runtime-prepared file(s), ${contentResults.size} content result(s), " +
                "and ${outcomes.size} release-JAR file outcome(s).",
        )
        return StaticExportAggregate(
            generatedFiles = generated,
            sourceOutcomes = outcomes,
            contentResults = contentResults,
            metadata = runtime.metadata + mapOf(
                "runtimePrepared.generatedFiles" to generated.size.toString(),
                "runtimePrepared.claimedSourceFiles" to outcomes.size.toString(),
                "runtimePrepared.contentResults" to contentResults.size.toString(),
                "runtimePrepared.originalContentReplaced" to runtime.replaceOriginalContent.toString(),
            ),
        )
    }

    private fun canonicalRuntimeSourcePath(
        raw: String,
        snapshot: SourceSnapshot,
        entriesByPath: Map<String, SourceEntry>,
        diagnostics: MutableList<Diagnostic>,
        logger: ConverterLogger,
    ): String {
        val normalized = normalizeRuntimePath(raw, "source entry", diagnostics, logger)
        if (normalized in entriesByPath) return normalized
        val stripped = snapshot.strippedRoot?.let { root ->
            normalized.removePrefix("$root/").takeIf { it != normalized }
        }
        if (stripped != null && stripped in entriesByPath) return stripped
        fail(
            code = "RUNTIME_PREPARED_UNKNOWN_SOURCE",
            message = "Runtime-prepared provenance refers to an unknown release-JAR entry: '$raw'.",
            diagnostics = diagnostics,
            logger = logger,
        )
    }

    private fun normalizeRuntimeOutputPath(
        raw: String,
        diagnostics: MutableList<Diagnostic>,
        logger: ConverterLogger,
    ): String = normalizeRuntimePath(raw, "output", diagnostics, logger)

    private fun normalizeRuntimePath(
        raw: String,
        description: String,
        diagnostics: MutableList<Diagnostic>,
        logger: ConverterLogger,
    ): String {
        val replaced = raw.replace('\\', '/')
        val segments = replaced.split('/')
        if (
            replaced.isBlank() || replaced.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(replaced) ||
            ':' in replaced || '\u0000' in replaced || segments.any { it.isBlank() || it == "." || it == ".." }
        ) {
            fail(
                code = "RUNTIME_PREPARED_PATH_INVALID",
                message = "Runtime-prepared $description path is unsafe: '$raw'.",
                diagnostics = diagnostics,
                logger = logger,
            )
        }
        return segments.joinToString("/")
    }

    private fun validateRuntimePreparedReportContract(
        generated: List<StaticGeneratedFile>,
        outcomes: Map<String, StaticSourceOutcome>,
        contents: List<io.github.moddpbridge.model.ContentResult>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        val generatedPaths = generated.map { it.outputPath }.toSet()
        generated.asSequence()
            .filter { it.outputPath.startsWith("content/", ignoreCase = true) }
            .filter { output -> contents.none { it.outputPath == output.outputPath } }
            .forEach { output ->
                diagnostics += Diagnostic(
                    code = "RUNTIME_PREPARED_CONTENT_RESULT_MISSING",
                    severity = DiagnosticSeverity.ERROR,
                    message = "Runtime-generated content is missing its declaration-level result.",
                    stage = ValidationStage.STRUCTURE,
                    details = output.outputPath,
                )
            }
        contents.filter {
            it.disposition == ContentDisposition.CONVERTED || it.disposition == ContentDisposition.DEGRADED
        }.filter { it.outputPath == null || it.outputPath !in generatedPaths }.forEach { content ->
            diagnostics += Diagnostic(
                code = "RUNTIME_PREPARED_CONTENT_OUTPUT_MISSING",
                severity = DiagnosticSeverity.ERROR,
                message = "A converted runtime content result has no matching prepared HJSON file.",
                stage = ValidationStage.STRUCTURE,
                location = content.location,
                details = "${content.sourceSymbol} -> ${content.outputPath ?: "<none>"}",
            )
        }
        outcomes.values.flatMap { outcome -> outcome.outputPaths.map { outcome to it } }
            .filter { (_, outputPath) -> outputPath !in generatedPaths }
            .forEach { (outcome, outputPath) ->
                diagnostics += Diagnostic(
                    code = "RUNTIME_PREPARED_FILE_OUTPUT_MISSING",
                    severity = DiagnosticSeverity.ERROR,
                    message = "A runtime-prepared file result refers to an output that was not emitted.",
                    stage = ValidationStage.STRUCTURE,
                    details = "${outcome.sourcePath} -> $outputPath",
                )
            }
    }

    private fun isOriginalContentPath(path: String): Boolean = runtimeAssetRelativePath(path)
        .substringBefore('/')
        .equals("content", ignoreCase = true)

    private fun isOriginalPatchPath(path: String): Boolean = runtimeAssetRelativePath(path)
        .substringBefore('/')
        .equals("patches", ignoreCase = true)

    private fun isOriginalRuntimeAssetPath(path: String): Boolean = runtimeAssetRelativePath(path)
        .substringBefore('/')
        .lowercase(Locale.ROOT) in setOf("bundles", "sprites", "sounds", "music")

    private fun runtimeAssetRelativePath(path: String): String =
        if (path.substringBefore('/').equals("assets", ignoreCase = true)) path.substringAfter('/', "") else path

    private fun buildReport(
        snapshot: SourceSnapshot,
        sourceKind: SourceKind,
        detection: SourceDetection,
        plan: ConversionPlan,
        diagnostics: List<Diagnostic>,
        outputs: List<OutputArtifact>,
        runtimePrepared: Boolean,
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
            toolVersion = "0.2.0",
            // Kept deterministic. The caller may add a publication timestamp later.
            generatedAt = null,
            target = TargetDescriptor(
                id = "mindustry-v159.7",
                gameVersion = "159.7",
                commit = "c9686eb5d0ae5dd47ee02c40f99f7d5018ccbc8c",
                dataPatchFormatVersion = 2,
                description = if (runtimePrepared) {
                    "Runtime-observed content mapped to Data Assets; DP runtime validation pending."
                } else {
                    "Static Data Assets conversion; runtime validation pending."
                },
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
            ) + (if (runtimePrepared) mapOf("runtimePrepared" to "true") else emptyMap()) + plan.metadata,
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
