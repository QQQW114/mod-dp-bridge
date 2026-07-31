package io.github.moddpbridge.cli

import io.github.moddpbridge.converter.RuntimePreparedContentResult
import io.github.moddpbridge.converter.RuntimePreparedConversion
import io.github.moddpbridge.converter.RuntimePreparedFile
import io.github.moddpbridge.model.ContentDisposition
import io.github.moddpbridge.model.ContentKind
import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import io.github.moddpbridge.target.v1597.ContentApplyValidationResult
import io.github.moddpbridge.target.v1597.ServerValidationResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine

class RuntimeConversionPipelineTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `root command exposes explicit runtime conversion mode`() {
        val commandLine = CommandLine(RootCommand())
        val subcommands = commandLine.subcommands.keys
        assertTrue("convert" in subcommands)
        assertTrue("runtime-convert" in subcommands)
        val runtime = commandLine.subcommands.getValue("runtime-convert").commandSpec
        assertTrue(runtime.findOption("--mod-jar").required())
        assertTrue(runtime.findOption("--server-jar").required())
        assertTrue(runtime.findOption("--allow-mod-execution").required())
    }

    @Test
    fun `successful extraction retains snapshot and reports mapper as not run`() {
        val modJar = Files.writeString(temporary.resolve("fixture-mod.jar"), "mod")
        val serverJar = Files.writeString(temporary.resolve("server.jar"), "server")
        val source = Files.createDirectory(temporary.resolve("source"))
        val output = temporary.resolve("out")
        Files.createDirectories(output.resolve("logs"))

        val extractor = RuntimeExtractorRunner { request, _ ->
            request.snapshot.parent?.let(Files::createDirectories)
            Files.createDirectories(request.workDirectory)
            request.logFile.parent?.let(Files::createDirectories)
            Files.writeString(request.logFile, "fixture extractor log\n", StandardCharsets.UTF_8)
            Files.writeString(request.commandLogFile, "fixture extractor command\n", StandardCharsets.UTF_8)
            Files.writeString(
                request.snapshot,
                """
                {
                  "schemaVersion": 2,
                  "targetMod": "fixture",
                  "gameVersion": {"type": "official", "modifier": "release", "build": 159, "revision": 7},
                  "contentCount": 3,
                  "contents": []
                }
                """.trimIndent(),
                StandardCharsets.UTF_8,
            )
            RuntimeExtractorResult(0, false, Duration.ofMillis(25), listOf("fixture-extractor"))
        }
        val sourceStage = RuntimeSourceIndexStage { _, _, report, _ ->
            Files.writeString(report, "{\"schemaVersion\":1}\n", StandardCharsets.UTF_8)
            RuntimeSourceIndexResult(report, 4, 3, 2, 2, 1)
        }

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeConversionPipeline(
                extractor,
                sourceStage,
                DeferredRuntimeToDpMappingStage,
                serverJarPolicy = ALLOW_TEST_SERVER,
            ).run(
                RuntimeConversionPipelineRequest(
                    modJar = modJar,
                    serverJar = serverJar,
                    source = source,
                    outputDirectory = output,
                    modId = null,
                    runtimeTimeout = Duration.ofSeconds(10),
                    serverValidationTimeout = Duration.ofSeconds(20),
                    allowModExecution = true,
                ),
                logger,
            )
        }

        assertEquals(5, result.exitCode)
        assertEquals("snapshotReady", result.status)
        assertTrue(Files.isRegularFile(assertNotNull(result.snapshot)))
        assertTrue(Files.isRegularFile(assertNotNull(result.sourceIndexReport)))
        assertTrue(Files.isRegularFile(result.report))

        val report = Json.parseToJsonElement(Files.readString(result.report)).jsonObject
        assertEquals("snapshotReady", report.getValue("status").jsonPrimitive.content)
        assertEquals(3, report.getValue("runtimeSnapshot").jsonObject.getValue("contentCount").jsonPrimitive.content.toInt())
        val stages = report.getValue("stages").jsonArray.associate { stageElement ->
            val stage = stageElement.jsonObject
            stage.getValue("stage").jsonPrimitive.content to stage.getValue("status").jsonPrimitive.content
        }
        assertEquals("passed", stages.getValue("runtimeExtraction"))
        assertEquals("passed", stages.getValue("sourceIndex"))
        assertEquals("notRun", stages.getValue("runtimeToDpMapping"))
        assertEquals("notRun", stages.getValue("hybridSourceSelection"))
        assertEquals("notRun", stages.getValue("packaging"))
        assertEquals("notRun", stages.getValue("dpValidation"))
        assertFalse(Files.exists(output.resolve("result.zip")))
    }

    @Test
    fun `extractor failure still writes a stage-complete pipeline report`() {
        val modJar = Files.writeString(temporary.resolve("broken-mod.jar"), "mod")
        val serverJar = Files.writeString(temporary.resolve("server.jar"), "server")
        val output = temporary.resolve("failed-out")
        Files.createDirectories(output.resolve("logs"))
        val extractor = RuntimeExtractorRunner { request, _ ->
            Files.createDirectories(request.workDirectory)
            Files.writeString(request.logFile, "fixture failure\n")
            Files.writeString(request.commandLogFile, "fixture command\n")
            RuntimeExtractorResult(4, false, Duration.ofMillis(10), listOf("fixture-extractor"))
        }

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeConversionPipeline(extractor, serverJarPolicy = ALLOW_TEST_SERVER).run(
                RuntimeConversionPipelineRequest(
                    modJar = modJar,
                    serverJar = serverJar,
                    source = null,
                    outputDirectory = output,
                    modId = "broken",
                    runtimeTimeout = Duration.ofSeconds(5),
                    serverValidationTimeout = Duration.ofSeconds(5),
                    allowModExecution = true,
                ),
                logger,
            )
        }

        assertEquals(3, result.exitCode)
        assertEquals("failed", result.status)
        val report = Json.parseToJsonElement(Files.readString(result.report)).jsonObject
        val stages = report.getValue("stages").jsonArray.map { it.jsonObject }
        assertEquals("failed", stages.single { it.getValue("stage").jsonPrimitive.content == "runtimeExtraction" }
            .getValue("status").jsonPrimitive.content)
        assertEquals(7, stages.size)
    }

    @Test
    fun `extractor process command forwards all trust and isolation inputs`() {
        val request = RuntimeExtractorRequest(
            modJar = temporary.resolve("mod.jar"),
            serverJar = temporary.resolve("server.jar"),
            snapshot = temporary.resolve("out/runtime-snapshot.json"),
            workDirectory = temporary.resolve("out/logs/runtime-work"),
            logFile = temporary.resolve("out/logs/runtime-extractor.log"),
            commandLogFile = temporary.resolve("out/logs/runtime-extractor-command.txt"),
            modId = "fixture-mod",
            timeout = Duration.ofSeconds(42),
            allowModExecution = true,
        )
        val process = RuntimeExtractorProcess(
            javaExecutable = Path.of("fake-java"),
            classPath = "fake-classpath",
            extractorMainClass = "fixture.ExtractorMain",
        )

        val command = process.buildCommand(request)
        assertEquals("fixture.ExtractorMain", command[6])
        assertTrue(command.containsAll(listOf(
            "extract",
            "--server-jar",
            request.serverJar.toAbsolutePath().normalize().toString(),
            "--mod-jar",
            request.modJar.toAbsolutePath().normalize().toString(),
            "--output",
            request.snapshot.toAbsolutePath().normalize().toString(),
            "--work-dir",
            request.workDirectory.toAbsolutePath().normalize().toString(),
            "--timeout-seconds",
            "42",
            "--mod-id",
            "fixture-mod",
            "--allow-mod-execution",
        )))
    }

    @Test
    fun `overwrite removes only known runtime artifacts and stale dp archives`() {
        val output = Files.createDirectory(temporary.resolve("overwrite-out"))
        Files.writeString(output.resolve("old-dp-v159.7.zip"), "old")
        Files.writeString(output.resolve("report.json"), "old report")
        Files.writeString(output.resolve("runtime-mapping.json"), "old mapping")
        Files.writeString(output.resolve("failure-report.txt"), "old failure")
        Files.createDirectories(output.resolve("server-assets/content/items"))
        Files.writeString(output.resolve("server-assets/content/items/old.hjson"), "color: ffffff")
        Files.createDirectories(output.resolve("logs/runtime-work/run-old"))
        Files.writeString(output.resolve("logs/runtime-work/run-old/headless.log"), "old")
        Files.writeString(output.resolve("logs/data-patch-apply.log"), "old")
        val unknownTopLevel = Files.writeString(output.resolve("keep-me.txt"), "user")
        val unknownLog = Files.writeString(output.resolve("logs/keep-me.log"), "user")

        prepareRuntimeOutput(output, overwrite = true)

        assertFalse(Files.exists(output.resolve("old-dp-v159.7.zip")))
        assertFalse(Files.exists(output.resolve("report.json")))
        assertFalse(Files.exists(output.resolve("runtime-mapping.json")))
        assertFalse(Files.exists(output.resolve("failure-report.txt")))
        assertFalse(Files.exists(output.resolve("server-assets")))
        assertFalse(Files.exists(output.resolve("logs/runtime-work")))
        assertFalse(Files.exists(output.resolve("logs/data-patch-apply.log")))
        assertTrue(Files.isRegularFile(unknownTopLevel))
        assertTrue(Files.isRegularFile(unknownLog))
        assertTrue(Files.isDirectory(output.resolve("logs")))
    }

    @Test
    fun `optional source index failure is retained but does not block mapping`() {
        val modJar = Files.writeString(temporary.resolve("source-failure-mod.jar"), "mod")
        val serverJar = Files.writeString(temporary.resolve("source-failure-server.jar"), "server")
        val source = Files.createDirectory(temporary.resolve("broken-source"))
        val output = temporary.resolve("source-failure-out")
        val extractor = successfulExtractor()
        val sourceStage = RuntimeSourceIndexStage { _, _, _, _ -> error("fixture source index failure") }

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeConversionPipeline(
                extractor = extractor,
                sourceIndexStage = sourceStage,
                mappingStage = DeferredRuntimeToDpMappingStage,
                serverJarPolicy = ALLOW_TEST_SERVER,
            ).run(runtimeRequest(modJar, serverJar, source, output), logger)
        }

        assertEquals(5, result.exitCode)
        assertEquals("snapshotReady", result.status)
        assertTrue(Files.isRegularFile(output.resolve("logs/source-index-failure.txt")))
        val report = Json.parseToJsonElement(Files.readString(result.report)).jsonObject
        val sourceRecord = report.getValue("stages").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("stage").jsonPrimitive.content == "sourceIndex" }
        assertEquals("failed", sourceRecord.getValue("status").jsonPrimitive.content)
        assertTrue(sourceRecord.getValue("summary").jsonPrimitive.content.contains("continued"))
    }

    @Test
    fun `empty runtime declaration baseline without source fails before packaging`() {
        val modJar = runtimeModJar("block-only.jar")
        val serverJar = Files.writeString(temporary.resolve("block-only-server.jar"), "server")
        val output = temporary.resolve("block-only-out")
        val mapper = RuntimeToDpMappingStage { _, _ ->
            RuntimeToDpMappingResult(
                status = RuntimePipelineStageStatus.PASSED,
                summary = "empty fixture baseline retained for hybrid",
                preparedConversion = RuntimePreparedConversion(
                    files = emptyList(),
                    contentResults = listOf(
                        RuntimePreparedContentResult(
                            sourceSymbol = "fixture-wall",
                            kind = ContentKind.BLOCK,
                            disposition = ContentDisposition.UNSUPPORTED,
                            sourceType = "fixture.Block",
                            targetType = "Wall",
                            diagnosticCodes = listOf("RUNTIME_CONTENT_TYPE_NOT_MAPPED"),
                        ),
                    ),
                ),
            )
        }

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeConversionPipeline(
                extractor = successfulExtractor(),
                mappingStage = mapper,
                serverJarPolicy = ALLOW_TEST_SERVER,
            ).run(runtimeRequest(modJar, serverJar, null, output), logger)
        }

        assertEquals(6, result.exitCode)
        assertEquals("failed", result.status)
        assertEquals(null, result.dpZip)
        assertEquals(null, result.conversionReport)
        assertFalse(Files.exists(output.resolve("server-assets")))
        assertFalse(Files.list(output).use { entries ->
            entries.anyMatch { it.fileName.toString().endsWith("-dp-v159.7.zip") }
        })
        val report = Json.parseToJsonElement(Files.readString(result.report)).jsonObject
        val stages = report.getValue("stages").jsonArray.associate { element ->
            val stage = element.jsonObject
            stage.getValue("stage").jsonPrimitive.content to stage
        }
        assertEquals("passed", stages.getValue("runtimeToDpMapping").getValue("status").jsonPrimitive.content)
        assertEquals("notRun", stages.getValue("hybridSourceSelection").getValue("status").jsonPrimitive.content)
        assertEquals("failed", stages.getValue("packaging").getValue("status").jsonPrimitive.content)
        assertTrue(stages.getValue("packaging").getValue("summary").jsonPrimitive.content.contains("no --source"))
        assertEquals("notRun", stages.getValue("dpValidation").getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `packaging conversion failure writes text and structured diagnostics`() {
        val modJar = runtimeModJar("packaging-failure.jar")
        val serverJar = Files.writeString(temporary.resolve("packaging-server.jar"), "server")
        val output = temporary.resolve("packaging-failure-out")
        val invalidPrepared = RuntimePreparedConversion(
            files = listOf(
                RuntimePreparedFile(
                    outputPath = "content/items/runtime-item.hjson",
                    sourcePaths = listOf("missing/Registrar.class"),
                    bytes = "color: ffffff\n".encodeToByteArray(),
                ),
            ),
        )
        val mapper = RuntimeToDpMappingStage { _, _ ->
            RuntimeToDpMappingResult(
                RuntimePipelineStageStatus.PASSED,
                "fixture mapping",
                invalidPrepared,
            )
        }

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeConversionPipeline(
                extractor = successfulExtractor(),
                mappingStage = mapper,
                serverJarPolicy = ALLOW_TEST_SERVER,
            ).run(runtimeRequest(modJar, serverJar, null, output), logger)
        }

        assertEquals(6, result.exitCode)
        assertTrue(Files.isRegularFile(output.resolve("failure-report.txt")))
        assertTrue(Files.isRegularFile(output.resolve("failure-diagnostics.json")))
        assertTrue(Files.readString(output.resolve("failure-report.txt")).contains("unknown release-JAR entry"))
    }

    @Test
    fun `apply warning diagnostic rejects validation even when counters and validators pass`() {
        val modJar = runtimeModJar("apply-warning.jar")
        val serverJar = Files.writeString(temporary.resolve("apply-warning-server.jar"), "server")
        val output = temporary.resolve("apply-warning-out")
        val warning = Diagnostic(
            code = "DATA_PATCH_WARNING_COUNT_MISMATCH",
            severity = DiagnosticSeverity.WARNING,
            message = "fixture warning",
            stage = ValidationStage.RUNTIME,
        )

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeConversionPipeline(
                extractor = successfulExtractor(),
                mappingStage = successfulPreparedMapper(),
                serverJarPolicy = ALLOW_TEST_SERVER,
                serverDiscoveryValidator = passingServerDiscovery(),
                dataPatchApplyValidator = passingDataPatchApply(listOf(warning)),
            ).run(runtimeRequest(modJar, serverJar, null, output), logger)
        }

        assertEquals(7, result.exitCode)
        assertEquals("validationFailed", result.status)
        val finalReport = Json.parseToJsonElement(Files.readString(assertNotNull(result.conversionReport))).jsonObject
        assertEquals("rejected", finalReport.getValue("status").jsonPrimitive.content.lowercase())
        assertEquals(
            "failed",
            finalReport.getValue("metadata").jsonObject.getValue("dataPatchApply").jsonPrimitive.content,
        )
        val validation = validationStage(result.report)
        assertEquals("failed", validation.getValue("status").jsonPrimitive.content)
        assertTrue(validation.getValue("summary").jsonPrimitive.content.contains("Structural=passed"))
        assertTrue(validation.getValue("summary").jsonPrimitive.content.contains("DataPatcherApply=failed"))
    }

    @Test
    fun `prepared report error and failed content reject validation when external validators pass`() {
        val modJar = runtimeModJar("prepared-error.jar")
        val serverJar = Files.writeString(temporary.resolve("prepared-error-server.jar"), "server")
        val output = temporary.resolve("prepared-error-out")
        val outputPath = "content/items/runtime-item.hjson"
        val prepared = RuntimePreparedConversion(
            files = listOf(
                RuntimePreparedFile(
                    outputPath = outputPath,
                    sourcePaths = listOf("mod/Registrar.class"),
                    bytes = "color: ffffff\n".encodeToByteArray(),
                ),
            ),
            contentResults = listOf(
                RuntimePreparedContentResult(
                    sourceSymbol = "fixture-runtime-item",
                    kind = ContentKind.ITEM,
                    disposition = ContentDisposition.CONVERTED,
                    sourceType = "mindustry.type.Item",
                    targetType = "Item",
                    outputName = "dp-runtime-item",
                    outputPath = outputPath,
                    location = SourceLocation("mod/Registrar.class"),
                ),
                RuntimePreparedContentResult(
                    sourceSymbol = "fixture-failed-item",
                    kind = ContentKind.ITEM,
                    disposition = ContentDisposition.FAILED,
                    sourceType = "fixture.InvalidItem",
                    reason = "fixture conversion failure",
                    diagnosticCodes = listOf("RUNTIME_PREPARED_FIXTURE_ERROR"),
                    location = SourceLocation("mod/Registrar.class"),
                ),
            ),
            diagnostics = listOf(
                Diagnostic(
                    code = "RUNTIME_PREPARED_FIXTURE_ERROR",
                    severity = DiagnosticSeverity.ERROR,
                    message = "fixture prepared report error",
                    stage = ValidationStage.STRUCTURE,
                    location = SourceLocation("mod/Registrar.class"),
                ),
            ),
        )
        val mapper = RuntimeToDpMappingStage { _, _ ->
            RuntimeToDpMappingResult(
                status = RuntimePipelineStageStatus.PASSED,
                summary = "fixture mapping with retained failure",
                preparedConversion = prepared,
            )
        }

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeConversionPipeline(
                extractor = successfulExtractor(),
                mappingStage = mapper,
                serverJarPolicy = ALLOW_TEST_SERVER,
                serverDiscoveryValidator = passingServerDiscovery(),
                dataPatchApplyValidator = passingDataPatchApply(),
            ).run(runtimeRequest(modJar, serverJar, null, output), logger)
        }

        assertEquals(7, result.exitCode)
        assertEquals("validationFailed", result.status)
        val finalReport = Json.parseToJsonElement(Files.readString(assertNotNull(result.conversionReport))).jsonObject
        assertEquals("rejected", finalReport.getValue("status").jsonPrimitive.content.lowercase())
        assertEquals(1, finalReport.getValue("summary").jsonObject.getValue("failedContents").jsonPrimitive.content.toInt())
        assertEquals(
            "failed",
            finalReport.getValue("metadata").jsonObject.getValue("preparedConversionReport").jsonPrimitive.content,
        )
        val validation = validationStage(result.report)
        assertEquals("failed", validation.getValue("status").jsonPrimitive.content)
        assertTrue(validation.getValue("summary").jsonPrimitive.content.contains("PreparedReport=failed"))
        assertTrue(validation.getValue("summary").jsonPrimitive.content.contains("Structural=passed"))
        assertTrue(validation.getValue("summary").jsonPrimitive.content.contains("serverDiscovery=passed"))
        assertTrue(validation.getValue("summary").jsonPrimitive.content.contains("DataPatcherApply=passed"))
    }

    @Test
    fun `validation exception rejects final report and still completes pipeline report`() {
        val modJar = runtimeModJar("validation-exception.jar")
        val serverJar = Files.writeString(temporary.resolve("validation-server.jar"), "server")
        val output = temporary.resolve("validation-exception-out")
        val mapper = successfulPreparedMapper()
        val throwingStructural = RuntimeStructuralValidationRunner { error("fixture validator exploded") }

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeConversionPipeline(
                extractor = successfulExtractor(),
                mappingStage = mapper,
                serverJarPolicy = ALLOW_TEST_SERVER,
                structuralValidator = throwingStructural,
            ).run(runtimeRequest(modJar, serverJar, null, output), logger)
        }

        assertEquals(7, result.exitCode)
        assertEquals("validationFailed", result.status)
        assertTrue(Files.isRegularFile(result.report))
        assertTrue(Files.isRegularFile(output.resolve("failure-report.txt")))
        assertTrue(Files.isRegularFile(output.resolve("failure-diagnostics.json")))
        val finalReport = Json.parseToJsonElement(Files.readString(assertNotNull(result.conversionReport))).jsonObject
        assertEquals("rejected", finalReport.getValue("status").jsonPrimitive.content.lowercase())
        assertTrue(
            finalReport.getValue("diagnostics").jsonArray.any {
                it.jsonObject.getValue("code").jsonPrimitive.content == "RUNTIME_DP_VALIDATION_EXCEPTION"
            },
        )
        val pipeline = Json.parseToJsonElement(Files.readString(result.report)).jsonObject
        val validation = pipeline.getValue("stages").jsonArray.map { it.jsonObject }
            .single { it.getValue("stage").jsonPrimitive.content == "dpValidation" }
        assertEquals("failed", validation.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `production server policy rejects an unpinned jar before extractor launch`() {
        val modJar = Files.writeString(temporary.resolve("policy-mod.jar"), "mod")
        val serverJar = Files.writeString(temporary.resolve("untrusted-server.jar"), "server")
        val output = temporary.resolve("policy-out")
        val called = AtomicBoolean(false)
        val extractor = RuntimeExtractorRunner { _, _ ->
            called.set(true)
            error("must not execute")
        }

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeConversionPipeline(extractor = extractor).run(
                runtimeRequest(modJar, serverJar, null, output),
                logger,
            )
        }

        assertEquals(2, result.exitCode)
        assertFalse(called.get())
        assertTrue(Files.readString(result.report).contains(OfficialMindustry1597ServerJarPolicy.SHA256))
    }

    @Test
    fun `snapshot must report official release runtime`() {
        val modJar = Files.writeString(temporary.resolve("snapshot-type-mod.jar"), "mod")
        val serverJar = Files.writeString(temporary.resolve("snapshot-type-server.jar"), "server")
        val output = temporary.resolve("snapshot-type-out")
        val mappingCalled = AtomicBoolean(false)
        val mapper = RuntimeToDpMappingStage { _, _ ->
            mappingCalled.set(true)
            error("must not map")
        }

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeConversionPipeline(
                extractor = successfulExtractor(gameType = "custom", gameModifier = "fork"),
                mappingStage = mapper,
                serverJarPolicy = ALLOW_TEST_SERVER,
            ).run(runtimeRequest(modJar, serverJar, null, output), logger)
        }

        assertEquals(5, result.exitCode)
        assertFalse(mappingCalled.get())
        assertTrue(Files.readString(result.report).contains("requires the official v159.7 release runtime"))
    }

    @Test
    fun `mod jar mutation after extraction is rejected before mapping`() {
        val modJar = Files.writeString(temporary.resolve("mutable-mod.jar"), "before")
        val serverJar = Files.writeString(temporary.resolve("mutable-server.jar"), "server")
        val output = temporary.resolve("mutable-out")
        val mappingCalled = AtomicBoolean(false)
        val baseExtractor = successfulExtractor()
        val mutatingExtractor = RuntimeExtractorRunner { request, logger ->
            val result = baseExtractor.extract(request, logger)
            Files.writeString(modJar, "after")
            result
        }
        val mapper = RuntimeToDpMappingStage { _, _ ->
            mappingCalled.set(true)
            error("must not map")
        }

        val result = BridgeLogger(output.resolve("logs/conversion.log")).use { logger ->
            RuntimeConversionPipeline(
                extractor = mutatingExtractor,
                mappingStage = mapper,
                serverJarPolicy = ALLOW_TEST_SERVER,
            ).run(runtimeRequest(modJar, serverJar, null, output), logger)
        }

        assertEquals(5, result.exitCode)
        assertFalse(mappingCalled.get())
        assertTrue(Files.readString(result.report).contains("Mod JAR changed after preflight"))
    }

    @Test
    fun `real source index stage retains exact asset provenance as json`() {
        val runtimeJar = temporary.resolve("asset-mod.jar")
        ZipOutputStream(Files.newOutputStream(runtimeJar)).use { zip ->
            zip.putNextEntry(ZipEntry("sprites/example.png"))
            zip.write(byteArrayOf(1, 2, 3, 4))
            zip.closeEntry()
        }
        val sourceRoot = temporary.resolve("asset-source")
        val sourceAsset = sourceRoot.resolve("assets/sprites/example.png")
        Files.createDirectories(sourceAsset.parent)
        Files.write(sourceAsset, byteArrayOf(1, 2, 3, 4))
        val report = temporary.resolve("source-index-report.json")

        val result = BridgeLogger(temporary.resolve("source-index.log")).use { logger ->
            JarRuntimeSourceIndexStage().index(runtimeJar, sourceRoot, report, logger)
        }

        assertEquals(1, result.runtimeAssets)
        assertEquals(1, result.exactAssetMatches)
        assertEquals(0, result.issueCount)
        val json = Json.parseToJsonElement(Files.readString(report)).jsonObject
        assertEquals(1, json.getValue("summary").jsonObject.getValue("runtimeAssets").jsonPrimitive.content.toInt())
        assertEquals(
            "exact",
            json.getValue("assets").jsonArray.single().jsonObject.getValue("match").jsonPrimitive.content,
        )
    }

    private fun validationStage(report: Path) =
        Json.parseToJsonElement(Files.readString(report)).jsonObject
            .getValue("stages").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("stage").jsonPrimitive.content == "dpValidation" }

    private fun passingServerDiscovery(): RuntimeServerDiscoveryRunner =
        RuntimeServerDiscoveryRunner { _, _, _, _ ->
            ServerValidationResult(
                passed = true,
                exitCode = 0,
                discoveredAssetFiles = 1,
                outputLines = emptyList(),
                diagnostics = emptyList(),
            )
        }

    private fun passingDataPatchApply(
        diagnostics: List<Diagnostic> = emptyList(),
    ): RuntimeDataPatchApplyRunner = RuntimeDataPatchApplyRunner { _, _, _, _ ->
        ContentApplyValidationResult(
            applyCompleted = true,
            passed = true,
            exitCode = 0,
            timedOut = false,
            totalAssets = 1,
            contentAssets = 1,
            patchAssets = 0,
            externalAssets = 0,
            failedAssets = 0,
            warningCount = 0,
            addedContent = 1,
            outputLines = emptyList(),
            diagnostics = diagnostics,
        )
    }

    private fun successfulExtractor(
        gameType: String = "official",
        gameModifier: String = "release",
    ): RuntimeExtractorRunner = RuntimeExtractorRunner { request, _ ->
        request.snapshot.parent?.let(Files::createDirectories)
        Files.createDirectories(request.workDirectory)
        request.logFile.parent?.let(Files::createDirectories)
        Files.writeString(request.logFile, "fixture extractor log\n", StandardCharsets.UTF_8)
        Files.writeString(request.commandLogFile, "fixture extractor command\n", StandardCharsets.UTF_8)
        Files.writeString(
            request.snapshot,
            """
            {
              "schemaVersion": 2,
              "targetMod": "fixture",
              "gameVersion": {
                "type": "$gameType",
                "modifier": "$gameModifier",
                "build": 159,
                "revision": 7
              },
              "contentCount": 0,
              "contents": []
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        RuntimeExtractorResult(0, false, Duration.ofMillis(10), listOf("fixture-extractor"))
    }

    private fun runtimeRequest(
        modJar: Path,
        serverJar: Path,
        source: Path?,
        output: Path,
    ): RuntimeConversionPipelineRequest = RuntimeConversionPipelineRequest(
        modJar = modJar,
        serverJar = serverJar,
        source = source,
        outputDirectory = output,
        modId = "fixture",
        runtimeTimeout = Duration.ofSeconds(5),
        serverValidationTimeout = Duration.ofSeconds(5),
        allowModExecution = true,
    )

    private fun runtimeModJar(name: String): Path {
        val jar = temporary.resolve(name)
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            mapOf(
                "mod.hjson" to "name: fixture\n",
                "mod/Registrar.class" to "fixture-bytecode",
            ).forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return jar
    }

    private fun successfulPreparedMapper(): RuntimeToDpMappingStage = RuntimeToDpMappingStage { _, _ ->
        val outputPath = "content/items/runtime-item.hjson"
        RuntimeToDpMappingResult(
            status = RuntimePipelineStageStatus.PASSED,
            summary = "fixture mapping",
            preparedConversion = RuntimePreparedConversion(
                files = listOf(
                    RuntimePreparedFile(
                        outputPath = outputPath,
                        sourcePaths = listOf("mod/Registrar.class"),
                        bytes = "color: ffffff\n".encodeToByteArray(),
                    ),
                ),
                contentResults = listOf(
                    RuntimePreparedContentResult(
                        sourceSymbol = "fixture-runtime-item",
                        kind = ContentKind.ITEM,
                        disposition = ContentDisposition.CONVERTED,
                        sourceType = "mindustry.type.Item",
                        targetType = "Item",
                        outputName = "dp-runtime-item",
                        outputPath = outputPath,
                        location = SourceLocation("mod/Registrar.class"),
                    ),
                ),
            ),
        )
    }

    private companion object {
        val ALLOW_TEST_SERVER = RuntimeServerJarPolicy { _, _ -> }
    }
}
