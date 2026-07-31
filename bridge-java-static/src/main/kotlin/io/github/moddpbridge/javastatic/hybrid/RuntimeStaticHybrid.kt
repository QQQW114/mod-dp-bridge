package io.github.moddpbridge.javastatic.hybrid

import io.github.moddpbridge.converter.ConvertedFileStatus
import io.github.moddpbridge.converter.RuntimePreparedContentResult
import io.github.moddpbridge.converter.RuntimePreparedConversion
import io.github.moddpbridge.converter.RuntimePreparedFile
import io.github.moddpbridge.converter.RuntimePreparedOutputNamespace
import io.github.moddpbridge.converter.StaticExportResult
import io.github.moddpbridge.converter.StaticGeneratedFile
import io.github.moddpbridge.model.ContentDisposition
import io.github.moddpbridge.model.ContentKind
import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One JAR class linked to a source file without loading or executing that class. */
data class HybridSourceClassProvenance(
    val sourcePath: String,
    val jarPath: String,
    val sourceCoversRuntimeLines: Boolean? = null,
    val runtimeLineNumbers: List<Int> = emptyList(),
)

/** Minimal runtime identity used to constrain source-derived candidates. */
data class HybridRuntimeContent(
    val fullName: String,
    val localName: String,
    val kind: ContentKind,
    val fallbackType: String,
    val snapshotLocation: SourceLocation,
)

enum class HybridValidationState {
    /** Candidate has not yet passed the official DataPatcher harness. */
    UNVALIDATED,

    /** Caller has observed zero warnings and zero failures for this candidate set. */
    DATA_PATCHER_CLEAN,
}

data class HybridCandidate(
    /** Stable, case-insensitive candidate identifier. */
    val id: String,
    val outputPath: String,
    val runtimeName: String,
    val localName: String,
    val kind: ContentKind,
    val fallbackType: String,
    val staticSourceType: String?,
    val staticTargetType: String,
    val sourceLocation: SourceLocation?,
    val sourcePaths: List<String>,
    val jarProvenancePaths: List<String>,
    val bytes: ByteArray,
    val staticDisposition: ContentDisposition,
    val staticReason: String?,
    val staticDiagnosticCodes: List<String>,
    val staticDiagnostics: List<Diagnostic>,
    val unitTemplateOverridden: Boolean = false,
)

data class HybridRejectedCandidate(
    val outputPath: String?,
    val sourceSymbol: String,
    val kind: ContentKind?,
    val reason: String,
    val diagnosticCodes: List<String>,
    val location: SourceLocation? = null,
    /** Set only when this rejected declaration matched a real runtime registration. */
    val runtimeName: String? = null,
)

enum class HybridCandidateDisposition {
    PENDING,
    REJECTED_WARNING,
    REJECTED_FAILURE,
    REJECTED_DEPENDENCY_CLOSURE,
    REJECTED_UNRESOLVED_REFERENCE,
    REJECTED_MANUAL,
}

/** Exact per-candidate outcome supplied by the official DataPatcher selection stage. */
data class HybridCandidateDecision(
    val disposition: HybridCandidateDisposition,
    val reason: String,
    val diagnosticCodes: List<String> = emptyList(),
)

data class HybridCandidateSummary(
    val runtimeEligibleContents: Int,
    val staticGeneratedContents: Int,
    val acceptedCandidates: Int,
    val rejectedCandidates: Int,
    val blockCandidates: Int,
    val unitCandidates: Int,
)

/**
 * Immutable candidate inventory. Source-derived declarations do not enter a DP until their exact
 * [HybridCandidate.outputPath] is passed to [materialize].
 */
