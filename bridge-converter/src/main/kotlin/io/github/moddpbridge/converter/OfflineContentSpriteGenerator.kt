package io.github.moddpbridge.converter

import io.github.moddpbridge.model.ContentKind
import org.hjson.JsonObject
import org.hjson.JsonValue
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import javax.imageio.ImageIO

internal data class OfflineContentSpriteResult(
    val files: List<PlannedOutputFile> = emptyList(),
    val fullIcons: Int = 0,
    val outlines: Int = 0,
    val misses: List<String> = emptyList(),
)

/**
 * Reproduces the high-value, deterministic subset of Mindustry's createIcons() pipeline without
 * loading or executing the input mod. B480 cannot safely run that pipeline for a large data pack,
 * because its image packer unload implementation mutates a texture set while iterating it.
 */
internal object OfflineContentSpriteGenerator {
    private const val spriteRoot = "sprites/"
    private const val generatedRoot = "sprites/generated/"
    private const val bundledTurretBaseRoot =
        "/io/github/moddpbridge/converter/mindustry-v159/turret-bases/"

    fun generate(
        contents: List<EmittedContent>,
        existingFiles: List<PlannedOutputFile>,
    ): OfflineContentSpriteResult {
        val workspace = SpriteWorkspace(existingFiles)
        val generated = mutableListOf<PlannedOutputFile>()
        val existingPaths = existingFiles.mapTo(linkedSetOf()) { it.path.lowercase(Locale.ROOT) }
        // Atlas names are global even though generated files are grouped by content hash. Shared
        // weapons/parts can be referenced by several units; emitting the same runtime name into
        // several hash folders makes PixmapPacker and the bridge's collision validator reject it.
        val generatedRuntimeNames = existingFiles.asSequence()
            .filter { it.path.startsWith(generatedRoot, ignoreCase = true) && it.path.endsWith(".png", true) }
            .map { generatedRuntimeName(it.path).lowercase(Locale.ROOT) }
            .toCollection(linkedSetOf())
        val misses = linkedSetOf<String>()
        var fullIcons = 0
        var outlines = 0

        fun add(
            content: EmittedContent,
            runtimeName: String,
            image: BufferedImage,
            label: String,
            isFullIcon: Boolean,
        ): Boolean {
            val runtimeKey = runtimeName.lowercase(Locale.ROOT)
            if (!generatedRuntimeNames.add(runtimeKey)) return false
            val storedName = generatedStorageName(runtimeName)
            val hash = contentHash(content)
            val path = "$generatedRoot$hash/$storedName.png"
            if (!existingPaths.add(path.lowercase(Locale.ROOT))) {
                generatedRuntimeNames.remove(runtimeKey)
                return false
            }
            val bytes = encodePng(image) ?: run {
                generatedRuntimeNames.remove(runtimeKey)
                misses += "${content.kind.name.lowercase()}/${content.basename}: failed to encode '$runtimeName'"
                return false
            }
            generated += PlannedOutputFile(
                path = path,
                bytes = bytes,
                sourcePath = "<bridge-generated:$label/${content.basename}>",
                status = ConvertedFileStatus.NORMALIZED,
            )
            workspace.override(runtimeName, image)
            if (isFullIcon) fullIcons++ else outlines++
            return true
        }

        contents.sortedWith(compareBy<EmittedContent> { it.kind.name }.thenBy { it.basename }).forEach { content ->
            val root = try {
                HjsonNormalizer.parse(content.bytes, content.sourcePath)
            } catch (_: ConversionException) {
                misses += "${content.kind.name.lowercase()}/${content.basename}: normalized content could not be parsed for icons"
                return@forEach
            }
            if (!root.isObject) return@forEach

            when (content.kind) {
                ContentKind.UNIT -> generateUnit(content, root.asObject(), workspace, misses, ::add)
                ContentKind.BLOCK -> generateBlock(content, root.asObject(), workspace, misses, ::add)
                else -> Unit
            }
        }

        return OfflineContentSpriteResult(
            files = generated.sortedBy { it.path },
            fullIcons = fullIcons,
            outlines = outlines,
            misses = misses.toList().sorted(),
        )
    }

