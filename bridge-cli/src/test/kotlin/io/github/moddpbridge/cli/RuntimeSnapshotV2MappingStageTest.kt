package io.github.moddpbridge.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RuntimeSnapshotV2MappingStageTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `block only snapshot remains a valid empty baseline for hybrid mapping`() {
        val snapshot = temporary.resolve("runtime-snapshot.json")
        Files.writeString(
            snapshot,
            """
            {
              "schemaVersion": 2,
              "targetMod": "fixture",
              "gameVersion": {"type":"official","modifier":"release","build":159,"revision":7},
              "loadedMod": {"name":"fixture"},
              "contentCount": 1,
              "contents": [{
                "name": "fixture-wall",
                "contentType": "block",
                "runtimeClass": "fixture.FixtureBlocks${'$'}1",
                "modName": "fixture"
              }]
            }
            """.trimIndent(),
        )
        val modJar = temporary.resolve("fixture.jar")
        ZipOutputStream(Files.newOutputStream(modJar)).use { zip ->
            mapOf(
                "mod.hjson" to "name: fixture\n",
                "fixture/FixtureBlocks\$1.class" to "fixture-bytecode",
            ).forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.encodeToByteArray())
                zip.closeEntry()
            }
        }
        val output = Files.createDirectories(temporary.resolve("out"))

        val result = BridgeLogger(output.resolve("mapping.log")).use { logger ->
            RuntimeSnapshotV2MappingStage.map(
                RuntimeToDpMappingInput(
                    snapshot = RuntimeSnapshotDescriptor(
                        path = snapshot,
                        sha256 = "fixture",
                        schemaVersion = 2,
                        targetMod = "fixture",
                        contentCount = 1,
                        gameType = "official",
                        gameModifier = "release",
                        gameBuild = 159,
                        gameRevision = 7,
                    ),
                    modJar = modJar,
                    sourceIndexReport = null,
                    outputDirectory = output,
                ),
                logger,
            )
        }

        assertEquals(RuntimePipelineStageStatus.PASSED, result.status)
        assertEquals("empty", result.metadata["runtimeDeclarationBaseline"])
        val prepared = assertNotNull(result.preparedConversion)
        assertTrue(prepared.files.none { it.outputPath.startsWith("content/") })
        assertTrue(Files.isRegularFile(assertNotNull(result.mappingReport)))
    }
}