class HybridCandidateSet internal constructor(
    private val runtimePrepared: RuntimePreparedConversion,
    val candidates: List<HybridCandidate>,
    val rejected: List<HybridRejectedCandidate>,
    val diagnostics: List<Diagnostic>,
    val logs: List<String>,
    val summary: HybridCandidateSummary,
) {
    val candidatePaths: Set<String> = candidates.mapTo(linkedSetOf()) { it.outputPath }

    private val candidatesById = candidates.associateBy { it.id }

    internal fun withAdditionalFindings(
        additionalRejected: List<HybridRejectedCandidate>,
        additionalDiagnostics: List<Diagnostic>,
        additionalLogs: List<String>,
    ): HybridCandidateSet = HybridCandidateSet(
        runtimePrepared = runtimePrepared,
        candidates = candidates,
        rejected = (rejected + additionalRejected)
            .distinctBy { listOf(it.runtimeName, it.outputPath, it.sourceSymbol, it.diagnosticCodes.joinToString()) }
            .sortedWith(compareBy({ it.outputPath.orEmpty() }, { it.sourceSymbol })),
        diagnostics = (diagnostics + additionalDiagnostics)
            .distinctBy { listOf(it.code, it.location?.path, it.location?.jsonPath, it.message) },
        logs = logs + additionalLogs,
        summary = summary.copy(rejectedCandidates = summary.rejectedCandidates + additionalRejected.size),
    )

    /**
     * Rebuilds an inert handoff containing only the selected candidates. Unknown paths, ambiguous
     * casing and output collisions fail closed. JAR-selected assets and dynamic mappings remain
     * byte-for-byte authoritative.
     */
    fun materialize(
        acceptedPaths: Set<String>,
        validation: HybridValidationState = HybridValidationState.UNVALIDATED,
        decisions: Map<String, HybridCandidateDecision> = emptyMap(),
    ): RuntimePreparedConversion {
        val normalizedAccepted = acceptedPaths.map(::candidateId).toSet()
        require(normalizedAccepted.size == acceptedPaths.size) {
            "acceptedPaths contains duplicate paths that differ only by case or path separators."
        }
        val unknown = normalizedAccepted - candidatesById.keys
        require(unknown.isEmpty()) { "Unknown hybrid candidate path(s): ${unknown.sorted().joinToString()}" }
        val normalizedDecisions = decisions.entries.associate { (path, decision) -> candidateId(path) to decision }
        require(normalizedDecisions.size == decisions.size) {
            "decisions contains duplicate paths that differ only by case or path separators."
        }
        val unknownDecisions = normalizedDecisions.keys - candidatesById.keys
        require(unknownDecisions.isEmpty()) {
            "Unknown hybrid candidate decision path(s): ${unknownDecisions.sorted().joinToString()}"
        }
        require((normalizedAccepted intersect normalizedDecisions.keys).isEmpty()) {
            "Accepted candidates may not also have a rejection or pending decision."
        }
        val selected = normalizedAccepted.map(candidatesById::getValue).sortedBy(HybridCandidate::id)

        val runtimePaths = runtimePrepared.files.map { candidateId(it.outputPath) }.toMutableSet()
        selected.forEach { candidate ->
            require(runtimePaths.add(candidate.id)) {
                "Hybrid candidate output collides with a runtime-prepared file: ${candidate.outputPath}"
            }
        }

        val selectedByRuntimeKey = selected.associateBy { runtimeKey(it.runtimeName, it.kind) }
        val exactSnapshotLocations = candidates.map { candidate ->
            val runtime = runtimePrepared.contentResults.first {
                runtimeKey(it.sourceSymbol, it.kind) == runtimeKey(candidate.runtimeName, candidate.kind)
            }
            runtime.location
        }.filterNotNull().toMutableSet()
        rejected.mapNotNull(HybridRejectedCandidate::runtimeName).forEach { runtimeName ->
            runtimePrepared.contentResults.firstOrNull { it.sourceSymbol.equals(runtimeName, ignoreCase = true) }
                ?.location
                ?.let(exactSnapshotLocations::add)
        }

        val validationCode = when (validation) {
            HybridValidationState.UNVALIDATED -> "HYBRID_STATIC_CANDIDATE_UNVALIDATED"
            HybridValidationState.DATA_PATCHER_CLEAN -> "HYBRID_DATA_PATCHER_CLEAN"
        }
        val addedFiles = selected.map { candidate ->
            RuntimePreparedFile(
                outputPath = candidate.outputPath,
                sourcePaths = candidate.jarProvenancePaths,
                bytes = candidate.bytes.copyOf(),
                namespace = RuntimePreparedOutputNamespace.SOURCE,
                reason = "Runtime registration and parser fallback confirmed this source-AST candidate; " +
                    "release-JAR assets remain authoritative.",
            )
        }
        val unselectedByRuntimeKey = candidates.asSequence()
            .filter { it.id !in normalizedAccepted }
            .associateBy { runtimeKey(it.runtimeName, it.kind) }
        val initiallyRejectedByRuntimeKey = rejected.asSequence()
            .filter { it.runtimeName != null && it.kind != null }
            .associateBy { runtimeKey(requireNotNull(it.runtimeName), it.kind) }
        val contentResults = runtimePrepared.contentResults.map { runtime ->
            val key = runtimeKey(runtime.sourceSymbol, runtime.kind)
            val candidate = selectedByRuntimeKey[key]
            if (candidate != null) {
                return@map RuntimePreparedContentResult(
                    sourceSymbol = runtime.sourceSymbol,
                    kind = candidate.kind,
                    disposition = ContentDisposition.DEGRADED,
                    sourceType = candidate.staticSourceType ?: runtime.sourceType,
                    targetType = candidate.fallbackType,
                    outputName = "dp-${candidate.localName}",
                    outputPath = candidate.outputPath,
                    reason = buildString {
                        append("A runtime-registered ")
                        append(candidate.kind.name.lowercase(Locale.ROOT))
                        append(" was reconstructed from a non-executed source AST candidate whose root parser fallback matched '")
                        append(candidate.fallbackType)
                        append("'.")
                        if (candidate.unitTemplateOverridden) {
                            append(" Its Unit template was explicitly replaced with the runtime fallback before validation.")
                        }
                        candidate.staticReason?.takeIf(String::isNotBlank)?.let { append(" Static exporter: ").append(it) }
                        if (validation == HybridValidationState.DATA_PATCHER_CLEAN) {
                            append(" The caller reports a zero-warning, zero-failure official DataPatcher apply result; gameplay parity is still not proven.")
                        } else {
                            append(" It remains pending official DataPatcher filtering.")
                        }
                    },
                    diagnosticCodes = (candidate.staticDiagnosticCodes +
                        listOf("HYBRID_STATIC_SUPPLEMENT_APPLIED", validationCode) +
                        if (candidate.unitTemplateOverridden) listOf("HYBRID_UNIT_TEMPLATE_REPLACED") else emptyList())
                        .distinct(),
                    location = candidate.sourceLocation ?: runtime.location,
                )
            }
            val unselected = unselectedByRuntimeKey[key]
            if (unselected != null) {
                val decision = normalizedDecisions[unselected.id] ?: HybridCandidateDecision(
                    HybridCandidateDisposition.PENDING,
                    "The fallback-matched source candidate was discovered but not selected for this materialization.",
                    listOf("HYBRID_CANDIDATE_PENDING"),
                )
                return@map runtime.copy(
                    reason = decision.reason,
                    diagnosticCodes = (runtime.diagnosticCodes.filterNot { it == "RUNTIME_CONTENT_TYPE_NOT_MAPPED" } +
                        decision.diagnosticCodes + decision.disposition.diagnosticCode).distinct(),
                    location = unselected.sourceLocation ?: runtime.location,
                )
            }
            val initialRejection = initiallyRejectedByRuntimeKey[key]
            if (initialRejection != null) {
                return@map runtime.copy(
                    reason = initialRejection.reason,
                    diagnosticCodes = (runtime.diagnosticCodes.filterNot { it == "RUNTIME_CONTENT_TYPE_NOT_MAPPED" } +
                        initialRejection.diagnosticCodes + "HYBRID_STATIC_CANDIDATE_REJECTED").distinct(),
                    location = initialRejection.location ?: runtime.location,
                )
            }
            runtime
        }

        val retainedRuntimeDiagnostics = runtimePrepared.diagnostics.filterNot { diagnostic ->
            diagnostic.code == "RUNTIME_CONTENT_TYPE_NOT_MAPPED" && diagnostic.location in exactSnapshotLocations
        }
        val selectedStaticDiagnostics = selected.flatMap(HybridCandidate::staticDiagnostics)
        val hybridDiagnostics = selected.flatMap { candidate ->
            buildList {
                add(
                    Diagnostic(
                        code = validationCode,
                        severity = if (validation == HybridValidationState.DATA_PATCHER_CLEAN) {
                            DiagnosticSeverity.INFO
                        } else {
                            DiagnosticSeverity.WARNING
                        },
                        message = if (validation == HybridValidationState.DATA_PATCHER_CLEAN) {
                            "A source-AST hybrid candidate passed the caller's clean official DataPatcher filter."
                        } else {
                            "A source-AST hybrid candidate is included before official DataPatcher filtering."
                        },
                        stage = ValidationStage.STRUCTURE,
                        location = candidate.sourceLocation,
                        details = "${candidate.runtimeName} -> ${candidate.outputPath}; fallback=${candidate.fallbackType}",
                        suggestion = if (validation == HybridValidationState.UNVALIDATED) {
                            "Run the pinned v159.7 DataPatcher harness and remove candidates that emit any warning or failure."
                        } else {
                            "Gameplay-test weapons, factories, effects and custom callbacks; clean parsing does not prove semantic parity."
                        },
                    ),
                )
                if (candidate.unitTemplateOverridden) {
                    add(
                        Diagnostic(
                            code = "HYBRID_UNIT_TEMPLATE_REPLACED",
                            severity = DiagnosticSeverity.WARNING,
                            message = "A Unit source template was explicitly replaced with the runtime parser fallback.",
                            stage = ValidationStage.STRUCTURE,
                            location = candidate.sourceLocation,
                            details = "${candidate.staticTargetType} -> ${candidate.fallbackType}",
                            suggestion = "Retain this candidate only after a clean official DataPatcher result and gameplay testing.",
                        ),
                    )
                }
            }
        }
        val decisionDiagnostics = candidates.asSequence()
            .filter { it.id !in normalizedAccepted }
            .map { candidate ->
                val decision = normalizedDecisions[candidate.id] ?: HybridCandidateDecision(
                    HybridCandidateDisposition.PENDING,
                    "The fallback-matched source candidate was not selected for this materialization.",
                    listOf("HYBRID_CANDIDATE_PENDING"),
                )
                Diagnostic(
                    code = decision.disposition.diagnosticCode,
                    severity = decision.disposition.severity,
                    message = decision.reason,
                    stage = ValidationStage.RUNTIME,
                    location = candidate.sourceLocation,
                    details = "${candidate.runtimeName} -> ${candidate.outputPath}; fallback=${candidate.fallbackType}",
                    suggestion = "Inspect the candidate-specific DataPatcher log before adding a manual compatibility override.",
                )
            }
            .toList()
        val initialRejectionDiagnostics = rejected.map { rejection ->
            Diagnostic(
                code = "HYBRID_STATIC_CANDIDATE_REJECTED",
                severity = if (rejection.runtimeName == null) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                message = rejection.reason,
                stage = ValidationStage.STRUCTURE,
                location = rejection.location,
                details = listOfNotNull(rejection.runtimeName, rejection.outputPath, rejection.sourceSymbol).joinToString(" -> "),
            )
        }

        val selectedPaths = selected.map(HybridCandidate::outputPath)
        val selectedOutputsByJarPath = selected
            .flatMap { candidate -> candidate.jarProvenancePaths.map { normalizePath(it) to candidate.outputPath } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, paths) -> paths.distinct().sorted() }
        val fileResults = runtimePrepared.fileResults.map { result ->
            val outputs = selectedOutputsByJarPath[normalizePath(result.sourcePath)] ?: return@map result
            result.copy(
                // A linked declaration does not mean the executable class, callbacks or custom
                // behavior were migrated. Preserve the original file-level disposition.
                reason = result.reason + " Release-JAR class provenance was linked to ${outputs.size} " +
                    "runtime-confirmed source-AST declaration(s), but executable Java behavior remains unmigrated.",
                outputPaths = (result.outputPaths + outputs).distinct().sorted(),
                diagnosticCodes = (result.diagnosticCodes + listOf(
                    "HYBRID_STATIC_SUPPLEMENT_APPLIED",
                    "HYBRID_EXECUTABLE_BEHAVIOR_UNMIGRATED",
                )).distinct(),
            )
        }
        return runtimePrepared.copy(
            files = (runtimePrepared.files + addedFiles).sortedBy { candidateId(it.outputPath) },
            fileResults = fileResults,
            contentResults = contentResults,
            diagnostics = (retainedRuntimeDiagnostics + selectedStaticDiagnostics + hybridDiagnostics +
                decisionDiagnostics + initialRejectionDiagnostics + diagnostics)
                .distinctBy { listOf(it.code, it.location?.path, it.location?.jsonPath, it.message, it.details) },
            logs = runtimePrepared.logs + logs +
                "Materialized ${selected.size}/${candidates.size} hybrid source candidate(s) " +
                "with validation state ${validation.name.lowercase(Locale.ROOT)}.",
            metadata = runtimePrepared.metadata + mapOf(
                "hybridCandidateCount" to candidates.size.toString(),
                "hybridSelectedCount" to selected.size.toString(),
                "hybridSelectedPaths" to selectedPaths.joinToString(","),
                "hybridRejectedAtDiscovery" to rejected.size.toString(),
                "hybridFilterDecisions" to normalizedDecisions.size.toString(),
                "hybridValidationState" to validation.name.lowercase(Locale.ROOT),
                "hybridAssetsAuthority" to "release-jar-only",
                "hybridSourceExecution" to "never",
            ),
        )
    }
}

