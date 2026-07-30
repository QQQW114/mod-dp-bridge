package io.github.moddpbridge.converter

import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceKind
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

internal data class SourceEntry(
    val path: String,
    val bytes: ByteArray,
) {
    val basename: String = path.substringAfterLast('/')
    val extension: String = basename.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

internal data class SourceSnapshot(
    val input: Path,
    val originalName: String,
    val physicalKind: SourceKind,
    val entries: List<SourceEntry>,
    val totalBytes: Long,
    val sha256: String,
    val strippedRoot: String? = null,
)

internal object SafeSourceReader {
    private val archiveExtensions = setOf("zip", "jar")
    private val reservedRootSegments = setOf(
        "assets",
        "content",
        "patches",
        "bundles",
        "sprites",
        "sounds",
        "music",
        "meta-inf",
    )

    fun read(
        input: Path,
        limits: SecurityLimits,
        logger: ConverterLogger,
        diagnostics: MutableList<Diagnostic>,
    ): SourceSnapshot {
        val absolute = input.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(absolute)) {
            throw ConversionException("Symbolic-link inputs are not allowed: $absolute")
        }
        return when {
            Files.isDirectory(absolute) -> readDirectory(absolute, limits, logger, diagnostics)
            Files.isRegularFile(absolute) && extensionOf(absolute) in archiveExtensions ->
                readArchive(absolute, limits, logger, diagnostics)

            Files.isRegularFile(absolute) -> readSingleFile(absolute, limits)
            else -> throw ConversionException("Input must be a directory or regular file: $absolute")
        }
    }

    private fun readSingleFile(input: Path, limits: SecurityLimits): SourceSnapshot {
        val size = Files.size(input)
        requireWithin(size, limits.maxInputBytes, "Input file exceeds the hard size limit")
        requireWithin(size, limits.maxEntryBytes, "Input file exceeds the per-entry size limit")
        val entry = SourceEntry(normalizeArchivePath(input.fileName.toString(), limits), Files.readAllBytes(input))
        return SourceSnapshot(
            input = input,
            originalName = input.fileName.toString(),
            physicalKind = SourceKind.UNKNOWN,
            entries = listOf(entry),
            totalBytes = size,
            sha256 = sha256(entry.bytes),
        )
    }

    private fun readDirectory(
        input: Path,
        limits: SecurityLimits,
        logger: ConverterLogger,
        diagnostics: MutableList<Diagnostic>,
    ): SourceSnapshot {
        val rootReal = input.toRealPath()
        val entries = mutableListOf<SourceEntry>()
        var total = 0L
        Files.walkFileTree(input, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != input && Files.isSymbolicLink(dir)) {
                    throw ConversionException("Symbolic-link directories are not allowed: $dir")
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(file)) {
                    throw ConversionException("Symbolic-link files are not allowed: $file")
                }
                if (!attrs.isRegularFile) {
                    diagnostics += Diagnostic(
                        code = "NON_REGULAR_FILE_IGNORED",
                        severity = DiagnosticSeverity.WARNING,
                        message = "A non-regular file was ignored.",
                        stage = ValidationStage.STRUCTURE,
                        location = SourceLocation(input.relativize(file).toString()),
                    )
                    return FileVisitResult.CONTINUE
                }
                val real = file.toRealPath()
                if (!real.startsWith(rootReal)) {
                    throw ConversionException("Directory entry escapes the input root: $file")
                }
                if (entries.size >= limits.maxEntries) {
                    throw ConversionException("Input contains more than ${limits.maxEntries} files")
                }
                val size = attrs.size()
                requireWithin(size, limits.maxEntryBytes, "Directory file exceeds the per-entry limit: $file")
                if (total + size > limits.maxExpandedBytes) {
                    throw ConversionException("Directory contents exceed ${limits.maxExpandedBytes} bytes")
                }
                val relative = normalizeArchivePath(input.relativize(file).toString(), limits)
                entries += SourceEntry(relative, Files.readAllBytes(file))
                total += size
                return FileVisitResult.CONTINUE
            }
        })
        val sorted = validateAndSort(entries)
        logger.log("Safely scanned ${sorted.size} directory files (${total} bytes).")
        return SourceSnapshot(
            input = input,
            originalName = input.fileName?.toString() ?: "directory",
            physicalKind = SourceKind.DIRECTORY,
            entries = sorted,
            totalBytes = total,
            sha256 = treeHash(sorted),
        )
    }

    @Suppress("DEPRECATION")
    private fun readArchive(
        input: Path,
        limits: SecurityLimits,
        logger: ConverterLogger,
        diagnostics: MutableList<Diagnostic>,
    ): SourceSnapshot {
        val archiveSize = Files.size(input)
        requireWithin(archiveSize, limits.maxInputBytes, "Archive exceeds the hard input size limit")
        val rawEntries = mutableListOf<SourceEntry>()
        var total = 0L
        ZipFile(input).use { zip ->
            val enumeration = zip.entries
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                if (rawEntries.size >= limits.maxEntries) {
                    throw ConversionException("Archive contains more than ${limits.maxEntries} files")
                }
                if (entry.isUnixSymlink) {
                    throw ConversionException("Archive symbolic links are not allowed: ${entry.name}")
                }
                if (!zip.canReadEntryData(entry)) {
                    throw ConversionException("Unsupported or encrypted ZIP entry: ${entry.name}")
                }
                val normalized = normalizeArchivePath(entry.name, limits)
                val declared = entry.size
                if (declared >= 0) {
                    requireWithin(declared, limits.maxEntryBytes, "Archive entry is too large: ${entry.name}")
                }
                val compressed = entry.compressedSize
                if (declared > 0 && compressed >= 0) {
                    val ratio = declared.toDouble() / compressed.coerceAtLeast(1L).toDouble()
                    if (ratio > limits.maxCompressionRatio) {
                        throw ConversionException(
                            "Archive compression ratio exceeds ${limits.maxCompressionRatio}:1: ${entry.name}",
                        )
                    }
                }
                val bytes = zip.getInputStream(entry).use {
                    readLimited(it, limits.maxEntryBytes, limits.maxExpandedBytes - total, entry.name)
                }
                total += bytes.size
                rawEntries += SourceEntry(normalized, bytes)
            }
        }
        val validated = validateAndSort(rawEntries)
        val (entries, strippedRoot) = stripCommonRoot(validated)
        if (strippedRoot != null) {
            logger.log("Stripped one common archive root directory: $strippedRoot")
            diagnostics += Diagnostic(
                code = "COMMON_ARCHIVE_ROOT_STRIPPED",
                severity = DiagnosticSeverity.INFO,
                message = "A single outer archive directory was removed.",
                stage = ValidationStage.STRUCTURE,
                details = strippedRoot,
            )
        }
        val finalEntries = validateAndSort(entries)
        logger.log("Safely read ${finalEntries.size} archive files (${total} expanded bytes).")
        return SourceSnapshot(
            input = input,
            originalName = input.fileName.toString(),
            physicalKind = SourceKind.ZIP_ARCHIVE,
            entries = finalEntries,
            totalBytes = archiveSize,
            sha256 = sha256(input),
            strippedRoot = strippedRoot,
        )
    }

    private fun readLimited(input: InputStream, entryLimit: Long, remainingTotal: Long, name: String): ByteArray {
        if (remainingTotal < 0) throw ConversionException("Archive expanded size limit exceeded")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var readTotal = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            readTotal += read
            if (readTotal > entryLimit) {
                throw ConversionException("Archive entry exceeds the per-entry limit while reading: $name")
            }
            if (readTotal > remainingTotal) {
                throw ConversionException("Archive expanded size limit exceeded while reading: $name")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun stripCommonRoot(entries: List<SourceEntry>): Pair<List<SourceEntry>, String?> {
        if (entries.isEmpty() || entries.any { '/' !in it.path }) return entries to null
        val roots = entries.map { it.path.substringBefore('/') }.distinct()
        if (roots.size != 1) return entries to null
        val root = roots.single()
        if (root.lowercase(Locale.ROOT) in reservedRootSegments) return entries to null
        return entries.map { entry ->
            SourceEntry(entry.path.substringAfter('/'), entry.bytes)
        } to root
    }

    private fun validateAndSort(entries: List<SourceEntry>): List<SourceEntry> {
        val seen = hashSetOf<String>()
        entries.forEach { entry ->
            val key = entry.path.lowercase(Locale.ROOT)
            if (!seen.add(key)) throw ConversionException("Duplicate input path: ${entry.path}")
        }
        return entries.sortedBy { it.path }
    }

    private fun normalizeArchivePath(raw: String, limits: SecurityLimits): String {
        require(raw.isNotBlank()) { "Input contains an empty path" }
        require(!raw.contains('\u0000')) { "Input path contains a NUL character" }
        val replaced = raw.replace('\\', '/')
        require(!replaced.startsWith('/')) { "Absolute paths are not allowed: $raw" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(replaced)) { "Drive paths are not allowed: $raw" }
        require(':' !in replaced) { "Colon is not allowed in input paths: $raw" }
        val segments = replaced.split('/').filter { it.isNotBlank() }
        require(segments.isNotEmpty()) { "Input contains an empty path: $raw" }
        require(segments.none { it == "." || it == ".." }) { "Path traversal is not allowed: $raw" }
        val normalized = segments.joinToString("/")
        require(normalized.length <= limits.maxPathLength) { "Input path is too long: $raw" }
        return normalized
    }

    private fun extensionOf(path: Path): String =
        path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun treeHash(entries: List<SourceEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sortedBy { it.path }.forEach { entry ->
            val path = entry.path.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(path.size).array())
            digest.update(path)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(entry.bytes.size.toLong()).array())
            digest.update(entry.bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireWithin(value: Long, limit: Long, message: String) {
        if (value > limit) throw ConversionException("$message ($value > $limit)")
    }
}
