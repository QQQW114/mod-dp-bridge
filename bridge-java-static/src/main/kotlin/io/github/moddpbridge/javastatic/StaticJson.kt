package io.github.moddpbridge.javastatic

import java.math.BigDecimal

internal sealed interface StaticJsonValue {
    data object Null : StaticJsonValue
    data class Bool(val value: Boolean) : StaticJsonValue
    data class Number(val value: BigDecimal) : StaticJsonValue
    data class StringValue(val value: String) : StaticJsonValue
    data class ArrayValue(val values: MutableList<StaticJsonValue> = mutableListOf()) : StaticJsonValue
    data class ObjectValue(
        val fields: LinkedHashMap<String, StaticJsonValue> = linkedMapOf(),
    ) : StaticJsonValue
}

internal fun StaticJsonValue.renderJson(indent: Int = 0): String = when (this) {
    StaticJsonValue.Null -> "null"
    is StaticJsonValue.Bool -> value.toString()
    is StaticJsonValue.Number -> value.stripTrailingZeros().toPlainString()
    is StaticJsonValue.StringValue -> quoteJson(value)
    is StaticJsonValue.ArrayValue -> {
        if (values.isEmpty()) "[]" else buildString {
            appendLine("[")
            values.forEachIndexed { index, value ->
                append(" ".repeat(indent + 2))
                append(value.renderJson(indent + 2))
                if (index != values.lastIndex) append(',')
                appendLine()
            }
            append(" ".repeat(indent))
            append(']')
        }
    }
    is StaticJsonValue.ObjectValue -> {
        if (fields.isEmpty()) "{}" else buildString {
            appendLine("{")
            fields.entries.forEachIndexed { index, (name, value) ->
                append(" ".repeat(indent + 2))
                append(quoteJson(name))
                append(": ")
                append(value.renderJson(indent + 2))
                if (index != fields.size - 1) append(',')
                appendLine()
            }
            append(" ".repeat(indent))
            append('}')
        }
    }
}

internal fun renderRootJson(value: StaticJsonValue.ObjectValue): ByteArray =
    (value.renderJson() + "\n").toByteArray(Charsets.UTF_8)

private fun quoteJson(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
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
