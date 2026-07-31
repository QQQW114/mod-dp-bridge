package io.github.moddpbridge.cli

import io.github.moddpbridge.sourceindex.JarSourceIndex
import io.github.moddpbridge.sourceindex.JarSourceIndexer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class RuntimeSourceIndexResult(
    val report: Path,
    val runtimeClassFiles: Int,
    val matchedClassFiles: Int,
    val runtimeAssets: Int,
    val exactAssetMatches: Int,
    val issueCount: Int,
)

internal fun interface RuntimeSourceIndexStage {
    fun index(modJar: Path, source: Path, report: Path, logger: BridgeLogger): RuntimeSourceIndexResult
}

/** Runs the existing non-executing JAR/source provenance index after runtime extraction. */
internal class JarRuntimeSourceIndexStage(
    private val indexer: JarSourceIndexer = JarSourceIndexer(),
) : RuntimeSourceIndexStage {
    override fun index(
        modJar: Path,
        source: Path,
        report: Path,
        logger: BridgeLogger,
    ): RuntimeSourceIndexResult {
        logger.info("Indexing optional source provenance: $source")
        val index = indexer.index(modJar, source)
        report.parent?.let(Files::createDirectories)
        Files.writeString(report, encode(index) + "\n", StandardCharsets.UTF_8)
        val summary = index.summary
        logger.info(
            "Source index: classes=${summary.matchedClassFiles}/${summary.runtimeClassFiles}, " +
                "assets=${summary.exactAssetMatches + summary.ambiguousExactAssetMatches}/${summary.runtimeAssets}, " +
                "issues=${index.issues.size}",
        )
        return RuntimeSourceIndexResult(
            report = report,
            runtimeClassFiles = summary.runtimeClassFiles,
            matchedClassFiles = summary.matchedClassFiles,
            runtimeAssets = summary.runtimeAssets,
            exactAssetMatches = summary.exactAssetMatches + summary.ambiguousExactAssetMatches,
            issueCount = index.issues.size,
        )
    }

    private fun encode(index: JarSourceIndex): String {
        val root = buildJsonObject {
            put("schemaVersion", 1)
            put("summary", buildJsonObject {
                put("runtimeClassFiles", index.summary.runtimeClassFiles)
                put("parsedClassFiles", index.summary.parsedClassFiles)
                put("matchedClassFiles", index.summary.matchedClassFiles)
                put("matchedDistinctSourceFiles", index.summary.matchedDistinctSourceFiles)
                put("classFileMatchRate", index.summary.classFileMatchRate)
                put("runtimeAssets", index.summary.runtimeAssets)
                put("exactAssetMatches", index.summary.exactAssetMatches)
                put("ambiguousExactAssetMatches", index.summary.ambiguousExactAssetMatches)
                put("changedAssetMatches", index.summary.changedAssetMatches)
                put("missingAssetMatches", index.summary.missingAssetMatches)
                put("exactAssetMatchRate", index.summary.exactAssetMatchRate)
            })
            put("classes", buildJsonArray {
                index.classes.forEach { item ->
                    add(buildJsonObject {
                        put("jarPath", item.jarPath)
                        put("binaryName", item.binaryName)
                        put("packageName", item.packageName)
                        put("topLevelClassName", item.topLevelClassName)
                        putNullable("sourceFileName", item.sourceFileName)
                        put("lineNumbers", buildJsonArray {
                            item.lineNumbers.forEach { line -> add(JsonPrimitive(line)) }
                        })
                        put("match", item.match.name.lowercase())
                        putNullable("sourcePath", item.sourcePath)
                        put("sourceCandidates", buildJsonArray {
                            item.sourceCandidates.forEach { candidate -> add(JsonPrimitive(candidate)) }
                        })
                        putNullable("sourceLineCount", item.sourceLineCount)
                        putNullable("sourceCoversRuntimeLines", item.sourceCoversRuntimeLines)
                    })
                }
            })
            put("assets", buildJsonArray {
                index.assets.forEach { item ->
                    add(buildJsonObject {
                        put("jarPath", item.jarPath)
                        put("sha256", item.sha256)
                        put("match", item.match.name.lowercase())
                        putNullable("sourcePath", item.sourcePath)
                        put("sourceCandidates", buildJsonArray {
                            item.sourceCandidates.forEach { candidate ->
                                add(buildJsonObject {
                                    put("sourcePath", candidate.sourcePath)
                                    put("sha256", candidate.sha256)
                                })
                            }
                        })
                    })
                }
            })
            put("issues", buildJsonArray {
                index.issues.forEach { issue ->
                    add(buildJsonObject {
                        put("code", issue.code)
                        put("path", issue.path)
                        put("message", issue.message)
                    })
                }
            })
        }
        return REPORT_JSON.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), root)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: String?) {
        put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: Int?) {
        put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: Boolean?) {
        put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private companion object {
        val REPORT_JSON = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
