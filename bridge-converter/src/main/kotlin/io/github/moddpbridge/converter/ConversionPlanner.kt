package io.github.moddpbridge.converter

import io.github.moddpbridge.model.AssetKind
import io.github.moddpbridge.model.AssetManifestEntry
import io.github.moddpbridge.model.ContentKind
import io.github.moddpbridge.model.ContentManifestEntry
import io.github.moddpbridge.model.ContentResult
import io.github.moddpbridge.model.ConversionInventory
import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.IgnoredSourceEntry
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

internal data class PlannedOutputFile(
    val path: String,
    val bytes: ByteArray,
    val sourcePath: String,
    val status: ConvertedFileStatus,
)

internal data class ConversionPlan(
    val slug: String,
    val outputFiles: List<PlannedOutputFile>,
    val convertedFiles: List<ConvertedFile>,
    val inventory: ConversionInventory,
    val normalizedTextFiles: Int,
    val contentResults: List<ContentResult> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

private data class Candidate(
    val entry: SourceEntry,
    val outputPath: String,
    val priority: Int,
    val contentKind: ContentKind? = null,
    val assetKind: AssetKind? = null,
    val conversionReason: String? = null,
    val staticGenerated: Boolean = false,
    val outputNamespace: StaticOutputNamespace = StaticOutputNamespace.SOURCE,
)

private data class RewrittenOutputPath(
    val path: String,
    val reason: String? = null,
)

private data class EnvironmentCompatibilityResult(
    val candidates: List<Candidate>,
    val reroutedCount: Int = 0,
    val omittedOptionalCount: Int = 0,
    val reducedVariantSpriteCount: Int = 0,
    val rewrittenVariantContentCount: Int = 0,
    val oreAliasCount: Int = 0,
    val reservedCount: Int = 0,
    val reservedRawPixels: Long = 0,
    val reservedPaddedPixels: Long = 0,
    val variantOverrides: Map<String, Int> = emptyMap(),
)

private data class EnvironmentBlockSpec(
    val candidate: Candidate,
    val basename: String,
    val type: String,
    val variants: Int,
    val targetVariants: Int,
    val autotile: Boolean,
    val autotileVariants: Int,
    val autotileMidVariants: Int,
    val tilingVariants: Int,
    val maxSize: Int,
    val itemDrop: String?,
)

internal data class EmittedContent(
    val kind: ContentKind,
    val basename: String,
    val bytes: ByteArray,
    val sourcePath: String,
)

internal object ConversionPlanner {
    private const val B480_ENVIRONMENT_ATLAS_PIXELS = 700L * 700L
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
    private val CONTENT_REGENERATION_SENTINEL_PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4////fwAJ+wP9KobjigAAAABJRU5ErkJggg==",
    )
    private val textExtensions = setOf("json", "hjson", "json5")
    private val excludedContentFolders = setOf("planet", "planets", "sector", "sectors")
    private val excludedRoots = mapOf(
        "maps" to ("MAPS_EXCLUDED" to "Maps are not data assets and were excluded."),
        "scripts" to ("SCRIPTS_EXCLUDED" to "Scripts are not executed or copied by the static converter."),
        "sprites-override" to (
            "SPRITES_OVERRIDE_EXCLUDED" to
                "sprites-override cannot preserve global atlas replacement semantics in a data pack."
        ),
    )

    fun plan(
        snapshot: SourceSnapshot,
        detection: SourceDetection,
        staticExport: StaticExportAggregate,
        diagnostics: MutableList<Diagnostic>,
        logger: ConverterLogger,
    ): ConversionPlan = when (detection.kind) {
        DetectedSourceKind.LEGACY_CP -> planLegacyCp(snapshot, detection, diagnostics, logger)
        DetectedSourceKind.MOD,
        DetectedSourceKind.DATA_PACK -> planAssetTree(snapshot, detection, staticExport, diagnostics, logger)
    }

    /** Applies the same source-Mod path rule used while scanning an ordinary sprites tree. */
    internal fun applyPreparedModAssetPathRules(
        outputPath: String,
        modNamespace: String?,
    ): Pair<String, String?> {
        val normalized = outputPath.replace('\\', '/').trim('/')
        if (!normalized.substringBefore('/').equals("sprites", ignoreCase = true)) {
            return normalized to null
        }
        val rewritten = rewriteGeneratedSpritePath(normalized, modNamespace)
        return rewritten.path to rewritten.reason
    }

    private fun planLegacyCp(
        snapshot: SourceSnapshot,
        detection: SourceDetection,
        diagnostics: MutableList<Diagnostic>,
        logger: ConverterLogger,
    ): ConversionPlan {
        val entry = detection.legacyCpEntry
            ?: fail("LEGACY_CP_MISSING", "The legacy CP source file could not be located.", diagnostics)
        val normalized = normalizeLegacyCpText(entry, diagnostics)
        val outputPath = "patches/${detection.slug}.hjson"
        val planned = PlannedOutputFile(outputPath, normalized.bytes, entry.path, ConvertedFileStatus.NORMALIZED)
        diagnostics += Diagnostic(
            code = "LEGACY_CP_WRAPPED",
            severity = DiagnosticSeverity.INFO,
            message = "The legacy CP PatchSet was normalized and wrapped as a v159 patch asset.",
            stage = ValidationStage.STRUCTURE,
            location = SourceLocation(entry.path),
            details = outputPath,
        )
        logger.log("Wrapped legacy CP as $outputPath")
        val manifest = AssetManifestEntry(
            kind = AssetKind.PATCH,
            sourcePath = entry.path,
            basename = detection.slug,
            extension = "hjson",
            sizeBytes = normalized.bytes.size.toLong(),
            sha256 = sha256(normalized.bytes),
            outputPath = outputPath,
        )
        return ConversionPlan(
            slug = detection.slug,
            outputFiles = listOf(planned),
            convertedFiles = listOf(
                ConvertedFile(
                    sourcePath = entry.path,
                    outputPath = outputPath,
                    status = ConvertedFileStatus.NORMALIZED,
                    sizeBytes = normalized.bytes.size.toLong(),
                    sha256 = sha256(normalized.bytes),
                ),
            ),
            inventory = ConversionInventory(
                scannedFiles = snapshot.entries.size,
                assets = listOf(manifest),
            ),
            normalizedTextFiles = 1,
        )
    }

    private fun planAssetTree(
        snapshot: SourceSnapshot,
        detection: SourceDetection,
        staticExport: StaticExportAggregate,
        diagnostics: MutableList<Diagnostic>,
        logger: ConverterLogger,
    ): ConversionPlan {
        val ignored = mutableListOf<IgnoredSourceEntry>()
        val converted = mutableListOf<ConvertedFile>()
        val candidates = mutableListOf<Candidate>()
        val excludedCounts = linkedMapOf<String, Int>()
        val unsupportedExtensions = linkedMapOf<String, Int>()

        snapshot.entries.sortedBy { it.path }.forEach { entry ->
            // A static exporter owns the final file-level disposition for claimed code files.
            if (entry.path in staticExport.sourceOutcomes) return@forEach
            val fromAssets = detection.kind == DetectedSourceKind.MOD &&
                entry.path.substringBefore('/').equals("assets", ignoreCase = true)
            val assetPath = if (fromAssets) entry.path.substringAfter('/', "") else entry.path
            if (assetPath.isBlank()) {
                ignore(entry, "Empty assets path", ignored, converted)
                return@forEach
            }
            val root = assetPath.substringBefore('/').lowercase(Locale.ROOT)

            excludedRoots[root]?.let { (code, reason) ->
                ignore(entry, reason, ignored, converted)
                excludedCounts[code] = (excludedCounts[code] ?: 0) + 1
                return@forEach
            }

            if (root !in SourceDetector.supportedRoots) {
                if (entry.path.equals("mod.hjson", true) || entry.path.equals("mod.json", true)) {
                    ignore(
                        entry,
                        "Mod metadata was used for identification but is not part of a data pack.",
                        ignored,
                        converted,
                    )
                } else {
                    ignore(
                        entry,
                        "File is outside supported Data Assets directories.",
                        ignored,
                        converted,
                        status = ConvertedFileStatus.UNSUPPORTED,
                    )
                }
                return@forEach
            }

            val parts = assetPath.split('/')
            val canonicalAssetPath = root + if (parts.size > 1) "/" + parts.drop(1).joinToString("/") else ""
            when (root) {
                "content" -> {
                    if (parts.size < 3) {
                        ignore(
                            entry,
                            "Content file is missing its content-type subdirectory.",
                            ignored,
                            converted,
                            status = ConvertedFileStatus.UNSUPPORTED,
                        )
                        return@forEach
                    }
                    val folder = parts[1].lowercase(Locale.ROOT)
                    if (folder in excludedContentFolders) {
                        val code = if (folder.startsWith("planet")) "PLANET_CONTENT_EXCLUDED" else "SECTOR_CONTENT_EXCLUDED"
                        val reason = "Top-level $folder content is not loadable by v159.7 Data Assets."
                        ignore(entry, reason, ignored, converted)
                        excludedCounts[code] = (excludedCounts[code] ?: 0) + 1
                        return@forEach
                    }
                    val kind = ContentKind.fromFolder(folder)
                    if (kind == null) {
                        ignore(
                            entry,
                            "Unsupported top-level content folder: $folder",
                            ignored,
                            converted,
                            status = ConvertedFileStatus.UNSUPPORTED,
                        )
                        excludedCounts["UNSUPPORTED_CONTENT_FOLDER"] =
                            (excludedCounts["UNSUPPORTED_CONTENT_FOLDER"] ?: 0) + 1
                        return@forEach
                    }
                    if (entry.extension !in textExtensions) {
                        ignore(
                            entry,
                            "Unsupported content extension: .${entry.extension}",
                            ignored,
                            converted,
                            status = ConvertedFileStatus.UNSUPPORTED,
                        )
                        unsupportedExtensions[entry.extension] = (unsupportedExtensions[entry.extension] ?: 0) + 1
                        return@forEach
                    }
                    candidates += Candidate(
                        entry = entry,
                        outputPath = "content/${kind.folderName}/${parts.drop(2).joinToString("/")}",
                        priority = if (fromAssets) 0 else 1,
                        contentKind = kind,
                    )
                }

                "patches" -> addAssetCandidate(
                    entry,
                    canonicalAssetPath,
                    fromAssets,
                    AssetKind.PATCH,
                    textExtensions,
                    candidates,
                    ignored,
                    converted,
                    unsupportedExtensions,
                )

                "bundles" -> addAssetCandidate(
                    entry,
                    canonicalAssetPath,
                    fromAssets,
                    AssetKind.BUNDLE,
                    setOf("properties"),
                    candidates,
                    ignored,
                    converted,
                    unsupportedExtensions,
                )

                "sprites" -> {
                    val rewrittenPath = if (detection.kind == DetectedSourceKind.MOD) {
                        rewriteGeneratedSpritePath(canonicalAssetPath, detection.modNamespace)
                    } else {
                        RewrittenOutputPath(canonicalAssetPath)
                    }
                    addAssetCandidate(
                        entry,
                        rewrittenPath.path,
                        fromAssets,
                        AssetKind.SPRITE,
                        setOf("png"),
                        candidates,
                        ignored,
                        converted,
                        unsupportedExtensions,
                        conversionReason = rewrittenPath.reason,
                    )
                }

                "sounds" -> addAssetCandidate(
                    entry,
                    canonicalAssetPath,
                    fromAssets,
                    AssetKind.SOUND,
                    setOf("mp3", "ogg"),
                    candidates,
                    ignored,
                    converted,
                    unsupportedExtensions,
                )

                "music" -> addAssetCandidate(
                    entry,
                    canonicalAssetPath,
                    fromAssets,
                    AssetKind.MUSIC,
                    setOf("mp3", "ogg"),
                    candidates,
                    ignored,
                    converted,
                    unsupportedExtensions,
                )
            }
        }

        staticExport.generatedFiles.sortedBy { it.outputPath }.forEach { generated ->
            candidates += staticGeneratedCandidate(generated, snapshot, diagnostics)
        }

        excludedCounts.forEach { (code, count) ->
            val message = when (code) {
                "MAPS_EXCLUDED" -> excludedRoots.getValue("maps").second
                "SCRIPTS_EXCLUDED" -> excludedRoots.getValue("scripts").second
                "SPRITES_OVERRIDE_EXCLUDED" -> excludedRoots.getValue("sprites-override").second
                "PLANET_CONTENT_EXCLUDED" -> "Planet content was excluded."
                "SECTOR_CONTENT_EXCLUDED" -> "Sector content was excluded."
                else -> "Unsupported content files were excluded."
            }
            diagnostics += Diagnostic(
                code = code,
                severity = DiagnosticSeverity.WARNING,
                message = message,
                stage = ValidationStage.STRUCTURE,
                details = "$count file(s)",
            )
        }
        unsupportedExtensions.forEach { (extension, count) ->
            diagnostics += Diagnostic(
                code = "UNSUPPORTED_ASSET_EXTENSION",
                severity = DiagnosticSeverity.WARNING,
                message = "Unsupported files were excluded from supported asset directories.",
                stage = ValidationStage.STRUCTURE,
                details = ".${extension.ifBlank { "<none>" }}: $count file(s)",
            )
        }

        val mirrorSideNormalized = normalizeMirroredSpriteSideCase(candidates, detection, diagnostics)
        val pathSelected = resolveOutputPathCollisions(mirrorSideNormalized, ignored, converted, diagnostics)
        val deduplicated = deduplicateIdenticalSpriteNames(pathSelected, ignored, converted, diagnostics)
        val environmentCompatibility = applyB480EnvironmentCompatibility(
            candidates = deduplicated,
            detection = detection,
            ignored = ignored,
            converted = converted,
            diagnostics = diagnostics,
        )
        val selected = environmentCompatibility.candidates
        addAudioContainerDiagnostics(selected, diagnostics)
        val namespaceSymbols = if (detection.kind == DetectedSourceKind.MOD) {
            buildNamespaceSymbols(
                sourceNamespace = detection.modNamespace
                    ?: fail("MOD_NAMESPACE_MISSING", "The original mod namespace could not be determined.", diagnostics),
                candidates = selected,
            )
        } else {
            null
        }
        val targetAssetIndex = buildTargetAssetIndex(selected)
        val outputFiles = mutableListOf<PlannedOutputFile>()
        val contents = mutableListOf<ContentManifestEntry>()
        val assets = mutableListOf<AssetManifestEntry>()
        val emittedContents = mutableListOf<EmittedContent>()
        var normalizedCount = 0
        var namespaceRewriteCount = 0

        selected.sortedBy { it.outputPath }.forEach { candidate ->
            val isText = candidate.contentKind != null || candidate.assetKind == AssetKind.PATCH
            val outputBytes: ByteArray
            val status: ConvertedFileStatus
            var conversionReason = candidate.conversionReason
            if (candidate.conversionReason != null && !candidate.staticGenerated) {
                diagnostics += Diagnostic(
                    code = "SPRITE_NAMESPACE_PATH_REWRITTEN",
                    severity = DiagnosticSeverity.INFO,
                    message = candidate.conversionReason,
                    stage = ValidationStage.STRUCTURE,
                    location = SourceLocation(candidate.entry.path),
                    details = candidate.outputPath,
                )
            }
            if (isText && detection.kind == DetectedSourceKind.DATA_PACK) {
                // ContentAsset hashes are derived from exact text. Reformatting an existing DP can
                // invalidate sprites/generated/<type>_<hash>/ paths in the editor.
                val parsed = try {
                    HjsonNormalizer.parse(candidate.entry.bytes, candidate.entry.path)
                } catch (error: ConversionException) {
                    fail(
                        "TEXT_PARSE_FAILED",
                        error.message ?: "Text parsing failed",
                        diagnostics,
                        SourceLocation(candidate.entry.path),
                        error,
                    )
                }
                addAssetReferenceDiagnostics(
                    candidate.entry.path,
                    AssetReferenceValidator.validate(parsed, targetAssetIndex),
                    diagnostics,
                )
                outputBytes = candidate.entry.bytes
                status = ConvertedFileStatus.COPIED
                conversionReason = "Preserved byte-for-byte so existing generated-asset hashes remain valid."
            } else if (isText) {
                val normalized = normalizeText(candidate.entry, diagnostics)
                val variantOverride = environmentCompatibility.variantOverrides[
                    candidate.outputPath.lowercase(Locale.ROOT)
                ]
                if (variantOverride != null && candidate.contentKind == ContentKind.BLOCK && normalized.root.isObject) {
                    normalized.root.asObject().set("variants", variantOverride)
                    conversionReason = "Normalized text and reduced cached-terrain variants to $variantOverride for B480 atlas compatibility."
                }
                val rewrite = when {
                    namespaceSymbols == null || candidate.outputNamespace == StaticOutputNamespace.TARGET -> null
                    candidate.contentKind != null -> ModNamespaceRewriter.rewriteContent(normalized.root, namespaceSymbols)
                    candidate.assetKind == AssetKind.PATCH -> ModNamespaceRewriter.rewritePatch(normalized.root, namespaceSymbols)
                    else -> null
                }
                if (rewrite != null) {
                    namespaceRewriteCount += rewrite.rewrites.size
                    addNamespaceDiagnostics(candidate.entry.path, rewrite, diagnostics)
                    if (rewrite.rewrites.isNotEmpty()) {
                        conversionReason = "Normalized text and rewrote ${rewrite.rewrites.size} mod namespace reference(s)."
                    }
                }
                addAssetReferenceDiagnostics(
                    candidate.entry.path,
                    AssetReferenceValidator.validate(normalized.root, targetAssetIndex),
                    diagnostics,
                )
                outputBytes = if (
                    rewrite?.rewrites?.isNotEmpty() == true ||
                    variantOverride != null
                ) {
                    HjsonNormalizer.render(normalized.root)
                } else {
                    normalized.bytes
                }
                status = ConvertedFileStatus.NORMALIZED
                normalizedCount++
            } else if (
                candidate.assetKind == AssetKind.BUNDLE &&
                namespaceSymbols != null &&
                candidate.outputNamespace != StaticOutputNamespace.TARGET
            ) {
                val rewrite = ModNamespaceRewriter.rewriteBundle(candidate.entry.bytes, namespaceSymbols)
                namespaceRewriteCount += rewrite.rewrites.size
                if (rewrite.rewrites.isNotEmpty()) {
                    addBundleNamespaceDiagnostic(candidate.entry.path, rewrite.rewrites, diagnostics)
                    outputBytes = rewrite.bytes
                    status = ConvertedFileStatus.NORMALIZED
                    normalizedCount++
                    conversionReason = "Rewrote ${rewrite.rewrites.size} bundle key(s) to the dp namespace."
                } else {
                    outputBytes = candidate.entry.bytes
                    status = candidate.conversionReason?.let { ConvertedFileStatus.NORMALIZED } ?: ConvertedFileStatus.COPIED
                }
            } else {
                outputBytes = candidate.entry.bytes
                status = candidate.conversionReason?.let { ConvertedFileStatus.NORMALIZED } ?: ConvertedFileStatus.COPIED
            }
            outputFiles += PlannedOutputFile(candidate.outputPath, outputBytes, candidate.entry.path, status)
            if (!candidate.staticGenerated) {
                converted += ConvertedFile(
                    sourcePath = candidate.entry.path,
                    outputPath = candidate.outputPath,
                    status = status,
                    reason = conversionReason,
                    sizeBytes = outputBytes.size.toLong(),
                    sha256 = sha256(outputBytes),
                )
            }
            val basename = candidate.outputPath.substringAfterLast('/').substringBeforeLast('.')
            val extension = candidate.outputPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (candidate.contentKind != null) {
                emittedContents += EmittedContent(candidate.contentKind, basename, outputBytes, candidate.entry.path)
                contents += ContentManifestEntry(
                    kind = candidate.contentKind,
                    sourcePath = candidate.entry.path,
                    basename = basename,
                    extension = extension,
                    sizeBytes = outputBytes.size.toLong(),
                    sha256 = sha256(outputBytes),
                    outputName = "dp-$basename",
                )
            } else {
                assets += AssetManifestEntry(
                    kind = candidate.assetKind ?: AssetKind.OTHER,
                    sourcePath = candidate.entry.path,
                    basename = basename,
                    extension = extension,
                    sizeBytes = outputBytes.size.toLong(),
                    sha256 = sha256(outputBytes),
                    outputPath = candidate.outputPath,
                )
            }
        }

        val offlineSprites = if (detection.kind == DetectedSourceKind.MOD) {
            addOfflineContentSprites(outputFiles, assets, emittedContents, diagnostics)
        } else {
            OfflineContentSpriteResult()
        }
        val regenerationSentinels = if (detection.kind == DetectedSourceKind.MOD) {
            addContentRegenerationSentinels(outputFiles, assets, emittedContents, diagnostics)
        } else {
            0
        }


        staticExport.sourceOutcomes.values.sortedBy { it.sourcePath }.forEach { outcome ->
            converted += ConvertedFile(
                sourcePath = outcome.sourcePath,
                outputPath = outcome.outputPaths.singleOrNull(),
                outputPaths = outcome.outputPaths.sorted(),
                status = outcome.status,
                reason = outcome.reason,
                diagnosticCodes = outcome.diagnosticCodes,
            )
        }

        validateDataAssetNames(outputFiles, diagnostics)
        validateContentIconClosure(selected, targetAssetIndex.spriteTargets, diagnostics)
        if (namespaceSymbols != null) {
            diagnostics += Diagnostic(
                code = "MOD_NAMESPACE_MIGRATED",
                severity = DiagnosticSeverity.INFO,
                message = "Original mod namespace '${namespaceSymbols.sourceNamespace}' was migrated to v159's fixed 'dp' namespace.",
                stage = ValidationStage.STRUCTURE,
                details = "$namespaceRewriteCount explicit reference(s) rewritten.",
            )
        }
        logger.log(
            "Planned ${outputFiles.size} output files: ${contents.size} content, ${assets.size} other assets, " +
                "${ignored.size} excluded.",
        )
        val unclaimedExecutable = snapshot.entries.filter(::looksExecutable)
            .filterNot { it.path in staticExport.sourceOutcomes }
        if (detection.kind == DetectedSourceKind.MOD && unclaimedExecutable.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "MOD_CODE_NOT_EXECUTED",
                severity = DiagnosticSeverity.ERROR,
                message = "Some Java/JS/Kotlin code was not handled by a deterministic static exporter.",
                stage = ValidationStage.STRUCTURE,
                details = unclaimedExecutable.take(20).joinToString { it.path } +
                    if (unclaimedExecutable.size > 20) " ... (${unclaimedExecutable.size} total)" else "",
                suggestion = "Use a compatible static exporter or manually port the listed code-defined gameplay content.",
            )
        }
        if (detection.kind == DetectedSourceKind.DATA_PACK) {
            diagnostics += Diagnostic(
                code = "DATA_PACK_REPACKAGED",
                severity = DiagnosticSeverity.INFO,
                message = "The existing v159 data pack was safely validated and deterministically repackaged.",
                stage = ValidationStage.STRUCTURE,
            )
            diagnostics += Diagnostic(
                code = "DATA_PACK_TEXT_PRESERVED",
                severity = DiagnosticSeverity.INFO,
                message = "Existing content/patch text was preserved byte-for-byte to keep generated sprite hashes valid.",
                stage = ValidationStage.STRUCTURE,
            )
        }

        return ConversionPlan(
            slug = detection.slug,
            outputFiles = outputFiles.sortedBy { it.path },
            convertedFiles = converted.sortedWith(compareBy<ConvertedFile> { it.sourcePath }.thenBy { it.outputPath ?: "" }),
            inventory = ConversionInventory(
                scannedFiles = snapshot.entries.size,
                contents = contents.sortedBy { it.sourcePath },
                assets = assets.sortedBy { it.sourcePath },
                ignored = ignored.sortedBy { it.sourcePath },
            ),
            normalizedTextFiles = normalizedCount,
            contentResults = staticExport.contentResults.sortedWith(
                compareBy<ContentResult> { it.location?.path.orEmpty() }
                    .thenBy { it.location?.line ?: Int.MAX_VALUE }
                    .thenBy { it.sourceSymbol },
            ),
            metadata = staticExport.metadata + mapOf(
                "b480EnvironmentReroutedSprites" to environmentCompatibility.reroutedCount.toString(),
                "b480EnvironmentOptionalSpritesOmitted" to environmentCompatibility.omittedOptionalCount.toString(),
                "b480EnvironmentReducedVariantSprites" to environmentCompatibility.reducedVariantSpriteCount.toString(),
                "b480EnvironmentRewrittenVariantContents" to environmentCompatibility.rewrittenVariantContentCount.toString(),
                "b480EnvironmentOreAliases" to environmentCompatibility.oreAliasCount.toString(),
                "b480EnvironmentReservedSprites" to environmentCompatibility.reservedCount.toString(),
                "b480EnvironmentReservedRawPixels" to environmentCompatibility.reservedRawPixels.toString(),
                "b480EnvironmentReservedPaddedPixels" to environmentCompatibility.reservedPaddedPixels.toString(),
                "b480OfflineGeneratedSprites" to offlineSprites.files.size.toString(),
                "b480OfflineGeneratedFullIcons" to offlineSprites.fullIcons.toString(),
                "b480OfflineGeneratedOutlines" to offlineSprites.outlines.toString(),
                "b480OfflineIconGenerationMisses" to offlineSprites.misses.size.toString(),
                "b480ContentRegenerationSentinels" to regenerationSentinels.toString(),
            ),
        )
    }

    /**
     * B480 gives imported environment sprites one fixed 700x700 region and silently publishes only
     * the first PixmapPacker page. Keep only regions read while drawing cached terrain, reduce
     * ordinary terrain variants deterministically, and synchronize the affected block HJSON. This
     * intentionally preserves all 47 runtime autotile bitmask regions while dropping offline source
     * sheets and optional StaticWall mosaics.
     */
    private fun applyB480EnvironmentCompatibility(
        candidates: List<Candidate>,
        detection: SourceDetection,
        ignored: MutableList<IgnoredSourceEntry>,
        converted: MutableList<ConvertedFile>,
        diagnostics: MutableList<Diagnostic>,
    ): EnvironmentCompatibilityResult {
        if (detection.kind != DetectedSourceKind.MOD) return EnvironmentCompatibilityResult(candidates)

        val cachedEnvironmentTypes = setOf(
            "Floor",
            "ColoredFloor",
            "EmptyFloor",
            "OverlayFloor",
            "ShallowLiquid",
            "SteamVent",
            "TiledFloor",
            "OreBlock",
            "Cliff",
            "StaticWall",
            "ColoredWall",
            "StaticTree",
            "TiledWall",
        )
        val staticWallTypes = setOf("StaticWall", "ColoredWall", "StaticTree", "TiledWall")
        val variantManagedTypes = setOf(
            "Floor",
            "ColoredFloor",
            "EmptyFloor",
            "OverlayFloor",
            "ShallowLiquid",
            "SteamVent",
            "TiledFloor",
            "OreBlock",
            "StaticWall",
            "ColoredWall",
            "StaticTree",
            "TiledWall",
        )
        val parsedBlockSpecs = candidates.asSequence()
            .filter { it.contentKind == ContentKind.BLOCK }
            .map { candidate ->
                val basename = candidate.outputPath.substringAfterLast('/').substringBeforeLast('.')
                    .lowercase(Locale.ROOT)
                val root = try {
                    HjsonNormalizer.parse(candidate.entry.bytes, candidate.entry.path)
                } catch (error: ConversionException) {
                    fail(
                        "TEXT_PARSE_FAILED",
                        error.message ?: "Text parsing failed",
                        diagnostics,
                        SourceLocation(candidate.entry.path),
                        error,
                    )
                }
                val objectValue = if (root.isObject) root.asObject() else null
                val rawType = objectValue?.getString("type", "Block") ?: "Block"
                val type = rawType.substringAfterLast('.').substringAfterLast('$')
                val defaultVariants = when (type) {
                    "Floor", "ColoredFloor", "OverlayFloor", "ShallowLiquid", "TiledFloor" -> 3
                    "SteamVent" -> 2
                    "OreBlock" -> 3
                    "StaticWall", "ColoredWall", "TiledWall" -> 2
                    "EmptyFloor", "StaticTree" -> 0
                    else -> 0
                }
                val variants = (objectValue?.getInt("variants", defaultVariants) ?: defaultVariants).coerceAtLeast(0)
                val autotile = objectValue?.getBoolean("autotile", false) ?: false
                val targetVariants = when {
                    autotile -> 0
                    type == "SteamVent" -> variants.coerceAtMost(1)
                    type in variantManagedTypes -> variants.coerceAtMost(2)
                    else -> variants
                }
                EnvironmentBlockSpec(
                    candidate = candidate,
                    basename = basename,
                    type = type,
                    variants = variants,
                    targetVariants = targetVariants,
                    autotile = autotile,
                    autotileVariants = (objectValue?.getInt("autotileVariants", 1) ?: 1).coerceAtLeast(1),
                    autotileMidVariants = (objectValue?.getInt("autotileMidVariants", 1) ?: 1).coerceAtLeast(1),
                    tilingVariants = (objectValue?.getInt("tilingVariants", 0) ?: 0).coerceAtLeast(0),
                    maxSize = (objectValue?.getInt("maxSize", 3) ?: 3).coerceAtLeast(1),
                    itemDrop = objectValue?.getString("itemDrop", null),
                )
            }
            .associateBy { it.basename }
        val blockSpecs = parsedBlockSpecs.mapValues { (_, spec) ->
            val hasLargeStandardVariant = !spec.autotile && spec.targetVariants > 1 && candidates.asSequence()
                .filter { it.assetKind == AssetKind.SPRITE }
                .filter { standardVariantIndex(spriteBasename(it), spec.basename) != null }
                .mapNotNull { pngDimensions(it.entry.bytes) }
                .any { (width, height) -> width > 32 || height > 32 }
            if (hasLargeStandardVariant) spec.copy(targetVariants = 1) else spec
        }
        val variantOverrides = blockSpecs.values.asSequence()
            .filter { !it.autotile && it.targetVariants < it.variants }
            .associate { it.candidate.outputPath.lowercase(Locale.ROOT) to it.targetVariants }
        val environmentPrefix = "sprites/blocks/environment/"
        val adjusted = mutableListOf<Candidate>()
        val rerouted = mutableListOf<Candidate>()
        val unmatchedRerouted = mutableListOf<Candidate>()
        val omittedOptional = mutableListOf<Candidate>()
        val omittedTools = mutableListOf<Candidate>()
        val reducedVariants = mutableListOf<Candidate>()

        candidates.forEach { candidate ->
            if (candidate.assetKind != AssetKind.SPRITE ||
                !candidate.outputPath.startsWith(environmentPrefix, ignoreCase = true)
            ) {
                adjusted += candidate
                return@forEach
            }

            val basename = candidate.outputPath.substringAfterLast('/').substringBeforeLast('.')
                .lowercase(Locale.ROOT)
            val owner = blockSpecs.values.asSequence()
                .filter { spriteBelongsToContent(basename, it.basename) }
                .maxByOrNull { it.basename.length }
            val ownerType = owner?.type
            val toolSheet = basename.endsWith("-autotile") || basename.endsWith("-tiled")

            if (toolSheet) {
                val reason =
                    "Offline autotile/tiled source sheet was omitted from the runtime data pack; " +
                        "its generated bitmask/tile regions are handled separately."
                omittedTools += candidate
                ignore(candidate.entry, reason, ignored, converted)
                return@forEach
            }

            if (ownerType in staticWallTypes && basename == "${owner?.basename}-large") {
                val reason =
                    "Optional StaticWall large-tile sprite was omitted to fit B480's fixed 700x700 environment atlas; " +
                        "the ordinary wall variants remain available."
                omittedOptional += candidate
                ignore(candidate.entry, reason, ignored, converted)
                return@forEach
            }

            val numberedVariant = owner?.let { standardVariantIndex(basename, it.basename) }
            if (owner != null && ownerType in variantManagedTypes && !owner.autotile && numberedVariant != null &&
                numberedVariant !in 1..owner.targetVariants
            ) {
                val reason =
                    "Environment variant $numberedVariant was omitted after reducing '${owner.basename}' from " +
                        "${owner.variants} to ${owner.targetVariants} variant(s) for B480 atlas compatibility."
                reducedVariants += candidate
                ignore(candidate.entry, reason, ignored, converted)
                return@forEach
            }

            val mustUseEnvironmentTexture = owner != null && ownerType in cachedEnvironmentTypes &&
                isRuntimeEnvironmentSprite(basename, owner)
            if (mustUseEnvironmentTexture) {
                adjusted += candidate
            } else {
                val relative = candidate.outputPath.substring(environmentPrefix.length)
                val reason = when {
                    ownerType != null ->
                        "Moved a $ownerType sprite that was stored under blocks/environment into the normal patch atlas; " +
                            "its atlas basename is unchanged."

                    else ->
                        "Moved an environment-folder sprite with no converted cached-terrain owner into the normal patch atlas; " +
                            "its atlas basename is unchanged."
                }
                val rewritten = candidate.copy(
                    outputPath = "sprites/dpbridge-main/environment/$relative",
                    conversionReason = listOfNotNull(candidate.conversionReason, reason).joinToString(" "),
                )
                adjusted += rewritten
                rerouted += candidate
                if (ownerType == null && !toolSheet) unmatchedRerouted += candidate
            }
        }

        val oreAliases = mutableListOf<Candidate>()
        val missingOreAliases = mutableListOf<String>()
        blockSpecs.values.asSequence()
            .filter { it.type == "OreBlock" && it.targetVariants > 0 }
            .sortedBy { it.basename }
            .forEach { ore ->
                val itemDrop = ore.itemDrop?.let { itemDropBasename(it, detection.modNamespace) }
                for (variant in 1..ore.targetVariants) {
                    val targetBasename = "${ore.basename}$variant"
                    var exactGeneratedSource: Candidate? = null
                    val existingIndex = adjusted.indexOfFirst {
                        it.assetKind == AssetKind.SPRITE && spriteBasename(it) == targetBasename
                    }
                    if (existingIndex >= 0) {
                        val existing = adjusted[existingIndex]
                        if (existing.outputPath.startsWith(environmentPrefix, ignoreCase = true)) continue
                        if (existing.outputPath.startsWith("sprites/generated/", ignoreCase = true)) {
                            exactGeneratedSource = existing
                        } else {
                            val reason =
                                "Moved the runtime OreBlock region '$targetBasename' into B480's environment atlas."
                            adjusted[existingIndex] = existing.copy(
                                outputPath = "${environmentPrefix}ores/$targetBasename.png",
                                conversionReason = listOfNotNull(existing.conversionReason, reason).joinToString(" "),
                            )
                            continue
                        }
                    }

                    val fallbackBasename = itemDrop?.let { "$it$variant" }
                    val source = exactGeneratedSource ?: fallbackBasename?.let { fallback ->
                        candidates.asSequence()
                            .filter { it.assetKind == AssetKind.SPRITE && spriteBasename(it) == fallback }
                            .sortedWith(
                                compareByDescending<Candidate> {
                                    it.outputPath.startsWith(environmentPrefix, ignoreCase = true)
                                }.thenBy { it.outputPath },
                            )
                            .firstOrNull()
                    }
                    if (source == null) {
                        missingOreAliases += "$targetBasename <- ${fallbackBasename ?: "<no itemDrop>"}"
                        continue
                    }

                    val alias = Candidate(
                        entry = SourceEntry(
                            path = "<bridge-generated:ore-alias/$targetBasename.png>",
                            bytes = source.entry.bytes,
                        ),
                        outputPath = "${environmentPrefix}ores/$targetBasename.png",
                        priority = 0,
                        assetKind = AssetKind.SPRITE,
                        conversionReason =
                            if (exactGeneratedSource != null) {
                                "Copied generated OreBlock sprite '$targetBasename' into B480's environment atlas."
                            } else {
                                "Copied legacy itemDrop-named ore sprite '$fallbackBasename' to runtime OreBlock name '$targetBasename'."
                            },
                        staticGenerated = true,
                        outputNamespace = StaticOutputNamespace.TARGET,
                    )
                    adjusted += alias
                    oreAliases += alias
                }
            }

        if (rerouted.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "B480_ENVIRONMENT_SPRITES_REROUTED",
                severity = DiagnosticSeverity.WARNING,
                message = "Sprites not rendered through Mindustry's cached terrain texture were moved out of the fixed B480 environment atlas.",
                stage = ValidationStage.STRUCTURE,
                details = "${rerouted.size} sprite(s); basenames were preserved.",
                suggestion = "Retest converted terrain and ordinary map buildings in the exact v159.7/B480 Desktop client.",
            )
        }
        if (unmatchedRerouted.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "B480_UNMATCHED_ENVIRONMENT_SPRITES_REROUTED",
                severity = DiagnosticSeverity.WARNING,
                message = "Environment-folder sprites with no converted cached-terrain owner were moved to the normal patch atlas.",
                stage = ValidationStage.STRUCTURE,
                details = unmatchedRerouted.take(20).joinToString { it.entry.path } +
                    if (unmatchedRerouted.size > 20) " ... (${unmatchedRerouted.size} total)" else "",
                suggestion = "If custom omitted Java logic referenced one of these as cached terrain, move that PNG back under sprites/blocks/environment manually.",
            )
        }
        if (omittedOptional.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "B480_OPTIONAL_STATIC_WALL_LARGE_OMITTED",
                severity = DiagnosticSeverity.WARNING,
                message = "Optional StaticWall large mosaics were omitted to stay within B480's single environment-atlas page.",
                stage = ValidationStage.STRUCTURE,
                details = omittedOptional.joinToString { it.entry.path },
                suggestion = "Walls still use their ordinary variants; restore a -large sprite only if the final environment atlas remains one 700x700 page.",
            )
        }
        if (omittedTools.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "B480_ENVIRONMENT_TOOL_SPRITES_OMITTED",
                severity = DiagnosticSeverity.INFO,
                message = "Offline terrain-generation source sheets were omitted from the runtime data pack.",
                stage = ValidationStage.STRUCTURE,
                details = omittedTools.joinToString { it.entry.path },
            )
        }
        if (reducedVariants.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "B480_ENVIRONMENT_VARIANTS_REDUCED",
                severity = DiagnosticSeverity.WARNING,
                message = "Cached-terrain variant counts and their HJSON declarations were reduced to fit B480's single environment-atlas page.",
                stage = ValidationStage.STRUCTURE,
                details = reducedVariants.take(30).joinToString { it.entry.path } +
                    if (reducedVariants.size > 30) " ... (${reducedVariants.size} total)" else "",
                suggestion = "Visual variety is reduced, but all retained variants remain valid runtime atlas regions.",
            )
        }
        if (oreAliases.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "B480_ORE_RUNTIME_ALIASES_ADDED",
                severity = DiagnosticSeverity.WARNING,
                message = "Ore sprites were copied into the environment atlas under the runtime names required when icon regeneration is bypassed.",
                stage = ValidationStage.STRUCTURE,
                details = oreAliases.joinToString { it.outputPath },
            )
        }
        if (missingOreAliases.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "B480_ORE_RUNTIME_ALIAS_MISSING",
                severity = DiagnosticSeverity.ERROR,
                message = "Some OreBlock runtime variants have neither an exact sprite nor a legacy itemDrop-named fallback to alias.",
                stage = ValidationStage.STRUCTURE,
                details = missingOreAliases.joinToString(),
                suggestion = "Add the listed ore PNGs manually before client import; missing cached-terrain regions render as env-error.",
            )
        }

        val reserved = adjusted.filter {
            it.assetKind == AssetKind.SPRITE &&
                it.outputPath.startsWith(environmentPrefix, ignoreCase = true)
        }
        var rawPixels = 0L
        var paddedPixels = 0L
        reserved.forEach { candidate ->
            pngDimensions(candidate.entry.bytes)?.let { (width, height) ->
                rawPixels += width.toLong() * height
                paddedPixels += (width.toLong() + 2L) * (height.toLong() + 2L)
            }
        }
        if (rawPixels > B480_ENVIRONMENT_ATLAS_PIXELS) {
            fail(
                code = "B480_ENVIRONMENT_ATLAS_CAPACITY_EXCEEDED",
                message = "Required cached-terrain sprites need $rawPixels raw pixels, exceeding B480's fixed " +
                    "700x700 (${B480_ENVIRONMENT_ATLAS_PIXELS}) environment atlas before padding.",
                diagnostics = diagnostics,
                suggestion = "Remove/degrade optional terrain variants or split the gameplay content; moving cached Floor/Ore/StaticWall sprites to the main atlas renders env-error.",
            )
        }
        diagnostics += Diagnostic(
            code = "B480_ENVIRONMENT_ATLAS_BUDGET",
            severity = if (paddedPixels > B480_ENVIRONMENT_ATLAS_PIXELS) {
                DiagnosticSeverity.WARNING
            } else {
                DiagnosticSeverity.INFO
            },
            message = "B480 environment-atlas budget was calculated after compatibility routing.",
            stage = ValidationStage.STRUCTURE,
            details = "sprites=${reserved.size}, rawPixels=$rawPixels, paddedAreaEstimate=$paddedPixels, capacity=$B480_ENVIRONMENT_ATLAS_PIXELS",
            suggestion = if (paddedPixels > B480_ENVIRONMENT_ATLAS_PIXELS) {
                "Exact Guillotine packing may still exceed one page; verify with the B480 Desktop atlas packer before publishing."
            } else {
                null
            },
        )

        return EnvironmentCompatibilityResult(
            candidates = adjusted,
            reroutedCount = rerouted.size,
            omittedOptionalCount = omittedOptional.size + omittedTools.size,
            reducedVariantSpriteCount = reducedVariants.size,
            rewrittenVariantContentCount = variantOverrides.size,
            oreAliasCount = oreAliases.size,
            reservedCount = reserved.size,
            reservedRawPixels = rawPixels,
            reservedPaddedPixels = paddedPixels,
            variantOverrides = variantOverrides,
        )
    }

    private fun isRuntimeEnvironmentSprite(sprite: String, owner: EnvironmentBlockSpec): Boolean {
        if (sprite == "${owner.basename}-edge") return true

        if (owner.autotile) {
            val suffix = sprite.removePrefix("${owner.basename}-")
            if (suffix.length < sprite.length) {
                val parts = suffix.split('-')
                if (owner.autotileVariants <= 1 && parts.size == 1) {
                    val bitmask = parts[0].toIntOrNull()
                    if (bitmask != null && bitmask in 0..46) return true
                }
                if (owner.autotileVariants > 1 && parts.size == 2) {
                    val variant = parts[0].toIntOrNull()
                    val bitmask = parts[1].toIntOrNull()
                    if (variant != null && variant in 1..owner.autotileVariants &&
                        bitmask != null && bitmask in 0..46
                    ) {
                        return true
                    }
                }
                if (owner.autotileMidVariants > 1) {
                    if (suffix == "13") return true
                    if (parts.size == 2 && parts[0] == "mid") {
                        val variant = parts[1].toIntOrNull()
                        if (variant != null && variant in 2..owner.autotileMidVariants) return true
                    }
                }
            }
            return false
        }

        if (owner.tilingVariants > 0) {
            val tileVariant = sprite.removePrefix("${owner.basename}-tile").takeIf {
                it.length < sprite.length
            }?.toIntOrNull()
            if (tileVariant != null && tileVariant in 1..owner.tilingVariants) return true
        }

        if (owner.type == "TiledFloor" || owner.type == "TiledWall") {
            val suffix = sprite.removePrefix("${owner.basename}-")
            if (suffix.length < sprite.length) {
                val parts = suffix.split('-')
                val size = parts.getOrNull(0)?.toIntOrNull()
                if (size != null && size in 1..owner.maxSize) {
                    if (parts.size == 1) return true
                    val variant = parts.getOrNull(1)?.toIntOrNull()
                    if (parts.size == 2 && variant != null && variant in 0 until maxOf(owner.targetVariants, 1)) {
                        return true
                    }
                }
            }
        }

        if (owner.type == "Cliff") return true
        if (owner.targetVariants == 0) return sprite == owner.basename
        val variant = standardVariantIndex(sprite, owner.basename)
        return variant != null && variant in 1..owner.targetVariants
    }

    private fun standardVariantIndex(sprite: String, content: String): Int? {
        if (!sprite.startsWith(content)) return null
        val suffix = sprite.substring(content.length)
        return suffix.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    }

    private fun spriteBasename(candidate: Candidate): String =
        candidate.outputPath.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)

    private fun itemDropBasename(reference: String, sourceNamespace: String?): String {
        val basename = reference.substringAfterLast('/').lowercase(Locale.ROOT)
        val sourcePrefix = sourceNamespace?.lowercase(Locale.ROOT)?.let { "$it-" }
        return when {
            basename.startsWith("dp-") -> basename.removePrefix("dp-")
            sourcePrefix != null && basename.startsWith(sourcePrefix) -> basename.removePrefix(sourcePrefix)
            else -> basename
        }
    }

    private fun spriteBelongsToContent(sprite: String, content: String): Boolean {
        if (sprite == content || sprite.startsWith("$content-")) return true
        val suffix = sprite.removePrefix(content)
        return suffix.length < sprite.length && suffix.isNotEmpty() && suffix.all(Char::isDigit)
    }

    /**
     * A generated image whose direct parent equals ContentAsset.hashData() makes B480 skip the
     * broken import-time createIcons -> reloadImages -> DataImagePacker.unload cycle. The sentinel
     * is intentionally unrelated to any runtime atlas name and costs one opaque pixel.
     */
    private fun addContentRegenerationSentinels(
        outputFiles: MutableList<PlannedOutputFile>,
        assets: MutableList<AssetManifestEntry>,
        contents: List<EmittedContent>,
        diagnostics: MutableList<Diagnostic>,
    ): Int {
        val sentinels = linkedMapOf<String, PlannedOutputFile>()
        contents.asSequence()
            .forEach { content ->
                val encoded = encodeMindustryHash(MessageDigest.getInstance("SHA-256").digest(content.bytes))
                val contentHash = "${content.kind.name.lowercase(Locale.ROOT)}_$encoded"
                val sentinelName =
                    "bridge-sentinel-${content.kind.name.lowercase(Locale.ROOT)}-${encoded.take(16).lowercase(Locale.ROOT)}"
                val path = "sprites/generated/$contentHash/$sentinelName.png"
                sentinels.putIfAbsent(
                    path,
                    PlannedOutputFile(
                        path = path,
                        bytes = CONTENT_REGENERATION_SENTINEL_PNG,
                        sourcePath = "<bridge-generated:${content.kind.name.lowercase(Locale.ROOT)}/${content.basename}>",
                        status = ConvertedFileStatus.NORMALIZED,
                    ),
                )
            }

        sentinels.values.forEach { sentinel ->
            outputFiles += sentinel
            val basename = sentinel.path.substringAfterLast('/').substringBeforeLast('.')
            assets += AssetManifestEntry(
                kind = AssetKind.SPRITE,
                sourcePath = sentinel.sourcePath,
                basename = basename,
                extension = "png",
                sizeBytes = sentinel.bytes.size.toLong(),
                sha256 = sha256(sentinel.bytes),
                outputPath = sentinel.path,
            )
        }
        if (sentinels.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "B480_CONTENT_REGENERATION_SENTINELS_ADDED",
                severity = DiagnosticSeverity.WARNING,
                message = "Content-hash sentinel sprites were added to bypass B480's broken import-time sprite regeneration/unload cycle.",
                stage = ValidationStage.STRUCTURE,
                details = "${sentinels.size} unique content hash(es).",
                suggestion = "Import into a freshly opened editor/map. B480 itself can still fail when unloading an already active patch atlas; restart the client before replacing an imported pack.",
            )
        }
        return sentinels.size
    }

    private fun addOfflineContentSprites(
        outputFiles: MutableList<PlannedOutputFile>,
        assets: MutableList<AssetManifestEntry>,
        contents: List<EmittedContent>,
        diagnostics: MutableList<Diagnostic>,
    ): OfflineContentSpriteResult {
        val result = OfflineContentSpriteGenerator.generate(contents, outputFiles)
        result.files.forEach { generated ->
            outputFiles += generated
            val basename = generated.path.substringAfterLast('/').substringBeforeLast('.')
            assets += AssetManifestEntry(
                kind = AssetKind.SPRITE,
                sourcePath = generated.sourcePath,
                basename = basename,
                extension = "png",
                sizeBytes = generated.bytes.size.toLong(),
                sha256 = sha256(generated.bytes),
                outputPath = generated.path,
            )
        }
        if (result.files.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "B480_OFFLINE_CONTENT_SPRITES_GENERATED",
                severity = DiagnosticSeverity.INFO,
                message = "Content outlines and composite build-menu icons were generated offline.",
                stage = ValidationStage.STRUCTURE,
                details = "${result.fullIcons} full icon(s), ${result.outlines} outline/outlined sprite(s), " +
                    "${result.files.size} generated PNG(s) total.",
            )
        }
        if (result.misses.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "B480_OFFLINE_CONTENT_SPRITES_PARTIAL",
                severity = DiagnosticSeverity.WARNING,
                message = "Some content icon layers could not be generated because source regions were absent or unsupported.",
                stage = ValidationStage.STRUCTURE,
                details = result.misses.take(40).joinToString("; ") +
                    if (result.misses.size > 40) "; ... (${result.misses.size} total)" else "",
                suggestion = "Review the listed atlas names and add manual generated PNGs when their missing visuals affect gameplay.",
            )
        }
        return result
    }

    private fun encodeMindustryHash(data: ByteArray): String {
        require(data.size == 32)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = CharArray(52)
        var dataIndex = 0
        var outputIndex = 0
        repeat(6) {
            val bits =
                ((data[dataIndex++].toLong() and 0xffL) shl 32) or
                    ((data[dataIndex++].toLong() and 0xffL) shl 24) or
                    ((data[dataIndex++].toLong() and 0xffL) shl 16) or
                    ((data[dataIndex++].toLong() and 0xffL) shl 8) or
                    (data[dataIndex++].toLong() and 0xffL)
            output[outputIndex++] = alphabet[((bits ushr 35) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 30) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 25) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 20) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 15) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 10) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 5) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[(bits and 0x1f).toInt()]
        }
        val b0 = data[dataIndex++].toInt() and 0xff
        val b1 = data[dataIndex].toInt() and 0xff
        output[outputIndex++] = alphabet[(b0 ushr 3) and 0x1f]
        output[outputIndex++] = alphabet[((b0 shl 2) and 0x1c) or ((b1 ushr 6) and 0x03)]
        output[outputIndex++] = alphabet[(b1 ushr 1) and 0x1f]
        output[outputIndex] = alphabet[(b1 shl 4) and 0x1f]
        return String(output)
    }

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24 || !PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) return null
        fun intAt(offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
        val width = intAt(16)
        val height = intAt(20)
        return if (width > 0 && height > 0) width to height else null
    }

    private fun staticGeneratedCandidate(
        generated: StaticGeneratedFile,
        snapshot: SourceSnapshot,
        diagnostics: MutableList<Diagnostic>,
    ): Candidate {
        val outputPath = generated.outputPath.replace('\\', '/').trim('/')
        val segments = outputPath.split('/')
        if (
            outputPath.isBlank() || generated.outputPath.startsWith('/') ||
            segments.any { it.isBlank() || it == "." || it == ".." }
        ) {
            fail(
                "STATIC_EXPORT_PATH_INVALID",
                "A static exporter emitted an unsafe target path: '${generated.outputPath}'.",
                diagnostics,
            )
        }
        if (generated.sourcePaths.isEmpty()) {
            fail(
                "STATIC_EXPORT_PROVENANCE_MISSING",
                "Static output '$outputPath' did not identify an original source file.",
                diagnostics,
            )
        }
        val unknownSource = generated.sourcePaths.firstOrNull { source -> snapshot.entries.none { it.path == source } }
        if (unknownSource != null) {
            fail(
                "STATIC_EXPORT_UNKNOWN_SOURCE",
                "Static output '$outputPath' refers to unknown source '$unknownSource'.",
                diagnostics,
                SourceLocation(unknownSource),
            )
        }

        val root = segments.first().lowercase(Locale.ROOT)
        val extension = outputPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val contentKind: ContentKind?
        val assetKind: AssetKind?
        when (root) {
            "content" -> {
                if (segments.size < 3 || extension !in textExtensions) {
                    fail(
                        "STATIC_EXPORT_PATH_UNSUPPORTED",
                        "Static content output must be content/<type>/<name>.hjson or .json: '$outputPath'.",
                        diagnostics,
                    )
                }
                contentKind = ContentKind.fromFolder(segments[1].lowercase(Locale.ROOT))
                    ?: fail(
                        "STATIC_EXPORT_PATH_UNSUPPORTED",
                        "Static exporter emitted unsupported content folder '${segments[1]}'.",
                        diagnostics,
                    )
                assetKind = null
            }

            "patches" -> {
                if (extension !in textExtensions) {
                    fail("STATIC_EXPORT_PATH_UNSUPPORTED", "Static patch output must be JSON/HJSON.", diagnostics)
                }
                contentKind = null
                assetKind = AssetKind.PATCH
            }

            "bundles" -> {
                if (extension != "properties") {
                    fail("STATIC_EXPORT_PATH_UNSUPPORTED", "Static bundle output must be .properties.", diagnostics)
                }
                contentKind = null
                assetKind = AssetKind.BUNDLE
            }

            "sprites" -> {
                if (extension != "png") {
                    fail("STATIC_EXPORT_PATH_UNSUPPORTED", "Static sprite output must be .png.", diagnostics)
                }
                contentKind = null
                assetKind = AssetKind.SPRITE
            }

            "sounds" -> {
                if (extension !in setOf("ogg", "mp3")) {
                    fail("STATIC_EXPORT_PATH_UNSUPPORTED", "Static sound output must be .ogg or .mp3.", diagnostics)
                }
                contentKind = null
                assetKind = AssetKind.SOUND
            }

            "music" -> {
                if (extension !in setOf("ogg", "mp3")) {
                    fail("STATIC_EXPORT_PATH_UNSUPPORTED", "Static music output must be .ogg or .mp3.", diagnostics)
                }
                contentKind = null
                assetKind = AssetKind.MUSIC
            }

            else -> fail(
                "STATIC_EXPORT_PATH_UNSUPPORTED",
                "Static exporter emitted an unsupported data-asset root '$root'.",
                diagnostics,
            )
        }

        return Candidate(
            entry = SourceEntry(generated.sourcePaths.first(), generated.bytes),
            outputPath = outputPath,
            priority = 0,
            contentKind = contentKind,
            assetKind = assetKind,
            conversionReason = generated.reason,
            staticGenerated = true,
            outputNamespace = generated.namespace,
        )
    }

    private fun rewriteGeneratedSpritePath(path: String, sourceNamespace: String?): RewrittenOutputPath {
        if (sourceNamespace.isNullOrBlank()) return RewrittenOutputPath(path)
        val relative = path.substringAfter("sprites/", "")
        if (relative.isBlank()) return RewrittenOutputPath(path)
        val filename = relative.substringAfterLast('/')
        val extension = filename.substringAfterLast('.', "")
        val basename = filename.substringBeforeLast('.')
        val firstHyphen = basename.indexOf('-')
        if (firstHyphen < 0) return RewrittenOutputPath(path)

        val remainder = basename.substring(firstHyphen + 1)
        val namespacePrefix = "$sourceNamespace-"
        if (!remainder.startsWith(namespacePrefix, ignoreCase = true)) return RewrittenOutputPath(path)

        val rewrittenBasename = basename.substring(0, firstHyphen + 1) +
            "dp-" + remainder.substring(namespacePrefix.length)
        val originalDirectory = relative.substringBeforeLast('/', "")
        val generatedDirectory = when {
            originalDirectory.isBlank() -> "generated"
            originalDirectory.equals("generated", ignoreCase = true) -> originalDirectory
            originalDirectory.startsWith("generated/", ignoreCase = true) -> originalDirectory
            else -> "generated/$originalDirectory"
        }
        val rewrittenPath = "sprites/$generatedDirectory/$rewrittenBasename" +
            extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        return RewrittenOutputPath(
            path = rewrittenPath,
            reason = "Moved a category-prefixed mod sprite into generated/ and rewrote '$sourceNamespace' to 'dp'.",
        )
    }

    private fun buildNamespaceSymbols(
        sourceNamespace: String,
        candidates: List<Candidate>,
    ): ModNamespaceSymbols {
        val contentNames = ContentKind.entries.associateWith { kind ->
            candidates.asSequence()
                .filter { it.contentKind == kind }
                .map { it.outputPath.substringAfterLast('/').substringBeforeLast('.') }
                .toCollection(linkedSetOf())
        }
        val assets = candidates.mapNotNull { candidate ->
            val kind = when (candidate.assetKind) {
                AssetKind.SPRITE -> NamespaceAssetKind.SPRITE
                AssetKind.SOUND -> NamespaceAssetKind.SOUND
                AssetKind.MUSIC -> NamespaceAssetKind.MUSIC
                else -> return@mapNotNull null
            }
            val sourceBasename = candidate.entry.basename.substringBeforeLast('.')
            val targetBasename = candidate.outputPath.substringAfterLast('/').substringBeforeLast('.')
            val sourceRuntimeName: String
            val targetRuntimeName: String
            when (kind) {
                NamespaceAssetKind.SPRITE -> {
                    val firstHyphen = sourceBasename.indexOf('-')
                    sourceRuntimeName = if (
                        firstHyphen >= 0 &&
                        sourceBasename.substring(firstHyphen + 1).startsWith("$sourceNamespace-", ignoreCase = true)
                    ) {
                        sourceBasename
                    } else {
                        "$sourceNamespace-$sourceBasename"
                    }
                    targetRuntimeName = if (
                        candidate.outputPath.startsWith("sprites/generated/", ignoreCase = true) &&
                        targetBasename.contains("-dp-")
                    ) {
                        targetBasename
                    } else {
                        "dp-$targetBasename"
                    }
                }

                NamespaceAssetKind.SOUND,
                NamespaceAssetKind.MUSIC -> {
                    sourceRuntimeName = "$sourceNamespace-$sourceBasename"
                    targetRuntimeName = "dp-${targetBasename.replace(' ', '_')}"
                }
            }
            val sourceAliases = when (kind) {
                NamespaceAssetKind.SPRITE -> emptySet()
                NamespaceAssetKind.SOUND -> setOf(
                    candidate.outputPath.substringAfter("sounds/").substringBeforeLast('.'),
                )
                NamespaceAssetKind.MUSIC -> setOf(
                    candidate.outputPath.substringAfter("music/").substringBeforeLast('.'),
                )
            }
            NamespaceAssetSymbol(kind, sourceBasename, sourceRuntimeName, sourceAliases, targetRuntimeName)
        }
        return ModNamespaceSymbols(sourceNamespace, contentNames, assets)
    }

    private fun buildTargetAssetIndex(candidates: List<Candidate>): TargetAssetIndex {
        val spriteTargets = linkedSetOf<String>()
        val soundTargets = linkedSetOf<String>()
        val musicTargets = linkedSetOf<String>()
        val spriteRaw = linkedMapOf<String, String>()
        val soundRaw = linkedMapOf<String, String>()
        val musicRaw = linkedMapOf<String, String>()

        candidates.forEach { candidate ->
            val basename = candidate.outputPath.substringAfterLast('/').substringBeforeLast('.')
            val rawName = basename.lowercase(Locale.ROOT)
            when (candidate.assetKind) {
                AssetKind.SPRITE -> {
                    val target = if (
                        candidate.outputPath.startsWith("sprites/generated/", ignoreCase = true) &&
                        basename.contains("-dp-")
                    ) {
                        basename
                    } else {
                        "dp-$basename"
                    }
                    spriteTargets += target.lowercase(Locale.ROOT)
                    spriteRaw[rawName] = target
                }

                AssetKind.SOUND -> {
                    val target = "dp-${basename.replace(' ', '_')}"
                    soundTargets += target.lowercase(Locale.ROOT)
                    soundRaw[rawName] = target
                    soundRaw[candidate.outputPath.substringAfter("sounds/").substringBeforeLast('.')
                        .lowercase(Locale.ROOT)] = target
                }

                AssetKind.MUSIC -> {
                    val target = "dp-${basename.replace(' ', '_')}"
                    musicTargets += target.lowercase(Locale.ROOT)
                    musicRaw[rawName] = target
                    musicRaw[candidate.outputPath.substringAfter("music/").substringBeforeLast('.')
                        .lowercase(Locale.ROOT)] = target
                }

                else -> Unit
            }
        }
        return TargetAssetIndex(
            spriteTargets = spriteTargets,
            soundTargets = soundTargets,
            musicTargets = musicTargets,
            spriteRawToTarget = spriteRaw,
            soundRawToTarget = soundRaw,
            musicRawToTarget = musicRaw,
        )
    }

    private fun addNamespaceDiagnostics(
        sourcePath: String,
        result: NamespaceRewriteResult,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (result.rewrites.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "MOD_REFERENCE_REWRITTEN",
                severity = DiagnosticSeverity.INFO,
                message = "References tied to the original mod namespace were rewritten for the dp namespace.",
                stage = ValidationStage.STRUCTURE,
                location = SourceLocation(sourcePath),
                details = result.rewrites.take(12).joinToString("; ") {
                    "${it.path}: '${it.from}' -> '${it.to}'"
                } + if (result.rewrites.size > 12) "; ... (${result.rewrites.size} total)" else "",
            )
        }
        result.unresolved.forEach { unresolved ->
            diagnostics += Diagnostic(
                code = "UNRESOLVED_MOD_REFERENCE",
                severity = DiagnosticSeverity.ERROR,
                message = "A namespaced ${unresolved.expectedKind} reference has no matching converted content or asset.",
                stage = ValidationStage.STRUCTURE,
                location = SourceLocation(sourcePath, jsonPath = unresolved.path),
                details = unresolved.value,
                suggestion = "Add the missing source asset/content or manually replace this reference after conversion.",
            )
        }
    }

    private fun addBundleNamespaceDiagnostic(
        sourcePath: String,
        rewrites: List<NamespaceRewrite>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        diagnostics += Diagnostic(
            code = "BUNDLE_NAMESPACE_REWRITTEN",
            severity = DiagnosticSeverity.INFO,
            message = "Bundle keys were rewritten from the original mod namespace to the dp namespace.",
            stage = ValidationStage.STRUCTURE,
            location = SourceLocation(sourcePath),
            details = rewrites.take(12).joinToString("; ") { "${it.from} -> ${it.to}" } +
                if (rewrites.size > 12) "; ... (${rewrites.size} total)" else "",
        )
    }

    private fun addAssetReferenceDiagnostics(
        sourcePath: String,
        issues: List<AssetReferenceIssue>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        issues.forEach { issue ->
            diagnostics += Diagnostic(
                code = issue.code,
                severity = DiagnosticSeverity.ERROR,
                message = issue.message,
                stage = ValidationStage.STRUCTURE,
                location = SourceLocation(sourcePath, jsonPath = issue.path),
                details = issue.value,
                suggestion = issue.suggestion,
            )
        }
    }

    private fun validateContentIconClosure(
        candidates: List<Candidate>,
        targetSpriteNames: Set<String>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        candidates.filter { it.contentKind != null && it.contentKind != ContentKind.WEATHER }.forEach { candidate ->
            val kind = candidate.contentKind ?: return@forEach
            val basename = candidate.outputPath.substringAfterLast('/').substringBeforeLast('.')
            val contentName = "dp-$basename"
            val prefix = kind.name.lowercase(Locale.ROOT)
            val conventionalNames = setOf(
                contentName,
                "$contentName-full",
                "${contentName}1",
                "$prefix-$contentName",
                "$prefix-$contentName-full",
                "$prefix-$contentName-ui",
            ).map { it.lowercase(Locale.ROOT) }
            if (conventionalNames.none(targetSpriteNames::contains)) {
                diagnostics += Diagnostic(
                    code = "CONTENT_ICON_NOT_FOUND",
                    severity = DiagnosticSeverity.WARNING,
                    message = "No conventional sprite/icon asset was found for converted ${kind.name.lowercase()} '$basename'.",
                    stage = ValidationStage.STRUCTURE,
                    location = SourceLocation(candidate.entry.path),
                    details = "Expected one of: ${conventionalNames.joinToString()}",
                    suggestion = "Add a matching PNG or verify an explicit icon override in the v159.7 client.",
                )
            }
        }
    }

    private fun addAssetCandidate(
        entry: SourceEntry,
        outputPath: String,
        fromAssets: Boolean,
        kind: AssetKind,
        allowedExtensions: Set<String>,
        candidates: MutableList<Candidate>,
        ignored: MutableList<IgnoredSourceEntry>,
        converted: MutableList<ConvertedFile>,
        unsupportedExtensions: MutableMap<String, Int>,
        conversionReason: String? = null,
    ) {
        if (entry.extension !in allowedExtensions) {
            ignore(
                entry,
                "Unsupported ${kind.name.lowercase()} extension: .${entry.extension}",
                ignored,
                converted,
                status = ConvertedFileStatus.UNSUPPORTED,
            )
            unsupportedExtensions[entry.extension] = (unsupportedExtensions[entry.extension] ?: 0) + 1
            return
        }
        candidates += Candidate(
            entry = entry,
            outputPath = outputPath,
            priority = if (fromAssets) 0 else 1,
            assetKind = kind,
            conversionReason = conversionReason,
        )
    }

    private fun resolveOutputPathCollisions(
        candidates: List<Candidate>,
        ignored: MutableList<IgnoredSourceEntry>,
        converted: MutableList<ConvertedFile>,
        diagnostics: MutableList<Diagnostic>,
    ): List<Candidate> {
        val selected = mutableListOf<Candidate>()
        candidates.groupBy { it.outputPath.lowercase(Locale.ROOT) }.toSortedMap().forEach { (_, group) ->
            val sorted = group.sortedWith(compareBy<Candidate> { it.priority }.thenBy { it.entry.path })
            val winner = sorted.first()
            val samePriority = sorted.count { it.priority == winner.priority }
            if (samePriority > 1) {
                fail(
                    "DUPLICATE_OUTPUT_PATH",
                    "Multiple source files map to '${winner.outputPath}': ${sorted.joinToString { it.entry.path }}",
                    diagnostics,
                )
            }
            selected += winner
            sorted.drop(1).forEach { duplicate ->
                val reason = "A matching assets/ file took precedence for output path ${winner.outputPath}."
                ignore(duplicate.entry, reason, ignored, converted)
                diagnostics += Diagnostic(
                    code = "ROOT_ASSET_SHADOWED",
                    severity = DiagnosticSeverity.WARNING,
                    message = reason,
                    stage = ValidationStage.STRUCTURE,
                    location = SourceLocation(duplicate.entry.path),
                )
            }
        }
        return selected
    }

    /**
     * [RegionPart] always resolves mirrored turret/unit sides with the lowercase `-r` and `-l`
     * suffixes. A few otherwise valid mods contain Windows-only sprite names such as `*-R.png`;
     * those load from a case-insensitive source tree but become distinct, missing atlas keys in the
     * v159 data-image packer. Normalize only this engine-defined terminal suffix for ordinary mods.
     * Existing data packs are kept byte/path exact because their content text is intentionally not
     * rewritten.
     */
    private fun normalizeMirroredSpriteSideCase(
        candidates: List<Candidate>,
        detection: SourceDetection,
        diagnostics: MutableList<Diagnostic>,
    ): List<Candidate> {
        if (detection.kind != DetectedSourceKind.MOD) return candidates
        val normalized = ArrayList<Candidate>(candidates.size)
        for (candidate in candidates) {
            if (candidate.assetKind != AssetKind.SPRITE) {
                normalized += candidate
                continue
            }
            val filename = candidate.outputPath.substringAfterLast('/')
            val extension = filename.substringAfterLast('.', "")
            val basename = filename.substringBeforeLast('.')
            if (basename.length < 2 || basename[basename.lastIndex - 1] != '-' ||
                basename.last() !in charArrayOf('R', 'L')
            ) {
                normalized += candidate
                continue
            }

            val normalizedBasename = basename.dropLast(1) + basename.last().lowercaseChar()
            val directory = candidate.outputPath.substringBeforeLast('/', "")
            val normalizedPath = directory.takeIf(String::isNotBlank)?.let { "$it/" }.orEmpty() +
                normalizedBasename + extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
            diagnostics.add(Diagnostic(
                code = "MIRRORED_SPRITE_SIDE_CASE_NORMALIZED",
                severity = DiagnosticSeverity.WARNING,
                message = "A mirrored RegionPart sprite used an uppercase side suffix that v159 cannot resolve.",
                stage = ValidationStage.STRUCTURE,
                location = SourceLocation(candidate.entry.path),
                details = "${candidate.outputPath} -> $normalizedPath",
                suggestion = "Prefer lowercase '-r.png' and '-l.png' names in the source mod.",
            ))
            normalized += candidate.copy(
                outputPath = normalizedPath,
                conversionReason = listOfNotNull(
                    candidate.conversionReason,
                    "Normalized the engine-defined mirrored sprite suffix to lowercase for v159 atlas lookup.",
                ).joinToString(" "),
            )
        }
        return normalized
    }

    /**
     * Sprite directories are not namespaces in either regular mods or v159 data packs. When a mod
     * contains byte-identical PNGs with the same basename in different ordinary directories, they
     * represent the same atlas region and one deterministic copy is sufficient. Different bytes are
     * intentionally left for [validateDataAssetNames] to reject rather than silently choosing one.
     */
    private fun deduplicateIdenticalSpriteNames(
        candidates: List<Candidate>,
        ignored: MutableList<IgnoredSourceEntry>,
        converted: MutableList<ConvertedFile>,
        diagnostics: MutableList<Diagnostic>,
    ): List<Candidate> {
        val removed = hashSetOf<Candidate>()
        candidates.asSequence()
            .filter { it.assetKind == AssetKind.SPRITE }
            .groupBy { it.outputPath.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT) }
            .toSortedMap()
            .forEach { (basename, group) ->
                if (group.size < 2) return@forEach
                val generated = group.filter { it.outputPath.startsWith("sprites/generated/", ignoreCase = true) }
                val ordinary = group - generated.toSet()
                // A single ordinary/generated pair is meaningful v159 precedence, not a duplicate.
                if (group.size == 2 && generated.size == 1 && ordinary.size == 1) return@forEach
                if (generated.isNotEmpty() || ordinary.size < 2) return@forEach
                val winner = ordinary.sortedBy { it.outputPath }.first()
                val duplicates = ordinary.filterNot { it === winner }
                if (duplicates.any { !it.entry.bytes.contentEquals(winner.entry.bytes) }) return@forEach

                duplicates.forEach { duplicate ->
                    removed += duplicate
                    val reason = "Byte-identical sprite basename '$basename' was deduplicated to ${winner.outputPath}."
                    ignore(duplicate.entry, reason, ignored, converted)
                    diagnostics += Diagnostic(
                        code = "IDENTICAL_SPRITE_DEDUPLICATED",
                        severity = DiagnosticSeverity.INFO,
                        message = reason,
                        stage = ValidationStage.STRUCTURE,
                        location = SourceLocation(duplicate.entry.path),
                        details = winner.entry.path,
                    )
                }
            }
        return candidates.filterNot(removed::contains)
    }

    private fun normalizeText(entry: SourceEntry, diagnostics: MutableList<Diagnostic>): NormalizedText {
        val normalized = try {
            HjsonNormalizer.normalize(entry.bytes, entry.path)
        } catch (error: ConversionException) {
            fail("TEXT_PARSE_FAILED", error.message ?: "Text parsing failed", diagnostics, SourceLocation(entry.path), error)
        }
        if (normalized.compatibilityMode != null) {
            diagnostics += Diagnostic(
                code = "MULTILINE_STRING_COMPATIBILITY_REPAIR",
                severity = DiagnosticSeverity.WARNING,
                message = "A multi-line string was rewritten as escaped JSON so the HJSON parser could read the file.",
                stage = ValidationStage.STRUCTURE,
                location = SourceLocation(entry.path),
                details = normalized.compatibilityMode,
                suggestion = "Review the normalized file carefully because multi-line strings were rewritten best-effort.",
            )
        }
        if (normalized.removedResearchPaths.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "RESEARCH_REMOVED",
                severity = DiagnosticSeverity.WARNING,
                message = "Unsupported research/tech-tree data was removed.",
                stage = ValidationStage.STRUCTURE,
                location = SourceLocation(entry.path),
                details = normalized.removedResearchPaths.joinToString(),
            )
        }
        return normalized
    }

    private fun normalizeLegacyCpText(entry: SourceEntry, diagnostics: MutableList<Diagnostic>): NormalizedText {
        val normalized = try {
            HjsonNormalizer.normalizeLegacyCp(entry.bytes, entry.path)
        } catch (error: ConversionException) {
            fail("TEXT_PARSE_FAILED", error.message ?: "Text parsing failed", diagnostics, SourceLocation(entry.path), error)
        }
        if (normalized.compatibilityMode != null) {
            diagnostics += Diagnostic(
                code = "LEGACY_CP_COMPATIBILITY_REPAIR",
                severity = DiagnosticSeverity.WARNING,
                message = "The CP required a best-effort legacy syntax repair before it could be parsed.",
                stage = ValidationStage.STRUCTURE,
                location = SourceLocation(entry.path),
                details = normalized.compatibilityMode,
                suggestion = "Review the normalized patch carefully because regex compatibility repair can be lossy.",
            )
        }
        if (normalized.removedResearchPaths.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "RESEARCH_REMOVED",
                severity = DiagnosticSeverity.WARNING,
                message = "Unsupported research/tech-tree data was removed.",
                stage = ValidationStage.STRUCTURE,
                location = SourceLocation(entry.path),
                details = normalized.removedResearchPaths.joinToString(),
            )
        }
        return normalized
    }

    private fun validateDataAssetNames(
        files: List<PlannedOutputFile>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        fun basename(path: String): String = path.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)

        val contentDuplicates = files.filter { it.path.startsWith("content/", true) }.groupBy { basename(it.path) }
            .filterValues { it.size > 1 }
        if (contentDuplicates.isNotEmpty()) {
            val duplicate = contentDuplicates.entries.first()
            fail(
                "CONTENT_ASSET_NAME_COLLISION",
                "Content basenames must be globally unique: ${duplicate.key} (${duplicate.value.joinToString { it.path }})",
                diagnostics,
            )
        }

        listOf("bundles", "sounds", "music").forEach { root ->
            val duplicate = files.filter { it.path.startsWith("$root/", true) }.groupBy { basename(it.path) }
                .entries.firstOrNull { it.value.size > 1 }
            if (duplicate != null) {
                fail(
                    "ASSET_NAME_COLLISION",
                    "Duplicate $root asset basename '${duplicate.key}': ${duplicate.value.joinToString { it.path }}",
                    diagnostics,
                )
            }
        }

        val audioCollision = files
            .filter { it.path.startsWith("sounds/", true) || it.path.startsWith("music/", true) }
            .groupBy { basename(it.path) }
            .entries
            .firstOrNull { entry ->
                entry.value.any { it.path.startsWith("sounds/", true) } &&
                    entry.value.any { it.path.startsWith("music/", true) }
            }
        if (audioCollision != null) {
            fail(
                "AUDIO_NAME_COLLISION",
                "Sound and music assets share one v159 audio namespace; basename '${audioCollision.key}' collides: " +
                    audioCollision.value.joinToString { it.path },
                diagnostics,
            )
        }

        files.filter { it.path.startsWith("sprites/", true) }.groupBy { basename(it.path) }
            .filterValues { it.size > 1 }
            .forEach { (name, sameName) ->
                val generated = sameName.count { it.path.startsWith("sprites/generated/", true) }
                val ordinary = sameName.size - generated
                if (sameName.size != 2 || generated != 1 || ordinary != 1) {
                    fail(
                        "SPRITE_NAME_COLLISION",
                        "Invalid duplicate sprite basename '$name': ${sameName.joinToString { it.path }}",
                        diagnostics,
                    )
                }
                diagnostics += Diagnostic(
                    code = "GENERATED_SPRITE_PAIR",
                    severity = DiagnosticSeverity.INFO,
                    message = "A normal/generated same-name sprite pair was retained using v159 precedence rules.",
                    stage = ValidationStage.STRUCTURE,
                    details = sameName.joinToString { it.path },
                )
        }
    }

    private fun addAudioContainerDiagnostics(
        candidates: List<Candidate>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        candidates.asSequence()
            .filter { it.assetKind == AssetKind.SOUND || it.assetKind == AssetKind.MUSIC }
            .sortedBy { it.outputPath }
            .forEach { candidate ->
                val declared = candidate.outputPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val detected = detectAudioContainer(candidate.entry.bytes) ?: return@forEach
                if (declared == detected) return@forEach

                diagnostics += Diagnostic(
                    code = "AUDIO_CONTAINER_EXTENSION_MISMATCH",
                    severity = DiagnosticSeverity.WARNING,
                    message = "The audio filename extension does not match its detected byte container; bytes were preserved unchanged.",
                    stage = ValidationStage.STRUCTURE,
                    location = SourceLocation(candidate.entry.path),
                    details = "output=${candidate.outputPath}, declared=.$declared, detected=$detected",
                    suggestion =
                        "Test this asset in the exact v159.7 Desktop client. If Arc/SoLoud cannot decode it, " +
                            "transcode it to genuine OGG or MP3 while preserving the basename.",
                )
            }
    }

    private fun detectAudioContainer(bytes: ByteArray): String? = when {
        hasAscii(bytes, 0, "OggS") -> "ogg"
        hasAscii(bytes, 0, "ID3") ||
            (bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0) -> "mp3"
        hasAscii(bytes, 0, "RIFF") && hasAscii(bytes, 8, "WAVE") -> "wav"
        else -> null
    }

    private fun hasAscii(bytes: ByteArray, offset: Int, value: String): Boolean {
        if (offset < 0 || bytes.size - offset < value.length) return false
        return value.indices.all { index -> bytes[offset + index].toInt() == value[index].code }
    }

    private fun ignore(
        entry: SourceEntry,
        reason: String,
        ignored: MutableList<IgnoredSourceEntry>,
        converted: MutableList<ConvertedFile>,
        status: ConvertedFileStatus = ConvertedFileStatus.EXCLUDED,
    ) {
        ignored += IgnoredSourceEntry(entry.path, reason)
        converted += ConvertedFile(entry.path, status = status, reason = reason)
    }

    private fun looksExecutable(entry: SourceEntry): Boolean {
        // The assets tree is a resource root. Only its scripts/ subtree is executable by
        // Mindustry; archived .java/.js snippets elsewhere must not trigger a false claim that
        // the original Mod ran them.
        if (entry.path.startsWith("assets/", ignoreCase = true) &&
            !entry.path.startsWith("assets/scripts/", ignoreCase = true)
        ) {
            return false
        }
        if (entry.path.equals("gradle/wrapper/gradle-wrapper.jar", ignoreCase = true)) return false
        val extension = entry.extension
        return extension in setOf("class", "jar", "js", "kts", "java", "kt") ||
            entry.path.startsWith("scripts/", true) || entry.path.startsWith("assets/scripts/", true)
    }

    private fun fail(
        code: String,
        message: String,
        diagnostics: MutableList<Diagnostic>,
        location: SourceLocation? = null,
        cause: Throwable? = null,
        suggestion: String? = null,
    ): Nothing {
        val diagnostic = Diagnostic(
            code = code,
            severity = DiagnosticSeverity.ERROR,
            message = message,
            stage = ValidationStage.STRUCTURE,
            location = location,
            suggestion = suggestion,
        )
        diagnostics += diagnostic
        throw ConversionException(message, diagnostics.toList(), cause = cause)
    }
}
