# Data Patch / Data Assets delta: Mindustry v159.7 → v160.5

Scope: the Data Patch (DP) / Data Assets machinery (`mindustry.mod.*`, `mindustry.mod.data.*`,
`mindustry.io.*`, `mindustry.core.ContentLoader|NetServer|NetClient`, `mindustry.net.NetworkIO`)
as it affects `mod-dp-bridge`.

## Provenance and method (read this before trusting any claim)

| Fact | Evidence |
| --- | --- |
| `mod-dp-bridge\work\samples\Mindustry-160.5\core\src` is a real v160.5 working tree | it carries its own `.git` with `.git/shallow` and a sparse-checkout of `core/src` only |
| v160.5 differs from v160.3 in **only three** files inside the whole `core/src` tree relevant here | byte-identical to `git show v160.3:…` for `DataManager`, `ContentParser`, `ClassMap`, `DataAssetType`, `DataAsset`, `ContentAsset`, `DataAssetCache`, `DataAudioLoader`, `DataBundleLoader`, `Mods`, `NetworkIO`, `ContentLoader`, `NetClient`, `World`; only `DataPatcher.java`, `DataImagePacker.java`, `NetServer.java` differ |
| Reference clone is a **2-commit shallow** clone | `.git/shallow` lists `254fd3e2…` (v160.3), `57221d4a…`, `89527f87…` (v160.2), `93c88fea…`; `git rev-list --count v159.7..v160.3` == 1, i.e. v159.7 is the **parent commit of v160.3**. `git diff v159.7 v160.3` is therefore an exact full delta, not a squash artifact |
| arc was updated | `gradle.properties`: `archash=208a754044` (v159.7) → `archash=68a04fab6e` (v160.x); `build.gradle` rhino pin changed |
| `@AllowSerialization` is a **new arc class in v160.x only** | `arc/util/serialization/AllowSerialization.class` is present in `mindustry-v160.5-server-release.jar` and **absent** from `work\mindustry-v159.7-server-release.jar` (verified with `ZipFile` entry enumeration) |

Every claim below is tagged:

* **[src]** — verified by reading v160.5 source at the cited `path:line`.
* **[src-diff]** — verified by `git diff v159.7 v160.3` (plus the three-file v160.3→v160.5 diff).
* **[bytecode]** — verified by `javap` on the two release jars.
* **[inferred]** — reasoning, not directly proven; listed again in "unknowns".

Line numbers for v160.5 refer to
`mod-dp-bridge\work\samples\Mindustry-160.5\core\src\…`; line numbers for v159.7 refer to
`git show v159.7:core/src/…` in `参考项目\Mindustry-master`.

---

## BOTTOM LINE — what must change in mod-dp-bridge for v160.5, ranked by risk

1. **R1 — Re-validate the ordinal/build gate for the target.** v160.5 = `build 160, revision 5`.
   `MindustryTarget` pins `build = 159, revision = 7` and
   `serverJarFileName = "mindustry-v159.7-server-release.jar"`
   (`bridge-model/src/main/kotlin/io/github/moddpbridge/model/MindustryTarget.kt:26-30`), and
   `RuntimeSnapshotMapper` rejects any snapshot whose `(build, revision)` differs from the target
   (`bridge-runtime-mapper/src/main/kotlin/io/github/moddpbridge/runtimemapper/RuntimeSnapshotMapper.kt:984-990`).
   Nothing in DP itself is build-gated **[src-diff: `DataPatcher.patchFormatVersion` is still `2`]**, but
   multiplayer is: the server hard-kicks a client whose `Version.build` differs
   (`core/src/mindustry/core/NetServer.java:282-285`). All player-facing DP work must therefore be
   tested on a **single, matched** build; a 160.5 client cannot join a 159.7 server.
   **Risk: blocks every acceptance test. Effort: config-only.**

2. **R2 — Multiplayer DP join is still non-atomic and is now *more* concurrent, not less.**
   The handshake is three asynchronous steps (`NetServer.java:330-339`, `:538-545`, `:962-996`,
   `:547-553`) and `writeWorld` re-reads the **live** content registry and the **live** asset list
   (`net/NetworkIO.java:45-71`). If DP assets/content change between `requestAssets` and
   `sendWorldData`, the client applies a patch set whose content IDs do not match the map it is
   about to read. v160.5 added a *dedupe* fix in that path (`NetServer.java:975-979`) but did not make
   it atomic. Realistic generator-side mitigation: emit DP packs whose external assets are stable
   (never regenerate/rename assets while a server is live), and treat "asset list mutated mid-join" as
   a known failure mode to be reproduced, not fixed, in the bridge.
   **Risk: the reported client crashes. Effort: policy + test harness, not code.**

3. **R3 — Refresh the pinned field/parser surface for vanilla `Block`/`UnitType`/`Weapon`/`BulletType`.**
   `ContentParser` is *functionally identical* between the two versions **[src-diff: only javadoc/indent
   churn in `core/src/mindustry/mod/ContentParser.java`]**, so the probe's *mechanism* is safe. But the
   *data* it reads is not: ~376 files changed between the tags, vanilla classes gained/lost fields, and
   `ClassMap` gained three entries (`NoAI`, `TriangleEffect`, `TargetDummy`) **[src-diff]**. A v159.7-era
   field whitelist will silently `Unknown field`-drop or mis-emit fields against 160.5.
   **Effort: re-run the runtime probe against the 160.5 jar, diff the emitted schema.**

4. **R4 — Stop treating `@AllowSerialization` as a DP concern; it is not one.**
   DP `type:` resolution never touches the arc gate: `DataPatcher` sets
   `cont.allowClassResolution = false` (`DataPatcher.java:76`) and `ContentParser.resolve` therefore
   only consults `ClassMap.classes` (`ContentParser.java:1438-1451`). The v160
   "JSON requires `@AllowSerialization`" change lives **only** in arc's
   `Json.resolveClass(String)` and only fires for the JSON key named by `Json.typeName`
   (arc default `"class"`). See Q2/Q3 for the exact bytecode. The observable direction of the change is
   **relaxation** for Mindustry: v159.7 additionally required `Serializable`/`JsonSerializable`
   (`JsonIO.java` override, present in v159.7, **removed in v160**) whereas v160 accepts
   `JsonSerializable` **or** `AllowSerialization` **[bytecode]**.
   **Effort: documentation only — but do not let anyone "fix" DP by adding the annotation.**

5. **R5 — Image and audio generator policy must be re-tuned.**
   v160.5 applies `Pixmaps.antialias` + `Drawf.checkBleed` to **every** packed patch sprite
   (`DataImagePacker.java:110-111`) **[src]**, so DP sprites are now post-processed exactly like vanilla
   ones — regenerate golden-image fixtures. Sounds ≤ 100 000 bytes are now `Sound.createLazy` instead of
   `Sound.createStream` (`DataAudioLoader.java:33-34`) **[src]**, so the bridge's
   "small ogg/mp3 decodes as a stream" assumption is wrong for 160.5.
   **Effort: small, but it invalidates existing golden outputs.**

6. **R6 — Structural validator needs three additions, none of them layout-breaking.**
   `ALLOWED_ROOTS` and `EXTENSIONS`
   (`bridge-target-1597/src/main/kotlin/io/github/moddpbridge/target/v1597/Mindustry1597StructuralValidator.kt:304-312`)
   still match `DataAssetType` exactly in 160.5 (`data/DataAssetType.java:8-13`) **[src]**. What is missing:
   (a) per-content-type path check using `ContentType.folderName` (the canonical subfolder is
   `statuses`, **not** `status`), (b) per-asset-type **name** uniqueness derived by
   `Strings.getFileNameWithoutExtension(path)` (DP names are effectively global per asset kind — a
   cross-kind basename collision is allowed, a cross-path one is not), (c) reuse of
   `DataAsset.validPath` (`data/DataAsset.java:117-124`) for the `..`/`.` rejection the editor now
   enforces. **Effort: ~30 lines.**