    private fun generateUnit(
        content: EmittedContent,
        root: JsonObject,
        workspace: SpriteWorkspace,
        misses: MutableSet<String>,
        add: (EmittedContent, String, BufferedImage, String, Boolean) -> Boolean,
    ) {
        val owner = "dp-${content.basename}"
        val color = parseColor(root.string("outlineColor"), 0x565666ff)
        val radius = root.int("outlineRadius", 3).coerceIn(0, 32)
        val outlinesEnabled = root.bool("outlines", true)

        // RegionPart outlines are created even when UnitType.outlines is false.
        generatePartOutlines(
            content = content,
            value = root.get("parts"),
            ownerName = owner,
            turretShading = false,
            color = color,
            radius = radius,
            workspace = workspace,
            misses = misses,
            add = add,
        )

        val weapons = root.get("weapons")
        if (weapons != null && weapons.isArray) {
            for (index in 0 until weapons.asArray().size()) {
                val value = weapons.asArray().get(index)
                if (!value.isObject) continue
                val weapon = value.asObject()
                val localName = weapon.string("name").orEmpty()
                if (localName.isBlank()) continue
                // ContentParser prefixes Weapon.name with the active `dp` pseudo-mod.
                val weaponName = if (localName.startsWith("dp-", true)) localName else "dp-$localName"

                generatePartOutlines(
                    content = content,
                    value = weapon.get("parts"),
                    ownerName = weaponName,
                    turretShading = false,
                    color = color,
                    radius = radius,
                    workspace = workspace,
                    misses = misses,
                    add = add,
                )

                if (outlinesEnabled) {
                    val source = workspace.image(weaponName)
                    if (source != null) {
                        val makeNew = !weapon.bool("top", true) || containsUnderPart(weapon.get("parts"))
                        val target = if (makeNew) "$weaponName-outline" else weaponName
                        if (!workspace.has(target) || target.equals(weaponName, true)) {
                            add(content, target, outline(source, color, radius), "unit-weapon-outline", false)
                        }
                    } else {
                        misses += "unit/${content.basename}: weapon region '$weaponName' is absent"
                    }
                }
            }
        }

        if (!outlinesEnabled) return

        val conventional = listOf(
            owner,
            "$owner-joint",
            "$owner-foot",
            // UnitType.load() uses "-joint-base" and "-treads" exactly.
            "$owner-joint-base",
            "$owner-leg",
            "$owner-treads",
            "$owner-leg-base",
        )
        conventional.forEachIndexed { index, runtimeName ->
            val source = workspace.image(runtimeName)
            if (source == null) {
                if (index == 0) misses += "unit/${content.basename}: body region '$runtimeName' is absent"
                return@forEachIndexed
            }
            val target = if (index == 0 && root.bool("alwaysCreateOutline", false)) {
                "$runtimeName-outline"
            } else {
                runtimeName
            }
            if (!workspace.has(target) || target.equals(runtimeName, true)) {
                add(content, target, outline(source, color, radius), "unit-outline", false)
            }
        }
    }

    private fun generateBlock(
        content: EmittedContent,
        root: JsonObject,
        workspace: SpriteWorkspace,
        misses: MutableSet<String>,
        add: (EmittedContent, String, BufferedImage, String, Boolean) -> Boolean,
    ) {
        val owner = "dp-${content.basename}"
        val blockType = root.string("type").orEmpty()
        val turret = blockType.endsWith("Turret", ignoreCase = true)
        val color = parseColor(root.string("outlineColor"), 0x404049ff)
        val radius = root.int("outlineRadius", 4).coerceIn(0, 32)
        val drawer = root.get("drawer")

        generateDrawerPartOutlines(
            content = content,
            drawer = drawer,
            owner = owner,
            blockType = blockType,
            color = color,
            radius = radius,
            workspace = workspace,
            misses = misses,
            add = add,
        )

        val requestedNames = blockIconNames(root, drawer, owner, blockType, workspace, misses, content.basename)
        if (requestedNames.isEmpty()) return

        val layers = requestedNames.mapNotNull { name ->
            val image = workspace.image(name)
            if (image == null) {
                misses += "block/${content.basename}: icon layer '$name' is absent"
                null
            } else {
                IconLayer(name, image)
            }
        }.toMutableList()
        if (layers.isEmpty()) return

        val outlineIcon = root.bool("outlineIcon", turret)
        var outlinedRuntimeName: String? = null
        if (outlineIcon) {
            val defaultIndex = if (turret) 1 else layers.lastIndex
            val requestedIndex = root.int("outlinedIcon", defaultIndex)
            // Block.createIcons() treats every negative outlinedIcon as "last", not index zero.
            val selectedIndex = if (requestedIndex < 0) layers.lastIndex else requestedIndex.coerceAtMost(layers.lastIndex)
            val selected = layers[selectedIndex]
            val outlined = outline(selected.image, color, radius)
            // Block.createIcons overwrites the selected atlas name rather than adding -outline.
            add(content, selected.runtimeName, outlined, "block-outline", false)
            layers[selectedIndex] = IconLayer(selected.runtimeName, outlined)
            outlinedRuntimeName = selected.runtimeName
        }

        // DrawTurret.getRegionsToOutline() also creates a separate outline for block.region when
        // the outlined build-icon layer is a distinct preview/top region. This is the outline that
        // part-based turrets draw below their moving pieces at runtime.
        if (turret && !owner.equals(outlinedRuntimeName, ignoreCase = true) && !workspace.has("$owner-outline")) {
            workspace.image(owner)?.let { body ->
                add(content, "$owner-outline", outline(body, color, radius), "turret-body-outline", false)
            }
        }

        // A single ordinary block already falls back to its source region. Composite icons are
        // required for turrets and multi-layer drawers/factories.
        if (!turret && requestedNames.size < 2) return

        val full = compose(layers.map { it.image }) ?: run {
            misses += "block/${content.basename}: icon layers could not be composited"
            return
        }
        add(content, "block-$owner-full", full, "block-full-icon", true)
    }

