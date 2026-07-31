package io.github.moddpbridge.web

import com.sun.net.httpserver.HttpExchange
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal data class UploadedFile(val path: Path, val originalName: String, val sizeBytes: Long)

internal class UploadException(val statusCode: Int, message: String) : Exception(message)

internal object MultipartUpload {
    private const val MAX_HEADER_LINE = 16 * 1024
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_MULTIPART_OVERHEAD = 1024L * 1024L

    fun receive(exchange: HttpExchange, inputDirectory: Path, maxFileBytes: Long): UploadedFile {
        val contentType = exchange.requestHeaders.getFirst("Content-Type")
            ?: throw UploadException(415, "Content-Type must be multipart/form-data.")
        val boundary = parseBoundary(contentType)
            ?: throw UploadException(415, "A valid multipart boundary is required.")
        if (boundary.length !in 1..200 || boundary.any { it == '\r' || it == '\n' }) {
            throw UploadException(400, "The multipart boundary is invalid.")
        }

        val contentLength = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        val requestLimit = Math.addExact(maxFileBytes, MAX_MULTIPART_OVERHEAD)
        if (contentLength != null && contentLength > requestLimit) {
            throw UploadException(413, "The upload exceeds the configured size limit.")
        }

        Files.createDirectories(inputDirectory)
        val limited = LimitedInputStream(exchange.requestBody, requestLimit)
        val input = PushbackInputStream(limited.buffered(64 * 1024), 64 * 1024 + boundary.length + 8)
        val opening = readAsciiLine(input)
        if (opening != "--$boundary") {
            throw UploadException(400, "Malformed multipart opening boundary.")
        }

        val headers = readHeaders(input)
        val disposition = headers["content-disposition"]
            ?: throw UploadException(400, "The upload part has no Content-Disposition header.")
        val field = dispositionParameter(disposition, "name")
        if (field != "file" && field != "mod") {
            throw UploadException(400, "The multipart file field must be named 'file'.")
        }
        val originalName = dispositionExtendedFilename(disposition)
            ?: dispositionParameter(disposition, "filename")?.let(::decodeHeaderFilename)
            ?: throw UploadException(400, "No file was selected.")
        if (originalName.isBlank()) throw UploadException(400, "No file was selected.")

        val safeName = sanitizeFilename(originalName)
        val destination = uniqueDestination(inputDirectory, safeName)
        try {
            Files.newOutputStream(
                destination,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { output ->
                copyUntilBoundary(input, output, "\r\n--$boundary".toByteArray(StandardCharsets.US_ASCII), maxFileBytes)
            }
            val suffix = readAsciiLine(input)
            if (suffix != "--") {
                throw UploadException(400, "Exactly one multipart file part is supported.")
            }
            val size = Files.size(destination)
            if (size == 0L) throw UploadException(400, "The uploaded file is empty.")
            return UploadedFile(destination, originalName, size)
        } catch (error: Throwable) {
            Files.deleteIfExists(destination)
            throw error
        }
    }

    private fun parseBoundary(contentType: String): String? {
        if (!contentType.substringBefore(';').trim().equals("multipart/form-data", ignoreCase = true)) return null
        return contentType.split(';').drop(1).firstNotNullOfOrNull { parameter ->
            val parts = parameter.trim().split('=', limit = 2)
            if (parts.size == 2 && parts[0].trim().equals("boundary", ignoreCase = true)) {
                parts[1].trim().removeSurrounding("\"")
            } else null
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readAsciiLine(input)
            total += line.length + 2
            if (total > MAX_HEADER_BYTES) throw UploadException(400, "Multipart headers are too large.")
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator <= 0) throw UploadException(400, "Malformed multipart header.")
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
    }

    private fun readAsciiLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) throw UploadException(400, "Unexpected end of multipart upload.")
            if (next == '\n'.code) break
            if (bytes.size() >= MAX_HEADER_LINE) throw UploadException(400, "Multipart header line is too long.")
            bytes.write(next)
        }
        val raw = bytes.toByteArray()
        val length = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
        return String(raw, 0, length, StandardCharsets.ISO_8859_1)
    }

    private fun dispositionParameter(value: String, name: String): String? {
        val pattern = Regex(
            "(?:^|;)\\s*${Regex.escape(name)}\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^;\\s]+))",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(value) ?: return null
        val quoted = match.groups[1]?.value
        return if (quoted != null) Regex("\\\\(.)").replace(quoted, "\$1") else match.groups[2]?.value
    }

    private fun dispositionExtendedFilename(value: String): String? {
        val match = Regex("(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(value)
            ?: return null
        val encoded = match.groupValues[1].trim().removePrefix("UTF-8''")
        return runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun decodeHeaderFilename(value: String): String {
        if (value.all { it.code < 0x80 }) return value
        val bytes = value.toByteArray(StandardCharsets.ISO_8859_1)
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrDefault(value)
    }

    private fun sanitizeFilename(original: String): String {
        val leaf = original.replace('\\', '/').substringAfterLast('/').trim()
        val sanitized = leaf.map { character ->
            when {
                character.code < 0x20 -> '_'
                character in "<>:\"/\\|?*" -> '_'
                else -> character
            }
        }.joinToString("").trim('.', ' ').take(180)
        return sanitized.ifBlank { "uploaded-mod.zip" }
    }

    private fun uniqueDestination(directory: Path, name: String): Path {
        var candidate = directory.resolve(name)
        var counter = 2
        while (Files.exists(candidate)) {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val extension = if (dot > 0) name.substring(dot) else ""
            candidate = directory.resolve("$stem-$counter$extension")
            counter++
        }
        return candidate
    }

    private fun copyUntilBoundary(input: PushbackInputStream, output: OutputStream, marker: ByteArray, maxBytes: Long): Long {
        require(marker.isNotEmpty())
        val buffer = ByteArray(64 * 1024)
        var carry = ByteArray(0)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) throw UploadException(400, "Multipart closing boundary was not found.")
            val combined = ByteArray(carry.size + read)
            carry.copyInto(combined)
            buffer.copyInto(combined, carry.size, 0, read)
            val boundaryAt = indexOf(combined, marker)
            if (boundaryAt >= 0) {
                written = writeLimited(output, combined, 0, boundaryAt, written, maxBytes)
                // Preserve bytes after the boundary for the closing-suffix parser.
                val remainderStart = boundaryAt + marker.size
                if (remainderStart < combined.size) {
                    input.unread(combined, remainderStart, combined.size - remainderStart)
                }
                return written
            }

            val safe = (combined.size - marker.size + 1).coerceAtLeast(0)
            if (safe > 0) written = writeLimited(output, combined, 0, safe, written, maxBytes)
            carry = combined.copyOfRange(safe, combined.size)
        }
    }

    private fun writeLimited(
        output: OutputStream,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        alreadyWritten: Long,
        limit: Long,
    ): Long {
        val updated = alreadyWritten + length
        if (updated > limit) throw UploadException(413, "The uploaded file exceeds the configured size limit.")
        output.write(bytes, offset, length)
        return updated
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (index in 0..haystack.size - needle.size) {
            for (needleIndex in needle.indices) {
                if (haystack[index + needleIndex] != needle[needleIndex]) continue@outer
            }
            return index
        }
        return -1
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var consumed = 0L

        override fun read(): Int {
            if (consumed >= limit) throw UploadException(413, "The upload exceeds the configured size limit.")
            val value = super.read()
            if (value >= 0) consumed++
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (consumed >= limit) throw UploadException(413, "The upload exceeds the configured size limit.")
            val allowed = minOf(length.toLong(), limit - consumed).toInt()
            val count = super.read(bytes, offset, allowed)
            if (count > 0) consumed += count
            return count
        }
    }

}