Everything else in the DP surface (folders, extensions, embedded/external split, save-chunk format,
`patchFormatVersion`, hash encoding, `ContentType` ordinals, `ContentAsset.loadableContent`) is
**unchanged**, so v159.7-authored DP files load on v160.5 — see Q7.

---

## Q1 — DP on-disk layout & format

**Verdict: no change. [src] + [src-diff]**

* `DataAssetType` is byte-identical between v159.7 and v160.3 (not in the diff), and identical to
  `git show v160.3:core/src/mindustry/mod/data/DataAssetType.java`. Enum order — which is the
  on-disk type id in saves **and** the ordering key for `orderedAssets` — is:

  ```java
  patch("patches",  Seq.with("json","hjson","json5"), PatchAsset::new, true),
  content("content",Seq.with("json","hjson","json5"), ContentAsset::new, true),
  bundle("bundles", Seq.with("properties"),           BundleAsset::new, false),
  image("sprites",  Seq.with("png"),                  ImageAsset::new,  false),
  sound("sounds",   Seq.with("mp3","ogg"),            SoundAsset::new,  false),
  music("music",    Seq.with("mp3","ogg"),            MusicAsset::new,  false);
  ```
  `core/src/mindustry/mod/data/DataAssetType.java:8-13`.
* There is **no `images` root**. The enum constant is `image`, the folder is `sprites`
  (`DataAssetType.java:11`), and `DataManager.getImages()` maps to `DataAssetType.image`
  (`DataManager.java:301-303`). A generator emitting `images/` produces a pack the game ignores
  (`ALLOWED_ROOTS` in the validator already excludes it).
* There is **no `content/<kind>` pluralization step**: `ContentAsset.getFullPath()` is
  `"content" + "/" + type.folderName + "/" + path` (`data/ContentAsset.java:46-48`) and
  `ContentType.folderName` is explicit (`ctype/ContentType.java:11-28`, e.g. `status("statuses",…)`).
  **[src-diff: `ContentType.java` is outside the v159.7→v160.3 diff, so the folder names are identical
  in both versions — including the pre-existing `folderName` field.]**