    private fun generateDrawerPartOutlines(
        content: EmittedContent,
        drawer: JsonValue?,
        owner: String,
        blockType: String,
        color: Int,
        radius: Int,
        workspace: SpriteWorkspace,
        misses: MutableSet<String>,
        add: (EmittedContent, String, BufferedImage, String, Boolean) -> Boolean,
    ) {
        if (drawer == null) return
        when {
            drawer.isArray -> {
                for (index in 0 until drawer.asArray().size()) {
                    generateDrawerPartOutlines(
                        content, drawer.asArray().get(index), owner, blockType, color, radius,
                        workspace, misses, add,
                    )
                }
            }

            drawer.isObject -> {
                val objectValue = drawer.asObject()
                val type = objectValue.string("type").orEmpty()
                val turretShading = type.endsWith("DrawTurret", true) ||
                    (type.isBlank() && blockType.endsWith("Turret", true))
                generatePartOutlines(
                    content, objectValue.get("parts"), owner, turretShading, color, radius,
                    workspace, misses, add,
                )
                val ammoParts = objectValue.get("ammoParts")
                if (ammoParts != null && ammoParts.isObject) {
                    ammoParts.asObject().names().forEach { key ->
                        generatePartOutlines(
                            content, ammoParts.asObject().get(key), owner, turretShading, color, radius,
                            workspace, misses, add,
                        )
                    }
                }
                objectValue.get("drawers")?.let {
                    generateDrawerPartOutlines(
                        content, it, owner, blockType, color, radius, workspace, misses, add,
                    )
                }
            }
        }
    }

    private fun generatePartOutlines(
        content: EmittedContent,
        value: JsonValue?,
        ownerName: String,
        turretShading: Boolean,
        color: Int,
        radius: Int,
        workspace: SpriteWorkspace,
        misses: MutableSet<String>,
        add: (EmittedContent, String, BufferedImage, String, Boolean) -> Boolean,
    ) {
        if (value == null) return
        when {
            value.isArray -> {
                for (index in 0 until value.asArray().size()) {
                    generatePartOutlines(
                        content, value.asArray().get(index), ownerName, turretShading, color, radius,
                        workspace, misses, add,
                    )
                }
            }

            value.isObject -> {
                val part = value.asObject()
                val type = part.string("type").orEmpty()
                if (type.endsWith("RegionPart", ignoreCase = true) &&
                    part.bool("outline", true) && part.bool("drawRegion", true)
                ) {
                    val explicitName = part.string("name")
                    val rawName = explicitName ?: ownerName + part.string("suffix").orEmpty()
                    val realName = workspace.resolve(rawName) ?: rawName
                    val sources = if (turretShading && part.bool("mirror", true)) {
                        listOf("$realName-r", "$realName-l")
                    } else {
                        listOf(realName)
                    }
                    sources.forEach { sourceName ->
                        val targetName = "$sourceName-outline"
                        if (workspace.has(targetName)) return@forEach
                        val source = workspace.image(sourceName)
                        if (source == null) {
                            misses += "${content.kind.name.lowercase()}/${content.basename}: part region '$sourceName' is absent"
                        } else {
                            add(content, targetName, outline(source, color, radius), "part-outline", false)
                        }
                    }
                }
                generatePartOutlines(
                    content, part.get("children"), ownerName, turretShading, color, radius,
                    workspace, misses, add,
                )
            }
        }
    }

