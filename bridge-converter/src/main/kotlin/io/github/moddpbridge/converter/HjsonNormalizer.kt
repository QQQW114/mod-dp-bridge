package io.github.moddpbridge.converter

import org.hjson.JsonValue
import org.hjson.Stringify
import java.nio.charset.StandardCharsets

internal data class NormalizedText(
    val bytes: ByteArray,
    val root: JsonValue,
    val removedResearchPaths: List<String>,
    val compatibilityMode: String? = null,
)

internal object HjsonNormalizer {
    init {
        JsonValue.setEol("\n")
    }

    fun parse(bytes: ByteArray, sourcePath: String): JsonValue {
        val text = bytes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        return try {
            JsonValue.readHjson(text)
        } catch (error: RuntimeException) {
            throw ConversionException("Failed to parse JSON/HJSON '$sourcePath': ${error.message}", cause = error)
        }
    }

    fun parseLegacyCp(bytes: ByteArray, sourcePath: String): Pair<JsonValue, String?> {
        val text = bytes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        try {
            return JsonValue.readHjson(text) to null
        } catch (nativeError: RuntimeException) {
            val repaired = compatibilityLegacyCp(text)
            try {
                return JsonValue.readHjson(repaired) to "legacy-cp-regex-repair"
            } catch (compatibilityError: RuntimeException) {
                throw ConversionException(
                    "Failed to parse legacy CP '$sourcePath'. Native: ${nativeError.message}; " +
                        "compatibility repair: ${compatibilityError.message}",
                    cause = compatibilityError,
                )
            }
        }
    }

    fun normalize(bytes: ByteArray, sourcePath: String, removeResearch: Boolean = true): NormalizedText {
        val root = parse(bytes, sourcePath)
        val removed = mutableListOf<String>()
        if (removeResearch) removeResearch(root, "$", removed)
        return NormalizedText(render(root), root, removed)
    }

    fun normalizeLegacyCp(bytes: ByteArray, sourcePath: String, removeResearch: Boolean = true): NormalizedText {
        val (root, compatibilityMode) = parseLegacyCp(bytes, sourcePath)
        val removed = mutableListOf<String>()
        if (removeResearch) removeResearch(root, "$", removed)
        return NormalizedText(render(root), root, removed, compatibilityMode)
    }

    fun render(root: JsonValue): ByteArray {
        val normalized = root.toString(Stringify.FORMATTED).trimEnd() + "\n"
        return normalized.toByteArray(StandardCharsets.UTF_8)
    }

    private fun removeResearch(value: JsonValue, path: String, removed: MutableList<String>) {
        when {
            value.isObject -> {
                val objectValue = value.asObject()
                objectValue.names().toList().forEach { name ->
                    val childPath = if (name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                        "$path.$name"
                    } else {
                        "$path['${name.replace("'", "\\'")}']"
                    }
                    if (name == "research") {
                        objectValue.remove(name)
                        removed += childPath
                    } else {
                        removeResearch(objectValue.get(name), childPath, removed)
                    }
                }
            }

            value.isArray -> {
                val array = value.asArray()
                for (index in 0 until array.size()) {
                    removeResearch(array.get(index), "$path[$index]", removed)
                }
            }
        }
    }

    /** Best-effort compatibility with old CP snippets that mixed inline JSON and bare HJSON tokens. */
    private fun compatibilityLegacyCp(raw: String): String = raw
        .replace("+=", "+")
        .replace(Regex("(?<=\\{|,|\\s)([A-Za-z0-9_-]+)\\s*:"), "\"$1\":")
        .replace(Regex(":\\s*([A-Za-z_][A-Za-z0-9_-]*)(?=\\s*[,}\\]])")) { match ->
            ":\"${match.groupValues[1]}\""
        }
}
