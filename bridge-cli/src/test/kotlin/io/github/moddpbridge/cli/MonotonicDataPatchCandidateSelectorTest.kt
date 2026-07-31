package io.github.moddpbridge.cli

import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import io.github.moddpbridge.target.v1597.ContentApplyValidationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MonotonicDataPatchCandidateSelectorTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `validates strict base then accepts the complete clean candidate set`() {
        val harness = FixtureHarness { _, _ -> cleanResult() }

        val result = select(harness, listOf(BLOCK_A, BLOCK_B))

        assertEquals(MonotonicCandidateSelectionStatus.DATA_PATCHER_CLEAN, result.status)
        assertEquals(listOf(BLOCK_A, BLOCK_B), result.acceptedPaths)
        assertEquals(emptyList(), result.rejectedPaths)
        assertEquals(listOf(emptySet(), setOf(BLOCK_A, BLOCK_B)), harness.selections)
        assertEquals(
            listOf(MonotonicCandidateRoundDecision.CLEAN, MonotonicCandidateRoundDecision.CLEAN),
            result.rounds.map { it.decision },
        )
        assertTrue(result.rounds.all { Files.isRegularFile(it.logFile) })
    }

    @Test
    fun `removes only directly attributed candidate and revalidates the remaining dependency set`() {
        val harness = FixtureHarness { selected, assets ->
            when {
                selected.isEmpty() -> cleanResult()
                BLOCK_B in selected -> failedResult(
                    assets,
                    BLOCK_B,
                    code = "DATA_PATCH_CONTENT_FAILED",
                    severity = DiagnosticSeverity.ERROR,
                )
                else -> cleanResult()
            }
        }

        val result = select(harness, listOf(BLOCK_C, BLOCK_B, BLOCK_A))

        assertEquals(MonotonicCandidateSelectionStatus.DATA_PATCHER_CLEAN, result.status)
        assertEquals(listOf(BLOCK_A, BLOCK_C), result.acceptedPaths)
        assertEquals(listOf(BLOCK_B), result.rejectedPaths)
        assertEquals(mapOf(BLOCK_B to "data-patcher-failure"), result.rejectedReasons)
        assertEquals(
            listOf(emptySet(), setOf(BLOCK_A, BLOCK_B, BLOCK_C), setOf(BLOCK_A, BLOCK_C)),
            harness.selections,
        )
        assertEquals(
            listOf(BLOCK_B),
            result.rounds.single { it.decision == MonotonicCandidateRoundDecision.REMOVE_ATTRIBUTED_CANDIDATES }
                .removedPaths,
        )
    }

    @Test
    fun `repeated full-set validation catches dependency fallout after an earlier removal`() {
        val harness = FixtureHarness { selected, assets ->
            when {
                selected.isEmpty() -> cleanResult()
                BLOCK_B in selected -> failedResult(assets, BLOCK_B)
                BLOCK_C in selected -> failedResult(assets, BLOCK_C)
                else -> cleanResult()
            }
        }

        val result = select(harness, listOf(BLOCK_A, BLOCK_B, BLOCK_C))

        assertEquals(MonotonicCandidateSelectionStatus.DATA_PATCHER_CLEAN, result.status)
        assertEquals(listOf(BLOCK_A), result.acceptedPaths)
        assertEquals(listOf(BLOCK_B, BLOCK_C), result.rejectedPaths)
        assertEquals(
            mapOf(BLOCK_B to "data-patcher-failure", BLOCK_C to "dependency-closure"),
            result.rejectedReasons,
        )
        assertEquals(4, result.rounds.size)
        assertEquals(
            listOf(
                emptySet(),
                setOf(BLOCK_A, BLOCK_B, BLOCK_C),
                setOf(BLOCK_A, BLOCK_C),
                setOf(BLOCK_A),
            ),
            harness.selections,
        )
    }

    @Test
    fun `treats an attributed apply warning as rejection even when validator passed is true`() {
        val harness = FixtureHarness { selected, assets ->
            if (selected.isEmpty()) cleanResult() else failedResult(
                assets = assets,
                path = BLOCK_A,
                code = "DATA_PATCH_APPLY_WARNING",
                severity = DiagnosticSeverity.WARNING,
                failedAssets = 0,
                warningCount = 1,
                passed = true,
                exitCode = 0,
            )
        }

        val result = select(harness, listOf(BLOCK_A))

        assertEquals(MonotonicCandidateSelectionStatus.DATA_PATCHER_CLEAN, result.status)
        assertEquals(emptyList(), result.acceptedPaths)
        assertEquals(listOf(BLOCK_A), result.rejectedPaths)
        assertEquals(mapOf(BLOCK_A to "data-patcher-warning"), result.rejectedReasons)
        assertEquals(2, result.rounds.size)
    }

    @Test
    fun `does not blame candidates for a warning located on a runtime base asset`() {
        val harness = FixtureHarness { selected, assets ->
            if (selected.isEmpty()) cleanResult() else failedResult(
                assets = assets,
                path = BASE_ITEM,
                code = "DATA_PATCH_APPLY_WARNING",
                severity = DiagnosticSeverity.WARNING,
                failedAssets = 0,
                warningCount = 1,
                passed = true,
                exitCode = 0,
            )
        }

        val result = select(harness, listOf(BLOCK_A, BLOCK_B))

        assertEquals(MonotonicCandidateSelectionStatus.FALLBACK_TO_RUNTIME_BASE, result.status)
        assertEquals(emptyList(), result.acceptedPaths)
        assertEquals(emptyList(), result.rejectedPaths)
        assertEquals(listOf(BLOCK_A, BLOCK_B), result.unresolvedPaths)
        assertEquals(
            mapOf(BLOCK_A to "unresolved", BLOCK_B to "unresolved"),
            result.unresolvedReasons,
        )
        assertEquals(1, result.unattributedDiagnostics.size)
        assertEquals(BASE_ITEM, relativeDiagnosticPath(result.unattributedDiagnostics.single(), harness.lastAssets))
    }

    @Test
    fun `harness and protocol failures force base fallback instead of deleting an implicated candidate`() {
        val harness = FixtureHarness { selected, assets ->
            if (selected.isEmpty()) {
                cleanResult()
            } else {
                failedResult(assets, BLOCK_A).copy(
                    applyCompleted = false,
                    exitCode = 4,
                    diagnostics = listOf(
                        diagnostic("DATA_PATCH_CONTENT_FAILED", DiagnosticSeverity.ERROR, assets.resolve(BLOCK_A)),
                        diagnostic("DATA_PATCH_PROTOCOL_NOT_STARTED", DiagnosticSeverity.ERROR, assets),
                    ),
                )
            }
        }

        val result = select(harness, listOf(BLOCK_A))

        assertEquals(MonotonicCandidateSelectionStatus.FALLBACK_TO_RUNTIME_BASE, result.status)
        assertEquals(emptyList(), result.rejectedPaths)
        assertEquals(listOf(BLOCK_A), result.unresolvedPaths)
        assertTrue(result.unattributedDiagnostics.any { it.code == "DATA_PATCH_PROTOCOL_NOT_STARTED" })
    }

    @Test
    fun `a non-clean runtime-only base stops before any candidate classification`() {
        val harness = FixtureHarness { selected, assets ->
            check(selected.isEmpty())
            failedResult(assets, BASE_ITEM)
        }

        val result = select(harness, listOf(BLOCK_A))

        assertEquals(MonotonicCandidateSelectionStatus.BASELINE_INVALID, result.status)
        assertEquals(listOf(BLOCK_A), result.unresolvedPaths)
        assertEquals(1, harness.selections.size)
        assertEquals(MonotonicCandidateRoundDecision.BASELINE_INVALID, result.rounds.single().decision)
    }

    @Test
    fun `max rounds preserves an explicit unresolved set and falls back to proven base`() {
        val harness = FixtureHarness { selected, assets ->
            if (selected.isEmpty()) cleanResult() else failedResult(assets, selected.sorted().first())
        }

        val result = select(harness, listOf(BLOCK_A, BLOCK_B), maxRounds = 1)

        assertEquals(MonotonicCandidateSelectionStatus.FALLBACK_TO_RUNTIME_BASE, result.status)
        assertEquals(listOf(BLOCK_A), result.rejectedPaths)
        assertEquals(listOf(BLOCK_B), result.unresolvedPaths)
        assertEquals(mapOf(BLOCK_A to "data-patcher-failure"), result.rejectedReasons)
        assertEquals(mapOf(BLOCK_B to "unresolved"), result.unresolvedReasons)
        assertTrue(result.reason.orEmpty().contains("maxRounds=1"))
    }

    private fun select(
        harness: FixtureHarness,
        candidates: List<String>,
        maxRounds: Int = candidates.size + 1,
    ): MonotonicCandidateSelectionResult = BridgeLogger(temporary.resolve("selector-${System.nanoTime()}.log")).use { logger ->
        MonotonicDataPatchCandidateSelector(harness.materializer, harness.validator).select(
            MonotonicCandidateSelectionRequest(
                candidatePaths = candidates,
                workspace = temporary.resolve("attempts-${System.nanoTime()}"),
                maxRounds = maxRounds,
            ),
            logger,
        )
    }

    private class FixtureHarness(
        private val result: (selected: Set<String>, assets: Path) -> ContentApplyValidationResult,
    ) {
        val selections = mutableListOf<Set<String>>()
        lateinit var lastAssets: Path

        val materializer = DataPatchCandidateMaterializer { selected, attempt ->
            selections += selected.toSet()
            val assets = attempt.resolve("server-assets")
            Files.createDirectories(assets.resolve(BASE_ITEM).parent)
            Files.writeString(assets.resolve(BASE_ITEM), "color: ffffff\n")
            selected.forEach { path ->
                val output = assets.resolve(path)
                Files.createDirectories(output.parent)
                Files.writeString(output, "type: Wall\n")
            }
            lastAssets = assets
            assets
        }

        val validator = DataPatchCandidateValidator { assets, logSink ->
            logSink("fixture apply ${selections.last().size}")
            result(selections.last(), assets)
        }
    }

    private companion object {
        const val BLOCK_A = "content/blocks/a.hjson"
        const val BLOCK_B = "content/blocks/b.hjson"
        const val BLOCK_C = "content/blocks/c.hjson"
        const val BASE_ITEM = "content/items/runtime-base.hjson"

        fun cleanResult(): ContentApplyValidationResult = ContentApplyValidationResult(
            applyCompleted = true,
            passed = true,
            exitCode = 0,
            timedOut = false,
            totalAssets = 1,
            contentAssets = 1,
            patchAssets = 0,
            externalAssets = 0,
            failedAssets = 0,
            warningCount = 0,
            addedContent = 1,
            outputLines = listOf("DPBRIDGE_RESULT fixture-clean"),
            diagnostics = emptyList(),
        )

        fun failedResult(
            assets: Path,
            path: String,
            code: String = "DATA_PATCH_CONTENT_FAILED",
            severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
            failedAssets: Int = 1,
            warningCount: Int = 0,
            passed: Boolean = false,
            exitCode: Int = 10,
        ): ContentApplyValidationResult = ContentApplyValidationResult(
            applyCompleted = true,
            passed = passed,
            exitCode = exitCode,
            timedOut = false,
            totalAssets = 1,
            contentAssets = 1,
            patchAssets = 0,
            externalAssets = 0,
            failedAssets = failedAssets,
            warningCount = warningCount,
            addedContent = 0,
            outputLines = listOf("DPBRIDGE_RESULT fixture-failed"),
            diagnostics = listOf(diagnostic(code, severity, assets.resolve(path))),
        )

        fun diagnostic(code: String, severity: DiagnosticSeverity, path: Path): Diagnostic = Diagnostic(
            code = code,
            severity = severity,
            message = "fixture $code",
            stage = ValidationStage.RUNTIME,
            location = SourceLocation(path.toString()),
        )

        fun relativeDiagnosticPath(diagnostic: Diagnostic, root: Path): String =
            root.relativize(Path.of(diagnostic.location!!.path)).joinToString("/") { it.toString() }
    }
}
