package io.github.moddpbridge.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stable JSON codec used by the CLI, workers and website API. */
object ConversionReportJson {
    val format: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(report: ConversionReport): String = format.encodeToString(report)

    fun decode(value: String): ConversionReport = format.decodeFromString(value)

    fun encode(result: ConversionResult): String = format.encodeToString(result)

    fun decodeResult(value: String): ConversionResult = format.decodeFromString(value)
}

/** Human-readable report. JSON remains the canonical machine format. */
fun ConversionReport.toMarkdown(): String = buildString {
    appendLine("# Mod → Data Pack conversion report")
    appendLine()
    appendLine("- **Status:** `${status.name}`")
    appendLine("- **Target:** `${escapeMarkdown(target.gameVersion)}`")
    appendLine("- **Source:** `${escapeMarkdown(source.name)}`")
    appendLine("- **Files scanned:** ${summary.scannedFiles}")
    appendLine("- **Content files:** ${summary.contentFiles}")
    appendLine("- **Asset files:** ${summary.assetFiles}")
    if (contentResults.isNotEmpty()) {
        appendLine(
                "- **Logical content:** ${contentResults.size} " +
                "(${summary.convertedContents} converted, ${summary.degradedContents} degraded, " +
                "${summary.excludedContents} excluded, ${summary.unsupportedContents} unsupported, " +
                "${summary.failedContents} failed)",
        )
    }
    appendLine()

    appendLine("## Validation stages")
    appendLine()
    appendLine("| Stage | Status | Summary |")
    appendLine("|---|---|---|")
    if (validationStages.isEmpty()) {
        appendLine("| _none_ | `NOT_RUN` | |")
    } else {
        validationStages.forEach { stage ->
            appendLine(
                "| `${stage.stage.name}` | `${stage.status.name}` | " +
                    "${escapeMarkdown(stage.summary.orEmpty())} |",
            )
        }
    }
    appendLine()

    appendLine("## Diagnostics")
    appendLine()
    if (diagnostics.isEmpty()) {
        appendLine("No diagnostics.")
    } else {
        appendLine("| Severity | Code | Location | Message |")
        appendLine("|---|---|---|---|")
        diagnostics.forEach { diagnostic ->
            appendLine(
                "| `${diagnostic.severity.name}` | `${escapeMarkdown(diagnostic.code)}` | " +
                    "${escapeMarkdown(diagnostic.location?.path.orEmpty())} | " +
                    "${escapeMarkdown(diagnostic.message)} |",
            )
        }
    }
    appendLine()

    appendLine("## Inventory")
    appendLine()
    appendLine("- Content: ${inventory.contents.size}")
    appendLine("- Assets: ${inventory.assets.size}")
    appendLine("- Ignored: ${inventory.ignored.size}")

    appendLine()
    appendLine("## Content conversion results")
    appendLine()
    if (contentResults.isEmpty()) {
        appendLine("No declaration-level conversion results were produced.")
    } else {
        appendLine("| Disposition | Kind | Symbol | Location | Source type | Target type | Output | Reason | Diagnostics |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        contentResults.forEach { result ->
            appendLine(
                "| `${result.disposition.name}` | `${result.kind?.name ?: "UNKNOWN"}` | " +
                    "`${escapeMarkdown(result.sourceSymbol)}` | " +
                    "${result.location?.let { location ->
                        "`${escapeMarkdown(location.path)}" +
                            (location.line?.let { ":$it" } ?: "") + "`"
                    } ?: "—"} | " +
                    "${result.sourceType?.let { "`${escapeMarkdown(it)}`" } ?: "—"} | " +
                    "${result.targetType?.let { "`${escapeMarkdown(it)}`" } ?: "—"} | " +
                    "${result.outputPath?.let { "`${escapeMarkdown(it)}`" } ?: "—"} | " +
                    "${result.reason?.let(::escapeMarkdown) ?: "—"} | " +
                    "${result.diagnosticCodes.joinToString(", ") { "`${escapeMarkdown(it)}`" }.ifBlank { "—" }} |",
            )
        }
    }

    appendLine()
    appendLine("## File results")
    appendLine()
    appendLine("Every scanned source file must have one final disposition. Empty categories are shown explicitly.")
    appendLine()
    appendLine("| Disposition | Count |")
    appendLine("|---|---:|")
    FileDisposition.entries.forEach { disposition ->
        appendLine("| `${disposition.name}` | ${fileResults.count { it.disposition == disposition }} |")
    }

    FileDisposition.entries.forEach { disposition ->
        val matching = fileResults.filter { it.disposition == disposition }
        appendLine()
        appendLine("### ${disposition.heading()} (${matching.size})")
        appendLine()
        if (matching.isEmpty()) {
            appendLine("No files.")
        } else {
            appendLine("| Source | Output | Reason | Diagnostics |")
            appendLine("|---|---|---|---|")
            matching.forEach { result ->
                val diagnostics = result.diagnosticCodes
                    .joinToString(", ") { "`${escapeMarkdown(it)}`" }
                    .ifBlank { "—" }
                appendLine(
                    "| `${escapeMarkdown(result.sourcePath)}` | " +
                        "${result.outputPaths.takeIf { it.isNotEmpty() }?.joinToString("<br>") { "`${escapeMarkdown(it)}`" } ?: "—"} | " +
                        "${result.reason?.let(::escapeMarkdown) ?: "—"} | $diagnostics |",
                )
            }
        }
    }

    if (outputs.isNotEmpty()) {
        appendLine()
        appendLine("## Outputs")
        appendLine()
        outputs.forEach { output ->
            appendLine("- `${output.kind.name}`: `${escapeMarkdown(output.path)}`")
        }
    }
}

private fun FileDisposition.heading(): String = when (this) {
    FileDisposition.COPIED -> "Copied files"
    FileDisposition.CONVERTED -> "Converted files"
    FileDisposition.EXCLUDED -> "Excluded files"
    FileDisposition.UNSUPPORTED -> "Unsupported files"
    FileDisposition.FAILED -> "Failed files"
}

private fun escapeMarkdown(value: String): String =
    value.replace("|", "\\|").replace("`", "\\`").replace("\r", " ").replace("\n", " ")
