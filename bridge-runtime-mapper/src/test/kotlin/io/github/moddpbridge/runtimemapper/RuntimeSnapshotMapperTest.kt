package io.github.moddpbridge.runtimemapper

import io.github.moddpbridge.converter.ConvertedFileStatus
import io.github.moddpbridge.model.ContentDisposition
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuntimeSnapshotMapperTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `temporary rooted jar keeps exact provenance and claims remaining executables`() {
        val jar = jar(
            "Release/mod.hjson" to "name: fixture\n".encodeToByteArray(),
            "Release/example/RuntimeMod\$1.class" to byteArrayOf(1),
            "Release/example/RuntimeMod.class" to byteArrayOf(2),
            "Release/classes.dex" to byteArrayOf(3),
            "Release/scripts/runtime.js" to "// executed script".encodeToByteArray(),
            "Release/sprites/fixture-icon.png" to byteArrayOf(4, 5, 6),
            "Release/sprites/unit/ground-unit/annihilation/weapons/large-launcher.png" to byteArrayOf(7),
            "Release/sprites/unit/weapons/large-launcher.png" to byteArrayOf(8),
        )
        val snapshot = snapshot(
            runtimeClass = "example.RuntimeMod\$1",
            registrationStack = listOf("example.RuntimeMod\$1.<init>(RuntimeMod.java:10)"),
        )

        val result = RuntimeSnapshotMapper.prepare(snapshot, jar)

        assertEquals(1, result.summary.convertedContents)
        val content = result.prepared.files.single { it.outputPath == "content/items/alloy.hjson" }
        assertEquals(listOf("Release/example/RuntimeMod\$1.class"), content.sourcePaths)
        val sprite = result.prepared.files.single { it.outputPath == "sprites/fixture-icon.png" }
        assertEquals(listOf("Release/sprites/fixture-icon.png"), sprite.sourcePaths)
        val collisionWinner = result.prepared.files.single { it.outputPath.endsWith("large-launcher.png") }
        assertEquals("sprites/unit/weapons/large-launcher.png", collisionWinner.outputPath)
        assertEquals(listOf("Release/sprites/unit/weapons/large-launcher.png"), collisionWinner.sourcePaths)
        assertTrue(
            result.prepared.diagnostics.any {
                it.code == "V159_SPRITE_BASENAME_LAST_WINS" &&
                    it.severity.name == "WARNING" &&
                    it.details.orEmpty().contains("bytesDifferent=true")
            },
        )
        assertEquals(
            ConvertedFileStatus.EXCLUDED,
            result.prepared.fileResults.single { it.sourcePath == "Release/example/RuntimeMod.class" }.status,
        )
        assertEquals(
            ConvertedFileStatus.UNSUPPORTED,
            result.prepared.fileResults.single { it.sourcePath == "Release/classes.dex" }.status,
        )
        assertEquals(
            ConvertedFileStatus.UNSUPPORTED,
            result.prepared.fileResults.single { it.sourcePath == "Release/scripts/runtime.js" }.status,
        )
        assertFalse(result.prepared.fileResults.any { it.sourcePath.endsWith("RuntimeMod\$1.class") })
        assertTrue(content.bytes!!.decodeToString().contains("\"hardness\": 7"))
    }

    @Test
    fun `descriptor alone is not accepted as generated content provenance`() {
        val jar = jar(
            "Release/mod.hjson" to "name: fixture\n".encodeToByteArray(),
            "Release/example/Unrelated.class" to byteArrayOf(1),
        )
        val snapshot = snapshot(
            runtimeClass = "mindustry.type.Item",
            registrationStack = listOf("mindustry.type.Item.<init>(Item.java:52)"),
        )

        val result = RuntimeSnapshotMapper.prepare(
            snapshot,
            jar,
            RuntimeSnapshotMappingOptions(includeAssets = false),
        )

        assertEquals(ContentDisposition.FAILED, result.prepared.contentResults.single().disposition)
        assertTrue(result.prepared.diagnostics.any { it.code == "RUNTIME_CONTENT_PROVENANCE_MISSING" })
        assertFalse(result.prepared.files.any { it.outputPath.startsWith("content/") })
        assertNotNull(result.prepared.fileResults.singleOrNull { it.sourcePath == "Release/example/Unrelated.class" })
    }

    @Test
    fun `content first registered in final phase maps from the earliest available snapshot`() {
        val jar = jar("example/LateItem.class" to byteArrayOf(1, 2, 3))
        val snapshot = snapshot(
            runtimeClass = "example.LateItem",
            registrationStack = listOf("example.LateItem.<init>(LateItem.java:12)"),
            availablePhases = setOf("finalAfterModInit"),
        )

        val result = RuntimeSnapshotMapper.prepare(
            snapshot,
            jar,
            RuntimeSnapshotMappingOptions(includeAssets = false),
        )

        assertEquals(1, result.summary.generatedContentFiles)
        assertEquals(ContentDisposition.DEGRADED, result.prepared.contentResults.single().disposition)
        assertTrue(result.prepared.diagnostics.any { it.code == "RUNTIME_CONTENT_REGISTERED_LATE" })
        assertTrue(result.prepared.files.single().reason.contains("FINAL_AFTER_MOD_INIT"))
    }

    @Test
    fun `content may not disappear between present runtime phases`() {
        val jar = jar("example/UnstableItem.class" to byteArrayOf(1))
        val snapshot = snapshot(
            runtimeClass = "example.UnstableItem",
            registrationStack = listOf("example.UnstableItem.<init>(UnstableItem.java:8)"),
            availablePhases = setOf("preContentInit", "finalAfterModInit"),
        )

        val result = RuntimeSnapshotMapper.prepare(
            snapshot,
            jar,
            RuntimeSnapshotMappingOptions(includeAssets = false),
        )

        assertEquals(ContentDisposition.FAILED, result.prepared.contentResults.single().disposition)
        assertTrue(result.prepared.diagnostics.any { it.code == "RUNTIME_PHASE_PRESENCE_NON_MONOTONIC" })
    }

    private fun snapshot(
        runtimeClass: String,
        registrationStack: List<String>,
        availablePhases: Set<String> = setOf("preContentInit", "postContentInit", "finalAfterModInit"),
    ): Path {
        val fields = itemFields()
        fun phase(name: String): JsonObject = buildJsonObject {
            put("schemaVersion", 1)
            put("phase", name)
            put("name", "fixture-alloy")
            put("contentType", "item")
            put("runtimeClass", runtimeClass)
            put("classAncestry", buildJsonArray { add(JsonPrimitive(runtimeClass)) })
            put("sourceClassMapFallback", buildJsonObject {
                put("parserName", "Item")
                put("runtimeClass", "mindustry.type.Item")
                put("loadableRoot", true)
            })
            put("fields", fields)
            put("customFields", buildJsonArray {})
            put("overriddenMethods", buildJsonArray {})
            put("customOnlyMethods", buildJsonArray {})
            put("declaredLosses", buildJsonArray {})
        }
        val content = buildJsonObject {
            put("name", "fixture-alloy")
            put("contentType", "item")
            put("runtimeClass", runtimeClass)
            put("modName", "fixture")
            put("registrationStack", buildJsonArray { registrationStack.forEach { add(JsonPrimitive(it)) } })
            put("runtimeSnapshots", buildJsonObject {
                if ("preContentInit" in availablePhases) put("preContentInit", phase("PRE_CONTENT_INIT"))
                if ("postContentInit" in availablePhases) put("postContentInit", phase("POST_CONTENT_INIT"))
                if ("finalAfterModInit" in availablePhases) put("finalAfterModInit", phase("FINAL_AFTER_MOD_INIT"))
            })
        }
        val root = buildJsonObject {
            put("schemaVersion", 2)
            put("targetMod", "fixture")
            put("gameVersion", buildJsonObject {
                put("build", 159)
                put("revision", 7)
            })
            put("loadedMod", buildJsonObject { put("name", "fixture") })
            put("contentCount", 1)
            put("contents", buildJsonArray { add(content) })
        }
        return temp.resolve("snapshot-${Files.list(temp).use { it.count() }}.json").also {
            Files.writeString(it, Json { prettyPrint = true }.encodeToString(root))
        }
    }

    private fun itemFields(): JsonObject = buildJsonObject {
        put("color", color("1234abcd"))
        listOf(
            "explosiveness" to "0.25", "flammability" to "0.0", "radioactivity" to "0.0",
            "charge" to "0.0", "cost" to "2.5", "healthScaling" to "0.0", "frameTime" to "4.0",
        ).forEach { (name, value) -> put(name, number("float", value)) }
        listOf("hardness" to "7", "frames" to "3", "transitionFrames" to "1")
            .forEach { (name, value) -> put(name, number("int", value)) }
        put("lowPriority", bool(false))
        put("buildable", bool(true))
        put("hidden", bool(false))
    }

    private fun color(value: String): JsonElement = typed("arc.graphics.Color", "color", "rgba", value)
    private fun number(type: String, value: String): JsonElement = buildJsonObject {
        put("declaredType", type)
        put("kind", "number")
        put("numberType", type)
        put("value", value)
    }
    private fun bool(value: Boolean): JsonElement = buildJsonObject {
        put("declaredType", "boolean")
        put("kind", "boolean")
        put("value", value)
    }
    private fun typed(declaredType: String, kind: String, field: String, value: String): JsonElement = buildJsonObject {
        put("declaredType", declaredType)
        put("kind", kind)
        put(field, value)
    }

    private fun jar(vararg entries: Pair<String, ByteArray>): Path = temp.resolve("fixture.jar").also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
