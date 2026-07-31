package io.github.moddpbridge.runtimeassets

import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale

/** Hard limits applied while reading an untrusted release JAR. */
data class RuntimeAssetLimits(
    /** Compressed JAR size. */
    val maxArchiveBytes: Long = 512_000_000L,
    /** Counts every central-directory entry, including classes and directories. */
    val maxEntries: Int = 100_000,
    /** Uncompressed size of one asset that would enter the staging snapshot. */
    val maxEntryBytes: Long = 64_000_000L,
    /** Sum of uncompressed bytes for assets that would enter the staging snapshot. */
    val maxExpandedBytes: Long = 512_000_000L,
    val maxCompressionRatio: Double = 250.0,
    val maxPathLength: Int = 1_024,
)

enum class RuntimeAssetKind(val root: String, val extensions: Set<String>) {
    BUNDLE("bundles", setOf("properties")),
    SPRITE("sprites", setOf("png")),
    SOUND("sounds", setOf("ogg", "mp3")),
    MUSIC("music", setOf("ogg", "mp3")),
    ;

    companion object {
        internal fun fromRoot(root: String): RuntimeAssetKind? = entries.firstOrNull { it.root == root }
    }
}

enum class RuntimeAssetDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

/** A diagnostic is kept separate from the converter report so runtime extraction can run independently. */
data class RuntimeAssetDiagnostic(
    val code: String,
    val severity: RuntimeAssetDiagnosticSeverity,
    val message: String,
    val sourceEntryPath: String? = null,
    val relatedSourceEntryPaths: List<String> = emptyList(),
    val outputPath: String? = null,
    val details: String? = null,
)

data class RuntimeAssetJar(
    /** Absolute normalized path used for this extraction. */
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * One immutable file ready to be combined with generated HJSON and handed to BridgeConverter.
 *
 * [outputPath] always starts with bundles/, sprites/, sounds/, or music/. JAR bytes are preserved
 * exactly; this component does not rename the mod namespace or normalize image/audio contents.
 */
class RuntimeAssetFile internal constructor(
    val kind: RuntimeAssetKind,
    val outputPath: String,
    val sourceEntryPath: String,
    val sourceEntrySizeBytes: Long,
    val sourceEntrySha256: String,
    bytes: ByteArray,
) {
    private val storedBytes = bytes.copyOf()

    /** Returns a defensive copy so the snapshot fingerprint cannot be mutated by callers. */
    fun bytes(): ByteArray = storedBytes.copyOf()

    internal fun rawBytes(): ByteArray = storedBytes

    override fun equals(other: Any?): Boolean =
        other is RuntimeAssetFile &&
            kind == other.kind &&
            outputPath == other.outputPath &&
            sourceEntryPath == other.sourceEntryPath &&
            sourceEntrySizeBytes == other.sourceEntrySizeBytes &&
            sourceEntrySha256 == other.sourceEntrySha256 &&
            storedBytes.contentEquals(other.storedBytes)

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + outputPath.hashCode()
        result = 31 * result + sourceEntryPath.hashCode()
        result = 31 * result + sourceEntrySizeBytes.hashCode()
        result = 31 * result + sourceEntrySha256.hashCode()
        result = 31 * result + storedBytes.contentHashCode()
        return result
    }
}

