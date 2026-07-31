package io.github.moddpbridge.cli

import io.github.moddpbridge.converter.BridgeConverter
import io.github.moddpbridge.converter.ConversionException
import io.github.moddpbridge.converter.ConversionRequest
import io.github.moddpbridge.converter.RuntimePreparedConversion
import io.github.moddpbridge.converter.SecurityLimits
import io.github.moddpbridge.model.ConversionReport
import io.github.moddpbridge.model.ConversionResult
import io.github.moddpbridge.model.ConversionReportJson
import io.github.moddpbridge.model.ConversionStatus
import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import io.github.moddpbridge.model.ValidationStageResult
import io.github.moddpbridge.model.ValidationStatus
import io.github.moddpbridge.model.toMarkdown
import io.github.moddpbridge.target.v1597.ContentApplyValidationResult
import io.github.moddpbridge.target.v1597.Mindustry1597ContentApplyValidator
import io.github.moddpbridge.target.v1597.Mindustry1597ServerValidator
import io.github.moddpbridge.target.v1597.Mindustry1597StructuralValidator
import io.github.moddpbridge.target.v1597.ServerValidationResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class RuntimePipelineStage(val wireName: String) {
    PREFLIGHT("preflight"),
    RUNTIME_EXTRACTION("runtimeExtraction"),
    SOURCE_INDEX("sourceIndex"),
    RUNTIME_TO_DP_MAPPING("runtimeToDpMapping"),
    HYBRID_SOURCE_SELECTION("hybridSourceSelection"),
    PACKAGING("packaging"),
    DP_VALIDATION("dpValidation"),
}

internal enum class RuntimePipelineStageStatus(val wireName: String) {
    PASSED("passed"),
    FAILED("failed"),
    NOT_RUN("notRun"),
}

internal data class RuntimePipelineStageRecord(
    val stage: RuntimePipelineStage,
    val status: RuntimePipelineStageStatus,
    val summary: String,
    val artifacts: List<Path> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

internal data class RuntimeSnapshotDescriptor(
    val path: Path,
    val sha256: String,
    val schemaVersion: Int,
    val targetMod: String?,
    val contentCount: Int,
    val gameType: String?,
    val gameModifier: String?,
    val gameBuild: Int?,
    val gameRevision: Int?,
)

internal data class RuntimeInputFingerprints(
    val modJarSha256: String,
    val serverJarSha256: String,
)

internal fun interface RuntimeServerJarPolicy {
    fun validate(serverJar: Path, sha256: String)
}

internal object OfficialMindustry1597ServerJarPolicy : RuntimeServerJarPolicy {
    const val SHA256 = "e41289c32bcf765eb50fa131e6b515d741e20f7843fb567d3aa949e7461f22ab"

    override fun validate(serverJar: Path, sha256: String) {
        require(sha256.equals(SHA256, ignoreCase = true)) {
            "--server-jar is not the pinned official Mindustry v159.7 release server: $serverJar " +
                "(expected SHA-256 $SHA256, found $sha256)."
        }
    }
}

internal fun interface RuntimeStructuralValidationRunner {
    fun validate(dpZip: Path): ConversionResult
}

internal fun interface RuntimeServerDiscoveryRunner {
    fun validate(serverAssets: Path, serverJar: Path, timeout: Duration, logSink: (String) -> Unit): ServerValidationResult
}

internal fun interface RuntimeDataPatchApplyRunner {
    fun validate(
        serverAssets: Path,
        serverJar: Path,
        timeout: Duration,
        logSink: (String) -> Unit,
    ): ContentApplyValidationResult
}

/**
 * Inert hand-off into the future field/object mapper. No live Mod object or source-repository byte
 * is permitted here; [snapshot] and the release [modJar] remain authoritative.
 */
internal data class RuntimeToDpMappingInput(
    val snapshot: RuntimeSnapshotDescriptor,
    val modJar: Path,
    val sourceIndexReport: Path?,
    val outputDirectory: Path,
)

internal data class RuntimeToDpMappingResult(
    val status: RuntimePipelineStageStatus,
    val summary: String,
    /** Exact inert handoff consumed by BridgeConverter.convertRuntimePrepared. */
    val preparedConversion: RuntimePreparedConversion? = null,
    val mappingReport: Path? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(status != RuntimePipelineStageStatus.PASSED || preparedConversion != null) {
            "A passed runtime mapping stage must provide RuntimePreparedConversion."
        }
    }
}

internal fun interface RuntimeToDpMappingStage {
    fun map(input: RuntimeToDpMappingInput, logger: BridgeLogger): RuntimeToDpMappingResult
}

/** Retained snapshots are not packageable until a version-specific field mapper accepts them. */
internal object DeferredRuntimeToDpMappingStage : RuntimeToDpMappingStage {
    override fun map(input: RuntimeToDpMappingInput, logger: BridgeLogger): RuntimeToDpMappingResult {
        val summary = "No RuntimeToDpMappingStage implementation is registered for runtime snapshot schema " +
            "${input.snapshot.schemaVersion}; the retained snapshot will not be packaged as a DP."
        logger.warn(summary)
        return RuntimeToDpMappingResult(
            status = RuntimePipelineStageStatus.NOT_RUN,
            summary = summary,
            metadata = mapOf(
                "snapshotSchemaVersion" to input.snapshot.schemaVersion.toString(),
                "mappingContract" to "RuntimePreparedConversion",
            ),
        )
    }
}

