package io.github.moddpbridge.javastatic.hybrid

import io.github.moddpbridge.converter.DetectedSourceKind
import io.github.moddpbridge.converter.RuntimePreparedConversion
import io.github.moddpbridge.converter.StaticExportContext
import io.github.moddpbridge.converter.StaticSourceFile
import io.github.moddpbridge.javastatic.JavaAstStaticExporter
import io.github.moddpbridge.model.ContentKind
import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class HybridSourceLimits(
    val maxEntries: Int = 20_000,
    val maxSourceFiles: Int = 4_096,
    val maxJavaFileBytes: Long = 4L * 1024L * 1024L,
    val maxExpandedJavaBytes: Long = 64L * 1024L * 1024L,
    val maxCandidates: Int = 4_096,
    val maxGeneratedFiles: Int = 8_192,
    val maxGeneratedFileBytes: Long = 8L * 1024L * 1024L,
    val maxGeneratedBytes: Long = 128L * 1024L * 1024L,
    val maxCompressionRatio: Double = 200.0,
    val maxPathLength: Int = 1_024,
    val maxSnapshotBytes: Long = 32L * 1024L * 1024L,
    val maxSourceIndexBytes: Long = 32L * 1024L * 1024L,
)

data class RuntimeStaticHybridRequest(
    val snapshot: Path,
    val source: Path,
    val sourceIndexReport: Path,
    val runtimePrepared: RuntimePreparedConversion,
    val options: HybridCandidateOptions = HybridCandidateOptions(),
    val limits: HybridSourceLimits = HybridSourceLimits(),
)

/**
 * Safe, non-executing front door for the hybrid candidate stage. The release JAR has already been
 * executed by the extractor; this stage reads only retained JSON reports and Java source text.
 */
object RuntimeStaticHybrid {
    fun prepare(request: RuntimeStaticHybridRequest): HybridCandidateSet {
        request.limits.validate()
        require(!Files.isSymbolicLink(request.snapshot)) { "Runtime snapshot symlinks are not accepted: ${request.snapshot}" }
        require(!Files.isSymbolicLink(request.source)) { "Source input symlinks are not accepted: ${request.source}" }
        require(!Files.isSymbolicLink(request.sourceIndexReport)) {
            "Source-index report symlinks are not accepted: ${request.sourceIndexReport}"
        }
        require(Files.isRegularFile(request.snapshot)) { "Runtime snapshot is not a file: ${request.snapshot}" }
        require(Files.exists(request.source)) { "Optional source input does not exist: ${request.source}" }
        require(Files.isRegularFile(request.sourceIndexReport)) {
            "Source-index report is not a file: ${request.sourceIndexReport}"
        }
        val snapshot = readSnapshot(request.snapshot, request.limits)
        val provenance = readSourceIndex(request.sourceIndexReport, request.limits)
        val sources = HybridJavaSourceReader.read(request.source, request.limits)
        val staticExport = JavaAstStaticExporter().export(
            StaticExportContext(
                detectedKind = DetectedSourceKind.MOD,
                sourceName = request.source.fileName?.toString() ?: "source",
                slug = snapshot.targetMod,
                modNamespace = snapshot.targetMod,
                files = sources,
            ),
        )
        validateStaticExportBudget(staticExport, request.limits)
        val candidates = RuntimeStaticHybridCore.buildCandidates(
            runtimePrepared = request.runtimePrepared,
            runtimeContents = snapshot.contents,
            staticExport = staticExport,
            sourceClassProvenance = provenance,
            options = request.options,
        )
        return candidates.withAdditionalFindings(
            additionalRejected = snapshot.rejected,
            additionalDiagnostics = snapshot.diagnostics,
            additionalLogs = listOf(
                "Read ${sources.size} bounded Java source file(s) without executing or building the source repository.",
                "Parsed ${snapshot.contents.size} eligible runtime Block/Unit fallback record(s) for '${snapshot.targetMod}'.",
                "Read ${provenance.size} source-to-release-JAR class provenance record(s).",
            ),
        )
    }