    private fun blockIconNames(
        root: JsonObject,
        drawer: JsonValue?,
        owner: String,
        blockType: String,
        workspace: SpriteWorkspace,
        misses: MutableSet<String>,
        basename: String,
    ): List<String> {
        if (drawer != null) {
            val icons = drawerIconNames(drawer, owner, blockType, root.int("size", 1), workspace, misses, basename)
            if (icons.isNotEmpty()) return icons
        }

        return when (blockType.lowercase(Locale.ROOT)) {
            "unitfactory" -> listOf(owner, "$owner-out", "$owner-top")
            "reconstructor" -> listOf(owner, "$owner-in", "$owner-out", "$owner-top")
            "drill" -> listOf(owner, "$owner-rotator", "$owner-top")
            "burstdrill" -> listOf(owner, "$owner-top")
            "solidpump" -> listOf(owner, "$owner-rotator", "$owner-top")
            else -> if (blockType.endsWith("Turret", true)) {
                drawerIconNames(null, owner, blockType, root.int("size", 1), workspace, misses, basename)
            } else {
                listOf(owner)
            }
        }
    }

    private fun drawerIconNames(
        drawer: JsonValue?,
        owner: String,
        blockType: String,
        size: Int,
        workspace: SpriteWorkspace,
        misses: MutableSet<String>,
        basename: String,
    ): List<String> {
        if (drawer == null) {
            return if (blockType.endsWith("Turret", true)) {
                turretIconNames(null, owner, size, workspace, misses, basename)
            } else {
                listOf(owner)
            }
        }
        if (drawer.isArray) {
            return buildList {
                for (index in 0 until drawer.asArray().size()) {
                    addAll(drawerIconNames(drawer.asArray().get(index), owner, blockType, size, workspace, misses, basename))
                }
            }
        }
        if (!drawer.isObject) return emptyList()

        val objectValue = drawer.asObject()
        val override = objectValue.get("iconOverride")
        if (override != null && override.isArray) {
            return buildList {
                for (index in 0 until override.asArray().size()) {
                    val suffix = override.asArray().get(index)
                    if (suffix.isString) add(owner + suffix.asString())
                }
            }
        }

        val type = objectValue.string("type").orEmpty()
        return when {
            type.isBlank() && blockType.endsWith("Turret", true) ->
                turretIconNames(objectValue, owner, size, workspace, misses, basename)
            type.isBlank() || type.endsWith("DrawDefault", true) -> listOf(owner)
            type.endsWith("DrawMulti", true) -> drawerIconNames(
                objectValue.get("drawers"), owner, blockType, size, workspace, misses, basename,
            )
            type.endsWith("DrawTurret", true) ->
                turretIconNames(objectValue, owner, size, workspace, misses, basename)
            type.endsWith("DrawRegion", true) -> {
                val raw = objectValue.string("name") ?: owner + objectValue.string("suffix").orEmpty()
                listOf(workspace.resolve(raw) ?: raw)
            }
            type.endsWith("DrawBlurSpin", true) -> listOf(owner + objectValue.string("suffix").orEmpty())
            type.endsWith("DrawFrames", true) -> listOf("$owner-frame0")
            type.endsWith("DrawPistons", true) ->
                listOf(owner + (objectValue.string("suffix") ?: "-piston") + "-icon")
            type.endsWith("DrawPower", true) && !objectValue.bool("mixcol", true) ->
                listOf(owner + (objectValue.string("suffix") ?: "-power") + "-empty")
            type.endsWith("DrawWeave", true) -> listOf("$owner-weave")
            type.endsWith("DrawMultiWeave", true) && !objectValue.bool("fadeWeave", false) ->
                listOf("$owner-weave")
            type.endsWith("DrawBlockParts", true) && workspace.has("$owner-preview") -> listOf("$owner-preview")
            else -> emptyList()
        }
    }

