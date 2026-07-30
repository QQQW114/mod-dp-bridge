package io.github.moddpbridge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Physical form of the source supplied to the bridge. */
@Serializable
enum class SourceKind {
    @SerialName("directory")
    DIRECTORY,

    @SerialName("zipArchive")
    ZIP_ARCHIVE,

    @SerialName("modArchive")
    MOD_ARCHIVE,

    @SerialName("dataPackArchive")
    DATA_PACK_ARCHIVE,

    @SerialName("legacyCp")
    LEGACY_CP,

    @SerialName("unknown")
    UNKNOWN,
}

/** Top-level content kinds supported by Mindustry v159.7 data content. */
@Serializable
enum class ContentKind(val folderName: String) {
    @SerialName("item")
    ITEM("items"),

    @SerialName("block")
    BLOCK("blocks"),

    @SerialName("liquid")
    LIQUID("liquids"),

    @SerialName("status")
    STATUS("statuses"),

    @SerialName("unit")
    UNIT("units"),

    @SerialName("weather")
    WEATHER("weather");

    companion object {
        fun fromFolder(folder: String): ContentKind? =
            entries.firstOrNull { it.folderName == folder }
    }
}

/** Data-asset categories understood by the conversion report. */
@Serializable
enum class AssetKind {
    @SerialName("patch")
    PATCH,

    @SerialName("content")
    CONTENT,

    @SerialName("bundle")
    BUNDLE,

    @SerialName("sprite")
    SPRITE,

    @SerialName("sound")
    SOUND,

    @SerialName("music")
    MUSIC,

    @SerialName("other")
    OTHER,
}

@Serializable
enum class DiagnosticSeverity {
    @SerialName("info")
    INFO,

    @SerialName("warning")
    WARNING,

    @SerialName("error")
    ERROR,
}

/** Lifecycle state of an individual diagnostic. */
@Serializable
enum class DiagnosticStatus {
    @SerialName("active")
    ACTIVE,

    @SerialName("acknowledged")
    ACKNOWLEDGED,

    @SerialName("resolved")
    RESOLVED,

    @SerialName("suppressed")
    SUPPRESSED,
}

/** Overall result. SUCCESS is reserved for results that completed every required validation stage. */
@Serializable
enum class ConversionStatus {
    @SerialName("success")
    SUCCESS,

    @SerialName("partial")
    PARTIAL,

    @SerialName("rejected")
    REJECTED,

    @SerialName("failed")
    FAILED,
}

@Serializable
enum class ValidationStage {
    @SerialName("structure")
    STRUCTURE,

    @SerialName("runtime")
    RUNTIME,

    @SerialName("mapImport")
    MAP_IMPORT,

    @SerialName("serverLoad")
    SERVER_LOAD,
}

@Serializable
enum class ValidationStatus {
    @SerialName("passed")
    PASSED,

    @SerialName("failed")
    FAILED,

    @SerialName("notRun")
    NOT_RUN,
}

@Serializable
enum class OutputArtifactKind {
    @SerialName("dataPackZip")
    DATA_PACK_ZIP,

    @SerialName("serverAssets")
    SERVER_ASSETS,

    @SerialName("reportJson")
    REPORT_JSON,

    @SerialName("reportMarkdown")
    REPORT_MARKDOWN,

    @SerialName("other")
    OTHER,
}

/** File-level outcome emitted by conversion stages. */
@Serializable
enum class FileDisposition {
    @SerialName("copied")
    COPIED,

    /** Rewritten or normalized rather than copied byte-for-byte. */
    @SerialName("converted")
    CONVERTED,

    @SerialName("excluded")
    EXCLUDED,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("failed")
    FAILED,
}

/**
 * Outcome of converting one logical content declaration. Unlike [FileDisposition], this is
 * declaration-level: one Java source file may define hundreds of blocks, units or weapons and
 * each declaration needs an independently auditable result.
 */
@Serializable
enum class ContentDisposition {
    @SerialName("converted")
    CONVERTED,

    /** A loadable data asset was emitted, but one or more source behaviours were approximated. */
    @SerialName("degraded")
    DEGRADED,

