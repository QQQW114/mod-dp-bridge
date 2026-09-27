package io.github.moddpbridge.runtimemapper

import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A single target-content field the patch could not carry, with a machine-readable reason code. */
data class VanillaOverrideReduction(
    val contentType: String,
    val name: String,
    val field: String,
    val code: String,
    val detail: String,
)

data class VanillaOverrideMappingOptions(
    val maxOverridesBytes: Long = 64L * 1024L * 1024L,
    /** Content types that can be addressed by a Data Patch at all. */
    val patchableContentTypes: Set<String> = setOf("item", "liquid", "status", "unit", "block"),
    /**
     * Data Patch content names the packaged DP declares. Content references outside this set - plus
     * every vanilla reference - cannot survive as a patch value, because the patch is applied against
     * the target registry.
     */
    val migratedContentNames: Set<String> = emptySet(),
    /** Release-JAR archive whose entries back the generated patch. */
    val modJar: Path? = null,
    /** Mod source paths that referenced the watched target content, in source-file form. */
    val provenanceSources: List<String> = emptyList(),
)

data class VanillaOverrideMappingSummary(
    val schemaVersion: Int,
    val targetMod: String,
    val watchSpec: String,
    val observedContents: Int,
    val observedFields: Int,
    val patchedContents: Int,
    val patchedFields: Int,
    val droppedFields: Int,
    val missingWatchTargets: Int,
    val loadTimeOverrides: Int,
    val afterInitOverrides: Int,
    val targetInitRecomputed: Int,
)

/**
 * Inert result of mapping runtime target-content overrides onto a Data Patch `patch` asset. [patchBytes]
 * is strict JSON, which is a subset of HJSON, so the target's HJSON reader accepts it verbatim.
 */
