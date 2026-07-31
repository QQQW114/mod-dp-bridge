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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RuntimeSnapshotSupportTemplateTest {
    @TempDir
    Path temp;

    @Test
    void fixtureCompilesAndCapturesOnlyFallbackOwnedParserFields() throws Exception {
        Path serverJar = findOfficialServerJar();
        assumeTrue(
            serverJar != null,
            "Set -Dmoddpbridge.test.serverJar=<official-v159.7-server.jar> to run the runtime template fixture."
        );

        Path sourceRoot = temp.resolve("source");
        Path classesRoot = temp.resolve("classes");
        Path supportSource = sourceRoot.resolve("bridgeprobe/RuntimeSnapshotSupport.java");
        Path fixtureSource = sourceRoot.resolve("bridgeprobe/RuntimeSnapshotSupportFixture.java");
        Files.createDirectories(supportSource.getParent());
        Files.createDirectories(classesRoot);

        try (InputStream input = RuntimeSnapshotSupportTemplateTest.class.getResourceAsStream(
            "/runtime-snapshot-support.java.template"
        )) {
            assertNotNull(input, "runtime snapshot support template");
            Files.write(supportSource, input.readAllBytes());
        }
        Files.writeString(fixtureSource, fixtureSource(), StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A full JDK is required for the runtime template fixture.");
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
        String classpath = classesRoot + File.pathSeparator + serverJar;
        Process process = new ProcessBuilder(
            javaExecutable,
            "-Dfile.encoding=UTF-8",
            "-cp", classpath,
            "bridgeprobe.RuntimeSnapshotSupportFixture"
        ).redirectErrorStream(true).start();
        boolean exited = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) process.destroyForcibly();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(exited, "Runtime template fixture timed out.\n" + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("RUNTIME_SNAPSHOT_FIXTURE_OK"), output);
    }

    private static Path findOfficialServerJar() {
        String configured = System.getProperty("moddpbridge.test.serverJar");
        List<Path> candidates = configured == null || configured.isBlank()
            ? List.of(
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

            import arc.struct.Seq;
            import arc.util.serialization.Json;
            import mindustry.Vars;
            import mindustry.core.ContentLoader;
            import mindustry.ctype.Content;
            import mindustry.ctype.ContentType;
            import mindustry.mod.ClassMap;
            import mindustry.mod.ContentParser;
            import mindustry.mod.Mods;
            import mindustry.type.Item;
            import mindustry.type.Liquid;
            import mindustry.type.UnitType;
            import mindustry.world.Block;

            import java.lang.reflect.Constructor;
            import java.lang.reflect.Field;
            import java.lang.reflect.Method;
            import java.util.ArrayList;
            import java.util.Collections;
            import java.util.List;
            import java.util.Map;

            public final class RuntimeSnapshotSupportFixture {
                public static void main(String[] args) throws Exception {
                    Vars.content = new ContentLoader();
                    Vars.mods = new Mods();
                    RuntimeSnapshotSupport.initializeTrustedMetadata();

                    ClassMap.classes.put("MaliciousUnitType", MaliciousUnitType.class);
                    ClassMap.classes.put("MaliciousBlock", MaliciousBlock.class);
                    ClassMap.classes.put("MaliciousLiquid", MaliciousLiquid.class);
                    ClassMap.classes.remove("UnitType");
                    ClassMap.classes.remove("Block");
                    ClassMap.classes.put("InjectedUnitAlias", UnitType.class);
                    ClassMap.classes.put("InjectedBlockAlias", Block.class);

                    MaliciousUnitType unit = new MaliciousUnitType("fixture-unit");
                    MaliciousBlock block = new MaliciousBlock("fixture-block");
                    MaliciousLiquid liquid = new MaliciousLiquid("fixture-liquid");
                    MaliciousItem item = new MaliciousItem("fixture-item");

                    Seq<?> itemRegistry = Vars.content.items();
                    require(contentAt(itemRegistry, item.id) == item,
                        "ContentLoader Seq<Object[]> traversal inserted an invalid Content[] cast");

                    Object unitFallback = resolveFallback(unit);
                    Object blockFallback = resolveFallback(block);
                    Object liquidFallback = resolveFallback(liquid);
                    require(fallbackClass(unitFallback) == UnitType.class, "custom UnitType ClassMap entry was trusted");
                    require(fallbackClass(blockFallback) == Block.class, "custom Block ClassMap entry was trusted");
                    require(fallbackClass(liquidFallback) == Liquid.class, "custom Liquid ClassMap entry was trusted");
                    require(fallbackName(unitFallback).equals("UnitType"), "mutable UnitType alias changed frozen fallback");
                    require(fallbackName(blockFallback).equals("Block"), "mutable Block alias changed frozen fallback");

                    Map<String, String> unitFields = captureFields(unit, unitFallback);
                    Map<String, String> blockFields = captureFields(block, blockFallback);
                    Map<String, String> itemFields = captureFields(item, resolveFallback(item));

                    requireSorted(unitFields, "unit fields");
                    requireSorted(blockFields, "block fields");
                    require(unitFields.equals(captureFields(unit, unitFallback)), "unit capture was not deterministic");
                    require(blockFields.equals(captureFields(block, blockFallback)), "block capture was not deterministic");

                    require(unitFields.containsKey("health"), "UnitType parser metadata did not expose health");
                    require(unitFields.get("health").contains("\\\"value\\\":\\\"321.0\\\""),
                        "custom shadow field was read instead of UnitType.health: " + unitFields.get("health"));
                    require(!unitFields.containsKey("maliciousNumber"), "custom UnitType field leaked into fields");
                    require(!unitFields.containsKey("maliciousIterable"), "custom UnitType iterable leaked into fields");
                    require(unitFields.get("aiController").contains("UNSUPPORTED_RUNTIME_OBJECT"),
                        "unsupported target field was not explicit opaque");

                    require(unitFields.containsKey("armor"), "fixture precondition: UnitType armor metadata missing");
                    activeParserJson().getFields(UnitType.class).remove("armor");
                    require(captureFields(unit, unitFallback).containsKey("armor"),
                        "active Mod parser mutation changed the frozen target field schema");

                    for(String pseudo : List.of(
                        "name", "description", "research", "type", "template", "requirements",
                        "controller", "defaultController", "waves"
                    )){
                        require(!unitFields.containsKey(pseudo), "unit pseudo field was captured: " + pseudo);
                    }

                    require(blockFields.containsKey("size"), "Block parser metadata did not expose size");
                    require(blockFields.get("size").contains("\\\"value\\\":\\\"3\\\""),
                        "custom shadow field was read instead of Block.size: " + blockFields.get("size"));
                    require(!blockFields.containsKey("maliciousNumber"), "custom Block field leaked into fields");
                    require(!blockFields.containsKey("consumes"), "Block consumes pseudo field was captured");
                    require(itemFields.get("cost").contains("\\\"value\\\":\\\"7.0\\\""),
                        "custom Item shadow field was read instead of Item.cost: " + itemFields.get("cost"));

                    String customNumber = encodeValue(new ExplodingNumber(), Number.class, freshBudget());
                    require(customNumber.contains("UNSUPPORTED_NUMBER_SUBCLASS"),
                        "custom Number was not made opaque: " + customNumber);

                    Object exhausted = freshBudget();
                    Field remaining = exhausted.getClass().getDeclaredField("remainingNodes");
                    remaining.setAccessible(true);
                    remaining.setInt(exhausted, 1);
                    String bounded = encodeValue(new Object[]{1, 2}, Object[].class, exhausted);
                    require(bounded.contains("NODE_BUDGET_EXCEEDED"), "node budget did not produce opaque values");

                    String unsupported = encodeValue(new ExplodingObject(), Object.class, freshBudget());
                    require(unsupported.contains("UNSUPPORTED_RUNTIME_OBJECT"),
                        "unsupported object was not made opaque: " + unsupported);

                    System.out.println("RUNTIME_SNAPSHOT_FIXTURE_OK");
                }

                private static Object resolveFallback(Content content) throws Exception {
                    Method method = RuntimeSnapshotSupport.class.getDeclaredMethod("resolveFallback", Content.class);
                    method.setAccessible(true);
                    return method.invoke(null, content);
                }

                private static Content contentAt(Seq<?> sequence, int index) throws Exception {
                    Method method = RuntimeSnapshotSupport.class.getDeclaredMethod("contentAt", Seq.class, int.class);
                    method.setAccessible(true);
                    return (Content)method.invoke(null, sequence, index);
                }

                @SuppressWarnings("unchecked")
                private static Map<String, String> captureFields(Content content, Object fallback) throws Exception {
                    Method method = RuntimeSnapshotSupport.class.getDeclaredMethod(
                        "captureFields", Content.class, fallback.getClass()
                    );
                    method.setAccessible(true);
                    return (Map<String, String>)method.invoke(null, content, fallback);
                }

                private static Class<?> fallbackClass(Object fallback) throws Exception {
                    Method method = fallback.getClass().getDeclaredMethod("runtimeClass");
                    method.setAccessible(true);
                    return (Class<?>)method.invoke(fallback);
                }

                private static String fallbackName(Object fallback) throws Exception {
                    Method method = fallback.getClass().getDeclaredMethod("parserName");
                    method.setAccessible(true);
                    return (String)method.invoke(fallback);
                }

                private static Object freshBudget() throws Exception {
                    Class<?> type = Class.forName("bridgeprobe.RuntimeSnapshotSupport$Budget");
                    Constructor<?> constructor = type.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    return constructor.newInstance();
                }

                private static Json activeParserJson() throws Exception {
                    Field parserField = Mods.class.getDeclaredField("parser");
                    parserField.setAccessible(true);
                    Object contentParser = parserField.get(Vars.mods);
                    Field jsonField = ContentParser.class.getDeclaredField("parser");
                    jsonField.setAccessible(true);
                    return (Json)jsonField.get(contentParser);
                }

                private static String encodeValue(Object value, Class<?> declaredType, Object budget) throws Exception {
                    Method method = RuntimeSnapshotSupport.class.getDeclaredMethod(
                        "encodeValue", Object.class, Class.class, int.class, budget.getClass()
                    );
                    method.setAccessible(true);
                    return (String)method.invoke(null, value, declaredType, 0, budget);
                }

                private static void requireSorted(Map<String, String> fields, String label) {
                    List<String> actual = new ArrayList<>(fields.keySet());
                    List<String> sorted = new ArrayList<>(actual);
                    Collections.sort(sorted);
                    require(actual.equals(sorted), label + " were not deterministically sorted");
                }

                private static void require(boolean condition, String message) {
                    if(!condition) throw new AssertionError(message);
                }

                public static final class MaliciousUnitType extends UnitType {
                    public float health = 999f;
                    public Number maliciousNumber = new ExplodingNumber();
                    private boolean rejectVirtualDispatch;
                    public Iterable<Object> maliciousIterable = () -> {
                        throw new AssertionError("custom Iterable.iterator() was called");
                    };

                    public MaliciousUnitType(String name) {
                        super(name);
                        super.health = 321f;
                        aiController = () -> {
                            throw new AssertionError("custom provider was called");
                        };
                        rejectVirtualDispatch = true;
                    }

                    @Override
                    public ContentType getContentType() {
                        if(rejectVirtualDispatch){
                            throw new AssertionError("custom UnitType.getContentType() was called");
                        }
                        return super.getContentType();
                    }
                }

                public static final class MaliciousBlock extends Block {
                    public int size = 99;
                    public Number maliciousNumber = new ExplodingNumber();

                    public MaliciousBlock(String name) {
                        super(name);
                        super.size = 3;
                    }
                }

                public static final class MaliciousLiquid extends Liquid {
                    public MaliciousLiquid(String name) {
                        super(name);
                    }
                }

                public static final class MaliciousItem extends Item {
                    public float cost = 999f;

                    public MaliciousItem(String name) {
                        super(name);
                        super.cost = 7f;
                    }
                }

                public static final class ExplodingNumber extends Number {
                    @Override public int intValue() { throw new AssertionError("custom Number.intValue() was called"); }
                    @Override public long longValue() { throw new AssertionError("custom Number.longValue() was called"); }
                    @Override public float floatValue() { throw new AssertionError("custom Number.floatValue() was called"); }
                    @Override public double doubleValue() { throw new AssertionError("custom Number.doubleValue() was called"); }
                    @Override public String toString() { throw new AssertionError("custom Number.toString() was called"); }
                }

                public static final class ExplodingObject {
                    @Override public String toString() { throw new AssertionError("custom Object.toString() was called"); }
                }
            }
            """;
    }
}
