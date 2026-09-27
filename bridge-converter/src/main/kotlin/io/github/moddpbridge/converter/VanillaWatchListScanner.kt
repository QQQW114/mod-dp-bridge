package io.github.moddpbridge.converter

import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Target (vanilla) content names a Mod source tree references, grouped by Data Patch content type.
 *
 * The list is deliberately a static, bounded over-approximation: the runtime probe resolves each name
 * against the target registry and reports names it cannot resolve, so an over-broad scan costs only
 * snapshot rows, while a missed name would silently lose an override.
 */
data class VanillaWatchList(
    val names: Map<String, List<String>>,
    val diagnostics: List<Diagnostic> = emptyList(),
    val scannedJavaFiles: Int = 0,
    val truncatedTypes: List<String> = emptyList(),
    /**
     * Source files that produced the watch list. The patch derived from these overrides keeps them as
     * its release-JAR provenance, after the reader rewrites `.java` to the packaged class entry.
     */
    val sourceFiles: List<String> = emptyList(),
) {
    /** Canonical `type:name,name;type:name` form consumed by the runtime extractor. */
    val spec: String
        get() = VanillaWatchListScanner.CONTENT_TYPES
            .mapNotNull { type -> names[type]?.takeIf(List<String>::isNotEmpty)?.let { "$type:${it.joinToString(",")}" } }
            .joinToString(";")

    val totalNames: Int get() = names.values.sumOf(List<String>::size)
    val isEmpty: Boolean get() = totalNames == 0
}

/**
 * Scans a Mod source directory or archive for references to target content declarations.
 *
 * Only Java text is read, nothing is compiled or executed, and the result stays inside the configured
 * source limits. Wildcard import lines are skipped because a package segment such as
 * `mindustry.world.blocks.defense.*` is not a content declaration.
 */
object VanillaWatchListScanner {
    /** Data Patch content types this scanner can produce, in the canonical emission order. */
    val CONTENT_TYPES: List<String> = listOf("item", "liquid", "status", "unit", "block")

    const val MAX_NAMES_PER_TYPE: Int = 512
    const val MAX_TOTAL_NAMES: Int = 1_024

    private val declarations = linkedMapOf(
        "Blocks" to "block",
        "UnitTypes" to "unit",
        "StatusEffects" to "status",
        "Items" to "item",
        "Liquids" to "liquid",
    )

    private val reference = Regex("\\b(${declarations.keys.joinToString("|")})\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)")
    private val wildcardImport = Regex("^\\s*import\\s+(static\\s+)?[\\w.]*\\.\\*\\s*;?\\s*$")

    fun scan(source: Path, limits: SecurityLimits = SecurityLimits()): VanillaWatchList {
        val diagnostics = mutableListOf<Diagnostic>()
        val snapshot = SafeSourceReader.read(source, limits, ConverterLogger(null), diagnostics)
        val found = CONTENT_TYPES.associateWith { linkedSetOf<String>() }.toMutableMap()
        val sourcesWithReferences = linkedSetOf<String>()
        var javaFiles = 0
        for (entry in snapshot.entries) {
            if (entry.extension != "java") continue
            javaFiles++
            val text = String(entry.bytes, StandardCharsets.UTF_8)
            var referenced = false
            for (line in text.lineSequence()) {
                if (wildcardImport.matches(line)) continue
                for (match in reference.findAll(line)) {
                    val type = declarations[match.groupValues[1]] ?: continue
                    found.getValue(type).add(match.groupValues[2])
                    referenced = true
                }
            }
            if (referenced) sourcesWithReferences += entry.path
        }

        val truncatedTypes = mutableListOf<String>()
        val names = linkedMapOf<String, List<String>>()
        var remaining = MAX_TOTAL_NAMES
        for (type in CONTENT_TYPES) {
            val candidates = found.getValue(type).sorted()
            if (candidates.isEmpty()) continue
            val allowed = minOf(candidates.size, MAX_NAMES_PER_TYPE, remaining)
            if (allowed < candidates.size) {
                truncatedTypes += type
                diagnostics += Diagnostic(
                    code = "VANILLA_WATCH_LIST_TRUNCATED",
                    severity = DiagnosticSeverity.WARNING,
                    message = "Target $type watch list kept $allowed of ${candidates.size} referenced names.",
                    stage = ValidationStage.STRUCTURE,
                    location = SourceLocation(source.toString()),
                    details = "Per-type limit=$MAX_NAMES_PER_TYPE, total limit=$MAX_TOTAL_NAMES.",
                    suggestion = "Narrow the Mod source tree so watch lists stay inside the configured bounds.",
                )
            }
            remaining -= allowed
            names[type] = candidates.take(allowed)
        }
        if (javaFiles == 0) {
            diagnostics += Diagnostic(
                code = "VANILLA_WATCH_LIST_EMPTY_SOURCE",
                severity = DiagnosticSeverity.WARNING,
                message = "No Java source file was found; no target content could be watched.",
                stage = ValidationStage.STRUCTURE,
                location = SourceLocation(source.toString()),
                suggestion = "Supply the Mod's Java source tree to enable target-content override capture.",
            )
        } else if (names.isEmpty()) {
            diagnostics += Diagnostic(
                code = "VANILLA_WATCH_LIST_NO_REFERENCES",
                severity = DiagnosticSeverity.INFO,
                message = "Scanned $javaFiles Java source files and found no target content reference.",
                stage = ValidationStage.STRUCTURE,
                location = SourceLocation(source.toString()),
            )
        }
        return VanillaWatchList(
            names = names,
            diagnostics = diagnostics,
            scannedJavaFiles = javaFiles,
            truncatedTypes = truncatedTypes,
            sourceFiles = sourcesWithReferences.sorted(),
        )
    }
}
