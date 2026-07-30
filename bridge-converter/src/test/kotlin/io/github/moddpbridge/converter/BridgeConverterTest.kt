package io.github.moddpbridge.converter

import io.github.moddpbridge.model.ConversionStatus
import io.github.moddpbridge.model.ConversionReportJson
import io.github.moddpbridge.model.ContentDisposition
import io.github.moddpbridge.model.ContentKind
import io.github.moddpbridge.model.ContentResult
import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.FileDisposition
import io.github.moddpbridge.model.SourceKind
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import io.github.moddpbridge.model.ValidationStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeConverterTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `routes B480 environment sprites and adds content regeneration sentinels`() {
        val input = temp.resolve("environment-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: environment-mod\n")
        input.resolve("assets/content/blocks").createDirectories()
        input.resolve("assets/content/blocks/floor.hjson").writeText("type: Floor\nvariants: 4\n")
        input.resolve("assets/content/blocks/rock.hjson").writeText("type: Prop\nvariants: 1\n")
        input.resolve("assets/content/blocks/wall.hjson").writeText("type: StaticWall\nvariants: 4\n")
        input.resolve("assets/content/blocks/turret.hjson").writeText("type: ItemTurret\n")
        input.resolve("assets/content/blocks/autofloor.hjson").writeText("type: Floor\nautotile: true\n")
        input.resolve("assets/content/blocks/ore-chromium.hjson").writeText(
            "type: OreBlock\nitemDrop: environment-mod-chromium\n",
        )
        input.resolve("assets/content/items").createDirectories()
            .resolve("chromium.hjson").writeText("color: ffffff\n")
        input.resolve("assets/content/weather").createDirectories()
            .resolve("storm.hjson").writeText("duration: 60\n")

        val env = input.resolve("assets/sprites/blocks/environment").createDirectories()
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4////fwAJ+wP9KobjigAAAABJRU5ErkJggg==",
        )
        env.resolve("floor1.png").writeBytes(png)
        env.resolve("floor2.png").writeBytes(png)
        env.resolve("floor3.png").writeBytes(png)
        env.resolve("floor4.png").writeBytes(png)
        env.resolve("rock1.png").writeBytes(png)
        env.resolve("wall1.png").writeBytes(png)
        env.resolve("wall2.png").writeBytes(png)
        env.resolve("wall3.png").writeBytes(png)
        env.resolve("wall4.png").writeBytes(png)
        env.resolve("wall-large.png").writeBytes(png)
        env.resolve("turret.png").writeBytes(png)
        env.resolve("mystery.png").writeBytes(png)
        env.resolve("autofloor.png").writeBytes(png)
        env.resolve("autofloor-autotile.png").writeBytes(png)
        repeat(47) { bitmask -> env.resolve("autofloor-$bitmask.png").writeBytes(png) }
        val ores = env.resolve("ores").createDirectories()
        ores.resolve("chromium1.png").writeBytes(png)
        ores.resolve("chromium2.png").writeBytes(png)
        ores.resolve("chromium3.png").writeBytes(png)

        val result = BridgeConverter.convert(
            ConversionRequest(input, temp.resolve("environment-out"), staticSourceExporters = emptyList()),
        )

        assertTrue(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/floor1.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/floor2.png")))
        assertFalse(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/floor3.png")))
        assertFalse(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/floor4.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/wall1.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/wall2.png")))
        assertFalse(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/wall3.png")))
        assertFalse(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/wall4.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/ores/ore-chromium1.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/ores/ore-chromium2.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/dpbridge-main/environment/ores/chromium1.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/dpbridge-main/environment/ores/chromium2.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/dpbridge-main/environment/ores/chromium3.png")))
        assertFalse(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/wall-large.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/dpbridge-main/environment/rock1.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/dpbridge-main/environment/turret.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/dpbridge-main/environment/mystery.png")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/dpbridge-main/environment/autofloor.png")))
        assertFalse(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/autofloor-autotile.png")))
        repeat(47) { bitmask ->
            assertTrue(Files.exists(result.serverAssets.resolve("sprites/blocks/environment/autofloor-$bitmask.png")))
        }
        assertEquals(
            FileDisposition.EXCLUDED,
            result.report.fileResults.single {
                it.sourcePath == "assets/sprites/blocks/environment/wall-large.png"
            }.disposition,
        )
        assertEquals(
            FileDisposition.CONVERTED,
            result.report.fileResults.single {
                it.sourcePath == "assets/sprites/blocks/environment/rock1.png"
            }.disposition,
        )
        assertTrue(result.diagnostics.any { it.code == "B480_ENVIRONMENT_SPRITES_REROUTED" })
        assertTrue(result.diagnostics.any { it.code == "B480_OPTIONAL_STATIC_WALL_LARGE_OMITTED" })
        assertTrue(result.diagnostics.any { it.code == "B480_ENVIRONMENT_TOOL_SPRITES_OMITTED" })
        assertTrue(result.diagnostics.any { it.code == "B480_ENVIRONMENT_VARIANTS_REDUCED" })
        assertTrue(result.diagnostics.any { it.code == "B480_ORE_RUNTIME_ALIASES_ADDED" })
        assertTrue(result.diagnostics.any { it.code == "B480_CONTENT_REGENERATION_SENTINELS_ADDED" })
        assertEquals("53", result.report.metadata["b480EnvironmentReservedSprites"])
        assertEquals("2", result.report.metadata["b480EnvironmentOptionalSpritesOmitted"])
        assertEquals("4", result.report.metadata["b480EnvironmentReducedVariantSprites"])
        assertEquals("3", result.report.metadata["b480EnvironmentRewrittenVariantContents"])
        assertEquals("2", result.report.metadata["b480EnvironmentOreAliases"])
        assertEquals("8", result.report.metadata["b480ContentRegenerationSentinels"])

        assertEquals(
            2,
            HjsonNormalizer.parse(
                Files.readAllBytes(result.serverAssets.resolve("content/blocks/floor.hjson")),
                "floor.hjson",
            ).asObject().getInt("variants", -1),
        )
        assertEquals(
            2,
            HjsonNormalizer.parse(
                Files.readAllBytes(result.serverAssets.resolve("content/blocks/wall.hjson")),
                "wall.hjson",
            ).asObject().getInt("variants", -1),
        )
        assertEquals(
            2,
            HjsonNormalizer.parse(
                Files.readAllBytes(result.serverAssets.resolve("content/blocks/ore-chromium.hjson")),
                "ore-chromium.hjson",
            ).asObject().getInt("variants", -1),
        )

        listOf(
            ContentKind.BLOCK to "blocks/floor",
            ContentKind.BLOCK to "blocks/rock",
            ContentKind.BLOCK to "blocks/wall",
            ContentKind.BLOCK to "blocks/turret",
            ContentKind.BLOCK to "blocks/autofloor",
            ContentKind.BLOCK to "blocks/ore-chromium",
            ContentKind.ITEM to "items/chromium",
            ContentKind.WEATHER to "weather/storm",
        ).forEach { (kind, relative) ->
            val bytes = Files.readAllBytes(result.serverAssets.resolve("content/$relative.hjson"))
            val hash = "${kind.name.lowercase()}_${encodeMindustryHash(MessageDigest.getInstance("SHA-256").digest(bytes))}"
            val markerDirectory = result.serverAssets.resolve("sprites/generated/$hash")
            assertTrue(Files.isDirectory(markerDirectory), "missing sentinel directory for $relative")
            Files.list(markerDirectory).use { markers ->
                assertTrue(
                    markers.anyMatch { it.fileName.toString().startsWith("bridge-sentinel-") },
                    "missing sentinel PNG for $relative",
                )
            }
        }
    }

    @Test
    fun `does not treat archived asset snippets or Gradle wrapper as mod runtime code`() {
        val input = temp.resolve("source-project").createDirectories()
        input.resolve("mod.hjson").writeText("name: source-project\n")
        input.resolve("assets/content/items").createDirectories()
        input.resolve("assets/content/items/alloy.hjson").writeText("color: ffffff")
        input.resolve("assets/archive").createDirectories()
        input.resolve("assets/archive/Old.java").writeText("this is archived text, not compiled source")
        input.resolve("assets/archive/old.js").writeText("not under the executable scripts root")
        input.resolve("gradle/wrapper").createDirectories()
        input.resolve("gradle/wrapper/gradle-wrapper.jar").writeBytes(byteArrayOf(1, 2, 3))

        val result = BridgeConverter.convert(
            ConversionRequest(input, temp.resolve("source-project-out"), staticSourceExporters = emptyList()),
        )

        assertFalse(result.diagnostics.any { it.code == "MOD_CODE_NOT_EXECUTED" })
        assertEquals(
            FileDisposition.UNSUPPORTED,
            result.report.fileResults.single { it.sourcePath == "assets/archive/Old.java" }.disposition,
        )
        assertEquals(
            FileDisposition.UNSUPPORTED,
            result.report.fileResults.single { it.sourcePath == "gradle/wrapper/gradle-wrapper.jar" }.disposition,
        )
    }

    @Test
    fun `normalizes uppercase mirrored sprite side suffixes for B480 lookup`() {
        val input = temp.resolve("mirror-side-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: mirror-side-mod\n")
        input.resolve("assets/content/blocks").createDirectories()
        input.resolve("assets/content/blocks/turret.hjson").writeText(
            """
            type: ItemTurret
            drawer: {
              type: DrawTurret
              parts: [
                {
                  type: RegionPart
                  name: mirror-side-mod-turret-barrel-R
                  mirror: false
                }
              ]
            }
            """.trimIndent(),
        )
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4////fwAJ+wP9KobjigAAAABJRU5ErkJggg==",
        )
        input.resolve("assets/sprites/blocks/turrets").createDirectories()
            .resolve("turret-barrel-R.png").writeBytes(png)

        val result = BridgeConverter.convert(
            ConversionRequest(input, temp.resolve("mirror-side-out"), staticSourceExporters = emptyList()),
        )

        val emittedSpriteNames = Files.list(result.serverAssets.resolve("sprites/blocks/turrets")).use { files ->
            files.map { it.fileName.toString() }.toList()
        }
        assertFalse("turret-barrel-R.png" in emittedSpriteNames)
        assertTrue("turret-barrel-r.png" in emittedSpriteNames)
        val content = Files.readString(result.serverAssets.resolve("content/blocks/turret.hjson"))
        assertTrue(content.contains("dp-turret-barrel-r"))
        assertTrue(
            result.diagnostics.any { it.code == "MIRRORED_SPRITE_SIDE_CASE_NORMALIZED" },
            result.diagnostics.joinToString { it.code },
        )
    }

    @Test
    fun `static exporter injects generated content and replaces Java file outcome`() {
        val input = temp.resolve("java-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: java-mod\n")
        input.resolve("src").createDirectories()
        input.resolve("src/JavaContent.java").writeText("class JavaContent {}")
        input.resolve("assets/sprites").createDirectories()
        input.resolve("assets/sprites/alloy.png").writeBytes(byteArrayOf(1))
        input.resolve("assets/sprites/wall.png").writeBytes(byteArrayOf(2))

        val exporter = object : StaticSourceExporter {
            override val id: String = "fixture-java-exporter"

            override fun export(context: StaticExportContext): StaticExportResult {
                assertEquals("java-mod", context.modNamespace)
                val source = "src/JavaContent.java"
                val outputs = listOf(
                    "content/items/alloy.hjson" to "color: ffffff\n",
                    "content/blocks/wall.hjson" to
                        "type: Wall\nhealth: 100\nrequirements: [\"java-mod-alloy/1\"]\n",
                )
                return StaticExportResult(
                    generatedFiles = outputs.map { (path, value) ->
                        StaticGeneratedFile(path, value.toByteArray(), listOf(source))
                    },
                    sourceOutcomes = listOf(
                        StaticSourceOutcome(
                            sourcePath = source,
                            status = ConvertedFileStatus.NORMALIZED,
                            reason = "Two Java declarations were statically exported.",
                            outputPaths = outputs.map { it.first },
                            diagnosticCodes = listOf("JAVA_STATIC_EXPORT_APPLIED"),
                        ),
                    ),
                    contentResults = listOf(
                        ContentResult(
                            sourceSymbol = "alloy",
                            kind = ContentKind.ITEM,
                            disposition = ContentDisposition.CONVERTED,
                            sourceType = "Item",
                            targetType = "Item",
                            outputName = "dp-alloy",
                            outputPath = outputs[0].first,
                            location = SourceLocation(source, line = 1),
                        ),
                        ContentResult(
                            sourceSymbol = "wall",
                            kind = ContentKind.BLOCK,
                            disposition = ContentDisposition.DEGRADED,
                            sourceType = "CustomWall",
                            targetType = "Wall",
                            outputName = "dp-wall",
                            outputPath = outputs[1].first,
                            reason = "Custom update callback was omitted.",
                            diagnosticCodes = listOf("CUSTOM_CALLBACK_OMITTED"),
                            location = SourceLocation(source, line = 1),
                        ),
                    ),
                    diagnostics = listOf(
                        Diagnostic(
                            code = "JAVA_STATIC_EXPORT_APPLIED",
                            severity = DiagnosticSeverity.INFO,
                            message = "Fixture declarations were exported without executing code.",
                            location = SourceLocation(source),
                        ),
                    ),
                    logs = listOf("Parsed 2 declarations."),
                    metadata = mapOf("declarations" to "2"),
                )
            }
        }

        val result = BridgeConverter.convert(
            ConversionRequest(
                input = input,
                outputDirectory = temp.resolve("java-out"),
                staticSourceExporters = listOf(exporter),
            ),
        )

        assertTrue(Files.exists(result.serverAssets.resolve("content/items/alloy.hjson")))
        assertTrue(Files.exists(result.serverAssets.resolve("content/blocks/wall.hjson")))
        assertTrue(
            Files.readString(result.serverAssets.resolve("content/blocks/wall.hjson"))
                .contains("dp-alloy/1"),
        )
        assertFalse(result.diagnostics.any { it.code == "MOD_CODE_NOT_EXECUTED" })
        assertFalse(
            result.diagnostics.any {
                it.code == "SPRITE_NAMESPACE_PATH_REWRITTEN" && it.location?.path == "src/JavaContent.java"
            },
        )
        val javaResult = result.report.fileResults.single { it.sourcePath == "src/JavaContent.java" }
        assertEquals(FileDisposition.CONVERTED, javaResult.disposition)
        assertEquals(2, javaResult.outputPaths.size)
        assertEquals(2, result.report.contentResults.size)
        assertEquals(1, result.report.summary.convertedContents)
        assertEquals(1, result.report.summary.degradedContents)
        assertTrue(
            result.diagnostics.any {
                it.code == "CUSTOM_CALLBACK_OMITTED" && it.severity == DiagnosticSeverity.WARNING
            },
        )
        assertEquals("2", result.report.metadata["staticExporter.fixture-java-exporter.declarations"])
        assertTrue(result.logs.any { it.contains("Parsed 2 declarations") })
        assertTrue(Files.readString(result.reportMarkdown).contains("Custom update callback was omitted."))
        assertEquals(result.inventory.scannedFiles, result.report.fileResults.size)
    }

    @Test
    fun `converts an ordinary mod assets tree and reports exclusions`() {
        val input = temp.resolve("sample-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: sample-mod\n")
        input.resolve("assets/content/units").createDirectories()
        input.resolve("assets/content/units/drone.hjson").writeText(
            """
            type: flying
            health: 100
            research: dagger
            """.trimIndent(),
        )
        input.resolve("assets/sprites").createDirectories()
        input.resolve("assets/sprites/drone.png").writeBytes(byteArrayOf(1, 2, 3))
        input.resolve("assets/maps").createDirectories()
        input.resolve("assets/maps/test.msav").writeBytes(byteArrayOf(4))
        input.resolve("assets/scripts").createDirectories()
        input.resolve("assets/scripts/main.js").writeText("print('not executed')")
        input.resolve("assets/sprites-override").createDirectories()
        input.resolve("assets/sprites-override/copper.png").writeBytes(byteArrayOf(5))
        input.resolve("assets/content/planets").createDirectories()
        input.resolve("assets/content/planets/example.hjson").writeText("name: example")
        input.resolve("assets/content/custom").createDirectories()
        input.resolve("assets/content/custom/unsupported.hjson").writeText("name: unsupported")

        val logs = mutableListOf<String>()
        val result = BridgeConverter.convert(
            ConversionRequest(
                input = input,
                outputDirectory = temp.resolve("out"),
                logSink = logs::add,
            ),
        )

        assertEquals(SourceKind.DIRECTORY, result.sourceKind)
        assertEquals(DetectedSourceKind.MOD, result.detectedKind)
        assertEquals(ConversionStatus.PARTIAL, result.status)
        assertTrue(Files.exists(result.dpZip))
        assertTrue(Files.exists(result.serverAssets.resolve("content/units/drone.hjson")))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/drone.png")))
        val normalized = Files.readString(result.serverAssets.resolve("content/units/drone.hjson"))
        assertFalse(normalized.contains("research"))
        assertTrue(result.diagnostics.any { it.code == "RESEARCH_REMOVED" })
        assertTrue(result.diagnostics.any { it.code == "MAPS_EXCLUDED" })
        assertTrue(result.diagnostics.any { it.code == "SCRIPTS_EXCLUDED" })
        assertTrue(result.diagnostics.any { it.code == "MOD_CODE_NOT_EXECUTED" && it.severity.name == "ERROR" })
        assertTrue(result.diagnostics.any { it.code == "SPRITES_OVERRIDE_EXCLUDED" })
        assertTrue(result.diagnostics.any { it.code == "PLANET_CONTENT_EXCLUDED" })
        assertEquals(result.files.size, result.report.fileResults.size)
        assertEquals(result.inventory.scannedFiles, result.report.fileResults.size)
        assertEquals(
            FileDisposition.CONVERTED,
            result.report.fileResults.single { it.sourcePath == "assets/content/units/drone.hjson" }.disposition,
        )
        assertEquals(
            FileDisposition.COPIED,
            result.report.fileResults.single { it.sourcePath == "assets/sprites/drone.png" }.disposition,
        )
        assertEquals(
            FileDisposition.EXCLUDED,
            result.report.fileResults.single { it.sourcePath == "assets/maps/test.msav" }.disposition,
        )
        val unsupported = result.report.fileResults.single {
            it.sourcePath == "assets/content/custom/unsupported.hjson"
        }
        assertEquals(FileDisposition.UNSUPPORTED, unsupported.disposition)
        assertTrue(unsupported.reason.orEmpty().contains("Unsupported top-level content folder"))
        assertEquals("1", result.report.metadata["unsupportedFiles"])
        assertEquals(result.report.fileResults, ConversionReportJson.decode(Files.readString(result.reportJson)).fileResults)
        val markdown = Files.readString(result.reportMarkdown)
        assertTrue(markdown.contains("### Copied files"))
        assertTrue(markdown.contains("### Converted files"))
        assertTrue(markdown.contains("### Excluded files"))
        assertTrue(markdown.contains("### Unsupported files"))
        assertTrue(markdown.contains("### Failed files"))
        assertTrue(markdown.contains("Unsupported top-level content folder: custom"))
        assertEquals(logs, result.logs)
        assertEquals(
            ValidationStatus.FAILED,
            result.report.validationStages.single { it.stage == ValidationStage.STRUCTURE }.status,
        )
        ZipFile(result.dpZip.toFile()).use { zip ->
            assertTrue(zip.getEntry("content/units/drone.hjson") != null)
            assertTrue(zip.getEntry("sprites/drone.png") != null)
            assertTrue(zip.getEntry("sample-mod/content/units/drone.hjson") == null)
        }
    }

    @Test
    fun `reports audio container extension mismatches without rewriting bytes`() {
        val input = temp.resolve("audio-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: audio-mod\n")
        input.resolve("assets/sounds").createDirectories()

        val realOgg = "OggSfixture".toByteArray()
        val mislabeledMp3 = "ID3fixture".toByteArray()
        val mislabeledWav = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0,
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
        )
        input.resolve("assets/sounds/real.ogg").writeBytes(realOgg)
        input.resolve("assets/sounds/mp3-in-ogg.ogg").writeBytes(mislabeledMp3)
        input.resolve("assets/sounds/wav-in-ogg.ogg").writeBytes(mislabeledWav)

        val result = BridgeConverter.convert(
            ConversionRequest(input, temp.resolve("audio-out"), staticSourceExporters = emptyList()),
        )

        val mismatches = result.diagnostics.filter { it.code == "AUDIO_CONTAINER_EXTENSION_MISMATCH" }
        assertEquals(2, mismatches.size)
        assertTrue(mismatches.any { it.location?.path == "assets/sounds/mp3-in-ogg.ogg" && it.details.orEmpty().contains("detected=mp3") })
        assertTrue(mismatches.any { it.location?.path == "assets/sounds/wav-in-ogg.ogg" && it.details.orEmpty().contains("detected=wav") })
        assertFalse(mismatches.any { it.location?.path == "assets/sounds/real.ogg" })
        assertContentEquals(mislabeledMp3, Files.readAllBytes(result.serverAssets.resolve("sounds/mp3-in-ogg.ogg")))
        assertContentEquals(mislabeledWav, Files.readAllBytes(result.serverAssets.resolve("sounds/wav-in-ogg.ogg")))
    }

    @Test
    fun `wraps a standalone legacy cp as a patch`() {
        val input = temp.resolve("legacy.hjson")
        input.writeText(
            """
            name: Legacy Test
            unit: {
              dagger: {
                health: 321
                research: core-shard
              }
            }
            """.trimIndent(),
        )

        val result = BridgeConverter.convert(
            ConversionRequest(input, temp.resolve("legacy-out")),
        )

        assertEquals(DetectedSourceKind.LEGACY_CP, result.detectedKind)
        assertEquals(SourceKind.LEGACY_CP, result.sourceKind)
        assertEquals(SourceKind.LEGACY_CP, result.report.source.kind)
        assertEquals(FileDisposition.CONVERTED, result.report.fileResults.single().disposition)
        assertTrue(ConversionReportJson.encode(result.report).contains("\"legacyCp\""))
        val patch = result.serverAssets.resolve("patches/Legacy-Test.hjson")
        assertTrue(Files.exists(patch))
        assertFalse(Files.readString(patch).contains("research"))
        assertTrue(result.diagnostics.any { it.code == "LEGACY_CP_WRAPPED" })
        assertTrue(result.diagnostics.any { it.code == "RESEARCH_REMOVED" })
    }

    @Test
    fun `repairs common legacy cp inline bare tokens with an explicit warning`() {
        val input = temp.resolve("legacy-inline.hjson")
        input.writeText(
            """
            # old CP syntax that Mindustry community scripts historically accepted
            name: Legacy Inline
            block: {
              copper-wall: {
                "requirements.+": [
                  { item: plastanium, amount: 2 }
                ]
              }
            }
            """.trimIndent(),
        )

        val result = BridgeConverter.convert(ConversionRequest(input, temp.resolve("legacy-inline-out")))

        assertEquals(DetectedSourceKind.LEGACY_CP, result.detectedKind)
        assertTrue(result.diagnostics.any { it.code == "LEGACY_CP_COMPATIBILITY_REPAIR" })
        val patch = Files.readString(result.serverAssets.resolve("patches/Legacy-Inline.hjson"))
        assertTrue(patch.contains("\"item\": \"plastanium\""))
        assertTrue(patch.contains("\"amount\": 2"))
    }

    @Test
    fun `repackages existing data pack deterministically`() {
        val sourceZip = temp.resolve("existing.zip")
        createZip(
            sourceZip,
            mapOf(
                "content/statuses/test.json" to "{\n // comment\n color: ff0000\n research: copper\n}".toByteArray(),
                "sprites/test.png" to byteArrayOf(1, 2, 3, 4),
                "sprites/generated/status_hash/test.png" to byteArrayOf(4, 3, 2, 1),
            ),
        )

        val first = BridgeConverter.convert(ConversionRequest(sourceZip, temp.resolve("first")))
        val second = BridgeConverter.convert(ConversionRequest(sourceZip, temp.resolve("second")))

        assertEquals(DetectedSourceKind.DATA_PACK, first.detectedKind)
        assertContentEquals(Files.readAllBytes(first.dpZip), Files.readAllBytes(second.dpZip))
        assertContentEquals(
            "{\n // comment\n color: ff0000\n research: copper\n}".toByteArray(),
            Files.readAllBytes(first.serverAssets.resolve("content/statuses/test.json")),
        )
        assertTrue(first.diagnostics.any { it.code == "GENERATED_SPRITE_PAIR" })
        assertTrue(first.diagnostics.any { it.code == "DATA_PACK_REPACKAGED" })
        assertTrue(first.diagnostics.any { it.code == "DATA_PACK_TEXT_PRESERVED" })
    }

    @Test
    fun `rewrites ordinary mod references bundles patches and generated sprite names`() {
        val input = temp.resolve("namespace-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: sample-mod\n")
        input.resolve("assets/content/items").createDirectories()
        input.resolve("assets/content/items/alloy.hjson").writeText("color: ffffff")
        input.resolve("assets/content/blocks").createDirectories()
        input.resolve("assets/content/blocks/wall.hjson").writeText(
            """
            type: ItemTurret
            name: sample-mod-alloy
            requirements: ["sample-mod-alloy/4", "alloy/1"]
            shootSound: sample-mod-shot
            region: sample-mod-shape
            ammoTypes: {
              alloy: {
                type: BasicBulletType
                damage: 1
              }
            }
            parts: [
              {
                type: RegionPart
                name: shape
              }
            ]
            """.trimIndent(),
        )
        input.resolve("assets/content/units").createDirectories()
        input.resolve("assets/content/units/drone.hjson").writeText(
            """
            type: flying
            weapons: [
              {
                name: sample-mod-drone-gun
                bullet: {
                  type: BasicBulletType
                  sprite: shape
                }
              }
            ]
            """.trimIndent(),
        )
        input.resolve("assets/patches").createDirectories()
        input.resolve("assets/patches/local.hjson").writeText(
            """
            block: {
              wall: {
                requirements: ["sample-mod-alloy/1"]
              }
            }
            item: {
              sample-mod-alloy: {
                cost: 2
              }
            }
            """.trimIndent(),
        )
        input.resolve("assets/bundles").createDirectories()
        input.resolve("assets/bundles/bundle.properties").writeText(
            "item.sample-mod-alloy.name = Alloy\nblock.sample-mod-wall.name = Wall\n",
        )
        input.resolve("assets/sprites").createDirectories()
        input.resolve("assets/sprites/alloy.png").writeBytes(byteArrayOf(1))
        input.resolve("assets/sprites/wall.png").writeBytes(byteArrayOf(2))
        input.resolve("assets/sprites/drone.png").writeBytes(byteArrayOf(3))
        input.resolve("assets/sprites/drone-gun.png").writeBytes(byteArrayOf(4))
        input.resolve("assets/sprites/shape.png").writeBytes(byteArrayOf(5))
        input.resolve("assets/sprites/item-sample-mod-alloy-full.png").writeBytes(byteArrayOf(6))
        input.resolve("assets/sounds").createDirectories()
        input.resolve("assets/sounds/shot.ogg").writeBytes(byteArrayOf(7))

        val result = BridgeConverter.convert(ConversionRequest(input, temp.resolve("namespace-out")))

        val wall = Files.readString(result.serverAssets.resolve("content/blocks/wall.hjson"))
        assertTrue(wall.contains("dp-alloy/4"))
        assertTrue(wall.contains("dp-alloy/1"))
        assertTrue(wall.contains("\"dp-alloy\": {"))
        assertTrue(wall.contains("\"name\": \"sample-mod-alloy\""))
        assertTrue(wall.contains("\"shootSound\": \"dp-shot\""))
        assertTrue(wall.contains("\"region\": \"dp-shape\""))
        assertTrue(wall.contains("\"name\": \"dp-shape\""))
        val drone = Files.readString(result.serverAssets.resolve("content/units/drone.hjson"))
        assertTrue(drone.contains("\"name\": \"drone-gun\""))
        assertTrue(drone.contains("\"sprite\": \"dp-shape\""))
        val patch = Files.readString(result.serverAssets.resolve("patches/local.hjson"))
        assertTrue(patch.contains("\"dp-wall\""))
        assertTrue(patch.contains("\"dp-alloy\""))
        assertTrue(patch.contains("dp-alloy/1"))
        val bundle = Files.readString(result.serverAssets.resolve("bundles/bundle.properties"))
        assertTrue(bundle.contains("item.dp-alloy.name"))
        assertTrue(bundle.contains("block.dp-wall.name"))
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/generated/item-dp-alloy-full.png")))
        assertFalse(Files.exists(result.serverAssets.resolve("sprites/item-sample-mod-alloy-full.png")))
        assertTrue(result.diagnostics.any { it.code == "MOD_REFERENCE_REWRITTEN" })
        assertTrue(result.diagnostics.any { it.code == "BUNDLE_NAMESPACE_REWRITTEN" })
        assertTrue(result.diagnostics.any { it.code == "SPRITE_NAMESPACE_PATH_REWRITTEN" })
        assertFalse(result.diagnostics.any { it.code == "UNRESOLVED_MOD_REFERENCE" })
    }

    @Test
    fun `rewrites nested mod audio paths to the basename registered by data packs`() {
        val input = temp.resolve("nested-audio-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: nested-audio\n")
        input.resolve("assets/content/blocks").createDirectories()
        input.resolve("assets/content/blocks/turret.hjson").writeText(
            """
            type: PowerTurret
            shootSound: weapons/heavy-shot
            """.trimIndent(),
        )
        input.resolve("assets/sprites").createDirectories()
        input.resolve("assets/sprites/turret.png").writeBytes(byteArrayOf(1))
        input.resolve("assets/sounds/weapons").createDirectories()
        input.resolve("assets/sounds/weapons/heavy-shot.ogg").writeBytes(byteArrayOf(2))

        val result = BridgeConverter.convert(ConversionRequest(input, temp.resolve("nested-audio-out")))

        val turret = Files.readString(result.serverAssets.resolve("content/blocks/turret.hjson"))
        assertTrue(turret.contains("\"shootSound\": \"dp-heavy-shot\""))
        assertFalse(result.diagnostics.any { it.code == "AUDIO_REFERENCE_NOT_DP_PREFIXED" })
        assertTrue(Files.exists(result.serverAssets.resolve("sounds/weapons/heavy-shot.ogg")))
    }

    @Test
    fun `deduplicates byte-identical ordinary sprite basenames`() {
        val input = temp.resolve("duplicate-sprite-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: duplicate-sprite\n")
        input.resolve("assets/content/items").createDirectories()
        input.resolve("assets/content/items/alloy.hjson").writeText("color: ffffff")
        input.resolve("assets/sprites/a").createDirectories()
        input.resolve("assets/sprites/b").createDirectories()
        input.resolve("assets/sprites/a/alloy.png").writeBytes(byteArrayOf(1, 2, 3))
        input.resolve("assets/sprites/b/alloy.png").writeBytes(byteArrayOf(1, 2, 3))

        val result = BridgeConverter.convert(ConversionRequest(input, temp.resolve("duplicate-sprite-out")))

        assertEquals(
            1,
            result.inventory.assets.count {
                it.kind.name == "SPRITE" && !it.sourcePath.startsWith("<bridge-generated:")
            },
        )
        assertTrue(result.diagnostics.any { it.code == "IDENTICAL_SPRITE_DEDUPLICATED" })
        assertEquals(
            FileDisposition.EXCLUDED,
            result.report.fileResults.single { it.sourcePath == "assets/sprites/b/alloy.png" }.disposition,
        )
        assertTrue(Files.exists(result.serverAssets.resolve("sprites/a/alloy.png")))
        assertFalse(Files.exists(result.serverAssets.resolve("sprites/b/alloy.png")))
    }

    @Test
    fun `generates B480 unit and turret outlines full icons and lowercase mirror sides`() {
        val input = temp.resolve("offline-icons-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: offline-icons\n")
        input.resolve("assets/content/blocks").createDirectories()
        input.resolve("assets/content/blocks/turret.hjson").writeText(
            """
            {
              "type": "ItemTurret",
              "size": 5,
              "drawer": {
                "type": "DrawTurret",
                "parts": [
                  {"type": "RegionPart", "suffix": "-arm", "mirror": true}
                ]
              }
            }
            """.trimIndent(),
        )
        input.resolve("assets/content/units").createDirectories()
        input.resolve("assets/content/units/drone.hjson").writeText(
            """
            {
              "type": "flying",
              "outlineColor": "383848ff",
              "weapons": [
                {
                  "name": "drone-gun",
                  "parts": [{"type": "RegionPart", "suffix": "-barrel"}],
                  "bullet": {"type": "BasicBulletType", "damage": 1}
                }
              ]
            }
            """.trimIndent(),
        )

        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4////fwAJ+wP9KobjigAAAABJRU5ErkJggg==",
        )
        val sprites = input.resolve("assets/sprites").createDirectories()
        listOf(
            "blocks/turrets/base/block-5.png",
            "blocks/turrets/turret.png",
            "blocks/turrets/turret-preview.png",
            "blocks/turrets/turret-arm-R.png",
            "blocks/turrets/turret-arm-l.png",
            "units/drone.png",
            "units/drone-gun.png",
            "units/drone-gun-barrel.png",
        ).forEach { path ->
            val target = sprites.resolve(path)
            target.parent.createDirectories()
            target.writeBytes(png)
        }

        val result = BridgeConverter.convert(
            ConversionRequest(input, temp.resolve("offline-icons-out"), staticSourceExporters = emptyList()),
        )

        val turretBytes = Files.readAllBytes(result.serverAssets.resolve("content/blocks/turret.hjson"))
        val turretSpriteDirectory = result.serverAssets.resolve("sprites/blocks/turrets")
        assertTrue(Files.exists(turretSpriteDirectory.resolve("turret-arm-r.png")))
        // Files.exists() cannot distinguish these spellings on case-insensitive Windows volumes;
        // inspect the directory entry's stored filename instead.
        assertFalse(
            Files.list(turretSpriteDirectory).use { entries ->
                entries.anyMatch { it.fileName.toString() == "turret-arm-R.png" }
            },
        )

        val turretHash = "block_${encodeMindustryHash(MessageDigest.getInstance("SHA-256").digest(turretBytes))}"
        val turretGenerated = result.serverAssets.resolve("sprites/generated/$turretHash")
        assertTrue(Files.exists(turretGenerated.resolve("block-dp-turret-full.png")))
        assertTrue(Files.exists(turretGenerated.resolve("turret-preview.png")))
        assertTrue(Files.exists(turretGenerated.resolve("turret-arm-r-outline.png")))
        assertTrue(Files.exists(turretGenerated.resolve("turret-arm-l-outline.png")))

        val unitBytes = Files.readAllBytes(result.serverAssets.resolve("content/units/drone.hjson"))
        val unitHash = "unit_${encodeMindustryHash(MessageDigest.getInstance("SHA-256").digest(unitBytes))}"
        val unitGenerated = result.serverAssets.resolve("sprites/generated/$unitHash")
        assertTrue(Files.exists(unitGenerated.resolve("drone.png")))
        assertTrue(Files.exists(unitGenerated.resolve("drone-gun.png")))
        assertTrue(Files.exists(unitGenerated.resolve("drone-gun-barrel-outline.png")))
        assertTrue(result.diagnostics.any { it.code == "B480_OFFLINE_CONTENT_SPRITES_GENERATED" })
        assertTrue(result.report.metadata.getValue("b480OfflineGeneratedFullIcons").toInt() >= 1)
        assertTrue(result.report.metadata.getValue("b480OfflineGeneratedOutlines").toInt() >= 6)
    }

    @Test
    fun `rejects sound and music basenames that collide in the shared data pack namespace`() {
        val input = temp.resolve("audio-collision-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: audio-collision\n")
        input.resolve("assets/sounds").createDirectories()
        input.resolve("assets/music").createDirectories()
        input.resolve("assets/sounds/theme.ogg").writeBytes(byteArrayOf(1))
        input.resolve("assets/music/theme.ogg").writeBytes(byteArrayOf(2))

        val error = assertFailsWith<ConversionException> {
            BridgeConverter.convert(ConversionRequest(input, temp.resolve("audio-collision-out")))
        }

        assertTrue(error.diagnostics.any { it.code == "AUDIO_NAME_COLLISION" })
    }

    @Test
    fun `reports missing explicitly namespaced assets without discarding generated artifacts`() {
        val input = temp.resolve("missing-asset-mod").createDirectories()
        input.resolve("mod.hjson").writeText("name: missing-mod\n")
        input.resolve("assets/content/blocks").createDirectories()
        input.resolve("assets/content/blocks/turret.hjson").writeText(
            """
            type: PowerTurret
            shootSound: missing-mod-absent-shot
            shootType: {
              type: BasicBulletType
              sprite: missing-mod-absent-bullet
            }
            """.trimIndent(),
        )
        input.resolve("assets/sprites").createDirectories()
        input.resolve("assets/sprites/turret.png").writeBytes(byteArrayOf(1))

        val result = BridgeConverter.convert(ConversionRequest(input, temp.resolve("missing-asset-out")))

        assertTrue(Files.exists(result.dpZip))
        assertEquals(2, result.diagnostics.count { it.code == "UNRESOLVED_MOD_REFERENCE" })
        assertEquals(
            ValidationStatus.FAILED,
            result.report.validationStages.single { it.stage == ValidationStage.STRUCTURE }.status,
        )
        assertTrue(
            result.report.fileResults.single { it.sourcePath.endsWith("turret.hjson") }
                .diagnosticCodes.contains("UNRESOLVED_MOD_REFERENCE"),
        )
    }

    @Test
    fun `rejects zip path traversal`() {
        val sourceZip = temp.resolve("unsafe.zip")
        createZip(sourceZip, mapOf("../escape.json" to "{}".toByteArray()))

        assertFailsWith<ConversionException> {
            BridgeConverter.convert(ConversionRequest(sourceZip, temp.resolve("unsafe-out")))
        }
        assertFalse(Files.exists(temp.resolve("escape.json")))
    }

    @Test
    fun `rejects excessive compression ratio`() {
        val sourceZip = temp.resolve("bomb.zip")
        createZip(sourceZip, mapOf("patches/bomb.hjson" to ByteArray(50_000) { 'a'.code.toByte() }))

        val error = assertFailsWith<ConversionException> {
            BridgeConverter.convert(
                ConversionRequest(
                    input = sourceZip,
                    outputDirectory = temp.resolve("bomb-out"),
                    limits = SecurityLimits(maxCompressionRatio = 2.0),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("compression ratio", ignoreCase = true))
    }

    @Test
    fun `strips one outer archive directory`() {
        val sourceZip = temp.resolve("wrapped.zip")
        createZip(
            sourceZip,
            mapOf(
                "project/mod.hjson" to "name: wrapped-mod".toByteArray(),
                "project/assets/content/items/foo.hjson" to "color: ffffff".toByteArray(),
            ),
        )

        val result = BridgeConverter.convert(ConversionRequest(sourceZip, temp.resolve("wrapped-out")))

        assertEquals(DetectedSourceKind.MOD, result.detectedKind)
        assertTrue(Files.exists(result.serverAssets.resolve("content/items/foo.hjson")))
        assertTrue(result.diagnostics.any { it.code == "COMMON_ARCHIVE_ROOT_STRIPPED" })
    }

    private fun createZip(path: Path, entries: Map<String, ByteArray>) {
        Files.newOutputStream(path).use { output ->
            ZipOutputStream(output, StandardCharsets.UTF_8).use { zip ->
                entries.toSortedMap().forEach { (name, bytes) ->
                    val entry = ZipEntry(name)
                    entry.time = 0L
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun encodeMindustryHash(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = CharArray(52)
        var dataIndex = 0
        var outputIndex = 0
        repeat(6) {
            val bits =
                ((data[dataIndex++].toLong() and 0xffL) shl 32) or
                    ((data[dataIndex++].toLong() and 0xffL) shl 24) or
                    ((data[dataIndex++].toLong() and 0xffL) shl 16) or
                    ((data[dataIndex++].toLong() and 0xffL) shl 8) or
                    (data[dataIndex++].toLong() and 0xffL)
            output[outputIndex++] = alphabet[((bits ushr 35) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 30) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 25) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 20) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 15) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 10) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[((bits ushr 5) and 0x1f).toInt()]
            output[outputIndex++] = alphabet[(bits and 0x1f).toInt()]
        }
        val b0 = data[dataIndex++].toInt() and 0xff
        val b1 = data[dataIndex].toInt() and 0xff
        output[outputIndex++] = alphabet[(b0 ushr 3) and 0x1f]
        output[outputIndex++] = alphabet[((b0 shl 2) and 0x1c) or ((b1 ushr 6) and 0x03)]
        output[outputIndex++] = alphabet[(b1 ushr 1) and 0x1f]
        output[outputIndex] = alphabet[(b1 shl 4) and 0x1f]
        return String(output)
    }
}
