package io.github.moddpbridge.target.v1597

import io.github.moddpbridge.model.AssetKind
import io.github.moddpbridge.model.AssetManifestEntry
import io.github.moddpbridge.model.ContentKind
import io.github.moddpbridge.model.ContentManifestEntry
import io.github.moddpbridge.model.ConversionInventory
import io.github.moddpbridge.model.ConversionReport
import io.github.moddpbridge.model.ConversionResult
import io.github.moddpbridge.model.ConversionStatus
import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.ReportSummary
import io.github.moddpbridge.model.SourceDescriptor
import io.github.moddpbridge.model.SourceKind
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.TargetDescriptor
import io.github.moddpbridge.model.ValidationStage
import io.github.moddpbridge.model.ValidationStageResult
import io.github.moddpbridge.model.ValidationStatus
import io.github.moddpbridge.target.api.TargetValidator
import io.github.moddpbridge.target.api.ValidationOptions
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile

/** Static v159.7 layout validator. It deliberately does not claim runtime or map compatibility. */
class Mindustry1597StructuralValidator : TargetValidator {
    override val target: TargetDescriptor = TargetDescriptor(
        id = "mindustry-v159.7",
        gameVersion = "159.7",
        commit = "c9686eb5d0ae5dd47ee02c40f99f7d5018ccbc8c",
        dataPatchFormatVersion = 2,
        description = "Mindustry v159.7/B480 Data Assets structural profile",
    )

