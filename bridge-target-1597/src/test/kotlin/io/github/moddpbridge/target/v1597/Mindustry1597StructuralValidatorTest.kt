package io.github.moddpbridge.target.v1597

import io.github.moddpbridge.model.ConversionStatus
import io.github.moddpbridge.model.ValidationStage
import io.github.moddpbridge.model.ValidationStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Mindustry1597StructuralValidatorTest {
    @Test
    fun acceptsSupportedDirectoryButLeavesRuntimeUnverified() {
        val root = Files.createTempDirectory("dpbridge-validator")
        Files.createDirectories(root.resolve("content/items"))
        Files.writeString(root.resolve("content/items/test.hjson"), "{ color: ff0000 }")

        val result = Mindustry1597StructuralValidator().validate(root)

        assertEquals(ConversionStatus.PARTIAL, result.status)
        assertEquals(
            ValidationStatus.PASSED,
            result.report.validationStages.single { it.stage == ValidationStage.STRUCTURE }.status,
        )
        assertEquals(
            ValidationStatus.NOT_RUN,
            result.report.validationStages.single { it.stage == ValidationStage.RUNTIME }.status,
        )
    }

    @Test
    fun rejectsUnsupportedContentFolder() {
        val root = Files.createTempDirectory("dpbridge-validator-invalid")
        Files.createDirectories(root.resolve("content/planets"))
        Files.writeString(root.resolve("content/planets/test.hjson"), "{}")

        val result = Mindustry1597StructuralValidator().validate(root)

        assertEquals(ConversionStatus.REJECTED, result.status)
        assertTrue(result.report.diagnostics.any { it.code == "UNSUPPORTED_CONTENT_FOLDER" })
    }

    @Test
    fun rejectsSameContentBasenameAcrossDifferentTypes() {
        val root = Files.createTempDirectory("dpbridge-validator-content-collision")
        Files.createDirectories(root.resolve("content/items"))
        Files.createDirectories(root.resolve("content/blocks"))
        Files.writeString(root.resolve("content/items/shared.hjson"), "{ color: ff0000 }")
        Files.writeString(root.resolve("content/blocks/shared.hjson"), "{ type: Wall }")

        val result = Mindustry1597StructuralValidator().validate(root)

        assertEquals(ConversionStatus.REJECTED, result.status)
        assertTrue(result.report.diagnostics.any { it.code == "DUPLICATE_CONTENT_BASENAME" })
    }
}
