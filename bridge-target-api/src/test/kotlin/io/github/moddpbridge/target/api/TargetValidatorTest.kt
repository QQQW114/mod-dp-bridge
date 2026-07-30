package io.github.moddpbridge.target.api

import io.github.moddpbridge.model.ConversionReport
import io.github.moddpbridge.model.ConversionResult
import io.github.moddpbridge.model.ConversionStatus
import io.github.moddpbridge.model.ReportSummary
import io.github.moddpbridge.model.SourceDescriptor
import io.github.moddpbridge.model.SourceKind
import io.github.moddpbridge.model.TargetDescriptor
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetValidatorTest {
    @Test
    fun `validator contract carries target options and result`() {
        val validator = RecordingValidator()
        val candidate = Path.of("sample.zip")
        val options = ValidationOptions(strictUnknownEntries = false)

        val result = validator.validate(candidate, options)

        assertEquals("test-target", validator.target.id)
        assertEquals(candidate, validator.lastCandidate)
        assertEquals(options, validator.lastOptions)
        assertEquals(ConversionStatus.PARTIAL, result.status)
        assertEquals(result.status, result.report.status)
    }

    private class RecordingValidator : TargetValidator {
        override val target = TargetDescriptor(
            id = "test-target",
            gameVersion = "test",
        )

        var lastCandidate: Path? = null
        var lastOptions: ValidationOptions? = null

        override fun validate(candidate: Path, options: ValidationOptions): ConversionResult {
            lastCandidate = candidate
            lastOptions = options

            val report = ConversionReport(
                target = target,
                source = SourceDescriptor(SourceKind.UNKNOWN, candidate.fileName.toString()),
                status = ConversionStatus.PARTIAL,
                summary = ReportSummary(),
            )
            return ConversionResult(report.status, report)
        }
    }
}
