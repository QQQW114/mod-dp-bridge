import arc.ApplicationCore;
import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.Logic;
import mindustry.core.FileTree;
import mindustry.core.NetServer;
import mindustry.core.World;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.ctype.MappableContent;
import mindustry.mod.DataPatcher;
import mindustry.mod.data.ContentAsset;
import mindustry.mod.data.DataAsset;
import mindustry.mod.data.DataAssetType;
import mindustry.mod.data.PatchAsset;
import mindustry.net.Net;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trusted source-file harness launched in a separate JVM by mod-dp-bridge.
 *
 * <p>This file is part of the converter. It never compiles, loads or executes classes from the
 * input mod. It initializes the supplied Mindustry server JAR and passes generated data assets to
 * the v159.7 DataManager/DataPatcher implementation.</p>
 */
public class DpApplyHarness {
    private static final String protocol = "DPBRIDGE_PROTOCOL";
    private static final String assetRecord = "DPBRIDGE_ASSET";
    private static final String patchRecord = "DPBRIDGE_PATCH";
    private static final String warningRecord = "DPBRIDGE_WARNING";
    private static final String readErrorRecord = "DPBRIDGE_READ_ERROR";
    private static final String resultRecord = "DPBRIDGE_RESULT";
    private static final String fatalRecord = "DPBRIDGE_FATAL";