* Asset name derivation is unchanged: `DataAsset.setPath` normalizes `\`→`/` and sets
  `name = Strings.getFileNameWithoutExtension(path)` (`data/DataAsset.java:37-40`). Only the
  **basename** matters for a sprite's atlas region (`dp-` + name, Q5); the directory part only
  matters for `blocks/environment/` special-casing (`DataImagePacker.java:115`).
* `DataPatcher.patchFormatVersion` is **`2` in both** (`DataPatcher.java:41`; v159.7 line 39). Written
  as the first int of the `patches` save region (`io/SaveVersion.java:605`).
* `@AllowSerialization`-era addition to `DataAsset`: a new GUI-side helper only —
  `public static boolean validPath(String path)` rejecting `.`/`..` path segments
  (`data/DataAsset.java:117-124`) **[src-diff]**, used solely by the editor
  (`editor/data/MapImagesView.java:266`).

**Consequence for the generator: nothing to change in layout.**

---

## Q2 — `DataPatcher` behaviour that affects what a generator should emit

All citations are v160.5 unless marked v159.7.

### 2.1 `unapply`/`reapply` and double-array-fix — **[src-diff] + [bytecode]**

* v159.7 `DataManager.reloadContent` called `patcher.unapply(reloadArrays)` **before**
  `patcher.apply(...)` (v159.7 `DataManager` lines 29-33).
* v160.5 removed that call: `reloadContent` only calls `patcher.apply(getPatches(), getContent(),
  reloadArrays)` (`DataManager.java:29-34`), and `apply` itself un-applies when needed
  (`DataPatcher.java:93-98`) — with the *same* `reloadContentWorld` flag (`unapply(reloadContentWorld)`).
  **Verified in bytecode**: `DataManager.reloadContent(boolean)` compiles to exactly
  `patcher.apply(Seq,Seq,boolean); rebuildOrderedAssets();` with no `unapply` call
  (`javap -c mindustry/mod/DataManager.class`, offsets 0-20), while `reloadPatches` still calls
  `unapply()` then `apply(Seq,Seq)` (offsets 16-39) — matching the source.
* Net effect: `fixContentArrays()` runs **once** per reload instead of twice, and the
  `needsArrayFix` flag → array-resize side effect happens at a slightly different point in the
  apply sequence. Also, the surviving guard means an unapply that changes arrays is now conditional on
  the caller's flag rather than unconditional.

### 2.2 `needsArrayFix` / `fixContentArrays` — the biggest generator-visible fix **[src-diff] + [bytecode]**

* `needsArrayFix` is set when any content asset is of type `item`, `liquid`
  — **and, new in v160, `block`**:

  ```java
  // v160.5 DataPatcher.java:133-137
  if(asset.type == ContentType.item || asset.type == ContentType.liquid || asset.type == ContentType.block){
      needsArrayFix = true;
  }
  ```
  (v159.7 line 131: only `item`/`liquid`.)
* `fixContentArrays()` gained:
  * `ItemModule.empty.checkArrayCapacity(items)` — **new** (`DataPatcher.java:324`; v159.7 had no
    equivalent). Jar-verified: `javap -c` shows `getstatic ItemModule.empty` +
    `invokevirtual ItemModule.checkArrayCapacity` in `DataPatcher.fixContentArrays`.
  * `if(block.lastConfig instanceof Content c && c.removed) block.lastConfig = null;`
    (`DataPatcher.java:330-332`) — new stale-config scrub.
  * Reader/editor tile repair via `getReplacementBlock(Block)` and `Tile.updateBlockReference`
    (`DataPatcher.java:338-363`, helper at `:370-379`) — new.
  * **Bug fix**: existing buildings now resize `LiquidModule` with the **liquid** count.
    v159.7 line 332 was `b.liquids.checkArrayCapacity(items);` — a latent liquid-array corruption for
    any DP that added items. **[src-diff]** Confirm in v160.5 bytecode: the loop calls
    `ItemModule.checkArrayCapacity(int)` and `LiquidModule.checkArrayCapacity(int)` at distinct offsets.
* **The `if(!needsArrayFix) return;` guard at v159.7 line 315 is GONE** — `fixContentArrays()` now
  always executes its body (`DataPatcher.java:321-368`). Consequence: `ItemModule.empty` and every
  synthetic block's filter arrays are resized on **every** `apply`/`unapply` cycle even when no
  items/liquids/blocks were added.
* `ItemModule.checkArrayCapacity` itself changed: v160 also nulls the static `cacheFlow`/`cacheSums`/
  `displayFlow`/`flow` fields **[src-diff: `core/src/mindustry/world/modules/ItemModule.java`]**, i.e.
  all per-building item-flow graphs are dropped on resize, and `updateFlow()` now self-heals the array
  length. Not something a generator emits, but it explains "flow readouts reset after a patch reload".

### 2.3 Sorting order — **[src]**

* `DataPatcher.apply` sorts content assets only: `content.sort();` (`DataPatcher.java:114`).
  `ContentAsset.compareTo` sorts by `ContentType` ordinal first, then `DataAsset.compareTo`
  (type, then `path`) (`data/ContentAsset.java:78-85`, `data/DataAsset.java:132-136`).
  **Ordinals and the comparison are unchanged**, so v159.7-authored DP packs load content in the same
  order on 160.5. Patches are **not** sorted by `DataPatcher`; their order comes from
  `DataManager.rebuildOrderedAssets()` concatenating `assets.get(type)` per `DataAssetType.all`
  (`DataManager.java:244-256`), and the editor sorts with `prev.sort()` before `state.data.load`
  (`editor/data/MapAssetsDialog.java:211-214`) using the same comparator.

### 2.4 Patch add/remove/merge operators — **[src]**

Unchanged: `"+"` (add/addAll) for `Seq`/array/`ObjectSet` (`DataPatcher.java:460-560`),
`"-"` removal for `ObjectMap`/`ObjectFloatMap` (`:575-581`, `:596-602`), index assignment with bounds
warnings (`:519-539`), `consumes.remove/all` via `readBlockConsumers` (`ContentParser.java:523-…`).
One behavioural delta:

```java
// v160.5 DataPatcher.java:696-699  (inside assignValue, "assign each field manually" branch)
var childFields = parser.getJson().getFields(prevValue.getClass().isAnonymousClass() ? ... : prevValue.getClass());
visit(prevValue);          // <-- NEW in v160
```
**[src-diff]** v159.7 did not call `visit(prevValue)` here. `visit` registers `after(c::afterPatch)`
for `Content` objects and pushes onto `visitStack` (`DataPatcher.java:381-386`), so nested-object
patches now (a) trigger `afterPatch()` on the nested content and (b) provide a better parent for
draw-part/weapon `load(...)` resolution in `created(...)` (`:396-417`). A generator that relies on
`afterPatch` side effects for nested content will see them fire now.

### 2.5 `requiredPlanets` removal now mutates the caller's list — **[src]**

`DataPatcher.apply` iterates patches with an explicit iterator and **removes** filtered entries:

```java
var patchIt = patches.iterator();
while(patchIt.hasNext()){
    PatchAsset set = patchIt.next();
    ...
    if(Vars.headless){ ... if(!Structs.contains(planets, Vars.state.rules.planet.name)){ patchIt.remove(); continue; } }
```
`DataPatcher.java:224-247`. v159.7 used a plain `for(var set : patches)` and merely `continue`d
(v159.7 lines ~222-243) **[src-diff]**. Effect: on a dedicated server, patches filtered by
`requiredPlanets` are **permanently dropped** from `state.data.getPatches()`, so a later
`reloadContent`/save/`/sync` no longer contains them. Client-side (`!Vars.headless`) the filter is not
applied at all, so server and client DP patch lists can diverge. Anything that assumes
"the server's patch list == the pack's patch list" is now wrong.

### 2.6 `@AllowSerialization` vs DP `type:` — **[bytecode] + [src]**

See Q3.5 for the mechanism and the exact bytecode. Summary for generator policy:

* DP `type:` values resolve through `ClassMap` only, with **no** serialization annotation
  requirement, in **both** versions.
* A dotted `type:` (e.g. `"type": "mymod.MyTurret"`) is rejected by the ContentParser override in both
  versions (`ContentParser.java:413-419`), because `allowClassResolution == false`
  (`DataPatcher.java:76`).
* The annotation gate is reachable only from JSON keys named `class`, through `JsonIO.json`
  (`io/JsonIO.java`, `typeName` defaults to `"class"` — arc default confirmed in bytecode).

### 2.7 Error/warning accounting and new failure modes — **[src]**

* `PatchAsset.error` / `PatchAsset.warnings`, `ContentAsset.errored` / `.warnings` are unchanged
  (`DataPatcher.java:118-156`, `:224-268`); the harness's counters
  (`DpApplyHarness.java:167-206`) remain valid.
* **New failure mode A**: `DataPatcher.apply` can now shrink the `patches` `Seq` it was given
  (§2.5), so `DpApplyHarness` reports fewer `DPBRIDGE_PATCH` records than the pack contains on
  headless runs. The harness runs `Vars.headless = true` (`DpApplyHarness.java:74`), so
  **`requiredPlanets` patches will silently vanish from the harness report today** — a reporting bug
  worth checking before it is blamed on 160.5.
* **New failure mode B**: `fixContentArrays()` iterates `Vars.world.tiles` when the editor is shown
  (`DataPatcher.java:338-363`). `tile.floor()`, `tile.overlay()` and `tile.block()` can return `null`
  (see Q4), and `getReplacementBlock(null)` → `Vars.content.block(null)` →
  `ContentLoader.getByName` returns `null` (`ContentLoader.java:228-233`) → `Blocks.air` →
  `Blocks.air.asFloor()` is a `ClassCastException` at `Block.asFloor()` (`world/Block.java:1105-1107`).
  Editor-only, but it is a real new crash surface.

---

## Q3 — `ContentParser` changes

**Verdict: no functional change. [src-diff]**

`git diff v159.7 v160.3 -- core/src/mindustry/mod/ContentParser.java` is 46 lines of comment removal and
re-indentation (the `//TODO this is untested`, `//TODO NoiseMesh is bad` comments and the `NoiseMesh` /
`HexSkyMesh` constructor argument reflow). No logic, no signature, no field-metadata behaviour change.

### 3.1 `readFields` / `getFields` metadata path — **[src]**

* `ContentParser.readFields(Object, JsonValue)` (`ContentParser.java:1268-1335`) is unchanged:
  it uses `parser.getFields(type)` (`:1274`), looks up
  `fields.get(child.name().replace(" ", "_"))` (`:1276`), warns and **skips** unknown fields because
  `ignoreUnknownFields = true` (`:58`, `:1277-1286`).
* It strips the `research` pseudo-field before the loop (`:1270`) and `type` via the
  `readFields(obj, json, stripType)` overload (`:1263-1266`, called with `true` for block/unit/weather,
  `:602`, `:682`).
* `checkNullFields` (`:1245-1261`) is what turns a missing required field into a content error; the
  implicit-nullable set is unchanged: `TextureRegion`, `TextureRegion[].class`, `[][].class`,
  `[][][].class` (`:60`).
* **Delta relevant to the probe**: the `@NoPatch` field set on `Block` changed by exactly one field —
  in v159.7 `@NoPatch` sat on `public boolean[] itemFilter = {}, liquidFilter = {};` (one annotation
  covering both, v159.7 `Block.java:404-405`), while v160.5 splits the declaration and annotates only
  `itemFilter` (`Block.java:410-412`) **[src]**. So `liquidFilter` becomes patchable on 160.5 and a
  v159.7-era emitter that dropped `liquidFilter` as "not patchable" is now over-restrictive. The
  `checkField` gate itself is unchanged (`DataPatcher.java:788-794`).

### 3.2 `allowClassResolution` / `allowAssetLoading` / `allowPatching` — **[src]**

