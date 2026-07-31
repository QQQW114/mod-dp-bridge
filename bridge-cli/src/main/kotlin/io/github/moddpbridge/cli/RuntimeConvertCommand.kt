package io.github.moddpbridge.cli

import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.Callable
import picocli.CommandLine.Command
import picocli.CommandLine.Option

@Command(
    name = "runtime-convert",
    mixinStandardHelpOptions = true,
    description = [
        "Execute a trusted built Mod with the pinned official Mindustry server in separate JVMs and map observed Content to a v159.7 data pack.",
        "The runtime mapper emits Item, Liquid/CellLiquid and StatusEffect plus exact JAR assets;",
        "when --source is present, runtime/fallback-matched Block and Unit AST candidates are filtered by the official DataPatcher.",
        "Unsupported content and Java-only behavior remain explicit in runtime-mapping.json, hybrid-report.json and report.json.",
    ],
)
class RuntimeConvertCommand : Callable<Int> {
    @Option(
        names = ["--mod-jar"],
        required = true,
        paramLabel = "JAR",
        description = ["Built release Mod JAR. This is the authority for runtime Content and assets."],
    )
    lateinit var modJar: Path

    @Option(
        names = ["--source"],
        paramLabel = "DIRECTORY|ZIP",
        description = [
            "Optional matching source checkout/archive. Java is parsed without build/execution and may supply only runtime-confirmed Block/Unit candidates; JAR runtime state and assets remain authoritative.",
        ],
    )
    var source: Path? = null

    @Option(
        names = ["--server-jar"],
        required = true,
        paramLabel = "JAR",
        description = [
            "Pinned official Mindustry v159.7 server JAR. Its bytecode is executed while loading the Mod and validating the DP.",
        ],
    )
    lateinit var serverJar: Path

    @Option(
        names = ["--allow-mod-execution"],
        required = true,
        description = [
            "Required acknowledgement: both the supplied Mod and Server JAR execute with the current user's JVM/file/network permissions.",
        ],
    )
    var allowModExecution: Boolean = false

    @Option(
        names = ["-o", "--output"],
        paramLabel = "DIRECTORY",
        description = ["Output directory. Default: ./out/<mod-jar-name>-runtime"],
    )
    var output: Path? = null

    @Option(names = ["--overwrite"], description = ["Replace this command's retained top-level artifacts/logs."])
    var overwrite: Boolean = false

    @Option(names = ["--mod-id"], description = ["Override the internal Mod name when descriptor detection is ambiguous."])
    var modId: String? = null

    @Option(
        names = ["--runtime-timeout"],
        description = ["Timeout for source-Mod extraction, in seconds. Default: 120"],
    )
    var runtimeTimeoutSeconds: Long = 120

    @Option(
        names = ["--server-timeout"],
        description = [
            "Timeout for each post-mapping server-discovery and DataPatcher validation process, in seconds. Default: 30",
        ],
    )
    var serverTimeoutSeconds: Long = 30

    @Option(
        names = ["--hybrid-max-rounds"],
        description = [
            "Maximum DataPatcher candidate-filter rounds when --source is supplied. Default: 8",
        ],
    )
    var hybridMaxRounds: Int = 8

