package io.github.moddpbridge.javastatic.hybrid

import io.github.moddpbridge.converter.ConvertedFileStatus
import io.github.moddpbridge.converter.RuntimePreparedContentResult
import io.github.moddpbridge.converter.RuntimePreparedConversion
import io.github.moddpbridge.converter.RuntimePreparedFile
import io.github.moddpbridge.converter.RuntimePreparedFileResult
import io.github.moddpbridge.converter.StaticExportResult
import io.github.moddpbridge.converter.StaticGeneratedFile
import io.github.moddpbridge.converter.StaticSourceOutcome
import io.github.moddpbridge.converter.StaticOutputNamespace
import io.github.moddpbridge.model.ContentDisposition
import io.github.moddpbridge.model.ContentKind
import io.github.moddpbridge.model.ContentResult
import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeStaticHybridCoreTest {
    @Test
    fun `only runtime registered same-kind fallback-matched content becomes a candidate`() {
        val runtime = runtimePrepared()
        val snapshotLocation = SourceLocation("runtime-snapshot.json", "$.contents[1]")
        val static = StaticExportResult(
            generatedFiles = listOf(
                generated("content/blocks/test-wall.hjson", "src/example/Contents.java", "Wall"),
                generated("content/blocks/source-only.hjson", "src/example/Contents.java", "Wall"),
                generated("content/blocks/wrong-fallback.hjson", "src/example/Contents.java", "GenericCrafter"),
            ),
            contentResults = listOf(
                staticResult("Contents.testWall", "content/blocks/test-wall.hjson", ContentKind.BLOCK, "Wall"),
                staticResult("Contents.sourceOnly", "content/blocks/source-only.hjson", ContentKind.BLOCK, "Wall"),
                staticResult("Contents.wrongFallback", "content/blocks/wrong-fallback.hjson", ContentKind.BLOCK, "GenericCrafter"),
            ),
            sourceOutcomes = listOf(
                StaticSourceOutcome(
                    "src/example/Contents.java",
                    ConvertedFileStatus.NORMALIZED,
                    "parsed",
                    listOf("content/blocks/test-wall.hjson"),
                ),
            ),
            diagnostics = listOf(
                Diagnostic(
                    code = "JAVA_FIELD_EXPRESSION_OMITTED",
                    severity = DiagnosticSeverity.WARNING,
                    message = "One callback was omitted.",
                    stage = ValidationStage.STRUCTURE,
                    location = SourceLocation("src/example/Contents.java", line = 12),
                ),
            ),
        )

        val set = RuntimeStaticHybridCore.buildCandidates(
            runtimePrepared = runtime,
            runtimeContents = listOf(
                HybridRuntimeContent(
                    "fixture-test-wall",
                    "test-wall",
                    ContentKind.BLOCK,
                    "Wall",
                    snapshotLocation,
                ),
                HybridRuntimeContent(
                    "fixture-wrong-fallback",
                    "wrong-fallback",
                    ContentKind.BLOCK,
                    "Wall",
                    SourceLocation("runtime-snapshot.json", "$.contents[2]"),
                ),
            ),
            staticExport = static,
            sourceClassProvenance = listOf(
                HybridSourceClassProvenance(
                    sourcePath = "src/example/Contents.java",
                    jarPath = "example/Contents.class",
                    sourceCoversRuntimeLines = true,
                    runtimeLineNumbers = listOf(12),
                ),
            ),
        )

        assertEquals(setOf("content/blocks/test-wall.hjson"), set.candidatePaths)
        assertEquals(1, set.summary.acceptedCandidates)
        assertEquals(2, set.summary.rejectedCandidates)
        assertTrue(set.rejected.any { "HYBRID_RUNTIME_REGISTRATION_MISSING" in it.diagnosticCodes })
        assertTrue(set.rejected.any { "HYBRID_FALLBACK_TYPE_MISMATCH" in it.diagnosticCodes })

        val filtered = set.materialize(
            acceptedPaths = emptySet(),
            decisions = mapOf(
                "content/blocks/test-wall.hjson" to HybridCandidateDecision(
                    HybridCandidateDisposition.REJECTED_WARNING,
                    "Official DataPatcher emitted a warning for this candidate.",
                    listOf("DATA_PATCHER_WARNING"),
                ),
            ),
        )
        val filteredResult = filtered.contentResults.single { it.sourceSymbol == "fixture-test-wall" }
        assertEquals("Official DataPatcher emitted a warning for this candidate.", filteredResult.reason)
        assertTrue("HYBRID_CANDIDATE_REJECTED_WARNING" in filteredResult.diagnosticCodes)
        assertTrue(filtered.files.none { it.outputPath == "content/blocks/test-wall.hjson" })

        val failedCandidate = set.materialize(
            acceptedPaths = emptySet(),
            decisions = mapOf(
                "content/blocks/test-wall.hjson" to HybridCandidateDecision(
                    HybridCandidateDisposition.REJECTED_FAILURE,
                    "Official DataPatcher rejected this optional candidate.",
                ),
            ),
        )
        assertEquals(
            DiagnosticSeverity.WARNING,
            failedCandidate.diagnostics.single { it.code == "HYBRID_CANDIDATE_REJECTED_FAILURE" }.severity,
        )

        val originalAsset = runtime.files.single { it.outputPath == "sprites/test-wall.png" }.bytes
        val prepared = set.materialize(set.candidatePaths)
        assertEquals(3, prepared.files.size)
        assertContentEquals(
            originalAsset,
            prepared.files.single { it.outputPath == "sprites/test-wall.png" }.bytes,
        )
        assertEquals(
            listOf("example/Contents.class"),
            prepared.files.single { it.outputPath == "content/blocks/test-wall.hjson" }.sourcePaths,
        )
        val classResult = prepared.fileResults.single { it.sourcePath == "example/Contents.class" }
        assertEquals(ConvertedFileStatus.UNSUPPORTED, classResult.status)
        assertEquals(listOf("content/blocks/test-wall.hjson"), classResult.outputPaths)
        assertTrue("HYBRID_EXECUTABLE_BEHAVIOR_UNMIGRATED" in classResult.diagnosticCodes)
        val result = prepared.contentResults.single { it.sourceSymbol == "fixture-test-wall" }
        assertEquals(ContentDisposition.DEGRADED, result.disposition)
        assertEquals("Wall", result.targetType)
        assertTrue("HYBRID_STATIC_CANDIDATE_UNVALIDATED" in result.diagnosticCodes)
        assertFalse(prepared.diagnostics.any {
            it.code == "RUNTIME_CONTENT_TYPE_NOT_MAPPED" && it.location == snapshotLocation
        })
        assertTrue(prepared.files.none { it.outputPath == "content/blocks/source-only.hjson" })
    }

    @Test
    fun `unit fallback mismatch requires explicit template replacement and remains degraded`() {
        val runtime = runtimePrepared(
            extraContent = RuntimePreparedContentResult(
                sourceSymbol = "fixture-origin",
                kind = ContentKind.UNIT,
                disposition = ContentDisposition.UNSUPPORTED,
                sourceType = "unit",
                reason = "not mapped",
                diagnosticCodes = listOf("RUNTIME_CONTENT_TYPE_NOT_MAPPED"),
                location = SourceLocation("runtime-snapshot.json", "$.contents[3]"),
            ),
        )
        val static = StaticExportResult(
            generatedFiles = listOf(
                StaticGeneratedFile(
                    outputPath = "content/units/origin.hjson",
                    bytes = """{"template":"NHUnitType","speed":1}""".toByteArray(),
                    sourcePaths = listOf("src/example/Units.java"),
                    namespace = StaticOutputNamespace.SOURCE,
                ),
            ),
            contentResults = listOf(
                staticResult("Units.origin", "content/units/origin.hjson", ContentKind.UNIT, "NHUnitType"),
            ),
            sourceOutcomes = listOf(
                StaticSourceOutcome(
                    "src/example/Units.java",
                    ConvertedFileStatus.NORMALIZED,
                    "parsed",
                    listOf("content/units/origin.hjson"),
                ),
            ),
        )
        val observed = listOf(
            HybridRuntimeContent(
                "fixture-origin",
                "origin",
                ContentKind.UNIT,
                "UnitType",
                SourceLocation("runtime-snapshot.json", "$.contents[3]"),
            ),
        )
        val provenance = listOf(
            HybridSourceClassProvenance(
                "src/example/Units.java",
                "example/Units.class",
                true,
                listOf(12),
            ),
        )

        val rejected = RuntimeStaticHybridCore.buildCandidates(runtime, observed, static, provenance)
        assertTrue(rejected.candidates.isEmpty())
        assertTrue(rejected.rejected.any { "HYBRID_FALLBACK_TYPE_MISMATCH" in it.diagnosticCodes })

        val accepted = RuntimeStaticHybridCore.buildCandidates(
            runtime,
            observed,
            static,
            provenance,
            HybridCandidateOptions(setOf("content/units/origin.hjson")),
        )
        assertEquals(1, accepted.candidates.size)
        val bytes = accepted.candidates.single().bytes.decodeToString()
        assertTrue("\"template\": \"UnitType\"" in bytes)
        assertFalse("NHUnitType" in bytes)

        val prepared = accepted.materialize(
            accepted.candidatePaths,
            HybridValidationState.DATA_PATCHER_CLEAN,
        )
        val result = prepared.contentResults.single { it.sourceSymbol == "fixture-origin" }
        assertEquals(ContentDisposition.DEGRADED, result.disposition)
        assertTrue("HYBRID_DATA_PATCHER_CLEAN" in result.diagnosticCodes)
        assertTrue("HYBRID_UNIT_TEMPLATE_REPLACED" in result.diagnosticCodes)
    }

    @Test
    fun `candidate declaration line must occur in a linked release class line table`() {
        val static = StaticExportResult(
            generatedFiles = listOf(generated("content/blocks/test-wall.hjson", "src/example/Contents.java", "Wall")),
            sourceOutcomes = listOf(
                StaticSourceOutcome("src/example/Contents.java", ConvertedFileStatus.NORMALIZED, "parsed"),
            ),
            contentResults = listOf(
                staticResult("Contents.testWall", "content/blocks/test-wall.hjson", ContentKind.BLOCK, "Wall"),
            ),
        )
        val set = RuntimeStaticHybridCore.buildCandidates(
            runtimePrepared(),
            listOf(
                HybridRuntimeContent(
                    "fixture-test-wall",
                    "test-wall",
                    ContentKind.BLOCK,
                    "Wall",
                    SourceLocation("runtime-snapshot.json", "$.contents[1]"),
                ),
            ),
            static,
            listOf(
                HybridSourceClassProvenance(
                    "src/example/Contents.java",
                    "example/Contents.class",
                    true,
                    listOf(99),
                ),
            ),
        )

        assertTrue(set.candidates.isEmpty())
        assertTrue(set.rejected.any { "HYBRID_JAR_LINE_PROVENANCE_MISSING" in it.diagnosticCodes })
    }

    @Test
    fun `failed partial AST source is rejected and its error diagnostic is retained`() {
        val parseError = Diagnostic(
            code = "JAVA_SOURCE_PARSE_ERROR",
            severity = DiagnosticSeverity.ERROR,
            message = "broken source",
            stage = ValidationStage.STRUCTURE,
            location = SourceLocation("src/example/Contents.java", line = 3),
        )
        val static = StaticExportResult(
            generatedFiles = listOf(generated("content/blocks/test-wall.hjson", "src/example/Contents.java", "Wall")),
            sourceOutcomes = listOf(
                StaticSourceOutcome("src/example/Contents.java", ConvertedFileStatus.FAILED, "parse failed"),
            ),
            contentResults = listOf(
                staticResult("Contents.testWall", "content/blocks/test-wall.hjson", ContentKind.BLOCK, "Wall"),
            ),
            diagnostics = listOf(parseError),
        )
        val set = RuntimeStaticHybridCore.buildCandidates(
            runtimePrepared(),
            listOf(
                HybridRuntimeContent(
                    "fixture-test-wall",
                    "test-wall",
                    ContentKind.BLOCK,
                    "Wall",
                    SourceLocation("runtime-snapshot.json", "$.contents[1]"),
                ),
            ),
            static,
            listOf(
                HybridSourceClassProvenance(
                    "src/example/Contents.java",
                    "example/Contents.class",
                    true,
                    listOf(12),
                ),
            ),
        )

        assertTrue(set.candidates.isEmpty())
        assertTrue(set.rejected.any { "HYBRID_SOURCE_PARSE_FAILED" in it.diagnosticCodes })
        assertTrue(set.diagnostics.any { it.code == "JAVA_SOURCE_PARSE_ERROR" })
    }

    @Test
    fun `materialize rejects unknown candidate paths`() {
        val set = RuntimeStaticHybridCore.buildCandidates(
            runtimePrepared(),
            emptyList(),
            StaticExportResult(),
            emptyList(),
        )
        assertFailsWith<IllegalArgumentException> {
            set.materialize(setOf("content/blocks/not-a-candidate.hjson"))
        }
    }

    private fun runtimePrepared(
        extraContent: RuntimePreparedContentResult? = null,
    ): RuntimePreparedConversion {
        val blockLocation = SourceLocation("runtime-snapshot.json", "$.contents[1]")
        return RuntimePreparedConversion(
            files = listOf(
                RuntimePreparedFile(
                    outputPath = "content/items/runtime-item.hjson",
                    sourcePaths = listOf("example/Items.class"),
                    bytes = "{\"color\":\"ffffff\"}".toByteArray(),
                ),
                RuntimePreparedFile(
                    outputPath = "sprites/test-wall.png",
                    sourcePaths = listOf("sprites/test-wall.png"),
                    bytes = null,
                ),
            ),
            fileResults = listOf(
                RuntimePreparedFileResult("example/Items.class", ConvertedFileStatus.NORMALIZED, "runtime"),
                RuntimePreparedFileResult("example/Contents.class", ConvertedFileStatus.UNSUPPORTED, "runtime"),
                RuntimePreparedFileResult("example/Units.class", ConvertedFileStatus.UNSUPPORTED, "runtime"),
            ),
            contentResults = listOfNotNull(
                RuntimePreparedContentResult(
                    sourceSymbol = "fixture-runtime-item",
                    kind = ContentKind.ITEM,
                    disposition = ContentDisposition.CONVERTED,
                    sourceType = "item",
                    targetType = "Item",
                    outputPath = "content/items/runtime-item.hjson",
                ),
                RuntimePreparedContentResult(
                    sourceSymbol = "fixture-test-wall",
                    kind = ContentKind.BLOCK,
                    disposition = ContentDisposition.UNSUPPORTED,
                    sourceType = "block",
                    reason = "not mapped",
                    diagnosticCodes = listOf("RUNTIME_CONTENT_TYPE_NOT_MAPPED"),
                    location = blockLocation,
                ),
                RuntimePreparedContentResult(
                    sourceSymbol = "fixture-wrong-fallback",
                    kind = ContentKind.BLOCK,
                    disposition = ContentDisposition.UNSUPPORTED,
                    sourceType = "block",
                    reason = "not mapped",
                    diagnosticCodes = listOf("RUNTIME_CONTENT_TYPE_NOT_MAPPED"),
                    location = SourceLocation("runtime-snapshot.json", "$.contents[2]"),
                ),
                extraContent,
            ),
            diagnostics = listOf(
                Diagnostic(
                    code = "RUNTIME_CONTENT_TYPE_NOT_MAPPED",
                    severity = DiagnosticSeverity.WARNING,
                    message = "not mapped",
                    stage = ValidationStage.STRUCTURE,
                    location = blockLocation,
                ),
            ),
        )
    }

    private fun generated(path: String, source: String, type: String) = StaticGeneratedFile(
        outputPath = path,
        bytes = "{\"type\":\"$type\"}".toByteArray(),
        sourcePaths = listOf(source),
        namespace = StaticOutputNamespace.SOURCE,
    )

    private fun staticResult(
        symbol: String,
        path: String,
        kind: ContentKind,
        targetType: String,
    ) = ContentResult(
        sourceSymbol = symbol,
        kind = kind,
        disposition = ContentDisposition.DEGRADED,
        sourceType = targetType,
        targetType = targetType,
        outputName = "dp-${path.substringAfterLast('/').substringBeforeLast('.')}",
        outputPath = path,
        reason = "Static approximation.",
        diagnosticCodes = listOf("JAVA_FIELD_EXPRESSION_OMITTED"),
        location = SourceLocation(
            if (kind == ContentKind.UNIT) "src/example/Units.java" else "src/example/Contents.java",
            line = 12,
        ),
    )
}
