package io.github.moddpbridge.runtimeextractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the phase classification that decides which target-content fields become Data Patch
 * overrides. The four phases are written explicitly, because the difference between them is the
 * entire contract of the exporter.
 */
class VanillaOverrideExporterTest {
    private static final String TURRET = "mindustry.world.blocks.defense.turrets.ItemTurret";
    private static final String ITEM = "mindustry.type.Item";
    private static final String UNIT = "mindustry.type.UnitType";
    private static final String LIQUID = "mindustry.type.Liquid";
    private static final String CONVEYOR = "mindustry.world.blocks.distribution.Conveyor";

    @TempDir
    Path directory;

    /** Watched names the probe could not resolve; recorded in every phase, as the probe does. */
    private final List<Content> missingTargets = new ArrayList<>();

    @Test
    void exportsLoadTimeOverrideWithFinalValueAndIgnoresTargetDerivedFields() throws Exception {
        // health: the Mod changed it while declaring content. range: only the target's init() derived it.
        writePhases(
            content("block", "salvo", TURRET, "health", "100", "range", "10"),
            content("block", "salvo", TURRET, "health", "900", "range", "10"),
            content("block", "salvo", TURRET, "health", "900", "range", "12"),
            content("block", "salvo", TURRET, "health", "900", "range", "12")
        );

        VanillaOverrideExporter.Result result = VanillaOverrideExporter.export(directory);

        assertEquals(1, result.contents().size());
        VanillaOverrideExporter.ContentOverride content = result.contents().get(0);
        assertEquals("block", content.contentType());
        assertEquals("salvo", content.name());
        assertEquals(TURRET, content.runtimeClass());
        assertEquals(1, content.overrides().size());
        VanillaOverrideExporter.FieldOverride override = content.overrides().get(0);
        assertEquals("health", override.field());
        assertEquals("900", override.value());
        assertEquals("100", override.baseline());
        assertEquals(VanillaOverrideExporter.MOMENT_LOAD_TIME, override.moment());
        assertFalse(override.targetInitRecomputed());
        assertFalse(override.baselineAbsent());
        assertEquals(1, result.loadTimeOverrides());
        assertEquals(0, result.afterInitOverrides());
        assertEquals(2, result.watchedFields());
    }

    @Test
    void flagsLoadTimeOverrideThatTargetInitRewrote() throws Exception {
        writePhases(
            content("unit", "dagger", UNIT, "health", "150"),
            content("unit", "dagger", UNIT, "health", "400"),
            content("unit", "dagger", UNIT, "health", "150"),
            content("unit", "dagger", UNIT, "health", "150")
        );

        VanillaOverrideExporter.Result result = VanillaOverrideExporter.export(directory);

        VanillaOverrideExporter.FieldOverride override = result.contents().get(0).overrides().get(0);
        assertEquals(VanillaOverrideExporter.MOMENT_LOAD_TIME, override.moment());
        assertEquals("150", override.value());
        assertTrue(override.targetInitRecomputed());
        assertEquals(1, result.targetInitRecomputed());
    }

    @Test
    void exportsOverrideAppliedOnlyByModInit() throws Exception {
        writePhases(
            content("item", "copper", ITEM, "hardness", "1"),
            content("item", "copper", ITEM, "hardness", "1"),
            content("item", "copper", ITEM, "hardness", "1"),
            content("item", "copper", ITEM, "hardness", "4")
        );

        VanillaOverrideExporter.Result result = VanillaOverrideExporter.export(directory);

        VanillaOverrideExporter.FieldOverride override = result.contents().get(0).overrides().get(0);
        assertEquals(VanillaOverrideExporter.MOMENT_AFTER_INIT, override.moment());
        assertEquals("4", override.value());
        assertFalse(override.targetInitRecomputed());
        assertEquals(0, result.loadTimeOverrides());
        assertEquals(1, result.afterInitOverrides());
    }

    @Test
    void exportsNothingWhenOnlyTheTargetItselfChangedFields() throws Exception {
        writePhases(
            content("liquid", "water", LIQUID, "alpha", "0.5", "viscosity", "0.5"),
            content("liquid", "water", LIQUID, "alpha", "0.5", "viscosity", "0.5"),
            content("liquid", "water", LIQUID, "alpha", "0.7", "viscosity", "0.6"),
            content("liquid", "water", LIQUID, "alpha", "0.7", "viscosity", "0.6")
        );

        VanillaOverrideExporter.Result result = VanillaOverrideExporter.export(directory);

        assertEquals(0, result.contents().size());
        assertEquals(0, result.overrideCount());
        assertEquals(2, result.watchedFields());
    }

