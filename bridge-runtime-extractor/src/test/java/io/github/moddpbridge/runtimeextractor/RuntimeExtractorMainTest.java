package io.github.moddpbridge.runtimeextractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
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
        RuntimeExtractorMain.TrackerData tracker = completeTracker(2);

        assertDoesNotThrow(() -> RuntimeExtractorMain.requireCompleteTracker(tracker, 2));
    }

    @Test
    void rejectsMissingRuntimeTrackerPhase() {
        RuntimeExtractorMain.TrackerData tracker = new RuntimeExtractorMain.TrackerData(
            "installed",
            null,
            1,
            1,
            Map.of("item\u0000first", List.of("Example.first(Example.java:1)")),
            Map.of("item\u0000first", "{}"),
            Map.of("item\u0000first", "{}"),
            Map.of("item\u0000first", "{}")
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
            1,
            Map.of("item\u0000first", List.of("Example.first(Example.java:1)")),
            Map.of("item\u0000first", "{}"),
            Map.of("item\u0000first", "{}"),
            Map.of("item\u0000first", "{}")
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
            2,
            Map.of("item\u0000first", List.of("Example.first(Example.java:1)")),
            Map.of("item\u0000first", "{}", "item\u0000second", "{}"),
            Map.of("item\u0000first", "{}", "item\u0000second", "{}"),
            Map.of("item\u0000first", "{}", "item\u0000second", "{}")
        );

        assertThrows(
            IllegalStateException.class,
            () -> RuntimeExtractorMain.requireCompleteTracker(tracker, 2)
        );
    }

    @Test
    void rejectsSnapshotKeysThatDoNotMatchRegistrationKeys() {
        RuntimeExtractorMain.TrackerData complete = completeTracker(1);
        RuntimeExtractorMain.TrackerData tracker = new RuntimeExtractorMain.TrackerData(
            complete.status(),
            complete.preContentInitCount(),
            complete.postContentInitCount(),
            complete.finalAfterModInitCount(),
            complete.registrationStacks(),
            Map.of("status\u0000different", "{}"),
            complete.postContentInitSnapshots(),
            complete.finalAfterModInitSnapshots()
        );

        assertThrows(
            IllegalStateException.class,
            () -> RuntimeExtractorMain.requireCompleteTracker(tracker, 1)
        );
    }

    @Test
    void acceptsContentRegisteredMonotonicallyAfterContentInit() {
        Map<String, String> pre = Map.of("item\u0000early", "{}");
        Map<String, String> post = Map.of(
            "item\u0000early", "{}",
            "item\u0000init", "{}"
        );
        Map<String, String> fin = Map.of(
            "item\u0000early", "{}",
            "item\u0000init", "{}",
            "item\u0000posted", "{}"
        );
        RuntimeExtractorMain.TrackerData tracker = new RuntimeExtractorMain.TrackerData(
            "installed",
            1,
            2,
            3,
            Map.of(
                "item\u0000early", List.of("Example.early(Example.java:1)"),
                "item\u0000init", List.of("Example.init(Example.java:2)"),
                "item\u0000posted", List.of("Example.posted(Example.java:3)")
            ),
            pre,
            post,
            fin
        );

        assertDoesNotThrow(() -> RuntimeExtractorMain.requireCompleteTracker(tracker, fin.keySet()));
    }

    @Test
    void rejectsContentRemovedBetweenRuntimePhases() {
        RuntimeExtractorMain.TrackerData tracker = new RuntimeExtractorMain.TrackerData(
            "installed",
            2,
            1,
            1,
            Map.of("item\u0000kept", List.of("Example.kept(Example.java:1)")),
            Map.of("item\u0000kept", "{}", "item\u0000removed", "{}"),
            Map.of("item\u0000kept", "{}"),
            Map.of("item\u0000kept", "{}")
        );

        assertThrows(
            IllegalStateException.class,
            () -> RuntimeExtractorMain.requireCompleteTracker(tracker, Set.of("item\u0000kept"))
        );
    }

    @Test
    void rejectsCombinedSnapshotArtifactsOverConfiguredBudget() throws Exception {
        Path first = temp.resolve("first.tsv");
        Path second = temp.resolve("second.tsv");
        Files.write(first, new byte[3]);
        Files.write(second, new byte[4]);

        assertDoesNotThrow(() -> RuntimeExtractorMain.requireSnapshotArtifactBudget(List.of(first, second), 7));
        assertThrows(
            IllegalStateException.class,
            () -> RuntimeExtractorMain.requireSnapshotArtifactBudget(List.of(first, second), 6)
        );
    }

    @Test
    void readsTypedSnapshotRowsByContentTypeAndName() throws Exception {
        Path file = temp.resolve("snapshots.tsv");
        Files.writeString(
            file,
            encode("status") + "\t" + encode("fixture-burning") + "\t" + encode("{\"phase\":\"PRE_CONTENT_INIT\"}") + "\n",
            StandardCharsets.UTF_8
        );

        assertEquals(
            "{\"phase\":\"PRE_CONTENT_INIT\"}",
            RuntimeExtractorMain.readSnapshotFile(file).get("status\u0000fixture-burning")
        );
    }

    private static RuntimeExtractorMain.TrackerData completeTracker(int count) {
        Map<String, List<String>> traces = count == 2
            ? Map.of(
                "item\u0000first", List.of("Example.first(Example.java:1)"),
                "item\u0000second", List.of("Example.second(Example.java:2)")
            )
            : Map.of("item\u0000first", List.of("Example.first(Example.java:1)"));
        Map<String, String> snapshots = count == 2
            ? Map.of("item\u0000first", "{}", "item\u0000second", "{}")
            : Map.of("item\u0000first", "{}");
        return new RuntimeExtractorMain.TrackerData(
            "installed",
            count,
            count,
            count,
            traces,
            snapshots,
            snapshots,
            snapshots
        );
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