* Definitions: `ContentParser.java:64-69` (`allowClassResolution` — "If false, arbitrary class names
  cannot be resolved with `Class.forName`"; `allowAssetLoading`; `allowPatching` — "If false, vanilla
  content cannot be edited").
* DP sets all three false (`DataPatcher.java:76-78`); identical in v159.7 (lines 74-76) **[src-diff]**.
* Consumption points: the parser's `resolveClass` override (`ContentParser.java:413-419`), the
  `resolve(...)` fallback (`:1438-1451`), sound/music loaders (`:321`, `:337`), and the
  `locate(...)`-based "patch instead of create" decisions (`:583`, `:620`, `:689`, `:704`, `:718`,
  `:751`, `:873`, `:925`, `:939`, `:1041`, `:1338`). All unchanged.

### 3.3 `type` resolution / ClassMap — **[src] + [src-diff]**

```java
// ContentParser.java:1433-1457
var out = ClassMap.classes.get(!base.isEmpty() && Character.isLowerCase(base.charAt(0)) ? Strings.capitalize(base) : base);
if(out != null) return (Class<T>)out;
if(base.indexOf('.') != -1 && allowClassResolution){ … Class.forName(base) … }
if(def != null){ warn("[@] No type '" + base + "' found, defaulting to type '" + def.getSimpleName() + "'", …); return def; }
throw new IllegalArgumentException("Type not found: " + base);
```
Identical in v159.7. What changed is `ClassMap`'s contents **[src-diff]** — three new entries:
`NoAI` (`ClassMap.java:23`), `TriangleEffect` (`:77`), `TargetDummy` + `TargetDummyBuild`
(`:190-191`). No removals in the diff. Therefore: every short `type:` name a v159.7 DP pack uses is
still resolvable on 160.5, and three previously-unavailable names became available.

### 3.4 Fields of `Block`/`UnitType`/`Weapon`/`BulletType` readable by the probe — **[src-diff]**

The probe reads `Json.getFields(UnitType.class)` / `getFields(Block.class)` and mirrors
`ContentParser.readFields`
(`bridge-runtime-extractor/src/main/resources/runtime-snapshot-support.java.template:184-185`,
`:352-364`, `:694-696`). The mechanism is unchanged, so the probe will *run* on 160.5. The field
*inventory* is not unchanged — 376 files differ between the tags, e.g. `world/blocks/…`,
`entities/bullet/…`. This is the concrete reason the pinned schema must be regenerated (R3).

### 3.5 Where `@AllowSerialization` is actually enforced — **[bytecode]**

**v160.5** (`javap -p -c` on `arc/util/serialization/Json.class`, method
`protected <T> Class<T> resolveClass(String)`):

```
23: ldc_w  #373  // class arc/util/serialization/Json$JsonSerializable
27: invokevirtual Class.isAssignableFrom
33: ldc_w  #889  // class arc/util/serialization/AllowSerialization
37: invokevirtual Class.isAssignableFrom
43: … String/Long/Integer/Short/Byte/Boolean/Character special cases …
93: new arc/util/serialization/SerializationException
104: ldc_w #891 // String Class must implement JsonSerializable or AllowSerialization:
```
**v159.7** arc has **no** such check (`AllowSerialization` is not even in the jar); arc's
`resolveClass` only rejects `arc.util.Timer` subclasses.

The gate is reached from `Json.readValue(Class,Class,JsonValue,Class)`:

```
14: getfield Json.typeName        // arc default is the literal "class" (ctor bytecode: ldc #35 // String class)
25: JsonValue.getString(typeName, null)
41: invokevirtual resolveClass(String)
```

And v159.7 added a Mindustry-level check **on top of** arc, which v160 removed:

| version | `core/src/mindustry/io/JsonIO.java` |
| --- | --- |
| v159.7 | `@Override protected <T> Class<T> resolveClass(String className){ Class<T> result = super.resolveClass(className); if(Serializable.class.isAssignableFrom(result) \|\| JsonSerializable.class.isAssignableFrom(result)) return result; throw new SerializationException("Class deserialization not allowed: " + result); }` |
| v160.5 | override **absent** (whole file differs only by its removal) **[src-diff]** |

Only three core classes implement `AllowSerialization` in 160.5:
`game/MapObjectives.java:180`, `net/Administration.java:638`,
`maps/filters/GenerateFilter.java:16` **[src]**. Since `JsonIO.json` is the *only* Json instance with
`allowClassResolution == true` and its `typeName` is `"class"`, the practical v159.7→v160 delta for
Mindustry is: **deserializing a JSON object whose `"class"` key names a class** used to require
`Serializable`/`JsonSerializable`; it now requires `JsonSerializable`/`AllowSerialization`. For DP
content this code path is unreachable (§3.2, §2.6). **Net: DP `type:` semantics did not change.**

---

## Q4 — `DataManager`, content hash, and multiplayer identity

### 4.1 Content hash and generated sprite folders — **[src]**

* Content hash: `ContentAsset.hashData()` = `type.name() + "_" + DataAssetCache.encodeHash(Strings.sha256(data))`
  (`data/ContentAsset.java:35-37`) — i.e. sha256 over the **raw JSON/HJSON source string**, base32
  (RFC4648 alphabet `A-Z2-7`, no padding, 52 chars) via
  `DataAssetCache.encodeHash` (`DataAssetCache.java:52-84`). Unchanged.
* Generated sprites are keyed by that hash: `"generated/" + currentHash[0] + "/" + name + ".png"`
  (`DataManager.java:90`) with `currentHash[0] = hashes.get(content)` and
  `hashes.put(content, content.minfo.asset.hashData())` (`:51-55`, `:139-141`). `ImageAsset.isGenerated()`
  is simply `path.startsWith("generated/")` (`data/ImageAsset.java:12-14`).
* The cache itself: `Vars.assetCacheDirectory = dataDirectory.child("assetCache")`
  (`Vars.java:344`), files named by the 52-char encodeHash (`DataAssetCache.java:26-36`).
* **Consequence for the bridge**: `generated/<hash>/<name>.png` is a *byte-stable* naming contract as
  long as the content JSON bytes are stable. Any whitespace/ordering change in the emitted HJSON
  changes the hash, invalidates every cached generated sprite, and forces a re-pack on every client.
  Unchanged between versions — but it is the mechanism behind "sometimes the client has the sprite,
  sometimes not".

### 4.2 Save/net container format — **[src] + [src-diff]**

* `patchFormatVersion` = 2 written first in the `patches` region (`SaveVersion.java:604-608`); read and
  ignored (`:555-556`). Assets are written as
  `byte typeOrdinal; UTF path; boolean embedded;` then either the embedded payload
  (`DataAsset.write` / `ContentAsset.write`) or the raw 32-byte sha256 (`:610-624`). Reader validates
  the type id (`:571`) and warns `"Asset @: cache file not found."` when the hash is unknown locally
  (`:587-590`) — **it does not fail**.
* `dataPatchFormatVersion` is still `2` and `DataAssetType` ordinals are unchanged, so the
  **`patches` region is byte-compatible** in both directions for the types the bridge emits.
* `DataAsset.validPath` (§Q1) is *not* used on the save-read path, so a save containing `..` segments
  is still accepted; only the editor GUI rejects it.
* `Saves.hasExternalAssets()` reads the `hasExternalAssets` meta tag
  (`game/Saves.java:457-458`), written from `state.data.getAllExternalAssets().size > 0`
  (`SaveVersion.java:149`). Unchanged.
* `LoadDialog` warns (does not crash) when a **save** references missing external assets
  (`ui/dialogs/LoadDialog.java:236-252`).

### 4.3 The multiplayer join handshake, step by step — **[src]**

All in v160.5.

1. `NetServer.ConnectPacket` handler: name/ban/whitelist/version checks, then
   `sendWorldAndAssets(player)` (`core/NetServer.java:158-325`, call at `:320`).
   Version gate: `if(packet.version != Version.build && Version.build != -1 && packet.version != -1) kick(...)`
   (`:282-285`) — a build mismatch is a kick, not a crash.
2. `sendWorldAndAssets` (`:330-339`):
   * if `state.data.hasExternalAssets()` → `determiningAssets = true` and `sendAssetRequirements(player)`;
   * else → straight to `sendWorldData(player)`.
3. `sendAssetRequirements` (`:538-545`) runs on `mainExecutor` and calls
   `NetworkIO.writeRequiredAssets`, which **throws** if any asset lacks a hash:
   `if(asset.byteHash == null) throw new RuntimeException("Invalid asset (missing hash): " + asset.path);`
   (`net/NetworkIO.java:120-133`, throw at `:127`). **This throw happens on a worker thread and is not
   caught here** — the client is left in `determiningAssets` forever.
4. Client: `AssetRequirementStream` handler hashes each entry and asks for the missing ones
   (`core/NetClient.java:156-166`); `assetCache.has(...)` is `DataAssetCache.has`
   (`DataAssetCache.java:38-40`).
5. `NetServer.requestAssets` (`:962-996`) validates `hasBegunConnecting/determiningAssets/receivingAssets/hasConnected`,
   de-duplicates ids (`Bits requestedIds` — **new in v160.5**, `:975-979`), then on `mainExecutor`
   `NetworkIO.writeAssets` → **`asset.getCacheFileNoNull()`**, which throws
   `RuntimeException("Cache file for asset … not found!")` (`data/DataAsset.java:61-65`, used at
   `NetworkIO.java:154`), then `sendStreamAsync(new AssetStream(), …)`.
6. Client `StreamBegin` for `AssetStream` loads the blobs into the cache
   (`NetClient.java:187-215`, `NetworkIO.loadAssets` `:164-176`) and then posts `Call::requestWorld`.
7. `NetServer.requestWorld` (`:954-960`) → `sendWorldData` → `NetworkIO.writeWorld`
   (`net/NetworkIO.java:30-75`) → client `WorldStream` handler → `NetworkIO.loadWorld`
   (`:77-118`) → `finishConnecting()` (`NetClient.java:149-154`, `:702-711`).

**The three things that can go wrong, in the code's own words:**

* **(i) Non-atomic snapshot.** `writeWorld` re-reads `state.data.getAllAssets()` **twice removed** from
  the asset negotiation: once inside `writer.writeDataPatches(stream, false)`
  (`NetworkIO.java:48` → `SaveVersion.writeDataPatches` → `:607` `state.data.getAllAssets()`) and it
  also uses the *live* content registry via `writer.writeContentHeader(stream)` (`:63` →
  `SaveVersion.writeContentHeader` → `:657` `content.getContentMap()`). Meanwhile `writeAssets`
  (`:150-162`) and `writeRequiredAssets` (`:120-133`) operate on
  `state.data.getAllExternalAssets()` snapshots taken at *different* times
  (`NetServer.java:539`, `:974`, both on `mainExecutor`). If DP content/assets are re-applied in that
  window, the client applies patch set **A** and then reads a map written from content revision **B**.
* **(ii) Content-id mismatch.** `readContentHeader` builds the temporary mapper **by name**
  (`SaveVersion.java:627-654`, `map[type][j] = content.getByName(type, type == block ? fallback.get(name,name) : name)`),
  where `fallback` is the `SaveFileReader.fallback` map (`io/SaveFileReader.java:10-67`). Then
  `readMap` resolves each tile through `content.block(id)` (`SaveVersion.java:310`, `:324`) →
  `ContentLoader.getByID` (`core/ContentLoader.java:242-259`):
  * temporary mapper present and `id` in range and non-null → correct content;
  * temporary mapper present, `id` **out of range or entry null** → **`contentMap[0]`** (`:250`) — the
    tile silently becomes `Blocks.air`;
  * temporary mapper at `ContentType.block` **empty** (`length == 0`, which happens when the server
    wrote zero blocks for that type) → falls through to the id-indexed path (`:255`);
  * `id >= contentMap.size` → **returns `null`** (`:255-257`).
* **(iii) What the `null` does.** `readMap` guards blocks (`if(block == null) block = Blocks.air;`,
  `SaveVersion.java:326`) but **not** floors/overlays: `context.create(x, y, floorid, oreid, 0)` is
  called with raw ids at `:312`/`:316`. On the client, `MapIO` then constructs a `CachedTile` whose
  `setBlock(Block type)` assigns `this.block = type` with no check (`io/MapIO.java:97-99`) and whose
  `create(...)` resolves `content.block(floorID)` / `content.block(overlayID)` directly
  (`io/MapIO.java:153`); `MapIO.colorFor` then casts:
  `(((Floor)overlay).wallOre ? … )` (`io/MapIO.java:235`) → `ClassCastException`/NPE for a null or
  non-`Floor` overlay. `Tile.setBlock` itself NPEs on a null argument at its very first statement:
  `if(type.isStatic() || this.block.isStatic())` (`world/Tile.java:240-243`, called from
  `:349`, `:387`, `:278`, `:306`, `:341`) and `Tile.setOverlay` casts `(Floor)block` unchecked
  (`world/Tile.java:426-431`).
  `Tile.updateBlockReference` (`Tile.java:199-202`) is the DP-only escape hatch and does **no** null check.
* **(iv) Load-time throws that are not asset-related but are content-id related.**
  `ContentLoader.getByID` returning `null` also feeds
  `TypeIO.readObject`'s `contentType` case (`io/TypeIO.java:236` → `readContent` `:1192-1194`,
  `readBuilding` `:584`), and `techNodeType` dereferences immediately:
  `content.getByID(...).techNode` (`io/TypeIO.java:253`). With `mapper == null` (i.e. after
  `content.setTemporaryMapper(null)` in the `finally` at `SaveVersion.java:75` / `NetworkIO.java:116`)
  any later packet carrying content ids resolves against the *client's* id table.
