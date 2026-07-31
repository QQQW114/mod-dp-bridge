package io.github.moddpbridge.converter

import io.github.moddpbridge.model.ContentDisposition
import io.github.moddpbridge.model.ContentKind
import io.github.moddpbridge.model.FileDisposition
import io.github.moddpbridge.model.SourceLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimePreparedConversionTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `packages inert runtime HJSON and selected JAR assets while preserving patches`() {
        val jar = jar(
            "mod.hjson" to "name: runtime-mod\n",
            "content/items/old.hjson" to "color: ff0000\n",
            "patches/vanilla.hjson" to "item: { copper: { hardness: 1 } }\n",
            "sprites/unit-runtime-mod-drone.png" to "selected-sprite",
            "sprites/unselected.png" to "must-not-leak",
            "mod/Registrar.class" to "registrar-bytecode",
            "mod/Helper.class" to "helper-bytecode",
        )
        val staticExporterCalled = AtomicBoolean(false)
        val poisonExporter = object : StaticSourceExporter {
            override fun export(context: StaticExportContext): StaticExportResult {
                staticExporterCalled.set(true)
                error("runtime-prepared conversion must not invoke this exporter")
            }
        }
        val contentPath = "content/items/alloy.hjson"
        val originalSpritePath = "sprites/unit-runtime-mod-drone.png"
        val prepared = RuntimePreparedConversion(
            files = listOf(
                RuntimePreparedFile(
                    outputPath = contentPath,
                    sourcePaths = listOf("mod/Registrar.class"),
                    bytes = "color: ffffff\nicon: unit-runtime-mod-drone\n".encodeToByteArray(),
                ),
                RuntimePreparedFile(
                    outputPath = originalSpritePath,
                    sourcePaths = listOf(originalSpritePath),
                    bytes = null,
                ),
            ),
            fileResults = listOf(
                RuntimePreparedFileResult(
                    sourcePath = "mod/Helper.class",
                    status = ConvertedFileStatus.EXCLUDED,
                    reason = "Runtime helper class registered no direct gameplay content.",
                ),
            ),
            contentResults = listOf(
                RuntimePreparedContentResult(
                    sourceSymbol = "runtime-mod-alloy",
                    kind = ContentKind.ITEM,
                    disposition = ContentDisposition.CONVERTED,
                    sourceType = "mindustry.type.Item",
                    targetType = "Item",
                    outputName = "dp-alloy",
                    outputPath = contentPath,
                    location = SourceLocation("mod/Registrar.class"),
                ),
            ),
            logs = listOf("Mapped one runtime Item."),
            metadata = mapOf("runtime.snapshot.schema" to "fixture-v2"),
        )

        val first = BridgeConverter.convertRuntimePrepared(
            ConversionRequest(
                input = jar,
                outputDirectory = temp.resolve("first"),
                staticSourceExporters = listOf(poisonExporter),
            ),
            prepared,
        )
        val second = BridgeConverter.convertRuntimePrepared(
            ConversionRequest(input = jar, outputDirectory = temp.resolve("second")),
            prepared,
        )

        assertFalse(staticExporterCalled.get())
        assertTrue(Files.exists(first.serverAssets.resolve(contentPath)))
        assertFalse(Files.exists(first.serverAssets.resolve("content/items/old.hjson")))
        assertTrue(Files.exists(first.serverAssets.resolve("patches/vanilla.hjson")))
        assertFalse(Files.exists(first.serverAssets.resolve("sprites/unselected.png")))
        val rewrittenSprite = "sprites/generated/unit-dp-drone.png"
        assertContentEquals(
            "selected-sprite".encodeToByteArray(),
            first.serverAssets.resolve(rewrittenSprite).readBytes(),
        )
        assertTrue(Files.readString(first.serverAssets.resolve(contentPath)).contains("unit-dp-drone"))
        assertFalse(first.diagnostics.any { it.code == "MOD_CODE_NOT_EXECUTED" })
        assertEquals(
            FileDisposition.EXCLUDED,
            first.report.fileResults.single { it.sourcePath == "mod/Helper.class" }.disposition,
        )
        assertEquals(
            FileDisposition.CONVERTED,
            first.report.fileResults.single { it.sourcePath == "mod/Registrar.class" }.disposition,
        )
        assertEquals(
            FileDisposition.EXCLUDED,
            first.report.fileResults.single { it.sourcePath == "content/items/old.hjson" }.disposition,
        )
        assertEquals(
            FileDisposition.CONVERTED,
            first.report.fileResults.single { it.sourcePath == "patches/vanilla.hjson" }.disposition,
        )
        assertEquals("true", first.report.metadata["runtimePrepared"])
        assertEquals("fixture-v2", first.report.metadata["runtime.snapshot.schema"])
        assertTrue(first.logs.any { it.contains("Mapped one runtime Item") })
        assertContentEquals(first.dpZip.readBytes(), second.dpZip.readBytes())
    }

    @Test
    fun `can retain hybrid declarative content when replacement is disabled`() {
        val jar = jar(
            "Release/mod.hjson" to "name: hybrid-mod\n",
            "Release/content/items/declarative.hjson" to "color: aaaaaa\n",
            "Release/mod/Registrar.class" to "registrar-bytecode",
        )
        val prepared = RuntimePreparedConversion(
            files = listOf(
                RuntimePreparedFile(
                    outputPath = "content/items/runtime.hjson",
                    sourcePaths = listOf("Release/mod/Registrar.class"),
                    bytes = "color: bbbbbb\n".encodeToByteArray(),
                ),
            ),
            contentResults = listOf(
                RuntimePreparedContentResult(
                    sourceSymbol = "hybrid-mod-runtime",
                    kind = ContentKind.ITEM,
                    disposition = ContentDisposition.CONVERTED,
                    outputName = "dp-runtime",
                    outputPath = "content/items/runtime.hjson",
                    location = SourceLocation("Release/mod/Registrar.class"),
                ),
            ),
            replaceOriginalContent = false,
        )

        val result = BridgeConverter.convertRuntimePrepared(
            ConversionRequest(input = jar, outputDirectory = temp.resolve("hybrid")),
            prepared,
        )

        assertTrue(Files.exists(result.serverAssets.resolve("content/items/declarative.hjson")))
        assertTrue(Files.exists(result.serverAssets.resolve("content/items/runtime.hjson")))
        assertFalse(result.diagnostics.any { it.code == "MOD_CODE_NOT_EXECUTED" })
    }

    private fun jar(vararg entries: Pair<String, String>): Path {
        val output = Files.createTempFile(temp, "runtime-prepared-", ".jar")
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            entries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return output
    }
}
