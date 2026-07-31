package io.github.moddpbridge.sourceindex

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JarSourceIndexerTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `links authoritative jar classes and assets without adding source-only entries`() {
        val sourceZip = temp.resolve("source.zip")
        writeZip(
            sourceZip,
            mapOf(
                "project/src/example/Foo.java" to javaSource("example", "Foo", 30),
                "project/assets/sprites/icon.png" to byteArrayOf(1, 2, 3),
                "project/assets/sounds/changed.ogg" to byteArrayOf(4, 5, 6),
                "project/assets/sprites/source-only.png" to byteArrayOf(9),
            ),
        )
        val runtimeJar = temp.resolve("runtime.jar")
        writeZip(
            runtimeJar,
            mapOf(
                "example/Foo.class" to classBytes("example/Foo", "Foo.java", 7, 19),
                "example/Foo\$1.class" to classBytes("example/Foo\$1", "Foo.java", 20),
                "sprites/icon.png" to byteArrayOf(1, 2, 3),
                "sounds/changed.ogg" to byteArrayOf(7, 8),
                "maps/runtime-only.msav" to byteArrayOf(10),
                "classes.dex" to byteArrayOf(0x64, 0x65, 0x78),
                "mod.hjson" to "{name: fixture}".toByteArray(),
                "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
            ),
        )

        val index = JarSourceIndexer().index(runtimeJar, sourceZip)

        assertEquals(2, index.classes.size)
        val outer = index.classesByBinaryName.getValue("example.Foo")
        assertEquals(ClassSourceMatch.SOURCE_FILE, outer.match)
        assertEquals("project/src/example/Foo.java", outer.sourcePath)
        assertEquals(listOf(7, 19), outer.lineNumbers)
        assertEquals(7, outer.firstLine)
        assertEquals(19, outer.lastLine)
        assertEquals(30, outer.sourceLineCount)
        assertEquals(true, outer.sourceCoversRuntimeLines)

        val inner = index.classesByBinaryName.getValue("example.Foo\$1")
        assertEquals("Foo", inner.topLevelClassName)
        assertEquals(20, inner.lastLine)
        assertEquals(outer.sourcePath, inner.sourcePath)

        assertEquals(3, index.assets.size)
        val exact = index.assets.single { it.jarPath == "sprites/icon.png" }
        assertEquals(AssetSourceMatch.EXACT, exact.match)
        assertEquals("project/assets/sprites/icon.png", exact.sourcePath)
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", exact.sha256)
        assertEquals(exact.sha256, exact.sourceCandidates.single().sha256)

        val changed = index.assets.single { it.jarPath == "sounds/changed.ogg" }
        assertEquals(AssetSourceMatch.HASH_MISMATCH, changed.match)
        assertNull(changed.sourcePath)
        assertEquals(1, changed.sourceCandidates.size)

        val missing = index.assets.single { it.jarPath == "maps/runtime-only.msav" }
        assertEquals(AssetSourceMatch.NOT_FOUND, missing.match)
        assertTrue(missing.sourceCandidates.isEmpty())
        assertFalse(index.assets.any { it.jarPath == "sprites/source-only.png" })
        assertFalse(index.assets.any { it.jarPath == "mod.hjson" })

        assertEquals(2, index.summary.runtimeClassFiles)
        assertEquals(2, index.summary.parsedClassFiles)
        assertEquals(2, index.summary.matchedClassFiles)
        assertEquals(1, index.summary.matchedDistinctSourceFiles)
        assertEquals(3, index.summary.runtimeAssets)
        assertEquals(1, index.summary.exactAssetMatches)
        assertEquals(1, index.summary.changedAssetMatches)
        assertEquals(1, index.summary.missingAssetMatches)
        assertEquals(1.0, index.summary.classFileMatchRate)
        assertEquals(1.0 / 3.0, index.summary.exactAssetMatchRate)
        assertTrue(index.issues.isEmpty())
    }

    @Test
    fun `uses top-level class name only when SourceFile metadata is absent`() {
        val source = temp.resolve("checkout")
        source.resolve("src/example").createDirectories()
        source.resolve("src/example/NoDebug.java").writeText(
            "package example;\nclass NoDebug {}\n",
        )
        val jar = temp.resolve("nodebug.jar")
        writeZip(jar, mapOf("example/NoDebug\$Nested.class" to classBytes("example/NoDebug\$Nested", null, 2)))

        val linked = JarSourceIndexer().index(jar, source).classes.single()

        assertEquals(ClassSourceMatch.DERIVED_TOP_LEVEL_NAME, linked.match)
        assertNull(linked.sourceFileName)
        assertEquals("src/example/NoDebug.java", linked.sourcePath)
    }

    @Test
    fun `does not select an ambiguous source coordinate`() {
        val sourceZip = temp.resolve("duplicates.zip")
        writeZip(
            sourceZip,
            mapOf(
                "one/example/Foo.java" to javaSource("example", "Foo", 3),
                "two/example/Foo.java" to javaSource("example", "Foo", 3),
                "one/assets/icon.png" to byteArrayOf(1),
                "two/assets/icon.png" to byteArrayOf(1),
            ),
        )
        val jar = temp.resolve("duplicates.jar")
        writeZip(
            jar,
            mapOf(
                "example/Foo.class" to classBytes("example/Foo", "Foo.java", 2),
                "icon.png" to byteArrayOf(1),
            ),
        )

        val index = JarSourceIndexer().index(jar, sourceZip)

        val linkedClass = index.classes.single()
        assertEquals(ClassSourceMatch.AMBIGUOUS, linkedClass.match)
        assertNull(linkedClass.sourcePath)
        assertEquals(2, linkedClass.sourceCandidates.size)
        val linkedAsset = index.assets.single()
        assertEquals(AssetSourceMatch.AMBIGUOUS_EXACT, linkedAsset.match)
        assertNull(linkedAsset.sourcePath)
        assertEquals(2, linkedAsset.sourceCandidates.size)
    }

    private fun classBytes(
        internalName: String,
        sourceFile: String?,
        vararg lines: Int,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        if (sourceFile != null) writer.visitSource(sourceFile, null)
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "fixture", "()V", null, null)
        method.visitCode()
        lines.forEach { line ->
            val label = Label()
            method.visitLabel(label)
            method.visitLineNumber(line, label)
            method.visitInsn(Opcodes.NOP)
        }
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun javaSource(packageName: String, className: String, lines: Int): ByteArray = buildString {
        appendLine("package $packageName;")
        appendLine("class $className {")
        repeat((lines - 3).coerceAtLeast(0)) { appendLine("    // line") }
        append("}")
    }.toByteArray()

    private fun writeZip(path: Path, entries: Map<String, ByteArray>) {
        path.parent?.createDirectories()
        ZipOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }
}
