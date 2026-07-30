package io.github.moddpbridge.converter

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DeterministicPackager {
    private val epoch = FileTime.fromMillis(0L)

    fun writeServerAssets(root: Path, files: List<PlannedOutputFile>) {
        Files.createDirectories(root)
        val normalizedRoot = root.toAbsolutePath().normalize()
        files.sortedBy { it.path }.forEach { file ->
            val output = normalizedRoot.resolve(file.path.replace('/', java.io.File.separatorChar)).normalize()
            require(output.startsWith(normalizedRoot) && output != normalizedRoot) {
                "Refusing to write outside server-assets: ${file.path}"
            }
            Files.createDirectories(output.parent)
            Files.write(
                output,
                file.bytes,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            runCatching { Files.setLastModifiedTime(output, epoch) }
        }
    }

    fun writeZip(output: Path, files: List<PlannedOutputFile>) {
        Files.createDirectories(output.parent)
        Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { stream ->
            ZipOutputStream(stream, StandardCharsets.UTF_8).use { zip ->
                zip.setLevel(Deflater.BEST_COMPRESSION)
                files.sortedBy { it.path }.forEach { file ->
                    val entry = ZipEntry(file.path)
                    entry.time = 0L
                    entry.lastModifiedTime = epoch
                    entry.lastAccessTime = epoch
                    entry.creationTime = epoch
                    zip.putNextEntry(entry)
                    zip.write(file.bytes)
                    zip.closeEntry()
                }
            }
        }
        runCatching { Files.setLastModifiedTime(output, epoch) }
    }

    fun treeHash(files: List<PlannedOutputFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.path }.forEach { file ->
            val path = file.path.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(path.size).array())
            digest.update(path)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(file.bytes.size.toLong()).array())
            digest.update(file.bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