    private fun readSnapshot(path: Path, limits: HybridSourceLimits): SnapshotInventory {
        checkFileSize(path, limits.maxSnapshotBytes, "runtime snapshot")
        val root = parseJson(path, limits.maxSnapshotBytes)
        require(root.int("schemaVersion") == 2) { "Hybrid mapping requires runtime snapshot schema 2." }
        val game = root["gameVersion"] as? JsonObject
            ?: throw IllegalArgumentException("Runtime snapshot has no gameVersion object.")
        require(game.string("type").equals("official", ignoreCase = true) &&
            game.string("modifier").equals("release", ignoreCase = true) &&
            game.int("build") == 159 && game.int("revision") == 7
        ) { "Hybrid mapping accepts only the official Mindustry v159.7 release snapshot." }
        val targetMod = root.string("targetMod")?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Runtime snapshot has no targetMod.")
        val records = root["contents"] as? JsonArray
            ?: throw IllegalArgumentException("Runtime snapshot has no contents array.")
        val contents = mutableListOf<HybridRuntimeContent>()
        val rejected = mutableListOf<HybridRejectedCandidate>()
        val diagnostics = mutableListOf<Diagnostic>()
        records.forEachIndexed { index, element ->
            val record = element as? JsonObject ?: return@forEachIndexed
            if (!record.string("modName").equals(targetMod, ignoreCase = true)) return@forEachIndexed
            val kind = when (record.string("contentType")?.lowercase(Locale.ROOT)) {
                "block" -> ContentKind.BLOCK
                "unit" -> ContentKind.UNIT
                else -> return@forEachIndexed
            }
            val fullName = record.string("name")?.takeIf(String::isNotBlank) ?: return@forEachIndexed
            val location = SourceLocation(path.toString(), "$.contents[$index]")
            val localName = fullName.removePrefixIgnoreCase("$targetMod-")
            val phases = record["runtimeSnapshots"] as? JsonObject
            val phaseKeys = listOf("preContentInit", "postContentInit", "finalAfterModInit")
            val indexedFallbacks = phaseKeys.mapIndexedNotNull { phaseIndex, phase ->
                val snapshot = phases?.get(phase) as? JsonObject ?: return@mapIndexedNotNull null
                val fallback = snapshot["sourceClassMapFallback"] as? JsonObject ?: return@mapIndexedNotNull null
                phaseIndex to FallbackRecord(
                    parserName = fallback.string("parserName"),
                    loadableRoot = fallback["loadableRoot"]?.jsonPrimitive?.booleanOrNull,
                )
            }
            val fallbackRecords = indexedFallbacks.map { it.second }
            val presentPhaseIndices = indexedFallbacks.map { it.first }
            val monotonicPresence = presentPhaseIndices.isNotEmpty() &&
                presentPhaseIndices == (presentPhaseIndices.first()..phaseKeys.lastIndex).toList()
            val fallbackNames = fallbackRecords.mapNotNull(FallbackRecord::parserName).distinct()
            val rejection = when {
                localName == null || localName.isBlank() ->
                    "Runtime name '$fullName' does not begin with the authoritative Mod namespace '$targetMod-'."
                phaseKeys.lastIndex !in presentPhaseIndices ->
                    "The authoritative FINAL_AFTER_MOD_INIT runtime phase is missing."
                !monotonicPresence ->
                    "Runtime phase presence is not monotonic for '$fullName'."
                fallbackNames.size != 1 ->
                    "The runtime phases where this Content exists do not expose one consistent parser fallback."
                fallbackRecords.any { it.loadableRoot != true } ->
                    "The runtime parser fallback is not marked as a loadable root in every phase where this Content exists."
                else -> null
            }
            if (rejection != null) {
                val code = "HYBRID_RUNTIME_FALLBACK_INVALID"
                rejected += HybridRejectedCandidate(
                    outputPath = localName?.let { "content/${kind.folderName}/$it.hjson" },
                    sourceSymbol = fullName,
                    kind = kind,
                    reason = rejection,
                    diagnosticCodes = listOf(code),
                    location = location,
                    runtimeName = fullName,
                )
                diagnostics += Diagnostic(
                    code = code,
                    severity = DiagnosticSeverity.WARNING,
                    message = rejection,
                    stage = ValidationStage.STRUCTURE,
                    location = location,
                )
            } else {
                contents += HybridRuntimeContent(
                    fullName = fullName,
                    localName = requireNotNull(localName),
                    kind = kind,
                    fallbackType = fallbackNames.single(),
                    snapshotLocation = location,
                )
            }
        }
        return SnapshotInventory(targetMod, contents, rejected, diagnostics)
    }