    @Test
    void reportsMissingTargetsAndMarksAbsentBaseline() throws Exception {
        missingTargets.add(missing("block", "ghost", "NOT_PRESENT_IN_TARGET"));
        writePhases(
            content("block", "salvo", TURRET),
            content("block", "salvo", TURRET, "ammoCapacity", "12"),
            content("block", "salvo", TURRET, "ammoCapacity", "12"),
            content("block", "salvo", TURRET, "ammoCapacity", "12")
        );

        VanillaOverrideExporter.Result result = VanillaOverrideExporter.export(directory);

        assertEquals(1, result.missing().size());
        assertEquals("ghost", result.missing().get(0).name());
        assertEquals("NOT_PRESENT_IN_TARGET", result.missing().get(0).reason());
        VanillaOverrideExporter.FieldOverride override = result.contents().get(0).overrides().get(0);
        assertEquals("ammoCapacity", override.field());
        assertTrue(override.baselineAbsent());
        assertEquals("12", override.value());
    }

    @Test
    void keepsTheRegistryNameSeparateFromTheDeclarationFieldName() throws Exception {
        // Mod source says Blocks.titaniumConveyor; the registry and every Data Patch key say
        // titanium-conveyor. Losing that distinction silently produced patches nobody applied.
        writePhases(
            declared("block", "titaniumConveyor", CONVEYOR, "titanium-conveyor", "health", "1"),
            declared("block", "titaniumConveyor", CONVEYOR, "titanium-conveyor", "health", "900"),
            declared("block", "titaniumConveyor", CONVEYOR, "titanium-conveyor", "health", "900"),
            declared("block", "titaniumConveyor", CONVEYOR, "titanium-conveyor", "health", "900")
        );

        VanillaOverrideExporter.Result result = VanillaOverrideExporter.export(directory);

        VanillaOverrideExporter.ContentOverride content = result.contents().get(0);
        assertEquals("titaniumConveyor", content.name());
        assertEquals("titanium-conveyor", content.runtimeName());
        assertEquals("DECLARATION_FIELD", content.resolution());
        assertTrue(
            VanillaOverrideExporter.toJson(result, "sfire-mod").contains("\"runtimeName\": \"titanium-conveyor\""),
            "artifact must carry the registry name"
        );
    }

    @Test
    void rejectsPhasesCapturedWithDifferentWatchLists() throws Exception {
        writePhases(
            content("item", "copper", ITEM),
            content("item", "copper", ITEM),
            content("item", "copper", ITEM),
            content("item", "copper", ITEM)
        );
        Files.writeString(
            RuntimeExtractorMain.vanillaPhaseFile(directory, VanillaOverrideExporter.PRE_CONTENT_INIT),
            renderPhase(
                VanillaOverrideExporter.PRE_CONTENT_INIT,
                "item:copper,lead",
                List.of(content("item", "copper", ITEM)),
                List.of()
            ),
            StandardCharsets.UTF_8
        );

        IOException error = assertThrows(IOException.class, () -> VanillaOverrideExporter.export(directory));
        assertTrue(error.getMessage().contains("watched name list"), error.getMessage());
    }

    @Test
    void rejectsUnknownRowMarkersAndWrongPhases() throws Exception {
        writePhases(
            content("item", "copper", ITEM),
            content("item", "copper", ITEM),
            content("item", "copper", ITEM),
            content("item", "copper", ITEM)
        );
        Path finalPhase = RuntimeExtractorMain.vanillaPhaseFile(directory, VanillaOverrideExporter.FINAL_AFTER_MOD_INIT);
        Files.writeString(finalPhase, "nonsense\trow\n", StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class, () -> VanillaOverrideExporter.export(directory));
        assertTrue(error.getMessage().contains("Unknown vanilla watch row marker"), error.getMessage());

        Files.writeString(
            finalPhase,
            renderPhase(
                VanillaOverrideExporter.PRE_CONTENT_INIT,
                "item:copper",
                List.of(content("item", "copper", ITEM)),
                List.of()
            ),
            StandardCharsets.UTF_8
        );
        IOException phaseError = assertThrows(IOException.class, () -> VanillaOverrideExporter.export(directory));
        assertTrue(phaseError.getMessage().contains("declares phase"), phaseError.getMessage());
    }

    @Test
    void requiresEveryPhaseFile() throws Exception {
        writePhases(
            content("item", "copper", ITEM),
            content("item", "copper", ITEM),
            content("item", "copper", ITEM),
            content("item", "copper", ITEM)
        );
        Files.delete(RuntimeExtractorMain.vanillaPhaseFile(directory, VanillaOverrideExporter.POST_CONTENT_INIT));

        IOException error = assertThrows(IOException.class, () -> VanillaOverrideExporter.export(directory));
        assertTrue(error.getMessage().contains("Missing vanilla watch phase file"), error.getMessage());
    }

    @Test
    void rendersJsonThatKeepsTypedValuesVerbatim() throws Exception {
        missingTargets.add(missing("block", "ghost", "NOT_PRESENT_IN_TARGET"));
        writePhases(
            content("block", "salvo", TURRET, "health", "100"),
            content("block", "salvo", TURRET, "health", "900"),
            content("block", "salvo", TURRET, "health", "900"),
            content("block", "salvo", TURRET, "health", "900")
        );

        String json = VanillaOverrideExporter.toJson(VanillaOverrideExporter.export(directory), "sfire-mod");

        assertTrue(json.contains("\"targetMod\": \"sfire-mod\""), json);
        assertTrue(json.contains("\"watchSpec\": \"block:ghost,salvo\""), json);
        assertTrue(json.contains("\"contentType\": \"block\""), json);
        assertTrue(json.contains("\"value\": 900"), json);
        assertTrue(json.contains("\"baseline\": 100"), json);
        assertTrue(json.contains("\"moment\": \"LOAD_TIME\""), json);
        assertTrue(json.contains("\"missingWatchTargets\": 1"), json);
        assertTrue(json.contains("\"overrideCount\": 1"), json);
    }