data class HybridCandidateOptions(
    /** Exact Unit content output paths whose root template may be replaced with runtime fallback. */
    val unitTemplateOverridePaths: Set<String> = emptySet(),
    /** Allows fallback-root replacement for UnitType/MissileUnitType candidates before validation. */
    val replaceOfficialUnitFallbackTemplates: Boolean = false,
    val officialUnitFallbackTypes: Set<String> = setOf("UnitType", "MissileUnitType"),
)

/** Pure candidate construction; filesystem scanning/parsing is implemented in [RuntimeStaticHybrid]. */
object RuntimeStaticHybridCore {
    fun buildCandidates(
        runtimePrepared: RuntimePreparedConversion,
        runtimeContents: List<HybridRuntimeContent>,
        staticExport: StaticExportResult,
        sourceClassProvenance: List<HybridSourceClassProvenance>,
        options: HybridCandidateOptions = HybridCandidateOptions(),
    ): HybridCandidateSet {
        val logs = mutableListOf<String>()
        val diagnostics = mutableListOf<Diagnostic>()
        val rejected = mutableListOf<HybridRejectedCandidate>()
        val candidates = mutableListOf<HybridCandidate>()
        val runtimeJarEntries = runtimePrepared.fileResults.asSequence()
            .filter { it.status != ConvertedFileStatus.FAILED }
            .map { normalizePath(it.sourcePath) }
            .toSet()
        val eligibleKinds = setOf(ContentKind.BLOCK, ContentKind.UNIT)
        val runtimeResults = runtimePrepared.contentResults.filter {
            it.kind in eligibleKinds && it.disposition == ContentDisposition.UNSUPPORTED
        }
        val runtimeByKey = runtimeContents
            .filter { it.kind in eligibleKinds }
            .groupBy { runtimeKeyByLocal(it.localName, it.kind) }
        val runtimeResultKeys = runtimeResults.mapTo(hashSetOf()) { runtimeKey(it.sourceSymbol, it.kind) }
        val staticSourcePaths = (staticExport.sourceOutcomes.map { it.sourcePath } +
            staticExport.generatedFiles.flatMap { it.sourcePaths })
            .mapNotNull(::safeNormalizedSourcePath)
            .toSet()
        val provenanceIndex = buildProvenanceIndex(sourceClassProvenance, runtimeJarEntries, staticSourcePaths)
        val sourceOutcomesByPath = staticExport.sourceOutcomes.groupBy {
            safeNormalizedSourcePath(it.sourcePath) ?: it.sourcePath
        }
        val generatedByPath = staticExport.generatedFiles
            .filter(::isContentFile)
            .groupBy { candidateId(it.outputPath) }
        val overrideIds = options.unitTemplateOverridePaths.mapTo(hashSetOf(), ::candidateId)
        val staticResults = staticExport.contentResults.filter {
            it.kind in eligibleKinds && it.disposition in setOf(ContentDisposition.CONVERTED, ContentDisposition.DEGRADED)
        }

        staticResults.sortedWith(compareBy({ it.outputPath.orEmpty() }, { it.sourceSymbol })).forEach { result ->
            val outputPath = result.outputPath?.let(::normalizePath)
            val kind = result.kind
            fun reject(code: String, reason: String, runtimeName: String? = null) {
                rejected += HybridRejectedCandidate(
                    outputPath = outputPath,
                    sourceSymbol = result.sourceSymbol,
                    kind = kind,
                    reason = reason,
                    diagnosticCodes = (result.diagnosticCodes + code).distinct(),
                    location = result.location,
                    runtimeName = runtimeName,
                )
            }

            if (outputPath == null || kind == null || !pathMatchesKind(outputPath, kind)) {
                reject("HYBRID_STATIC_PATH_INVALID", "Static result has no canonical content path matching its kind.")
                return@forEach
            }
            val localName = outputPath.substringAfterLast('/').substringBeforeLast('.')
            val matchingRuntime = runtimeByKey[runtimeKeyByLocal(localName, kind)].orEmpty()
                .filter { runtimeKey(it.fullName, it.kind) in runtimeResultKeys }
            if (matchingRuntime.size != 1) {
                reject(
                    if (matchingRuntime.isEmpty()) "HYBRID_RUNTIME_REGISTRATION_MISSING" else "HYBRID_RUNTIME_REGISTRATION_AMBIGUOUS",
                    if (matchingRuntime.isEmpty()) {
                        "No unsupported runtime registration has the same local name and content kind."
                    } else {
                        "More than one runtime registration has the same local name and content kind."
                    },
                )
                return@forEach
            }
            val runtime = matchingRuntime.single()
            val generated = generatedByPath[candidateId(outputPath)].orEmpty()
            if (generated.size != 1) {
                reject(
                    "HYBRID_STATIC_OUTPUT_AMBIGUOUS",
                    "The static result must have exactly one generated content file at '$outputPath'.",
                    runtime.fullName,
                )
                return@forEach
            }
            val generatedFile = generated.single()
            if (generatedFile.namespace != io.github.moddpbridge.converter.StaticOutputNamespace.SOURCE) {
                reject(
                    "HYBRID_NAMESPACE_NOT_SOURCE",
                    "Hybrid candidates must retain the source Mod namespace for common rewriting.",
                    runtime.fullName,
                )
                return@forEach
            }
            val expectedOutputName = "dp-$localName"
            if (result.outputName != null && !result.outputName.equals(expectedOutputName, ignoreCase = true)) {
                reject("HYBRID_OUTPUT_NAME_MISMATCH", "Static outputName does not match '$expectedOutputName'.", runtime.fullName)
                return@forEach
            }

            val staticTargetType = result.targetType?.takeIf(String::isNotBlank)
            if (staticTargetType == null) {
                reject("HYBRID_STATIC_TARGET_TYPE_MISSING", "Static candidate has no target parser type.", runtime.fullName)
                return@forEach
            }
            val overrideTemplate = kind == ContentKind.UNIT && (
                candidateId(outputPath) in overrideIds ||
                    options.replaceOfficialUnitFallbackTemplates &&
                    options.officialUnitFallbackTypes.any { it.equals(runtime.fallbackType, ignoreCase = true) }
                ) && !staticTargetType.equals(runtime.fallbackType, ignoreCase = true)
            if (!staticTargetType.equals(runtime.fallbackType, ignoreCase = true) && !overrideTemplate) {
                reject(
                    "HYBRID_FALLBACK_TYPE_MISMATCH",
                    "Static target '$staticTargetType' does not match runtime fallback '${runtime.fallbackType}'.",
                    runtime.fullName,
                )
                return@forEach
            }
            if (kind != ContentKind.UNIT && candidateId(outputPath) in overrideIds) {
                reject(
                    "HYBRID_UNIT_TEMPLATE_OVERRIDE_INVALID",
                    "Template replacement is allowed only for Unit candidates.",
                    runtime.fullName,
                )
                return@forEach
            }

            val sourcePaths = generatedFile.sourcePaths.mapNotNull(::safeNormalizedSourcePath).distinct().sorted()
            if (sourcePaths.isEmpty()) {
                reject("HYBRID_SOURCE_PROVENANCE_MISSING", "Static generated file has no source-file provenance.", runtime.fullName)
                return@forEach
            }
            if (sourcePaths.size != generatedFile.sourcePaths.size) {
                reject("HYBRID_SOURCE_PATH_INVALID", "Static generated file contains an unsafe source path.", runtime.fullName)
                return@forEach
            }
            val sourceOutcomes = sourcePaths.flatMap { sourceOutcomesByPath[it].orEmpty() }
            if (sourceOutcomes.size != sourcePaths.size) {
                reject(
                    "HYBRID_SOURCE_OUTCOME_MISSING",
                    "Every candidate source file must have exactly one static-export outcome.",
                    runtime.fullName,
                )
                return@forEach
            }
            val sourceErrors = staticExport.diagnostics.filter { diagnostic ->
                diagnostic.severity == DiagnosticSeverity.ERROR && diagnostic.location?.let { location ->
                    safeNormalizedSourcePath(location.path) in sourcePaths
                } != false
            }
            if (sourceOutcomes.any { it.status == ConvertedFileStatus.FAILED } || sourceErrors.isNotEmpty()) {
                diagnostics += sourceErrors
                reject(
                    "HYBRID_SOURCE_PARSE_FAILED",
                    "The source file failed static parsing or emitted an error diagnostic; partial AST output was withheld.",
                    runtime.fullName,
                )
                return@forEach
            }
            val sourceLocation = result.location
            val declarationLine = sourceLocation?.line?.takeIf { it > 0 }
            val declarationPath = sourceLocation?.path?.let(::safeNormalizedSourcePath)
            if (declarationLine == null || declarationPath == null) {
                reject(
                    "HYBRID_SOURCE_LOCATION_MISSING",
                    "The source candidate has no safe declaration path and positive source line.",
                    runtime.fullName,
                )
                return@forEach
            }
            if (declarationPath !in sourcePaths) {
                reject(
                    "HYBRID_SOURCE_LOCATION_MISMATCH",
                    "The source candidate declaration does not belong to its generated file provenance.",
                    runtime.fullName,
                )
                return@forEach
            }
            val linkedRecords = provenanceIndex.recordsFor(declarationPath)
            if (linkedRecords.isEmpty()) {
                reject(
                    "HYBRID_JAR_PROVENANCE_MISSING",
                    "No source-index class entry links this source declaration back to the authoritative release JAR.",
                    runtime.fullName,
                )
                return@forEach
            }
            val jarProvenance = linkedRecords.asSequence()
                .filter { declarationLine in it.runtimeLineNumbers }
                .map { normalizePath(it.jarPath) }
                .distinct()
                .sorted()
                .toList()
            if (jarProvenance.isEmpty()) {
                reject(
                    "HYBRID_JAR_LINE_PROVENANCE_MISSING",
                    "No linked release-JAR class line table contains source declaration line $declarationLine.",
                    runtime.fullName,
                )
                return@forEach
            }

            val bytes = if (overrideTemplate) {
                replaceUnitTemplate(generatedFile, runtime.fallbackType)
            } else {
                generatedFile.bytes.copyOf()
            }
            val relevantDiagnostics = staticExport.diagnostics.filter { diagnostic ->
                val location = diagnostic.location
                diagnostic.code in result.diagnosticCodes &&
                    (location == null || safeNormalizedSourcePath(location.path) in sourcePaths)
            }
            candidates += HybridCandidate(
                id = candidateId(outputPath),
                outputPath = outputPath,
                runtimeName = runtime.fullName,
                localName = localName,
                kind = kind,
                fallbackType = runtime.fallbackType,
                staticSourceType = result.sourceType,
                staticTargetType = staticTargetType,
                sourceLocation = result.location,
                sourcePaths = sourcePaths,
                jarProvenancePaths = jarProvenance,
                bytes = bytes,
                staticDisposition = result.disposition,
                staticReason = result.reason,
                staticDiagnosticCodes = result.diagnosticCodes,
                staticDiagnostics = relevantDiagnostics,
                unitTemplateOverridden = overrideTemplate,
            )
        }

        val duplicateCandidateIds = candidates.groupBy(HybridCandidate::id).filterValues { it.size > 1 }.keys
        val runtimeOutputIds = runtimePrepared.files.mapTo(hashSetOf()) { candidateId(it.outputPath) }
        val basenameCollisions = (runtimePrepared.files + candidates.map {
            RuntimePreparedFile(it.outputPath, it.jarProvenancePaths, it.bytes)
        }).filter { normalizePath(it.outputPath).startsWith("content/", ignoreCase = true) }
            .groupBy { it.outputPath.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT) }
            .filterValues { it.size > 1 }
            .keys
        val filteredCandidates = candidates.filterNot { candidate ->
            val codes = buildList {
                if (candidate.id in duplicateCandidateIds) add("HYBRID_STATIC_OUTPUT_AMBIGUOUS")
                if (candidate.id in runtimeOutputIds) add("HYBRID_RUNTIME_OUTPUT_COLLISION")
                if (candidate.localName.lowercase(Locale.ROOT) in basenameCollisions) add("HYBRID_CONTENT_BASENAME_COLLISION")
            }
            if (codes.isNotEmpty()) {
                rejected += HybridRejectedCandidate(
                    candidate.outputPath,
                    candidate.runtimeName,
                    candidate.kind,
                    "Candidate output collides with another runtime or static content file.",
                    (candidate.staticDiagnosticCodes + codes).distinct(),
                    candidate.sourceLocation,
                    candidate.runtimeName,
                )
                true
            } else {
                false
            }
        }.sortedBy(HybridCandidate::id)