    private static final CountDownLatch done = new CountDownLatch(1);
    private static final AtomicBoolean fatalPrinted = new AtomicBoolean();
    private static final IdentityHashMap<DataAsset, String> sourcePaths = new IdentityHashMap<>();
    private static Path assetsRoot;
    private static Path dataDirectory;
    private static volatile int resultCode = 20;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println(fatalRecord + "\t" + encode("Expected: <server-assets> <isolated-data-directory>"));
            System.exit(24);
        }

        assetsRoot = Path.of(args[0]).toAbsolutePath().normalize();
        dataDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        Log.useColors = false;
        System.out.println(protocol + "\t1");

        ApplicationCore core = new ApplicationCore() {
            @Override
            public void setup() {
                Core.settings.setDataDirectory(new Fi(dataDirectory.toFile()));
                Vars.headless = true;
                Vars.loadLocales = false;
                Vars.net = new Net(null);
                Vars.tree = new FileTree();
                Vars.init();
                Vars.world = new World() {
                    @Override
                    public float getDarkness(int x, int y) {
                        return 0f;
                    }
                };
                Vars.content.createBaseContent();
                add(Vars.logic = new Logic());
                add(Vars.netServer = new NetServer());
                Vars.content.init();
            }

            @Override
            public void init() {
                super.init();
                try {
                    resultCode = validateAssets();
                } catch (Throwable throwable) {
                    printFatal(throwable);
                    resultCode = 21;
                } finally {
                    done.countDown();
                    Core.app.exit();
                }
            }
        };

        new HeadlessApplication(core, throwable -> {
            printFatal(throwable);
            resultCode = 22;
            done.countDown();
        });

        if (!done.await(120, TimeUnit.SECONDS)) {
            System.out.println(fatalRecord + "\t" + encode("Harness timed out waiting for Mindustry initialization."));
            System.exit(23);
        }
        System.out.flush();
        System.err.flush();
        System.exit(resultCode);
    }

    private static int validateAssets() throws Exception {
        Seq<DataAsset> allAssets = new Seq<>();
        int readErrors = 0;
        int externalAssets = 0;

        for (ContentType type : ContentAsset.loadableContent) {
            Path folder = assetsRoot.resolve("content").resolve(type.folderName);
            if (!Files.isDirectory(folder)) continue;

            for (Path file : supportedFiles(folder, DataAssetType.content)) {
                String relative = normalizedRelative(assetsRoot, file);
                try {
                    ContentAsset asset = new ContentAsset();
                    asset.readOverride("server-assets/" + normalizedRelative(folder, file), new Fi(file.toFile()), type);
                    sourcePaths.put(asset, relative);
                    allAssets.add(asset);
                } catch (Throwable throwable) {
                    readErrors++;
                    printReadError(relative, throwable);
                }
            }
        }

        for (DataAssetType type : DataAssetType.all) {
            if (type == DataAssetType.content) continue;
            Path folder = assetsRoot.resolve(type.folder);
            if (!Files.isDirectory(folder)) continue;

            for (Path file : supportedFiles(folder, type)) {
                String relative = normalizedRelative(assetsRoot, file);
                try {
                    DataAsset asset = type.create();
                    asset.readOverride("server-assets/" + normalizedRelative(folder, file), new Fi(file.toFile()));
                    sourcePaths.put(asset, relative);
                    allAssets.add(asset);
                    if (type != DataAssetType.patch) externalAssets++;
                } catch (Throwable throwable) {
                    readErrors++;
                    printReadError(relative, throwable);
                }
            }
        }

        allAssets.sort();
        Vars.state.data.load(allAssets);

        int failedAssets = readErrors;
        int warningCount = 0;
        int contentAssets = 0;
        int patchAssets = 0;

        for (ContentAsset asset : Vars.state.data.getContent()) {
            contentAssets++;
            Content content = asset.content;
            boolean contentErrored = content != null && content.hasErrored();
            boolean registered = content != null && containsIdentity(Vars.content.getBy(content.getContentType()), content);
            boolean nameBound = !(content instanceof MappableContent)
                || Vars.content.getByName(asset.type, ((MappableContent)content).name) == content;
            boolean failed = asset.errored || content == null || contentErrored || !registered || !nameBound;
            if (failed) failedAssets++;
            warningCount += asset.warnings.size;

            String reason = failureReason(asset, content, contentErrored, registered, nameBound);
            String contentName = content instanceof MappableContent
                ? ((MappableContent)content).name
                : content == null ? "" : content.toString();
            System.out.println(
                assetRecord + "\t" + encode(sourcePath(asset)) + "\t" + asset.type.name() + "\t"
                    + failed + "\t" + asset.warnings.size + "\t" + encode(contentName) + "\t" + encode(reason)
            );
            for (String warning : asset.warnings) {
                printWarning(sourcePath(asset), warning);
            }
        }

        for (PatchAsset asset : Vars.state.data.getPatches()) {
            patchAssets++;
            if (asset.error) failedAssets++;
            warningCount += asset.warnings.size;
            System.out.println(
                patchRecord + "\t" + encode(sourcePath(asset)) + "\t" + asset.error + "\t" + asset.warnings.size
            );
            for (String warning : asset.warnings) {
                printWarning(sourcePath(asset), warning);
            }
        }

        int addedContent = 0;
        for (Seq<Content> contents : Vars.content.getContentMap()) {
            for (Content content : contents) {
                if (content.minfo.mod == DataPatcher.dpMod && !content.hasErrored()) addedContent++;
            }
        }

        System.out.println(
            resultRecord + "\t" + allAssets.size + "\t" + contentAssets + "\t" + patchAssets + "\t"
                + externalAssets + "\t" + failedAssets + "\t" + warningCount + "\t" + addedContent
        );
        return failedAssets == 0 ? 0 : 10;
    }

    private static java.util.List<Path> supportedFiles(Path folder, DataAssetType type) throws Exception {
        try (var stream = Files.walk(folder)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> type.extensions.contains(extension(path)))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot == -1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean containsIdentity(Seq<Content> contents, Content expected) {
        for (Content content : contents) {
            if (content == expected) return true;
        }
        return false;
    }

    private static String failureReason(
        ContentAsset asset,
        Content content,
        boolean contentErrored,
        boolean registered,
        boolean nameBound
    ) {
        StringBuilder reason = new StringBuilder();
        if (asset.errored) appendReason(reason, "asset.errored");
        if (content == null) appendReason(reason, "content-null");
        if (contentErrored) appendReason(reason, "content.hasErrored");
        if (content != null && !registered) appendReason(reason, "removed-from-content-registry");
        if (content instanceof MappableContent && !nameBound) appendReason(reason, "name-not-bound-to-content");
        return reason.toString();
    }

    private static void appendReason(StringBuilder target, String value) {
        if (target.length() > 0) target.append(", ");
        target.append(value);
    }

    private static void printWarning(String path, String warning) {
        System.out.println(warningRecord + "\t" + encode(path) + "\t" + encode(warning));
    }

    private static void printReadError(String path, Throwable throwable) {
        System.out.println(readErrorRecord + "\t" + encode(path) + "\t" + encode(finalMessage(throwable)));
    }

    private static void printFatal(Throwable throwable) {
        if (!fatalPrinted.compareAndSet(false, true)) return;
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        System.out.println(fatalRecord + "\t" + encode(writer.toString()));
    }

    private static String finalMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private static String sourcePath(DataAsset asset) {
        return sourcePaths.getOrDefault(asset, asset.getFullPath());
    }

    private static String normalizedRelative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
