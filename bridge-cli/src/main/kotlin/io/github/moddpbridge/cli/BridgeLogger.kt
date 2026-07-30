package io.github.moddpbridge.cli

import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Writes every conversion event to both the console and an optional UTF-8 log file. */
class BridgeLogger(
    logFile: Path? = null,
) : Closeable {
    private val writer = logFile?.let { path ->
        path.parent?.let(Files::createDirectories)
        Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    @Synchronized
    fun info(message: String) = write("INFO", message)

    @Synchronized
    fun warn(message: String) = write("WARN", message)

    @Synchronized
    fun error(message: String) = write("ERROR", message)

    @Synchronized
    fun raw(message: String) {
        println(message)
        writer?.apply {
            write(message)
            newLine()
            flush()
        }
    }

    private fun write(level: String, message: String) {
        val timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        raw("[$timestamp] [$level] $message")
    }

    override fun close() {
        writer?.close()
    }
}
