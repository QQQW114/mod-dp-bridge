package io.github.moddpbridge.target.api

import io.github.moddpbridge.model.ConversionResult
import io.github.moddpbridge.model.TargetDescriptor
import java.nio.file.Path

/**
 * Validates a converted data-pack candidate against one concrete Mindustry target.
 *
 * Implementations may perform only a subset of validation stages, but they must expose
 * every omitted stage as NOT_RUN in the returned report rather than implying success.
 */
interface TargetValidator {
    val target: TargetDescriptor

    fun validate(
        candidate: Path,
        options: ValidationOptions = ValidationOptions(),
    ): ConversionResult
}

/** Options shared by target-specific structural validators. */
data class ValidationOptions(
    /** Reject files and directories outside the target's documented roots. */
    val strictUnknownEntries: Boolean = true,
    /** Ignore common archive/OS metadata with a warning instead of rejecting it. */
    val allowKnownMetadata: Boolean = true,
    /** Detect target namespace collisions even when source paths differ. */
    val detectDuplicateBasenames: Boolean = true,
)
