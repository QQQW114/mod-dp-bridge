package io.github.moddpbridge.runtimeextractor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Experimental local-only runtime extractor.
 *
 * <p>The launcher deliberately starts Mindustry in a child JVM. The worker uses reflection so this
 * module has no build-time dependency on a particular Mindustry JAR. Supplying a server JAR and the
 * explicit {@code --allow-mod-execution} flag is mandatory because the input Mod receives ordinary
 * JVM process privileges.</p>
 */
public final class RuntimeExtractorMain {
    private static final DateTimeFormatter RUN_STAMP =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final Pattern MOD_NAME = Pattern.compile(
        "(?im)^\\s*name\\s*:\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s,#}]+))"
    );
    private static final Pattern OUTPUT_CONTENT_COUNT = Pattern.compile(
        "\\\"contentCount\\\"\\s*:\\s*(\\d+)"
    );

    private RuntimeExtractorMain() {
    }

    public static void main(String[] args) {
        int exit;
        try {
            exit = run(args);
        } catch (IllegalArgumentException error) {
            System.err.println("ERROR: " + error.getMessage());
            usage();
            exit = 2;
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            exit = 70;
        }
        System.exit(exit);
    }

    static int run(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            usage();
            return 0;
        }
        return switch (args[0]) {
            case "extract" -> launch(parse(args, 1));
            case "worker" -> runWorker(parse(args, 1));
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        };
    }

    private static int launch(Arguments arguments) throws Exception {
        if (!arguments.flag("allow-mod-execution")) {
            throw new IllegalArgumentException(
                "Runtime extraction executes the supplied Mod. Re-run with --allow-mod-execution only in a trusted local environment."
            );
        }

        Path serverJar = arguments.requiredPath("server-jar");
        Path modJar = arguments.requiredPath("mod-jar");
        requireRegularFile(serverJar, "Mindustry server JAR");
        requireRegularFile(modJar, "Mod JAR");

        String modId = arguments.value("mod-id");
        if (modId == null || modId.isBlank()) {
            modId = detectModId(modJar);
        }
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("Could not detect the Mod name; supply --mod-id explicitly.");
        }

        long timeoutSeconds = arguments.longValue("timeout-seconds", 120L);
        if (timeoutSeconds <= 0L) {
            throw new IllegalArgumentException("--timeout-seconds must be positive.");
        }

        Path workBase = arguments.path("work-dir", Path.of("work", "runtime-extractor"));
        Files.createDirectories(workBase);
        String runName = "run-" + RUN_STAMP.format(Instant.now()) + "-" + ProcessHandle.current().pid();
        Path runDirectory = uniqueDirectory(workBase.resolve(runName));
        Path modsDirectory = runDirectory.resolve("config/mods");
        Files.createDirectories(modsDirectory);
        // Prefixes make retained runs easy to inspect, but correctness does not rely on filename
        // order: the Probe refuses installation unless the Content registry is still empty.
        Path loadedMod = modsDirectory.resolve("100-" + safeFileName(modJar.getFileName().toString()));
        Files.copy(modJar, loadedMod, StandardCopyOption.REPLACE_EXISTING);

        Path output = arguments.path("output", runDirectory.resolve("content-runtime.json"));
        output = output.toAbsolutePath().normalize();
        Path headlessLog = runDirectory.resolve("headless.log");
        Path commandLog = runDirectory.resolve("command.txt");
        Path traceFile = runDirectory.resolve("registration-traces.tsv").toAbsolutePath().normalize();
        Path phaseFile = runDirectory.resolve("registration-phases.tsv").toAbsolutePath().normalize();
        Path trackerStatusFile = runDirectory.resolve("tracker-status.txt").toAbsolutePath().normalize();
        Path probeJar = modsDirectory.resolve("000-dpbridge-runtime-trace-probe.jar");
        compileRuntimeProbe(serverJar, runDirectory, probeJar);

        String javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            isWindows() ? "java.exe" : "java"
        ).toString();
        String classPath = System.getProperty("java.class.path") +
            System.getProperty("path.separator") + serverJar.toAbsolutePath().normalize();
        List<String> command = List.of(
            javaExecutable,
            "-Djava.awt.headless=true",
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-Dmoddpbridge.runtimeTargetMod=" + modId,
            "-Dmoddpbridge.runtimeTraceFile=" + traceFile,
            "-Dmoddpbridge.runtimePhaseFile=" + phaseFile,
            "-Dmoddpbridge.runtimeTrackerStatusFile=" + trackerStatusFile,
            "-cp",
            classPath,
            RuntimeExtractorMain.class.getName(),
            "worker",
            "--output",
            output.toString(),
            "--mod-id",
            modId,
            "--input-mod-jar",
            modJar.toAbsolutePath().normalize().toString(),
            "--trace-file",
            traceFile.toString(),
            "--phase-file",
            phaseFile.toString(),
            "--tracker-status-file",
            trackerStatusFile.toString()
        );
        Files.writeString(commandLog, renderCommand(command) + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("Runtime extraction WILL execute Mod code in a child JVM.");
        System.out.println("Server JAR: " + serverJar.toAbsolutePath().normalize());
        System.out.println("Mod JAR: " + modJar.toAbsolutePath().normalize());
        System.out.println("Detected Mod ID: " + modId);
        System.out.println("Isolated run directory: " + runDirectory.toAbsolutePath().normalize());
        System.out.println("Runtime tracker probe: " + probeJar.toAbsolutePath().normalize());
        System.out.println("Headless log: " + headlessLog.toAbsolutePath().normalize());

        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(runDirectory.toFile())
            .redirectErrorStream(true);
        Process process = builder.start();
        Thread outputPump = new Thread(() -> copyProcessOutput(process, headlessLog), "runtime-extractor-output");
        outputPump.setDaemon(true);
        outputPump.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            destroyProcessTree(process);
            outputPump.join(5_000L);
            System.err.println("ERROR: Headless Mindustry extraction timed out after " + timeoutSeconds + " seconds.");
            return 3;
        }
        outputPump.join(5_000L);
        int childExit = process.exitValue();
        if (childExit != 0) {
            System.err.println("ERROR: Headless Mindustry worker exited with code " + childExit + ". See " + headlessLog);
            return 4;
        }
        if (!Files.isRegularFile(output)) {
            System.err.println("ERROR: Worker exited successfully but did not create " + output);
            return 5;
        }

        try {
            TrackerData tracker = readTrackerData(traceFile, phaseFile, trackerStatusFile);
            int finalContentCount = readOutputContentCount(output);
            requireCompleteTracker(tracker, finalContentCount);
        } catch (IOException | RuntimeException error) {
            System.err.println("ERROR: Runtime tracker validation failed: " + error.getMessage());
            System.err.println("The incomplete snapshot has been retained for diagnosis at " + output);
            return 6;
        }

        System.out.println("Runtime content JSON: " + output);
        return 0;
    }

    private static int runWorker(Arguments arguments) throws Exception {
        Path output = arguments.requiredPath("output").toAbsolutePath().normalize();
        String modId = arguments.required("mod-id");
        String inputModJar = arguments.value("input-mod-jar");
        Path traceFile = arguments.requiredPath("trace-file");
        Path phaseFile = arguments.requiredPath("phase-file");
        Path trackerStatusFile = arguments.requiredPath("tracker-status-file");

        Class<?> consType = Class.forName("arc.func.Cons");
        Class<?> serverLoadEvent = Class.forName("mindustry.game.EventType$ServerLoadEvent");
        Object listener = Proxy.newProxyInstance(
            RuntimeExtractorMain.class.getClassLoader(),
            new Class<?>[]{consType},
            (proxy, method, values) -> {
                if ("get".equals(method.getName())) {
                    try {
                        dumpRegisteredContent(
                            output,
                            modId,
                            inputModJar,
                            readTrackerData(traceFile, phaseFile, trackerStatusFile)
                        );
                        System.out.println("RUNTIME_EXTRACTOR_OUTPUT=" + output);
                        System.out.flush();
                        System.exit(0);
                    } catch (Throwable error) {
                        error.printStackTrace(System.err);
                        System.err.flush();
                        System.exit(71);
                    }
                }
                if ("toString".equals(method.getName())) return "mod-dp-bridge-runtime-extractor";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == values[0];
                return null;
            }
        );

        Class<?> events = Class.forName("arc.Events");
        events.getMethod("on", Class.class, consType).invoke(null, serverLoadEvent, listener);
        System.out.println("Runtime extractor listener registered; starting headless Mindustry.");
        Class<?> serverLauncher = Class.forName("mindustry.server.ServerLauncher");
        serverLauncher.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        // ServerLauncher.main() starts Arc's application thread and may return immediately. Keep the
        // worker alive until the ServerLoadEvent listener writes the snapshot and terminates it.
        new CountDownLatch(1).await();
        return 0;
    }

    private static void dumpRegisteredContent(
        Path output,
        String targetMod,
        String inputModJar,
        TrackerData tracker
    ) throws Exception {
        Class<?> vars = Class.forName("mindustry.Vars");
        Object contentLoader = vars.getField("content").get(null);
        Object contentMap = contentLoader.getClass().getMethod("getContentMap").invoke(contentLoader);
        List<ContentRow> rows = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        ModInfo targetInfo = null;

        int mapLength = Array.getLength(contentMap);
        for (int mapIndex = 0; mapIndex < mapLength; mapIndex++) {
            Object sequence = Array.get(contentMap, mapIndex);
            int size = sequence.getClass().getField("size").getInt(sequence);
            Method get = sequence.getClass().getMethod("get", int.class);
            for (int index = 0; index < size; index++) {
                Object content = get.invoke(sequence, index);
                Object minfo = content.getClass().getField("minfo").get(content);
                Object loadedMod = minfo.getClass().getField("mod").get(minfo);
                if (loadedMod == null) continue;

                ModInfo mod = readModInfo(loadedMod);
                if (!mod.name().equalsIgnoreCase(targetMod)) continue;
                targetInfo = mod;

                Object type = content.getClass().getMethod("getContentType").invoke(content);
                String contentType = ((Enum<?>) type).name();
                String name;
                try {
                    name = String.valueOf(content.getClass().getField("name").get(content));
                } catch (NoSuchFieldException ignored) {
                    name = String.valueOf(content);
                }
                int id = content.getClass().getField("id").getInt(content);
                rows.add(new ContentRow(
                    name,
                    contentType,
                    content.getClass().getName(),
                    id,
                    mod,
                    tracker.registrationStacks().getOrDefault(name, List.of())
                ));
                counts.merge(contentType, 1, Integer::sum);
            }
        }

        requireCompleteTracker(tracker, rows.size());

        rows.sort(Comparator
            .comparing(ContentRow::contentType)
            .thenComparing(ContentRow::name)
            .thenComparing(ContentRow::runtimeClass));
        Files.createDirectories(output.getParent());
        Files.writeString(
            output,
            toJson(targetMod, inputModJar, targetInfo, readGameVersion(), tracker, counts, rows),
            StandardCharsets.UTF_8
        );
    }

    private static ModInfo readModInfo(Object loadedMod) throws Exception {
        String name = String.valueOf(loadedMod.getClass().getField("name").get(loadedMod));
        Object metadata = loadedMod.getClass().getField("meta").get(loadedMod);
        String displayName = nullableField(metadata, "displayName");
        String version = nullableField(metadata, "version");
        Object file = loadedMod.getClass().getField("file").get(loadedMod);
        String source;
        try {
            source = String.valueOf(file.getClass().getMethod("absolutePath").invoke(file));
        } catch (ReflectiveOperationException ignored) {
            source = String.valueOf(file);
        }
        return new ModInfo(name, displayName, version, source);
    }

    private static GameVersion readGameVersion() {
        try {
            Class<?> version = Class.forName("mindustry.core.Version");
            return new GameVersion(
                String.valueOf(version.getField("type").get(null)),
                String.valueOf(version.getField("modifier").get(null)),
                version.getField("number").getInt(null),
                version.getField("build").getInt(null),
                version.getField("revision").getInt(null),
                String.valueOf(version.getField("commitHash").get(null))
            );
        } catch (ReflectiveOperationException error) {
            return new GameVersion("unknown", "unknown", 0, 0, 0, "unknown");
        }
    }

    private static TrackerData readTrackerData(Path traceFile, Path phaseFile, Path statusFile) throws IOException {
        Map<String, List<String>> registrationStacks = new LinkedHashMap<>();
        if (Files.isRegularFile(traceFile)) {
            for (String line : Files.readAllLines(traceFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] columns = line.split("\\t", -1);
                if (columns.length != 2) continue;
                String name = decodeBase64(columns[0]);
                String stack = decodeBase64(columns[1]);
                List<String> frames = stack.lines().filter(value -> !value.isBlank()).toList();
                if (!frames.isEmpty()) registrationStacks.put(name, frames);
            }
        }

        Integer preInit = null;
        Integer postInit = null;
        if (Files.isRegularFile(phaseFile)) {
            for (String line : Files.readAllLines(phaseFile, StandardCharsets.UTF_8)) {
                String[] columns = line.split("\\t", -1);
                if (columns.length != 2) continue;
                try {
                    if ("PRE_INIT".equals(columns[0])) preInit = Integer.parseInt(columns[1]);
                    if ("POST_INIT".equals(columns[0])) postInit = Integer.parseInt(columns[1]);
                } catch (NumberFormatException ignored) {
                    // Preserve the rest of the runtime result even if a phase marker is malformed.
                }
            }
        }

        String status = Files.isRegularFile(statusFile)
            ? Files.readString(statusFile, StandardCharsets.UTF_8).trim()
            : "missing";
        return new TrackerData(status, preInit, postInit, registrationStacks);
    }

    private static int readOutputContentCount(Path output) throws IOException {
        Matcher matcher = OUTPUT_CONTENT_COUNT.matcher(Files.readString(output, StandardCharsets.UTF_8));
        if (!matcher.find()) {
            throw new IllegalStateException("Generated runtime JSON has no contentCount field.");
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException error) {
            throw new IllegalStateException("Generated runtime JSON has an invalid contentCount field.", error);
        }
    }

    static void requireCompleteTracker(TrackerData tracker, int finalContentCount) {
        if (!"installed".equals(tracker.status())) {
            throw new IllegalStateException("tracker status is '" + tracker.status() + "', expected 'installed'.");
        }
        if (tracker.preInitContentCount() == null) {
            throw new IllegalStateException("PRE_INIT content count is missing.");
        }
        if (tracker.postInitContentCount() == null) {
            throw new IllegalStateException("POST_INIT content count is missing.");
        }
        int tracedContentCount = tracker.registrationStacks().size();
        if (tracedContentCount != finalContentCount) {
            throw new IllegalStateException(
                "registration trace count " + tracedContentCount +
                    " does not match final target content count " + finalContentCount + "."
            );
        }
    }

    private static void compileRuntimeProbe(Path serverJar, Path runDirectory, Path probeJar) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                "A full JDK is required to compile the runtime tracker probe; no system Java compiler is available."
            );
        }

        Path sourceRoot = runDirectory.resolve("runtime-probe-source");
        Path classesRoot = runDirectory.resolve("runtime-probe-classes");
        Path sourceFile = sourceRoot.resolve("bridgeprobe/RuntimeTraceProbe.java");
        Path compilerLog = runDirectory.resolve("runtime-probe-compile.log");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classesRoot);

        String template;
        try (InputStream input = RuntimeExtractorMain.class.getResourceAsStream("/runtime-trace-probe.java.template")) {
            if (input == null) {
                throw new IOException("Bundled runtime tracker source template is missing.");
            }
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(sourceFile, template, StandardCharsets.UTF_8);

        List<String> compilerArguments = List.of(
            "-encoding", "UTF-8",
            "--release", "17",
            "-classpath", serverJar.toAbsolutePath().normalize().toString(),
            "-d", classesRoot.toAbsolutePath().normalize().toString(),
            sourceFile.toAbsolutePath().normalize().toString()
        );
        ByteArrayOutputStream compilerOutput = new ByteArrayOutputStream();
        int compileExit = compiler.run(null, compilerOutput, compilerOutput, compilerArguments.toArray(String[]::new));
        Files.write(compilerLog, compilerOutput.toByteArray());
        if (compileExit != 0) {
            throw new IOException(
                "Could not compile the runtime tracker probe (javac exit " + compileExit + "). See " + compilerLog
            );
        }

        Files.createDirectories(probeJar.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(probeJar))) {
            writeJarEntry(jar, "mod.hjson", """
                name: dpbridge-runtime-trace-probe
                main: bridgeprobe.RuntimeTraceProbe
                version: 0.1.0
                minGameVersion: 159.7
                hidden: true
                java: true
                """.getBytes(StandardCharsets.UTF_8));

            try (var paths = Files.walk(classesRoot)) {
                for (Path classFile : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String entryName = classesRoot.relativize(classFile).toString().replace('\\', '/');
                    writeJarEntry(jar, entryName, Files.readAllBytes(classFile));
                }
            }
        }
    }

    private static void writeJarEntry(JarOutputStream jar, String name, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        jar.putNextEntry(entry);
        jar.write(bytes);
        jar.closeEntry();
    }

    private static String decodeBase64(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String toJson(
        String targetMod,
        String inputModJar,
        ModInfo loadedMod,
        GameVersion gameVersion,
        TrackerData tracker,
        Map<String, Integer> counts,
        List<ContentRow> rows
    ) {
        StringBuilder json = new StringBuilder(64_000);
        json.append("{\n");
        appendProperty(json, 1, "schemaVersion", "1", false, true);
        appendProperty(json, 1, "generatedAt", quote(Instant.now().toString()), false, true);
        appendProperty(json, 1, "targetMod", quote(targetMod), false, true);
        appendProperty(json, 1, "inputModJar", quoteNullable(inputModJar), false, true);
        json.append("  \"gameVersion\": {\n");
        appendProperty(json, 2, "type", quote(gameVersion.type()), false, true);
        appendProperty(json, 2, "modifier", quote(gameVersion.modifier()), false, true);
        appendProperty(json, 2, "number", Integer.toString(gameVersion.number()), false, true);
        appendProperty(json, 2, "build", Integer.toString(gameVersion.build()), false, true);
        appendProperty(json, 2, "revision", Integer.toString(gameVersion.revision()), false, true);
        appendProperty(json, 2, "commitHash", quote(gameVersion.commitHash()), false, false);
        json.append("  },\n");
        json.append("  \"loadedMod\": ");
        appendMod(json, loadedMod, 1);
        json.append(",\n");
        json.append("  \"registrationTracker\": {\n");
        appendProperty(json, 2, "status", quote(tracker.status()), true);
        appendProperty(json, 2, "preInitContentCount", integerOrNull(tracker.preInitContentCount()), true);
        appendProperty(json, 2, "postInitContentCount", integerOrNull(tracker.postInitContentCount()), true);
        appendProperty(json, 2, "tracedContentCount", Integer.toString(tracker.registrationStacks().size()), false);
        json.append("  },\n");
        appendProperty(json, 1, "contentCount", Integer.toString(rows.size()), false, true);
        json.append("  \"countsByType\": {");
        if (!counts.isEmpty()) json.append('\n');
        int countIndex = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            json.append("    ").append(quote(entry.getKey())).append(": ").append(entry.getValue());
            if (++countIndex < counts.size()) json.append(',');
            json.append('\n');
        }
        json.append("  },\n");
        json.append("  \"contents\": [");
        if (!rows.isEmpty()) json.append('\n');
        for (int index = 0; index < rows.size(); index++) {
            ContentRow row = rows.get(index);
            json.append("    {\n");
            appendProperty(json, 3, "name", quote(row.name()), false, true);
            appendProperty(json, 3, "contentType", quote(row.contentType()), false, true);
            appendProperty(json, 3, "runtimeClass", quote(row.runtimeClass()), false, true);
            appendProperty(json, 3, "id", Integer.toString(row.id()), false, true);
            appendProperty(json, 3, "modName", quote(row.mod().name()), false, true);
            appendProperty(json, 3, "modDisplayName", quoteNullable(row.mod().displayName()), false, true);
            appendProperty(json, 3, "modVersion", quoteNullable(row.mod().version()), false, true);
            appendProperty(json, 3, "modSource", quote(row.mod().source()), false, true);
            json.append("      \"registrationStack\": [");
            if (!row.registrationStack().isEmpty()) json.append('\n');
            for (int frameIndex = 0; frameIndex < row.registrationStack().size(); frameIndex++) {
                json.append("        ").append(quote(row.registrationStack().get(frameIndex)));
                if (frameIndex + 1 < row.registrationStack().size()) json.append(',');
                json.append('\n');
            }
            json.append("      ]\n");
            json.append("    }");
            if (index + 1 < rows.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static void appendMod(StringBuilder json, ModInfo mod, int indent) {
        if (mod == null) {
            json.append("null");
            return;
        }
        json.append("{\n");
        appendProperty(json, indent + 1, "name", quote(mod.name()), false, true);
        appendProperty(json, indent + 1, "displayName", quoteNullable(mod.displayName()), false, true);
        appendProperty(json, indent + 1, "version", quoteNullable(mod.version()), false, true);
        appendProperty(json, indent + 1, "source", quote(mod.source()), false, false);
        json.append("  ".repeat(indent)).append('}');
    }

    private static void appendProperty(
        StringBuilder json,
        int indent,
        String name,
        String value,
        boolean comma
    ) {
        json.append("  ".repeat(indent)).append(quote(name)).append(": ").append(value);
        if (comma) json.append(',');
        json.append('\n');
    }

    private static void appendProperty(
        StringBuilder json,
        int indent,
        String name,
        String value,
        boolean ignored,
        boolean comma
    ) {
        appendProperty(json, indent, name, value, comma);
    }

    private static String integerOrNull(Integer value) {
        return value == null ? "null" : Integer.toString(value);
    }

    static String quote(String value) {
        if (value == null) return "null";
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
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String quoteNullable(String value) {
        return value == null ? "null" : quote(value);
    }

    static String detectModId(Path modJar) throws IOException {
        try (ZipFile zip = new ZipFile(modJar.toFile())) {
            ZipEntry selected = null;
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (entry.isDirectory()) continue;
                String normalized = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!(normalized.endsWith("/mod.hjson") || normalized.endsWith("/mod.json") ||
                    normalized.equals("mod.hjson") || normalized.equals("mod.json"))) continue;
                if (selected == null || entry.getName().length() < selected.getName().length()) {
                    selected = entry;
                }
            }
            if (selected == null) return null;
            String metadata = new String(zip.getInputStream(selected).readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = MOD_NAME.matcher(metadata);
            if (!matcher.find()) return null;
            for (int group = 1; group <= matcher.groupCount(); group++) {
                String value = matcher.group(group);
                if (value != null && !value.isBlank()) return value.trim();
            }
            return null;
        }
    }

    private static String nullableField(Object target, String name) throws ReflectiveOperationException {
        Object value = target.getClass().getField(name).get(target);
        return value == null ? null : String.valueOf(value);
    }

    private static void copyProcessOutput(Process process, Path logFile) {
        try {
            Files.createDirectories(logFile.getParent());
            try (
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
                );
                var writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)
            ) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
            }
        } catch (IOException error) {
            System.err.println("Failed to capture headless output: " + error.getMessage());
        }
    }

    private static void destroyProcessTree(Process process) {
        process.descendants().forEach(handle -> {
            try {
                handle.destroyForcibly();
            } catch (Throwable ignored) {
                // Best-effort teardown of an untrusted child process tree.
            }
        });
        process.destroyForcibly();
    }

    private static Arguments parse(String[] args, int offset) {
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (int index = offset; index < args.length; index++) {
            String token = args[index];
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + token);
            }
            String key = token.substring(2);
            if ("allow-mod-execution".equals(key)) {
                flags.put(key, true);
                continue;
            }
            if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + token);
            }
            values.put(key, args[++index]);
        }
        return new Arguments(values, flags);
    }

    private static Path uniqueDirectory(Path candidate) throws IOException {
        Path absolute = candidate.toAbsolutePath().normalize();
        int suffix = 0;
        Path selected = absolute;
        while (Files.exists(selected)) {
            selected = Path.of(absolute + "-" + ++suffix);
        }
        return Files.createDirectories(selected);
    }

    private static String safeFileName(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "input-mod.jar" : sanitized;
    }

    private static String renderCommand(List<String> command) {
        return command.stream().map(RuntimeExtractorMain::shellQuote).reduce((left, right) -> left + " " + right).orElse("");
    }

    private static String shellQuote(String value) {
        if (value.matches("[A-Za-z0-9_./:\\\\-]+")) return value;
        return '"' + value.replace("\"", "\\\"") + '"';
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void requireRegularFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " does not exist or is not a regular file: " + path);
        }
    }

    private static void usage() {
        System.out.println("""
            Experimental local Mindustry runtime content extractor.

            WARNING: this executes the supplied Mod with your user account's JVM permissions.

            Usage:
              bridge-runtime-extractor extract \\
                --server-jar <v159.7-server.jar> \\
                --mod-jar <mod.jar> \\
                --output <content-runtime.json> \\
                [--mod-id <internal-name>] \\
                [--work-dir <directory>] \\
                [--timeout-seconds <seconds>] \\
                --allow-mod-execution
            """);
    }

    private record Arguments(Map<String, String> values, Map<String, Boolean> flags) {
        String required(String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + key);
            return value;
        }

        Path requiredPath(String key) {
            return Path.of(required(key)).toAbsolutePath().normalize();
        }

        String value(String key) {
            return values.get(key);
        }

        boolean flag(String key) {
            return flags.getOrDefault(key, false);
        }

        Path path(String key, Path defaultValue) {
            String value = values.get(key);
            return value == null ? defaultValue.toAbsolutePath().normalize() : Path.of(value).toAbsolutePath().normalize();
        }

        long longValue(String key, long defaultValue) {
            String value = values.get(key);
            if (value == null) return defaultValue;
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("--" + key + " must be an integer: " + value, error);
            }
        }
    }

    private record ModInfo(String name, String displayName, String version, String source) {
    }

    private record ContentRow(
        String name,
        String contentType,
        String runtimeClass,
        int id,
        ModInfo mod,
        List<String> registrationStack
    ) {
    }

    record TrackerData(
        String status,
        Integer preInitContentCount,
        Integer postInitContentCount,
        Map<String, List<String>> registrationStacks
    ) {
    }

    private record GameVersion(String type, String modifier, int number, int build, int revision, String commitHash) {
    }
}
