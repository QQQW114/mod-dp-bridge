package io.github.moddpbridge.cli

import io.github.moddpbridge.converter.ConvertedFileStatus
import io.github.moddpbridge.converter.RuntimePreparedContentResult
import io.github.moddpbridge.converter.RuntimePreparedConversion
import io.github.moddpbridge.converter.RuntimePreparedFileResult
import io.github.moddpbridge.model.ContentDisposition
import io.github.moddpbridge.model.ContentKind
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.target.v1597.ContentApplyValidationResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RuntimeHybridSourceSelectionTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `runtime source selector packages and accepts a fallback matched block candidate`() {
        val modJar = fixtureJar()
        val source = Files.createDirectories(temporary.resolve("source/src/fixture"))
            .resolve("FixtureBlocks.java")
        Files.writeString(
            source,
            """
            package fixture;
            import mindustry.world.Block;
            import mindustry.world.blocks.defense.Wall;

            public class FixtureBlocks {
                public static Block wall = new Wall("wall") {{ health = 100; }};
            }
            """.trimIndent(),
        )
        val snapshot = fixtureSnapshot()
        val sourceIndex = fixtureSourceIndex()
        val output = temporary.resolve("out")
        val selections = mutableListOf<Boolean>()
        val apply = RuntimeDataPatchApplyRunner { assets, _, _, logSink ->
            val hasBlock = Files.isRegularFile(assets.resolve("content/blocks/wall.hjson"))
            selections += hasBlock
            logSink("fixture hybrid apply block=$hasBlock")
            cleanApply(if (hasBlock) 1 else 0)
        }

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeHybridSourceSelector(apply).run(
                RuntimeHybridSourceSelectionInput(
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
                    source = temporary.resolve("source"),
                    sourceIndexReport = sourceIndex,
                    serverJar = Files.writeString(temporary.resolve("server.jar"), "fixture"),
                    outputDirectory = output,
                    runtimePrepared = runtimePrepared(),
                    validationTimeout = Duration.ofSeconds(5),
                    maxRounds = 4,
                ),
                logger,
            )
        }

        assertEquals(RuntimePipelineStageStatus.PASSED, result.status)
        assertEquals(listOf(false, true), selections)
        assertTrue(result.preparedConversion.files.any { it.outputPath == "content/blocks/wall.hjson" })
        val wall = result.preparedConversion.contentResults.single { it.sourceSymbol == "fixture-wall" }
        assertEquals(ContentDisposition.DEGRADED, wall.disposition)
        assertTrue("HYBRID_DATA_PATCHER_CLEAN" in wall.diagnosticCodes)
        assertTrue(Files.isRegularFile(result.report))
    }

    private fun fixtureJar(): Path {
        val jar = temporary.resolve("fixture.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            mapOf(
                "mod.hjson" to "name: fixture\n",
                "fixture/FixtureBlocks.class" to "fixture-bytecode",
            ).forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return jar
    }

    private fun fixtureSnapshot(): Path {
        val file = temporary.resolve("runtime-snapshot.json")
        val phase = { name: String ->
            """
            "$name": {
              "schemaVersion": 1,
              "phase": "${when (name) {
                "preContentInit" -> "PRE_CONTENT_INIT"
                "postContentInit" -> "POST_CONTENT_INIT"
                else -> "FINAL_AFTER_MOD_INIT"
            }}",
              "name": "fixture-wall",
              "contentType": "block",
              "runtimeClass": "fixture.FixtureBlocks${'$'}1",
              "sourceClassMapFallback": {
                "parserName": "Wall",
                "runtimeClass": "mindustry.world.blocks.defense.Wall",
                "loadableRoot": true
              },
              "fields": {},
              "customFields": [],
              "overriddenMethods": [],
              "customOnlyMethods": [],
              "declaredLosses": []
            }
            """.trimIndent()
        }
        Files.writeString(
            file,
            """
            {
              "schemaVersion": 2,
              "targetMod": "fixture",
              "gameVersion": {"type":"official","modifier":"release","build":159,"revision":7},
              "contentCount": 1,
              "contents": [{
                "name": "fixture-wall",
                "contentType": "block",
                "runtimeClass": "fixture.FixtureBlocks${'$'}1",
                "modName": "fixture",
                "runtimeSnapshots": {
                  ${phase("preContentInit")},
                  ${phase("postContentInit")},
                  ${phase("finalAfterModInit")}
                }
              }]
            }
            """.trimIndent(),
        )
        return file
    }

    private fun fixtureSourceIndex(): Path {
        val file = temporary.resolve("source-index-report.json")
        Files.writeString(
            file,
            """
            {
              "schemaVersion": 1,
              "classes": [{
                "jarPath": "fixture/FixtureBlocks.class",
                "match": "source_file",
                "sourcePath": "src/fixture/FixtureBlocks.java",
                "sourceCoversRuntimeLines": true,
                "lineNumbers": [6]
              }]
            }
            """.trimIndent(),
        )
        return file
    }

    private fun runtimePrepared(): RuntimePreparedConversion = RuntimePreparedConversion(
        files = emptyList(),
        fileResults = listOf(
            RuntimePreparedFileResult(
                sourcePath = "fixture/FixtureBlocks.class",
                status = ConvertedFileStatus.EXCLUDED,
                reason = "Runtime executable had no direct output before hybrid matching.",
                diagnosticCodes = listOf("RUNTIME_EXECUTABLE_NO_DIRECT_OUTPUT"),
            ),
        ),
        contentResults = listOf(
            RuntimePreparedContentResult(
                sourceSymbol = "fixture-wall",
                kind = ContentKind.BLOCK,
                disposition = ContentDisposition.UNSUPPORTED,
                sourceType = "fixture.FixtureBlocks${'$'}1",
                targetType = "Wall",
                reason = "Runtime Block mapping is not implemented.",
                diagnosticCodes = listOf("RUNTIME_CONTENT_TYPE_NOT_MAPPED"),
                location = SourceLocation("runtime-snapshot.json", "$.contents[0]"),
            ),
        ),
    )

    private fun cleanApply(contentAssets: Int): ContentApplyValidationResult = ContentApplyValidationResult(
        applyCompleted = true,
        passed = true,
        exitCode = 0,
        timedOut = false,
        totalAssets = contentAssets,
        contentAssets = contentAssets,
        patchAssets = 0,
        externalAssets = 0,
        failedAssets = 0,
        warningCount = 0,
        addedContent = contentAssets,
        outputLines = listOf("DPBRIDGE_RESULT fixture"),
        diagnostics = emptyList(),
    )
}
