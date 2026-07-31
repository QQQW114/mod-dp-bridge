package io.github.moddpbridge.javastatic.hybrid

import io.github.moddpbridge.converter.ConvertedFileStatus
import io.github.moddpbridge.converter.RuntimePreparedContentResult
import io.github.moddpbridge.converter.RuntimePreparedConversion
import io.github.moddpbridge.converter.RuntimePreparedFileResult
import io.github.moddpbridge.model.ContentDisposition
import io.github.moddpbridge.model.ContentKind
import io.github.moddpbridge.model.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeStaticHybridInputsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `path entry reads source without building it and binds top-level archive prefix provenance`() {
        val source = temp.resolve("source")
        val java = source.resolve("src/example/Contents.java")
        Files.createDirectories(java.parent)
        Files.writeString(
            java,
            """
            public class Contents {
                static Block testWall;
                static void load(){
                    testWall = new Wall("test-wall") {{ health = 100; }};
                }
            }
            """.trimIndent(),
        )
        val snapshot = temp.resolve("runtime-snapshot.json")
        Files.writeString(snapshot, snapshotJson())
        val sourceIndex = temp.resolve("source-index-report.json")
        Files.writeString(sourceIndex, sourceIndexJson())
        val runtimeLocation = SourceLocation(snapshot.toString(), "$.contents[0]")
        val runtime = RuntimePreparedConversion(
            files = emptyList(),
            fileResults = listOf(
                RuntimePreparedFileResult("example/Contents.class", ConvertedFileStatus.UNSUPPORTED, "runtime class"),
            ),
            contentResults = listOf(
                RuntimePreparedContentResult(
                    sourceSymbol = "fixture-test-wall",
                    kind = ContentKind.BLOCK,
                    disposition = ContentDisposition.UNSUPPORTED,
                    sourceType = "block",
                    diagnosticCodes = listOf("RUNTIME_CONTENT_TYPE_NOT_MAPPED"),
                    location = runtimeLocation,
                ),
            ),
        )

        val candidates = RuntimeStaticHybrid.prepare(
            RuntimeStaticHybridRequest(
                snapshot = snapshot,
                source = source,
                sourceIndexReport = sourceIndex,
                runtimePrepared = runtime,
            ),
        )

        assertEquals(setOf("content/blocks/test-wall.hjson"), candidates.candidatePaths)
        assertEquals(listOf("example/Contents.class"), candidates.candidates.single().jarProvenancePaths)
        assertTrue(candidates.logs.any { "without executing or building" in it })
    }

    @Test
    fun `generated output budget fails closed before candidate materialization`() {
        val fixture = fixtureInputs()
        val error = assertFailsWith<IllegalArgumentException> {
            RuntimeStaticHybrid.prepare(
                RuntimeStaticHybridRequest(
                    snapshot = fixture.snapshot,
                    source = fixture.source,
                    sourceIndexReport = fixture.sourceIndex,
                    runtimePrepared = fixture.runtime,
                    limits = HybridSourceLimits(maxGeneratedBytes = 1),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("total-byte limit"))
    }

    @Test
    fun `source file count budget fails closed`() {
        val fixture = fixtureInputs()
        Files.writeString(fixture.source.resolve("src/example/Extra.java"), "class Extra {}")
        assertFailsWith<IllegalArgumentException> {
            RuntimeStaticHybrid.prepare(
                RuntimeStaticHybridRequest(
                    snapshot = fixture.snapshot,
                    source = fixture.source,
                    sourceIndexReport = fixture.sourceIndex,
                    runtimePrepared = fixture.runtime,
                    limits = HybridSourceLimits(maxSourceFiles = 1),
                ),
            )
        }
    }

    @Test
    fun `source archive paths reject control characters`() {
        val fixture = fixtureInputs()
        val archive = temp.resolve("unsafe.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("src/\u0001Bad.java"))
            zip.write("class Bad {}".toByteArray())
            zip.closeEntry()
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeStaticHybrid.prepare(
                RuntimeStaticHybridRequest(
                    snapshot = fixture.snapshot,
                    source = archive,
                    sourceIndexReport = fixture.sourceIndex,
                    runtimePrepared = fixture.runtime,
                ),
            )
        }
    }

    @Test
    fun `final-only late runtime registration remains eligible for source matching`() {
        val fixture = fixtureInputs(setOf("finalAfterModInit"))

        val candidates = RuntimeStaticHybrid.prepare(
            RuntimeStaticHybridRequest(
                snapshot = fixture.snapshot,
                source = fixture.source,
                sourceIndexReport = fixture.sourceIndex,
                runtimePrepared = fixture.runtime,
            ),
        )

        assertEquals(setOf("content/blocks/test-wall.hjson"), candidates.candidatePaths)
    }

    private fun fixtureInputs(
        availablePhases: Set<String> = setOf("preContentInit", "postContentInit", "finalAfterModInit"),
    ): FixtureInputs {
        val source = temp.resolve("fixture-${System.nanoTime()}")
        val java = source.resolve("src/example/Contents.java")
        Files.createDirectories(java.parent)
        Files.writeString(
            java,
            """
            public class Contents {
                static Block testWall;
                static void load(){
                    testWall = new Wall("test-wall") {{ health = 100; }};
                }
            }
            """.trimIndent(),
        )
        val snapshot = temp.resolve("snapshot-${System.nanoTime()}.json")
        Files.writeString(snapshot, snapshotJson(availablePhases))
        val sourceIndex = temp.resolve("index-${System.nanoTime()}.json")
        Files.writeString(sourceIndex, sourceIndexJson())
        val runtime = RuntimePreparedConversion(
            files = emptyList(),
            fileResults = listOf(
                RuntimePreparedFileResult("example/Contents.class", ConvertedFileStatus.UNSUPPORTED, "runtime class"),
            ),
            contentResults = listOf(
                RuntimePreparedContentResult(
                    sourceSymbol = "fixture-test-wall",
                    kind = ContentKind.BLOCK,
                    disposition = ContentDisposition.UNSUPPORTED,
                    sourceType = "block",
                    diagnosticCodes = listOf("RUNTIME_CONTENT_TYPE_NOT_MAPPED"),
                    location = SourceLocation(snapshot.toString(), "$.contents[0]"),
                ),
            ),
        )
        return FixtureInputs(source, snapshot, sourceIndex, runtime)
    }

    private data class FixtureInputs(
        val source: Path,
        val snapshot: Path,
        val sourceIndex: Path,
        val runtime: RuntimePreparedConversion,
    )

    private fun snapshotJson(
        availablePhases: Set<String> = setOf("preContentInit", "postContentInit", "finalAfterModInit"),
    ): String {
        val phases = listOf("preContentInit", "postContentInit", "finalAfterModInit")
            .filter(availablePhases::contains)
            .joinToString(",\n") { phase ->
                "              \"$phase\": {\"sourceClassMapFallback\":{\"parserName\":\"Wall\",\"loadableRoot\":true}}"
            }
        return """
        {
          "schemaVersion": 2,
          "targetMod": "fixture",
          "gameVersion": {"type":"official","modifier":"release","build":159,"revision":7},
          "contents": [{
            "name":"fixture-test-wall",
            "contentType":"block",
            "modName":"fixture",
            "runtimeSnapshots": {
$phases
            }
          }]
        }
        """.trimIndent()
    }

    private fun sourceIndexJson(): String = """
        {
          "schemaVersion": 1,
          "classes": [{
            "jarPath":"example/Contents.class",
            "match":"source_file",
            "sourcePath":"FixtureRepo/src/example/Contents.java",
            "lineNumbers":[1,2,3,4,5,6],
            "sourceCoversRuntimeLines":true
          }]
        }
    """.trimIndent()
}
