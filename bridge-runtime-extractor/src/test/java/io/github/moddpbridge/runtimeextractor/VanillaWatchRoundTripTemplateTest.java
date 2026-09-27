package io.github.moddpbridge.runtimeextractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Round-trips the real watch capture against the real exporter.
 *
 * The probe template writes the phase files and the exporter reads them back, so the column contract
 * between the two is a real interface. A hand-written fixture cannot catch a mismatch there; this test
 * compiles the shipped template and feeds its actual output to {@link VanillaOverrideExporter}.
 */
class VanillaWatchRoundTripTemplateTest {
    @TempDir
    Path temp;

    @Test
    void templateOutputIsReadableByTheExporter() throws Exception {
        Path serverJar = findOfficialServerJar();
        assumeTrue(
            serverJar != null,
            "Set -Dmoddpbridge.test.serverJar=<official-server.jar> to run the vanilla watch round trip."
        );

        Path sourceRoot = temp.resolve("source");
        Path classesRoot = temp.resolve("classes");
        Path vanillaDirectory = temp.resolve("vanilla-watch");
        Path supportSource = sourceRoot.resolve("bridgeprobe/RuntimeSnapshotSupport.java");
        Path fixtureSource = sourceRoot.resolve("bridgeprobe/VanillaWatchFixture.java");
        Files.createDirectories(supportSource.getParent());
        Files.createDirectories(classesRoot);
        Files.createDirectories(vanillaDirectory);

        try (InputStream input = RuntimeSnapshotSupportTemplateTest.class.getResourceAsStream(
            "/runtime-snapshot-support.java.template"
        )) {
            assertNotNull(input, "runtime snapshot support template");
            Files.write(supportSource, input.readAllBytes());
        }
        Files.writeString(fixtureSource, fixtureSource(), StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A full JDK is required for the vanilla watch round trip.");
        ByteArrayOutputStream compilerLog = new ByteArrayOutputStream();
        int compileExit = compiler.run(
            null,
            compilerLog,
            compilerLog,
            "-encoding", "UTF-8",
            "--release", "17",
            "-classpath", serverJar.toString(),
            "-d", classesRoot.toString(),
            supportSource.toString(),
            fixtureSource.toString()
        );
        assertEquals(0, compileExit, compilerLog.toString(StandardCharsets.UTF_8));

        String javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            isWindows() ? "java.exe" : "java"
        ).toString();
        Process process = new ProcessBuilder(
            javaExecutable,
            "-Dfile.encoding=UTF-8",
            "-Dmoddpbridge.runtimeVanillaWatch=item:fixture-vanilla-item,fixture-absent-item;block:fixture-vanilla-block",
            "-Dmoddpbridge.runtimeVanillaDir=" + vanillaDirectory,
            "-cp", classesRoot + File.pathSeparator + serverJar,
            "bridgeprobe.VanillaWatchFixture"
        ).redirectErrorStream(true).start();
        boolean exited = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) process.destroyForcibly();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(exited, "Vanilla watch fixture timed out.\n" + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("VANILLA_WATCH_FIXTURE_OK"), output);

        for (String phase : VanillaOverrideExporter.PHASES) {
            Path file = RuntimeExtractorMain.vanillaPhaseFile(vanillaDirectory, phase);
            assertTrue(Files.isRegularFile(file), "missing captured phase file: " + file);
        }
        VanillaOverrideExporter.Result result = VanillaOverrideExporter.export(vanillaDirectory);