    private fun readSourceIndex(path: Path, limits: HybridSourceLimits): List<HybridSourceClassProvenance> {
        checkFileSize(path, limits.maxSourceIndexBytes, "source-index report")
        val root = parseJson(path, limits.maxSourceIndexBytes)
        require(root.int("schemaVersion") == 1) { "Hybrid mapping requires source-index report schema 1." }
        val classes = root["classes"] as? JsonArray
            ?: throw IllegalArgumentException("Source-index report has no classes array.")
        return classes.mapNotNull { element ->
            val record = element as? JsonObject ?: return@mapNotNull null
            val match = record.string("match")
            if (match != "source_file" && match != "derived_top_level_name") return@mapNotNull null
            val sourcePath = record.string("sourcePath") ?: return@mapNotNull null
            val jarPath = record.string("jarPath") ?: return@mapNotNull null
            HybridSourceClassProvenance(
                sourcePath = sourcePath,
                jarPath = jarPath,
                sourceCoversRuntimeLines = record["sourceCoversRuntimeLines"]?.jsonPrimitive?.booleanOrNull,
                runtimeLineNumbers = (record["lineNumbers"] as? JsonArray).orEmpty()
                    .mapNotNull { it.jsonPrimitive.intOrNull },
            )
        }
    }

    private fun parseJson(path: Path, maxBytes: Long): JsonObject = try {
        JSON.parseToJsonElement(readBoundedText(path, maxBytes)).jsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("Could not parse JSON report '$path': ${error.message}", error)
    }

    private fun readBoundedText(path: Path, maxBytes: Long): String {
        val bytes = Files.newInputStream(path).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "JSON report exceeds the $maxBytes byte limit: $path" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun checkFileSize(path: Path, maxBytes: Long, label: String) {
        val size = Files.size(path)
        require(size <= maxBytes) { "$label exceeds the $maxBytes byte limit: $path ($size bytes)" }
    }

    private data class SnapshotInventory(
        val targetMod: String,
        val contents: List<HybridRuntimeContent>,
        val rejected: List<HybridRejectedCandidate>,
        val diagnostics: List<Diagnostic>,
    )

    private data class FallbackRecord(val parserName: String?, val loadableRoot: Boolean?)

    private val JSON = Json { ignoreUnknownKeys = true }
}

private object HybridJavaSourceReader {
    fun read(source: Path, limits: HybridSourceLimits): List<StaticSourceFile> =
        if (source.isDirectory()) readDirectory(source, limits) else readArchive(source, limits)

    private fun readDirectory(root: Path, limits: HybridSourceLimits): List<StaticSourceFile> {
        require(!Files.isSymbolicLink(root)) { "Source directory symlinks are not accepted: $root" }
        val normalizedRoot = root.toAbsolutePath().normalize()
        val paths = Files.walk(normalizedRoot).use { stream -> stream.limit(limits.maxEntries.toLong() + 1L).toList() }
        require(paths.size <= limits.maxEntries) { "Source directory exceeds the ${limits.maxEntries} entry limit." }
        val budget = ReadBudget(limits.maxExpandedJavaBytes)
        val sources = paths.asSequence()
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .map { normalizedRoot.relativize(it.toAbsolutePath().normalize()).toString() to it }
            .filter { (relative, _) -> isJavaSource(relative) }
            .sortedBy { (relative, _) -> normalizeSourcePath(relative) }
            .map { (relative, file) ->
                val path = checkedSourcePath(relative, limits)
                StaticSourceFile(
                    path,
                    Files.newInputStream(file).use { it.readBounded(limits.maxJavaFileBytes, budget, path) },
                )
            }
            .toList()
        require(sources.size <= limits.maxSourceFiles) {
            "Source directory exceeds the ${limits.maxSourceFiles} Java-file limit."
        }
        return sources
    }

    private fun readArchive(archive: Path, limits: HybridSourceLimits): List<StaticSourceFile> {
        require(Files.isRegularFile(archive)) { "Source input is neither a directory nor a regular archive: $archive" }
        val budget = ReadBudget(limits.maxExpandedJavaBytes)
        ZipFile(archive.toFile()).use { zip ->
            val entries = mutableListOf<java.util.zip.ZipEntry>()
            val enumeration = zip.entries()
            var entryCount = 0
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                entryCount++
                require(entryCount <= limits.maxEntries) {
                    "Source archive exceeds the ${limits.maxEntries} entry limit."
                }
                if (!entry.isDirectory) entries += entry
            }
            val seen = hashSetOf<String>()
            val sources = entries.asSequence()
                .map { entry -> checkedSourcePath(entry.name, limits) to entry }
                .filter { (path, _) -> isJavaSource(path) }
                .sortedBy { (path, _) -> path }
                .map { (path, entry) ->
                    require(seen.add(path.lowercase(Locale.ROOT))) { "Duplicate source archive path: $path" }
                    val declared = entry.size
                    require(declared < 0L || declared <= limits.maxJavaFileBytes) {
                        "Java source '$path' exceeds the ${limits.maxJavaFileBytes} byte limit."
                    }
                    val compressed = entry.compressedSize
                    if (declared > 0L && compressed >= 0L) {
                        require(compressed > 0L && declared.toDouble() / compressed <= limits.maxCompressionRatio) {
                            "Java source '$path' exceeds the ${limits.maxCompressionRatio} compression-ratio limit."
                        }
                    }
                    StaticSourceFile(
                        path,
                        zip.getInputStream(entry).use { it.readBounded(limits.maxJavaFileBytes, budget, path) },
                    )
                }
                .toList()
            require(sources.size <= limits.maxSourceFiles) {
                "Source archive exceeds the ${limits.maxSourceFiles} Java-file limit."
            }
            return sources
        }
    }