    @Test
    void describesTheSummary() throws Exception {
        writePhases(
            content("block", "salvo", TURRET, "health", "100"),
            content("block", "salvo", TURRET, "health", "900"),
            content("block", "salvo", TURRET, "health", "900"),
            content("block", "salvo", TURRET, "health", "900")
        );

        String summary = VanillaOverrideExporter.toSummary(VanillaOverrideExporter.export(directory));

        assertTrue(summary.contains("Watched 1 target content entries"), summary);
        assertTrue(summary.contains("overrides=1"), summary);
        assertTrue(summary.contains("loadTime=1"), summary);
        assertTrue(summary.contains("missingWatchTargets=0"), summary);
    }

    /** Writes exactly one content entry per probe phase, in capture order, under one shared watch list. */
    private void writePhases(Content baseline, Content preInit, Content postInit, Content finalPhase)
        throws IOException {
        List<Content> phases = List.of(baseline, preInit, postInit, finalPhase);
        String spec = specOf(phases, missingTargets);
        List<String> phaseNames = VanillaOverrideExporter.PHASES;
        for (int index = 0; index < phaseNames.size(); index++) {
            Files.writeString(
                RuntimeExtractorMain.vanillaPhaseFile(directory, phaseNames.get(index)),
                renderPhase(phaseNames.get(index), spec, List.of(phases.get(index)), missingTargets),
                StandardCharsets.UTF_8
            );
        }
    }

    private static String renderPhase(
        String phase,
        String spec,
        List<Content> contents,
        List<Content> missing
    ) {
        StringBuilder text = new StringBuilder();
        text.append("header\t1\t").append(phase).append('\t').append(encode(spec)).append('\n');
        for (Content content : contents) {
            text.append("content\t").append(content.type).append('\t').append(encode(content.name)).append('\t')
                .append(encode(content.runtimeName == null ? content.name : content.runtimeName)).append('\t')
                .append(encode(content.runtimeClass)).append('\t')
                .append(encode(content.resolution)).append('\n');
            for (Map.Entry<String, String> field : content.fields.entrySet()) {
                if (field.getValue() == null) continue;
                text.append("field\t").append(content.type).append('\t').append(encode(content.name)).append('\t')
                    .append(encode(field.getKey())).append('\t').append(encode(field.getValue())).append('\n');
            }
        }
        for (Content target : missing) {
            text.append("missing\t").append(target.type).append('\t').append(encode(target.name)).append('\t')
                .append(encode(target.missingReason)).append('\n');
        }
        return text.toString();
    }

    /** One content entry with the field values observed in one phase. */
    private static Content content(String type, String name, String runtimeClass, String... fieldValues) {
        Content content = new Content(type, name, runtimeClass);
        for (int index = 0; index < fieldValues.length; index += 2) {
            content.fields.put(fieldValues[index], fieldValues[index + 1]);
        }
        return content;
    }

    /** A content entry resolved through its declaration field, so the registry name differs. */
    private static Content declared(
        String type,
        String name,
        String runtimeClass,
        String runtimeName,
        String... fieldValues
    ) {
        Content content = content(type, name, runtimeClass, fieldValues);
        content.runtimeName = runtimeName;
        content.resolution = "DECLARATION_FIELD";
        return content;
    }

    /** A watched name the probe could not resolve; rendered as a {@code missing} row. */
    private static Content missing(String type, String name, String reason) {
        Content content = new Content(type, name, null);
        content.missingReason = reason;
        return content;
    }

    /** The canonical watch list the probe would have been given for these contents. */
    private static String specOf(List<Content> phases, List<Content> missing) {
        Map<String, Set<String>> byType = new LinkedHashMap<>();
        List<Content> all = new ArrayList<>(phases);
        all.addAll(missing);
        for (Content content : all) {
            byType.computeIfAbsent(content.type, key -> new LinkedHashSet<>()).add(content.name);
        }
        List<String> groups = new ArrayList<>();
        byType.forEach((type, names) -> {
            List<String> sorted = new ArrayList<>(names);
            sorted.sort(String::compareTo);
            groups.add(type + ":" + String.join(",", sorted));
        });
        groups.sort(String::compareTo);
        return String.join(";", groups);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Content {
        private final String type;
        private final String name;
        private final String runtimeClass;
        private final Map<String, String> fields = new LinkedHashMap<>();
        private String runtimeName;
        private String resolution = "REGISTRY_NAME";
        private String missingReason;

        Content(String type, String name, String runtimeClass) {
            this.type = type;
            this.name = name;
            this.runtimeClass = runtimeClass;
        }
    }
}
