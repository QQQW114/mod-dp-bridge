package io.github.moddpbridge.converter

import io.github.moddpbridge.model.ContentKind
import org.hjson.JsonValue
import java.nio.charset.StandardCharsets
import java.util.Locale

internal enum class NamespaceAssetKind {
    SPRITE,
    SOUND,
    MUSIC,
}

/** One original asset name and the exact runtime name produced by the v159 data pack. */
internal data class NamespaceAssetSymbol(
    val kind: NamespaceAssetKind,
    val sourceBasename: String,
    val sourceRuntimeName: String,
    /** Additional source-side lookup names, such as `subdir/shot` for mod audio. */
    val sourceAliases: Set<String> = emptySet(),
    val targetRuntimeName: String,
)

internal data class ModNamespaceSymbols(
    val sourceNamespace: String,
    val contentNames: Map<ContentKind, Set<String>>,
    val assets: List<NamespaceAssetSymbol>,
) {
    private val allContentNames = contentNames.values.flatten()

    val contentByQualifiedName: Map<String, String> = allContentNames.associateBy(
        keySelector = { "$sourceNamespace-$it".lowercase(Locale.ROOT) },
        valueTransform = { it },
    )

    val contentByLocalName: Map<String, String> = allContentNames.associateBy(
        keySelector = { it.lowercase(Locale.ROOT) },
        valueTransform = { it },
    )

    val assetsByKind: Map<NamespaceAssetKind, Map<String, NamespaceAssetSymbol>> =
        NamespaceAssetKind.entries.associateWith { kind ->
            buildMap {
                assets.filter { it.kind == kind }.forEach { asset ->
                    put(asset.sourceBasename.lowercase(Locale.ROOT), asset)
                    put(asset.sourceRuntimeName.lowercase(Locale.ROOT), asset)
                    asset.sourceAliases.forEach { alias ->
                        put(alias.lowercase(Locale.ROOT), asset)
                    }
                }
            }
        }
}

internal data class NamespaceRewrite(
    val path: String,
    val from: String,
    val to: String,
    val kind: String,
)

internal data class UnresolvedNamespaceReference(
    val path: String,
    val value: String,
    val expectedKind: String,
)

internal data class NamespaceRewriteResult(
    val rewrites: List<NamespaceRewrite>,
    val unresolved: List<UnresolvedNamespaceReference>,
)

internal data class BundleNamespaceRewriteResult(
    val bytes: ByteArray,
    val rewrites: List<NamespaceRewrite>,
)

/**
 * Rewrites names that were bound to a regular mod namespace into v159's fixed `dp` namespace.
 * This is deliberately symbol-driven: arbitrary strings are never globally replaced.
 */
internal object ModNamespaceRewriter {
    fun rewriteContent(root: JsonValue, symbols: ModNamespaceSymbols): NamespaceRewriteResult =
        JsonTreeRewriter(symbols, RewriteMode.CONTENT).rewrite(root)

    fun rewritePatch(root: JsonValue, symbols: ModNamespaceSymbols): NamespaceRewriteResult =
        JsonTreeRewriter(symbols, RewriteMode.PATCH).rewrite(root)

    fun rewriteBundle(bytes: ByteArray, symbols: ModNamespaceSymbols): BundleNamespaceRewriteResult {
        val rewrites = mutableListOf<NamespaceRewrite>()
        val source = bytes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val output = lines.mapIndexed { index, line ->
            val match = PROPERTY_LINE.matchEntire(line) ?: return@mapIndexed line
            val key = match.groupValues[2]
            val rewritten = rewriteBundleKey(key, symbols) ?: return@mapIndexed line
            rewrites += NamespaceRewrite(
                path = "line ${index + 1}",
                from = key,
                to = rewritten,
                kind = "bundle-key",
            )
            match.groupValues[1] + rewritten + match.groupValues[3] + match.groupValues[4]
        }.joinToString("\n").trimEnd() + "\n"

        return BundleNamespaceRewriteResult(output.toByteArray(StandardCharsets.UTF_8), rewrites)
    }

    private fun rewriteBundleKey(key: String, symbols: ModNamespaceSymbols): String? {
        val segments = key.split('.')
        if (segments.size < 3) return null
        val kind = ContentKind.entries.firstOrNull { it.name.equals(segments[0], ignoreCase = true) } ?: return null
        val qualified = segments[1]
        val localName = symbols.contentByQualifiedName[qualified.lowercase(Locale.ROOT)] ?: return null
        if (symbols.contentNames[kind].orEmpty().none { it.equals(localName, ignoreCase = true) }) return null
        return buildString {
            append(segments[0])
            append(".dp-")
            append(localName)
            segments.drop(2).forEach { segment -> append('.').append(segment) }
        }
    }

    private val PROPERTY_LINE = Regex("^(\\s*)([^#!\\s=:]+)(\\s*[=:]\\s*)(.*)$")
}