* **(v) `readTeamBlocks` deliberately allows `null` blocks.** `data.plans.addLast(new BlockPlan(x, y, rot,
  content.block(bid), obj))` (`SaveVersion.java:489`) — the `Block` may be `null`, and the plan is
  consumed by the build/plan rendering code (see crash hypothesis C).
* **(vi) `/sync` skips asset negotiation entirely.** `clientCommands.register("sync", …)` calls
  `netServer.sendWorldData(player)` directly (`NetServer.java:510-523`), bypassing
  `sendWorldAndAssets`. On a server whose DP asset list changed since the client joined, `/sync`
  re-sends the world with no asset top-up.
* **(vii) New in v160.5: a third stream type shares the same connection.** `TextureStream` is
  declared at `net/Packets.java:74-76`, alongside `AssetRequirementStream` (`:78`), and is registered
  with the other packet types in `net/Net.java` (`registerPacket(AssetRequirementStream::new);` is at
  `:51`). It is handled by
  `NetClient.java:168-185` → `DataManager.addTexture/removeTexture`
  (`DataManager.java:191-201`) → `DataImagePacker.addTexture/removeTextureQueued`
  (`DataImagePacker.java:207-257`) with the `net-` region prefix (`:26`). The existing
  name/chunked-stream plumbing is reused, so a `net-`-prefixed region can now appear in the atlas
  independently of the `dp-` pack — and `DataImagePacker.unload()` now only removes the
  DP textures from `Core.atlas` (`:184`) plus the `serverImages` textures (`:197-204`), which is the
  v160.5 fix for a v160.3-era leak (`patchAtlas.getTextures().remove(...)` instead of
  `Core.atlas.getTextures().remove(...)`) **[src-diff v160.3→v160.5]**.

---

## Q5 — `DataImagePacker` / `DataAudioLoader` / `DataBundleLoader` deltas

### 5.1 `DataImagePacker` — **[src] + [src-diff]**

Two independent changes between v160.3 and v160.5, plus the v160 additions.

