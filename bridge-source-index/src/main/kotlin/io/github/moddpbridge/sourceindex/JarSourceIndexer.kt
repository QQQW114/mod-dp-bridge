package io.github.moddpbridge.sourceindex

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.TreeSet
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory

data class JarSourceIndexLimits(
    val maxEntriesPerInput: Int = 20_000,
    val maxClassBytes: Long = 16L * 1024L * 1024L,
    val maxSourceFileBytes: Long = 8L * 1024L * 1024L,
    val maxAssetBytes: Long = 256L * 1024L * 1024L,
    val maxReadBytesPerInput: Long = 1024L * 1024L * 1024L,
)

class JarSourceIndexLimitException(message: String) : IllegalArgumentException(message)

/**
 * Links the authoritative contents of a built Mod JAR to an optional source checkout/archive.
 *
 * Class files are inspected with ASM using only structural/debug attributes. No class is loaded,
 * initialized, reflected over or executed. Source-only classes and assets never enter the result.
 */
class JarSourceIndexer(
    private val limits: JarSourceIndexLimits = JarSourceIndexLimits(),
) {
    fun index(runtimeJar: Path, sourceRepository: Path): JarSourceIndex {
        require(Files.isRegularFile(runtimeJar)) { "Runtime JAR is not a file: $runtimeJar" }
        require(Files.exists(sourceRepository)) { "Source repository input does not exist: $sourceRepository" }

        val issues = mutableListOf<SourceIndexIssue>()
        val source = scanSourceRepository(sourceRepository, issues)
        val runtime = scanRuntimeJar(runtimeJar, issues)
        val sourceFilesByCoordinate = source.sourceFiles.groupBy { SourceCoordinate(it.packageName, it.fileName) }
        val sourceAssetsByPath = source.assets.groupBy(SourceAssetRecord::relativePath)

        val classes = runtime.classes.map { entry -> linkClass(entry, sourceFilesByCoordinate) }
        val assets = runtime.assets.map { entry -> linkAsset(entry, sourceAssetsByPath) }
        val matchedClasses = classes.filter { it.match == ClassSourceMatch.SOURCE_FILE || it.match == ClassSourceMatch.DERIVED_TOP_LEVEL_NAME }
        val summary = JarSourceIndexSummary(
            runtimeClassFiles = runtime.classEntryCount,
            parsedClassFiles = classes.size,
            matchedClassFiles = matchedClasses.size,
            matchedDistinctSourceFiles = matchedClasses.mapNotNull(RuntimeClassSource::sourcePath).distinct().size,
            runtimeAssets = assets.size,
            exactAssetMatches = assets.count { it.match == AssetSourceMatch.EXACT },
            ambiguousExactAssetMatches = assets.count { it.match == AssetSourceMatch.AMBIGUOUS_EXACT },
            changedAssetMatches = assets.count { it.match == AssetSourceMatch.HASH_MISMATCH },
            missingAssetMatches = assets.count { it.match == AssetSourceMatch.NOT_FOUND },
        )
        return JarSourceIndex(classes, assets, issues.toList(), summary)
    }

    private fun scanRuntimeJar(path: Path, issues: MutableList<SourceIndexIssue>): RuntimeScan {
        val budget = ReadBudget(limits.maxReadBytesPerInput, path)
        val classes = mutableListOf<RuntimeClassRecord>()
        val assets = mutableListOf<RuntimeAssetRecord>()
        var classEntryCount = 0
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            checkEntryCount(entries.size, path)
            entries.sortedBy { normalizePath(it.name) }.forEach { entry ->
                val normalized = normalizePath(entry.name)
                when {
                    normalized.endsWith(".class", ignoreCase = true) -> {
                        classEntryCount++
                        captureEntry(issues, "CLASS_INSPECTION_FAILED", normalized) {
                            val bytes = zip.getInputStream(entry).use {
                                it.readBounded(limits.maxClassBytes, budget, normalized)
                            }
                            inspectClass(normalized, bytes, issues)
                        }?.let(classes::add)
                    }
                    isRuntimeAsset(normalized) -> {
                        captureEntry(issues, "RUNTIME_ASSET_HASH_FAILED", normalized) {
                            val digest = zip.getInputStream(entry).use {
                                it.sha256Bounded(limits.maxAssetBytes, budget, normalized)
                            }
                            RuntimeAssetRecord(normalized, digest)
                        }?.let(assets::add)
                    }
                }
            }
        }
        return RuntimeScan(classEntryCount, classes, assets)
    }

    private fun scanSourceRepository(path: Path, issues: MutableList<SourceIndexIssue>): SourceScan =
        if (path.isDirectory()) scanSourceDirectory(path, issues) else scanSourceArchive(path, issues)

    private fun scanSourceArchive(path: Path, issues: MutableList<SourceIndexIssue>): SourceScan {
        val budget = ReadBudget(limits.maxReadBytesPerInput, path)
        val sourceFiles = mutableListOf<SourceFileRecord>()
        val assets = mutableListOf<SourceAssetRecord>()
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            checkEntryCount(entries.size, path)
            entries.sortedBy { normalizePath(it.name) }.forEach { entry ->
                val normalized = normalizePath(entry.name)
                when {
                    isSourceFile(normalized) -> captureEntry(issues, "SOURCE_FILE_READ_FAILED", normalized) {
                        val bytes = zip.getInputStream(entry).use {
                            it.readBounded(limits.maxSourceFileBytes, budget, normalized)
                        }
                        sourceFileRecord(normalized, bytes)
                    }?.let(sourceFiles::add)
                    assetRelativePath(normalized) != null -> captureEntry(
                        issues,
                        "SOURCE_ASSET_HASH_FAILED",
                        normalized,
                    ) {
                        val digest = zip.getInputStream(entry).use {
                            it.sha256Bounded(limits.maxAssetBytes, budget, normalized)
                        }
                        SourceAssetRecord(normalized, assetRelativePath(normalized)!!, digest)
                    }?.let(assets::add)
                }
            }
        }
        return SourceScan(sourceFiles, assets)
    }

    private fun scanSourceDirectory(root: Path, issues: MutableList<SourceIndexIssue>): SourceScan {
        val budget = ReadBudget(limits.maxReadBytesPerInput, root)
        val paths = Files.walk(root).use { stream ->
            stream.filter(Files::isRegularFile).sorted().toList()
        }
        checkEntryCount(paths.size, root)
        val sourceFiles = mutableListOf<SourceFileRecord>()
        val assets = mutableListOf<SourceAssetRecord>()
        paths.forEach { file ->
            val relative = normalizePath(root.relativize(file).toString())
            when {
                isSourceFile(relative) -> captureEntry(issues, "SOURCE_FILE_READ_FAILED", relative) {
                    val bytes = Files.newInputStream(file).use {
                        it.readBounded(limits.maxSourceFileBytes, budget, relative)
                    }
                    sourceFileRecord(relative, bytes)
                }?.let(sourceFiles::add)
                assetRelativePath(relative, root.fileName?.toString() == "assets") != null -> captureEntry(
                    issues,
                    "SOURCE_ASSET_HASH_FAILED",
                    relative,
                ) {
                    val digest = Files.newInputStream(file).use {
                        it.sha256Bounded(limits.maxAssetBytes, budget, relative)
                    }
                    val assetPath = assetRelativePath(relative, root.fileName?.toString() == "assets")!!
                    SourceAssetRecord(relative, assetPath, digest)
                }?.let(assets::add)
            }
        }
        return SourceScan(sourceFiles, assets)
    }

    private fun inspectClass(
        jarPath: String,
        bytes: ByteArray,
        issues: MutableList<SourceIndexIssue>,
    ): RuntimeClassRecord {
        var internalName: String? = null
        var sourceFileName: String? = null
        val lines = TreeSet<Int>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    internalName = name
                }

                override fun visitSource(source: String?, debug: String?) {
                    sourceFileName = source?.substringAfterLast('/')?.substringAfterLast('\\')
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitLineNumber(line: Int, start: Label) {
                        if (line > 0) lines += line
                    }
                }
            },
            ClassReader.SKIP_FRAMES,
        )
        val authoritativeName = requireNotNull(internalName) { "Class has no internal name" }
        val expectedPath = "$authoritativeName.class"
        if (jarPath != expectedPath) {
            issues += SourceIndexIssue(
                code = "CLASS_ENTRY_NAME_MISMATCH",
                path = jarPath,
                message = "Class bytes declare '$authoritativeName' (expected JAR path '$expectedPath').",
            )
        }
        return RuntimeClassRecord(jarPath, authoritativeName, sourceFileName, lines.toList())
    }

    private fun linkClass(
        runtime: RuntimeClassRecord,
        sourceFiles: Map<SourceCoordinate, List<SourceFileRecord>>,
    ): RuntimeClassSource {
        val packagePath = runtime.internalName.substringBeforeLast('/', "")
        val packageName = packagePath.replace('/', '.')
        val simpleBinaryName = runtime.internalName.substringAfterLast('/')
        val topLevelName = simpleBinaryName.substringBefore('$')
        val recordedFile = runtime.sourceFileName
        val lookupFile = recordedFile ?: "$topLevelName.java"
        val candidates = sourceFiles[SourceCoordinate(packageName, lookupFile)].orEmpty()
            .sortedBy(SourceFileRecord::path)
        val match = when {
            candidates.size > 1 -> ClassSourceMatch.AMBIGUOUS
            candidates.size == 1 && recordedFile != null -> ClassSourceMatch.SOURCE_FILE
            candidates.size == 1 -> ClassSourceMatch.DERIVED_TOP_LEVEL_NAME
            else -> ClassSourceMatch.NOT_FOUND
        }
        val selected = candidates.singleOrNull()
        return RuntimeClassSource(
            jarPath = runtime.jarPath,
            binaryName = runtime.internalName.replace('/', '.'),
            packageName = packageName,
            topLevelClassName = topLevelName,
            sourceFileName = recordedFile,
            lineNumbers = runtime.lineNumbers,
            match = match,
            sourcePath = selected?.path,
            sourceCandidates = candidates.map(SourceFileRecord::path),
            sourceLineCount = selected?.lineCount,
        )
    }

    private fun linkAsset(
        runtime: RuntimeAssetRecord,
        sourceAssets: Map<String, List<SourceAssetRecord>>,
    ): RuntimeAssetSource {
        val candidates = sourceAssets[runtime.path].orEmpty().sortedBy(SourceAssetRecord::path)
        val exact = candidates.filter { it.sha256 == runtime.sha256 }
        val match = when {
            exact.size == 1 -> AssetSourceMatch.EXACT
            exact.size > 1 -> AssetSourceMatch.AMBIGUOUS_EXACT
            candidates.isNotEmpty() -> AssetSourceMatch.HASH_MISMATCH
            else -> AssetSourceMatch.NOT_FOUND
        }
        return RuntimeAssetSource(
            jarPath = runtime.path,
            sha256 = runtime.sha256,
            match = match,
            sourcePath = exact.singleOrNull()?.path,
            sourceCandidates = candidates.map { SourceAssetCandidate(it.path, it.sha256) },
        )
    }

    private fun sourceFileRecord(path: String, bytes: ByteArray): SourceFileRecord {
        val text = bytes.toString(StandardCharsets.ISO_8859_1)
        val packageName = PACKAGE_PATTERN.find(text)?.groupValues?.get(1).orEmpty()
        val lineCount = if (bytes.isEmpty()) 0 else bytes.count { it == '\n'.code.toByte() } + 1
        return SourceFileRecord(path, path.substringAfterLast('/'), packageName, lineCount)
    }

    private fun checkEntryCount(count: Int, path: Path) {
        if (count > limits.maxEntriesPerInput) {
            throw JarSourceIndexLimitException(
                "Input '$path' contains $count files, exceeding the source-index limit of ${limits.maxEntriesPerInput}.",
            )
        }
    }

    private inline fun <T> captureEntry(
        issues: MutableList<SourceIndexIssue>,
        code: String,
        path: String,
        block: () -> T,
    ): T? = try {
        block()
    } catch (exception: JarSourceIndexLimitException) {
        // Limits protect the entire indexing operation; never downgrade them into a per-entry
        // warning and continue consuming an untrusted archive.
        throw exception
    } catch (exception: Exception) {
        issues += SourceIndexIssue(
            code = code,
            path = path,
            message = exception.message ?: exception.javaClass.simpleName,
        )
        null
    }

    private data class RuntimeScan(
        val classEntryCount: Int,
        val classes: List<RuntimeClassRecord>,
        val assets: List<RuntimeAssetRecord>,
    )

    private data class SourceScan(
        val sourceFiles: List<SourceFileRecord>,
        val assets: List<SourceAssetRecord>,
    )

    private data class RuntimeClassRecord(
        val jarPath: String,
        val internalName: String,
        val sourceFileName: String?,
        val lineNumbers: List<Int>,
    )

    private data class RuntimeAssetRecord(val path: String, val sha256: String)
    private data class SourceCoordinate(val packageName: String, val fileName: String)
    private data class SourceFileRecord(val path: String, val fileName: String, val packageName: String, val lineCount: Int)
    private data class SourceAssetRecord(val path: String, val relativePath: String, val sha256: String)

    private class ReadBudget(private val maxBytes: Long, private val input: Path) {
        private var consumed: Long = 0

        fun consume(count: Int) {
            consumed += count
            if (consumed > maxBytes) {
                throw JarSourceIndexLimitException(
                    "Reading '$input' exceeded the source-index expanded-byte limit of $maxBytes.",
                )
            }
        }
    }

    private fun InputStream.readBounded(maxBytes: Long, budget: ReadBudget, path: String): ByteArray {
        val output = ByteArrayOutputStream()
        transferBounded(maxBytes, budget, path) { bytes, count -> output.write(bytes, 0, count) }
        return output.toByteArray()
    }

    private fun InputStream.sha256Bounded(maxBytes: Long, budget: ReadBudget, path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        transferBounded(maxBytes, budget, path) { bytes, count -> digest.update(bytes, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inline fun InputStream.transferBounded(
        maxBytes: Long,
        budget: ReadBudget,
        path: String,
        consume: (ByteArray, Int) -> Unit,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            entryBytes += count
            if (entryBytes > maxBytes) {
                throw JarSourceIndexLimitException("Entry '$path' exceeds its source-index byte limit of $maxBytes.")
            }
            budget.consume(count)
            consume(buffer, count)
        }
    }

    private companion object {
        val PACKAGE_PATTERN = Regex(
            "(?m)^\\s*package\\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)\\s*;?",
        )

        fun isSourceFile(path: String): Boolean =
            path.endsWith(".java", ignoreCase = true) || path.endsWith(".kt", ignoreCase = true)

        fun isRuntimeAsset(path: String): Boolean =
            !path.startsWith("META-INF/", ignoreCase = true) &&
                path.lowercase() !in setOf("mod.hjson", "mod.json", "plugin.json") &&
                // Android builds commonly append classes.dex beside real Mod resources. It is
                // executable bytecode rather than an asset and must not enter source asset maps.
                !path.endsWith(".dex", ignoreCase = true) &&
                !path.endsWith(".java", ignoreCase = true) &&
                !path.endsWith(".kt", ignoreCase = true)

        fun assetRelativePath(path: String, rootIsAssets: Boolean = false): String? {
            if (rootIsAssets) return path.takeIf(String::isNotEmpty)
            val parts = path.split('/')
            val assetsIndex = parts.indexOfLast { it == "assets" }
            return if (assetsIndex >= 0 && assetsIndex + 1 < parts.size) {
                parts.drop(assetsIndex + 1).joinToString("/")
            } else {
                null
            }
        }

        fun normalizePath(path: String): String = path.replace('\\', '/').trimStart('/')
    }
}
