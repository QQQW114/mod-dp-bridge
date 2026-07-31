package io.github.moddpbridge.runtimeassets

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeAssetStagerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `stages only DP assets and resolves known tool directory conflicts`() {
        val jar = jar(
            "mod.hjson" to "name: test",
            "bundles/bundle.properties" to "formal-bundle",
            "bundles/blank/bundle.properties" to "blank-bundle",
            "bundles/blank/bundle_fr.properties" to "tool-only-bundle",
            "sprites/unit/foo.png" to "formal-sprite",
            "sprites/pre-processed/foo.png" to "processed-sprite",
            "sprites/pre-processed/tool-only.png" to "tool-only-sprite",
            "sounds/fire.ogg" to "sound",
            "music/theme.mp3" to "music",
            "assets/sprites/from-assets.png" to "assets-prefix",
            "sounds/aaa-info.txt" to "not-audio",
            "maps/map.msav" to "map",
            "assets/scripts/main.js" to "script",
            "shaders/effect.frag" to "shader",
            "textures/noise.png" to "texture",
            "sprites-override/ui/logo.png" to "override",
            "example/Mod.class" to "class",
        )

        val snapshot = RuntimeAssetStager.scan(jar)

        assertEquals(
            listOf(
                "bundles/bundle.properties",
                "music/theme.mp3",
                "sounds/fire.ogg",
                "sprites/pre-processed/tool-only.png",
                "sprites/unit/foo.png",
            ),
            snapshot.files.map { it.outputPath },
        )
        assertEquals("formal-bundle", text(snapshot, "bundles/bundle.properties"))
        assertEquals("formal-sprite", text(snapshot, "sprites/unit/foo.png"))
        assertEquals(6, snapshot.candidateFileCount)
        assertTrue(snapshot.diagnostics.any { it.code == "BUNDLE_BLANK_SHADOWED" })
        assertTrue(snapshot.diagnostics.any { it.code == "BUNDLE_BLANK_EXCLUDED" })
        assertTrue(snapshot.diagnostics.any { it.code == "PREPROCESSED_SPRITE_SHADOWED" })
        assertTrue(snapshot.diagnostics.any { it.code == "PREPROCESSED_SPRITE_RETAINED" })
        assertTrue(snapshot.diagnostics.any { it.code == "NESTED_ASSETS_TREE_NOT_LOADED" })
        assertEquals(4, snapshot.diagnostics.count { it.code == "NON_DP_RUNTIME_ROOT_EXCLUDED" })
        assertTrue(snapshot.diagnostics.any { it.code == "UNSUPPORTED_RUNTIME_ASSET_EXTENSION" })
        assertEquals(jar.toAbsolutePath().normalize().toString(), snapshot.sourceJar.path)
        assertEquals(sha256(jar.readBytes()), snapshot.sourceJar.sha256)

        val second = RuntimeAssetStager.scan(jar)
        assertEquals(snapshot.stagingSha256, second.stagingSha256)
        assertEquals(snapshot.files, second.files)

        val staging = temporaryDirectory.resolve("staging")
        snapshot.writeTo(staging)
        snapshot.files.forEach { file ->
            assertContentEquals(file.bytes(), staging.resolve(file.outputPath).readBytes())
        }
        assertFailsWith<RuntimeAssetStagingException> { snapshot.writeTo(staging) }
    }

    @Test
    fun `assets directory is not treated as a second mod root`() {
        val jar = jar(
            "mod.hjson" to "name: test",
            "sprites/icon.png" to "root",
            "assets/sprites/icon.png" to "assets",
        )

        val snapshot = RuntimeAssetStager.scan(jar)

        assertEquals(listOf("sprites/icon.png"), snapshot.files.map { it.outputPath })
        assertEquals("root", text(snapshot, "sprites/icon.png"))
        assertTrue(snapshot.diagnostics.any { it.code == "NESTED_ASSETS_TREE_NOT_LOADED" })
    }

    @Test
    fun `strips one outer directory like Mindustry resolveRoot`() {
        val jar = jar(
            "Release/mod.hjson" to "name: test",
            "Release/sprites/icon.png" to "sprite",
            "Release/bundles/bundle.properties" to "bundle",
        )

        val snapshot = RuntimeAssetStager.scan(jar)

        assertEquals("Release", snapshot.resolvedRootPrefix)
        assertEquals(listOf("bundles/bundle.properties", "sprites/icon.png"), snapshot.files.map { it.outputPath })
        assertTrue(snapshot.diagnostics.any { it.code == "MOD_ARCHIVE_ROOT_STRIPPED" })
    }

    @Test
    fun `rejects traversal before any extraction`() {
        val jar = jar("../sprites/escape.png" to "escape")

        val error = assertFailsWith<RuntimeAssetStagingException> { RuntimeAssetStager.scan(jar) }

        assertTrue(error.message.orEmpty().contains("traversal"))
        assertTrue(!Files.exists(temporaryDirectory.resolve("escape.png")))
    }

    @Test
    fun `enforces entry count single file and expanded byte limits`() {
        val many = jar(
            "mod.hjson" to "name: test",
            "sprites/a.png" to "aaa",
            "sprites/b.png" to "bbb",
        )
        assertFailsWith<RuntimeAssetStagingException> {
            RuntimeAssetStager.scan(many, relaxedLimits(maxEntries = 1))
        }
        assertFailsWith<RuntimeAssetStagingException> {
            RuntimeAssetStager.scan(many, relaxedLimits(maxEntryBytes = 2))
        }
        assertFailsWith<RuntimeAssetStagingException> {
            RuntimeAssetStager.scan(many, relaxedLimits(maxExpandedBytes = 5))
        }
    }

    @Test
    fun `default entry limit accommodates a release jar larger than the old static limit`() {
        val entries = buildList {
            repeat(3_000) { index -> add("classes/C$index.class" to "x") }
            add("sprites/kept.png" to "sprite")
        }.toTypedArray()
        val jar = jar(*entries)

        val snapshot = RuntimeAssetStager.scan(jar)

        assertEquals(3_001, snapshot.scannedEntryCount)
        assertEquals(listOf("sprites/kept.png"), snapshot.files.map { it.outputPath })
    }

    @Test
    fun `reports unresolved ordinary basename collisions without silently deleting assets`() {
        val jar = jar(
            "sprites/a/shared.png" to "a",
            "sprites/b/shared.png" to "b",
            "sounds/shared.ogg" to "sound",
            "music/shared.mp3" to "music",
        )

        val snapshot = RuntimeAssetStager.scan(jar)

        assertEquals(4, snapshot.files.size)
        assertTrue(snapshot.diagnostics.any { it.code == "UNRESOLVED_RUNTIME_ASSET_BASENAME_COLLISION" })
        assertTrue(snapshot.diagnostics.any { it.code == "UNRESOLVED_RUNTIME_AUDIO_NAMESPACE_COLLISION" })
    }

    @Test
    fun `runtime mapper policy selects later ordinary sprite but preserves generated pair and audio conflicts`() {
        val jar = jar(
            "sprites/unit/ground-unit/annihilation/weapons/large-launcher.png" to "older-bytes",
            "sprites/generated/legal-pair.png" to "generated",
            "sprites/unit/legal-pair.png" to "formal",
            "sounds/a/shared.ogg" to "sound-a",
            "sounds/b/shared.mp3" to "sound-b",
            "sprites/unit/weapons/large-launcher.png" to "later-bytes",
        )

        val snapshot = RuntimeAssetStager.scanForRuntimeMapper(jar)

        assertEquals(
            listOf("sprites/unit/weapons/large-launcher.png"),
            snapshot.files.filter { it.outputPath.endsWith("large-launcher.png") }.map { it.outputPath },
        )
        assertEquals("later-bytes", text(snapshot, "sprites/unit/weapons/large-launcher.png"))
        assertEquals(2, snapshot.files.count { it.outputPath.endsWith("legal-pair.png") })
        assertEquals(2, snapshot.files.count { it.outputPath.substringAfterLast('/').startsWith("shared.") })
        val selection = snapshot.diagnostics.single { it.code == "V159_SPRITE_BASENAME_LAST_WINS" }
        assertEquals(RuntimeAssetDiagnosticSeverity.WARNING, selection.severity)
        assertEquals("sprites/unit/weapons/large-launcher.png", selection.sourceEntryPath)
        assertEquals(
            listOf("sprites/unit/ground-unit/annihilation/weapons/large-launcher.png"),
            selection.relatedSourceEntryPaths,
        )
        assertTrue(selection.details.orEmpty().contains("central-directory ordinal"))
        assertTrue(selection.details.orEmpty().contains("bytesDifferent=true"))
        assertFalse(
            snapshot.diagnostics.any {
                it.code == "UNRESOLVED_RUNTIME_ASSET_BASENAME_COLLISION" &&
                    it.details.orEmpty().contains("large-launcher")
            },
        )
        assertTrue(
            snapshot.diagnostics.any {
                it.code == "UNRESOLVED_RUNTIME_ASSET_BASENAME_COLLISION" &&
                    it.details.orEmpty().contains("shared")
            },
        )
    }

    @Test
    fun `runtime mapper policy reports byte identical ordinary sprite selection as info`() {
        val jar = jar(
            "mod.hjson" to "name: test",
            "sprites/a/same.png" to "identical",
            "sprites/b/same.png" to "identical",
        )

        val snapshot = RuntimeAssetStager.scanForRuntimeMapper(jar)

        assertEquals(listOf("sprites/b/same.png"), snapshot.files.map { it.outputPath })
        val selection = snapshot.diagnostics.single { it.code == "V159_SPRITE_BASENAME_LAST_WINS" }
        assertEquals(RuntimeAssetDiagnosticSeverity.INFO, selection.severity)
        assertTrue(selection.details.orEmpty().contains("bytesDifferent=false"))
    }

    private fun text(snapshot: RuntimeAssetSnapshot, outputPath: String): String =
        snapshot.files.single { it.outputPath == outputPath }.bytes().decodeToString()

    private fun relaxedLimits(
        maxEntries: Int = 100,
        maxEntryBytes: Long = 1_000,
        maxExpandedBytes: Long = 10_000,
    ) = RuntimeAssetLimits(
        maxArchiveBytes = 1_000_000,
        maxEntries = maxEntries,
        maxEntryBytes = maxEntryBytes,
        maxExpandedBytes = maxExpandedBytes,
        maxCompressionRatio = 10_000.0,
        maxPathLength = 1_024,
    )

    private fun jar(vararg entries: Pair<String, String>): Path {
        val jar = Files.createTempFile(temporaryDirectory, "assets-", ".jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { output ->
            entries.forEach { (name, text) ->
                output.putNextEntry(ZipEntry(name))
                output.write(text.encodeToByteArray())
                output.closeEntry()
            }
        }
        return jar
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
