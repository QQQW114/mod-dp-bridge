package io.github.moddpbridge.web

internal fun jsonString(value: String?): String = value?.let {
    buildString(it.length + 2) {
        append('"')
        it.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
} ?: "null"

internal fun jsonObject(vararg fields: Pair<String, String>): String = fields.joinToString(
    prefix = "{",
    postfix = "}",
    separator = ",",
) { (name, encodedValue) -> "${jsonString(name)}:$encodedValue" }

internal fun errorJson(code: String, message: String): String = jsonObject(
    "error" to jsonString(code),
    "message" to jsonString(message),
)
