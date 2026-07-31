package io.github.moddpbridge.cli

import io.github.moddpbridge.converter.BridgeConverter
import io.github.moddpbridge.converter.ConversionException
import io.github.moddpbridge.converter.ConversionRequest
import io.github.moddpbridge.converter.RuntimePreparedConversion
import io.github.moddpbridge.converter.SecurityLimits
import io.github.moddpbridge.javastatic.hybrid.HybridCandidateDecision
import io.github.moddpbridge.javastatic.hybrid.HybridCandidateDisposition
import io.github.moddpbridge.javastatic.hybrid.HybridCandidateOptions
import io.github.moddpbridge.javastatic.hybrid.HybridValidationState
import io.github.moddpbridge.javastatic.hybrid.RuntimeStaticHybrid
import io.github.moddpbridge.javastatic.hybrid.RuntimeStaticHybridRequest
import io.github.moddpbridge.model.Diagnostic
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class RuntimeHybridSourceSelectionInput(
    val snapshot: RuntimeSnapshotDescriptor,
    val modJar: Path,
    val source: Path,
    val sourceIndexReport: Path,
    val serverJar: Path,
    val outputDirectory: Path,
    val runtimePrepared: RuntimePreparedConversion,
    val validationTimeout: Duration,
    val maxRounds: Int,
)

internal data class RuntimeHybridSourceSelectionResult(
    val preparedConversion: RuntimePreparedConversion,
    val status: RuntimePipelineStageStatus,
    val summary: String,
    val report: Path,
    val artifacts: List<Path>,
    val metadata: Map<String, String>,
)

/**
 * Adds optional source-AST Block/Unit candidates to an already authoritative runtime mapping, then
 * filters them with the pinned official DataPatcher harness. Source code is parsed only; it is
 * never compiled, built or executed.
 */