    private fun turretIconNames(
        drawer: JsonObject?,
        owner: String,
        size: Int,
        workspace: SpriteWorkspace,
        misses: MutableSet<String>,
        basename: String,
    ): List<String> {
        val prefix = drawer?.string("basePrefix").orEmpty()
        val baseName = "${prefix}block-$size"
        val base = when {
            // DrawTurret.load(): explicit per-turret base, then the active mod namespace, then
            // vanilla. Checking vanilla first silently discards a mod-provided block-N base.
            workspace.image("$owner-base") != null -> "$owner-base"
            workspace.image("dp-$baseName") != null -> "dp-$baseName"
            workspace.image(baseName) != null -> baseName
            else -> {
                misses += "block/$basename: turret base regions '$owner-base', 'dp-$baseName', and '$baseName' are unavailable for offline composition"
                null
            }
        }
        val preview = when {
            workspace.image("$owner-preview") != null -> "$owner-preview"
            workspace.image(owner) != null -> owner
            else -> {
                misses += "block/$basename: turret preview '$owner-preview' and body '$owner' are absent"
                null
            }
        }
        val top = "$owner-top".takeIf { workspace.image(it) != null }
        // Do not collapse a missing preview/body into a one-layer list: Block.createIcons indexes
        // the original DrawTurret array, and treating the remaining base as index 1 would outline
        // and globally override shared `block-N` bases. An incomplete turret is reported instead.
        if (base == null || preview == null) return emptyList()
        return listOfNotNull(base, preview, top)
    }

    private fun containsUnderPart(value: JsonValue?): Boolean {
        if (value == null) return false
        return when {
            value.isArray -> (0 until value.asArray().size()).any { containsUnderPart(value.asArray().get(it)) }
            value.isObject -> value.asObject().bool("under", false) || containsUnderPart(value.asObject().get("children"))
            else -> false
        }
    }

