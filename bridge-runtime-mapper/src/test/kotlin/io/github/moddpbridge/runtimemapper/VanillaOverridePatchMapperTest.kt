package io.github.moddpbridge.runtimemapper

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class VanillaOverridePatchMapperTest {
    @TempDir
    lateinit var directory: Path

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun emitsPatchShapeTargetedAtTheVanillaContentName() {
        val result = mapArtifact("""{"contentType":"block","name":"salvo","overrides":[""" + number("health", "900") + """]}""")
        val patch = json.parseToJsonElement(String(result.patchBytes)).jsonObject

        assertEquals("vanilla-overrides", patch["name"]?.jsonPrimitive?.content)
        val health = patch["block"]!!.jsonObject["salvo"]!!.jsonObject["health"]!!.jsonPrimitive
        assertEquals("900", health.content)
        assertEquals(1, result.summary.patchedFields)
        assertEquals(1, result.summary.patchedContents)
        assertTrue(result.reductions.isEmpty(), result.reductions.toString())
    }

    @Test
    fun keepsEveryConvertibleScalarKind() {
        val overrides = listOf(
            number("health", "900"),
            """{"field":"armor","value":{"declaredType":"float","kind":"number","numberType":"float","value":"2.5"}}""",
            """{"field":"targetAir","value":{"declaredType":"boolean","kind":"boolean","value":true}}""",
            """{"field":"description","value":{"declaredType":"java.lang.String","kind":"string","value":"hi","truncated":false}}""",
            """{"field":"buildVisibility","value":{"declaredType":"mindustry.world.meta.BuildVisibility","kind":"enum","enumType":"x","value":"hidden"}}""",
            """{"field":"color","value":{"declaredType":"arc.graphics.Color","kind":"color","rgba":"ff0000ff"}}""",
            """{"field":"shootEffect","value":{"declaredType":"mindustry.entities.Effect","kind":"staticRef","owner":"mindustry.content.Fx","field":"shootSmall","parserValue":"shootSmall"}}""",
            """{"field":"requirements","value":{"declaredType":"arc.struct.Seq","kind":"array","values":[{"declaredType":"int","kind":"number","numberType":"int","value":"1"}],"truncated":false}}""",
            """{"field":"item","value":{"declaredType":"mindustry.type.Item","kind":"contentRef","contentType":"item","name":"copper","ownerMod":null}}""",
        ).joinToString(",")
        val result = mapArtifact("""{"contentType":"block","name":"salvo","overrides":[$overrides]}""")
        val fields = json.parseToJsonElement(String(result.patchBytes)).jsonObject["block"]!!
            .jsonObject["salvo"]!!.jsonObject

        assertEquals("900", fields["health"]!!.jsonPrimitive.content)
        assertEquals("2.5", fields["armor"]!!.jsonPrimitive.content)
        assertEquals("true", fields["targetAir"]!!.jsonPrimitive.content)
        assertEquals("hi", fields["description"]!!.jsonPrimitive.content)
        assertEquals("hidden", fields["buildVisibility"]!!.jsonPrimitive.content)
        assertEquals("ff0000ff", fields["color"]!!.jsonPrimitive.content)
        assertEquals("shootSmall", fields["shootEffect"]!!.jsonPrimitive.content)
        assertEquals("1", fields["requirements"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("copper", fields["item"]!!.jsonPrimitive.content)
        assertEquals(9, result.summary.patchedFields)
        assertTrue(result.reductions.isEmpty(), result.reductions.toString())
    }

    @Test
    fun dropsReferenceToContentThePatchDoesNotDeclare() {
        val result = VanillaOverridePatchMapper.map(
            artifact(
                """{"contentType":"block","name":"salvo","overrides":[""" +
                    """{"field":"planet","value":{"declaredType":"mindustry.type.Planet","kind":"contentRef","contentType":"planet","name":"sfire-mod-tiberia","ownerMod":"sfire-mod"}}""" +
                    """]}""",
            ),
        )

        assertEquals(0, result.summary.patchedFields)
        assertEquals(1, result.reductions.size)
        assertEquals(VanillaOverridePatchMapper.CODE_REFERENCED_CONTENT_NOT_MIGRATED, result.reductions.single().code)
        assertTrue(result.diagnostics.single().code == "VANILLA_OVERRIDE_REFERENCED_CONTENT_NOT_MIGRATED")
    }

    @Test
    fun keepsReferenceToMigratedModContent() {
        val result = VanillaOverridePatchMapper.map(
            artifact(
                """{"contentType":"block","name":"salvo","overrides":[""" +
                    """{"field":"item","value":{"declaredType":"mindustry.type.Item","kind":"contentRef","contentType":"item","name":"sfire-mod-chromium","ownerMod":"sfire-mod"}}""" +
                    """]}""",
            ),
            VanillaOverrideMappingOptions(migratedContentNames = setOf("sfire-mod-chromium")),
        )

        assertEquals(1, result.summary.patchedFields)
        assertTrue(result.reductions.isEmpty(), result.reductions.toString())
    }

    @Test
    fun neverEmitsCampaignPlacementFields() {
        val result = VanillaOverridePatchMapper.map(
            artifact(
                """{"contentType":"block","name":"salvo","overrides":[""" +
                    """{"field":"shownPlanets","value":{"declaredType":"arc.struct.Seq","kind":"array","values":[{"declaredType":"mindustry.type.Planet","kind":"contentRef","contentType":"planet","name":"serpulo","ownerMod":null}],"truncated":false}},""" +
                    """{"field":"requiredPlanets","value":{"declaredType":"arc.struct.Seq","kind":"array","values":[],"truncated":false}}""" +
                    """]}""",
            ),
        )

        val patch = json.parseToJsonElement(String(result.patchBytes)).jsonObject
        assertNull(patch["block"])
        assertFalse(String(result.patchBytes).contains("requiredPlanets"))
        assertFalse(String(result.patchBytes).contains("shownPlanets"))
        assertEquals(
            listOf(
                VanillaOverridePatchMapper.CODE_REFERENCED_CONTENT_NOT_MIGRATED,
                VanillaOverridePatchMapper.CODE_REFERENCED_CONTENT_NOT_MIGRATED,
            ),
            result.reductions.map { it.code },
        )
    }

    @Test
    fun dropsNestedObjectsAndNullsWithReasons() {
        val result = VanillaOverridePatchMapper.map(
            artifact(
                """{"contentType":"block","name":"salvo","overrides":[""" +
                    """{"field":"weapons","value":{"declaredType":"arc.struct.Seq","kind":"array","values":[{"declaredType":"mindustry.type.Weapon","kind":"opaque","reason":"UNSUPPORTED_RUNTIME_OBJECT","runtimeClass":"mindustry.type.Weapon","detail":null}],"truncated":false}},""" +
                    """{"field":"ammoTypes","value":{"declaredType":"arc.struct.ObjectMap","kind":"opaque","reason":"UNSUPPORTED_RUNTIME_OBJECT","runtimeClass":"arc.struct.ObjectMap","detail":null}},""" +
                    """{"field":"targetGround","value":{"declaredType":"boolean","kind":"null"}}""" +
                    """]}""",
            ),
        )

        assertEquals(0, result.summary.patchedFields)
        assertEquals(3, result.reductions.size)
        assertTrue(result.reductions.all { it.code == VanillaOverridePatchMapper.CODE_NESTED_OBJECT_OMITTED })
        assertEquals(
            listOf("weapons", "ammoTypes", "targetGround"),
            result.reductions.map { it.field },
        )
    }

    @Test
    fun dropsNonFiniteNumbersAndTruncatedStrings() {
        val result = VanillaOverridePatchMapper.map(
            artifact(
                """{"contentType":"item","name":"copper","overrides":[""" +
                    """{"field":"hardness","value":{"declaredType":"float","kind":"number","numberType":"float","value":"NaN"}},""" +
                    """{"field":"description","value":{"declaredType":"java.lang.String","kind":"string","value":"trunc","truncated":true}}""" +
                    """]}""",
            ),
        )

        assertEquals(0, result.summary.patchedFields)
        assertEquals(
            listOf(VanillaOverridePatchMapper.CODE_UNREPRESENTABLE_BY_TARGET_MODEL, VanillaOverridePatchMapper.CODE_VALUE_TRUNCATED),
            result.reductions.map { it.code },
        )
    }

    @Test
    fun rejectsUnsupportedSchemaVersions() {
        val path = directory.resolve("vanilla-overrides.json")
        Files.writeString(path, """{"schemaVersion": 99, "contents": []}""", StandardCharsets.UTF_8)

        assertThrows<VanillaOverrideMappingException> { VanillaOverridePatchMapper.map(path) }
    }

    @Test
    fun reportsSummaryCountsFromTheArtifact() {
        val path = artifact(
            """{"contentType":"block","name":"salvo","overrides":[""" + number("health", "900") + """]}""",
            extra = """,""" +
                """"missingWatchTargets":[{"contentType":"block","name":"ghost","reason":"NOT_PRESENT_IN_TARGET"}],""" +
                """"stats":{"overrideCount":1,"loadTimeOverrides":1,"afterInitOverrides":0,"targetInitRecomputed":7,"missingWatchTargets":1}""",
        )

        val result = VanillaOverridePatchMapper.map(path)

        assertEquals("sfire-mod", result.summary.targetMod)
        assertEquals(1, result.summary.observedContents)
        assertEquals(1, result.summary.observedFields)
        assertEquals(1, result.summary.loadTimeOverrides)
        assertEquals(0, result.summary.afterInitOverrides)
        assertEquals(7, result.summary.targetInitRecomputed)
        assertEquals(1, result.summary.missingWatchTargets)
        assertTrue(result.reportJson.contains("\"patchedFields\": 1"), result.reportJson)
        assertEquals(VanillaOverridePatchMapper.PATCH_PATH, result.patchPath)
    }

    private fun number(field: String, value: String) =
        """{"field":"$field","moment":"LOAD_TIME","targetInitRecomputed":false,"baselineAbsent":false,""" +
            """"value":{"declaredType":"int","kind":"number","numberType":"int","value":"$value"}}"""

    /** Writes an artifact containing a single content entry plus optional extra top-level members. */
    private fun mapArtifact(content: String): VanillaOverrideMappingResult =
        VanillaOverridePatchMapper.map(artifact(content))

    private fun artifact(content: String, extra: String = ""): Path {
        val path = directory.resolve("vanilla-overrides-${counter++}.json")
        Files.writeString(
            path,
            """{"schemaVersion": 1, "targetMod": "sfire-mod", "watchSpec": "block:salvo", "contents": [$content]$extra}""",
            StandardCharsets.UTF_8,
        )
        return path
    }

    private companion object {
        var counter = 0
    }
}