internal class RuntimeHybridSourceSelector(
    private val applyValidator: RuntimeDataPatchApplyRunner,
) {
    fun run(
        input: RuntimeHybridSourceSelectionInput,
        logger: BridgeLogger,
    ): RuntimeHybridSourceSelectionResult {
        require(input.maxRounds > 0) { "hybrid max rounds must be positive." }
        val output = input.outputDirectory.toAbsolutePath().normalize()
        val report = output.resolve("hybrid-report.json")
        val workspace = output.resolve("logs/hybrid-selection")
        Files.createDirectories(workspace)

        logger.info("Preparing runtime-guided Java Block/Unit candidates from ${input.source}.")
        val candidates = RuntimeStaticHybrid.prepare(
            RuntimeStaticHybridRequest(
                snapshot = input.snapshot.path,
                source = input.source,
                sourceIndexReport = input.sourceIndexReport,
                runtimePrepared = input.runtimePrepared,
                options = HybridCandidateOptions(replaceOfficialUnitFallbackTemplates = true),
            ),
        )
        candidates.logs.forEach { logger.info("[hybrid] $it") }

        if (candidates.candidatePaths.isEmpty()) {
            val prepared = candidates.materialize(emptySet(), HybridValidationState.UNVALIDATED)
            val summary = "Runtime/source matching produced no DataPatcher-eligible Block/Unit candidate; " +
                "${candidates.rejected.size} source declaration(s) were retained as explicit rejections."
            writeHybridReport(report, candidates, selection = null, status = "noCandidates", summary = summary)
            logger.warn(summary)
            return RuntimeHybridSourceSelectionResult(
                preparedConversion = prepared,
                status = RuntimePipelineStageStatus.PASSED,
                summary = summary,
                report = report,
                artifacts = listOf(report),
                metadata = discoveryMetadata(candidates) + mapOf(
                    "selectionStatus" to "noCandidates",
                    "selectedCandidates" to "0",
                ),
            )
        }

        val materializer = DataPatchCandidateMaterializer { selectedPaths, attemptDirectory ->
            val selected = candidates.materialize(
                selectedPaths,
                validation = HybridValidationState.UNVALIDATED,
            )
            // Parser/apply trials need declarations and preserved JAR patches, not thousands of
            // external images/audio files. The final package still uses the complete JAR-authority
            // prepared conversion and receives a second full validation below.
            val contentOnly = selected.copy(
                files = selected.files.filter { it.outputPath.isContentPath() },
                metadata = selected.metadata + mapOf("hybridTrialExternalAssets" to "omitted"),
            )
            val attemptName = attemptDirectory.fileName?.toString() ?: "hybrid-attempt"
            try {
                BridgeConverter.convertRuntimePrepared(
                    ConversionRequest(
                        input = input.modJar,
                        outputDirectory = attemptDirectory,
                        outputBaseName = attemptName,
                        overwrite = true,
                        limits = runtimeConversionSecurityLimits(),
                        logSink = { line -> logger.raw("[hybrid-package] $line") },
                    ),
                    contentOnly,
                ).serverAssets
            } catch (error: ConversionException) {
                if (contentOnly.files.isEmpty() && error.diagnostics.any { it.code == "NO_SUPPORTED_ASSETS" }) {
                    // A Block/Unit-only Mod has a legitimately empty runtime declaration baseline.
                    // DataPatcher can validate an empty asset root before candidate declarations are
                    // added; the final pipeline still refuses to package an empty conversion.
                    attemptDirectory.resolve("server-assets").also { Files.createDirectories(it) }
                } else {
                    throw error
                }
            }
        }
        val validator = DataPatchCandidateValidator { serverAssets, logSink ->
            applyValidator.validate(
                serverAssets,
                input.serverJar,
                input.validationTimeout,
                logSink,
            )
        }
        val selection = MonotonicDataPatchCandidateSelector(materializer, validator).select(
            MonotonicCandidateSelectionRequest(
                candidatePaths = candidates.candidatePaths,
                workspace = workspace,
                maxRounds = minOf(input.maxRounds, candidates.candidatePaths.size + 1),
            ),
            logger,
        )
        val decisions = selection.toHybridDecisions()
        val clean = selection.status == MonotonicCandidateSelectionStatus.DATA_PATCHER_CLEAN
        val accepted = if (clean) selection.acceptedPaths.toSet() else emptySet()
        val prepared = candidates.materialize(
            acceptedPaths = accepted,
            validation = if (clean) HybridValidationState.DATA_PATCHER_CLEAN else HybridValidationState.UNVALIDATED,
            decisions = decisions,
        )
        val summary = when (selection.status) {
            MonotonicCandidateSelectionStatus.DATA_PATCHER_CLEAN ->
                "Official DataPatcher filtering accepted ${selection.acceptedPaths.size}/${candidates.candidates.size} " +
                    "runtime-guided candidate(s); rejected=${selection.rejectedPaths.size}, warnings/failures=0 in the final candidate set."

            MonotonicCandidateSelectionStatus.BASELINE_INVALID ->
                "The runtime-only base was not strictly clean; all ${candidates.candidates.size} optional source candidates were withheld."

            MonotonicCandidateSelectionStatus.FALLBACK_TO_RUNTIME_BASE ->
                "Candidate attribution did not converge safely; the pipeline fell back to the proven runtime-only base and withheld " +
                    "${selection.unresolvedPaths.size} unresolved candidate(s)."
        }
        writeHybridReport(
            report = report,
            candidates = candidates,
            selection = selection,
            status = selection.status.name.lowercase(Locale.ROOT),
            summary = summary,
        )
        if (clean) logger.info(summary) else logger.warn(summary)
        return RuntimeHybridSourceSelectionResult(
            preparedConversion = prepared,
            status = if (clean) RuntimePipelineStageStatus.PASSED else RuntimePipelineStageStatus.FAILED,
            summary = summary,
            report = report,
            artifacts = listOf(report) + selection.rounds.map { it.logFile }.filter(Files::exists),
            metadata = discoveryMetadata(candidates) + mapOf(
                "selectionStatus" to selection.status.name.lowercase(Locale.ROOT),
                "selectedCandidates" to accepted.size.toString(),
                "dataPatcherRejectedCandidates" to selection.rejectedPaths.size.toString(),
                "unresolvedCandidates" to selection.unresolvedPaths.size.toString(),
                "selectionRounds" to selection.rounds.size.toString(),
            ),
        )
    }

    private fun MonotonicCandidateSelectionResult.toHybridDecisions(): Map<String, HybridCandidateDecision> {
        val result = linkedMapOf<String, HybridCandidateDecision>()
        rejectedPaths.sorted().forEach { path ->
            val reason = rejectedReasons[path] ?: "data-patcher-failure"
            result[path] = when (reason) {
                "data-patcher-warning" -> HybridCandidateDecision(
                    HybridCandidateDisposition.REJECTED_WARNING,
                    "The official v159.7 DataPatcher emitted a warning while applying this candidate.",
                    listOf("DATA_PATCH_APPLY_WARNING"),
                )

                "dependency-closure" -> HybridCandidateDecision(
                    HybridCandidateDisposition.REJECTED_DEPENDENCY_CLOSURE,
                    "This candidate stopped applying cleanly after an earlier rejected dependency was removed.",
                    listOf("HYBRID_DEPENDENCY_CLOSURE_REJECTED"),
                )

                else -> HybridCandidateDecision(
                    HybridCandidateDisposition.REJECTED_FAILURE,
                    "The official v159.7 DataPatcher rejected or could not read this candidate.",
                    listOf("HYBRID_DATA_PATCHER_FAILURE"),
                )
            }
        }
        unresolvedPaths.sorted().forEach { path ->
            result[path] = HybridCandidateDecision(
                HybridCandidateDisposition.REJECTED_UNRESOLVED_REFERENCE,
                "The candidate was withheld because the automatic DataPatcher filter could not attribute or converge safely.",
                listOf("HYBRID_SELECTION_UNRESOLVED"),
            )
        }
        return result
    }
}

