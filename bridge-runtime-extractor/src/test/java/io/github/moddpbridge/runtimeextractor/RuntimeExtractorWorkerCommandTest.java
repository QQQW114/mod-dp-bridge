package io.github.moddpbridge.runtimeextractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the child-JVM command line. A system property placed after the main class is rejected by the
 * worker as an unexpected argument, which only shows up after a full headless launch.
 */
class RuntimeExtractorWorkerCommandTest {
    @TempDir
    Path directory;

    @Test
    void placesEverySystemPropertyBeforeTheClassPath() {
        List<String> command = build("block:salvo;item:copper");

        int classPath = command.indexOf("-cp");
        assertTrue(classPath > 0, command.toString());
        List<String> properties = command.subList(0, classPath).stream().filter(it -> it.startsWith("-D")).toList();
        assertTrue(
            properties.stream().anyMatch(it -> it.startsWith("-Dmoddpbridge.runtimeVanillaWatch=")),
            properties.toString()
        );
        assertTrue(
            properties.stream().anyMatch(it -> it.startsWith("-Dmoddpbridge.runtimeVanillaBeforeModContentFile=")),
            properties.toString()
        );
        assertTrue(
            properties.stream().anyMatch(it -> it.startsWith("-Dmoddpbridge.runtimeVanillaFinalFile=")),
            properties.toString()
        );
        assertTrue(
            command.subList(classPath, command.size()).stream().noneMatch(it -> it.startsWith("-D")),
            command.toString()
        );
    }

    @Test
    void passesWorkerOptionsAfterTheMainClass() {
        List<String> command = build("block:salvo");
        int mainClass = command.indexOf(RuntimeExtractorMain.class.getName());
        assertTrue(mainClass > 0, command.toString());
        assertEquals("worker", command.get(mainClass + 1), command.toString());

        List<String> workerArguments = command.subList(mainClass + 1, command.size());
        assertTrue(workerArguments.contains("--vanilla-dir"), workerArguments.toString());
        assertTrue(workerArguments.contains("--vanilla-overrides-output"), workerArguments.toString());
        assertTrue(workerArguments.contains("--mod-id"), workerArguments.toString());
    }

    @Test
    void omitsEveryVanillaOptionWhenNoWatchListIsRequested() {
        List<String> command = build(null);

        assertFalse(command.stream().anyMatch(it -> it.contains("runtimeVanilla")), command.toString());
        assertFalse(command.contains("--vanilla-dir"), command.toString());
        assertFalse(command.contains("--vanilla-overrides-output"), command.toString());
        // The ordinary worker options must survive unchanged.
        assertTrue(command.contains("--final-snapshot-file"), command.toString());
    }

    @Test
    void treatsABlankWatchListAsDisabled() {
        List<String> command = build("   ");

        assertFalse(command.stream().anyMatch(it -> it.contains("runtimeVanilla")), command.toString());
        assertFalse(command.contains("--vanilla-dir"), command.toString());
    }

    private List<String> build(String watchSpec) {
        return RuntimeExtractorMain.buildWorkerCommand(
            "java",
            "server.jar;extractor.jar",
            1024L,
            "fixture-mod",
            directory.resolve("out/content-runtime.json"),
            directory.resolve("mod.jar"),
            directory.resolve("trace.tsv"),
            directory.resolve("phase.tsv"),
            directory.resolve("status.txt"),
            directory.resolve("pre.tsv"),
            directory.resolve("post.tsv"),
            directory.resolve("final.tsv"),
            256L * 1024L * 1024L,
            watchSpec,
            directory.resolve("vanilla-watch"),
            directory.resolve("vanilla-watch-phases.tsv"),
            directory.resolve("out/vanilla-overrides.json")
        );
    }
}