    private fun checkedSourcePath(raw: String, limits: HybridSourceLimits): String {
        val replaced = raw.replace('\\', '/')
        require(!replaced.startsWith('/') && !DRIVE_PREFIX.containsMatchIn(replaced)) { "Absolute source path is forbidden: '$raw'" }
        val normalized = replaced.trim('/')
        require(normalized.isNotBlank() && normalized.length <= limits.maxPathLength) { "Invalid source path length: '$raw'" }
        require(normalized.none { it == '\u0000' || it.code < 0x20 || it.code == 0x7f }) {
            "Control characters are forbidden in source paths: '$raw'"
        }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe source path: '$raw'" }
        return normalized
    }

    private fun isJavaSource(raw: String): Boolean {
        val path = normalizeSourcePath(raw)
        return path.endsWith(".java", ignoreCase = true) &&
            path.split('/').none { it.equals("assets", ignoreCase = true) }
    }

    private class ReadBudget(private val maxBytes: Long) {
        private var consumed = 0L

        fun consume(count: Int) {
            consumed += count
            require(consumed <= maxBytes) { "Reading Java sources exceeded the $maxBytes expanded-byte limit." }
        }
    }

    private fun InputStream.readBounded(maxBytes: Long, budget: ReadBudget, path: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "Java source '$path' exceeds the $maxBytes byte limit." }
            budget.consume(read)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun String.removePrefixIgnoreCase(prefix: String): String? =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else null

private fun normalizeSourcePath(path: String): String = path.replace('\\', '/').trim('/')

private fun HybridSourceLimits.validate() {
    require(maxEntries > 0 && maxSourceFiles > 0 && maxCandidates > 0 && maxGeneratedFiles > 0) {
        "Hybrid source count limits must be positive."
    }
    require(
        maxJavaFileBytes > 0L && maxExpandedJavaBytes > 0L && maxGeneratedFileBytes > 0L &&
            maxGeneratedBytes > 0L && maxSnapshotBytes > 0L && maxSourceIndexBytes > 0L,
    ) { "Hybrid source byte limits must be positive." }
    require(maxCompressionRatio.isFinite() && maxCompressionRatio > 0.0) {
        "Hybrid source compression ratio must be finite and positive."
    }
    require(maxPathLength > 0) { "Hybrid source path-length limit must be positive." }
}

private fun validateStaticExportBudget(
    export: io.github.moddpbridge.converter.StaticExportResult,
    limits: HybridSourceLimits,
) {
    require(export.generatedFiles.size <= limits.maxGeneratedFiles) {
        "Static export produced ${export.generatedFiles.size} files, exceeding the ${limits.maxGeneratedFiles} limit."
    }
    val eligibleCandidates = export.contentResults.count {
        it.kind == ContentKind.BLOCK || it.kind == ContentKind.UNIT
    }
    require(eligibleCandidates <= limits.maxCandidates) {
        "Static export produced $eligibleCandidates Block/Unit candidates, exceeding the ${limits.maxCandidates} limit."
    }
    var generatedBytes = 0L
    export.generatedFiles.forEach { file ->
        require(file.bytes.size.toLong() <= limits.maxGeneratedFileBytes) {
            "Static output '${file.outputPath}' exceeds the ${limits.maxGeneratedFileBytes} byte limit."
        }
        generatedBytes = Math.addExact(generatedBytes, file.bytes.size.toLong())
        require(generatedBytes <= limits.maxGeneratedBytes) {
            "Static outputs exceed the ${limits.maxGeneratedBytes} total-byte limit."
        }
    }
}