internal fun writeHybridFailureReport(
    report: Path,
    source: Path,
    failure: Throwable,
) {
    report.parent?.let(Files::createDirectories)
    val root = buildJsonObject {
        put("schemaVersion", 1)
        put("status", "failedBeforeSelection")
        put("generatedAt", Instant.now().toString())
        put("source", source.toString())
        put("failure", failure.message ?: failure::class.java.name)
        put("details", failure.stackTraceToString())
        put("fallback", "runtime-only")
    }
    Files.writeString(report, HYBRID_JSON.encodeToString(root) + "\n", StandardCharsets.UTF_8)
}

private fun discoveryMetadata(candidates: io.github.moddpbridge.javastatic.hybrid.HybridCandidateSet): Map<String, String> =
    mapOf(
        "runtimeEligibleContents" to candidates.summary.runtimeEligibleContents.toString(),
        "staticGeneratedContents" to candidates.summary.staticGeneratedContents.toString(),
        "candidateCount" to candidates.candidates.size.toString(),
        "discoveryRejectedCount" to candidates.rejected.size.toString(),
        "blockCandidates" to candidates.summary.blockCandidates.toString(),
        "unitCandidates" to candidates.summary.unitCandidates.toString(),
    )

private fun writeHybridReport(
    report: Path,
    candidates: io.github.moddpbridge.javastatic.hybrid.HybridCandidateSet,
    selection: MonotonicCandidateSelectionResult?,
    status: String,
    summary: String,
) {
    report.parent?.let(Files::createDirectories)
    val root = buildJsonObject {
        put("schemaVersion", 1)
        put("status", status)
        put("generatedAt", Instant.now().toString())
        put("summary", summary)
        put("discovery", buildJsonObject {
            discoveryMetadata(candidates).forEach { (key, value) -> put(key, value.toInt()) }
        })
        put("candidates", buildJsonArray {
            candidates.candidates.sortedBy { it.outputPath }.forEach { candidate ->
                add(buildJsonObject {
                    put("outputPath", candidate.outputPath)
                    put("runtimeName", candidate.runtimeName)
                    put("kind", candidate.kind.name.lowercase(Locale.ROOT))
                    put("fallbackType", candidate.fallbackType)
                    putNullable("staticSourceType", candidate.staticSourceType)
                    put("staticTargetType", candidate.staticTargetType)
                    put("staticDisposition", candidate.staticDisposition.name.lowercase(Locale.ROOT))
                    put("unitTemplateOverridden", candidate.unitTemplateOverridden)
                    put("sourcePaths", buildJsonArray { candidate.sourcePaths.forEach { add(JsonPrimitive(it)) } })
                    put("jarProvenancePaths", buildJsonArray {
                        candidate.jarProvenancePaths.forEach { add(JsonPrimitive(it)) }
                    })
                    put("staticDiagnosticCodes", buildJsonArray {
                        candidate.staticDiagnosticCodes.forEach { add(JsonPrimitive(it)) }
                    })
                })
            }
        })
        put("rejectedAtDiscovery", buildJsonArray {
            candidates.rejected.forEach { rejected ->
                add(buildJsonObject {
                    putNullable("outputPath", rejected.outputPath)
                    put("sourceSymbol", rejected.sourceSymbol)
                    putNullable("runtimeName", rejected.runtimeName)
                    putNullable("kind", rejected.kind?.name?.lowercase(Locale.ROOT))
                    put("reason", rejected.reason)
                    put("diagnosticCodes", buildJsonArray {
                        rejected.diagnosticCodes.forEach { add(JsonPrimitive(it)) }
                    })
                })
            }
        })
        put("selection", selection?.let { selected ->
            buildJsonObject {
                put("status", selected.status.name.lowercase(Locale.ROOT))
                putNullable("reason", selected.reason)
                put("acceptedPaths", stringArray(selected.acceptedPaths))
                put("rejectedPaths", stringArray(selected.rejectedPaths))
                put("unresolvedPaths", stringArray(selected.unresolvedPaths))
                put("rejectedReasons", stringMap(selected.rejectedReasons))
                put("unresolvedReasons", stringMap(selected.unresolvedReasons))
                put("unattributedDiagnostics", diagnosticArray(selected.unattributedDiagnostics))
                put("rounds", buildJsonArray {
                    selected.rounds.forEach { round ->
                        add(buildJsonObject {
                            put("index", round.index)
                            put("kind", round.kind.name.lowercase(Locale.ROOT))
                            put("decision", round.decision.name.lowercase(Locale.ROOT))
                            put("testedPaths", stringArray(round.testedPaths))
                            put("removedPaths", stringArray(round.removedPaths))
                            put("logFile", round.logFile.toString())
                            putNullable("failure", round.failure)
                            put("apply", round.applyResult?.let { apply ->
                                buildJsonObject {
                                    put("applyCompleted", apply.applyCompleted)
                                    put("passed", apply.passed)
                                    putNullable("exitCode", apply.exitCode)
                                    put("timedOut", apply.timedOut)
                                    putNullable("contentAssets", apply.contentAssets)
                                    putNullable("failedAssets", apply.failedAssets)
                                    putNullable("warningCount", apply.warningCount)
                                    putNullable("addedContent", apply.addedContent)
                                    put("diagnostics", diagnosticArray(apply.diagnostics))
                                }
                            } ?: JsonNull)
                        })
                    }
                })
            }
        } ?: JsonNull)
    }
    Files.writeString(report, HYBRID_JSON.encodeToString(root) + "\n", StandardCharsets.UTF_8)
}

