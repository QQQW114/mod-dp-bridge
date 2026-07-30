package io.github.moddpbridge.target.v1597

import io.github.moddpbridge.model.DiagnosticSeverity
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Mindustry1597ContentApplyValidatorTest {
    @Test
    fun acceptsCompletedApplyAndReportsHeadlessExternalAssetLimit() {
        val root = Files.createTempDirectory("dpbridge-apply-protocol")
        val result = Mindustry1597ContentApplyValidator.parseOutput(
            lines = listOf(
                "DPBRIDGE_PROTOCOL\t1",
                "DPBRIDGE_ASSET\t${encoded("content/items/test.hjson")}\titem\tfalse\t0\t${encoded("dp-test")}\t${encoded("")}",
                "DPBRIDGE_RESULT\t3\t1\t0\t2\t0\t0\t1",
            ),
            exitCode = 0,
            timedOut = false,
            assetsRoot = root,
        )

        assertTrue(result.applyCompleted)
        assertTrue(result.passed)
        assertEquals(3, result.totalAssets)
        assertEquals(1, result.addedContent)
        assertTrue(result.diagnostics.any { it.code == "HEADLESS_EXTERNAL_ASSET_DECODE_NOT_RUN" })
        assertFalse(result.diagnostics.any { it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun turnsContentFailureAndWarningsIntoFileLinkedDiagnostics() {
        val root = Files.createTempDirectory("dpbridge-apply-protocol-failed")
        val result = Mindustry1597ContentApplyValidator.parseOutput(
            lines = listOf(
                "DPBRIDGE_PROTOCOL\t1",
                "DPBRIDGE_ASSET\t${encoded("content/blocks/broken.hjson")}\tblock\ttrue\t1\t${encoded("dp-broken")}\t${encoded("content.hasErrored, removed-from-content-registry")}",
                "DPBRIDGE_WARNING\t${encoded("content/blocks/broken.hjson")}\t${encoded("spawnUnit should be a string, but it is an object")}",
                "DPBRIDGE_RESULT\t1\t1\t0\t0\t1\t1\t0",
            ),
            exitCode = 10,
            timedOut = false,
            assetsRoot = root,
        )

        assertTrue(result.applyCompleted)
        assertFalse(result.passed)
        assertEquals(1, result.failedAssets)
        val failure = assertNotNull(result.diagnostics.singleOrNull { it.code == "DATA_PATCH_CONTENT_FAILED" })
        assertTrue(failure.location!!.path.endsWith("content${java.io.File.separator}blocks${java.io.File.separator}broken.hjson"))
        assertTrue(failure.details!!.contains("removed-from-content-registry"))
        assertTrue(result.diagnostics.any {
            it.code == "DATA_PATCH_APPLY_WARNING" && it.message.contains("spawnUnit should be a string")
        })
        assertFalse(result.diagnostics.any { it.code == "DATA_PATCH_PROCESS_FAILED" })
    }

    @Test
    fun rejectsMalformedOrIncompleteProtocol() {
        val root = Files.createTempDirectory("dpbridge-apply-protocol-malformed")
        val result = Mindustry1597ContentApplyValidator.parseOutput(
            lines = listOf(
                "DPBRIDGE_PROTOCOL\t1",
                "DPBRIDGE_RESULT\tnot-a-number\t0\t0\t0\t0\t0\t0",
            ),
            exitCode = 1,
            timedOut = false,
            assetsRoot = root,
        )

        assertFalse(result.applyCompleted)
        assertFalse(result.passed)
        assertTrue(result.diagnostics.any { it.code == "DATA_PATCH_PROTOCOL_MALFORMED" })
        assertTrue(result.diagnostics.any { it.code == "DATA_PATCH_APPLY_NOT_COMPLETED" })
        assertTrue(result.diagnostics.any { it.code == "DATA_PATCH_PROCESS_FAILED" })
    }

    @Test
    fun marksTimeoutWithoutClaimingApplyRan() {
        val root = Files.createTempDirectory("dpbridge-apply-protocol-timeout")
        val result = Mindustry1597ContentApplyValidator.parseOutput(
            lines = emptyList(),
            exitCode = null,
            timedOut = true,
            assetsRoot = root,
        )

        assertFalse(result.applyCompleted)
        assertFalse(result.passed)
        assertTrue(result.timedOut)
        assertTrue(result.diagnostics.any { it.code == "DATA_PATCH_APPLY_TIMEOUT" })
        assertTrue(result.diagnostics.any { it.code == "DATA_PATCH_PROTOCOL_NOT_STARTED" })
    }

    private fun encoded(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