* **v160.5: alpha bleeding + antialiasing.** In the packing loop:

  ```java
  Pixmap pixmap = new Pixmap(cacheFile);
  Pixmaps.antialias(pixmap);     // NEW
  Drawf.checkBleed(pixmap);      // NEW
  String name = (image.isGenerated() && image.name.contains("-dp-") ? "" : regionPrefix) + image.name;
  ```
  `DataImagePacker.java:109-113`. This is the changelog item "images in data patches now have alpha
  bleeding and antialiasing applied". Both calls mutate the pixmap **before** packing, and
  `Drawf.checkBleed` writes into the pixmap's edge pixels, so the pixels that end up in the atlas are
  no longer identical to the bytes in the asset cache. Generator consequences:
  * do **not** pre-bleed/pre-multiply sprites yourself — you would bleed twice;
  * do **not** ship sprites whose outermost 1px is meaningful (UI borders, single-pixel frames,
    non-square hit outlines), because bleeding will overwrite it;
  * golden-image comparisons must be regenerated, and a "did the sprite survive byte-exactly"
    assertion is no longer a valid test of the packer.
* **`regionPrefix` is still `"dp-"`** (`DataImagePacker.java:24`) and the double-prefix guard is
  unchanged (`:113`). A generated image whose name already contains `-dp-` gets **no** prefix, i.e.
  its atlas region name equals `image.name` (`DataManager.java:89-90` strips a leading `dp-` when
  writing generated assets).
* **Sizing: there is no per-image size check in the packer.** `DataPatcher.maxImageSize = 2000`
  (`DataPatcher.java:40`) is enforced only (a) by the editor when importing (`editor/data/MapImagesView.java:46-47`,
  `:114`, `:140-141`) and (b) for server-pushed textures (`DataImagePacker.java:218-222`). The packer's
  own atlas target is `Mathf.clamp(Math.max(nextPowerOfTwo(sqrt(sumArea)*1.35), maxSize), 128,
  min(4096, Vars.maxTextureSize))` (`:71-72`), and `packer.pack(...)` failing with "one page" is what
  produces the env-specific warning (`:123-127`, `:134-136`). So an oversized DP sprite produces a
  *page-packing* failure at runtime, not a validation error. `regionPrefix` is applied to the packer,
  `PixmapPacker` splits/pads come from `PixmapPacker(target, target, 2, true)` (`:74`).
* **Duplicate names**: the packer itself does not detect them; `DataManager.regenerateContentSprites`
  builds `imageMap.put(regionPrefix + image.name, image)` (`DataManager.java:72-75`) so the last one
  wins, and `DataImagePacker.pack` prioritises generated images over pending ones
  (`:81-98`). Cross-path same-basename images are therefore **silently shadowed**. (Note:
  `DataManager.getAssets(type)` returns `assets.get(type, Seq::new).as()` — an `as()` copy, never the
  backing `Seq` — `DataManager.java:293-295`, so `getImages()` is a stable copy; `reloadImages(Seq)`
  is the only entry point that rewrites the backing list, `:185-189`.)
* **Unloading fix** (§4.3(vii)).
* **Server texture API** (`serverRegionPrefix = "net-"`, `:26`; `addTexture`, `:208`; `removeTextureQueued`,
  `:255`; `TextureStreamEvent` `game/EventType.java:122-129`; senders `core/NetServer.java:555-587`):
  brand new in v160, not used by map DP packs, but it means "an atlas region named `net-…`" is a
  legitimate thing a DP-adjacent mod can now create.

### 5.2 `DataAudioLoader` — **[src] + [src-diff]**

```java
Fi file = asset.getCacheFile();
//large sounds become streams, standard ones don't
Sound sound = Vars.headless || file == null ? new Sound()
            : file.length() > 100_000 ? Sound.createStream(file) : Sound.createLazy(file);
```
`DataAudioLoader.java:32-34`. v159.7/v160.3: `Sound.createStream(file)` unconditionally. So:
* DP `sounds/` entries **> 100 000 bytes** are still streams;
* **≤ 100 000 bytes are now lazily loaded**, which changes first-play latency and changes what happens
  when the cache file is deleted after load. Generator policy: keep the cache-file contract
  (assets must be present in `assetCache`), and stop asserting "every DP sound is a stream".
* Duplicate handling is unchanged and silent-ish: `registered.contains(realName)` →
  `Log.warn("Duplicate audio file: " + asset.name); continue;` (`:27-30`, `:50-53`), with
  `realName = "dp-" + asset.name.replace(' ', '_')` (`:26`, `:49`). A duplicate basename across
  `sounds/` and `music/` **collides in the same namespace** and the second one is dropped.
* Music is still `Music.create(file)` (`:55`), registration into `Core.assets` under `dp-<name>`
  (`:43`, `:60`), logic var `@sfx-dp-<name>` (`:41`), sound ids from `soundIdOffset = 100_000`
  (`:13`, `:23`, `:37`) — all unchanged.
* `unload()` (`:71-95`) unchanged; note it calls `Core.assets.unload(reg)` and
  `Vars.logicVars.remove("@sfx-" + reg)`.

### 5.3 `DataBundleLoader` — **[src]**

Byte-identical to v159.7 (not in the diff). Unchanged behaviour: bundle assets are matched to
`bundle` / `bundle_<locale>` by **asset name** (`DataBundleLoader.java:24-27`), loaded with
`PropertiesUtils.load` into the live `I18NBundle` chain (`:31-37`), and restored on `unload()`
(`:44-47`). So a bundle asset's derived name must be exactly `bundle` or `bundle_<locale>` (i.e. the
file must be `bundles/bundle.properties` or `bundles/bundle_zh_CN.properties`) or it is silently
ignored.

---

## Q6 — Structural / validation implications

Baseline: `bridge-target-1597/.../Mindustry1597StructuralValidator.kt`.

| Validator rule | v160.5 status |
| --- | --- |
| `ALLOWED_ROOTS = {patches, content, bundles, sprites, sounds, music}` (`:304`) | **still correct** — exactly `DataAssetType.folder` in 160.5 (`data/DataAssetType.java:8-13`) |
| `EXTENSIONS` (`:305-312`) | **still correct** — same `extensions` sets in 160.5, and the editor import uses `type.extensions` (`editor/data/MapAssetsDialog.java:168`, `:187`) |
| `content` requires `content/<type>/<file>` (`:131-140`) | **correct but under-specified**: the middle segment must be `ContentType.folderName` (`ctype/ContentType.java:33-43`), i.e. `statuses` (plural), `units`, `blocks`, `items`, `liquids`, `weather`. The editor reads only `ContentAsset.loadableContent` subfolders (`MapAssetsDialog.java:165-166`) |
| content kinds limited to items/blocks/liquids/statuses/units/weather (`:147`) | **still correct** — `ContentAsset.loadableContent` is unchanged (`data/ContentAsset.java:13`) |
| `DUPLICATE_DATA_PACK_PATH` case-insensitive (`:82-89`) | correct |
| `DUPLICATE_CONTENT_BASENAME` (`:153-163`) | **correct in spirit, wrong in scope**: the real constraint is *per DataAssetType* name/path uniqueness (`DataManager.hasAssetNameOrPath`, `editor/data/MapAssetsDialog.java:172`, `:191`). The `patches/`+`sprites/`+`sounds/`+`music/`+`bundles/` basenames are **not** a shared namespace (a `blocks/foo.json` and a `sprites/foo.png` coexist fine and are the *intended* pairing), so the current check is a false positive in exactly the common case |
| path escape via `../` (`:72-80`) | matches `DataAsset.validPath` (`data/DataAsset.java:117-124`) — note the validator also rejects `.`; `validPath` rejects both `.` and `..` segments too |
| `dataPatchFormatVersion = 2` (`:34`) | **still correct** (`DataPatcher.patchFormatVersion = 2`, `DataPatcher.java:41`) but the validator never asserts anything about it; harmless |
| missing rule: **no check that sprite basenames are unique across directories** | should be added — see §5.1 duplication |
| missing rule: **no check for `generated/` in a *content* path** | `ImageAsset.isGenerated()` keys off `generated/` (`data/ImageAsset.java:12-14`), so a hand-authored `sprites/generated/...` is treated as a hash-keyed generated image. Worth an INFO/WARN |
| missing rule: **no `.hjson`/`.json5` allowance note for `content` in the editor** | editor's mod-folder content scan accepts `json`/`hjson` only (`mod/Mods.java:885`), but `DataAssetType.content.extensions` accepts `json5` and the DP editor path uses `type.extensions` (`MapAssetsDialog.java:168`). Irrelevant for DP packs, relevant if a pack is dropped into a mod folder |