private fun diagnosticArray(diagnostics: List<Diagnostic>) = buildJsonArray {
    diagnostics.forEach { diagnostic ->
        add(buildJsonObject {
            put("code", diagnostic.code)
            put("severity", diagnostic.severity.name.lowercase(Locale.ROOT))
            put("message", diagnostic.message)
            putNullable("stage", diagnostic.stage?.name?.lowercase(Locale.ROOT))
            putNullable("path", diagnostic.location?.path)
            putNullable("jsonPath", diagnostic.location?.jsonPath)
            putNullable("details", diagnostic.details)
            putNullable("suggestion", diagnostic.suggestion)
        })
    }
}

private fun stringArray(values: Collection<String>) = buildJsonArray {
    values.sorted().forEach { add(JsonPrimitive(it)) }
}

private fun stringMap(values: Map<String, String>) = buildJsonObject {
    values.toSortedMap().forEach { (key, value) -> put(key, value) }
}

private fun String.isContentPath(): Boolean =
    replace('\\', '/').trim('/').startsWith("content/", ignoreCase = true)

private fun runtimeConversionSecurityLimits(): SecurityLimits = SecurityLimits(
    maxInputBytes = 512L * 1024L * 1024L,
    maxEntries = 100_000,
    maxEntryBytes = 64L * 1024L * 1024L,
    maxExpandedBytes = 512L * 1024L * 1024L,
    maxCompressionRatio = 250.0,
    maxPathLength = 1_024,
)

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: String?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: Int?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private val HYBRID_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    explicitNulls = true
}
