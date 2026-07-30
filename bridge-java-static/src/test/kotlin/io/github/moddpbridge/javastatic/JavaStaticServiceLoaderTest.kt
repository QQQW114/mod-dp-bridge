package io.github.moddpbridge.javastatic

import io.github.moddpbridge.converter.BridgeConverter
import io.github.moddpbridge.converter.ConversionRequest
import io.github.moddpbridge.converter.StaticSourceExporters
import io.github.moddpbridge.model.ContentDisposition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaStaticServiceLoaderTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `service provider is packaged and participates in the converter pipeline`() {
        val discovered = StaticSourceExporters.discover(JavaAstStaticExporter::class.java.classLoader)
        assertTrue(discovered.any { it.id == "java-ast-v1597" })

        val input = temp.resolve("service-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: service-mod\njava: true\n")
        input.resolve("src").createDirectories()
        input.resolve("src/Content.java").writeText(
            """
            class Content {
                static Item alloy = new Item("alloy") {{
                    color = Color.valueOf("abcdef");
                    cost = 2f;
                }};
            }
            """.trimIndent(),
        )

        // Null uses the same ServiceLoader path as the installed CLI distribution.
        val result = BridgeConverter.convert(
            ConversionRequest(
                input = input,
                outputDirectory = temp.resolve("out"),
                staticSourceExporters = null,
            ),
        )

        assertTrue(Files.exists(result.serverAssets.resolve("content/items/alloy.hjson")))
        assertEquals(1, result.report.summary.convertedContents)
        assertEquals(ContentDisposition.CONVERTED, result.report.contentResults.single().disposition)
        assertTrue(result.diagnostics.any { it.code == "JAVA_STATIC_EXPORT_APPLIED" })
        assertFalse(result.diagnostics.any { it.code == "STATIC_EXPORTER_DISCOVERY_FAILED" })
        assertFalse(result.diagnostics.any { it.code == "MOD_CODE_NOT_EXECUTED" })
    }
}
