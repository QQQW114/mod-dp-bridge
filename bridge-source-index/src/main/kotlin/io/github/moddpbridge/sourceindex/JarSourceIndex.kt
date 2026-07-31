package io.github.moddpbridge.sourceindex

/** How a runtime class was linked back to a repository source file. */
enum class ClassSourceMatch {
    /** The class SourceFile attribute and package identify one repository source file. */
    SOURCE_FILE,

    /** Debug SourceFile metadata was absent, but the top-level binary class name was unique. */
    DERIVED_TOP_LEVEL_NAME,

    /** More than one repository source file has the same package and file name. */
    AMBIGUOUS,

    /** No repository source file has the package/file coordinate recorded by the class. */
    NOT_FOUND,
}

/**
 * A runtime class and its non-executing link to repository source.
 *
 * [binaryName] and debug metadata come from the JAR class bytes. Repository source is only used
 * to populate [sourcePath] and never changes which runtime classes exist.
 */
data class RuntimeClassSource(
    val jarPath: String,
    val binaryName: String,
    val packageName: String,
    val topLevelClassName: String,
    val sourceFileName: String?,
    val lineNumbers: List<Int>,
    val match: ClassSourceMatch,
    val sourcePath: String?,
    val sourceCandidates: List<String>,
    val sourceLineCount: Int?,
) {
    val firstLine: Int? get() = lineNumbers.firstOrNull()
    val lastLine: Int? get() = lineNumbers.lastOrNull()

    /** Null when either side has no line information; false is a useful stale-source signal. */
    val sourceCoversRuntimeLines: Boolean?
        get() = if (lastLine == null || sourceLineCount == null) null else lastLine!! <= sourceLineCount
}

/** A relative-path and SHA-256 comparison between a runtime JAR asset and repository assets. */
enum class AssetSourceMatch {
    /** Exactly one source asset has both the same relative path and the same bytes. */
    EXACT,

    /** Several source asset roots contain the same relative path and bytes. */
    AMBIGUOUS_EXACT,

    /** The relative path exists in source, but none of its SHA-256 values match the JAR. */
    HASH_MISMATCH,

    /** The runtime relative path does not exist below a source assets directory. */
    NOT_FOUND,
}

data class RuntimeAssetSource(
    val jarPath: String,
    val sha256: String,
    val match: AssetSourceMatch,
    val sourcePath: String?,
    val sourceCandidates: List<SourceAssetCandidate>,
)

data class SourceAssetCandidate(
    val sourcePath: String,
    val sha256: String,
)

data class SourceIndexIssue(
    val code: String,
    val path: String,
    val message: String,
)

data class JarSourceIndexSummary(
    val runtimeClassFiles: Int,
    val parsedClassFiles: Int,
    val matchedClassFiles: Int,
    val matchedDistinctSourceFiles: Int,
    val runtimeAssets: Int,
    val exactAssetMatches: Int,
    val ambiguousExactAssetMatches: Int,
    val changedAssetMatches: Int,
    val missingAssetMatches: Int,
) {
    val classFileMatchRate: Double
        get() = matchedClassFiles.toRate(runtimeClassFiles)

    val exactAssetMatchRate: Double
        get() = (exactAssetMatches + ambiguousExactAssetMatches).toRate(runtimeAssets)
}

data class JarSourceIndex(
    val classes: List<RuntimeClassSource>,
    val assets: List<RuntimeAssetSource>,
    val issues: List<SourceIndexIssue>,
    val summary: JarSourceIndexSummary,
) {
    val classesByBinaryName: Map<String, RuntimeClassSource>
        get() = classes.associateBy(RuntimeClassSource::binaryName)
}

private fun Int.toRate(total: Int): Double = if (total == 0) 1.0 else toDouble() / total