    override fun validate(candidate: Path, options: ValidationOptions): ConversionResult {
        val normalized = candidate.toAbsolutePath().normalize()
        val diagnostics = mutableListOf<Diagnostic>()
        val entries = try {
            readEntries(normalized)
        } catch (error: Exception) {
            diagnostics += diagnostic(
                code = "CANDIDATE_READ_FAILED",
                severity = DiagnosticSeverity.ERROR,
                message = error.message ?: "Failed to read candidate.",
                path = normalized.toString(),
            )
            return result(normalized, emptyList(), diagnostics, structurePassed = false)
        }

        if (entries.isEmpty()) {
            diagnostics += diagnostic(
                "EMPTY_DATA_PACK",
                DiagnosticSeverity.ERROR,
                "The candidate does not contain any files.",
                normalized.toString(),
            )
        }

        val knownMetadata = setOf("__macosx", ".ds_store", "thumbs.db")
        val contentBasenames = mutableMapOf<String, String>()
        val canonicalPaths = mutableMapOf<String, String>()

        entries.forEach { entry ->
            val canonical = entry.path.replace('\\', '/').trimStart('/')
            val lower = canonical.lowercase(Locale.ROOT)
            val segments = canonical.split('/').filter(String::isNotEmpty)
            val root = segments.firstOrNull()?.lowercase(Locale.ROOT).orEmpty()

            if (canonical.contains("../") || canonical == ".." || entry.path.startsWith('/') || DRIVE_PREFIX.matches(entry.path)) {
                diagnostics += diagnostic(
                    "INVALID_DATA_PACK_PATH",
                    DiagnosticSeverity.ERROR,
                    "Archive path escapes or is absolute.",
                    entry.path,
                )
                return@forEach
            }

            canonicalPaths.putIfAbsent(lower, entry.path)?.let { previous ->
                diagnostics += diagnostic(
                    "DUPLICATE_DATA_PACK_PATH",
                    DiagnosticSeverity.ERROR,
                    "Duplicate case-insensitive output path; previous entry: $previous",
                    entry.path,
                )
            }

            if (root in knownMetadata || lower.endsWith("/.ds_store")) {
                if (!options.allowKnownMetadata) {
                    diagnostics += diagnostic(
                        "METADATA_ENTRY_NOT_ALLOWED",
                        DiagnosticSeverity.ERROR,
                        "Archive metadata is not allowed by current validation options.",
                        entry.path,
                    )
                } else {
                    diagnostics += diagnostic(
                        "METADATA_ENTRY_IGNORED",
                        DiagnosticSeverity.WARNING,
                        "Archive/OS metadata should not be included in deployment output.",
                        entry.path,
                    )
                }
                return@forEach
            }

            if (root !in ALLOWED_ROOTS) {
                diagnostics += diagnostic(
                    "UNKNOWN_DATA_PACK_ROOT",
                    if (options.strictUnknownEntries) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                    "Only ${ALLOWED_ROOTS.sorted().joinToString()} are valid data-pack roots.",
                    entry.path,
                )
                return@forEach
            }

            val extension = canonical.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val allowedExtensions = EXTENSIONS.getValue(root)
            if (extension !in allowedExtensions) {
                diagnostics += diagnostic(
                    "UNSUPPORTED_DATA_ASSET_EXTENSION",
                    DiagnosticSeverity.ERROR,
                    "Root '$root' only accepts: ${allowedExtensions.sorted().joinToString()}.",
                    entry.path,
                )
            }

            if (root == "content") {
                if (segments.size < 3) {
                    diagnostics += diagnostic(
                        "INVALID_CONTENT_PATH",
                        DiagnosticSeverity.ERROR,
                        "Content files must use content/<type>/<file>.",
                        entry.path,
                    )
                    return@forEach
                }
                val folder = segments[1].lowercase(Locale.ROOT)
                val kind = ContentKind.fromFolder(folder)
                if (kind == null) {
                    diagnostics += diagnostic(
                        "UNSUPPORTED_CONTENT_FOLDER",
                        DiagnosticSeverity.ERROR,
                        "v159.7 can create only items, blocks, liquids, statuses, units and weather.",
                        entry.path,
                    )
                    return@forEach
                }

                if (options.detectDuplicateBasenames) {
                    val basename = segments.last().substringBeforeLast('.').lowercase(Locale.ROOT)
                    contentBasenames.putIfAbsent(basename, entry.path)?.let { previous ->
                        diagnostics += diagnostic(
                            "DUPLICATE_CONTENT_BASENAME",
                            DiagnosticSeverity.ERROR,
                            "Content basename collides with $previous; content types/directories do not create independent data-asset namespaces.",
                            entry.path,
                        )
                    }
                }
            }
        }

        val hasSupportedAssets = entries.any { entry ->
            entry.path.replace('\\', '/').substringBefore('/').lowercase(Locale.ROOT) in ALLOWED_ROOTS
        }
        if (!hasSupportedAssets) {
            diagnostics += diagnostic(
                "NO_DATA_ASSETS",
                DiagnosticSeverity.ERROR,
                "No supported v159.7 data assets were found.",
                normalized.toString(),
            )
        }

        return result(
            candidate = normalized,
            entries = entries,
            diagnostics = diagnostics,
            structurePassed = diagnostics.none { it.severity == DiagnosticSeverity.ERROR },
        )
    }