        // The phase header carries the watch list exactly as the extractor supplied it, order included.
        assertEquals(
            "item:fixture-vanilla-item,fixture-absent-item;block:fixture-vanilla-block",
            result.watchSpec()
        );
        assertEquals(2, result.contents().size());
        VanillaOverrideExporter.ContentOverride content = result.contents().stream()
            .filter(entry -> entry.contentType().equals("item"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no item entry in " + result.contents()));
        assertEquals("fixture-vanilla-item", content.name());
        assertEquals("fixture-vanilla-item", content.runtimeName());
        assertEquals("mindustry.type.Item", content.runtimeClass());
        assertEquals("REGISTRY_NAME", content.resolution());

        // hardness was raised while declaring content and stayed put; cost moved at Mod init time only.
        VanillaOverrideExporter.FieldOverride hardness = content.overrides().stream()
            .filter(override -> override.field().equals("hardness"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no hardness override in " + content.overrides()));
        assertEquals("9", typedNumber(hardness.value()));
        assertEquals("3", typedNumber(hardness.baseline()));
        assertEquals(VanillaOverrideExporter.MOMENT_LOAD_TIME, hardness.moment());
        assertFalse(hardness.targetInitRecomputed());

        VanillaOverrideExporter.FieldOverride late = content.overrides().stream()
            .filter(override -> override.field().equals("cost"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no cost override in " + content.overrides()));
        assertEquals(VanillaOverrideExporter.MOMENT_AFTER_INIT, late.moment());
        // Item.cost is a float field, so the typed encoding keeps its float form.
        assertEquals("7.0", typedNumber(late.value()));

        // The block changed both health and size, but the target forbids patching size, so only health
        // may reach the patch. This is the difference between "the Mod changed it" and "a patch can say it".
        VanillaOverrideExporter.ContentOverride block = result.contents().stream()
            .filter(entry -> entry.contentType().equals("block"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no block entry in " + result.contents()));
        assertEquals("fixture-vanilla-block", block.runtimeName());
        assertEquals(
            List.of("health"),
            block.overrides().stream().map(VanillaOverrideExporter.FieldOverride::field).sorted().toList()
        );
        // Block.health is an int field in this target.
        assertEquals("500", typedNumber(block.overrides().get(0).value()));

        assertEquals(1, result.missing().size());
        assertEquals("fixture-absent-item", result.missing().get(0).name());
        assertEquals("NOT_PRESENT_IN_TARGET", result.missing().get(0).reason());
    }

    /** The typed runtime encoding is JSON; the fixture asserts on the raw string to stay reflection-free. */
    private static String typedNumber(String typedValue) {
        int start = typedValue.indexOf("\"value\":\"");
        assertTrue(start >= 0, typedValue);
        int valueStart = start + "\"value\":\"".length();
        return typedValue.substring(valueStart, typedValue.indexOf('"', valueStart));
    }

    private static Path findOfficialServerJar() {
        String configured = System.getProperty("moddpbridge.test.serverJar");
        List<Path> candidates = configured == null || configured.isBlank()
            ? List.of(
                Path.of("work/samples/downloads/mindustry-v160.5-server-release.jar"),
                Path.of("../work/samples/downloads/mindustry-v160.5-server-release.jar"),
                Path.of("work/mindustry-v159.7-server-release.jar"),
                Path.of("../work/mindustry-v159.7-server-release.jar")
            )
            : List.of(Path.of(configured));
        return candidates.stream()
            .map(path -> path.toAbsolutePath().normalize())
            .filter(Files::isRegularFile)
            .findFirst()
            .orElse(null);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String fixtureSource() {
        return """
            package bridgeprobe;

            import mindustry.Vars;
            import mindustry.core.ContentLoader;
            import mindustry.mod.Mods;
            import mindustry.type.Item;
            import mindustry.world.Block;

            import java.nio.file.Path;

            public final class VanillaWatchFixture {
                public static void main(String[] args) throws Exception {
                    Vars.content = new ContentLoader();
                    Vars.mods = new Mods();
                    RuntimeSnapshotSupport.initializeTrustedMetadata();

                    // Registers as ordinary (non-Mod) target content, exactly like vanilla content.
                    Item item = new Item("fixture-vanilla-item");
                    item.hardness = 3;
                    item.cost = 1f;

                    Block block = new Block("fixture-vanilla-block");
                    block.health = 100;
                    block.size = 1;

                    Path directory = Path.of(System.getProperty("moddpbridge.runtimeVanillaDir"));

                    // Mod load-time change plus a target-derived change, then a post-init Mod change.
                    write(directory, "BEFORE_MOD_CONTENT", "BEFORE_MOD_CONTENT");
                    item.hardness = 9;
                    item.explosiveness = 0.5f;
                    block.health = 500;
                    // Block.size is @NoPatch: the target refuses it as a patch, so it must never be
                    // captured as an override even though the Mod clearly changed it.
                    block.size = 3;
                    write(directory, "PRE_CONTENT_INIT", "PRE_CONTENT_INIT");
                    item.explosiveness = 0.75f;
                    write(directory, "POST_CONTENT_INIT", "POST_CONTENT_INIT");
                    item.cost = 7f;
                    write(directory, "FINAL_AFTER_MOD_INIT", "FINAL_AFTER_MOD_INIT");

                    System.out.println("VANILLA_WATCH_FIXTURE_OK");
                }

                private static void write(Path directory, String phase, String marker) throws Exception {
                    Path file = directory.resolve("vanilla-" + phase.toLowerCase().replace('_', '-') + ".tsv");
                    RuntimeSnapshotSupport.writeVanillaWatch(file, phase, null, marker);
                }
            }
            """;
    }
}