data class RuntimeAssetSnapshot(
    val sourceJar: RuntimeAssetJar,
    /** The one outer directory stripped by Mindustry Mods.resolveRoot, if present. */
    val resolvedRootPrefix: String?,
    /** Sorted by [RuntimeAssetFile.outputPath], case-insensitively and then bytewise. */
    val files: List<RuntimeAssetFile>,
    val diagnostics: List<RuntimeAssetDiagnostic>,
    val scannedEntryCount: Int,
    /** Files read after root/extension filtering and before tool-directory precedence. */
    val candidateFileCount: Int,
    val candidateExpandedBytes: Long,
    val stagedBytes: Long,
    /** Content-derived fingerprint of output paths and exact staged bytes. */
    val stagingSha256: String,
) {
    /**
     * Writes the deterministic file tree into a new or empty directory.
     *
     * Requiring an empty target prevents stale files from silently entering a later conversion.
     */
    fun writeTo(outputDirectory: Path): Path {
        val root = outputDirectory.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(root)) {
            throw RuntimeAssetStagingException("Staging output may not be a symbolic link: $root")
        }
        if (Files.exists(root)) {
            if (!Files.isDirectory(root)) {
                throw RuntimeAssetStagingException("Staging output is not a directory: $root")
            }
            Files.newDirectoryStream(root).use { stream ->
                if (stream.iterator().hasNext()) {
                    throw RuntimeAssetStagingException("Staging output must be empty: $root")
                }
            }
        } else {
            Files.createDirectories(root)
        }

        val rootReal = root.toRealPath()
        val createdFiles = mutableListOf<Path>()
        val createdDirectories = linkedSetOf<Path>()
        try {
            files.forEach { file ->
                val target = root.resolve(file.outputPath.replace('/', root.fileSystem.separator.single())).normalize()
                if (!target.startsWith(root)) {
                    throw RuntimeAssetStagingException("Staged path escapes the output directory: ${file.outputPath}")
                }
                val parent = target.parent
                Files.createDirectories(parent)
                var cursor: Path? = parent
                while (cursor != null && cursor != root) {
                    createdDirectories.add(cursor)
                    cursor = cursor.parent
                }
                if (!parent.toRealPath().startsWith(rootReal)) {
                    throw RuntimeAssetStagingException("Staged path resolves outside the output directory: ${file.outputPath}")
                }
                Files.write(target, file.rawBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                createdFiles.add(target)
            }
        } catch (error: Exception) {
            createdFiles.asReversed().forEach { runCatching { Files.deleteIfExists(it) } }
            createdDirectories.sortedByDescending { it.nameCount }.forEach { runCatching { Files.deleteIfExists(it) } }
            throw if (error is RuntimeAssetStagingException) error else {
                RuntimeAssetStagingException("Could not write runtime asset staging directory: $root", error)
            }
        }
        return root
    }
}

class RuntimeAssetStagingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Extracts only server-map-play assets from the exact published JAR.
 *
 * It never loads JAR classes. Maps, scripts, shaders, textures and sprites-override are observed
 * only for aggregate exclusion diagnostics and never enter [RuntimeAssetSnapshot.files].
 */
object RuntimeAssetStager {
    private enum class SpriteBasenameCollisionPolicy {
        REPORT_ONLY,
        V159_JAR_CENTRAL_DIRECTORY_LAST_WINS,
    }

    private val explicitlyExcludedRoots = setOf(
        "maps",
        "scripts",
        "shaders",
        "textures",
        "sprites-override",
    )

    fun scan(jar: Path, limits: RuntimeAssetLimits = RuntimeAssetLimits()): RuntimeAssetSnapshot =
        scan(jar, limits, SpriteBasenameCollisionPolicy.REPORT_ONLY)

    /**
     * Runtime-mapper selection policy for ordinary sprite basename collisions.
     *
     * v159 loads JAR sprites through `ZipFi.findAll` and later atlas writes replace earlier names.
     * Arc's ZipFi builds a hash-backed tree, so central-directory ordinal is deliberately documented
     * as a deterministic approximation, not a universal proof of ZipFi order. It has been checked
     * against a real v159.7 Arc probe for New Horizon 2.2.1's `large-launcher` collision.
     */
    fun scanForRuntimeMapper(
        jar: Path,
        limits: RuntimeAssetLimits = RuntimeAssetLimits(),
    ): RuntimeAssetSnapshot = scan(jar, limits, SpriteBasenameCollisionPolicy.V159_JAR_CENTRAL_DIRECTORY_LAST_WINS)