    override fun call(): Int {
        val normalizedModJar = modJar.toAbsolutePath().normalize()
        val normalizedServerJar = serverJar.toAbsolutePath().normalize()
        val normalizedSource = source?.toAbsolutePath()?.normalize()
        val outputDirectory = (output ?: defaultOutput(normalizedModJar)).toAbsolutePath().normalize()

        try {
            prepareRuntimeOutput(outputDirectory, overwrite)
        } catch (error: Throwable) {
            System.err.println("ERROR: ${error.message}")
            return 2
        }

        BridgeLogger(outputDirectory.resolve("logs/conversion.log")).use { logger ->
            logger.info("mod-dp-bridge local runtime pipeline started")
            logger.warn("SECURITY: trusted Mod and Server JAR bytecode will execute outside a security sandbox.")
            logger.info("Mod JAR: $normalizedModJar")
            logger.info("Server JAR: $normalizedServerJar")
            logger.info("Optional source: ${normalizedSource ?: "not supplied"}")
            logger.info("Output: $outputDirectory")

            return try {
                val result = RuntimeConversionPipeline().run(
                    RuntimeConversionPipelineRequest(
                        modJar = normalizedModJar,
                        serverJar = normalizedServerJar,
                        source = normalizedSource,
                        outputDirectory = outputDirectory,
                        modId = modId,
                        runtimeTimeout = Duration.ofSeconds(runtimeTimeoutSeconds),
                        serverValidationTimeout = Duration.ofSeconds(serverTimeoutSeconds),
                        allowModExecution = allowModExecution,
                        hybridMaxRounds = hybridMaxRounds,
                    ),
                    logger,
                )
                logger.info("Runtime pipeline status: ${result.status}")
                result.snapshot?.let { logger.info("Runtime snapshot: $it") }
                result.sourceIndexReport?.let { logger.info("Source index report: $it") }
                result.hybridReport?.let { logger.info("Hybrid source report: $it") }
                result.dpZip?.let { logger.info("DP ZIP: $it") }
                result.conversionReport?.let { logger.info("Conversion report: $it") }
                logger.info("Pipeline report: ${result.report}")
                if (result.status == "snapshotReady") {
                    logger.warn("No DP ZIP was produced because this snapshot schema/type set was not accepted by the mapper.")
                }
                result.exitCode
            } catch (error: Throwable) {
                logger.error("Unexpected runtime pipeline failure: ${error.stackTraceToString()}")
                70
            }
        }
    }

    private fun defaultOutput(modJar: Path): Path {
        val name = modJar.fileName?.toString()?.substringBeforeLast('.')?.ifBlank { "runtime-mod" } ?: "runtime-mod"
        return Path.of("out").resolve("$name-runtime")
    }

}

private val RUNTIME_TOP_LEVEL_ARTIFACTS = listOf(
    "runtime-snapshot.json",
    "runtime-pipeline.json",
    "source-index-report.json",
    "runtime-mapping.json",
    "hybrid-report.json",
    "report.json",
    "report.md",
    "failure-report.txt",
    "failure-diagnostics.json",
    "server-assets",
)

private val RUNTIME_LOG_ARTIFACTS = listOf(
    "conversion.log",
    "runtime-extractor.log",
    "runtime-extractor-command.txt",
    "server-asset-discovery.log",
    "data-patch-apply.log",
    "source-index-failure.txt",
    "hybrid-selection",
    "runtime-work",
)

/** Clears only artifacts owned by runtime-convert and never follows a symbolic link. */
internal fun prepareRuntimeOutput(output: Path, overwrite: Boolean) {
    if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
        require(!Files.isSymbolicLink(output)) { "Output directory may not be a symbolic link: $output" }
        require(Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
            "Output exists but is not a directory: $output"
        }
        val hasEntries = Files.list(output).use { stream -> stream.findAny().isPresent }
        require(overwrite || !hasEntries) {
            "Output directory is not empty: $output (use --overwrite to replace retained runtime artifacts)."
        }
    } else {
        Files.createDirectories(output)
    }

    if (overwrite) {
        RUNTIME_TOP_LEVEL_ARTIFACTS.forEach { name -> deleteRuntimeArtifact(output.resolve(name), output) }
        val logs = output.resolve("logs")
        RUNTIME_LOG_ARTIFACTS.forEach { name -> deleteRuntimeArtifact(logs.resolve(name), output) }
        Files.newDirectoryStream(output).use { entries ->
            entries.forEach { entry ->
                val name = entry.fileName?.toString().orEmpty()
                if (
                    name.endsWith("-dp-v159.7.zip", ignoreCase = true) &&
                    (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry))
                ) {
                    deleteRuntimeArtifact(entry, output)
                }
            }
        }
    }
    Files.createDirectories(output.resolve("logs"))
}

private fun deleteRuntimeArtifact(path: Path, outputRoot: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    val root = outputRoot.toAbsolutePath().normalize()
    val target = path.toAbsolutePath().normalize()
    require(target.startsWith(root) && target != root) { "Refusing to delete outside runtime output: $target" }
    if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(target)
        return
    }
    Files.walkFileTree(
        target,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
