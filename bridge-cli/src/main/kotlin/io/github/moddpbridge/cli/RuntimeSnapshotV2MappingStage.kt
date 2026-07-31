package io.github.moddpbridge.cli

import io.github.moddpbridge.runtimemapper.RuntimeSnapshotMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Production mapper for the currently supported typed runtime snapshot schema. */
internal object RuntimeSnapshotV2MappingStage : RuntimeToDpMappingStage {
    override fun map(input: RuntimeToDpMappingInput, logger: BridgeLogger): RuntimeToDpMappingResult {
        if (input.snapshot.schemaVersion != 2) {
            val summary = "Runtime snapshot schema ${input.snapshot.schemaVersion} is not supported by the v159.7 mapper."
            logger.warn(summary)
            return RuntimeToDpMappingResult(
                status = RuntimePipelineStageStatus.NOT_RUN,
                summary = summary,
                metadata = mapOf("supportedSnapshotSchema" to "2"),
            )
        }

        logger.info("Mapping the earliest available typed registration snapshot and exact release-JAR assets to inert v159.7 declarations.")
        val result = RuntimeSnapshotMapper.prepare(input.snapshot.path, input.modJar)
        val report = input.outputDirectory.resolve("runtime-mapping.json")
        Files.writeString(report, result.reportJson, StandardCharsets.UTF_8)
        result.prepared.logs.forEach(logger::info)

        val summary = result.summary
        val text = "Generated ${summary.generatedContentFiles} Item/Liquid/Status declarations and selected " +
            "${summary.selectedAssetFiles} assets; converted=${summary.convertedContents}, " +
            "degraded=${summary.degradedContents}, unsupported=${summary.unsupportedContents}, " +
            "failed=${summary.failedContents}, droppedFields=${summary.droppedFields}."
        val stageSummary = if (summary.generatedContentFiles == 0) {
            "$text The empty runtime declaration baseline is retained so an optional --source " +
                "Block/Unit hybrid stage can add runtime-confirmed declarations."
        } else {
            text
        }
        if (summary.generatedContentFiles == 0) logger.warn(stageSummary) else logger.info(stageSummary)
        return RuntimeToDpMappingResult(
            status = RuntimePipelineStageStatus.PASSED,
            summary = stageSummary,
            preparedConversion = result.prepared,
            mappingReport = report,
            metadata = metadata(summary) + mapOf(
                "runtimeDeclarationBaseline" to if (summary.generatedContentFiles == 0) "empty" else "nonEmpty",
            ),
        )
    }

    private fun metadata(summary: io.github.moddpbridge.runtimemapper.RuntimeSnapshotMappingSummary): Map<String, String> =
        mapOf(
            "snapshotSchemaVersion" to summary.snapshotSchemaVersion.toString(),
            "targetMod" to summary.targetMod,
            "observedContents" to summary.observedContents.toString(),
            "generatedContentFiles" to summary.generatedContentFiles.toString(),
            "convertedContents" to summary.convertedContents.toString(),
            "degradedContents" to summary.degradedContents.toString(),
            "unsupportedContents" to summary.unsupportedContents.toString(),
            "failedContents" to summary.failedContents.toString(),
            "selectedAssetFiles" to summary.selectedAssetFiles.toString(),
            "mappedFields" to summary.mappedFields.toString(),
            "droppedFields" to summary.droppedFields.toString(),
        )
}