internal data class RuntimeConversionPipelineRequest(
    val modJar: Path,
    val serverJar: Path,
    val source: Path?,
    val outputDirectory: Path,
    val modId: String?,
    val runtimeTimeout: Duration,
    val serverValidationTimeout: Duration,
    val allowModExecution: Boolean,
    val hybridMaxRounds: Int = 8,
)

internal data class RuntimeConversionPipelineResult(
    val exitCode: Int,
    val status: String,
    val report: Path,
    val snapshot: Path?,
    val sourceIndexReport: Path?,
    val dpZip: Path? = null,
    val conversionReport: Path? = null,
    val hybridReport: Path? = null,
)

internal class RuntimeConversionPipeline(
    private val extractor: RuntimeExtractorRunner = RuntimeExtractorProcess(),
    private val sourceIndexStage: RuntimeSourceIndexStage = JarRuntimeSourceIndexStage(),
    private val mappingStage: RuntimeToDpMappingStage = RuntimeSnapshotV2MappingStage,
    private val serverJarPolicy: RuntimeServerJarPolicy = OfficialMindustry1597ServerJarPolicy,
    private val structuralValidator: RuntimeStructuralValidationRunner = RuntimeStructuralValidationRunner { dpZip ->
        Mindustry1597StructuralValidator().validate(dpZip)
    },
    private val serverDiscoveryValidator: RuntimeServerDiscoveryRunner = RuntimeServerDiscoveryRunner {
            serverAssets,
            serverJar,
            timeout,
            logSink,
        ->
        Mindustry1597ServerValidator().validate(serverAssets, serverJar, timeout, logSink)
    },
    private val dataPatchApplyValidator: RuntimeDataPatchApplyRunner = RuntimeDataPatchApplyRunner {
            serverAssets,
            serverJar,
            timeout,
            logSink,
        ->
        Mindustry1597ContentApplyValidator().validate(serverAssets, serverJar, timeout, logSink)
    },
) {
    fun run(request: RuntimeConversionPipelineRequest, logger: BridgeLogger): RuntimeConversionPipelineResult {
        val output = request.outputDirectory.toAbsolutePath().normalize()
        val snapshotPath = output.resolve("runtime-snapshot.json")
        val sourceIndexPath = output.resolve("source-index-report.json")
        val pipelineReportPath = output.resolve("runtime-pipeline.json")
        val extractorLog = output.resolve("logs/runtime-extractor.log")
        val extractorCommandLog = output.resolve("logs/runtime-extractor-command.txt")
        val runtimeWork = output.resolve("logs/runtime-work")
        val stages = mutableListOf<RuntimePipelineStageRecord>()
        var snapshot: RuntimeSnapshotDescriptor? = null
        var sourceIndex: RuntimeSourceIndexResult? = null
        var sourceIndexFailure: Diagnostic? = null
        var extractorResult: RuntimeExtractorResult? = null
        var fingerprints: RuntimeInputFingerprints? = null
        var dpZip: Path? = null
        var conversionReport: Path? = null
        var hybridReport: Path? = null

        fun complete(status: String, exitCode: Int, failure: String? = null): RuntimeConversionPipelineResult {
            appendNotRunStages(stages)
            writePipelineReport(
                report = pipelineReportPath,
                status = status,
                request = request,
                stages = stages,
                snapshot = snapshot,
                sourceIndex = sourceIndex,
                extractorResult = extractorResult,
                extractorLog = extractorLog,
                extractorCommandLog = extractorCommandLog,
                runtimeWork = runtimeWork,
                failure = failure,
            )
            return RuntimeConversionPipelineResult(
                exitCode = exitCode,
                status = status,
                report = pipelineReportPath,
                snapshot = snapshot?.path ?: snapshotPath.takeIf(Files::isRegularFile),
                sourceIndexReport = sourceIndex?.report,
                dpZip = dpZip,
                conversionReport = conversionReport,
                hybridReport = hybridReport,
            )
        }

        try {
            val verified = preflight(request)
            fingerprints = verified
            stages += RuntimePipelineStageRecord(
                stage = RuntimePipelineStage.PREFLIGHT,
                status = RuntimePipelineStageStatus.PASSED,
                summary = "Trusted local runtime inputs and explicit execution consent were validated.",
                metadata = mapOf(
                    "modJarSha256" to verified.modJarSha256,
                    "serverJarSha256" to verified.serverJarSha256,
                    "serverJarPolicy" to "official-v159.7-pinned-sha256",
                ),
            )
        } catch (error: Throwable) {
            logger.error("Runtime conversion preflight failed: ${error.message}")
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.PREFLIGHT,
                RuntimePipelineStageStatus.FAILED,
                error.message ?: error::class.java.name,
            )
            return complete("failed", EXIT_PREFLIGHT, error.stackTraceToString())
        }

        logger.info("Starting isolated runtime extraction. Supplied Mod bytecode will execute in a child JVM.")
        extractorResult = try {
            extractor.extract(
                RuntimeExtractorRequest(
                    modJar = request.modJar,
                    serverJar = request.serverJar,
                    snapshot = snapshotPath,
                    workDirectory = runtimeWork,
                    logFile = extractorLog,
                    commandLogFile = extractorCommandLog,
                    modId = request.modId,
                    timeout = request.runtimeTimeout,
                    allowModExecution = request.allowModExecution,
                ),
                logger,
            )
        } catch (error: Throwable) {
            logger.error("Could not launch or monitor runtime extractor: ${error.message}")
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.RUNTIME_EXTRACTION,
                RuntimePipelineStageStatus.FAILED,
                error.message ?: error::class.java.name,
                listOfNotNull(extractorLog.takeIf(Files::exists), extractorCommandLog.takeIf(Files::exists)),
            )
            return complete("failed", EXIT_EXTRACTION, error.stackTraceToString())
        }

        if (!extractorResult.succeeded || !Files.isRegularFile(snapshotPath)) {
            val summary = when {
                extractorResult.timedOut -> "Runtime extractor exceeded the CLI deadline."
                extractorResult.exitCode != 0 -> "Runtime extractor exited with code ${extractorResult.exitCode}."
                else -> "Runtime extractor reported success but did not write runtime-snapshot.json."
            }
            logger.error(summary)
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.RUNTIME_EXTRACTION,
                RuntimePipelineStageStatus.FAILED,
                summary,
                listOfNotNull(
                    snapshotPath.takeIf(Files::exists),
                    extractorLog.takeIf(Files::exists),
                    extractorCommandLog.takeIf(Files::exists),
                ),
                mapOf(
                    "exitCode" to (extractorResult.exitCode?.toString() ?: "none"),
                    "timedOut" to extractorResult.timedOut.toString(),
                ),
            )
            return complete("failed", EXIT_EXTRACTION)
        }

        snapshot = try {
            readSnapshotDescriptor(snapshotPath)
        } catch (error: Throwable) {
            logger.error("Runtime snapshot is invalid: ${error.message}")
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.RUNTIME_EXTRACTION,
                RuntimePipelineStageStatus.FAILED,
                "Extractor completed, but its retained snapshot could not be parsed: ${error.message}",
                listOf(snapshotPath, extractorLog, extractorCommandLog),
            )
            return complete("failed", EXIT_EXTRACTION, error.stackTraceToString())
        }
        stages += RuntimePipelineStageRecord(
            RuntimePipelineStage.RUNTIME_EXTRACTION,
            RuntimePipelineStageStatus.PASSED,
            "Captured ${snapshot.contentCount} runtime Content registrations from " +
                "${snapshot.targetMod ?: "the requested Mod"} (snapshot schema ${snapshot.schemaVersion}).",
            listOf(snapshotPath, extractorLog, extractorCommandLog, runtimeWork),
            mapOf(
                "snapshotSha256" to snapshot.sha256,
                "contentCount" to snapshot.contentCount.toString(),
                "gameType" to (snapshot.gameType ?: "unknown"),
                "gameModifier" to (snapshot.gameModifier ?: "unknown"),
                "gameBuild" to (snapshot.gameBuild?.toString() ?: "unknown"),
                "gameRevision" to (snapshot.gameRevision?.toString() ?: "unknown"),
                "durationMillis" to extractorResult.duration.toMillis().toString(),
            ),
        )

        if (
            !snapshot.gameType.equals("official", ignoreCase = true) ||
            !snapshot.gameModifier.equals("release", ignoreCase = true) ||
            snapshot.gameBuild != 159 || snapshot.gameRevision != 7
        ) {
            val summary = "The runtime mapper requires the official v159.7 release runtime, but extraction reported " +
                "type=${snapshot.gameType ?: "unknown"}, modifier=${snapshot.gameModifier ?: "unknown"}, " +
                "build=${snapshot.gameBuild ?: "unknown"}, revision=${snapshot.gameRevision ?: "unknown"}."
            logger.error(summary)
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.RUNTIME_TO_DP_MAPPING,
                RuntimePipelineStageStatus.FAILED,
                summary,
                listOf(snapshot.path),
                mapOf("requiredBuild" to "159", "requiredRevision" to "7"),
            )
            return complete("failed", EXIT_MAPPING)
        }

        if (request.source == null) {
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.SOURCE_INDEX,
                RuntimePipelineStageStatus.NOT_RUN,
                "No --source repository/archive was supplied; runtime JAR and registration traces remain authoritative.",
            )
        } else {
            sourceIndex = try {
                sourceIndexStage.index(request.modJar, request.source, sourceIndexPath, logger)
            } catch (error: Throwable) {
                logger.warn("Optional source indexing failed; runtime conversion will continue from the authoritative JAR: ${error.message}")
                val failureReport = output.resolve("logs/source-index-failure.txt")
                Files.writeString(
                    failureReport,
                    buildString {
                        appendLine("Optional source provenance indexing failed")
                        appendLine("Mod JAR: ${request.modJar}")
                        appendLine("Source: ${request.source}")
                        appendLine()
                        append(error.stackTraceToString())
                    },
                    StandardCharsets.UTF_8,
                )
                sourceIndexFailure = Diagnostic(
                    code = "RUNTIME_SOURCE_INDEX_FAILED",
                    severity = DiagnosticSeverity.WARNING,
                    message = "Optional source provenance indexing failed; runtime conversion continued from the release JAR.",
                    stage = ValidationStage.STRUCTURE,
                    location = SourceLocation(request.source.toString()),
                    details = error.message ?: error::class.java.name,
                    suggestion = "Inspect logs/source-index-failure.txt or rerun without --source.",
                )
                stages += RuntimePipelineStageRecord(
                    RuntimePipelineStage.SOURCE_INDEX,
                    RuntimePipelineStageStatus.FAILED,
                    "Optional provenance indexing failed; mapping continued from the unchanged release JAR. " +
                        (error.message ?: error::class.java.name),
                    listOfNotNull(sourceIndexPath.takeIf(Files::exists), failureReport),
                )
                null
            }
            sourceIndex?.let { indexed ->
                stages += RuntimePipelineStageRecord(
                    RuntimePipelineStage.SOURCE_INDEX,
                    RuntimePipelineStageStatus.PASSED,
                    "Linked ${indexed.matchedClassFiles}/${indexed.runtimeClassFiles} runtime classes and " +
                        "${indexed.exactAssetMatches}/${indexed.runtimeAssets} runtime assets to optional source.",
                    listOf(indexed.report),
                    mapOf("issueCount" to indexed.issueCount.toString()),
                )
            }
        }

        try {
            verifyInputFingerprints(request, requireNotNull(fingerprints), "before runtime mapping")
        } catch (error: Throwable) {
            logger.error("Runtime input integrity check failed: ${error.message}")
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.RUNTIME_TO_DP_MAPPING,
                RuntimePipelineStageStatus.FAILED,
                error.message ?: error::class.java.name,
            )
            return complete("failed", EXIT_MAPPING, error.stackTraceToString())
        }

        val mapping = try {
            mappingStage.map(
                RuntimeToDpMappingInput(
                    snapshot = snapshot,
                    modJar = request.modJar,
                    sourceIndexReport = sourceIndex?.report,
                    outputDirectory = output,
                ),
                logger,
            )
        } catch (error: Throwable) {
            logger.error("Runtime-to-DP mapping stage failed: ${error.message}")
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.RUNTIME_TO_DP_MAPPING,
                RuntimePipelineStageStatus.FAILED,
                error.message ?: error::class.java.name,
            )
            return complete("failed", EXIT_MAPPING, error.stackTraceToString())
        }
        stages += RuntimePipelineStageRecord(
            RuntimePipelineStage.RUNTIME_TO_DP_MAPPING,
            mapping.status,
            mapping.summary,
            listOfNotNull(
                mapping.mappingReport,
            ),
            mapping.metadata,
        )

        if (mapping.status == RuntimePipelineStageStatus.FAILED) {
            return complete("failed", EXIT_MAPPING)
        }
        val runtimePrepared = mapping.preparedConversion
        if (mapping.status != RuntimePipelineStageStatus.PASSED || runtimePrepared == null) {
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.PACKAGING,
                RuntimePipelineStageStatus.NOT_RUN,
                "The runtime snapshot was retained, but its schema/type set was not accepted by a mapper.",
            )
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.DP_VALIDATION,
                RuntimePipelineStageStatus.NOT_RUN,
                "No DP was produced, so v159.7 validation was not invoked.",
            )
            logger.warn("Runtime snapshot retained without a DP because the mapper did not accept it.")
            return complete("snapshotReady", EXIT_MAPPING)
        }

        var prepared = runtimePrepared
        when {
            request.source == null -> stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.HYBRID_SOURCE_SELECTION,
                RuntimePipelineStageStatus.NOT_RUN,
                "No --source input was supplied; the authoritative runtime-only mapping will be packaged.",
            )

            sourceIndex == null -> stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.HYBRID_SOURCE_SELECTION,
                RuntimePipelineStageStatus.NOT_RUN,
                "Optional source provenance was unavailable; source-AST candidates were not trusted or selected.",
                listOfNotNull(output.resolve("logs/source-index-failure.txt").takeIf(Files::exists)),
            )

            else -> {
                val report = output.resolve("hybrid-report.json")
                try {
                    val hybrid = RuntimeHybridSourceSelector(dataPatchApplyValidator).run(
                        RuntimeHybridSourceSelectionInput(
                            snapshot = snapshot,
                            modJar = request.modJar,
                            source = request.source,
                            sourceIndexReport = sourceIndex.report,
                            serverJar = request.serverJar,
                            outputDirectory = output,
                            runtimePrepared = runtimePrepared,
                            validationTimeout = request.serverValidationTimeout,
                            maxRounds = request.hybridMaxRounds,
                        ),
                        logger,
                    )
                    prepared = hybrid.preparedConversion
                    hybridReport = hybrid.report
                    stages += RuntimePipelineStageRecord(
                        RuntimePipelineStage.HYBRID_SOURCE_SELECTION,
                        hybrid.status,
                        hybrid.summary,
                        hybrid.artifacts,
                        hybrid.metadata,
                    )
                } catch (error: Throwable) {
                    logger.warn(
                        "Optional runtime-guided source candidate stage failed; continuing from the unchanged runtime-only base: " +
                            (error.message ?: error::class.java.name),
                    )
                    runCatching { writeHybridFailureReport(report, request.source, error) }
                    hybridReport = report.takeIf(Files::exists)
                    stages += RuntimePipelineStageRecord(
                        RuntimePipelineStage.HYBRID_SOURCE_SELECTION,
                        RuntimePipelineStageStatus.FAILED,
                        "Optional source candidates failed safely and were not included; runtime-only conversion continued. " +
                            (error.message ?: error::class.java.name),
                        listOfNotNull(hybridReport),
                        mapOf(
                            "fallback" to "runtime-only",
                            "failure" to (error.message ?: error::class.java.name),
                        ),
                    )
                }
            }
        }

        if (!prepared.hasPreparedContentDeclarations()) {
            val summary = if (request.source == null) {
                "The runtime mapper emitted no Item/Liquid/Status declaration and no --source input was supplied " +
                    "for runtime-confirmed Block/Unit reconstruction; no DP will be packaged."
            } else {
                "Neither the runtime mapper nor the optional hybrid source stage produced a content declaration; " +
                    "no DP will be packaged."
            }
            logger.error(summary)
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.PACKAGING,
                RuntimePipelineStageStatus.FAILED,
                summary,
                listOfNotNull(mapping.mappingReport, hybridReport),
                mapOf("preparedContentDeclarations" to "0"),
            )
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.DP_VALIDATION,
                RuntimePipelineStageStatus.NOT_RUN,
                "No DP was produced because the prepared conversion contained no content declaration.",
            )
            return complete("failed", EXIT_PACKAGING)
        }

        try {
            verifyInputFingerprints(request, requireNotNull(fingerprints), "after runtime mapping")
        } catch (error: Throwable) {
            logger.error("Runtime input integrity check failed: ${error.message}")
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.PACKAGING,
                RuntimePipelineStageStatus.FAILED,
                error.message ?: error::class.java.name,
                listOfNotNull(mapping.mappingReport),
            )
            return complete("failed", EXIT_PACKAGING, error.stackTraceToString())
        }

        val converted = try {
            BridgeConverter.convertRuntimePrepared(
                ConversionRequest(
                    input = request.modJar,
                    outputDirectory = output,
                    outputBaseName = request.modJar.fileName.toString().substringBeforeLast('.'),
                    overwrite = true,
                    limits = SecurityLimits(
                        maxInputBytes = 512L * 1024L * 1024L,
                        maxEntries = 100_000,
                        maxEntryBytes = 64L * 1024L * 1024L,
                        maxExpandedBytes = 512L * 1024L * 1024L,
                        maxCompressionRatio = 250.0,
                        maxPathLength = 1_024,
                    ),
                    logSink = logger::info,
                ),
                prepared,
            )
        } catch (error: ConversionException) {
            logger.error("Runtime-prepared packaging failed: ${error.message}")
            error.diagnostics.forEach { logger.diagnostic(it) }
            writeFailureFiles(output, request.modJar, error, logger)
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.PACKAGING,
                RuntimePipelineStageStatus.FAILED,
                error.message ?: "BridgeConverter rejected the runtime-prepared input.",
                listOfNotNull(
                    output.resolve("failure-report.txt").takeIf(Files::exists),
                    output.resolve("failure-diagnostics.json").takeIf(Files::exists),
                ),
            )
            return complete("failed", EXIT_PACKAGING, error.stackTraceToString())
        } catch (error: Throwable) {
            logger.error("Runtime-prepared packaging failed unexpectedly: ${error.message}")
            writeUnexpectedFailure(output, request.modJar, error)
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.PACKAGING,
                RuntimePipelineStageStatus.FAILED,
                error.message ?: error::class.java.name,
                listOf(output.resolve("failure-report.txt")),
            )
            return complete("failed", EXIT_PACKAGING, error.stackTraceToString())
        }
        dpZip = converted.dpZip
        conversionReport = converted.reportJson
        stages += RuntimePipelineStageRecord(
            RuntimePipelineStage.PACKAGING,
            RuntimePipelineStageStatus.PASSED,
            "BridgeConverter deterministically packaged ${converted.inventory.contents.size} content files and " +
                "${converted.inventory.assets.size} external assets.",
            listOf(converted.dpZip, converted.serverAssets, converted.reportJson, converted.reportMarkdown),
            mapOf(
                "dpZip" to converted.dpZip.toString(),
                "serverAssets" to converted.serverAssets.toString(),
                "contentFiles" to converted.inventory.contents.size.toString(),
                "assetFiles" to converted.inventory.assets.size.toString(),
            ),
        )

        val discoveryLog = output.resolve("logs/server-asset-discovery.log")
        val discoveryLines = mutableListOf<String>()
        val applyLog = output.resolve("logs/data-patch-apply.log")
        val applyLines = mutableListOf<String>()
        val validationDiagnostics = mutableListOf<Diagnostic>()
        val validationStageUpdates = mutableListOf<ValidationStageResult>()
        try {
            verifyInputFingerprints(request, requireNotNull(fingerprints), "before DP validation")

            val structural = structuralValidator.validate(converted.dpZip)
            validationDiagnostics += structural.report.diagnostics
            validationStageUpdates += structural.report.validationStages
            structural.report.diagnostics.forEach { logger.diagnostic(it) }

            val discovery = serverDiscoveryValidator.validate(
                converted.serverAssets,
                request.serverJar,
                request.serverValidationTimeout,
            ) { line ->
                discoveryLines += line
                logger.raw("[server-discovery] $line")
            }
            Files.write(discoveryLog, discoveryLines, StandardCharsets.UTF_8)
            validationDiagnostics += discovery.diagnostics
            discovery.diagnostics.forEach { logger.diagnostic(it) }
            validationStageUpdates += ValidationStageResult(
                ValidationStage.SERVER_LOAD,
                ValidationStatus.NOT_RUN,
                "Server cold start discovered the asset tree, but no user map/save carrying the DP was loaded.",
                discovery.diagnostics.map { it.code }.distinct(),
            )

            val apply = dataPatchApplyValidator.validate(
                converted.serverAssets,
                request.serverJar,
                request.serverValidationTimeout,
            ) { line ->
                applyLines += line
                logger.raw("[data-patch-apply] $line")
            }
            Files.write(applyLog, applyLines, StandardCharsets.UTF_8)
            validationDiagnostics += apply.diagnostics
            apply.diagnostics.forEach { logger.diagnostic(it) }
            val applyClean = apply.passed && apply.failedAssets == 0 && apply.warningCount == 0 &&
                apply.diagnostics.none {
                    it.severity == DiagnosticSeverity.WARNING || it.severity == DiagnosticSeverity.ERROR
                }
            validationStageUpdates += ValidationStageResult(
                ValidationStage.RUNTIME,
                if (applyClean) ValidationStatus.PASSED else ValidationStatus.FAILED,
                if (apply.applyCompleted) {
                    "v159.7 DataPatcher.apply completed: assets=${apply.totalAssets ?: "unknown"}, " +
                        "content=${apply.contentAssets ?: "unknown"}, added=${apply.addedContent ?: "unknown"}, " +
                        "failed=${apply.failedAssets ?: "unknown"}, warnings=${apply.warningCount ?: "unknown"}."
                } else {
                    "v159.7 DataPatcher.apply did not complete."
                },
                apply.diagnostics.map { it.code }.distinct(),
            )

            val preparedReportClean = !converted.report.hasPreparedConversionFailure()
            val structuralClean = structural.status != ConversionStatus.REJECTED &&
                structural.status != ConversionStatus.FAILED
            val validationPassed = preparedReportClean && structuralClean && discovery.passed && applyClean
            val combinedDiagnostics = (
                converted.report.diagnostics + listOfNotNull(sourceIndexFailure) + validationDiagnostics
                ).distinctBy { Triple(it.code, it.location?.path, it.message) }
            var finalReport = converted.report.copy(
                status = if (validationPassed) converted.report.status else ConversionStatus.REJECTED,
                diagnostics = combinedDiagnostics,
                validationStages = mergeStages(converted.report.validationStages, validationStageUpdates),
                metadata = converted.report.metadata + mapOf(
                    "serverJar" to request.serverJar.toString(),
                    "serverJarSha256" to requireNotNull(fingerprints).serverJarSha256,
                    "preparedConversionReport" to if (preparedReportClean) "passed" else "failed",
                    "serverAssetDiscovery" to if (discovery.passed) "passed" else "failed",
                    "serverDiscoveredAssetFiles" to (discovery.discoveredAssetFiles?.toString() ?: "unknown"),
                    "serverAssetDiscoveryExitCode" to (discovery.exitCode?.toString() ?: "unknown"),
                    "serverAssetDiscoveryLog" to discoveryLog.toString(),
                    "dataPatchApply" to when {
                        applyClean -> "passed"
                        apply.applyCompleted -> "failed"
                        else -> "notCompleted"
                    },
                    "dataPatchApplyExitCode" to (apply.exitCode?.toString() ?: "unknown"),
                    "dataPatchApplyTotalAssets" to (apply.totalAssets?.toString() ?: "unknown"),
                    "dataPatchApplyContentAssets" to (apply.contentAssets?.toString() ?: "unknown"),
                    "dataPatchApplyFailedAssets" to (apply.failedAssets?.toString() ?: "unknown"),
                    "dataPatchApplyWarnings" to (apply.warningCount?.toString() ?: "unknown"),
                    "dataPatchApplyAddedContent" to (apply.addedContent?.toString() ?: "unknown"),
                    "dataPatchApplyLog" to applyLog.toString(),
                    "serverLoadValidation" to "notRun:no-map-or-save-loaded",
                ),
            )
            finalReport = finalReport.copy(summary = finalReport.summary.withDiagnosticCounts(finalReport.diagnostics))
            Files.writeString(converted.reportJson, ConversionReportJson.encode(finalReport) + "\n", StandardCharsets.UTF_8)
            Files.writeString(converted.reportMarkdown, finalReport.toMarkdown(), StandardCharsets.UTF_8)

            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.DP_VALIDATION,
                if (validationPassed) RuntimePipelineStageStatus.PASSED else RuntimePipelineStageStatus.FAILED,
                "PreparedReport=${if (preparedReportClean) "passed" else "failed"}, " +
                    "Structural=${if (structuralClean) "passed" else "failed"}, " +
                    "serverDiscovery=${if (discovery.passed) "passed" else "failed"}, " +
                    "DataPatcherApply=${if (applyClean) "passed" else "failed"}.",
                listOf(discoveryLog, applyLog, converted.reportJson, converted.reportMarkdown),
                mapOf(
                    "preparedReport" to if (preparedReportClean) "passed" else "failed",
                    "discoveredAssets" to (discovery.discoveredAssetFiles?.toString() ?: "unknown"),
                    "applyContentAssets" to (apply.contentAssets?.toString() ?: "unknown"),
                    "applyAddedContent" to (apply.addedContent?.toString() ?: "unknown"),
                    "applyFailedAssets" to (apply.failedAssets?.toString() ?: "unknown"),
                    "applyWarnings" to (apply.warningCount?.toString() ?: "unknown"),
                ),
            )

            logger.info("Runtime snapshot: $snapshotPath")
            logger.info("Runtime mapping report: ${mapping.mappingReport}")
            logger.info("DP ZIP: ${converted.dpZip}")
            logger.info("Conversion report: ${converted.reportJson}")
            logger.warn("Map-editor import and a real map/save server load still require user testing.")
            return complete(
                if (validationPassed) "completed" else "validationFailed",
                if (validationPassed) EXIT_SUCCESS else EXIT_VALIDATION,
            )
        } catch (error: Throwable) {
            runCatching { Files.write(discoveryLog, discoveryLines, StandardCharsets.UTF_8) }
            runCatching { Files.write(applyLog, applyLines, StandardCharsets.UTF_8) }
            val diagnostic = Diagnostic(
                code = "RUNTIME_DP_VALIDATION_EXCEPTION",
                severity = DiagnosticSeverity.ERROR,
                message = "Post-packaging v159.7 validation terminated unexpectedly: " +
                    (error.message ?: error::class.java.name),
                stage = ValidationStage.RUNTIME,
                location = SourceLocation(converted.dpZip.toString()),
                details = error.stackTraceToString(),
                suggestion = "Inspect the validation logs and verify the pinned official Server JAR and local Java runtime.",
            )
            logger.diagnostic(diagnostic)
            val combinedDiagnostics = (
                converted.report.diagnostics + listOfNotNull(sourceIndexFailure) + validationDiagnostics + diagnostic
                ).distinctBy { Triple(it.code, it.location?.path, it.message) }
            var rejectedReport = converted.report.copy(
                status = ConversionStatus.REJECTED,
                diagnostics = combinedDiagnostics,
                validationStages = mergeStages(
                    converted.report.validationStages,
                    validationStageUpdates + ValidationStageResult(
                        ValidationStage.RUNTIME,
                        ValidationStatus.FAILED,
                        "Post-packaging validation raised an exception; generated artifacts must not be treated as validated.",
                        listOf(diagnostic.code),
                    ),
                ),
                metadata = converted.report.metadata + mapOf(
                    "serverJar" to request.serverJar.toString(),
                    "serverJarSha256" to requireNotNull(fingerprints).serverJarSha256,
                    "runtimeValidation" to "exception",
                    "runtimeValidationException" to (error.message ?: error::class.java.name),
                    "serverAssetDiscoveryLog" to discoveryLog.toString(),
                    "dataPatchApplyLog" to applyLog.toString(),
                    "serverLoadValidation" to "notRun:validation-exception",
                ),
            )
            rejectedReport = rejectedReport.copy(
                summary = rejectedReport.summary.withDiagnosticCounts(rejectedReport.diagnostics),
            )
            Files.writeString(converted.reportJson, ConversionReportJson.encode(rejectedReport) + "\n", StandardCharsets.UTF_8)
            Files.writeString(converted.reportMarkdown, rejectedReport.toMarkdown(), StandardCharsets.UTF_8)
            val failure = ConversionException(
                message = diagnostic.message,
                diagnostics = listOf(diagnostic),
                cause = error,
            )
            writeFailureFiles(output, request.modJar, failure, logger)
            stages += RuntimePipelineStageRecord(
                RuntimePipelineStage.DP_VALIDATION,
                RuntimePipelineStageStatus.FAILED,
                diagnostic.message,
                listOfNotNull(
                    discoveryLog.takeIf(Files::exists),
                    applyLog.takeIf(Files::exists),
                    converted.reportJson.takeIf(Files::exists),
                    converted.reportMarkdown.takeIf(Files::exists),
                    output.resolve("failure-report.txt").takeIf(Files::exists),
                    output.resolve("failure-diagnostics.json").takeIf(Files::exists),
                ),
                mapOf("diagnosticCode" to diagnostic.code),
            )
            return complete("validationFailed", EXIT_VALIDATION, error.stackTraceToString())
        }
    }

    private fun RuntimePreparedConversion.hasPreparedContentDeclarations(): Boolean = files.any { file ->
        file.outputPath.replace('\\', '/').trimStart('/').startsWith("content/", ignoreCase = true)
    }

    private fun ConversionReport.hasPreparedConversionFailure(): Boolean =
        status == ConversionStatus.REJECTED ||
            status == ConversionStatus.FAILED ||
            summary.failedContents > 0 ||
            diagnostics.any { it.severity == DiagnosticSeverity.ERROR } ||
            validationStages.any {
                it.stage == ValidationStage.STRUCTURE && it.status == ValidationStatus.FAILED
            }

    private fun preflight(request: RuntimeConversionPipelineRequest): RuntimeInputFingerprints {
        require(request.allowModExecution) {
            "Runtime execution was not acknowledged; pass --allow-mod-execution only when both the Mod and Server JAR are trusted."
        }
        require(Files.isRegularFile(request.modJar)) { "Mod JAR is not a regular file: ${request.modJar}" }
        require(Files.isRegularFile(request.serverJar)) { "Server JAR is not a regular file: ${request.serverJar}" }
        require(request.modJar.fileName.toString().endsWith(".jar", ignoreCase = true)) {
            "--mod-jar must point to the built Mod JAR, not a source ZIP: ${request.modJar}"
        }
        request.source?.let { source ->
            require(Files.exists(source)) { "Optional source input does not exist: $source" }
            require(Files.isDirectory(source) || Files.isRegularFile(source)) {
                "Optional source input must be a directory or archive: $source"
            }
        }
        require(!request.runtimeTimeout.isZero && !request.runtimeTimeout.isNegative) {
            "--runtime-timeout must be positive."
        }
        require(!request.serverValidationTimeout.isZero && !request.serverValidationTimeout.isNegative) {
            "--server-timeout must be positive."
        }
        require(request.hybridMaxRounds > 0) { "--hybrid-max-rounds must be positive." }
        val fingerprints = RuntimeInputFingerprints(
            modJarSha256 = sha256(request.modJar),
            serverJarSha256 = sha256(request.serverJar),
        )
        serverJarPolicy.validate(request.serverJar, fingerprints.serverJarSha256)
        return fingerprints
    }

    private fun verifyInputFingerprints(
        request: RuntimeConversionPipelineRequest,
        expected: RuntimeInputFingerprints,
        checkpoint: String,
    ) {
        val currentMod = sha256(request.modJar)
        require(currentMod == expected.modJarSha256) {
            "Mod JAR changed after preflight ($checkpoint): expected ${expected.modJarSha256}, found $currentMod."
        }
        val currentServer = sha256(request.serverJar)
        require(currentServer == expected.serverJarSha256) {
            "Server JAR changed after preflight ($checkpoint): expected ${expected.serverJarSha256}, found $currentServer."
        }
    }

    private fun readSnapshotDescriptor(path: Path): RuntimeSnapshotDescriptor {
        val root = REPORT_JSON.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)).jsonObject
        val schemaVersion = root.requiredInt("schemaVersion")
        val contentCount = root.requiredInt("contentCount")
        require(schemaVersion > 0) { "schemaVersion must be positive." }
        require(contentCount >= 0) { "contentCount must not be negative." }
        val gameVersion = root["gameVersion"] as? JsonObject
        return RuntimeSnapshotDescriptor(
            path = path.toAbsolutePath().normalize(),
            sha256 = sha256(path),
            schemaVersion = schemaVersion,
            targetMod = root["targetMod"]?.jsonPrimitive?.contentOrNull,
            contentCount = contentCount,
            gameType = gameVersion?.get("type")?.jsonPrimitive?.contentOrNull,
            gameModifier = gameVersion?.get("modifier")?.jsonPrimitive?.contentOrNull,
            gameBuild = gameVersion?.get("build")?.jsonPrimitive?.intOrNull,
            gameRevision = gameVersion?.get("revision")?.jsonPrimitive?.intOrNull,
        )
    }

    private fun JsonObject.requiredInt(name: String): Int =
        this[name]?.jsonPrimitive?.intOrNull ?: throw IllegalArgumentException("Snapshot has no integer '$name' field.")

    private fun appendNotRunStages(stages: MutableList<RuntimePipelineStageRecord>) {
        RuntimePipelineStage.entries.forEach { stage ->
            if (stages.none { it.stage == stage }) {
                stages += RuntimePipelineStageRecord(
                    stage,
                    RuntimePipelineStageStatus.NOT_RUN,
                    "Not reached because an earlier runtime pipeline stage did not complete.",
                )
            }
        }
    }

    private fun writePipelineReport(
        report: Path,
        status: String,
        request: RuntimeConversionPipelineRequest,
        stages: List<RuntimePipelineStageRecord>,
        snapshot: RuntimeSnapshotDescriptor?,
        sourceIndex: RuntimeSourceIndexResult?,
        extractorResult: RuntimeExtractorResult?,
        extractorLog: Path,
        extractorCommandLog: Path,
        runtimeWork: Path,
        failure: String?,
    ) {
        val root = buildJsonObject {
            put("schemaVersion", 1)
            put("mode", "runtime-convert")
            put("status", status)
            put("generatedAt", Instant.now().toString())
            put("request", buildJsonObject {
                put("modJar", request.modJar.toString())
                put("serverJar", request.serverJar.toString())
                putNullable("source", request.source?.toString())
                putNullable("modId", request.modId)
                put("outputDirectory", request.outputDirectory.toString())
                put("runtimeTimeoutSeconds", request.runtimeTimeout.seconds)
                put("serverValidationTimeoutSeconds", request.serverValidationTimeout.seconds)
                put("hybridMaxRounds", request.hybridMaxRounds)
                put("allowModExecution", request.allowModExecution)
            })
            put("runtimeSnapshot", snapshot?.let { value ->
                buildJsonObject {
                    put("path", value.path.toString())
                    put("sha256", value.sha256)
                    put("schemaVersion", value.schemaVersion)
                    putNullable("targetMod", value.targetMod)
                    put("contentCount", value.contentCount)
                    putNullable("gameType", value.gameType)
                    putNullable("gameModifier", value.gameModifier)
                    putNullable("gameBuild", value.gameBuild)
                    putNullable("gameRevision", value.gameRevision)
                }
            } ?: JsonNull)
            put("sourceIndex", sourceIndex?.let { value ->
                buildJsonObject {
                    put("path", value.report.toString())
                    put("runtimeClassFiles", value.runtimeClassFiles)
                    put("matchedClassFiles", value.matchedClassFiles)
                    put("runtimeAssets", value.runtimeAssets)
                    put("exactAssetMatches", value.exactAssetMatches)
                    put("issueCount", value.issueCount)
                }
            } ?: JsonNull)
            put("extractor", buildJsonObject {
                putNullable("exitCode", extractorResult?.exitCode)
                put("timedOut", extractorResult?.timedOut ?: false)
                putNullable("durationMillis", extractorResult?.duration?.toMillis())
                put("log", extractorLog.toString())
                put("commandLog", extractorCommandLog.toString())
                put("workDirectory", runtimeWork.toString())
            })
            put("stages", buildJsonArray {
                stages.forEach { stage ->
                    add(buildJsonObject {
                        put("stage", stage.stage.wireName)
                        put("status", stage.status.wireName)
                        put("summary", stage.summary)
                        put("artifacts", buildJsonArray {
                            stage.artifacts.forEach { artifact -> add(JsonPrimitive(artifact.toString())) }
                        })
                        put("metadata", buildJsonObject {
                            stage.metadata.toSortedMap().forEach { (key, value) -> put(key, value) }
                        })
                    })
                }
            })
            putNullable("failure", failure)
        }
        report.parent?.let(Files::createDirectories)
        Files.writeString(
            report,
            REPORT_JSON.encodeToString(JsonElement.serializer(), root) + "\n",
            StandardCharsets.UTF_8,
        )
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: String?) {
        put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: Int?) {
        put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: Long?) {
        put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private companion object {
        const val EXIT_SUCCESS = 0
        const val EXIT_PREFLIGHT = 2
        const val EXIT_EXTRACTION = 3
        const val EXIT_SOURCE_INDEX = 4
        const val EXIT_MAPPING = 5
        const val EXIT_PACKAGING = 6
        const val EXIT_VALIDATION = 7

        val REPORT_JSON = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
        }
    }
}
