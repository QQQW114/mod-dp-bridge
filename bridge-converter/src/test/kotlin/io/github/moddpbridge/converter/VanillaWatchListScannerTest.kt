package io.github.moddpbridge.converter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VanillaWatchListScannerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun scansDirectoryReferencesAndSkipsWildcardImports() {
        write(
            "SFire/content/SFOverride.java",
            """
            package SFire.content;

            import mindustry.world.blocks.defense.*;
            import mindustry.content.Blocks;
            import static mindustry.content.UnitTypes.*;

            public class SFOverride {
                public static void load() {
                    Blocks.salvo.health = 900;
                    Blocks.salvo.range = 10f;
                    ((Blocks) Blocks.scatter).ammoTypes.put(Items.copper, null);
                    UnitTypes.dagger.speed = 1.5f;
                    StatusEffects.burning.damage = 2f;
                    Liquids.water.viscosity = 0.5f;
                    // Blocks.commentedOut must not be captured? it is text, so it is captured.
                }
            }
            """.trimIndent(),
        )
        write("SFire/Other.java", "package SFire; class Other {}")

        val watch = VanillaWatchListScanner.scan(directory)

        assertEquals(2, watch.scannedJavaFiles)
        assertEquals(listOf("copper"), watch.names["item"])
        assertEquals(listOf("water"), watch.names["liquid"])
        assertEquals(listOf("burning"), watch.names["status"])
        assertEquals(listOf("dagger"), watch.names["unit"])
        // Sorted, de-duplicated, and free of package segments from the wildcard import.
        assertEquals(listOf("commentedOut", "salvo", "scatter"), watch.names["block"])
        assertTrue(watch.truncatedTypes.isEmpty())
        assertTrue(watch.diagnostics.isEmpty(), watch.diagnostics.toString())
    }

    @Test
    fun rendersCanonicalSpecInFixedTypeOrder() {
        write(
            "SFire/SFOverride.java",
            """
            class SFOverride {
                void load() {
                    Blocks.tsunami.health = 1;
                    Blocks.ripple.health = 1;
                    Items.copper.hardness = 1;
                }
            }
            """.trimIndent(),
        )

        val watch = VanillaWatchListScanner.scan(directory)

        assertEquals("item:copper;block:ripple,tsunami", watch.spec)
        assertEquals(3, watch.totalNames)
        assertFalse(watch.isEmpty)
    }

    @Test
    fun scansZipArchives() {
        val archive = directory.resolve("source.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { output ->
            output.putNextEntry(ZipEntry("src/SFire/SFOverride.java"))
            output.write("class A { void f(){ Blocks.foreshadow.health = 1; } }".toByteArray(StandardCharsets.UTF_8))
            output.closeEntry()
            output.putNextEntry(ZipEntry("src/assets/icon.png"))
            output.write(ByteArray(16))
            output.closeEntry()
        }

        val watch = VanillaWatchListScanner.scan(archive)

        assertEquals(listOf("foreshadow"), watch.names["block"])
        assertEquals(1, watch.scannedJavaFiles)
    }

    @Test
    fun reportsEmptySourceInsteadOfSilentlyProducingNothing() {
        write("README.md", "no java here")

        val watch = VanillaWatchListScanner.scan(directory)

        assertTrue(watch.isEmpty)
        assertTrue(watch.diagnostics.any { it.code == "VANILLA_WATCH_LIST_EMPTY_SOURCE" }, watch.diagnostics.toString())
    }

    @Test
    fun reportsNoReferencesWhenOnlyUnrelatedJavaExists() {
        write("src/Helper.java", "class Helper { void f(){ int blocks = 1; } }")

        val watch = VanillaWatchListScanner.scan(directory)

        assertTrue(watch.isEmpty)
        assertTrue(
            watch.diagnostics.any { it.code == "VANILLA_WATCH_LIST_NO_REFERENCES" },
            watch.diagnostics.toString(),
        )
    }

    @Test
    fun truncatesPerTypeListsAndRecordsTheReason() {
        val references = buildString {
            append("class A { void f(){ ")
            repeat(VanillaWatchListScanner.MAX_NAMES_PER_TYPE + 20) { index ->
                append("Blocks.block$index.health = 1; ")
            }
            append("} }")
        }
        write("src/A.java", references)

        val watch = VanillaWatchListScanner.scan(directory)

        assertEquals(VanillaWatchListScanner.MAX_NAMES_PER_TYPE, watch.names.getValue("block").size)
        assertEquals(listOf("block"), watch.truncatedTypes)
        assertTrue(
            watch.diagnostics.any { it.code == "VANILLA_WATCH_LIST_TRUNCATED" },
            watch.diagnostics.toString(),
        )
    }

    private fun write(relative: String, text: String) {
        val target = directory.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, text, StandardCharsets.UTF_8)
    }
}