---

## Q7 — Do v159.7-authored DP files still load unchanged on v160.5?

**Yes, with two exceptions. [src] + [src-diff]**

Unchanged and load-bearing:

* `DataAssetType` (folders, extensions, ordinals, embedded flags) — identical.
* `patchFormatVersion` — `2` in both.
* `ContentType` ordinals **and** `folderName` values — identical, so `content/<folder>/<file>` paths and
  the type-id byte in saves are identical.
* `ContentAsset.loadableContent` — identical.
* `DataPatcher` base64-free HJSON/JSON/JSON5 parsing, `Jval.read(...).toString(Jformat.plain)` →
  `parser.getJson().fromJson(null, …)` — identical (`DataPatcher.java:232-233`).
* `ClassMap` — only additions, no removals.
* The `DataAssetCache` hash encoding and `generated/<hash>/` keying — identical.
* `DataPatcher.unapply`/`usedpatches`/`resetters` semantics — identical.
* `Mods.java`'s mod-folder content scan got *more* permissive (it now also accepts the canonical
  `folderName` folders, `Mods.java:878-882`), so a v159.7 pack that also happens to be installed as a
  mod folder keeps loading.

Conditions under which a v159.7 DP file would **not** behave the same:

1. **Patches carrying a field that v160 marked `@NoPatch`** (or a field that no longer exists) now
   produce a warning and are skipped: `checkField` → `warn("Field '@' cannot be edited.", field)`
   (`DataPatcher.java:788-794`) and the `Unknown field '@' for class '@'` warning
   (`:667`). Conversely, `Block.itemFilter`/`liquidFilter` became patchable in 160.5 (§3.1).
2. **Images**: the visual result changes for any sprite with a transparent border, because
   `Pixmaps.antialias` + `Drawf.checkBleed` now run on it (`DataImagePacker.java:110-111`). The file
   still loads; the pixels differ.
3. **Sounds ≤ 100 000 bytes** change load kind (stream → lazy) (`DataAudioLoader.java:34`).
4. **A pack whose patch list relies on `requiredPlanets` on a headless server** now loses those
   patches from `state.data.getPatches()` permanently (§2.5).
5. **A pack whose save was written by v159.7**: the `patches` region is compatible (same
   `patchFormatVersion`, same type ordinals), but the surrounding save format is version-gated:
   `readRegion("patches", …)` requires `version >= 12` (`SaveVersion.java:66`) and
   `SaveIO.versionArray` ends at `Save13` (`io/SaveIO.java:23`), so v159.7 saves remain readable.
   The `content`/`map`/`entities` regions changed shape between the versions
   (`SaveReadState` now carries `preview` and `allBuildings`; `readEntities` now takes a
   `SaveReadState`), which is a save-compat question for the bridge only if it ever parses maps.

---

## Crash hypotheses for multiplayer join/build

Ranked by likelihood given the evidence. Each entry lists the exact throwing line, why a DP pack
triggers it, and the cheapest confirming experiment.

### C1 (most likely) — client reads a map whose content ids belong to a different patch revision

Path: `NetServer.sendWorldAndAssets` (`core/NetServer.java:330-339`) → client
`AssetRequirementStream` (`core/NetClient.java:156-166`) → `AssetStream` (`:187-215`) →
`Call::requestWorld` → `NetServer.sendWorldData` (`:547-553`) → `NetworkIO.writeWorld`
(`net/NetworkIO.java:30-75`) → client `NetworkIO.loadWorld` (`:77-118`).

The asset requirement list and the world data are snapshotted at different times
(`NetServer.java:539` vs `NetworkIO.java:48`, `:63`), and both `writeAssets` (`:150-162`) and the
`requestAssets` de-dup path (`NetServer.java:974-979`) run on `mainExecutor` while the world write
runs on the net thread. When DP content is re-applied in that window (patch reload, editor asset
import, `regenerateContentSprites`), the client applies patch set A but materialises a map written
from revision B.

Throwing lines on mismatch:
* `SaveVersion.readMap` → `content.block(stream.readShort())` (`:324`) → `ContentLoader.getByID`
  (`ContentLoader.java:242-259`) → `null` for out-of-range ids (`:255-257`);
  guarded for the **block** at `:326` (`block = Blocks.air`), **not** for floor/overlay at `:312`/`:316`.
* Floor/overlay null or non-`Floor` → `MapIO.colorFor`'s `((Floor)overlay).wallOre`
  (`io/MapIO.java:235`, reached from `:153`) → `ClassCastException` / `NullPointerException`, or
  `Tile.setOverlay`'s `this.overlay = (Floor)block;` (`world/Tile.java:426-431`).
* Block null (any other call site) → `Tile.setBlock`'s first statement `type.isStatic()`
  (`world/Tile.java:240-243`, e.g. `setAir()` at `:341-343`, multiblock cleanup at `:278`).
* Temporary-mapper fallback silently substitutes `contentMap[0]` when the entry is missing
  (`ContentLoader.java:249-251`) — the "wrong block, no exception" variant of the same bug.

**Confirming evidence to collect**: client `crash-logs/*.txt` containing
`java.lang.ClassCastException: mindustry.world.Block cannot be cast to mindustry.world.blocks.environment.Floor`
or an NPE whose stack includes `mindustry.world.Tile.setBlock` **and**
`mindustry.io.SaveVersion.readMap`; plus server-side `Asset @: cache file not found.` warnings
(`SaveVersion.java:589`) in the same session. A deterministic repro is to call
`state.data.reloadPatches(...)` (or import the same pack twice) on the server between
`sendAssetRequirements` and `requestWorld` — e.g. by scripting a reload a fraction of a second after
a client hits `AssetRequirementStream`.

### C2 — join during an in-progress build: plans/entities referencing content the client does not have yet

Path: `NetworkIO.writeWorld` → `writer.writeTeamBlocks(stream)` (`net/NetworkIO.java:67`) →
`SaveVersion.writeTeamBlocks` writes `block.block.id` for every `BlockPlan`
(`SaveVersion.java:398-417`), and `writer.writeWorldEntities(stream, filter)` (`:68`) writes live
buildings/units.

On the client: `SaveVersion.readTeamBlocks` constructs
`new BlockPlan(x, y, rot, content.block(bid), obj)` **with a possibly null block**
(`SaveVersion.java:489`), and `readMap`'s tile path can hand a half-built block to
`tile.build.readAll(in, revision)` (`:365-382`) whose failure is wrapped as
`IOException("Failed to read tile entity of block: " + block, e)` (`:373-375`) — i.e. the
"being built" case reports itself as a *read failure* of the block entity, and `block` in that message
can print as a stale/removed content object because `DataPatcher.callContentRemove` sets
`value.removed = true` and calls `removeContent()` while the object may still be referenced by live
tiles (`DataPatcher.java:310-319`).
Additionally `TypeIO.readObject`'s content case can return `null` (`io/TypeIO.java:236`) and
`TypeIO`'s `techNodeType` dereferences without a check:
`content.getByID(ContentType.all[read.b()], read.s()).techNode` (`io/TypeIO.java:253`).

