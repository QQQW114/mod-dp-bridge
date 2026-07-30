package io.github.moddpbridge.converter

import java.util.Locale

internal data class SourceDetection(
    val kind: DetectedSourceKind,
    val slug: String,
    val legacyCpEntry: SourceEntry? = null,
    /** Namespace Mindustry used while loading the original mod's content. */
    val modNamespace: String? = null,
)

internal object SourceDetector {
    val supportedRoots = setOf("content", "patches", "bundles", "sprites", "sounds", "music")
    private val legacyPatchKeys = setOf("item", "block", "liquid", "status", "unit", "weather")
    private val textExtensions = setOf("json", "hjson", "json5")

    fun detect(snapshot: SourceSnapshot): SourceDetection {
        val entries = snapshot.entries
        val modMeta = entries.firstOrNull { entry ->
            entry.path.equals("mod.hjson", ignoreCase = true) ||
                entry.path.equals("mod.json", ignoreCase = true)
        }
        val hasAssetsRoot = entries.any { it.path.substringBefore('/').equals("assets", ignoreCase = true) }
        val inputExtension = snapshot.originalName.substringAfterLast('.', "").lowercase(Locale.ROOT)

        if (modMeta != null || hasAssetsRoot || inputExtension == "jar") {
            val name = modMeta?.let(::metadataName)
                ?: snapshot.originalName.substringBeforeLast('.')
            return SourceDetection(
                kind = DetectedSourceKind.MOD,
                slug = safeSlug(name),
                modNamespace = mindustryModNamespace(name),
            )
        }

        if (entries.size == 1 && entries.single().extension in textExtensions) {
            val entry = entries.single()
            val value = HjsonNormalizer.parseLegacyCp(entry.bytes, entry.path).first
            if (value.isObject && value.asObject().names().any { it.lowercase(Locale.ROOT) in legacyPatchKeys }) {
                val name = value.asObject().getString("name", entry.basename.substringBeforeLast('.'))
                return SourceDetection(DetectedSourceKind.LEGACY_CP, safeSlug(name), entry)
            }
        }

        if (entries.any { it.path.substringBefore('/').lowercase(Locale.ROOT) in supportedRoots }) {
            return SourceDetection(
                DetectedSourceKind.DATA_PACK,
                safeSlug(snapshot.originalName.substringBeforeLast('.')),
            )
        }

        throw ConversionException(
            "Could not identify the input as a Mindustry mod, legacy CP, or v159 data pack.",
        )
    }

    private fun metadataName(entry: SourceEntry): String? {
        val value = HjsonNormalizer.parse(entry.bytes, entry.path)
        if (!value.isObject) return null
        return value.asObject().get("name")
            ?.takeIf { it.isString }
            ?.asString()
            ?.takeIf { it.isNotBlank() }
    }

    /** Mirrors LoadedMod.name: lower-case metadata name with spaces replaced by hyphens. */
    private fun mindustryModNamespace(name: String): String =
        name.lowercase(Locale.ROOT).replace(' ', '-')
}