data class VanillaOverrideMappingResult(
    val patchPath: String,
    val patchBytes: ByteArray,
    /** Release-JAR entries the patch was derived from; the converter requires non-empty provenance. */
    val provenance: List<String>,
    val summary: VanillaOverrideMappingSummary,
    val reductions: List<VanillaOverrideReduction>,
    val diagnostics: List<Diagnostic>,
    val reportJson: String,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

class VanillaOverrideMappingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Maps the runtime probe's target-content overrides onto the Data Patch `patches/` form.
 *
 * The mapper never invents values: every emitted field is a field the runtime proved the Mod changed,
 * written with the value the running Mod actually ended with. Fields whose runtime value cannot be
 * expressed as a patch value are dropped with an explicit reason code instead of being approximated.
 */
object VanillaOverridePatchMapper {
    const val PATCH_PATH = "patches/vanilla-overrides.hjson"
    const val PATCH_LABEL = "vanilla-overrides"

    private const val supportedSchemaVersion = 1
    private const val MAX_PROVENANCE_ENTRIES = 16

    /** Reason codes; kept stable because reports and tests match on them. */
    const val CODE_NESTED_OBJECT_OMITTED = "NESTED_OBJECT_OMITTED"
    const val CODE_REFERENCED_CONTENT_NOT_MIGRATED = "REFERENCED_CONTENT_NOT_MIGRATED"
    const val CODE_UNREPRESENTABLE_BY_TARGET_MODEL = "UNREPRESENTABLE_BY_TARGET_MODEL"
    const val CODE_VALUE_TRUNCATED = "VALUE_TRUNCATED"
    const val CODE_FIELD_NOT_PATCHABLE = "FIELD_NOT_PATCHABLE"

    /**
     * Fields that describe target campaign placement rather than behavior. Planets and sectors are not
     * migrated, so a Mod value that names one (for example `shownPlanets` extended with the Mod's own
     * planet) can never resolve in a Data Patch game.
     */
    private val unrepresentableFields = mapOf(
        "shownPlanets" to CODE_REFERENCED_CONTENT_NOT_MIGRATED,
        "requiredPlanets" to CODE_REFERENCED_CONTENT_NOT_MIGRATED,
    )

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun map(
        overrides: Path,
        options: VanillaOverrideMappingOptions = VanillaOverrideMappingOptions(),
    ): VanillaOverrideMappingResult {
        val path = overrides.toAbsolutePath().normalize()
        require(Files.isRegularFile(path)) { "Vanilla override artifact does not exist: $path" }
        if (Files.size(path) > options.maxOverridesBytes) {
            throw VanillaOverrideMappingException(
                "Vanilla override artifact exceeds the ${options.maxOverridesBytes} byte safety limit: $path",
            )
        }
        val root = json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)).jsonObject
        val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: throw VanillaOverrideMappingException("Vanilla override artifact has no schemaVersion: $path")
        if (schemaVersion != supportedSchemaVersion) {
            throw VanillaOverrideMappingException(
                "Vanilla override schema $schemaVersion is not supported; $supportedSchemaVersion was expected: $path",
            )
        }

        val reductions = mutableListOf<VanillaOverrideReduction>()
        val diagnostics = mutableListOf<Diagnostic>()
        val patched = linkedMapOf<String, MutableMap<String, MutableMap<String, JsonElement>>>()
        var observedFields = 0
        var loadTimeOverrides = 0
        var afterInitOverrides = 0
        var targetInitRecomputed = 0

        val contents = root["contents"] as? JsonArray ?: JsonArray(emptyList())
        for (element in contents) {
            val content = element as? JsonObject ?: continue
            val contentType = content.string("contentType") ?: continue
            // The patch must address the target registry name; the watch name may be a declaration field.
            val name = content.string("runtimeName") ?: content.string("name") ?: continue
            val overrides = content["overrides"] as? JsonArray ?: continue
            for (entry in overrides) {
                val override = entry as? JsonObject ?: continue
                val field = override.string("field") ?: continue
                observedFields++
                when (override.string("moment")) {
                    "LOAD_TIME" -> loadTimeOverrides++
                    "AFTER_INIT" -> afterInitOverrides++
                }
                if (override["targetInitRecomputed"]?.jsonPrimitive?.booleanOrNull == true) targetInitRecomputed++

                if (contentType !in options.patchableContentTypes) {
                    reductions += reduction(contentType, name, field, CODE_UNREPRESENTABLE_BY_TARGET_MODEL, "Content type is not patch-addressable.")
                    continue
                }
                unrepresentableFields[field]?.let { code ->
                    reductions += reduction(contentType, name, field, code, "Target campaign placement is not migrated by this converter.")
                    continue
                }
                val converted = convert(override["value"], options, contentType, name, field) { code, detail ->
                    reductions += reduction(contentType, name, field, code, detail)
                } ?: continue
                patched.getOrPut(contentType) { linkedMapOf() }.getOrPut(name) { linkedMapOf() }[field] = converted
            }
        }

        val missingTargets = (root["missingWatchTargets"] as? JsonArray)?.size ?: 0
        val provenance = resolveProvenance(options, path)
        val patchRoot = buildJsonObject {
            put("name", JsonPrimitive(PATCH_LABEL))
            patched.forEach { (contentType, entries) ->
                put(
                    contentType,
                    buildJsonObject {
                        entries.forEach { (name, fields) ->
                            put(
                                name,
                                buildJsonObject {
                                    fields.forEach { (field, value) -> put(field, value) }
                                },
                            )
                        }
                    },
                )
            }
        }
        val patchBytes = (json.encodeToString(JsonObject.serializer(), patchRoot) + "\n").toByteArray(StandardCharsets.UTF_8)
        val patchedFields = patched.values.sumOf { entries -> entries.values.sumOf { fields -> fields.size } }
        val stats = root["stats"] as? JsonObject

        val summary = VanillaOverrideMappingSummary(
            schemaVersion = schemaVersion,
            targetMod = root.string("targetMod") ?: "<unknown>",
            watchSpec = root.string("watchSpec") ?: "",
            observedContents = contents.size,
            observedFields = observedFields,
            patchedContents = patched.values.sumOf { it.size },
            patchedFields = patchedFields,
            droppedFields = reductions.size,
            missingWatchTargets = missingTargets,
            loadTimeOverrides = loadTimeOverrides,
            afterInitOverrides = afterInitOverrides,
            targetInitRecomputed = stats?.let { element -> (element["targetInitRecomputed"] as? JsonPrimitive)?.intOrNull }
                ?: targetInitRecomputed,
        )
        reductions.groupBy { it.code }.forEach { (code, entries) ->
            diagnostics += Diagnostic(
                code = "VANILLA_OVERRIDE_$code",
                severity = DiagnosticSeverity.WARNING,
                message = "${entries.size} target content field(s) could not be expressed as a Data Patch value: $code.",
                stage = ValidationStage.STRUCTURE,
                location = SourceLocation(path.toString()),
                details = entries.take(20).joinToString("; ") { "${it.contentType}/${it.name}.${it.field}: ${it.detail}" },
                suggestion = "The affected capability is not reproducible by the target Data Patch model; " +
                    "keep the Mod installed or accept the reduced behavior.",
            )
        }

        return VanillaOverrideMappingResult(
            patchPath = PATCH_PATH,
            patchBytes = patchBytes,
            provenance = provenance,
            summary = summary,
            reductions = reductions,
            diagnostics = diagnostics,
            reportJson = renderReport(path, summary, reductions, diagnostics, provenance),
        )
    }

    /**
     * Resolves the release-JAR entries the patch is derived from.
     *
     * A generated artifact must not claim provenance it does not have, so every candidate is verified
     * against the archive. Mod sources are named after their compilation unit, which is why the source
     * path is rewritten to its class entry; the archive's own metadata is the last resort.
     */
    private fun resolveProvenance(options: VanillaOverrideMappingOptions, artifact: Path): List<String> {
        val jar = options.modJar
        if (jar == null || !Files.isRegularFile(jar)) {
            throw VanillaOverrideMappingException(
                "The release Mod JAR is required to attribute '$PATCH_PATH' to real archive entries: $artifact",
            )
        }
        val candidates = linkedSetOf<String>()
        java.util.zip.ZipFile(jar.toFile()).use { zip ->
            val entries = linkedSetOf<String>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                entries += enumeration.nextElement().name
            }
            options.provenanceSources.forEach { source ->
                val normalized = source.replace('\\', '/').removePrefix("/")
                val classEntry = normalized.removeSuffix(".java") + ".class"
                if (classEntry in entries) candidates += classEntry
            }
            if (candidates.isEmpty()) {
                listOf("mod.hjson", "plugin.hjson").firstOrNull { it in entries }?.let(candidates::add)
            }
        }
        if (candidates.isEmpty()) {
            throw VanillaOverrideMappingException(
                "No release-JAR entry backs '$PATCH_PATH': watched sources ${options.provenanceSources.size} " +
                    "and no mod metadata entry was found in $jar",
            )
        }
        return candidates.sorted().take(MAX_PROVENANCE_ENTRIES)
    }

    private fun reduction(contentType: String, name: String, field: String, code: String, detail: String) =
        VanillaOverrideReduction(contentType, name, field, code, detail)

    /** Converts one typed runtime value, reporting a reduction instead of guessing when it cannot be expressed. */
    private fun convert(
        element: JsonElement?,
        options: VanillaOverrideMappingOptions,
        contentType: String,
        name: String,
        field: String,
        reduce: (String, String) -> Unit,
    ): JsonElement? {
        val value = element as? JsonObject ?: return null.also { reduce(CODE_NESTED_OBJECT_OMITTED, "Runtime value is not a typed object.") }
        val kind = value.string("kind") ?: return null.also { reduce(CODE_NESTED_OBJECT_OMITTED, "Runtime value has no kind.") }
        return when (kind) {
            "number" -> {
                val raw = value.string("value") ?: return null.also { reduce(CODE_UNREPRESENTABLE_BY_TARGET_MODEL, "Number has no value.") }
                val number = runCatching { BigDecimal(raw) }.getOrNull()
                if (number == null) {
                    reduce(CODE_UNREPRESENTABLE_BY_TARGET_MODEL, "Number '$raw' has no finite decimal form.")
                    null
                } else {
                    JsonPrimitive(number)
                }
            }

            "boolean" -> (value["value"] as? JsonPrimitive)?.booleanOrNull?.let(::JsonPrimitive)

            "string" -> {
                if (value["truncated"]?.jsonPrimitive?.booleanOrNull == true) {
                    reduce(CODE_VALUE_TRUNCATED, "Captured string was truncated by the runtime snapshot budget.")
                    null
                } else {
                    JsonPrimitive(value.string("value").orEmpty())
                }
            }

            "enum" -> JsonPrimitive(value.string("value").orEmpty())

            "color" -> JsonPrimitive(value.string("rgba").orEmpty())

            "staticRef" -> JsonPrimitive(value.string("parserValue").orEmpty())

            "contentRef" -> {
                val target = value.string("name").orEmpty()
                val owner = value.string("ownerMod")
                when {
                    owner == null -> JsonPrimitive(target)
                    options.migratedContentNames.contains(target) -> JsonPrimitive(target)
                    else -> {
                        reduce(
                            CODE_REFERENCED_CONTENT_NOT_MIGRATED,
                            "References $owner content '$target', which this Data Patch does not declare.",
                        )
                        null
                    }
                }
            }

            "array" -> {
                val values = value["values"] as? JsonArray
                if (values == null) {
                    reduce(CODE_NESTED_OBJECT_OMITTED, "Array value has no element list.")
                    null
                } else {
                    val converted = mutableListOf<JsonElement>()
                    var failed = false
                    for (item in values) {
                        val element = convert(item, options, contentType, name, field) { code, detail ->
                            if (!failed) reduce(code, detail)
                            failed = true
                        }
                        if (failed || element == null) {
                            failed = true
                            break
                        }
                        converted += element
                    }
                    if (failed) null else JsonArray(converted)
                }
            }

            "null" -> {
                reduce(CODE_NESTED_OBJECT_OMITTED, "A null override would clear the target field; it is not applied implicitly.")
                null
            }

            else -> {
                reduce(
                    CODE_NESTED_OBJECT_OMITTED,
                    "Runtime value kind '$kind' (${value.string("reason") ?: value.string("runtimeClass") ?: "unknown"}) has no patch form.",
                )
                null
            }
        }
    }

    private fun renderReport(
        path: Path,
        summary: VanillaOverrideMappingSummary,
        reductions: List<VanillaOverrideReduction>,
        diagnostics: List<Diagnostic>,
        provenance: List<String>,
    ): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("artifact", JsonPrimitive(path.toString()))
            put("patchPath", JsonPrimitive(PATCH_PATH))
            put("provenance", JsonArray(provenance.map(::JsonPrimitive)))
            put(
                "summary",
                buildJsonObject {
                    put("schemaVersion", JsonPrimitive(summary.schemaVersion))
                    put("targetMod", JsonPrimitive(summary.targetMod))
                    put("watchSpec", JsonPrimitive(summary.watchSpec))
                    put("observedContents", JsonPrimitive(summary.observedContents))
                    put("observedFields", JsonPrimitive(summary.observedFields))
                    put("patchedContents", JsonPrimitive(summary.patchedContents))
                    put("patchedFields", JsonPrimitive(summary.patchedFields))
                    put("droppedFields", JsonPrimitive(summary.droppedFields))
                    put("missingWatchTargets", JsonPrimitive(summary.missingWatchTargets))
                    put("loadTimeOverrides", JsonPrimitive(summary.loadTimeOverrides))
                    put("afterInitOverrides", JsonPrimitive(summary.afterInitOverrides))
                    put("targetInitRecomputed", JsonPrimitive(summary.targetInitRecomputed))
                },
            )
            put(
                "reductions",
                JsonArray(
                    reductions.map { entry ->
                        buildJsonObject {
                            put("contentType", JsonPrimitive(entry.contentType))
                            put("name", JsonPrimitive(entry.name))
                            put("field", JsonPrimitive(entry.field))
                            put("code", JsonPrimitive(entry.code))
                            put("detail", JsonPrimitive(entry.detail))
                        }
                    },
                ),
            )
            put(
                "diagnostics",
                JsonArray(
                    diagnostics.map { entry ->
                        buildJsonObject {
                            put("code", JsonPrimitive(entry.code))
                            put("severity", JsonPrimitive(entry.severity.name.lowercase()))
                            put("message", JsonPrimitive(entry.message))
                            entry.details?.let { put("details", JsonPrimitive(it)) }
                        }
                    },
                ),
            )
        },
    ) + "\n"

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it !== JsonNull }?.contentOrNull
}