    private fun outline(source: BufferedImage, rgba: Int, radius: Int): BufferedImage {
        val output = copyImage(source)
        if (radius <= 0) return output
        val argb = rgbaToArgb(rgba)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val alpha = source.getRGB(x, y) ushr 24 and 0xff
                if (alpha >= 255) continue
                var found = false
                loop@ for (dx in -radius..radius) {
                    for (dy in -radius..radius) {
                        if (dx * dx + dy * dy > radius * radius) continue
                        val px = x + dx
                        val py = y + dy
                        if (px !in 0 until source.width || py !in 0 until source.height) continue
                        if ((source.getRGB(px, py) ushr 24 and 0xff) != 0) {
                            found = true
                            break@loop
                        }
                    }
                }
                if (found) output.setRGB(x, y, argb)
            }
        }
        return output
    }

    private fun compose(layers: List<BufferedImage>): BufferedImage? {
        val first = layers.firstOrNull() ?: return null
        val output = copyImage(first)
        val graphics = output.createGraphics()
        try {
            graphics.composite = AlphaComposite.SrcOver
            for (index in 1 until layers.size) {
                // Arc's Pixmap.draw(other, true) draws at 0,0 and clips to the first layer.
                graphics.drawImage(layers[index], 0, 0, null)
            }
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun copyImage(source: BufferedImage): BufferedImage {
        val output = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.drawImage(source, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return output
    }

    private fun parseColor(value: String?, defaultRgba: Int): Int {
        val text = value?.trim()?.removePrefix("#") ?: return defaultRgba
        return when {
            text.length == 6 && text.all(::isHexDigit) -> (text.toLong(16).toInt() shl 8) or 0xff
            text.length == 8 && text.all(::isHexDigit) -> text.toLong(16).toInt()
            else -> defaultRgba
        }
    }

    private fun rgbaToArgb(rgba: Int): Int =
        ((rgba and 0xff) shl 24) or ((rgba ushr 8) and 0x00ffffff)

    private fun isHexDigit(value: Char): Boolean =
        value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'

    private fun encodePng(image: BufferedImage): ByteArray? = try {
        ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(image, "png", output)) null else output.toByteArray()
        }
    } catch (_: Exception) {
        null
    }

    private fun contentHash(content: EmittedContent): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.bytes)
        return "${content.kind.name.lowercase(Locale.ROOT)}_${encodeMindustryHash(digest)}"
    }

    private fun encodeMindustryHash(data: ByteArray): String {
        require(data.size == 32)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = StringBuilder(52)
        var index = 0
        repeat(6) {
            val bits =
                ((data[index++].toLong() and 0xffL) shl 32) or
                    ((data[index++].toLong() and 0xffL) shl 24) or
                    ((data[index++].toLong() and 0xffL) shl 16) or
                    ((data[index++].toLong() and 0xffL) shl 8) or
                    (data[index++].toLong() and 0xffL)
            for (shift in 35 downTo 0 step 5) output.append(alphabet[((bits ushr shift) and 0x1f).toInt()])
        }
        val tail = ((data[index++].toInt() and 0xff) shl 8) or (data[index].toInt() and 0xff)
        output.append(alphabet[(tail ushr 11) and 0x1f])
        output.append(alphabet[(tail ushr 6) and 0x1f])
        output.append(alphabet[(tail ushr 1) and 0x1f])
        output.append(alphabet[(tail shl 4) and 0x1f])
        return output.toString()
    }

    private fun generatedStorageName(runtimeName: String): String =
        if (runtimeName.startsWith("dp-", ignoreCase = true)) runtimeName.substring(3) else runtimeName

    private fun generatedRuntimeName(path: String): String {
        val basename = path.substringAfterLast('/').substringBeforeLast('.')
        return if (basename.contains("-dp-")) basename else "dp-$basename"
    }

    private data class IconLayer(val runtimeName: String, val image: BufferedImage)

    private class SpriteWorkspace(files: List<PlannedOutputFile>) {
        private data class Source(val path: String, val bytes: ByteArray)

        private val sources = linkedMapOf<String, Source>()
        private val decoded = mutableMapOf<String, BufferedImage?>()
        private val overrides = mutableMapOf<String, BufferedImage>()

        init {
            files.asSequence()
                .filter { it.path.startsWith(spriteRoot, ignoreCase = true) && it.path.endsWith(".png", true) }
                .sortedByDescending { it.path.startsWith(generatedRoot, ignoreCase = true) }
                .forEach { file ->
                    val basename = file.path.substringAfterLast('/').substringBeforeLast('.')
                    val runtime = if (
                        file.path.startsWith(generatedRoot, ignoreCase = true) && basename.contains("-dp-")
                    ) {
                        basename
                    } else {
                        "dp-$basename"
                    }
                    sources.putIfAbsent(runtime.lowercase(Locale.ROOT), Source(file.path, file.bytes))
                }
        }

        fun resolve(rawName: String): String? {
            if (has(rawName)) return canonical(rawName)
            val prefixed = if (rawName.startsWith("dp-", true)) rawName else "dp-$rawName"
            return prefixed.takeIf(::has)?.let(::canonical)
        }

        fun has(runtimeName: String): Boolean =
            runtimeName.lowercase(Locale.ROOT) in overrides ||
                runtimeName.lowercase(Locale.ROOT) in sources ||
                bundledTurretBase(runtimeName) != null

        fun image(runtimeName: String): BufferedImage? {
            val key = runtimeName.lowercase(Locale.ROOT)
            overrides[key]?.let { return it }
            val source = sources[key]
            if (source != null) {
                return decoded.getOrPut(source.path) {
                    try {
                        ImageIO.read(ByteArrayInputStream(source.bytes))?.let(::copyImage)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            return bundledTurretBase(runtimeName)
        }

        fun override(runtimeName: String, image: BufferedImage) {
            overrides[runtimeName.lowercase(Locale.ROOT)] = image
        }

        private fun canonical(runtimeName: String): String =
            sources.keys.firstOrNull { it.equals(runtimeName, true) }
                ?: overrides.keys.firstOrNull { it.equals(runtimeName, true) }
                ?: runtimeName

        private fun bundledTurretBase(runtimeName: String): BufferedImage? {
            val lower = runtimeName.lowercase(Locale.ROOT)
            if (!lower.matches(Regex("(?:reinforced-)?block-[1-5]"))) return null
            return decoded.getOrPut("builtin:$lower") {
                OfflineContentSpriteGenerator::class.java.getResourceAsStream("$bundledTurretBaseRoot$lower.png")
                    ?.use { ImageIO.read(it)?.let(::copyImage) }
            }
        }
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isString }?.asString()

    private fun JsonObject.bool(name: String, default: Boolean): Boolean =
        get(name)?.takeIf { it.isBoolean }?.asBoolean() ?: default

    private fun JsonObject.int(name: String, default: Int): Int =
        get(name)?.takeIf { it.isNumber }?.asInt() ?: default
}
