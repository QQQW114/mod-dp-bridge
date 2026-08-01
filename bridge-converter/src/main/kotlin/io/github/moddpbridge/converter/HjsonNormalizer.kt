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

    fun parse(bytes: ByteArray, sourcePath: String): JsonValue =
        readHjsonCompat(decode(bytes), sourcePath).first

    fun parseLegacyCp(bytes: ByteArray, sourcePath: String): Pair<JsonValue, String?> {
        val text = decode(bytes)
        try {
            return JsonValue.readHjson(text) to null
        } catch (nativeError: RuntimeException) {
            val multiline = compatibilityMultilineStrings(text)
            try {
                return JsonValue.readHjson(multiline) to MULTILINE_STRING_REPAIR
            } catch (multilineError: RuntimeException) {
                val repaired = compatibilityLegacyCp(text)
                try {
                    return JsonValue.readHjson(repaired) to "legacy-cp-regex-repair"
                } catch (compatibilityError: RuntimeException) {
                    throw ConversionException(
                        "Failed to parse legacy CP '$sourcePath'. Native: ${nativeError.message}; " +
                            "multiline-string repair: ${multilineError.message}; " +
                            "compatibility repair: ${compatibilityError.message}",
                        cause = compatibilityError,
                    )
                }
            }
        }
    }

    fun normalize(bytes: ByteArray, sourcePath: String, removeResearch: Boolean = true): NormalizedText {
        val (root, compatibilityMode) = readHjsonCompat(decode(bytes), sourcePath)
        val removed = mutableListOf<String>()
        if (removeResearch) removeResearch(root, "$", removed)
        return NormalizedText(render(root), root, removed, compatibilityMode)
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

    private fun decode(bytes: ByteArray): String =
        bytes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")

    private fun readHjsonCompat(text: String, sourcePath: String): Pair<JsonValue, String?> {
        try {
            return JsonValue.readHjson(text) to null
        } catch (nativeError: RuntimeException) {
            try {
                return JsonValue.readHjson(compatibilityMultilineStrings(text)) to MULTILINE_STRING_REPAIR
            } catch (multilineError: RuntimeException) {
                throw ConversionException(
                    "Failed to parse JSON/HJSON '$sourcePath'. Native: ${nativeError.message}; " +
                        "multiline-string repair: ${multilineError.message}",
                    cause = multilineError,
                )
            }
        }
    }

    /**
     * Best-effort compatibility with multi-line strings written as bare `"`/`'`/`"""` quotes, which
     * hjson-java rejects ("Expected valid string character"). Only runs after native parsing fails.
     * Line comments, block comments, and ordinary single-line strings are preserved verbatim.
     */
    private fun compatibilityMultilineStrings(raw: String): String {
        val out = StringBuilder(raw.length + 128)
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            when {
                c == '#' || (c == '/' && i + 1 < n && raw[i + 1] == '/') -> {
                    val start = i
                    while (i < n && raw[i] != '\n') i++
                    out.append(raw, start, i)
                }
                c == '/' && i + 1 < n && raw[i + 1] == '*' -> {
                    val start = i
                    i += 2
                    while (i < n && !(raw[i] == '*' && i + 1 < n && raw[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                    out.append(raw, start, i)
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    val triple = i + 2 < n && raw[i + 1] == quote && raw[i + 2] == quote
                    val start = i
                    i += if (triple) 3 else 1
                    val contentStart = i
                    var hasNewline = false
                    var closed = false
                    while (i < n && !closed) {
                        val d = raw[i]
                        when {
                            d == '\\' && !triple -> {
                                if (i + 1 < n && (raw[i + 1] == '\n' || raw[i + 1] == '\r')) hasNewline = true
                                i += 2
                            }
                            triple && d == quote && i + 2 < n && raw[i + 1] == quote && raw[i + 2] == quote -> closed = true
                            !triple && d == quote -> closed = true
                            d == '\n' || d == '\r' -> {
                                hasNewline = true
                                i++
                            }
                            else -> i++
                        }
                    }
                    val contentEnd = i
                    if (triple || hasNewline) {
                        out.append('"').append(escapeJsonContent(raw.substring(contentStart, contentEnd))).append('"')
                    } else {
                        out.append(raw, start, contentEnd + 1)
                    }
                    i += if (triple) 3 else 1
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun escapeJsonContent(content: String): String = buildString(content.length + 16) {
        for (ch in content) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch < ' ') {
                        append("\\u%04x".format(ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }

    private const val MULTILINE_STRING_REPAIR = "multiline-string-repair"

    /** Best-effort compatibility with old CP snippets that mixed inline JSON and bare HJSON tokens. */
    private fun compatibilityLegacyCp(raw: String): String = raw
        .replace("+=", "+")
        .replace(Regex("(?<=\\{|,|\\s)([A-Za-z0-9_-]+)\\s*:"), "\"$1\":")
        .replace(Regex(":\\s*([A-Za-z_][A-Za-z0-9_-]*)(?=\\s*[,}\\]])")) { match ->
            ":\"${match.groupValues[1]}\""
        }
}
