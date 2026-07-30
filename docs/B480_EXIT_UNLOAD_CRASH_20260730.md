# B477/B480 data-pack exit/reset crash diagnosis

Date: 2026-07-30 (Asia/Shanghai)

## Result

The reported map-exit crash and the reported editor/no-core crash are not caused by a converted turret, unit, or core declaration. The supplied log contains one exception, and it is an upstream Mindustry 159.7 / MindustryX B477-B480 `DataImagePacker.unload()` defect.

Any client-side world reset after loading a data pack with a multi-page main patch atlas can enter this path. A core-less editor playtest merely reaches a reset/return path more readily; there is no separate core-specific exception in the supplied log.

## Evidence

Captured log:

`work\diagnostics\client-b480\last_log-20260730-072305.txt`

Log SHA-256:

`9BB2DBFC34311F1B6CDCF546C7FC98807D069BCCE57CAEB34FB13842F494942A`

Relevant stack:

```text
java.lang.IllegalArgumentException: key cannot be null.
    at arc.struct.ObjectSet.locateKey(ObjectSet.java:162)
    at arc.struct.ObjectSet.remove(ObjectSet.java:252)
    at mindustry.mod.DataImagePacker.unload(DataImagePacker.java:175)
    at mindustry.mod.DataManager.unload(DataManager.java:223)
    at mindustry.core.Logic.reset(Logic.java:306)
    at mindustry.ui.dialogs.PausedDialog.checkPlaytest(PausedDialog.java:184)
    at mindustry.ui.dialogs.PausedDialog.runExitSave(PausedDialog.java:203)
```

The two locally named client jars are byte-identical:

```text
<local-MindustryX-B480-Desktop.jar>
<local-MindustryX-B477-Desktop.jar>
SHA-256: 7A902938BCE8C07BC08642DB30B8D1830B57517E3F3EAEFA0A96AA47128CE381
```

`javap -c -l -p` of that exact jar confirms that `DataImagePacker.unload()` obtains an iterator from `patchAtlas.getTextures()` and then calls `remove()` on the same `ObjectSet` inside the loop. The bytecode and line tables are saved in:

`work\diagnostics\client-b480\DataImagePacker-javap-user-client.txt`

The same implementation remains present in the local Mindustry and MindustryX source trees, and in current upstream Mindustry commit `d07016eea966f40a2ac9b442d0085bbc50a9e935` checked on 2026-07-30.

## Exact defect

The shipped implementation is effectively:

```java
for(var texture : patchAtlas.getTextures()){
    patchAtlas.getTextures().remove(texture);
}
for(var region : patchAtlas.getRegions()){
    patchAtlas.getRegionMap().remove(region.name);
}
patchAtlas.dispose();
patchAtlas = null;
```

There are two wrong target collections:

1. Patch textures were previously added to `Core.atlas.getTextures()`, but unload removes them from `patchAtlas.getTextures()` while iterating that same set. Arc's open-addressed `ObjectSet.remove()` can shift subsequent entries. The iterator has already cached its next index, so a later `next()` may return `null`; the next `remove(null)` throws the logged exception.
2. Patch regions were previously added to `Core.atlas.getRegionMap()`, but unload removes them only from `patchAtlas.getRegionMap()`. Even when no exception occurs, stale global atlas mappings remain.

The intended implementation is:

```java
for(var texture : patchAtlas.getTextures()){
    Core.atlas.getTextures().remove(texture);
}
for(var region : patchAtlas.getRegions()){
    Core.atlas.getRegionMap().remove(region.name);
}
patchAtlas.dispose();
patchAtlas = null;
```

A minimal source patch is saved at:

`docs/patches/DataImagePacker-unload-fix.patch`

## Why later imports become broadly corrupted

`DataManager.unload()` performs these operations in order:

1. unapply content patches;
2. unload bundles;
3. unload the image packer;
4. unload audio;
5. clear all asset collections.

The exception occurs at step 3. Therefore content has already been partly reset, but the image packer, sounds, and asset collections are not fully cleared. `Logic.reset()` also aborts before creating a fresh `GameState`. This exactly explains why, after the first failure, later imports in the same client process can report errors for many otherwise valid new contents. A full client restart is required after this exception.

## Why Saturation Firepower triggers it

Candidate ZIP:

`work\saturation-clientfix-20260730-070717\sfire-mod-dp-v159.7.zip`

ZIP SHA-256:

`A687BC24F4D1503754AC3293A9D4CBCA106612101983994A612C375FD3F88BEF`

An exact B480 `PixmapPacker(4096, 4096, 2, true)` replay of the non-environment images gives:

```text
input images:        1678
successfully packed: 1676
area used for B480 sizing: 49,641,057 px
successfully packed raw area: 48,956,157 px
padded area:         50,525,073 px
target page size:    4096 x 4096
main atlas pages:    4
```

Ten shuffled-order replays also produced four pages. `PixmapPacker.generateTextureAtlas()` creates one `Texture` per non-empty page, so the buggy unload receives four texture entries. Replay files:

```text
work\diagnostics\client-b480\MainAtlasPackHarness.java
work\diagnostics\client-b480\main-atlas-current.txt
work\diagnostics\client-b480\main-atlas-random10.txt
```

The two decode failures shown by the replay are separate unsupported grayscale+alpha PNGs; they are caught by the client packer and are not the exit exception.

## Converter-side feasibility

### Pure data-pack repair

Not generally possible. A data pack can supply content, patches, images, bundles, and audio, but cannot replace Java method bytecode or register a reset listener. The converter cannot directly repair `DataImagePacker.unload()` inside an unmodified client.

### Pure data-pack mitigation

The current exception can usually be avoided when the non-environment patch atlas contains at most one texture page. With one set entry, the iterator discovers the end before the only entry is removed. This is only a mitigation: the shipped method still removes from the wrong collection and leaks/stales global atlas state.

For this candidate, one 4096 page has only 16,777,216 pixels before packing fragmentation, versus 50,525,073 padded pixels. Reaching one page would require eliminating or rescaling more than two thirds of the current main-atlas footprint. Removing only UI/generated conveniences such as `-full`, `-cell`, and previews is insufficient. A single-page mode would therefore substantially reduce visual fidelity for this mod and should not be presented as a transparent fix.

### Recommended directions

1. **Correct fix:** patch B477/B480/current client `DataImagePacker.unload()` with the two-line target-collection correction above and submit it upstream.
2. **Converter protection:** reproduce the B480 main-atlas pack during conversion and emit a high-severity `B480_MAIN_ATLAS_MULTI_PAGE_UNLOAD_RISK` diagnostic whenever pages exceed one.
3. **Optional strict compatibility mode:** prune optional/unreferenced images and require one main page; if one page cannot be reached without dropping required assets, fail or clearly report the fidelity loss rather than claiming exit safety.
4. **Do not use a generated agent/runtime companion as the default workaround.** Every client would need it, which violates the desired portable DP-only deployment model.

The already implemented generated-content sentinel avoids the earlier first-import `createIcons -> reloadImages -> unload` path. It cannot prevent the unconditional `DataManager.unload -> DataImagePacker.unload` call made during world reset/exit.

## Unrelated log entry

The duplicate `mindustryx` mod-name error at startup is unrelated to this data-pack reset crash.
