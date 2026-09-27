package io.github.moddpbridge.runtimeextractor;

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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Classifies target (vanilla) content fields captured by the runtime probe into explicit overrides.
 *
 * <p>The probe captures four phases of one JVM run. A Mod can mutate target content at two distinct
 * moments and the target's own {@code Content#init()} runs between them, so each phase means
 * something different:</p>
 *
 * <ul>
 *   <li>{@code BEFORE_MOD_CONTENT} - pristine target content, captured before any Mod code ran.</li>
 *   <li>{@code PRE_CONTENT_INIT} - after Mod content declaration, before the target initialized
 *       itself. A field that differs from the baseline here was mutated by Mod load-time code such as
 *       {@code Mod#loadContent()}.</li>
 *   <li>{@code POST_CONTENT_INIT} - after the target's own {@code init()}/{@code postInit()} pass.</li>
 *   <li>{@code FINAL_AFTER_MOD_INIT} - after every {@code Mod#init()}. A field that differs from
 *       {@code POST_CONTENT_INIT} here was mutated by Mod initialization code.</li>
 * </ul>
 *
 * <p>A field becomes an override when either Mod moment changed it. The exported value is always the
 * {@code FINAL_AFTER_MOD_INIT} value, because that is what the running Mod actually plays with; Data
 * Patch patches are applied after target initialization, so writing the final value reproduces the
 * observed behavior. Fields only the target itself derived are never exported, which is what keeps
 * target-initialization noise out of the patch.</p>
 *
 * <p>Deliberately free of Arc/Mindustry types so it stays unit-testable.</p>
 */
final class VanillaOverrideExporter {
    static final String BEFORE_MOD_CONTENT = "BEFORE_MOD_CONTENT";
    static final String PRE_CONTENT_INIT = "PRE_CONTENT_INIT";
    static final String POST_CONTENT_INIT = "POST_CONTENT_INIT";
    static final String FINAL_AFTER_MOD_INIT = "FINAL_AFTER_MOD_INIT";

    /** Probe phases in capture order. The last phase is the exported value's source of truth. */
    static final List<String> PHASES = List.of(
        BEFORE_MOD_CONTENT,
        PRE_CONTENT_INIT,
        POST_CONTENT_INIT,
        FINAL_AFTER_MOD_INIT
    );

    static final String MOMENT_LOAD_TIME = "LOAD_TIME";
    static final String MOMENT_AFTER_INIT = "AFTER_INIT";

    /** One exported field override. */
    record FieldOverride(
        String field,
        String value,
        String baseline,
        String moment,
        boolean targetInitRecomputed,
        boolean baselineAbsent
    ) {
    }

    /** One watched target content entry with its exported overrides. */
    record ContentOverride(
        String contentType,
        String name,
        String runtimeClass,
        List<FieldOverride> overrides
    ) {
    }

    /** A watched name that could not be captured. */
    record MissingTarget(String contentType, String name, String reason) {
    }

    /** Complete export result. */
    record Result(
        String watchSpec,
        List<ContentOverride> contents,
        List<MissingTarget> missing,
        int watchedContents,
        int watchedFields,
        int loadTimeOverrides,
        int afterInitOverrides,
        int targetInitRecomputed,
        List<String> diagnostics
    ) {
        int overrideCount() {
            return loadTimeOverrides + afterInitOverrides;
        }
    }

    private VanillaOverrideExporter() {
    }

    private record Key(String contentType, String name) {
    }

    private record ContentRow(String runtimeClass, Map<String, String> fields) {
    }

    private record Phase(String name, String spec, Map<Key, ContentRow> contents, List<MissingTarget> missing) {
    }

    static Result export(Path vanillaDirectory) throws IOException {
        Map<String, Phase> phases = new LinkedHashMap<>();
        for (String phase : PHASES) {
            Path file = RuntimeExtractorMain.vanillaPhaseFile(vanillaDirectory, phase);
            if (!Files.isRegularFile(file)) {
                throw new IOException("Missing vanilla watch phase file: " + file);
            }
            phases.put(phase, readPhase(file, phase));
        }

        Phase baseline = phases.get(BEFORE_MOD_CONTENT);
        Phase preInit = phases.get(PRE_CONTENT_INIT);
        Phase postInit = phases.get(POST_CONTENT_INIT);
        Phase finalPhase = phases.get(FINAL_AFTER_MOD_INIT);

        Set<String> specs = new LinkedHashSet<>();
        phases.values().forEach(phase -> specs.add(phase.spec()));
        if (specs.size() != 1) {
            throw new IOException("Vanilla watch phases disagree on the watched name list: " + specs);
        }

        List<String> diagnostics = new ArrayList<>();
        List<MissingTarget> missing = new ArrayList<>();
        Set<String> missingKeys = new LinkedHashSet<>();
        for (Phase phase : phases.values()) {
            for (MissingTarget target : phase.missing()) {
                String key = target.contentType() + "/" + target.name();
                if (missingKeys.add(key)) {
                    missing.add(target);
                }
            }
        }
        missing.sort((left, right) -> {
            int byType = left.contentType().compareTo(right.contentType());
            return byType != 0 ? byType : left.name().compareTo(right.name());
        });

        List<ContentOverride> contents = new ArrayList<>();
        int watchedFields = 0;
        int loadTimeOverrides = 0;
        int afterInitOverrides = 0;
        int recomputed = 0;

        List<Key> keys = new ArrayList<>(finalPhase.contents().keySet());
        keys.sort((left, right) -> {
            int byType = left.contentType().compareTo(right.contentType());
            return byType != 0 ? byType : left.name().compareTo(right.name());
        });

        for (Key key : keys) {
            ContentRow finalRow = finalPhase.contents().get(key);
            ContentRow baselineRow = baseline.contents().get(key);
            ContentRow preRow = preInit.contents().get(key);
            ContentRow postRow = postInit.contents().get(key);
            if (baselineRow == null || preRow == null || postRow == null) {
                diagnostics.add(
                    "Watched target content " + key.contentType() + "/" + key.name() +
                        " was not captured in every Mod-mutation phase; overrides were skipped."
                );
                continue;
            }

            Set<String> fieldNames = new LinkedHashSet<>();
            fieldNames.addAll(baselineRow.fields().keySet());
            fieldNames.addAll(preRow.fields().keySet());
            fieldNames.addAll(postRow.fields().keySet());
            fieldNames.addAll(finalRow.fields().keySet());
            watchedFields += fieldNames.size();

            List<FieldOverride> overrides = new ArrayList<>();
            for (String field : fieldNames) {
                String base = baselineRow.fields().get(field);
                String pre = preRow.fields().get(field);
                String post = postRow.fields().get(field);
                String last = finalRow.fields().get(field);
                if (last == null) {
                    diagnostics.add(
                        "Field " + key.contentType() + "/" + key.name() + "." + field +
                            " is absent from the final phase and was not exported."
                    );
                    continue;
                }
                boolean loadTimeChanged = !Objects.equals(pre, base);
                boolean afterInitChanged = !Objects.equals(last, post);
                if (!loadTimeChanged && !afterInitChanged) continue;

                String moment = loadTimeChanged ? MOMENT_LOAD_TIME : MOMENT_AFTER_INIT;
                boolean targetInitRecomputed = loadTimeChanged && !Objects.equals(post, pre);
                if (targetInitRecomputed) recomputed++;
                if (loadTimeChanged) loadTimeOverrides++;
                if (afterInitChanged) afterInitOverrides++;
                overrides.add(new FieldOverride(
                    field,
                    last,
                    base,
                    moment,
                    targetInitRecomputed,
                    base == null
                ));
            }
            if (overrides.isEmpty()) continue;
            contents.add(new ContentOverride(key.contentType(), key.name(), finalRow.runtimeClass(), overrides));
        }

        if (watchedFields == 0) {
            diagnostics.add("No watched target content was captured; the watch list matched nothing.");
        }

        return new Result(
            finalPhase.spec(),
            contents,
            missing,
            finalPhase.contents().size(),
            watchedFields,
            loadTimeOverrides,
            afterInitOverrides,
            recomputed,
            diagnostics
        );
    }

    /** Renders the export as the artifact consumed by the runtime-to-DP mapper. */
    static String toJson(Result result, String targetMod) {
        StringBuilder json = new StringBuilder(16_384);
        json.append("{\n");
        append(json, 1, "schemaVersion", "1", true);
        append(json, 1, "targetMod", quote(targetMod), true);
        append(json, 1, "watchSpec", quote(result.watchSpec()), true);
        append(json, 1, "watchedContents", Integer.toString(result.watchedContents()), true);
        append(json, 1, "watchedFields", Integer.toString(result.watchedFields()), true);

        json.append("  \"contents\": [");
        if (!result.contents().isEmpty()) json.append('\n');
        for (int index = 0; index < result.contents().size(); index++) {
            ContentOverride content = result.contents().get(index);
            json.append("    {\n");
            append(json, 3, "contentType", quote(content.contentType()), true);
            append(json, 3, "name", quote(content.name()), true);
            append(json, 3, "runtimeClass", quote(content.runtimeClass()), true);
            json.append("      \"overrides\": [");
            if (!content.overrides().isEmpty()) json.append('\n');
            for (int fieldIndex = 0; fieldIndex < content.overrides().size(); fieldIndex++) {
                FieldOverride override = content.overrides().get(fieldIndex);
                json.append("        {\n");
                append(json, 5, "field", quote(override.field()), true);
                appendRaw(json, 5, "value", override.value(), true);
                appendRaw(json, 5, "baseline", override.baseline(), true);
                append(json, 5, "moment", quote(override.moment()), true);
                append(json, 5, "targetInitRecomputed", Boolean.toString(override.targetInitRecomputed()), true);
                append(json, 5, "baselineAbsent", Boolean.toString(override.baselineAbsent()), false);
                json.append("        }");
                if (fieldIndex + 1 < content.overrides().size()) json.append(',');
                json.append('\n');
            }
            json.append("      ]\n");
            json.append("    }");
            if (index + 1 < result.contents().size()) json.append(',');
            json.append('\n');
        }
        json.append("  ],\n");

        json.append("  \"missingWatchTargets\": [");
        if (!result.missing().isEmpty()) json.append('\n');
        for (int index = 0; index < result.missing().size(); index++) {
            MissingTarget target = result.missing().get(index);
            json.append("    {");
            json.append("\"contentType\": ").append(quote(target.contentType())).append(", ");
            json.append("\"name\": ").append(quote(target.name())).append(", ");
            json.append("\"reason\": ").append(quote(target.reason()));
            json.append('}');
            if (index + 1 < result.missing().size()) json.append(',');
            json.append('\n');
        }
        json.append("  ],\n");

        json.append("  \"stats\": {\n");
        append(json, 2, "overrideCount", Integer.toString(result.overrideCount()), true);
        append(json, 2, "loadTimeOverrides", Integer.toString(result.loadTimeOverrides()), true);
        append(json, 2, "afterInitOverrides", Integer.toString(result.afterInitOverrides()), true);
        append(json, 2, "targetInitRecomputed", Integer.toString(result.targetInitRecomputed()), true);
        append(json, 2, "missingWatchTargets", Integer.toString(result.missing().size()), false);
        json.append("  },\n");

        json.append("  \"diagnostics\": [");
        if (!result.diagnostics().isEmpty()) json.append('\n');
        for (int index = 0; index < result.diagnostics().size(); index++) {
            json.append("    ").append(quote(result.diagnostics().get(index)));
            if (index + 1 < result.diagnostics().size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    static String toSummary(Result result) {
        return "Watched " + result.watchedContents() + " target content entries / " + result.watchedFields() +
            " fields; overrides=" + result.overrideCount() + " (loadTime=" + result.loadTimeOverrides() +
            ", afterInit=" + result.afterInitOverrides() + "), targetInitRecomputed=" +
            result.targetInitRecomputed() + ", missingWatchTargets=" + result.missing().size() + ".";
    }

    private static Phase readPhase(Path file, String expectedPhase) throws IOException {
        Map<Key, ContentRow> contents = new LinkedHashMap<>();
        List<MissingTarget> missing = new ArrayList<>();
        String spec = null;
        String declaredPhase = null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] columns = line.split("\t", -1);
            switch (columns[0]) {
                case "header" -> {
                    requireColumns(file, line, columns, 4);
                    declaredPhase = columns[2];
                    spec = decode(columns[3]);
                }
                case "content" -> {
                    requireColumns(file, line, columns, 4);
                    Key key = new Key(columns[1], decode(columns[2]));
                    contents.put(key, new ContentRow(decode(columns[3]), new TreeMap<>()));
                }
                case "field" -> {
                    requireColumns(file, line, columns, 5);
                    ContentRow row = contents.get(new Key(columns[1], decode(columns[2])));
                    if (row == null) {
                        throw new IOException("Vanilla watch field row precedes its content row in " + file + ": " + line);
                    }
                    row.fields().put(decode(columns[3]), decode(columns[4]));
                }
                case "missing" -> {
                    requireColumns(file, line, columns, 4);
                    missing.add(new MissingTarget(columns[1], decode(columns[2]), decode(columns[3])));
                }
                default -> throw new IOException("Unknown vanilla watch row marker in " + file + ": " + line);
            }
        }
        if (spec == null) {
            throw new IOException("Vanilla watch phase file has no header row: " + file);
        }
        if (!expectedPhase.equals(declaredPhase)) {
            throw new IOException(
                "Vanilla watch phase file " + file + " declares phase " + declaredPhase + " but " +
                    expectedPhase + " was expected."
            );
        }
        return new Phase(expectedPhase, spec, contents, missing);
    }

    private static void requireColumns(Path file, String line, String[] columns, int expected) throws IOException {
        if (columns.length != expected) {
            throw new IOException(
                "Malformed vanilla watch row in " + file + ": expected " + expected + " columns, found " +
                    columns.length + ": " + line
            );
        }
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void append(StringBuilder json, int indent, String name, String value, boolean comma) {
        json.append("  ".repeat(indent)).append(quote(name)).append(": ").append(value);
        if (comma) json.append(',');
        json.append('\n');
    }

    /** Spliced verbatim: the probe already emits a complete, escaped JSON value or null. */
    private static void appendRaw(StringBuilder json, int indent, String name, String value, boolean comma) {
        json.append("  ".repeat(indent)).append(quote(name)).append(": ")
            .append(value == null ? "null" : value);
        if (comma) json.append(',');
        json.append('\n');
    }

    static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
