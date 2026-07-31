# Runtime JAR asset staging

`bridge-runtime-assets` is the asset half of the runtime conversion pipeline. It is deliberately
independent from the runtime content-field snapshot: a release JAR remains the authority for bytes,
while the later runtime serializer remains the authority for registered content objects.

## Boundary

The stager accepts one published `.jar` and only emits paths that the existing `BridgeConverter`
can package as v159.7 data assets:

| JAR tree | Accepted extensions | Staged tree |
| --- | --- | --- |
| resolved-root `bundles/` (direct files only) | `.properties` | `bundles/` |
| resolved-root `sprites/` (recursive) | `.png` | `sprites/` |
| resolved-root `sounds/` (recursive) | `.ogg`, `.mp3` | `sounds/` |
| resolved-root `music/` (recursive) | `.ogg`, `.mp3` | `music/` |

The following trees never enter staging: `maps`, `scripts`, `shaders`, `textures`, and
`sprites-override`. Their counts are retained as `NON_DP_RUNTIME_ROOT_EXCLUDED` diagnostics.
Other JAR entries (classes, metadata and libraries) are scanned for structural safety and entry
limits but are not copied.

The root is resolved exactly like `mindustry.mod.Mods.resolveRoot`: if the archive contains exactly
one top-level child and it is a directory, that one outer directory is stripped. No general
`assets/` alias exists. For example, a JAR containing root `mod.hjson`, root `sprites/`, and
`assets/sprites/` loads only root `sprites/`; the nested `assets/sprites/` tree is excluded with
`NESTED_ASSETS_TREE_NOT_LOADED`.

The component does not rewrite namespaces, image bytes, audio containers or generated HJSON. A
future runtime conversion coordinator should:

1. call `RuntimeAssetStager.scan(releaseJar)`;
2. create an empty assembly directory;
3. call `snapshot.writeTo(assemblyDirectory)`;
4. add runtime-generated HJSON below `content/` in the same directory;
5. hand that directory to `BridgeConverter`.

Because the assembly directory then has canonical data-asset roots, `BridgeConverter` can package
the HJSON and exact JAR asset bytes together. The staging snapshot exposes the absolute source JAR
path, source JAR SHA-256, every selected source entry path and SHA-256, plus a content-derived
`stagingSha256`.

## Tool-directory precedence

Some release JARs contain build/helper output alongside runtime assets. New Horizon 2.2.1 is a
representative case:

- `bundles/blank/bundle_zh_CN.properties` collides by basename with the formal
  `bundles/bundle_zh_CN.properties`;
- `sprites/pre-processed/` contains same-basename copies of several formal sprites.

Mindustry v159 data assets use basename-oriented namespaces and the existing converter rejects most
such collisions. More importantly, `Mods.buildFiles` only calls `list()` on `mod.root/bundles`; it
does not recursively load nested bundle directories. The stager therefore excludes all
`bundles/blank` files. A same-name formal bundle produces `BUNDLE_BLANK_SHADOWED`; a helper with no
formal counterpart produces `BUNDLE_BLANK_EXCLUDED`. Other nested bundle directories produce
`NESTED_BUNDLE_EXCLUDED`.

Sprites are recursively loaded. The stager therefore applies a narrower rule:

- formal sprite wins over `sprites/pre-processed` (`PREPROCESSED_SPRITE_SHADOWED`).

Pre-processed sprites with no formal counterpart are retained and explicitly reported as
`PREPROCESSED_SPRITE_RETAINED`. Remaining ordinary basename collisions are not resolved arbitrarily:
all bytes are retained and an error-severity
`UNRESOLVED_RUNTIME_ASSET_BASENAME_COLLISION` diagnostic is produced for the coordinator/report.
The normal/generated sprite pair already supported by `BridgeConverter` is exempt. Sound/music
cross-root basename collisions receive `UNRESOLVED_RUNTIME_AUDIO_NAMESPACE_COLLISION`.

## Safety and determinism

The stager never loads input classes. It rejects:

- symbolic-link JAR inputs and ZIP symbolic-link entries;
- absolute, drive, colon, NUL, `.` and `..` entry paths (Zip Slip);
- excessive compressed JAR size, central-directory entry count, per-asset expanded size, total
  selected-asset expanded size, path length and compression ratio;
- encrypted/unsupported selected entries;
- ambiguous case-insensitive canonical output paths.

The default `maxEntries` is 100,000 rather than the static converter's older 2,048-file input limit,
because compiled Java mods commonly contain thousands of classes plus assets. New Horizon 2.2.1 has
3,177 non-directory JAR files and 1,585 candidate gameplay assets.

The in-memory file list is sorted deterministically. `writeTo()` only accepts a new or empty output
directory so stale data cannot leak into a conversion. Every output path is rechecked below the
normalized staging root before writing, and files are created with `CREATE_NEW`.

## New Horizon 2.2.1 verification

The published `NewHorizonMod.2.2.1.jar` was scanned directly after implementation:

```text
source JAR SHA-256  4d9c1d036e4bfe59f5c790c7064f776d63ebb951f24b4ab9aae71046e4615c87
central entries     3393 (files and directories)
accepted candidates 1579
staged files         1576
staged bytes         8346088
staging SHA-256      3e4aa153c5da56bd1f0af6509b2cce5b6ca090dd5e9bba72249115d670072cc2

bundle                 10
sound                  63
sprite               1503
music                    0
```

Observed decisions and diagnostics:

- 4 `bundles/blank` files excluded: 1 shadowed a formal bundle, 3 were tool-only;
- 91 PNGs existed under `sprites/pre-processed`: 88 retained and 3 shadowed by formal sprites;
- 16 files under maps/shaders/textures/sprites-override excluded by the project boundary;
- 2 unsupported asset extensions excluded (`sprites/*.aseprite`, `sounds/*.txt`);
- one remaining formal sprite basename collision was retained and reported:
  `sprites/unit/ground-unit/annihilation/weapons/large-launcher.png` versus
  `sprites/unit/weapons/large-launcher.png`.

The last collision is intentionally not guessed away. A later coordinator can stop before invoking
`BridgeConverter`, or use runtime atlas evidence to choose the actual winning region and record that
additional decision.