    private fun result(
        candidate: Path,
        entries: List<CandidateEntry>,
        diagnostics: List<Diagnostic>,
        structurePassed: Boolean,
    ): ConversionResult {
        val contents = mutableListOf<ContentManifestEntry>()
        val assets = mutableListOf<AssetManifestEntry>()
        entries.forEach { entry ->
            val path = entry.path.replace('\\', '/').trimStart('/')
            val segments = path.split('/').filter(String::isNotEmpty)
            val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            when (segments.firstOrNull()?.lowercase(Locale.ROOT)) {
                "content" -> {
                    val kind = segments.getOrNull(1)?.let(ContentKind::fromFolder) ?: return@forEach
                    contents += ContentManifestEntry(
                        kind = kind,
                        sourcePath = path,
                        basename = segments.last().substringBeforeLast('.'),
                        extension = extension,
                        sizeBytes = entry.size,
                    )
                }

                "patches" -> assets += asset(AssetKind.PATCH, path, extension, entry.size)
                "bundles" -> assets += asset(AssetKind.BUNDLE, path, extension, entry.size)
                "sprites" -> assets += asset(AssetKind.SPRITE, path, extension, entry.size)
                "sounds" -> assets += asset(AssetKind.SOUND, path, extension, entry.size)
                "music" -> assets += asset(AssetKind.MUSIC, path, extension, entry.size)
            }
        }

        val inventory = ConversionInventory(
            scannedFiles = entries.size,
            contents = contents,
            assets = assets,
        )
        val status = if (structurePassed) ConversionStatus.PARTIAL else ConversionStatus.REJECTED
        val report = ConversionReport(
            toolVersion = "0.1.0-SNAPSHOT",
            target = target,
            source = SourceDescriptor(
                kind = if (Files.isDirectory(candidate)) SourceKind.DIRECTORY else SourceKind.DATA_PACK_ARCHIVE,
                name = candidate.fileName?.toString() ?: candidate.toString(),
                path = candidate.toString(),
                sizeBytes = if (Files.isRegularFile(candidate)) Files.size(candidate) else null,
            ),
            status = status,
            summary = ReportSummary(
                scannedFiles = entries.size,
                contentFiles = contents.size,
                assetFiles = assets.size,
                infoCount = diagnostics.count { it.severity == DiagnosticSeverity.INFO },
                warningCount = diagnostics.count { it.severity == DiagnosticSeverity.WARNING },
                errorCount = diagnostics.count { it.severity == DiagnosticSeverity.ERROR },
            ),
            inventory = inventory,
            diagnostics = diagnostics,
            validationStages = listOf(
                ValidationStageResult(
                    ValidationStage.STRUCTURE,
                    if (structurePassed) ValidationStatus.PASSED else ValidationStatus.FAILED,
                    if (structurePassed) "v159.7 directory and extension checks passed." else "Structural validation failed.",
                    diagnostics.map { it.code }.distinct(),
                ),
                ValidationStageResult(ValidationStage.RUNTIME, ValidationStatus.NOT_RUN, "Real Mindustry runtime not invoked."),
                ValidationStageResult(ValidationStage.MAP_IMPORT, ValidationStatus.NOT_RUN, "Desktop editor import not invoked."),
                ValidationStageResult(ValidationStage.SERVER_LOAD, ValidationStatus.NOT_RUN, "Server cold-load not invoked."),
            ),
        )
        return ConversionResult(status, report)
    }

    private fun readEntries(candidate: Path): List<CandidateEntry> {
        require(Files.exists(candidate)) { "Candidate does not exist: $candidate" }
        if (Files.isDirectory(candidate)) {
            return Files.walk(candidate).use { stream ->
                stream.filter(Files::isRegularFile)
                    .map { file -> CandidateEntry(candidate.relativize(file).toString(), Files.size(file)) }
                    .toList()
            }
        }

        require(Files.isRegularFile(candidate)) { "Candidate is neither a directory nor a regular file: $candidate" }
        return ZipFile(candidate.toFile()).use { zip ->
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { CandidateEntry(it.name, it.size.coerceAtLeast(0L)) }
                .toList()
        }
    }

    private fun asset(kind: AssetKind, path: String, extension: String, size: Long): AssetManifestEntry =
        AssetManifestEntry(
            kind = kind,
            sourcePath = path,
            basename = path.substringAfterLast('/').substringBeforeLast('.'),
            extension = extension,
            sizeBytes = size,
        )

    private fun diagnostic(
        code: String,
        severity: DiagnosticSeverity,
        message: String,
        path: String,
    ): Diagnostic = Diagnostic(
        code = code,
        severity = severity,
        message = message,
        stage = ValidationStage.STRUCTURE,
        location = SourceLocation(path),
    )

    private data class CandidateEntry(val path: String, val size: Long)

    private companion object {
        val ALLOWED_ROOTS = setOf("patches", "content", "bundles", "sprites", "sounds", "music")
        val EXTENSIONS = mapOf(
            "patches" to setOf("json", "hjson", "json5"),
            "content" to setOf("json", "hjson", "json5"),
            "bundles" to setOf("properties"),
            "sprites" to setOf("png"),
            "sounds" to setOf("mp3", "ogg"),
            "music" to setOf("mp3", "ogg"),
        )
        val DRIVE_PREFIX = Regex("^[A-Za-z]:[/\\\\].*")
    }
}
