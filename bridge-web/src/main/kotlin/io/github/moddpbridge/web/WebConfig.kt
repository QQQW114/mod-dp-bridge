package io.github.moddpbridge.web

import java.net.InetSocketAddress
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.absolute

data class WebConfig(
    val host: String,
    val port: Int,
    val allowedHosts: Set<String>,
    val workDirectory: Path,
    val maxUploadBytes: Long,
    val maxExpandedMiB: Long,
    val maxArchiveEntries: Int,
    val maxConcurrentJobs: Int,
    val maxQueuedJobs: Int,
    val maxSseClients: Int,
    val retention: Duration,
    val serverJar: Path?,
    val serverTimeoutSeconds: Long,
    val javaCommand: String,
    val cliClasspath: String,
) {
    val address: InetSocketAddress get() = InetSocketAddress(host, port)

    companion object {
        private const val MIB = 1024L * 1024L

        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            systemProperties: Map<String, String> = System.getProperties().stringPropertyNames()
                .associateWith(System::getProperty),
        ): WebConfig {
            fun env(name: String): String? = environment[name]?.trim()?.takeIf(String::isNotEmpty)
            fun positiveInt(name: String, default: Int): Int = env(name)?.toIntOrNull()
                ?.takeIf { it > 0 } ?: default
            fun positiveLong(name: String, default: Long): Long = env(name)?.toLongOrNull()
                ?.takeIf { it > 0 } ?: default

            val work = Path.of(env("MOD_DP_BRIDGE_WORK_DIR") ?: "work/web-jobs").absolute().normalize()
            Files.createDirectories(work)

            val host = env("MOD_DP_BRIDGE_HOST") ?: "127.0.0.1"
            val allowedHosts = buildSet {
                add("localhost")
                add("127.0.0.1")
                add("::1")
                canonicalHost(host)?.takeUnless { it == "0.0.0.0" || it == "::" }?.let(::add)
                env("MOD_DP_BRIDGE_ALLOWED_HOSTS")
                    ?.split(',')
                    ?.mapNotNull(::canonicalHost)
                    ?.forEach(::add)
            }
            val configuredJar = env("MOD_DP_BRIDGE_SERVER_JAR")?.let { Path.of(it).absolute().normalize() }
            val javaHome = systemProperties["java.home"] ?: error("java.home is unavailable")
            val defaultJava = Path.of(
                javaHome,
                "bin",
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
            ).toString()

            return WebConfig(
                host = host,
                port = env("MOD_DP_BRIDGE_PORT")?.toIntOrNull()?.takeIf { it in 0..65535 } ?: 8080,
                allowedHosts = allowedHosts,
                workDirectory = work,
                maxUploadBytes = Math.multiplyExact(positiveLong("MOD_DP_BRIDGE_MAX_UPLOAD_MIB", 64), MIB),
                maxExpandedMiB = positiveLong("MOD_DP_BRIDGE_MAX_EXPANDED_MIB", 512),
                maxArchiveEntries = positiveInt("MOD_DP_BRIDGE_MAX_ARCHIVE_ENTRIES", 20_000),
                maxConcurrentJobs = positiveInt("MOD_DP_BRIDGE_MAX_CONCURRENT_JOBS", 1),
                maxQueuedJobs = positiveInt("MOD_DP_BRIDGE_MAX_QUEUED_JOBS", 8),
                // Keep at least eight HTTP workers free for uploads, status and downloads.
                maxSseClients = positiveInt("MOD_DP_BRIDGE_MAX_SSE_CLIENTS", 32).coerceAtMost(120),
                retention = Duration.ofHours(positiveLong("MOD_DP_BRIDGE_JOB_RETENTION_HOURS", 24)),
                serverJar = configuredJar,
                serverTimeoutSeconds = positiveLong("MOD_DP_BRIDGE_SERVER_TIMEOUT_SECONDS", 60),
                javaCommand = env("MOD_DP_BRIDGE_JAVA") ?: defaultJava,
                cliClasspath = normalizeClasspath(
                    env("MOD_DP_BRIDGE_CLI_CLASSPATH")
                        ?: systemProperties["java.class.path"]
                        ?: error("java.class.path is unavailable"),
                ),
            )
        }

        private fun normalizeClasspath(value: String): String {
            val launchDirectory = Path.of("").absolute().normalize()
            return value.split(File.pathSeparatorChar).joinToString(File.pathSeparator) { entry ->
                if (entry.isBlank()) launchDirectory.toString()
                else {
                    val wildcard = entry.endsWith('*')
                    val path = Path.of(if (wildcard) entry.dropLast(1) else entry)
                    val normalized = (if (path.isAbsolute) path else launchDirectory.resolve(path)).normalize().toString()
                    if (wildcard) normalized.trimEnd('/', '\\') + File.separator + "*" else normalized
                }
            }
        }

        internal fun canonicalHost(value: String): String? {
            var candidate = value.trim()
            if (candidate.isEmpty()) return null
            candidate = when {
                candidate.startsWith('[') -> candidate.substringAfter('[').substringBefore(']')
                candidate.count { it == ':' } == 1 && candidate.substringAfterLast(':').toIntOrNull() != null -> {
                    candidate.substringBeforeLast(':')
                }
                else -> candidate
            }
            return candidate.trim().trimEnd('.').lowercase().takeIf(String::isNotEmpty)
        }
    }
}
