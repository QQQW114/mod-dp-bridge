package io.github.moddpbridge.converter

import org.hjson.JsonObject
import org.hjson.JsonValue
import java.util.Locale

internal data class TargetAssetIndex(
    val spriteTargets: Set<String>,
    val soundTargets: Set<String>,
    val musicTargets: Set<String>,
    val spriteRawToTarget: Map<String, String>,
    val soundRawToTarget: Map<String, String>,
    val musicRawToTarget: Map<String, String>,
)

internal data class AssetReferenceIssue(
    val code: String,
    val path: String,
    val value: String,
    val message: String,
    val suggestion: String,
)

/** Static client-side resource closure checks that the headless server cannot perform. */
internal object AssetReferenceValidator {
    fun validate(root: JsonValue, index: TargetAssetIndex): List<AssetReferenceIssue> {
        val issues = mutableListOf<AssetReferenceIssue>()
        visit(root, "$", AssetHint.NONE, index, issues)
        return issues.distinct()
    }

    private fun visit(
        value: JsonValue,
        path: String,
        inheritedHint: AssetHint,
        index: TargetAssetIndex,
        issues: MutableList<AssetReferenceIssue>,
    ) {
        when {
            value.isObject -> {
                val objectValue = value.asObject()
                objectValue.names().forEach { name ->
                    val child = objectValue.get(name)
                    val hint = hint(name, objectValue, path)
                    val childPath = pathFor(path, name)
                    when {
                        child.isString -> check(child.asString(), childPath, hint, index, issues)
                        child.isArray -> visit(child, childPath, hint, index, issues)
                        child.isObject -> visit(child, childPath, hint, index, issues)
                    }
                }
            }

            value.isArray -> {
                val array = value.asArray()
                for (i in 0 until array.size()) {
                    val child = array.get(i)
                    val childPath = "$path[$i]"
                    if (child.isString) {
                        check(child.asString(), childPath, inheritedHint, index, issues)
                    } else {
                        visit(child, childPath, inheritedHint, index, issues)
                    }
                }
            }
        }
    }

    private fun check(
        value: String,
        path: String,
        hint: AssetHint,
        index: TargetAssetIndex,
        issues: MutableList<AssetReferenceIssue>,
    ) {
        if (hint == AssetHint.NONE) return
        val normalized = stripExtension(value, hint)
        val lower = normalized.lowercase(Locale.ROOT)
        val targets: Set<String>
        val rawToTarget: Map<String, String>
        val label: String
        when (hint) {
            AssetHint.SPRITE -> {
                targets = index.spriteTargets
                rawToTarget = index.spriteRawToTarget
                label = "sprite"
            }

            AssetHint.SOUND -> {
                targets = index.soundTargets
                rawToTarget = index.soundRawToTarget
                label = "sound"
            }

            AssetHint.MUSIC -> {
                targets = index.musicTargets
                rawToTarget = index.musicRawToTarget
                label = "music"
            }

            AssetHint.NONE -> return
        }

        if (lower in targets) return
        rawToTarget[lower]?.let { target ->
            if (!target.equals(normalized, ignoreCase = true)) {
                issues += AssetReferenceIssue(
                    code = if (hint == AssetHint.SPRITE) {
                        "SPRITE_REFERENCE_NOT_DP_PREFIXED"
                    } else {
                        "AUDIO_REFERENCE_NOT_DP_PREFIXED"
                    },
                    path = path,
                    value = value,
                    message = "Custom $label '$value' is present in the pack but is registered as '$target'.",
                    suggestion = "Change this reference to '$target'.",
                )
            }
            return
        }

        val explicitlyDataPackNamed = lower.startsWith("dp-") || (hint == AssetHint.SPRITE && "-dp-" in lower)
        val likelyCustomMissing = hint == AssetHint.SPRITE && normalized.any { it.code > 0x7f }
        if (explicitlyDataPackNamed || likelyCustomMissing) {
            issues += AssetReferenceIssue(
                code = if (hint == AssetHint.SPRITE) "SPRITE_REFERENCE_MISSING" else "AUDIO_REFERENCE_MISSING",
                path = path,
                value = value,
                message = "Referenced $label '$value' has no matching converted data asset.",
                suggestion = "Add the missing asset or replace the reference with a vanilla/existing name.",
            )
        }
    }

    private fun stripExtension(value: String, hint: AssetHint): String = when (hint) {
        AssetHint.SPRITE -> value.removeSuffix(".png")
        AssetHint.SOUND,
        AssetHint.MUSIC -> value.removeSuffix(".ogg").removeSuffix(".mp3")
        AssetHint.NONE -> value
    }

    private fun hint(name: String, parent: JsonObject, parentPath: String): AssetHint {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower == "name" -> {
                if (parentPath == "$") return AssetHint.NONE
                val type = parent.get("type")?.takeIf { it.isString }?.asString()?.lowercase(Locale.ROOT).orEmpty()
                if (type.endsWith("regionpart") || type.endsWith("drawregion")) AssetHint.SPRITE else AssetHint.NONE
            }

            lower.endsWith("sound") || lower == "sounds" -> AssetHint.SOUND
            lower.endsWith("music") || lower == "musics" -> AssetHint.MUSIC
            lower.endsWith("region") || lower.endsWith("sprite") || lower.endsWith("icon") ||
                lower in setOf("icons", "texture", "textures", "fulloverride") -> AssetHint.SPRITE
            else -> AssetHint.NONE
        }
    }

    private fun pathFor(parent: String, name: String): String =
        if (name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "$parent.$name"
        } else {
            "$parent['${name.replace("'", "\\'")}']"
        }

    private enum class AssetHint {
        NONE,
        SPRITE,
        SOUND,
        MUSIC,
    }
}
