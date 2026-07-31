package io.github.moddpbridge.cli

import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.target.v1597.ContentApplyValidationResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Materializes one exact candidate set into a fresh validation directory.
 *
 * The returned path must be the `server-assets` directory that is passed to the real v159.7
 * DataPatcher validator. Implementations may package an intermediate DP ZIP, but must not execute
 * the source Mod; the runtime snapshot and already prepared declarations are the only authority.
 */
internal fun interface DataPatchCandidateMaterializer {
    fun materialize(candidatePaths: Set<String>, attemptDirectory: Path): Path
}

/** Runs one already-materialized candidate set through the real DataPatcher apply harness. */
internal fun interface DataPatchCandidateValidator {
    fun validate(serverAssets: Path, logSink: (String) -> Unit): ContentApplyValidationResult
}

internal enum class MonotonicCandidateSelectionStatus {
    /** The returned [MonotonicCandidateSelectionResult.acceptedPaths] passed with zero failures/warnings. */
    DATA_PATCHER_CLEAN,

    /** The runtime-only base itself was not clean, so no candidate can be classified safely. */
    BASELINE_INVALID,

    /** Candidate attribution became unsafe; the caller must use the previously proven runtime-only base. */
    FALLBACK_TO_RUNTIME_BASE,
}

internal enum class MonotonicCandidateRoundKind {
    BASELINE,
    CANDIDATES,
}

internal enum class MonotonicCandidateRoundDecision {
    CLEAN,
    REMOVE_ATTRIBUTED_CANDIDATES,
    BASELINE_INVALID,
    FALLBACK_TO_RUNTIME_BASE,
}

internal data class MonotonicCandidateRound(
    val index: Int,
    val kind: MonotonicCandidateRoundKind,
    val testedPaths: List<String>,
    val decision: MonotonicCandidateRoundDecision,
    val removedPaths: List<String>,
    val serverAssets: Path?,
    val logFile: Path,
    val applyResult: ContentApplyValidationResult?,
    val failure: String? = null,
)

internal data class MonotonicCandidateSelectionRequest(
    val candidatePaths: Collection<String>,
    val workspace: Path,
    /** Candidate rounds only; the mandatory runtime-base validation does not consume this budget. */
    val maxRounds: Int = candidatePaths.size + 1,
)

internal data class MonotonicCandidateSelectionResult(
    val status: MonotonicCandidateSelectionStatus,
    /** Safe only when [status] is [MonotonicCandidateSelectionStatus.DATA_PATCHER_CLEAN]. */
    val acceptedPaths: List<String>,
    /** Paths removed by directly attributable DataPatcher diagnostics. */
    val rejectedPaths: List<String>,
    /** Stable report reason for every entry in [rejectedPaths]. */
    val rejectedReasons: Map<String, String> = emptyMap(),
    /** Candidates that were still present when selection had to fall back to the runtime-only base. */
    val unresolvedPaths: List<String>,
    /** Stable report reason for every entry in [unresolvedPaths]. */
    val unresolvedReasons: Map<String, String> = emptyMap(),
    val rounds: List<MonotonicCandidateRound>,
    val unattributedDiagnostics: List<Diagnostic>,
    val reason: String? = null,
)

/**
 * Deterministic, decreasing-set selector for optional static Block/Unit candidates.
 *
 * The runtime-only base is validated first and must report `failedAssets == 0` and
 * `warningCount == 0`. Candidate validation then always starts with the full remaining set. Only
 * the three path-bearing DataPatcher diagnostics in [REMOVABLE_DIAGNOSTIC_CODES] may remove a
 * candidate, and only when their path resolves exactly to that candidate output. The reduced set
 * is validated again, allowing dependency fallout to be discovered in a later round.
 *
 * Harness/protocol/patch failures, timeouts, warnings on base assets and diagnostics without an
 * exact candidate owner are never converted into a guessed rejection. In those cases the caller
 * must fall back to the already-proven runtime-only base.
 */
