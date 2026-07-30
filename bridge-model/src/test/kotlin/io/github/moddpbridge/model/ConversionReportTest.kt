package io.github.moddpbridge.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConversionReportTest {
    private val target = TargetDescriptor(
        id = "mindustry-v159.7",
        gameVersion = "159.7",
        commit = "c9686eb5d0ae5dd47ee02c40f99f7d5018ccbc8c",
        dataPatchFormatVersion = 2,
    )

    @Test
    fun `report round trips through json`() {
        val report = ConversionReport(
            target = target,
            source = SourceDescriptor(SourceKind.ZIP_ARCHIVE, "sample.zip"),
            status = ConversionStatus.PARTIAL,
            summary = ReportSummary(
                scannedFiles = 2,
                contentFiles = 1,
                assetFiles = 1,
                infoCount = 1,
            ),
            inventory = ConversionInventory(
                scannedFiles = 2,
                contents = listOf(
                    ContentManifestEntry(
                        kind = ContentKind.UNIT,
                        sourcePath = "content/units/test.json",
                        basename = "test",
                        extension = "json",
                        sizeBytes = 42,
                    ),
                ),
                assets = listOf(
                    AssetManifestEntry(
                        kind = AssetKind.SPRITE,
                        sourcePath = "sprites/test.png",
                        basename = "test",
                        extension = "png",
                    ),
                ),
            ),
            fileResults = listOf(
                FileResult(
                    sourcePath = "content/units/test.json",
                    disposition = FileDisposition.COPIED,
                    outputPath = "content/units/test.json",
                ),
            ),
            contentResults = listOf(
                ContentResult(
                    sourceSymbol = "test",
                    kind = ContentKind.UNIT,
                    disposition = ContentDisposition.DEGRADED,
                    sourceType = "CustomUnitType",
                    targetType = "UnitType",
                    outputName = "dp-test",
                    outputPath = "content/units/test.json",
                    reason = "Custom callback omitted.",
                    diagnosticCodes = listOf("CUSTOM_CALLBACK_OMITTED"),
                    location = SourceLocation("src/Test.java", line = 12),
                ),
            ),
            diagnostics = listOf(
                Diagnostic(
                    code = "DP1597_RUNTIME_NOT_VALIDATED",
                    severity = DiagnosticSeverity.INFO,
                    message = "Runtime validation was not run.",
                    stage = ValidationStage.RUNTIME,
                ),
            ),
            validationStages = listOf(
                ValidationStageResult(ValidationStage.STRUCTURE, ValidationStatus.PASSED),
                ValidationStageResult(ValidationStage.RUNTIME, ValidationStatus.NOT_RUN),
            ),
        )

        val encoded = ConversionReportJson.encode(report)
        val decoded = ConversionReportJson.decode(encoded)

        assertEquals(report, decoded)
        assertTrue(encoded.contains("\"gameVersion\": \"159.7\""))
        assertTrue(encoded.contains("\"zipArchive\""))
        assertTrue(encoded.contains("\"copied\""))
        assertTrue(encoded.contains("\"degraded\""))
    }

    @Test
    fun `markdown includes status stages and diagnostics`() {
        val report = ConversionReport(
            target = target,
            source = SourceDescriptor(SourceKind.DIRECTORY, "sample"),
            status = ConversionStatus.REJECTED,
            summary = ReportSummary(errorCount = 1),
            diagnostics = listOf(
                Diagnostic(
                    code = "DP1597_ROOT_UNSUPPORTED",
                    severity = DiagnosticSeverity.ERROR,
                    message = "Unsupported root directory | scripts",
                    location = SourceLocation("scripts/main.js"),
                ),
            ),
            validationStages = listOf(
                ValidationStageResult(
                    ValidationStage.STRUCTURE,
                    ValidationStatus.FAILED,
                    "Invalid root",
                ),
            ),
        )

        val markdown = report.toMarkdown()

        assertTrue(markdown.contains("`REJECTED`"))
        assertTrue(markdown.contains("DP1597_ROOT_UNSUPPORTED"))
        assertTrue(markdown.contains("Unsupported root directory \\| scripts"))
    }

    @Test
    fun `markdown lists every file disposition and its reason`() {
        val report = ConversionReport(
            target = target,
            source = SourceDescriptor(SourceKind.DIRECTORY, "sample"),
            status = ConversionStatus.PARTIAL,
            summary = ReportSummary(scannedFiles = 5),
            fileResults = listOf(
                FileResult(
                    sourcePath = "sprites/copied.png",
                    disposition = FileDisposition.COPIED,
                    outputPath = "sprites/copied.png",
                    reason = "Copied byte-for-byte.",
                ),
                FileResult(
                    sourcePath = "content/units/normalized.hjson",
                    disposition = FileDisposition.CONVERTED,
                    outputPath = "content/units/normalized.hjson",
                    reason = "Normalized text.",
                    diagnosticCodes = listOf("RESEARCH_REMOVED"),
                ),
                FileResult(
                    sourcePath = "maps/ignored.msav",
                    disposition = FileDisposition.EXCLUDED,
                    reason = "Maps are outside product scope.",
                ),
                FileResult(
                    sourcePath = "content/custom/unknown.hjson",
                    disposition = FileDisposition.UNSUPPORTED,
                    reason = "Unsupported content folder | custom.",
                ),
                FileResult(
                    sourcePath = "content/units/broken.hjson",
                    disposition = FileDisposition.FAILED,
                    reason = "Parse failed.",
                ),
            ),
        )

        val markdown = report.toMarkdown()

        FileDisposition.entries.forEach { disposition ->
            assertTrue(markdown.contains("| `${disposition.name}` | 1 |"))
        }
        assertTrue(markdown.contains("### Copied files (1)"))
        assertTrue(markdown.contains("### Converted files (1)"))
        assertTrue(markdown.contains("### Excluded files (1)"))
        assertTrue(markdown.contains("### Unsupported files (1)"))
        assertTrue(markdown.contains("### Failed files (1)"))
        assertTrue(markdown.contains("Unsupported content folder \\| custom."))
        assertTrue(markdown.contains("`RESEARCH_REMOVED`"))
    }

    @Test
    fun `conversion result requires matching status`() {
        val report = ConversionReport(
            target = target,
            source = SourceDescriptor(SourceKind.UNKNOWN, "missing"),
            status = ConversionStatus.REJECTED,
            summary = ReportSummary(),
        )

        assertFailsWith<IllegalArgumentException> {
            ConversionResult(ConversionStatus.SUCCESS, report)
        }
    }

    @Test
    fun `markdown renders declaration level losses and multiple generated outputs`() {
        val report = ConversionReport(
            target = target,
            source = SourceDescriptor(SourceKind.DIRECTORY, "sample"),
            status = ConversionStatus.PARTIAL,
            summary = ReportSummary(degradedContents = 1),
            contentResults = listOf(
                ContentResult(
                    sourceSymbol = "customWall",
                    kind = ContentKind.BLOCK,
                    disposition = ContentDisposition.DEGRADED,
                    sourceType = "ExplodeWall",
                    targetType = "Wall",
                    outputPath = "content/blocks/custom-wall.hjson",
                    reason = "updateTile cannot be represented.",
                    diagnosticCodes = listOf("JAVA_OVERRIDE_OMITTED"),
                    location = SourceLocation("src/Blocks.java", line = 42),
                ),
            ),
            fileResults = listOf(
                FileResult(
                    sourcePath = "src/Blocks.java",
                    disposition = FileDisposition.CONVERTED,
                    outputPaths = listOf(
                        "content/blocks/custom-wall.hjson",
                        "content/blocks/other-wall.hjson",
                    ),
                ),
            ),
        )

        val markdown = report.toMarkdown()

        assertTrue(markdown.contains("ExplodeWall"))
        assertTrue(markdown.contains("src/Blocks.java:42"))
        assertTrue(markdown.contains("JAVA_OVERRIDE_OMITTED"))
        assertTrue(markdown.contains("content/blocks/other-wall.hjson"))
    }
}