Why "while a DP block is being built" is special: a block under construction is a `ConstructBlock`
whose payload/config (`BlockPlan.config`) and whose `lastConfig` are content references; in v160.5
`fixContentArrays()` explicitly scrubs stale configs
(`if(block.lastConfig instanceof Content c && c.removed) block.lastConfig = null;`,
`DataPatcher.java:330-332`) and rewrites tile block references via `Tile.updateBlockReference`
(`:347-356`) — code that exists **because** a live world can hold references to removed content.
That scrub only runs in the editor (`Vars.ui.editor.isShown()`, `:338`), so on a dedicated server the
stale references survive into the world stream.

**Confirming evidence**: server log line `Failed to read tile entity of block: …` in the *client's*
log (`SaveVersion.java:374`), or a client stack containing `mindustry.io.TypeIO.readObject` /
`SaveVersion.readTeamBlocks`; and a repro that starts a client join while a builder is placing a DP
block on a map whose DP patch was (re)applied after the server started.

### C3 — asset-request / world-send aborting on the server, leaving the client stuck or content-less

Path A: `NetworkIO.writeRequiredAssets` throws
`RuntimeException("Invalid asset (missing hash): " + asset.path)` (`net/NetworkIO.java:127`) on the
`mainExecutor` thread (`NetServer.java:540-544`) with **no try/catch** → the client never receives
`AssetRequirementStream` and hangs in `determiningAssets`.
Trigger: any external asset (sprite/sound/music/bundle) whose `byteHash` is null — e.g. a
programmatically constructed `ImageAsset` added without `updateData` (`data/DataAsset.java:27-35`),
or a `DataAsset` deserialised from a save with `embedded == false` where only `stringHash` was set…

Path B: `NetworkIO.writeAssets` → `asset.getCacheFileNoNull()`
(`net/NetworkIO.java:154`) → `RuntimeException("Cache file for asset " + path + " not found!")`
(`data/DataAsset.java:61-65`). Caught by the `catch(Exception e){ Log.err(e); }` at
`NetServer.java:991-993`, so the server logs and the client waits for an `AssetStream` that never
arrives; `StreamBegin` never fires, so `requestWorld` is never called and the join times out.
Trigger: `DataAssetCache.get(stringHash)` returning null while `stringHash` is non-null — e.g. a
patch reload that replaces assets after `rebuildOrderedAssets()` but before the reader, or a cache
directory cleared by the "clear assets" button (`ui/dialogs/SettingsMenuDialog.java:624`
`assetCacheDirectory.deleteDirectory()`).

**Confirming evidence**: server logs containing `Invalid asset (missing hash):` or
`Cache file for asset … not found!` in the same session as a client that reports a join timeout
rather than a crash. Note that `LoadDialog` uses `getMissingAssets()` to *warn* (`LoadDialog.java:236`)
while the network path *throws* — the asymmetry is worth turning into a bridge-side pre-flight check.

### C4 (lower) — editor-only crash in the new stale-content repair

`DataPatcher.fixContentArrays` → `getReplacementBlock(tile.floor())` / `(tile.overlay())` /
`(tile.block())` (`DataPatcher.java:344`, `:345`, `:348`) → `getReplacementBlock` returns
`Blocks.air` for null/type-mismatched content (`:370-379`) → `Blocks.air.asFloor()`
(`world/Block.java:1105-1107`) throws `ClassCastException`, or
`tile.setFloor(getReplacementBlock(...).asFloor())` NPEs earlier for a null `tile.floor()`
(`world/Tile.java:309-310` dereferences nothing, but `Tile.recache()`/`build.onProximityUpdate()` at
`:319-322` do).
Trigger: map contains content from a removed/renamed DP pack and `needsArrayFix` is true while the
editor is open. v160 runs this body unconditionally now (the `if(!needsArrayFix) return;` guard is
gone), so the reachability is strictly higher than in v159.7.
Not a join-time crash — **but** it means "the map is damaged" states can now surface as a hard
exception instead of a silent skip, which will look like a regression when the bridge starts
producing maps for 160.5.

---

## Unknowns / needs runtime experiment

1. **Whether the shipped 160.5 client actually reuses a `160.3`-format save's `patches` region
   verbatim.** Static evidence says yes (`SaveVersion.java:604-625`, same `patchFormatVersion`,
   same `DataAssetType` ordinals) but no 160.5 client was run here. Experiment: load a DP save
   written by a 159.7-era tool on 160.5 and diff `DPBRIDGE_ASSET`/warning output from
   `DpApplyHarness` against a 159.7 run.
2. **The real frequency and exact stack of the reported client crashes.** Nothing in this report can
   distinguish C1 from C2 without the actual client crash log. Experiment: collect a client log for
   a failing join and check for the C1 vs C2 signatures named above.
3. **`Pixmaps.antialias`/`Drawf.checkBleed` exact pixel semantics** (does antialias mutate or only
   mark? is the bleed a 1px wrap?) — the calls are in the jar's dependency (`arc.graphics.Pixmaps`,
   `mindustry.graphics.Drawf`), not in `core/src/mindustry/mod`. Experiment: pack one known sprite
   through 160.5 and diff the atlas pixmap / the exported `sprites/*.png` from the editor's
   "export zip" button (`editor/data/MapAssetsDialog.java:240-259`).
4. **Whether `DataPatcher.apply`'s new `visit(prevValue)` changes `usedpatches`/`afterPatch`
   counts that the bridge's determinism harness asserts.** Static reading says yes; the harness
   output has not been re-generated against 160.5.
5. **Whether the deliberate de-dup `Bits requestedIds` (`NetServer.java:975-979`) fixed an observed
   real-world duplicate-id request**, i.e. whether a client can genuinely ask for the same asset id
   twice in 160.5. Not derivable from source.
6. **Whether `DataAsset.validPath` is also applied on the mod-folder / mod-zip asset path.** It is
   referenced only from `editor/data/MapImagesView.java:266` in `core/src`; a mod-side loader was not
   found (mod zips are only read by the editor, `editor/data/MapAssetsDialog.java:155-208`). So
   "DP packs shipped inside mod zips" are **not** auto-loaded in either version — unverified whether
   some other path (server `config/mods`, `scripts/`) picks them up.
7. **`Version.build`/`Version.revision` for the 160.5 jar** — `core/assets/version.properties` is not
   in the sparse checkout (only `core/src`), so "build 160, revision 5" was taken from the task
   statement, not verified from the artifact. Experiment: `unzip -p mindustry-v160.5-server-release.jar
   version.properties` (or read `Version.build` in a probe run).
8. **Whether `ItemModule.checkArrayCapacity`'s new cache-nulling (`ItemModule.java`,
   v160-diff) has any DP-visible consequence beyond a flow-readout reset.** Not exercised here.
9. **The bridge's own `server-assets/…` prefixed paths.** `DpApplyHarness` calls
   `asset.readOverride("server-assets/" + relative, …)` (`DpApplyHarness.java:134`, `:153`), which
   makes `asset.path` start with `server-assets/`. That path is what lands in the save when the
   harness-built state is written. No code path in either version strips that prefix, so it is
   presumably intentional (it is overwritten by `sourcePaths` when reporting), but it was not verified
   against a real save round-trip.