    private fun scan(
        jar: Path,
        limits: RuntimeAssetLimits,
        spriteCollisionPolicy: SpriteBasenameCollisionPolicy,
    ): RuntimeAssetSnapshot {
        validateLimits(limits)
        val source = jar.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(source)) {
            throw RuntimeAssetStagingException("Symbolic-link JAR inputs are not allowed: $source")
        }
        if (!Files.isRegularFile(source)) {
            throw RuntimeAssetStagingException("Runtime asset input must be a regular JAR file: $source")
        }
        if (!source.fileName.toString().endsWith(".jar", ignoreCase = true)) {
            throw RuntimeAssetStagingException("Runtime asset input must use the .jar extension: $source")
        }
        val archiveSize = Files.size(source)
        requireWithin(archiveSize, limits.maxArchiveBytes, "JAR exceeds the compressed input limit")
        val sourceJar = RuntimeAssetJar(source.toString(), archiveSize, sha256(source))

        val candidates = mutableListOf<Candidate>()
        val diagnostics = mutableListOf<RuntimeAssetDiagnostic>()
        val excludedRootCounts = linkedMapOf<String, Int>()
        val nestedAssetsRootCounts = linkedMapOf<String, Int>()
        val unsupportedExtensionCounts = linkedMapOf<String, Int>()
        var scannedEntries = 0
        var expandedBytes = 0L
        var resolvedRootPrefix: String? = null