        diagnostics += Diagnostic(
            code = "HYBRID_STATIC_CANDIDATES_DISCOVERED",
            severity = DiagnosticSeverity.INFO,
            message = "Source AST declarations were constrained by runtime registrations and parser fallbacks.",
            stage = ValidationStage.STRUCTURE,
            details = "${filteredCandidates.size} candidate(s), ${rejected.size} rejected; no source asset was accepted.",
            suggestion = "Materialize only candidates that pass the pinned official v159.7 DataPatcher warning/failure filter.",
        )
        logs += "Hybrid candidate scan accepted ${filteredCandidates.size} Block/Unit declaration(s) and rejected ${rejected.size}."
        logs += "Runtime Item/Liquid/Status declarations and all selected assets remain authoritative; source assets were ignored."
        return HybridCandidateSet(
            runtimePrepared = runtimePrepared,
            candidates = filteredCandidates,
            rejected = rejected.sortedWith(compareBy({ it.outputPath.orEmpty() }, { it.sourceSymbol })),
            diagnostics = diagnostics,
            logs = logs,
            summary = HybridCandidateSummary(
                runtimeEligibleContents = runtimeResults.size,
                staticGeneratedContents = staticResults.size,
                acceptedCandidates = filteredCandidates.size,
                rejectedCandidates = rejected.size,
                blockCandidates = filteredCandidates.count { it.kind == ContentKind.BLOCK },
                unitCandidates = filteredCandidates.count { it.kind == ContentKind.UNIT },
            ),
        )
    }

    private fun replaceUnitTemplate(file: StaticGeneratedFile, fallback: String): ByteArray {
        val root = try {
            JSON.parseToJsonElement(file.bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
        } catch (error: Exception) {
            null
        } ?: throw IllegalArgumentException("Unit template override requires a JSON object: ${file.outputPath}")
        val replaced = buildJsonObject {
            root.forEach { (key, value) ->
                if (key != "template") put(key, value)
            }
            put("template", JsonPrimitive(fallback))
        }
        return (JSON.encodeToString(JsonElement.serializer(), replaced) + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    private val JSON = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}

private data class SourceProvenanceIndex(
    private val byStaticPath: Map<String, List<HybridSourceClassProvenance>>,
) {
    fun recordsFor(staticPath: String): List<HybridSourceClassProvenance> = byStaticPath[staticPath].orEmpty()
}

private fun buildProvenanceIndex(
    provenance: List<HybridSourceClassProvenance>,
    runtimeJarEntries: Set<String>,
    staticSourcePaths: Set<String>,
): SourceProvenanceIndex {
    val eligible = provenance.asSequence()
        .filter { it.sourceCoversRuntimeLines == true }
        .filter { it.runtimeLineNumbers.isNotEmpty() }
        .filter { normalizePath(it.jarPath) in runtimeJarEntries }
        .mapNotNull { record -> safeNormalizedSourcePath(record.sourcePath)?.let { it to record } }
        .toList()
    val provenancePaths = eligible.mapTo(linkedSetOf()) { it.first }
    val rootPlan = verifiedSingleRootPlan(staticSourcePaths, provenancePaths)
    val byStaticPath = linkedMapOf<String, MutableList<HybridSourceClassProvenance>>()
    eligible.forEach { (sourcePath, record) ->
        val canonical = when {
            sourcePath in staticSourcePaths -> sourcePath
            rootPlan?.direction == RootDirection.PROVENANCE_HAS_ROOT &&
                sourcePath.startsWith("${rootPlan.root}/") -> sourcePath.substringAfter('/')
            rootPlan?.direction == RootDirection.STATIC_HAS_ROOT -> "${rootPlan.root}/$sourcePath"
            else -> null
        }
        if (canonical != null && canonical in staticSourcePaths) {
            byStaticPath.getOrPut(canonical, ::mutableListOf) += record
        }
    }
    return SourceProvenanceIndex(byStaticPath.mapValues { (_, records) ->
        records.distinctBy { normalizePath(it.jarPath) }.sortedBy { normalizePath(it.jarPath) }
    })
}

private enum class RootDirection { PROVENANCE_HAS_ROOT, STATIC_HAS_ROOT }

private data class SingleRootPlan(val direction: RootDirection, val root: String)

private fun verifiedSingleRootPlan(
    staticPaths: Set<String>,
    provenancePaths: Set<String>,
): SingleRootPlan? {
    if (staticPaths.isEmpty() || provenancePaths.isEmpty() || provenancePaths.any { it in staticPaths }) return null
    val candidates = mutableListOf<SingleRootPlan>()
    val provenanceRoots = provenancePaths.map { it.substringBefore('/') }.distinct()
    if (provenanceRoots.size == 1 && provenancePaths.all { '/' in it && it.substringAfter('/') in staticPaths }) {
        candidates += SingleRootPlan(RootDirection.PROVENANCE_HAS_ROOT, provenanceRoots.single())
    }
    val staticRoots = staticPaths.map { it.substringBefore('/') }.distinct()
    staticRoots.forEach { root ->
        if (provenancePaths.all { "$root/$it" in staticPaths }) {
            candidates += SingleRootPlan(RootDirection.STATIC_HAS_ROOT, root)
        }
    }
    return candidates.singleOrNull()
}

private fun isContentFile(file: StaticGeneratedFile): Boolean =
    normalizePath(file.outputPath).startsWith("content/", ignoreCase = true)

private fun pathMatchesKind(path: String, kind: ContentKind): Boolean {
    val normalized = normalizePath(path)
    val segments = normalized.split('/')
    val extension = normalized.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return normalized.startsWith("content/${kind.folderName}/", ignoreCase = true) &&
        normalized.none { it == '\u0000' || it.code < 0x20 || it.code == 0x7f } &&
        segments.none { it.isBlank() || it == "." || it == ".." } &&
        extension in setOf("json", "hjson", "json5") &&
        normalized.substringAfterLast('/').substringBeforeLast('.').isNotBlank()
}

private fun normalizePath(path: String): String = path.replace('\\', '/').trim('/')

private fun safeNormalizedSourcePath(path: String): String? {
    val normalized = path.replace('\\', '/').trim('/')
    if (normalized.isBlank() || normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
    if (normalized.any { it == '\u0000' || it.code < 0x20 || it.code == 0x7f }) return null
    if (normalized.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
    return normalized
}

private fun candidateId(path: String): String = normalizePath(path).lowercase(Locale.ROOT)

private fun runtimeKey(name: String, kind: ContentKind?): String =
    "${kind?.name ?: "unknown"}:${name.lowercase(Locale.ROOT)}"

private fun runtimeKeyByLocal(localName: String, kind: ContentKind): String =
    "${kind.name}:${localName.lowercase(Locale.ROOT)}"

private val HybridCandidateDisposition.diagnosticCode: String
    get() = when (this) {
        HybridCandidateDisposition.PENDING -> "HYBRID_CANDIDATE_PENDING"
        HybridCandidateDisposition.REJECTED_WARNING -> "HYBRID_CANDIDATE_REJECTED_WARNING"
        HybridCandidateDisposition.REJECTED_FAILURE -> "HYBRID_CANDIDATE_REJECTED_FAILURE"
        HybridCandidateDisposition.REJECTED_DEPENDENCY_CLOSURE -> "HYBRID_CANDIDATE_REJECTED_DEPENDENCY_CLOSURE"
        HybridCandidateDisposition.REJECTED_UNRESOLVED_REFERENCE -> "HYBRID_CANDIDATE_REJECTED_UNRESOLVED_REFERENCE"
        HybridCandidateDisposition.REJECTED_MANUAL -> "HYBRID_CANDIDATE_REJECTED_MANUAL"
    }

private val HybridCandidateDisposition.severity: DiagnosticSeverity
    get() = when (this) {
        HybridCandidateDisposition.PENDING -> DiagnosticSeverity.INFO
        HybridCandidateDisposition.REJECTED_WARNING,
        HybridCandidateDisposition.REJECTED_FAILURE,
        HybridCandidateDisposition.REJECTED_DEPENDENCY_CLOSURE,
        HybridCandidateDisposition.REJECTED_UNRESOLVED_REFERENCE,
        HybridCandidateDisposition.REJECTED_MANUAL,
        -> DiagnosticSeverity.WARNING
    }
