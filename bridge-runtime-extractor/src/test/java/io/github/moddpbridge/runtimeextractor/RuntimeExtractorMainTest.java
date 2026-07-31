package io.github.moddpbridge.runtimeextractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeExtractorMainTest {
    @TempDir
    Path temp;

    @Test
    void detectsQuotedModNameFromArchiveMetadata() throws Exception {
        Path archive = temp.resolve("fixture.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("mod.hjson"));
            output.write("name: \"new-horizon\"\nmain: example.Main\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        assertEquals("new-horizon", RuntimeExtractorMain.detectModId(archive));
    }

    @Test
    void escapesJsonControlCharacters() {
        assertEquals("\"a\\\\b\\n\\\"c\"", RuntimeExtractorMain.quote("a\\b\n\"c"));
    }

    @Test
    void acceptsOnlyCompleteRuntimeTrackerData() {
        RuntimeExtractorMain.TrackerData tracker = new RuntimeExtractorMain.TrackerData(
            "installed",
            2,
            2,
            Map.of(
                "first", List.of("Example.first(Example.java:1)"),
                "second", List.of("Example.second(Example.java:2)")
            )
        );

        assertDoesNotThrow(() -> RuntimeExtractorMain.requireCompleteTracker(tracker, 2));
    }

    @Test
    void rejectsMissingRuntimeTrackerPhase() {
        RuntimeExtractorMain.TrackerData tracker = new RuntimeExtractorMain.TrackerData(
            "installed",
            null,
            1,
            Map.of("first", List.of("Example.first(Example.java:1)"))
        );

        assertThrows(
            IllegalStateException.class,
            () -> RuntimeExtractorMain.requireCompleteTracker(tracker, 1)
        );
    }

    @Test
    void rejectsRuntimeTrackerThatWasNotInstalled() {
        RuntimeExtractorMain.TrackerData tracker = new RuntimeExtractorMain.TrackerData(
            "missing",
            1,
            1,
            Map.of("first", List.of("Example.first(Example.java:1)"))
        );

        assertThrows(
            IllegalStateException.class,
            () -> RuntimeExtractorMain.requireCompleteTracker(tracker, 1)
        );
    }

    @Test
    void rejectsRuntimeTraceCountMismatch() {
        RuntimeExtractorMain.TrackerData tracker = new RuntimeExtractorMain.TrackerData(
            "installed",
            2,
            2,
            Map.of("first", List.of("Example.first(Example.java:1)"))
        );

        assertThrows(
            IllegalStateException.class,
            () -> RuntimeExtractorMain.requireCompleteTracker(tracker, 2)
        );
    }
}