internal class MonotonicDataPatchCandidateSelector(
    private val materializer: DataPatchCandidateMaterializer,
    private val validator: DataPatchCandidateValidator,
) {
    fun select(
        request: MonotonicCandidateSelectionRequest,
        logger: BridgeLogger,
    ): MonotonicCandidateSelectionResult {
        require(request.maxRounds > 0) { "maxRounds must be positive." }
        val candidates = canonicalCandidatePaths(request.candidatePaths)
        val workspace = request.workspace.toAbsolutePath().normalize()
        Files.createDirectories(workspace)

        val rounds = mutableListOf<MonotonicCandidateRound>()
        val rejected = linkedSetOf<String>()
        val rejectionReasons = linkedMapOf<String, String>()

        val baseline = runAttempt(
            index = 0,
            kind = MonotonicCandidateRoundKind.BASELINE,
            candidatePaths = emptyList(),
            workspace = workspace,
            logger = logger,
        )
        val baselineRound = when (val execution = baseline.execution) {
            is AttemptExecution.Failed -> MonotonicCandidateRound(
                index = 0,
                kind = MonotonicCandidateRoundKind.BASELINE,
                testedPaths = emptyList(),
                decision = MonotonicCandidateRoundDecision.BASELINE_INVALID,
                removedPaths = emptyList(),
                serverAssets = execution.serverAssets,
                logFile = baseline.logFile,
                applyResult = execution.result,
                failure = execution.failure,
            )

            is AttemptExecution.Completed -> MonotonicCandidateRound(
                index = 0,
                kind = MonotonicCandidateRoundKind.BASELINE,
                testedPaths = emptyList(),
                decision = if (execution.result.isStrictlyClean()) {
                    MonotonicCandidateRoundDecision.CLEAN
                } else {
                    MonotonicCandidateRoundDecision.BASELINE_INVALID
                },
                removedPaths = emptyList(),
                serverAssets = execution.serverAssets,
                logFile = baseline.logFile,
                applyResult = execution.result,
                failure = null,
            )
        }
        rounds += baselineRound
        if (baselineRound.decision != MonotonicCandidateRoundDecision.CLEAN) {
            val diagnostics = baselineRound.applyResult?.blockingDiagnostics().orEmpty()
            val reason = baselineRound.failure ?: strictFailureSummary(
                "The runtime-only base did not pass strict DataPatcher validation.",
                baselineRound.applyResult,
            )
            logger.error(reason)
            return MonotonicCandidateSelectionResult(
                status = MonotonicCandidateSelectionStatus.BASELINE_INVALID,
                acceptedPaths = emptyList(),
                rejectedPaths = emptyList(),
                rejectedReasons = emptyMap(),
                unresolvedPaths = candidates,
                unresolvedReasons = candidates.associateWith { REASON_UNRESOLVED },
                rounds = rounds,
                unattributedDiagnostics = diagnostics,
                reason = reason,
            )
        }

        if (candidates.isEmpty()) {
            return MonotonicCandidateSelectionResult(
                status = MonotonicCandidateSelectionStatus.DATA_PATCHER_CLEAN,
                acceptedPaths = emptyList(),
                rejectedPaths = emptyList(),
                rejectedReasons = emptyMap(),
                unresolvedPaths = emptyList(),
                unresolvedReasons = emptyMap(),
                rounds = rounds,
                unattributedDiagnostics = emptyList(),
            )
        }

        var remaining = candidates
        var candidateRound = 0
        while (remaining.isNotEmpty()) {
            if (candidateRound >= request.maxRounds) {
                val reason = "Candidate selection reached maxRounds=${request.maxRounds} before a clean set was proven."
                logger.warn("$reason Falling back to the runtime-only base.")
                return fallbackResult(rounds, rejected, rejectionReasons, remaining, emptyList(), reason)
            }
            candidateRound++
            val index = rounds.size
            val attempt = runAttempt(
                index = index,
                kind = MonotonicCandidateRoundKind.CANDIDATES,
                candidatePaths = remaining,
                workspace = workspace,
                logger = logger,
            )

            when (val execution = attempt.execution) {
                is AttemptExecution.Failed -> {
                    val round = MonotonicCandidateRound(
                        index = index,
                        kind = MonotonicCandidateRoundKind.CANDIDATES,
                        testedPaths = remaining,
                        decision = MonotonicCandidateRoundDecision.FALLBACK_TO_RUNTIME_BASE,
                        removedPaths = emptyList(),
                        serverAssets = execution.serverAssets,
                        logFile = attempt.logFile,
                        applyResult = execution.result,
                        failure = execution.failure,
                    )
                    rounds += round
                    logger.warn("Candidate validation infrastructure failed; falling back to the runtime-only base: ${execution.failure}")
                    return fallbackResult(
                        rounds,
                        rejected,
                        rejectionReasons,
                        remaining,
                        execution.result?.blockingDiagnostics().orEmpty(),
                        execution.failure,
                    )
                }

                is AttemptExecution.Completed -> {
                    val result = execution.result
                    if (result.isStrictlyClean()) {
                        rounds += MonotonicCandidateRound(
                            index = index,
                            kind = MonotonicCandidateRoundKind.CANDIDATES,
                            testedPaths = remaining,
                            decision = MonotonicCandidateRoundDecision.CLEAN,
                            removedPaths = emptyList(),
                            serverAssets = execution.serverAssets,
                            logFile = attempt.logFile,
                            applyResult = result,
                        )
                        logger.info("DataPatcher accepted ${remaining.size} optional hybrid candidate(s) with zero failures and warnings.")
                        return MonotonicCandidateSelectionResult(
                            status = MonotonicCandidateSelectionStatus.DATA_PATCHER_CLEAN,
                            acceptedPaths = remaining,
                            rejectedPaths = rejected.toList().sorted(),
                            rejectedReasons = rejectionReasons.toSortedMap(),
                            unresolvedPaths = emptyList(),
                            unresolvedReasons = emptyMap(),
                            rounds = rounds,
                            unattributedDiagnostics = emptyList(),
                        )
                    }

                    val attribution = attributeFailures(
                        result = result,
                        serverAssets = execution.serverAssets,
                        remaining = remaining,
                        dependencyClosureRound = candidateRound > 1,
                    )
                    if (attribution.unattributed.isNotEmpty() || attribution.removedPaths.isEmpty()) {
                        val reason = strictFailureSummary(
                            "Candidate validation failed without a complete, safe candidate-path attribution.",
                            result,
                        )
                        rounds += MonotonicCandidateRound(
                            index = index,
                            kind = MonotonicCandidateRoundKind.CANDIDATES,
                            testedPaths = remaining,
                            decision = MonotonicCandidateRoundDecision.FALLBACK_TO_RUNTIME_BASE,
                            removedPaths = attribution.removedPaths,
                            serverAssets = execution.serverAssets,
                            logFile = attempt.logFile,
                            applyResult = result,
                            failure = reason,
                        )
                        logger.warn("$reason Falling back to the runtime-only base.")
                        return fallbackResult(
                            rounds,
                            rejected,
                            rejectionReasons,
                            remaining,
                            attribution.unattributed,
                            reason,
                        )
                    }

                    rejected += attribution.removedPaths
                    rejectionReasons.putAll(attribution.reasons)
                    rounds += MonotonicCandidateRound(
                        index = index,
                        kind = MonotonicCandidateRoundKind.CANDIDATES,
                        testedPaths = remaining,
                        decision = MonotonicCandidateRoundDecision.REMOVE_ATTRIBUTED_CANDIDATES,
                        removedPaths = attribution.removedPaths,
                        serverAssets = execution.serverAssets,
                        logFile = attempt.logFile,
                        applyResult = result,
                    )
                    logger.warn(
                        "DataPatcher attributed this round to ${attribution.removedPaths.size} candidate(s); " +
                            "removing them and validating the complete remaining dependency set again.",
                    )
                    remaining = remaining.filterNot(attribution.removedPaths.toSet()::contains)
                }
            }
        }

        // Every candidate was directly rejected. The already-proven baseline is the clean result.
        return MonotonicCandidateSelectionResult(
            status = MonotonicCandidateSelectionStatus.DATA_PATCHER_CLEAN,
            acceptedPaths = emptyList(),
            rejectedPaths = rejected.toList().sorted(),
            rejectedReasons = rejectionReasons.toSortedMap(),
            unresolvedPaths = emptyList(),
            unresolvedReasons = emptyMap(),
            rounds = rounds,
            unattributedDiagnostics = emptyList(),
        )
    }

    private fun runAttempt(
        index: Int,
        kind: MonotonicCandidateRoundKind,
        candidatePaths: List<String>,
        workspace: Path,
        logger: BridgeLogger,
    ): Attempt {
        val suffix = if (kind == MonotonicCandidateRoundKind.BASELINE) "baseline" else "candidates"
        val attemptDirectory = workspace.resolve("attempt-${index.toString().padStart(3, '0')}-$suffix")
        val logFile = attemptDirectory.resolve("data-patch-apply.log")
        logger.info(
            if (kind == MonotonicCandidateRoundKind.BASELINE) {
                "Validating the runtime-only DataPatcher baseline."
            } else {
                "Validating ${candidatePaths.size} remaining optional hybrid candidate(s), round $index."
            },
        )

        var serverAssets: Path? = null
        val result = try {
            Files.createDirectory(attemptDirectory)
            val materialized = materializer.materialize(candidatePaths.toCollection(linkedSetOf()), attemptDirectory)
                .toAbsolutePath()
                .normalize()
            require(materialized.startsWith(attemptDirectory.toAbsolutePath().normalize())) {
                "Candidate materializer returned a path outside its attempt directory: $materialized"
            }
            require(Files.isDirectory(materialized)) {
                "Candidate materializer did not return a server-assets directory: $materialized"
            }
            serverAssets = materialized

            val outputLines = mutableListOf<String>()
            val apply = validator.validate(materialized) { line ->
                outputLines += line
                logger.raw("[candidate-apply:$index] $line")
            }
            val retainedOutput = if (apply.outputLines.isNotEmpty()) apply.outputLines else outputLines
            Files.writeString(
                logFile,
                retainedOutput.joinToString(separator = "\n", postfix = if (retainedOutput.isEmpty()) "" else "\n"),
                StandardCharsets.UTF_8,
            )
            AttemptExecution.Completed(materialized, apply)
        } catch (error: Throwable) {
            val failure = "${error::class.java.name}: ${error.message.orEmpty()}"
            runCatching {
                Files.createDirectories(attemptDirectory)
                Files.writeString(logFile, failure + "\n\n" + error.stackTraceToString(), StandardCharsets.UTF_8)
            }
            AttemptExecution.Failed(serverAssets, null, failure)
        }
        return Attempt(logFile, result)
    }

    private fun attributeFailures(
        result: ContentApplyValidationResult,
        serverAssets: Path,
        remaining: List<String>,
        dependencyClosureRound: Boolean,
    ): Attribution {
        if (!result.applyCompleted || result.timedOut || result.exitCode == null) {
            return Attribution(emptyList(), emptyMap(), result.blockingDiagnostics())
        }

        val candidateSet = remaining.toSet()
        val removed = linkedSetOf<String>()
        val reasons = linkedMapOf<String, String>()
        val unattributed = mutableListOf<Diagnostic>()
        result.blockingDiagnostics().forEach { diagnostic ->
            if (diagnostic.code !in REMOVABLE_DIAGNOSTIC_CODES) {
                unattributed += diagnostic
                return@forEach
            }
            val relative = diagnostic.candidateRelativePath(serverAssets)
            if (relative == null || relative !in candidateSet) {
                unattributed += diagnostic
            } else {
                removed += relative
                val reason = when {
                    dependencyClosureRound -> REASON_DEPENDENCY_CLOSURE
                    diagnostic.code == "DATA_PATCH_APPLY_WARNING" -> REASON_DATA_PATCHER_WARNING
                    else -> REASON_DATA_PATCHER_FAILURE
                }
                reasons.merge(relative, reason, ::preferReason)
            }
        }

        // Counts are authoritative even if a malformed/incomplete protocol omitted path records.
        if ((result.failedAssets ?: -1) > 0 && removed.isEmpty()) {
            return Attribution(emptyList(), emptyMap(), result.blockingDiagnostics())
        }
        if ((result.warningCount ?: -1) > 0 &&
            result.diagnostics.none { it.code == "DATA_PATCH_APPLY_WARNING" }
        ) {
            return Attribution(removed.toList().sorted(), reasons.toSortedMap(), result.blockingDiagnostics())
        }
        if (!result.passed && result.blockingDiagnostics().isEmpty()) {
            return Attribution(removed.toList().sorted(), reasons.toSortedMap(), result.diagnostics)
        }
        return Attribution(removed.toList().sorted(), reasons.toSortedMap(), unattributed.distinct())
    }

    private fun Diagnostic.candidateRelativePath(serverAssets: Path): String? {
        val raw = location?.path ?: return null
        val root = serverAssets.toAbsolutePath().normalize()
        val locationPath = runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull() ?: return null
        if (!locationPath.startsWith(root) || locationPath == root) return null
        return canonicalCandidatePath(root.relativize(locationPath).joinToString("/") { it.toString() })
    }

    private fun fallbackResult(
        rounds: List<MonotonicCandidateRound>,
        rejected: Set<String>,
        rejectionReasons: Map<String, String>,
        remaining: List<String>,
        unattributed: List<Diagnostic>,
        reason: String,
    ): MonotonicCandidateSelectionResult = MonotonicCandidateSelectionResult(
        status = MonotonicCandidateSelectionStatus.FALLBACK_TO_RUNTIME_BASE,
        acceptedPaths = emptyList(),
        rejectedPaths = rejected.toList().sorted(),
        rejectedReasons = rejectionReasons.toSortedMap(),
        unresolvedPaths = remaining,
        unresolvedReasons = remaining.associateWith { REASON_UNRESOLVED },
        rounds = rounds,
        unattributedDiagnostics = unattributed.distinct(),
        reason = reason,
    )

    private fun canonicalCandidatePaths(raw: Collection<String>): List<String> {
        val normalized = raw.map(::canonicalCandidatePath)
        require(normalized.size == normalized.toSet().size) {
            "candidatePaths must be unique after path normalization."
        }
        return normalized.sorted()
    }

    private fun canonicalCandidatePath(raw: String): String {
        val replaced = raw.replace('\\', '/').trim()
        val segments = replaced.split('/')
        require(
            replaced.isNotBlank() &&
                !replaced.startsWith('/') &&
                !WINDOWS_ABSOLUTE.matches(replaced) &&
                segments.none { it.isBlank() || it == "." || it == ".." },
        ) { "Invalid candidate output path: '$raw'." }
        return segments.joinToString("/").lowercase(Locale.ROOT)
    }

    private fun ContentApplyValidationResult.isStrictlyClean(): Boolean =
        applyCompleted && passed && !timedOut && exitCode == 0 && failedAssets == 0 && warningCount == 0 &&
            blockingDiagnostics().isEmpty()

    private fun ContentApplyValidationResult.blockingDiagnostics(): List<Diagnostic> =
        diagnostics.filter { it.severity == DiagnosticSeverity.WARNING || it.severity == DiagnosticSeverity.ERROR }

    private fun strictFailureSummary(prefix: String, result: ContentApplyValidationResult?): String = buildString {
        append(prefix)
        if (result != null) {
            append(" applyCompleted=${result.applyCompleted}")
            append(", exitCode=${result.exitCode ?: "none"}")
            append(", timedOut=${result.timedOut}")
            append(", failedAssets=${result.failedAssets ?: "unknown"}")
            append(", warningCount=${result.warningCount ?: "unknown"}")
            append('.')
        }
    }

    private data class Attempt(
        val logFile: Path,
        val execution: AttemptExecution,
    )

    private sealed interface AttemptExecution {
        data class Completed(
            val serverAssets: Path,
            val result: ContentApplyValidationResult,
        ) : AttemptExecution

        data class Failed(
            val serverAssets: Path?,
            val result: ContentApplyValidationResult?,
            val failure: String,
        ) : AttemptExecution
    }

    private data class Attribution(
        val removedPaths: List<String>,
        val reasons: Map<String, String>,
        val unattributed: List<Diagnostic>,
    )

    private fun preferReason(existing: String, new: String): String =
        if (reasonPriority(new) > reasonPriority(existing)) new else existing

    private fun reasonPriority(reason: String): Int = when (reason) {
        REASON_DEPENDENCY_CLOSURE -> 3
        REASON_DATA_PATCHER_FAILURE -> 2
        REASON_DATA_PATCHER_WARNING -> 1
        else -> 0
    }

    private companion object {
        val REMOVABLE_DIAGNOSTIC_CODES = setOf(
            "DATA_PATCH_CONTENT_FAILED",
            "DATA_PATCH_APPLY_WARNING",
            "DATA_ASSET_READ_FAILED",
        )
        val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:.*")
        const val REASON_DATA_PATCHER_WARNING = "data-patcher-warning"
        const val REASON_DATA_PATCHER_FAILURE = "data-patcher-failure"
        const val REASON_DEPENDENCY_CLOSURE = "dependency-closure"
        const val REASON_UNRESOLVED = "unresolved"
    }
}