        try {
            @Suppress("DEPRECATION")
            ZipFile(source).use { zip ->
                val archiveEntries = mutableListOf<ArchiveEntryRef>()
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    scannedEntries++
                    if (scannedEntries > limits.maxEntries) {
                        throw RuntimeAssetStagingException(
                            "JAR contains more than ${limits.maxEntries} central-directory entries",
                        )
                    }
                    val normalized = normalizeJarPath(entry.name, limits)
                    if (entry.isUnixSymlink) {
                        throw RuntimeAssetStagingException("JAR symbolic links are not allowed: ${entry.name}")
                    }
                    archiveEntries += ArchiveEntryRef(entry, normalized, scannedEntries - 1)
                }

                resolvedRootPrefix = resolveRootPrefix(archiveEntries)
                if (resolvedRootPrefix != null) {
                    diagnostics += RuntimeAssetDiagnostic(
                        code = "MOD_ARCHIVE_ROOT_STRIPPED",
                        severity = RuntimeAssetDiagnosticSeverity.INFO,
                        message = "The single outer JAR directory was stripped to mirror Mindustry Mods.resolveRoot.",
                        details = resolvedRootPrefix,
                    )
                }

                val resolvedEntries = archiveEntries.mapNotNull { ref ->
                    relativeToResolvedRoot(ref, resolvedRootPrefix)?.let { relative ->
                        ResolvedEntry(ref.entry, ref.path, relative, ref.centralDirectoryOrdinal)
                    }
                }
                val formalBundles = resolvedEntries.asSequence()
                    .filterNot { it.entry.isDirectory }
                    .filter { resolved ->
                        val parts = resolved.relativePath.split('/')
                        parts.size == 2 && parts[0].equals("bundles", ignoreCase = true) &&
                            parts[1].substringAfterLast('.', "").equals("properties", ignoreCase = true)
                    }
                    .groupBy { basename(it.relativePath).lowercase(Locale.ROOT) }

                for (resolved in resolvedEntries) {
                    val entry = resolved.entry
                    val normalized = resolved.sourcePath
                    val relative = resolved.relativePath
                    if (entry.isDirectory) continue

                    val relativeParts = relative.split('/')
                    val relativeRoot = relativeParts.first().lowercase(Locale.ROOT)
                    if (relativeRoot == "bundles" && relativeParts.size > 2) {
                        val sameNameFormal = formalBundles[basename(relative).lowercase(Locale.ROOT)].orEmpty()
                        val blank = relativeParts[1].equals("blank", ignoreCase = true)
                        diagnostics += RuntimeAssetDiagnostic(
                            code = when {
                                blank && sameNameFormal.isNotEmpty() -> "BUNDLE_BLANK_SHADOWED"
                                blank -> "BUNDLE_BLANK_EXCLUDED"
                                else -> "NESTED_BUNDLE_EXCLUDED"
                            },
                            severity = RuntimeAssetDiagnosticSeverity.WARNING,
                            message = when {
                                blank && sameNameFormal.isNotEmpty() ->
                                    "A bundles/blank helper file was excluded; Mindustry loads only direct bundle files and a formal same-name bundle exists."
                                blank ->
                                    "A bundles/blank helper file was excluded because Mindustry does not recursively load bundle directories."
                                else ->
                                    "A nested bundle file was excluded because Mindustry loads only direct files in bundles/."
                            },
                            sourceEntryPath = normalized,
                            relatedSourceEntryPaths = sameNameFormal.map { it.sourcePath }.sorted(),
                            outputPath = sameNameFormal.firstOrNull()?.relativePath,
                        )
                        continue
                    }

                    if (relativeRoot == "assets" && relativeParts.size > 1) {
                        val nestedRoot = relativeParts[1].lowercase(Locale.ROOT)
                        if (RuntimeAssetKind.fromRoot(nestedRoot) != null || nestedRoot in explicitlyExcludedRoots) {
                            val key = "assets/$nestedRoot"
                            nestedAssetsRootCounts[key] = (nestedAssetsRootCounts[key] ?: 0) + 1
                        }
                        continue
                    }

                    val classification = classify(relative)
                    if (classification == null) {
                        excludedRoot(relative)?.let { root ->
                            excludedRootCounts[root] = (excludedRootCounts[root] ?: 0) + 1
                        }
                        continue
                    }
                    val extension = classification.outputPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (extension !in classification.kind.extensions) {
                        val key = "${classification.kind.root}/.${extension.ifBlank { "<none>" }}"
                        unsupportedExtensionCounts[key] = (unsupportedExtensionCounts[key] ?: 0) + 1
                        continue
                    }
                    if (!zip.canReadEntryData(entry)) {
                        throw RuntimeAssetStagingException("Unsupported or encrypted JAR entry: ${entry.name}")
                    }
                    val declared = entry.size
                    if (declared >= 0) {
                        requireWithin(declared, limits.maxEntryBytes, "JAR asset is too large: ${entry.name}")
                        if (declared > limits.maxExpandedBytes - expandedBytes) {
                            throw RuntimeAssetStagingException(
                                "JAR asset bytes exceed the expanded limit at: ${entry.name}",
                            )
                        }
                    }
                    val compressed = entry.compressedSize
                    if (declared > 0 && compressed >= 0) {
                        val ratio = declared.toDouble() / compressed.coerceAtLeast(1L).toDouble()
                        if (ratio > limits.maxCompressionRatio) {
                            throw RuntimeAssetStagingException(
                                "JAR asset compression ratio exceeds ${limits.maxCompressionRatio}:1: ${entry.name}",
                            )
                        }
                    }
                    val bytes = zip.getInputStream(entry).use { input ->
                        readLimited(
                            input = input,
                            entryLimit = limits.maxEntryBytes,
                            remainingTotal = limits.maxExpandedBytes - expandedBytes,
                            name = entry.name,
                        )
                    }
                    expandedBytes += bytes.size.toLong()
                    candidates += Candidate(
                        kind = classification.kind,
                        outputPath = classification.outputPath,
                        sourceEntryPath = normalized,
                        centralDirectoryOrdinal = resolved.centralDirectoryOrdinal,
                        toolDirectory = classification.toolDirectory,
                        bytes = bytes,
                        sha256 = sha256(bytes),
                    )
                }
            }
        } catch (error: RuntimeAssetStagingException) {
            throw error
        } catch (error: Exception) {
            throw RuntimeAssetStagingException("Could not safely read release JAR: $source", error)
        }

        excludedRootCounts.toSortedMap().forEach { (root, count) ->
            diagnostics += RuntimeAssetDiagnostic(
                code = "NON_DP_RUNTIME_ROOT_EXCLUDED",
                severity = RuntimeAssetDiagnosticSeverity.INFO,
                message = "The '$root' JAR tree is outside the runtime-to-DP gameplay asset boundary.",
                details = "$count file(s) excluded",
            )
        }
        nestedAssetsRootCounts.toSortedMap().forEach { (root, count) ->
            diagnostics += RuntimeAssetDiagnostic(
                code = "NESTED_ASSETS_TREE_NOT_LOADED",
                severity = RuntimeAssetDiagnosticSeverity.WARNING,
                message = "The '$root' tree is not under Mindustry's resolved mod root and was not staged.",
                details = "$count file(s) excluded",
            )
        }
        unsupportedExtensionCounts.toSortedMap().forEach { (kindAndExtension, count) ->
            diagnostics += RuntimeAssetDiagnostic(
                code = "UNSUPPORTED_RUNTIME_ASSET_EXTENSION",
                severity = RuntimeAssetDiagnosticSeverity.WARNING,
                message = "Files with an extension unsupported by BridgeConverter were not staged.",
                details = "$kindAndExtension: $count file(s)",
            )
        }

        val canonical = resolveCanonicalPathCollisions(candidates)
        val toolResolved = resolveToolDirectoryConflicts(canonical, diagnostics)
        val collisionResolved = when (spriteCollisionPolicy) {
            SpriteBasenameCollisionPolicy.REPORT_ONLY -> toolResolved
            SpriteBasenameCollisionPolicy.V159_JAR_CENTRAL_DIRECTORY_LAST_WINS ->
                resolveV159OrdinarySpriteBasenameCollisions(toolResolved, diagnostics)
        }
        addRemainingLogicalCollisionDiagnostics(collisionResolved, diagnostics)
        val files = collisionResolved.sortedWith(candidateOrdering).map { candidate ->
            RuntimeAssetFile(
                kind = candidate.kind,
                outputPath = candidate.outputPath,
                sourceEntryPath = candidate.sourceEntryPath,
                sourceEntrySizeBytes = candidate.bytes.size.toLong(),
                sourceEntrySha256 = candidate.sha256,
                bytes = candidate.bytes,
            )
        }
        return RuntimeAssetSnapshot(
            sourceJar = sourceJar,
            resolvedRootPrefix = resolvedRootPrefix,
            files = files,
            diagnostics = diagnostics.sortedWith(diagnosticOrdering),
            scannedEntryCount = scannedEntries,
            candidateFileCount = candidates.size,
            candidateExpandedBytes = expandedBytes,
            stagedBytes = files.sumOf { it.sourceEntrySizeBytes },
            stagingSha256 = stagingHash(files),
        )
    }

    fun stage(
        jar: Path,
        outputDirectory: Path,
        limits: RuntimeAssetLimits = RuntimeAssetLimits(),
    ): RuntimeAssetSnapshot = scan(jar, limits).also { it.writeTo(outputDirectory) }

    /** Mirrors Mods.resolveRoot: strip exactly one top-level child only when that child is a directory. */
    private fun resolveRootPrefix(entries: List<ArchiveEntryRef>): String? {
        data class TopLevelChild(var fileAtRoot: Boolean = false, var directory: Boolean = false)

        val children = linkedMapOf<String, TopLevelChild>()
        entries.forEach { ref ->
            val first = ref.path.substringBefore('/')
            val child = children.getOrPut(first) { TopLevelChild() }
            if ('/' in ref.path || ref.entry.isDirectory) {
                child.directory = true
            } else {
                child.fileAtRoot = true
            }
        }
        if (children.size != 1) return null
        val (name, child) = children.entries.single()
        return name.takeIf { child.directory && !child.fileAtRoot }
    }

    private fun relativeToResolvedRoot(ref: ArchiveEntryRef, prefix: String?): String? {
        if (prefix == null) return ref.path
        if (ref.path == prefix) return null
        val expected = "$prefix/"
        if (!ref.path.startsWith(expected)) {
            throw RuntimeAssetStagingException(
                "JAR entry unexpectedly falls outside resolved root '$prefix': ${ref.path}",
            )
        }
        return ref.path.removePrefix(expected)
    }

    private fun classify(path: String): Classification? {
        val segments = path.split('/')
        if (segments.size < 2) return null
        val root = segments[0].lowercase(Locale.ROOT)
        val kind = RuntimeAssetKind.fromRoot(root) ?: return null
        val remainder = segments.drop(1)
        if (kind == RuntimeAssetKind.BUNDLE && remainder.size != 1) return null
        val outputPath = "$root/${remainder.joinToString("/")}"
        val toolDirectory = when (kind) {
            RuntimeAssetKind.SPRITE -> remainder.firstOrNull()?.equals("pre-processed", ignoreCase = true) == true
            RuntimeAssetKind.BUNDLE,
            RuntimeAssetKind.SOUND,
            RuntimeAssetKind.MUSIC -> false
        }
        return Classification(kind, outputPath, toolDirectory)
    }

    private fun excludedRoot(path: String): String? {
        val segments = path.split('/')
        val root = segments.firstOrNull()?.lowercase(Locale.ROOT) ?: return null
        return root.takeIf { it in explicitlyExcludedRoots }
    }

    private fun resolveCanonicalPathCollisions(candidates: List<Candidate>): List<Candidate> {
        val selected = mutableListOf<Candidate>()
        candidates.groupBy { it.outputPath.lowercase(Locale.ROOT) }.toSortedMap().forEach { (_, group) ->
            if (group.size == 1) {
                selected += group.single()
                return@forEach
            }
            throw RuntimeAssetStagingException(
                "Ambiguous case-insensitive JAR asset path '${group.first().outputPath}': " +
                    group.sortedWith(candidateOrdering).joinToString { it.sourceEntryPath },
            )
        }
        return selected
    }

    private fun resolveToolDirectoryConflicts(
        candidates: List<Candidate>,
        diagnostics: MutableList<RuntimeAssetDiagnostic>,
    ): List<Candidate> {
        val removed = hashSetOf<Candidate>()
        candidates.groupBy(::logicalNameKey).toSortedMap().forEach { (_, group) ->
            val formal = group.filterNot { it.toolDirectory }
            val tool = group.filter { it.toolDirectory }
            if (tool.isEmpty()) return@forEach
            if (formal.isEmpty()) {
                tool.sortedWith(candidateOrdering).forEach { retained ->
                    diagnostics += RuntimeAssetDiagnostic(
                        code = "PREPROCESSED_SPRITE_RETAINED",
                        severity = RuntimeAssetDiagnosticSeverity.INFO,
                        message = "A recursively loaded sprites/pre-processed asset has no formal same-name sprite and was retained.",
                        sourceEntryPath = retained.sourceEntryPath,
                        outputPath = retained.outputPath,
                    )
                }
                return@forEach
            }
            val preferred = formal.sortedWith(candidateOrdering).first()
            tool.sortedWith(candidateOrdering).forEach { loser ->
                removed += loser
                diagnostics += RuntimeAssetDiagnostic(
                    code = "PREPROCESSED_SPRITE_SHADOWED",
                    severity = RuntimeAssetDiagnosticSeverity.WARNING,
                    message = "A sprites/pre-processed helper file was omitted because a formal sprite with the same basename exists.",
                    sourceEntryPath = loser.sourceEntryPath,
                    relatedSourceEntryPaths = formal.sortedWith(candidateOrdering).map { it.sourceEntryPath },
                    outputPath = preferred.outputPath,
                )
            }
        }
        return candidates.filterNot { it in removed }
    }

    /**
     * Resolves only ordinary sprite basename collisions. Generated/formal pairs and every audio
     * collision remain untouched so the later common validator can report them as unresolved.
     */
    private fun resolveV159OrdinarySpriteBasenameCollisions(
        candidates: List<Candidate>,
        diagnostics: MutableList<RuntimeAssetDiagnostic>,
    ): List<Candidate> {
        val removed = hashSetOf<Candidate>()
        candidates.asSequence()
            .filter { it.kind == RuntimeAssetKind.SPRITE }
            .groupBy(::logicalNameKey)
            .toSortedMap()
            .forEach { (_, group) ->
                if (group.size <= 1 || validGeneratedSpritePair(group)) return@forEach
                if (group.any { it.outputPath.startsWith("sprites/generated/", ignoreCase = true) }) {
                    return@forEach
                }

                val ordered = group.sortedBy { it.centralDirectoryOrdinal }
                val winner = ordered.last()
                val losers = ordered.dropLast(1)
                val bytesDiffer = losers.any { !it.bytes.contentEquals(winner.bytes) }
                removed += losers
                diagnostics += RuntimeAssetDiagnostic(
                    code = "V159_SPRITE_BASENAME_LAST_WINS",
                    severity = if (bytesDiffer) {
                        RuntimeAssetDiagnosticSeverity.WARNING
                    } else {
                        RuntimeAssetDiagnosticSeverity.INFO
                    },
                    message = if (bytesDiffer) {
                        "Ordinary sprites shared one v159 basename with different bytes; the later JAR entry was selected explicitly."
                    } else {
                        "Byte-identical ordinary sprites shared one v159 basename; the later JAR entry was selected explicitly."
                    },
                    sourceEntryPath = winner.sourceEntryPath,
                    relatedSourceEntryPaths = losers.map { it.sourceEntryPath },
                    outputPath = winner.outputPath,
                    details = buildString {
                        append("orderBasis=release JAR central-directory ordinal ")
                        append("(deterministic v159 mapper approximation; verified against v159.7 Arc ZipFi.findAll ")
                        append("for New Horizon 2.2.1 large-launcher); ")
                        append("winner=").append(winner.sourceEntryPath)
                        append('@').append(winner.centralDirectoryOrdinal)
                        append("; losers=")
                        append(losers.joinToString(",") { "${it.sourceEntryPath}@${it.centralDirectoryOrdinal}" })
                        append("; bytesDifferent=").append(bytesDiffer)
                    },
                )
            }
        return candidates.filterNot { it in removed }
    }

    private fun addRemainingLogicalCollisionDiagnostics(
        candidates: List<Candidate>,
        diagnostics: MutableList<RuntimeAssetDiagnostic>,
    ) {
        candidates.groupBy(::logicalNameKey).toSortedMap().forEach { (_, group) ->
            if (group.size <= 1 || validGeneratedSpritePair(group)) return@forEach
            diagnostics += RuntimeAssetDiagnostic(
                code = "UNRESOLVED_RUNTIME_ASSET_BASENAME_COLLISION",
                severity = RuntimeAssetDiagnosticSeverity.ERROR,
                message = "Multiple staged assets still share one v159 basename namespace.",
                sourceEntryPath = group.sortedWith(candidateOrdering).first().sourceEntryPath,
                relatedSourceEntryPaths = group.sortedWith(candidateOrdering).drop(1).map { it.sourceEntryPath },
                details = "basename=${basename(group.first().outputPath)}",
            )
        }
        candidates.filter { it.kind == RuntimeAssetKind.SOUND || it.kind == RuntimeAssetKind.MUSIC }
            .groupBy { basename(it.outputPath).lowercase(Locale.ROOT) }
            .toSortedMap()
            .forEach { (_, group) ->
                if (group.any { it.kind == RuntimeAssetKind.SOUND } && group.any { it.kind == RuntimeAssetKind.MUSIC }) {
                    diagnostics += RuntimeAssetDiagnostic(
                        code = "UNRESOLVED_RUNTIME_AUDIO_NAMESPACE_COLLISION",
                        severity = RuntimeAssetDiagnosticSeverity.ERROR,
                        message = "A sound and music file share the same v159 audio basename.",
                        sourceEntryPath = group.sortedWith(candidateOrdering).first().sourceEntryPath,
                        relatedSourceEntryPaths = group.sortedWith(candidateOrdering).drop(1).map { it.sourceEntryPath },
                        details = "basename=${basename(group.first().outputPath)}",
                    )
                }
            }
    }

    private fun validGeneratedSpritePair(group: List<Candidate>): Boolean =
        group.size == 2 &&
            group.all { it.kind == RuntimeAssetKind.SPRITE } &&
            group.count { it.outputPath.startsWith("sprites/generated/", ignoreCase = true) } == 1

    private fun logicalNameKey(candidate: Candidate): String =
        "${candidate.kind.root}/${basename(candidate.outputPath).lowercase(Locale.ROOT)}"

    private fun basename(path: String): String = path.substringAfterLast('/').substringBeforeLast('.')

    private fun normalizeJarPath(raw: String, limits: RuntimeAssetLimits): String {
        if (raw.isBlank()) throw RuntimeAssetStagingException("JAR contains an empty path")
        if ('\u0000' in raw) throw RuntimeAssetStagingException("JAR path contains a NUL character")
        val replaced = raw.replace('\\', '/')
        if (replaced.startsWith('/')) throw RuntimeAssetStagingException("Absolute JAR paths are not allowed: $raw")
        if (Regex("^[A-Za-z]:").containsMatchIn(replaced) || ':' in replaced) {
            throw RuntimeAssetStagingException("Drive or colon JAR paths are not allowed: $raw")
        }
        val segments = replaced.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." }) {
            throw RuntimeAssetStagingException("JAR path traversal is not allowed: $raw")
        }
        val normalized = segments.joinToString("/")
        if (normalized.length > limits.maxPathLength) {
            throw RuntimeAssetStagingException("JAR path exceeds ${limits.maxPathLength} characters: $raw")
        }
        return normalized
    }

    private fun readLimited(
        input: InputStream,
        entryLimit: Long,
        remainingTotal: Long,
        name: String,
    ): ByteArray {
        if (remainingTotal < 0) throw RuntimeAssetStagingException("JAR expanded asset limit exceeded")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var readTotal = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            readTotal += read
            if (readTotal > entryLimit) {
                throw RuntimeAssetStagingException("JAR asset exceeds the per-entry limit while reading: $name")
            }
            if (readTotal > remainingTotal) {
                throw RuntimeAssetStagingException("JAR expanded asset limit exceeded while reading: $name")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun stagingHash(files: List<RuntimeAssetFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            val pathBytes = file.outputPath.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(pathBytes.size).array())
            digest.update(pathBytes)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(file.sourceEntrySizeBytes).array())
            digest.update(file.rawBytes())
        }
        return digest.hex()
    }

    private fun sha256(path: Path): String =
        Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.hex()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    private fun requireWithin(value: Long, limit: Long, message: String) {
        if (value > limit) throw RuntimeAssetStagingException("$message ($value > $limit)")
    }

    private fun validateLimits(limits: RuntimeAssetLimits) {
        if (limits.maxArchiveBytes <= 0 || limits.maxEntries <= 0 || limits.maxEntryBytes <= 0 ||
            limits.maxExpandedBytes <= 0 || limits.maxCompressionRatio <= 0 || limits.maxPathLength <= 0
        ) {
            throw IllegalArgumentException("All runtime asset limits must be positive")
        }
    }

    private val candidateOrdering =
        compareBy<Candidate> { it.outputPath.lowercase(Locale.ROOT) }
            .thenBy { it.outputPath }
            .thenBy { it.sourceEntryPath.lowercase(Locale.ROOT) }
            .thenBy { it.sourceEntryPath }

    private val diagnosticOrdering =
        compareBy<RuntimeAssetDiagnostic> { it.code }
            .thenBy { it.sourceEntryPath.orEmpty().lowercase(Locale.ROOT) }
            .thenBy { it.sourceEntryPath.orEmpty() }
            .thenBy { it.details.orEmpty() }

    private data class Classification(
        val kind: RuntimeAssetKind,
        val outputPath: String,
        val toolDirectory: Boolean,
    )

    private data class ArchiveEntryRef(
        val entry: ZipArchiveEntry,
        val path: String,
        val centralDirectoryOrdinal: Int,
    )

    private data class ResolvedEntry(
        val entry: ZipArchiveEntry,
        val sourcePath: String,
        val relativePath: String,
        val centralDirectoryOrdinal: Int,
    )

    private class Candidate(
        val kind: RuntimeAssetKind,
        val outputPath: String,
        val sourceEntryPath: String,
        val centralDirectoryOrdinal: Int,
        val toolDirectory: Boolean,
        val bytes: ByteArray,
        val sha256: String,
    )
}
