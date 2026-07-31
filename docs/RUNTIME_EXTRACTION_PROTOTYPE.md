# Local runtime content extraction prototype

Status: experimental, local-only, and deliberately separate from `bridge-converter`.

## Why this exists

Static Java AST conversion cannot recover content from a release JAR that contains only `.class`
and `classes.dex`. The `bridge-runtime-extractor` prototype instead starts the real Mindustry
v159.7 headless runtime in a child JVM, lets the supplied Mod register its actual objects, and then
records the resulting Content registry.

The current prototype exports:

- Content internal `name`;
- `contentType`;
- concrete `runtimeClass`;
- Content ID;
- owning Mod internal/display name and version;
- actual loaded Mod path.
- the registration stack for every target Content;
- target Content counts immediately before and after `ContentLoader.init()`.

It does not yet snapshot fields or serialize the complete runtime object graph into HJSON.

## Security boundary

**This path executes arbitrary Mod bytecode.** A child JVM is a crash/isolation boundary, not a
security sandbox. The Mod can read and write files accessible to the current user, start processes,
or use the network. Therefore:

- this prototype requires the explicit `--allow-mod-execution` flag;
- use only locally and only with Mod files you trust;
- do not connect it to the removed Web upload path;
- for untrusted inputs, use a disposable VM/container and deny network and host filesystem access.

The launcher creates an isolated Mindustry data directory and copies only the target Mod into its
`config/mods` directory. It preserves the headless log and exact child command.

## Build

A full JDK is required. The launcher compiles a tiny trusted Probe Plugin against the exact supplied
server JAR at extraction time and targets Java 17 bytecode.

```powershell
.\scripts\gradle.ps1 :bridge-runtime-extractor:test :bridge-runtime-extractor:installDist --no-daemon
```

## Run

```powershell
.\bridge-runtime-extractor\build\install\bridge-runtime-extractor\bin\bridge-runtime-extractor.bat extract `
  --server-jar "C:\path\to\v159.7-server.jar" `
  --mod-jar "C:\path\to\trusted-mod.jar" `
  --output ".\work\runtime-new-horizon.json" `
  --work-dir ".\work\runtime-extractor" `
  --timeout-seconds 120 `
  --allow-mod-execution
```

`--mod-id` is optional when `mod.hjson` or `mod.json` contains a simple `name` field.

## New Horizon 2.2.1 local validation

Validated on 2026-08-01 with the official v159.7 release server:

```text
Server:
<repository-root>\work\mindustry-v159.7-server-release.jar

Mod:
<local-downloads>\NewHorizonMod.2.2.1.jar
```

The server JAR reports `build=159.7`, `type=official`, `modifier=release`, and has SHA-256
`E41289C32BCF765EB50FA131E6B515D741E20F7843FB567D3AA949E7461F22AB`. The same registry count was
also reproduced with the local B480 MDT/MindustryX server build.

The headless runtime loaded New Horizon successfully and emitted 382 actual Content instances:

| Content type | Count |
|---|---:|
| block | 281 |
| item | 18 |
| liquid | 17 |
| status | 21 |
| unit | 40 |
| weather | 1 |
| planet | 1 |
| sector | 2 |
| loadout_UNUSED | 1 |
| **Total** | **382** |

The run also proves why runtime extraction is higher-value than the current static result for this
Mod: the runtime contains 40 units and 281 blocks, including anonymous/custom New Horizon classes,
whereas the static source pass found 308 top-level declarations and the compiled JAR pass found no
Content.

Example runtime records include:

```json
{
  "name": "new-horizon-ancient-artillery",
  "contentType": "block",
  "runtimeClass": "newhorizon.content.blocks.TurretBlock$1",
  "modName": "new-horizon",
  "modVersion": "2.2.1"
}
```

The formal extractor run additionally verified:

```text
registrationTracker.status = installed
registrationTracker.preInitContentCount = 382
registrationTracker.postInitContentCount = 382
registrationTracker.tracedContentCount = 382
```

Representative traces resolve to the original debug source locations:

```text
new-horizon-hard-light       -> NHItems.java:23
new-horizon-ancient-artillery -> TurretBlock.java:61, NHBlocks.java:2157
new-horizon-macrophage       -> NHUnitTypes.java:991
```

Formal output and process log:

```text
work/runtime-new-horizon-formal-validated.json
work/runtime-extractor/run-20260731-201849-11380/headless.log
```

The JSON is 573,377 bytes with SHA-256
`79A1783666B8B0E748C67CD1F708BE17D4418FA8F1ADB0CAAB20F1CFE75F7E21`. Generated timestamps and
isolated Mod paths intentionally make successive snapshot hashes differ.

The launcher now rejects an extraction unless the Probe reports `installed`, both phase counts are
present, and the number of non-empty registration traces equals the final target Content count. A
failed or incomplete run exits non-zero while preserving the isolated directory, compile log,
headless log, tracker files, and any incomplete JSON for diagnosis.

## Current limitations and next work

1. Registry identity, source stacks, and phase counts are exported. Fields, nested
   weapons/bullets/abilities, collections,
   references, colors, effects and asset handles still need a cycle-safe schema.
2. Anonymous/custom subclasses must be mapped to a DP-supported base type while separately reporting
   overridden methods and runtime-only behavior.
3. Headless-incompatible Mods may fail before `ServerLoadEvent`; their complete registry cannot be
   extracted by this hook without a desktop worker or earlier instrumentation.
4. Dependencies are not resolved or downloaded. Copy/support for explicitly supplied dependency Mods
   is still required.
5. A Mod can intentionally terminate or tamper with the worker. Process timeouts and logs aid diagnosis
   but do not make execution safe.
6. The temporary `000-`/`100-` archive prefixes are diagnostic conveniences, not a formal ordering
   guarantee: Mindustry resolves metadata through dependency maps rather than promising filename order.
   Normally every Java Mod constructor runs during `Mods.load()` and gameplay Content is registered
   later from `loadContent()`, so the Probe can still install after another Mod object was constructed.
   A non-conventional Mod that registers Content directly in its constructor may run too early. The
   Probe checks that the registry is empty and writes `refused` if it is not; strict tracker validation
   then makes the extraction fail non-zero instead of silently returning an incomplete snapshot. A
   future hard guarantee should inject a temporary required dependency on the Probe while preserving
   the target metadata, or use an earlier lifecycle harness.

## Prototype implementation notes

The module has no compile-time Mindustry dependency. Before launch it reads the bundled
`runtime-trace-probe.java.template`, compiles it against the user-supplied server JAR with the JDK
compiler, and packages it as a temporary hidden Java Mod. The Probe is loaded before the target Mod;
while `Vars.content` is still empty, it replaces the loader with a `ContentLoader` subclass that calls
the original methods and records registration stacks plus pre/post-init counts.

The launcher then adds the server JAR to the child classpath. The worker registers an Arc
`ServerLoadEvent` listener through reflection before calling `mindustry.server.ServerLauncher.main`.
Because `ServerLauncher.main()` may return after starting Arc's application thread, the worker remains
alive until the listener finishes. After Mod loading and initialization, it reflects over
`Vars.content.getContentMap()`, filters by `Content.minfo.mod.name`, validates tracker completeness,
writes JSON, and terminates the child process.