    @SerialName("excluded")
    EXCLUDED,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class SourceDescriptor(
    val kind: SourceKind,
    val name: String,
    /** Host path is optional because reports may be published by a website. */
    val path: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

@Serializable
data class SourceLocation(
    val path: String,
    val jsonPath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

@Serializable
data class ContentManifestEntry(
    val kind: ContentKind,
    val sourcePath: String,
    val basename: String,
    val extension: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    /** Filled by later conversion stages once dp- namespace planning has happened. */
    val outputName: String? = null,
)

@Serializable
data class AssetManifestEntry(
    val kind: AssetKind,
    val sourcePath: String,
    val basename: String,
    val extension: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val outputPath: String? = null,
)

@Serializable
data class IgnoredSourceEntry(
    val sourcePath: String,
    val reason: String,
)

/**
 * Auditable disposition for one source file. This is intentionally separate from the
 * manifest: excluded, unsupported and failed files never become manifest entries.
 */
@Serializable
data class FileResult(
    val sourcePath: String,
    val disposition: FileDisposition,
    val outputPath: String? = null,
    /** All generated files when one source file expands into multiple data assets. */
    val outputPaths: List<String> = outputPath?.let(::listOf) ?: emptyList(),
    val reason: String? = null,
    val diagnosticCodes: List<String> = emptyList(),
)

/** Auditable result for a logical content declaration discovered in declarative data or code. */
@Serializable
data class ContentResult(
    /** Stable source-side symbol/name used to identify the declaration in reports. */
    val sourceSymbol: String,
    val kind: ContentKind? = null,
    val disposition: ContentDisposition,
    val sourceType: String? = null,
    val targetType: String? = null,
    val outputName: String? = null,
    val outputPath: String? = null,
    val reason: String? = null,
    val diagnosticCodes: List<String> = emptyList(),
    val location: SourceLocation? = null,
)

@Serializable
data class ConversionInventory(
    val scannedFiles: Int = 0,
    val contents: List<ContentManifestEntry> = emptyList(),
    val assets: List<AssetManifestEntry> = emptyList(),
    val ignored: List<IgnoredSourceEntry> = emptyList(),
)

@Serializable
data class Diagnostic(
    val code: String,
    val severity: DiagnosticSeverity,
    val message: String,
    val status: DiagnosticStatus = DiagnosticStatus.ACTIVE,
    val stage: ValidationStage? = null,
    val location: SourceLocation? = null,
    val details: String? = null,
    val suggestion: String? = null,
)

@Serializable
data class ValidationStageResult(
    val stage: ValidationStage,
    val status: ValidationStatus,
    val summary: String? = null,
    val diagnosticCodes: List<String> = emptyList(),
)

@Serializable
data class TargetDescriptor(
    val id: String,
    val gameVersion: String,
    val commit: String? = null,
    val dataPatchFormatVersion: Int? = null,
    val description: String? = null,
)

@Serializable
data class ReportSummary(
    val scannedFiles: Int = 0,
    val contentFiles: Int = 0,
    val assetFiles: Int = 0,
    val infoCount: Int = 0,
    val warningCount: Int = 0,
    val errorCount: Int = 0,
    val convertedContents: Int = 0,
    val degradedContents: Int = 0,
    val excludedContents: Int = 0,
    val unsupportedContents: Int = 0,
    val failedContents: Int = 0,
)

@Serializable
data class OutputArtifact(
    val kind: OutputArtifactKind,
    val path: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

@Serializable
data class ConversionReport(
    val schemaVersion: Int = 1,
    val toolVersion: String? = null,
    val generatedAt: String? = null,
    val target: TargetDescriptor,
    val source: SourceDescriptor,
    val status: ConversionStatus,
    val summary: ReportSummary,
    val inventory: ConversionInventory = ConversionInventory(),
    val fileResults: List<FileResult> = emptyList(),
    val contentResults: List<ContentResult> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    val validationStages: List<ValidationStageResult> = emptyList(),
    val outputs: List<OutputArtifact> = emptyList(),
    /** Extensible, serialization-friendly values for website/CLI presentation. */
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class ConversionResult(
    val status: ConversionStatus,
    val report: ConversionReport,
    val artifacts: List<OutputArtifact> = report.outputs,
) {
    init {
        require(status == report.status) {
            "ConversionResult status ($status) must equal report status (${report.status})."
        }
    }
}