private enum class RewriteMode {
    /** Content files need special handling for member names such as Weapon.name. */
    CONTENT,

    /** DataPatcher applies patches after currentMod is cleared. */
    PATCH,
}

private enum class ReferenceHint {
    GENERIC,
    LITERAL,
    CONTENT,
    CONTENT_MAP,
    NAME,
    SPRITE,
    SOUND,
    MUSIC,
}

private class JsonTreeRewriter(
    private val symbols: ModNamespaceSymbols,
    private val mode: RewriteMode,
) {
    private val rewrites = mutableListOf<NamespaceRewrite>()
    private val unresolved = mutableListOf<UnresolvedNamespaceReference>()
    private val namespacePrefix = "${symbols.sourceNamespace}-"

    fun rewrite(root: JsonValue): NamespaceRewriteResult {
        rewriteValue(root, "$", ReferenceHint.GENERIC)
        return NamespaceRewriteResult(
            rewrites = rewrites.distinct(),
            unresolved = unresolved.distinct(),
        )
    }

    private fun rewriteValue(value: JsonValue, path: String, inheritedHint: ReferenceHint) {
        when {
            value.isObject -> rewriteObject(value, path, inheritedHint)
            value.isArray -> {
                val array = value.asArray()
                for (index in 0 until array.size()) {
                    val child = array.get(index)
                    if (child.isString) {
                        rewriteString(child.asString(), "$path[$index]", inheritedHint)?.let { array.set(index, it) }
                    } else {
                        rewriteValue(child, "$path[$index]", inheritedHint)
                    }
                }
            }
        }
    }

    private fun rewriteObject(value: JsonValue, path: String, inheritedHint: ReferenceHint) {
        val objectValue = value.asObject()
        objectValue.names().toList().forEach { originalName ->
            var name = originalName
            val child = objectValue.get(originalName)
            val keyReplacement = rewriteContentReference(originalName, dpQualified = true)
                ?: if (inheritedHint == ReferenceHint.CONTENT_MAP || isPatchContentMap(path)) {
                    rewriteLocalContentReference(originalName, dpQualified = true)
                } else {
                    null
                }
            if (keyReplacement != null && keyReplacement != originalName) {
                if (objectValue.get(keyReplacement) == null) {
                    objectValue.remove(originalName)
                    objectValue.set(keyReplacement, child)
                    name = keyReplacement
                    record(pathFor(path, originalName), originalName, keyReplacement, "object-key")
                } else {
                    unresolved += UnresolvedNamespaceReference(
                        path = pathFor(path, originalName),
                        value = originalName,
                        expectedKind = "non-colliding object key",
                    )
                }
            }

            val childPath = pathFor(path, name)
            val hint = referenceHint(name, objectValue, path)
            when {
                child.isString -> rewriteString(child.asString(), childPath, hint)?.let { objectValue.set(name, it) }
                child.isArray -> rewriteValue(child, childPath, hint)
                child.isObject -> rewriteValue(child, childPath, hint)
            }
        }
    }

    private fun rewriteString(value: String, path: String, hint: ReferenceHint): String? {
        if (hint == ReferenceHint.LITERAL) return null
        if (mode == RewriteMode.CONTENT && hint == ReferenceHint.NAME) {
            rewriteContentReference(value, dpQualified = false)?.let { replacement ->
                if (replacement != value) {
                    record(path, value, replacement, "content-or-member-name")
                    return replacement
                }
            }
            if (value.startsWith(namespacePrefix, ignoreCase = true)) {
                val replacement = value.substring(namespacePrefix.length)
                record(path, value, replacement, "member-name")
                return replacement
            }
            return null
        }

        val assetKind = when (hint) {
            ReferenceHint.SPRITE -> NamespaceAssetKind.SPRITE
            ReferenceHint.SOUND -> NamespaceAssetKind.SOUND
            ReferenceHint.MUSIC -> NamespaceAssetKind.MUSIC
            else -> null
        }
        if (assetKind != null) {
            findAsset(assetKind, value)?.let { target ->
                if (target != value) {
                    record(path, value, target, assetKind.name.lowercase(Locale.ROOT))
                    return target
                }
            }
        }

        // Explicit dp- names are accepted by both the regular Content parser and manual lookup
        // paths (e.g. PayloadStack) that do not apply currentMod automatically.
        rewriteContentReference(value, dpQualified = true)?.let { replacement ->
            if (replacement != value) {
                record(path, value, replacement, "content-reference")
                return replacement
            }
        }

        if (hint == ReferenceHint.CONTENT || hint == ReferenceHint.CONTENT_MAP) {
            rewriteLocalContentReference(value, dpQualified = true)?.let { replacement ->
                if (replacement != value) {
                    record(path, value, replacement, "local-content-reference")
                    return replacement
                }
            }
        }

        if (assetKind == null) {
            NamespaceAssetKind.entries.forEach { kind ->
                findAsset(kind, value)?.let { target ->
                    if (target != value && value.startsWith(namespacePrefix, ignoreCase = true)) {
                        record(path, value, target, kind.name.lowercase(Locale.ROOT))
                        return target
                    }
                }
            }
        }

        if (hint != ReferenceHint.GENERIC && value.startsWith(namespacePrefix, ignoreCase = true)) {
            unresolved += UnresolvedNamespaceReference(
                path = path,
                value = value,
                expectedKind = hint.name.lowercase(Locale.ROOT),
            )
        }
        return null
    }

    private fun rewriteContentReference(value: String, dpQualified: Boolean): String? {
        val slash = value.indexOf('/')
        val head = if (slash >= 0) value.substring(0, slash) else value
        val suffix = if (slash >= 0) value.substring(slash) else ""
        val localName = symbols.contentByQualifiedName[head.lowercase(Locale.ROOT)] ?: return null
        return (if (dpQualified) "dp-$localName" else localName) + suffix
    }

    private fun rewriteLocalContentReference(value: String, dpQualified: Boolean): String? {
        val slash = value.indexOf('/')
        val head = if (slash >= 0) value.substring(0, slash) else value
        val suffix = if (slash >= 0) value.substring(slash) else ""
        val localName = symbols.contentByLocalName[head.lowercase(Locale.ROOT)] ?: return null
        return (if (dpQualified) "dp-$localName" else localName) + suffix
    }

    private fun findAsset(kind: NamespaceAssetKind, value: String): String? {
        val withoutExtension = when (kind) {
            NamespaceAssetKind.SOUND,
            NamespaceAssetKind.MUSIC -> value.removeSuffix(".ogg").removeSuffix(".mp3")
            NamespaceAssetKind.SPRITE -> value.removeSuffix(".png")
        }
        return symbols.assetsByKind.getValue(kind)[withoutExtension.lowercase(Locale.ROOT)]?.targetRuntimeName
    }

    private fun referenceHint(name: String, parent: org.hjson.JsonObject, parentPath: String): ReferenceHint {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower in setOf("description", "details", "localizedname", "displayname") -> ReferenceHint.LITERAL
            lower == "name" -> {
                if (parentPath == "$") {
                    ReferenceHint.LITERAL
                } else {
                    val objectType = parent.get("type")?.takeIf { it.isString }?.asString()?.lowercase(Locale.ROOT).orEmpty()
                    when {
                        objectType.endsWith("regionpart") || objectType.endsWith("drawregion") -> ReferenceHint.SPRITE
                        objectType.endsWith("weapon") || parent.get("bullet") != null || parent.get("reload") != null ->
                            ReferenceHint.NAME
                        objectType.endsWith("statuseffect") -> ReferenceHint.NAME
                        else -> ReferenceHint.LITERAL
                    }
                }
            }
            lower.endsWith("sound") || lower == "sounds" -> ReferenceHint.SOUND
            lower.endsWith("music") || lower == "musics" -> ReferenceHint.MUSIC
            lower.endsWith("region") || lower.endsWith("sprite") ||
                lower.endsWith("icon") ||
                lower in setOf("icons", "texture", "textures", "fulloverride") -> ReferenceHint.SPRITE
            lower in CONTENT_MAP_FIELDS -> ReferenceHint.CONTENT_MAP
            lower in CONTENT_REFERENCE_FIELDS -> ReferenceHint.CONTENT
            else -> ReferenceHint.GENERIC
        }
    }

    private fun record(path: String, from: String, to: String, kind: String) {
        rewrites += NamespaceRewrite(path, from, to, kind)
    }

    private fun pathFor(parent: String, name: String): String =
        if (name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "$parent.$name"
        } else {
            "$parent['${name.replace("'", "\\'")}']"
        }

    private fun isPatchContentMap(path: String): Boolean {
        if (mode != RewriteMode.PATCH || !path.startsWith("$.") || path.substring(2).contains('.')) return false
        val bucket = path.substring(2).lowercase(Locale.ROOT)
        return ContentKind.entries.any { kind ->
            bucket == kind.name.lowercase(Locale.ROOT) || bucket == kind.folderName.lowercase(Locale.ROOT)
        }
    }

    private companion object {
        val CONTENT_REFERENCE_FIELDS = setOf(
            "item", "items", "liquid", "liquids", "unit", "units", "block", "blocks",
            "status", "shootstatus", "immunities", "opposites", "affinities",
            "requirements", "outputitem", "outputitems", "outputliquid", "outputliquids",
            "spawnunit", "unittype", "previous", "upgrades",
            "floor", "wall", "decoration", "oredefault", "replacement", "liquiddrop",
        )
        val CONTENT_MAP_FIELDS = setOf("ammotypes", "capacities")
    }
}
