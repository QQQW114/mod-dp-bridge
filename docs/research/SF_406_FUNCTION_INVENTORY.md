# Saturation Firepower 4.0.6 — functional inventory & Data-Pack expressibility assessment

Scope: the release tag source `work/samples/Saturation-Firepower-4.0.6` (tag `v-4.0.6`, commit `db983066`,
`mod.hjson` → `version: 4.0.6`, `minGameVersion: 160`, `java: true`, `main: SFire.SFireMod`, `name: sfire-mod`),
cross-checked against the released `work/samples/downloads/Saturation-Firepower-4.0.6.jar` (bytecode only, not executed)
and against the reference engine `work/samples/Mindustry-160.5/core/src`.

Evidence conventions used below:

- `SF:` = `work/samples/Saturation-Firepower-4.0.6/…`
- `V:` = `work/samples/Mindustry-160.5/core/src/mindustry/…`
- `JAR:` = `work/samples/downloads/Saturation-Firepower-4.0.6.jar` (entry names / `javap -p -c` output)
- `UP:` = `work/samples/Saturation-Firepower/…` (upstream copy, version 4.0.6.2, comparison only)
- Claims derived rather than read are explicitly marked **(inferred)**.

Method: source reading (no Gradle, no JAR execution) + structural counting (`Select-String`/regex over the exact
files) + bytecode-side cross-check of content counts with `javap -p -c` on the released JAR + reading the v160.5
`ContentParser` / `DataPatcher` / `ClassMap` / `DataManager` / `DataImagePacker` / `Mods` sources to decide what a
DP can and cannot carry. The companion DP-engine delta document is `docs/research/TARGET_1605_DP_DELTA.md`.

---

## 1. Scalar inventory

### 1.1 Java surface

| Metric | Value | Evidence |
|---|---:|---|
| `.java` files under `src/` | 26 | directory listing of `SF:src/SFire` |
| Total Java lines under `src/` | 22,331 | per-file line counts |
| Largest files | `SFBlocks.java` 10,420 / `SFUnitTypes.java` 9,060 / `SFOverride.java` 717 / `SFStatusEffects.java` 575 / `SFTechTree.java` 293 | per-file line counts |
| Custom classes (`SFire/expand/**`, `SFire/ai/**`) | 10 files, 5 of which declare a live class | see §2 |
| Files that declare content | `SFBlocks`, `SFUnitTypes`, `SFItems`, `SFLiquids`, `SFStatusEffects`, `SFSounds`, `SFAttribute`, `SFPlanets`, `SFSectorPresets`, `SFFx`, `SFColor` | `SF:src/SFire/SFireMod.java:35-47` (`loadContent` call order) |
| Inert legacy source shipped as an asset | `SF:assets/火力老文件/AccelTurret.java` (46 lines, `extends PowerTurret`, `accelMount`/`maxBoost`, `updateCooling` override) — never compiled, never referenced | `SF:assets/火力老文件/AccelTurret.java:9-46`; no `AccelTurret` reference anywhere under `src/` |

`loadContent()` order is exactly: sounds → statuses → attributes → items → liquids → units → blocks → overrides →
planet → sector presets → tech tree (`SF:src/SFire/SFireMod.java:35-47`). `init()` only calls `TechFix.init()`
(`SF:src/SFire/SFireMod.java:30`).

### 1.2 Registered content (counts)

Counts were produced two ways: (a) structural counting of the top-level declarations in the source, and (b) an
independent bytecode count of `putstatic` assignments in the released JAR. Both are reported because they disagree
in two places (see §1.5).

| Content kind | Count | Evidence |
|---|---:|---|
| Item | 15 | `SF:src/SFire/content/SFItems.java:11-14`; `JAR:SFire/content/SFItems.class` (15 `Item` fields) |
| Liquid | 7 (6 `Liquid` + 1 `CellLiquid`) | `SF:src/SFire/content/SFLiquids.java:14-16`; `actiNanofluid` is a `CellLiquid` at `:35` |
| StatusEffect | 23 | `SF:src/SFire/content/SFStatusEffects.java:25-30`; 23 `StatusEffect` fields in the JAR class |
| Attribute (not a `Content`) | 2 (`radioactivity`, `gas`) | `SF:src/SFire/content/SFAttribute.java:5-9` |
| Block | **264** live (268 declared assignments, 4 inside a block comment) | source: 268 indent-8 `x = new …` sites, minus `SF:src/SFire/content/SFBlocks.java:318-347` (commented `crystal*Cluster`); JAR: 264 `putstatic … :Lmindustry/world/Block;` |
| UnitType (top level) | **62** in source (57 `UnitType` + 5 `TankUnitType`); **59** in the JAR | source: 62 live `x = new UnitType|TankUnitType(` sites; JAR: 59 `putstatic … :Lmindustry/type/UnitType;` — delta = `ship11/ship12/ship13` (§1.5) |
| UnitType (inline, promoted) | 4 (`knocker-missile`, `blade-missile`, `sundown-missile`, `defense-platform-nuke-missile`) | `SF:src/SFire/content/SFUnitTypes.java:796`, `:1667`, `:3326`; `SF:src/SFire/content/SFBlocks.java:10161` |
| Planet | 1 (`tiberia`) | `SF:src/SFire/content/SFPlanets.java:16-18` |
| SectorPreset | 5 declared, **1 assigned** (`frozenFront`); `centerCore/crackHills/sandValley/craterBay` stay `null` | `SF:src/SFire/content/SFSectorPresets.java:9-25` (`centerCore` commented `:21-25`) |
| TechNode | 1 root + 249 `node()/nodeProduce()` calls inside `load()` | `SF:src/SFire/content/SFTechTree.java:25` (root), `:93-291` (nodes) |
| TechTree attachment points (`addToNode`) | 86 | `SF:src/SFire/content/SFTechTree.java:93-291` |
| Named `Effect` (in `SFFx`) | 1 named + 1 factory (`TriExplosion`) used at 8 sites | `SF:src/SFire/content/SFFx.java:19-33`, `:37`; call sites `SF:…/SFBlocks.java:669,726,727,5945,7172,7428,8896,9541` |
| Inline lambda `Effect` instances | **58** live (`SFBlocks` 43, `SFUnitTypes` 6, `SFOverride` 5, `SFFx` 2, `SFStatusEffects` 1, `ExplodeWall` 1); 59 `new Effect(` text matches, of which `SF:src/SFire/content/SFStatusEffects.java:423` is inside a comment | `new Effect(` counts per file, comment lines excluded |
| `Sound` | 9 referenced (11 `.ogg` shipped; `mapNew`/`mapWarning` unused) | `SF:src/SFire/content/SFSounds.java:13-18`; `SF:assets/sounds/` = 11 files |
| `Color` constants | 24 | `SF:src/SFire/content/SFColor.java:6-33` |

Block sub-inventory (264 live), by region marker in `SF:src/SFire/content/SFBlocks.java`:

| Region (source lines) | Total | Type breakdown |
|---|---:|---|
| environment (`:142-1038`) | 106 | Floor 45, StaticWall 17, Prop 14, OreBlock 8, SteamVent 7, TallBlock 5, Wall 5, Radar 2, Thruster 2, LiquidRouter 1 |
| crafting (`:1039-2352`) | 47 | GenericCrafter 38, AttributeCrafter 6, Separator 3 |
| wall + defense (`:2353-2654`) | 25 | Wall 15, ExplodeWall 2, ForceProjector 2, MemoryWall 2, AutoDoor 1, MendProjector 1, RegenProjector 1, ShieldWall 1 |
| transport + liquid (`:2655-2821`) | 13 | ItemBridge 2, LiquidBridge 2, ArmoredConduit 1, ArmoredConveyor 1, Conduit 1, Conveyor 1, Junction 1, LiquidRouter 1, MassDriver 1, Pump 1, StackConveyor 1 |
| power (`:2822-3335`) | 12 | ConsumeGenerator 3, GasTurbineGenerator 2, PowerNode 2, ThermalGenerator 2, Battery 1, ImpactReactor 1, NuclearReactor 1 |
| drill (`:3336-3535`) | 8 | AttributeCrafter 2, BurstDrill 2, PressureDrill 2, Fracker 1, SolidPump 1 |
| storage (`:3536-3613`) | 7 | CoreBlock 2, LandingPad 1, LaunchPad 1, SFCore 1, StorageBlock 1, Unloader 1 |
| turrets (`:3614-10743`) | 44 | ItemTurret 21, PowerTurret 17, EnhancedPowerTurret 2, LiquidTurret 2, ContinuousLiquidTurret 1, ContinuousTurret 1 |
| unit production (`:10744-10878`) | 6 | UnitAssembler 2, Constructor 1, Reconstructor 1, RepairTower 1, UnitFactory 1 |

Units: 62 top-level, split as enemy-only/T6 (14), tank line (6), air line (6), naval line (6), hover line (6),
"special force" (`farmer…terrascape`, 9), UTV tanks (5), drones (3) — declaration list `SF:src/SFire/content/SFUnitTypes.java:91-118`.
Explicitly declared but never assigned (dead `null` fields, no reader anywhere in `src/`):
`thunderclap`, `falcon`, `wyvern`, `ship21…ship43` (12 unit fields) and `crystalRedCluster`, `slagExtractor`,
`zheyue`, `chuifa`, `finalWeapon1`, `finalWeapon2`, `defensePlatform6`, `spaceFactory`, `spaceUpgrate`,
`spaceUpgratePro` (10 block fields) — verified by grepping every identifier across `src/`.

Asset inventory (`SF:assets/…`, 1,752 files total, incl. 1,678 `.png`):

| Folder | Files | Notes |
|---|---:|---|
| `sprites/` | 1,653 png (+1 non-png: `sprites/blocks/turrets/未启用/垂发/大垂发-heat.p`) | ammo 16, blocks 1,070, effects 6, items 22, sectors 5, status 23, units 509, root 2 (`gked.png`, `sflog.png`) |
| `sprites-override/` | 23 png | blocks 7 (`lancer*`), ui 5 (`logo/error/unit-*-ui`), units 11 (`crawler/flare/horizon/corvus/vela/retusa` previews & cells) |
| `sounds/` | 11 `.ogg` | boom, explosionbig, flying, hugeExplosion, lancer, laser, **mapNew, mapWarning**, missileX, release, shortLaser. Only **9** are wired up: `SFSounds` declares flying/hugeExplosion/missileX/lancer/shortLaser/boom/explosionbig/release/laser (`SF:…/SFSounds.java:13-18`); `mapNew.ogg`/`mapWarning.ogg` have no Java reference (inferred: map/event leftovers) |
| `bundles/` | 3 `.properties` (+ 2 `.copy`, 3 `.txt`) | `bundle.properties` 886 keys, `bundle_zh_CN` 918, `bundle_zh_TW` 865 |
| `maps/` | 2 `.msav` | `frozenFront.msav` (80,572 B), `荒芜矿区-饱和火力4.0-第七版.msav` (87,192 B) |
| root | 2 png + `adc.json` | `icon.png`, `preview.png`, `adc.json` (1,974 B, unreferenced) |
| `火力老文件/` (legacy) | 51 files | 3 block `.json`, 45 unit `.json`, 2 weather `.json`, `AccelTurret.java`; **not referenced by any Java code and not in a Mindustry-loaded folder** (`sprites|bundles|sounds|music|maps|schematics` are the loaded ones, `V:mod/Mods.java:47`) → inert payload |

Bundle key namespace histogram (`bundle.properties`): `block.` 514, `unit.` 187, `item.` 43, `ff.` 32, `cc.` 30,
`status.` 28, `liquid.` 17, `sector.` 12, `eva.` 10, `stat.` 7, `bar.` 3, `weather.` 2, `planet.` 1.
Every content key is written with the runtime prefix, e.g. `block.sfire-mod-xianqu.name`,
`unit.sfire-mod-flareX.name`, `sector.sfire-mod-frozenFront.name`, `planet.sfire-mod-tiberia.name`.
`ff.*`/`cc.*`/`eva.*` (72 keys) and the 2 `weather.*` keys have **no Java reference** (inferred: leftovers of a
removed scripted campaign / removed weather content; the matching legacy JSON lives in `assets/火力老文件/weathers/`).

### 1.3 Generated / atlas dependencies

- Sprite → region naming is `mod.name + "-" + filename-without-extension`, with a guard that does **not** double
  prefix when the part after the first hyphen already starts with the mod name
  (`V:mod/Mods.java:387-412`, especially `:409-411`). Hence `sprites/blocks/wall/memory-wall.png` → region
  `sfire-mod-memory-wall`; and because the guard suppresses the prefix only when the text after the first hyphen
  already starts with the mod name, `sprites/sectors/sector-sfire-mod-frozenFront.png` is registered as
  `sector-sfire-mod-frozenFront` (not `sfire-mod-sector-…`), which matches `SectorPreset.getRegions()`'s
  `"sector-" + name` (`V:type/SectorPreset.java:126-134`) because the preset's runtime name is
  `sfire-mod-frozenFront`.
  Spot checks that confirm the whole chain: `sprites/effects/tri.png` ↔ `region = "sfire-mod-tri"`
  (`SF:…/SFStatusEffects.java:42`, `:85`, `:130`, …); `sprites/ammo/arrow-bullet.png` ↔
  `new ShieldBreakBullet(16,150,"sfire-mod-arrow-bullet")` (`SF:…/SFBlocks.java:7768`);
  `sprites/units/weapon/red-point-defense.png` ↔ `name("red-point-defense")`
  (`SF:…/SFUnitTypes.java:1531`). Every one of the 118 `name(...)` call sites and the 37 `region = "…"` sites
  therefore resolves through a file whose basename is the string **after** the `sfire-mod-` prefix.
- Content names are prefixed **automatically at construction** for every `MappableContent`
  (`V:ctype/MappableContent.java:10-13` + `V:core/ContentLoader.java:184-186`), and `UnlockableContent extends
  MappableContent` (`V:ctype/UnlockableContent.java:25`), so blocks/units/items/liquids/statuses/planet/sector
  presets all become `sfire-mod-*` at runtime even though the source literals are unprefixed
  (`SF:…/SFBlocks.java:143-10878` uses `new Floor("snow-sand")` style everywhere; 264/264 block literals and
  62/62 unit literals are unprefixed). `Weapon` is **not** `MappableContent`, so the mod prefixes weapon/region
  names by hand through `SFireMod.name()` = `"sfire-mod-" + s` (`SF:src/SFire/SFireMod.java:14-17`), used 118 times.
- Duplicate atlas region: basename `defense-platform-nuke-missile` exists twice
  (`sprites/blocks/turrets/defense platform/` and `sprites/units/missile/`), i.e. the same packed region name twice;
  `packSprites` only duplicate-checks `sprites-override` (`V:mod/Mods.java:394-396`), so one of the two silently wins
  **(inferred)**.
- 89 sprite files live under WIP directories (`sprites/blocks/turrets/未启用/**`) for turrets that have no Java
  declaration at all (e.g. `多管炮`, `金属风暴`, `天罚`, `DUO`, `B6机炮`) — shipped, packed, referenced by nothing.
- The released JAR additionally contains `classes.dex` (833,672 B) and 1,682 `.class` entries (1,665 under
  `SFire/content`), i.e. it is the Android/desktop release artifact. No asset is generated at runtime by the mod
  itself; the only "generated" dependency is the engine-side atlas.

### 1.4 Top-level declarations per source file

| File | Declared content | Other top-level declarations |
|---|---|---|
| `SFBlocks.java` | 264 blocks (268 assignment sites, 4 commented) | 1 class, 1 method (`load()`), plus 2 method-local helpers (`int largeHealth` `:2354`, `ItemStack[] T3Item` `:10794`) and per-block locals that the converter must inline (e.g. `float cryoMul` `:7963`, `var qs1` `:81`) |
| `SFUnitTypes.java` | 62 units + 4 inline missile units | 1 class, 5 methods (`copy`, `copyMirror`, `copyRotate`, `copyRotRel` `:46-74`, `loadUnit()` `:120`) |
| `SFStatusEffects.java` | 23 statuses | 1 class, 1 method |
| `SFItems.java` | 15 items | 1 class, 1 method |
| `SFLiquids.java` | 7 liquids | 1 class, 1 method |
| `SFSounds.java` | 9 sounds (reflective field walk) | 1 class, 2 methods |
| `SFAttribute.java` | 2 attributes | 1 class, 1 method |
| `SFPlanets.java` | 1 planet | 1 class, 1 method |
| `SFSectorPresets.java` | 1 live sector preset (5 declared) | 1 class, 1 method |
| `SFTechTree.java` | 0 content, 250 tech nodes | 1 class, 9 methods (5 `node` overloads, 2 `nodeProduce`, `addToNode`, `load`) |
| `SFFx.java` | 1 named effect + 1 effect factory | 1 class, 2 statics (`rand`, `v`) |
| `SFColor.java` | 24 colors | 1 class |
| `SFOverride.java` | 0 content (mutation only) | 1 class, 1 method |
| `TechFix.java` | 0 content | 1 class, 1 method (reflection over `SFUnitTypes.class.getFields()`, `:46-61`) |
| `SFireMod.java` | 0 content | 1 class, 2 overrides + `name()` helper |

### 1.5 Source ↔ JAR divergences (bytecode cross-check)

1. **Units.** The tag source assigns 62 top-level units; the shipped JAR's `loadUnit()` performs only 59
   `putstatic …:UnitType`. The missing three are `ship11`/`ship12`/`ship13`
   (`SF:src/SFire/content/SFUnitTypes.java:7206` `new UnitType("arma")`, `:7226` `"bifurca"`, `:7252` `"cinnabar"`).
   The strings `arma`/`bifurca`/`cinnabar` do not occur in **any** class file in the JAR, while the
   corresponding sprite files exist (`SF:assets/sprites/units/spaceForce/{arma,bifurca,cinnabar}*.png`).
   The 3 fields are declared but never assigned in the JAR, exactly like the compat-free `thunderclap`/`falcon`/
   `wyvern`/`ship2x`/`ship3x`/`ship4x` fields (which are commented out in the source too). Cause is **(inferred)**:
   either the release JAR was built from a revision with that block commented out, or the Android R8 pass dropped
   assignments to never-read static fields. Practical impact: the released artifact registers **63** units
   (59 top-level + the 4 inline missiles), whereas the tag source yields **66** (62 + 4) — `arma`, `bifurca` and
   `cinnabar` exist only in the source (and their sprites ship unused).
2. **Blocks.** 264 in both, after discounting the 4 commented `crystal*Cluster` sites
   (`SF:…/SFBlocks.java:318-347`). No other divergence.
3. `SF:src/SFire/expand/blocks/arcFissionReactor.java` declares **no** live code (the class is inside the comment
   `:4-17`), so the JAR has 25 top-level classes from 26 source files; `arcFissionReactor` (the block) is actually a
   plain `new ConsumeGenerator("arc-fission-reactor")` (`SF:…/SFBlocks.java:3256`) — the custom reactor was
   abandoned and the file left behind.
4. Upstream (`UP:`, 4.0.6.2) adds 2 more custom block classes — `UP:src/SFire/expand/blocks/OmniBridge.java` (180
   lines) and `OmniLiquidBridge.java` — used at `UP:src/SFire/content/SFBlocks.java:2833` (`spBridge`) and `:2845`
   (`spLquidBridge`), plus 30 extra lines in `SFBlocks.java` and 8 extra sprites (1,686 vs 1,678 png).
   `SFUnitTypes/SFOverride/SFStatusEffects/SFTechTree` are byte-identical between tag and upstream.
5. Commented-out ranges that a naive declaration scan will over-count (all verified byte-for-byte against the JAR's
   `putstatic` set): `SF:…/SFBlocks.java:318-347` (`crystal*Cluster`, 4 `TallBlock`s),
   `SF:…/SFBlocks.java:10848-10859` (`spaceFactory`, references the non-existent `SFUnitTypes.piercer/vagris/shatter`),
   `SF:…/SFUnitTypes.java:650-678` (`thunderclap`, contains `constructor = EntityMapping.map(3)` at `:652`),
   `SF:…/SFUnitTypes.java:76-89` (`redPointDefenseBig`). The JAR confirms exactly 264 live block assignments and 59
   live top-level unit assignments, so the source's 62-vs-59 unit delta is entirely `ship11/ship12/ship13`.

---

## 2. Custom Java surface

Legend for "expressible?": **data** = the behaviour is a field value that a DP HJSON/`type` can carry;
**code** = it is a method/callback that no DP construct can install.

### 2.1 `SFire/SFireMod.java`

| Member | Extends / implements | Behaviour | Expressible? |
|---|---|---|---|
| `SFireMod extends Mod` (`:10`), `init()` (`:20-31`), `loadContent()` (`:34-47`) | `mindustry.mod.Mod` | Content registration order; `TechFix.init()` | **code**, but replaceable: DP loads `content/<type>/*.hjson` in its own order (`V:mod/ContentAsset.java:13`), and the "load order" concern disappears because DP binds names first (`V:mod/ContentParser.java:401-405`). The only non-replaceable part is `TechFix` (§2.2). |
| `static String name(String add)` (`:15-17`) | — | Region/weapon-name prefixing | **data**: just a rename rule the converter must apply (`dp-` instead of `sfire-mod-`, see §5.1) |

### 2.2 `SFire/TechFix.java` and `SFire/ai/**`

| Class / member | Extends | Behaviour | Expressible? |
|---|---|---|---|
| `TechFix.init()` (`:16-72`) | — | (a) adds `tiberia` to `shownPlanets` of every item/liquid/unit that is on `serpulo`, then `postInit()` (`:20-44`); (b) reflectively walks `SFUnitTypes.class.getFields()` and adds the planet to every unit field (`:46-61`); (c) for every block: if `requirements.length == 0` add **all** planets except the sun to `shownPlanets`, else `shownPlanets.clear()`, then `postInit()` (`:63-71`) | **code**. Per-content `shownPlanets`/`requiredPlanets` cannot be computed from data alone in a DP; the closest DP analogue is explicit `requiredPlanets`/`shownPlanets` arrays per content (and `DataPatcher`'s `requiredPlanets` filter, `V:mod/DataPatcher.java:224-247`), which the converter would have to **compute per content** (planet assignment by "has requirements / is on serpulo"). Note (c) also silently makes every *requirement-less* block show on `erekir`/`tiberia`, and every block *with* requirements show nowhere else. |
| `GunShipAI extends FlyingAI` (`SF:src/SFire/ai/GunShipAI.java:15`) | `mindustry.ai.types.FlyingAI` | `updateMovement()` (`:33-55`) orbit-attack: move to 80 % of `unit.type.range`, then circle the target at `orbitRadius = 240` with `orbitSpeed = 72 °/s`; `findTarget()` (`:58-63`) prefers the main target | **code** (orbit kinematics cannot be expressed). `FlyingAI` exists in `ClassMap`, so the *fallback* is expressible via `aiController: "FlyingAI"`. **This class is dead code**: the only occurrence of `GunShipAI` outside its own file is the comment `//aiController = GunShipAI::new;` (`:13`); no `SFUnitTypes` unit references it. |

### 2.3 `SFire/expand/blocks/**`

| Class | Extends | Overridden / added behaviour | Expressible? |
|---|---|---|---|
| `EnhancedPowerTurret` (`:17`) | `Turret` (`hasPower = true`, `:29`) | fields `enhancerItem`, `enhancedBullet`, `enhancedPattern`, `maxEnhanced` (`:19-24`); `setStats` (`:38-44`), `setBars` (`:46-54`); inner `PowerTurretBuild`: `handleItem` (`:60-64`), `useAmmo` (`:67-73`), `hasAmmo` (`:76-78`), `peekAmmo` (`:81-84`), `acceptStack` (`:87-90`), `acceptItem` (`:93-95`), `hasEnhancedAmmo` (`:97-99`), `shoot` (`:101-143`) | **code**. A second ammo channel fed by one specific item, with its own bullet/pattern and its own capacity, is a new ammo type. DP can express `PowerTurret`+`itemAmmo`/`ammoTypes` (`V:mod/ClassMap.java` has `PowerTurret`, `ItemTurret`, `ItemEntry`) but not "two bullets switched by an item counter". Instances: `SF:…/SFBlocks.java:7942` (`guangyin`), `:8949` (`poxiao`). |
| `ExplodeWall` (`:21`) | `Block` (sets `update/solid/flags=turret/priority=turret/group=walls`, `:43-56`) | fields `timerCheck/checkInterval/range/expDamage/shake/expLimit/buildingDamageMultiplier` (`:22-29`), `hitEffect`, inline lambda `waveEffect` (`:32-38`), `waveColor`; `setStats` (`:58-65`), `drawPlace` (`:67-72`); inner `ExplodeWallBuild`: `updateTile` (`:78-103`, intercepts bullets via `Groups.bullet.intersect`, damages/removes them, then `kill()`s itself), `onDestroyed` (`:106-113`), `drawSelect` (`:116-119`) | **code**. A "wall that shoots down incoming bullets when below 10 % HP and then self-destructs" has no declarative carrier. DP fallback `Wall` keeps only fields. Instances: `SF:…/SFBlocks.java:2393` (`expWall`), `:2399` (`expWallLarge`). |
| `MemoryWall` (`:16`) | `Wall` (`update = true`, `:29`) | fields `hitArmorUp/maxArmor/regenDelay/regenAmount/regenPercent` (`:17-25`); `setStats` (`:32-44`), `setBars` (`:46-61`); inner `MemoryWallBuild`: `updateTile` (`:67-84`), `collision(Bullet)` (`:86-128`) | **code**, and the mechanism is worse than "lost": `Building` has no `armor` field, so `armor` in `MemoryWallBuild` resolves to the **enclosing `Block.armor`** (`V:world/Block.java:195`, used for damage at `V:entities/comp/BuildingComp.java:1767` and read by the `armor` logic sensor at `:2125`). Every placed memory wall therefore mutates one shared block-level value, and the regen path sets it back to `0` (`SF:…/MemoryWall.java:78`). Instances: `SF:…/SFBlocks.java:2469`, `:2479`. A DP `Wall` can only carry a static `armor`. |
| `PressureDrill` (`:13`) | `Drill` (`flags = drill`, `:20`) | fields `maxFactor`, `minPowerNeed` (`:15-16`); `setStats` (`:23-28`), `setBars` (`:30-46`); inner `PressureDrillBuild`: `updateTile` (`:55-98`) recomputing `finalFactor = sqrt(clamp(power.graph.getPowerBalance()/(minPowerNeed*consPower.requestedPower(this)), 1, maxFactor))` (`:72-73`) and scaling drill speed by it (`:75-80`) | **code**. "Overdrive the drill by surplus grid power" has no field form; DP `Drill` keeps `drillTime`, `tier`, `hardnessDrillMultiplier`, `consumes`. Instances: `SF:…/SFBlocks.java:3337` (`energyDrill`), `:3356` (`heavyDrill`). |
| `GasTurbineGenerator` (`:15`) | `ConsumeGenerator` (`hasPower/outputsPower/flags = generator`, `:26-28`) | fields `timerUse`, `warmupSpeed`, `extraPower`, `powerUpSpeed` (`:17-18`); `setBars` (`:31-42`), `setStats` (`:44-48`); inner `GasTurbineGeneratorBuild`: `updateEfficiencyMultiplier` (`:65-74`), `updateTile` (`:76-113`, ramps `exPower` toward `extraPower`, `productionEfficiency = pow(warmup,5)*efficiency*efficiencyMultiplier*exPower` `:96`), `warmup()` (`:115-118`), `totalProgress()` (`:120-123`), `sense(LAccess.heat)` → `warmup` (`:125-129`), `write/read` extra floats (`:131-143`) | **code**. `warmupSpeed` exists on `ConsumeGenerator` and survives; `extraPower`, `powerUpSpeed`, the ramp, the `heat` sensor mapping and the extra save fields do not. Instances: `SF:…/SFBlocks.java:2958` (`gasTurbine`, uses `extraPower = 1.75f`, `powerUpSpeed = 0.0025f`), `:2985` (`eleCollector`, `extraPower = 10/3f`, `warmupSpeed = powerUpSpeed = 0.013f`). |
| `SFCore` (`:15`) | `CoreBlock` | field `maxNumber = 7` (`:20`); `canBreak(Tile)` (`:22-25`, breakable only if >1 core), `canReplace(Block)` (`:27-30`, only `alwaysReplace`), `canPlaceOn(Tile,Team,int)` (`:32-35`, caps cores per team), `setStats` (`:38-41`), `setBars` (`:43-51`) | **code**. Placement/removal policy and a custom bar. DP `CoreBlock` keeps data fields only. Instance: `SF:…/SFBlocks.java:3537` (`frontCore`). |
| `arcFissionReactor.java` | (all commented) | — | n/a |

### 2.4 `SFire/expand/bullets/**`

| Class | Extends | Overridden behaviour | Expressible? |
|---|---|---|---|
| `PowerupBullet` (`:6`) | `BasicBulletType` | `maxDamageMultiplier`, `damageUp` (`:9-11`); ctor sets `pierce = true`, `reflectable = false`, sprite (`:13-19`); `handlePierce` (`:21-42`) grows `b.damage` by `damage*damageUp` per pierce up to `damage*maxDamageMultiplier`, then `createSplashDamage`, then the default `removeAfterPierce` check | **code** (per-pierce damage math). Sites: `SF:…/SFBlocks.java:7210`, `:8899`, `:9599`, `:9695`. |
| `ShieldBreakBullet` (`:10`) | `BasicBulletType` | `shieldDamagePercent`, `shbreakEffect` (`:12-13`); ctor sets `hittable/reflectable/absorbable = false` (`:17-19`, `:24-26`); `hitEntity` (`:29-59`) deals `shield * shieldDamagePercent` instead of `damage` when that is larger, plays `shbreakEffect`, otherwise normal damage, then knockback/status and `handlePierce` | **code** (percent-of-current-shield damage + dedicated effect trigger). Sites: `SF:…/SFBlocks.java:7688`, `:7742`, `:7768`, `:9850`; `SF:…/SFUnitTypes.java:8543`. |
| `SizeDamageBullet` (`:7`) | `BasicBulletType` | `sizeDamageMul`, `basicSize` (`:10-12`); `hitEntity` (`:22-46`) scales damage by `hitSize/basicSize` above 30 px, applies `pierceArmor` variant, knockback, status | **code** (target-size-dependent damage). Sites: `SF:…/SFBlocks.java:8184`, `:8271`; `SF:…/SFUnitTypes.java:6836`. |

### 2.5 Custom classes declared inline in the big content files

There are **none**, in the sense the question asks: `SFBlocks.java` and `SFUnitTypes.java` contain exactly one
`class` declaration each (`SF:…/SFBlocks.java:60`, `SF:…/SFUnitTypes.java:44`) and **zero** `@Override` methods.
What they do contain are anonymous *subclasses that only set fields* (`new Wall("x"){{ … }}`), which is why the
whole 20k-line surface is field data. The only inline class in the content package that overrides a method is the
anonymous `StatusEffect` for `acidded` (`SF:src/SFire/content/SFStatusEffects.java:241-264`: extra field
`armorAcidDown`, `update(Unit,StatusEntry)` that eats `unit.armor` at `armorAcidDown/60` per tick and triples
`intervalDamage` once armor hits 0, plus `setStats`).

Cross-cutting counts for "code inside data files": 58 inline lambda `Effect`s, 43 of them inside 22 `SFBlocks`
contents (21 of those 22 are turrets; most-affected: `chongchao` 5, `cuowei` 5, `luolong` 4, `kuosan` 4,
`dianmai` 3, and `chemicalTank`, a `Wall`), 6 in 5 units (`air3` 2, `electrodile`, `hover3`, `hover4`,
`terrascape`); 8 `SFFx.TriExplosion(...)` call sites
(`ammoBox`, `ammoBoxLarge` ×2, `fangtian`, `sizhao`, `liemei`, `kuosan`, `zhulin`); 85 `drawer = new Draw…(){{…}}`;
91 `new RegionPart(`, 13 `new HaloPart(`, 10 `new ShapePart(`, 10 `new HoverPart(`, 1 `new FlarePart(`;
80 `parts.add`; 51 `abilities.add`; 106 `new Weapon(name(...))`, 7 `new PointDefenseWeapon` (6 in `name(...)` form),
5 `new RepairBeamWeapon`;
16 `setEnginesMirror(`; 25 `new UnitEngine(`; 26 `ammo(`/10 `ammoTypes.put`/7 `ammoTypes.putAll`;
101 `consumePower(`, 51 `consumeLiquid(`, 26 `consumeCoolant(`, 25 `consumeItems(`, 24 `consumeItem(`;
158 `requirements(`; 10 `new Consume…` sites (7 live: `ConsumeItemCharged`, `ConsumeItemExplode`,
`ConsumeItemFlammable`, `ConsumeItemRadioactive`, `ConsumeLiquid`, `ConsumeLiquidFlammable`, and the commented
`ConsumeItemFlammable`/`ConsumeLiquid`).

---

## 3. Vanilla-content mutation — `SFire/content/SFOverride.java`

`SFOverride.load()` (`SF:src/SFire/content/SFOverride.java:32-774`) creates **no** new content; it mutates
**49 distinct vanilla contents** plus **16 nested vanilla `BulletType` instances** in place, and adds new entries to
vanilla ammo tables. Grouped enumeration (kind of change → DP `patch` expressibility):

### 3.1 Turrets (13 vanilla turrets; `:34-577`)

| Vanilla content | Change | Kind | DP `patch`? |
|---|---|---|---|
| `parallax` | `status = magnStrif`, `statusDuration = 30` (`:35-36`) | field values | yes |
| `scatter` | add 2 ammo entries (`blastCompound` → new `FlakBulletType`, `siliSteel` → new `FlakBulletType`) (`:37-63`) | map insert | yes — `ObjectMap` key insert (`V:mod/DataPatcher.java:561-581`) |
| `salvo` | `health = 1060` (`:64`); `shoot.shots = 6`, `shoot.shotDelay = 2` (`:65-66`); add `blastCompound` `BasicBulletType` (`:67-80`); mutate the **existing** `thorium` ammo bullet (`lifetime/speed/rangeChange`, `:81-84`) | field + nested field + map insert | yes (`:81-84` needs nested-object merge, `V:mod/DataPatcher.java:696-711`) |
| `lancer` | `range = 226`, `reload = 100`, replace `shootType` with a new `LaserBulletType(180){{…}}` (`:86-109`), replace `drawer` with `DrawTurret` + `RegionPart("-barrel")` (`:110-115`) | structural | yes for the bullet (ClassMap has `LaserBulletType`); `drawer` yes (`DrawTurret`/`RegionPart` are in ClassMap), but see §6 |
| `wave` | add `nanoFluid`/`nitrate` `LiquidBulletType` ammo (`:116-134`) | map insert | yes |
| `tsunami` | `shoot = ShootSpread(3,1.5f)`, `rotateSpeed += 2`, `range += 80` (`:136-138`); mutate 4 existing liquid bullets' `speed` (`:139-143`); add 3 new liquid ammo (`:144-191`) | structural + nested + insert | yes (note `rotateSpeed += 2` is a **read-modify-write**, so the converter must snapshot vanilla values — the pattern the mapping doc §6.1 calls a "vanilla snapshot") |
| `ripple` | `shoot.shots = 5` (`:193`); add `siliSteel` and `clusBomb` ammo, the latter with a nested `fragBullet` (`:194-238`) | field + insert | yes |
| `swarmer` | `requirements(...)` re-specified (`:239`), `reload = 32`, `range = 260`, `shoot = ShootBarrel{3 barrels, shots 6, shotDelay 2.5}` (`:240-250`), mutate 3 existing ammo lifetimes (`:251-254`), add 2 ammo entries with frag bullets (`:255-304`) | structural | `shoot` replacement yes (`ShootBarrel` in ClassMap); `requirements` re-specification yes |
| `cyclone` | `range = 228`; mutate 4 existing ammo bullets' `lifetime`/`speed`; add 2 ammo entries, one with a nested `FlakBulletType` frag (`:305-357`) | field + nested + insert | yes |
| `fuse` | `reload = 40`, `range = 100`, `shoot = ShootSpread(4,12.5f)`, `health = 2300`, `armor = 8`; mutate 2 existing ammo damage values; add `discFabric` `ShrapnelBulletType` with `spawnBullets` and an inline **lambda** `shootEffect`/`smokeEffect` (`:358-394`) | structural + **code** | mostly yes; the two lambda effects have no data equivalent (§6.4) |
| `spectre` | 6 scalar fields, mutate 2 existing ammo bullets, add `thorium` and `siliSteel` `BasicBulletType` ammo (`:396-444`) | fields + nested + insert | yes |
| `meltdown` | 4 scalars incl. `liquidCapacity`; replace `shootType` with `ContinuousLaserBulletType(86)` whose `intervalBullet` is a `LaserBulletType(33)` (`:446-488`) | structural | yes (`ContinuousLaserBulletType` in ClassMap) |
| `foreshadow` | `rotateSpeed`, `reload`, add `tayrAlloy` `PointBulletType` with **4 inline lambda effects** (`trailEffect`, `shootEffect`, `hitEffect`, `despawnEffect`) (`:490-577`) | structural + **code** | bullet fields yes; the 4 effects no |

### 3.2 Drills / production / transport (11 vanilla blocks; `:579-609`)

`laserDrill` (`drillTime = 252`, `updateEffect = Fx.hitLancer`), `blastDrill` (`drillTime`, `updateEffect`,
`hardnessDrillMultiplier = 40`, re-specified `requirements`), `impulsePump` (`pumpAmount = 1/3f`,
`consumePower(1.5f)`), `siliconSmelter` (`craftTime`, `consumePower`), `siliconCrucible` (`boostScale = 1/3f`,
`maxBoost = 2.5f`, `consumePower(3.5f)`, `floating = true`, `itemCapacity = 40`), `armoredConveyor` +
`platedConduit` (`placeableLiquid = true`), `titaniumConveyor`/`armoredConveyor` (`speed`, `displayedSpeed`),
`plastaniumConveyor` (`itemCapacity`), `unloader` (`speed = 4`), `junction` (`speed = 24`, `displayedSpeed = 15`).
All of these are field values or `consumePower` re-specification → **expressible as `patch`**, with two caveats:
`consumePower(...)` *adds* a consumer to a block that already has one (the DP `consumes` add/remove path is
`V:mod/ContentParser.java:523-570`), and `blastDrill.requirements(...)` replaces a `ItemStack[]` array.

### 3.3 Power (5 vanilla blocks; `:611-659`)

`combustionGenerator.itemDurationMultipliers.put(strontium, 0.8f)`, `steamGenerator` (same + `powerProduction = 6f`),
`differentialGenerator` (`powerProduction = 19.8f`, plus `destroyBullet = new BulletType{…}` with `instantDisappear`,
`hitShake`, `hitSound = SFSounds.explosionbig`, a `MultiEffect` of 2 `ParticleEffect`s + 1 `WaveEffect`, `makeFire`,
`status`, `splashDamage`, `splashDamageRadius` — `:618-656`), `rtgGenerator.itemDurationMultipliers.put(discFabric, 0.7f)`,
`impactReactor.requirements(...)`. All **expressible** (map inserts on `ObjectFloatMap`, a `BulletType` object on a
`Block` field; `ObjectFloatMap` insert path `V:mod/DataPatcher.java:582-602`).

### 3.4 Cores (3 vanilla blocks; `:662-669`)

`coreShard` (health 2000 / armor 3), `coreFoundation` (5000 / 8), `coreNucleus` (8000 / 12). Plain fields → **expressible**.

### 3.5 Vanilla statuses (5; `:671-700`)

`boss` (`damageMultiplier`, `healthMultiplier`), `fast` (`effectChance`, `effect = MultiEffect(2 × WrapEffect)`),
`slow` (same shape), `sapped` (`speedMultiplier`, `healthMultiplier`, `damageMultiplier`, `effectChance`,
`effect = ParticleEffect{region="sfire-mod-loz"}`), `disarmed` (`disarm = false`, `reloadMultiplier = 0`).
All fields → **expressible**, except that `sapped`'s effect references the mod's own atlas region, so the patch only
resolves if the DP image pack has been applied first (it is: `V:mod/DataManager.java:221-226` packs images before
`patcher.apply`).

### 3.6 Unit production & vanilla units (12 vanilla contents; `:701-767`)

| Vanilla content | Change | DP `patch`? |
|---|---|---|
| `airFactory`, `groundFactory`, `navalFactory` | `plans.add(new UnitFactory.UnitPlan(...))` for `air1`, `tank1`, `naval1`, `hover1` ×2 (`:702-706`) | yes — `Seq` `"+"`/add syntax (`V:mod/DataPatcher.java:460-560`, delta doc §2.4) |
| `additiveReconstructor`, `multiplicativeReconstructor`, `exponentialReconstructor`, `tetrativeReconstructor` | `upgrades.addAll(4 × UnitType[]{a,b})` (`:708-731`) | yes — nested `UnitType[]` array add |
| `UnitTypes.crawler` | weapon 0 renamed to `"crawler-weapon"` and its bullet **replaced** by an anonymous `BulletType` (`collidesTiles = false`, `collides = false`, `rangeOverride = 28`, `speed = 0`, `splashDamage = 80`, `splashDamageRadius = 50`, `killShooter = true`, `instantDisappear = true`, custom `ExplosionEffect` `:747-761`) (`:733-762`) | partly: `weapons` is a `Seq<Weapon>`; patching `weapons[0].name` and `weapons[0].bullet` requires array-index assignment **plus** re-running `weapon.load()`/region resolution — index assignment is supported, but see §6.6 for the `weapon.name` prefixing interaction |
| `UnitTypes.flare` / `quad` / `horizon` / `retusa` | weapon `name` set to `"flare-weapon"` / `"quad-weapon"` / `"horizon-weapon"` / `"retusa-mine"` (`:763-766`) to match the 11 `sprites-override/units/**` files | yes mechanically (string patch), but the override *sprites* themselves are the real problem (§5.10) |

Nothing in `SFOverride` is a DP `patch` blocker by kind — every one of the 49 contents is reachable through
`DataPatcher.assign` field/map/array paths. What is **not** expressible is the *intent* behind two of them:
`UnitTypes.*.weapons.get(0).name = new String(...)` is a `new String(...)` (not a literal) and the object is a
**shared vanilla** `BulletType` handed to `ammoTypes`/`weapons`, so a converter must snapshot the vanilla object's
current values (same "vanilla snapshot" requirement as §3.1 `tsunami`/`salvo`).

---

## 4. Expressibility matrix

Verdicts are for "reproduce the mod's behaviour in a DP (declarative HJSON content + assets), targeting v160.5".
`fully` = the DP can carry it with no behavioural loss; `partial` = loads and plays, with enumerated losses;
`impossible` = no DP construct carries it.

| # | Feature group | Verdict | Concrete reason & source reference |
|---|---|---|---|
| 1 | Turrets & ammo (44 turrets: 21 `ItemTurret`, 17 `PowerTurret`, 2 `EnhancedPowerTurret`, 2 `LiquidTurret`, 1 `ContinuousLiquidTurret`, 1 `ContinuousTurret`) | **partial** | `ItemTurret`/`PowerTurret`/`LiquidTurret`/`Turrets`/`ItemEntry`/`AmmoEntry` and all used bullet classes except the 3 custom ones are in `ClassMap` (`V:mod/ClassMap.java`: `ItemTurret`, `PowerTurret`, `LiquidTurret`, `ContinuousTurret`, `ContinuousLiquidTurret`, `BasicBulletType`, `FlakBulletType`, `MissileBulletType`, `ArtilleryBulletType`, `LaserBulletType`, `ShrapnelBulletType`, `PointBulletType`, `RailBulletType`, `EmpBulletType`, `ContinuousLaserBulletType`, `FireBulletType`, `LightningBulletType`, `SapBulletType`, `EmptyBulletType`, `ExplosionBulletType`, `LiquidBulletType`) and `ammo()`/`ObjectMap` become HJSON maps. Losses: `EnhancedPowerTurret` enhancer channel (2 turrets, `EnhancedPowerTurret.java:56-143`), 12 custom-bullet sites (§2.4), 5 lambda `shootEffect`/`hitEffect` clones, turret-level lambda `Effect`s in 21 of the 44 turrets (§2.5), and `unitSort`/`coolant`/`shoot` combos are fine (`Sortf`/`Consume`/`ShootPattern` parsers exist, `V:mod/ContentParser.java:85-105`, `:213-219`, `:372-385`). |
| 2 | Factories & consumers (38 `GenericCrafter`, 8 `AttributeCrafter`, 3 `Separator`, 6 unit-production blocks) | **partial** | Every block class and every `Consume*`/`DrawBlock` used is in `ClassMap`; `requirements`, `consumeItem(s)`, `consumeLiquid(s)`, `consumePower`, `consumeCoolant`, `.boost()`, `itemDurationMultipliers`, `outputItems`/`outputLiquids`, `plans`, `upgrades`, `droneType`, `filter` are all field/map data (`V:mod/ContentParser.java:523-570` consumes syntax; `:644-658` `requirements` → factory plan / reconstructor upgrade). Losses: lambda `craftEffect`/`updateEffect` clones and `DrawPistons`-style *part* lambdas where a `PartFunc` is used; `AttributeCrafter` boost math itself is vanilla. `UnitAssembler.droneType` references promoted drone units (expressible, `SF:…/SFBlocks.java:10761`). |
| 3 | Drills (2 `PressureDrill`, 2 `BurstDrill`, 1 `Fracker`, 1 `SolidPump`, 2 `AttributeCrafter`) | **partial** | `Drill`/`BurstDrill`/`BeamDrill`/`Fracker`/`SolidPump` are in `ClassMap`; `drillTime`, `tier`, `hardnessDrillMultiplier`, `liquidBoostIntensity`, `consumes` are fields. Loss: the two `PressureDrill`s lose power-balance overdrive (`PressureDrill.java:72-80`) — a DP `Drill` has no per-tick efficiency hook. |
| 4 | Power (12 blocks incl. 2 `GasTurbineGenerator`, 1 `NuclearReactor`, 1 `ImpactReactor`, 2 `ThermalGenerator`) | **partial** | `ConsumeGenerator`/`ThermalGenerator`/`NuclearReactor`/`ImpactReactor`/`Battery`/`PowerNode`/`LongPowerNode`/`BeamNode` are in `ClassMap`; `powerProduction`, `itemDurationMultipliers`, `outputLiquid`, `consume*`, `destroyBullet` are fields. Loss: `extraPower`/`powerUpSpeed` ramp, custom `warmup`/`productionEfficiency` curve, `sense(LAccess.heat)` mapping and the extra `write`/`read` floats of `GasTurbineGenerator` (`GasTurbineGenerator.java:65-143`); `drawer = new DrawMulti(DrawRegion("-rot1",6f){spinSprite=true}, …)` uses `DrawRegion` with ctor args, still expressible. |
| 5 | Walls & defense (15 `Wall`, 2 `ExplodeWall`, 2 `MemoryWall`, 2 `ForceProjector`, 1 `AutoDoor`, 1 `MendProjector`, 1 `RegenProjector`, 1 `ShieldWall`) | **partial** | `Wall`, `AutoDoor`, `Door`, `ForceProjector`, `MendProjector`, `RegenProjector`, `ShieldWall`, `ShockwaveTower`, `OverdriveProjector` are in `ClassMap`; `itemConsumer = consumeItem(x).boost()`, `shieldHealth`, `radius`, `phaseShieldBoost`, `cooldown*`, `coolantConsumption` are fields. Loss: all `MemoryWall` per-hit armor growth/regen (2 blocks) and all `ExplodeWall` interception/self-destruct (2 blocks); `ExplodeWall`'s `flags = turret` + `priority = TargetPriority.turret` (`ExplodeWall.java:47-48`) also changes enemy targeting, and a DP `Wall` cannot reproduce `group = BlockGroup.walls` + turret priority simultaneously. |
| 6 | Units, weapons, abilities, AI (62 units + 4 promoted missile units) | **partial** | Unit `template`/`type` resolution is `ClassMap`-based and the 11 ability classes actually used are all in `ClassMap` (`StatusFieldAbility` 16, `MoveEffectAbility` 10, `ForceFieldAbility` 6, `ArmorPlateAbility` 5, `RegenAbility` 5, `ShieldArcAbility` 3, `ShieldRegenFieldAbility` 3, `EnergyFieldAbility` 2, `RepairFieldAbility` 2, `LiquidExplodeAbility` 1, `SuppressionFieldAbility` 1); `PointDefenseWeapon`/`RepairBeamWeapon`/`Weapon` and all `UnitEngine`/`RegionPart`/`HaloPart`/`ShapePart`/`HoverPart`/`FlarePart` are in `ClassMap`; `controller`/`aiController` accept ClassMap AI names (`V:mod/ContentParser.java:660-669`, `V:mod/DataPatcher.java:624-629`, `resolveController` `:1217-1221`). Losses: `MissileUnitType.spawnUnit` promotion is supported but the promoted unit's own inline `Effect`s are not; 6 unit-level lambda `Effect`s; per-unit `constructor = UnitTypes.X.constructor` (62 sites) must be re-derived into `type:` (mapping table in `docs/JAVA_TO_DP_MAPPING_V1597.md` §7); `weapons.get(0).name`-style renames only matter through the override sprites. `GunShipAI` is dead. |
| 7 | Statuses & callbacks (23 statuses + 5 vanilla status patches) | **partial** | `StatusEffect` is a DP root content type (`V:mod/data/ContentAsset.java:13`) and the parser reads all its data fields including `transitionDamage`, `opposites`, `affinities` (`V:mod/ContentParser.java:144-154`). Impossible in DP: the *behaviour* of `opposite()` (duration halving) and of `affinity()` handlers, because they are installed into `StatusEffect.transitions` as `TransitionHandler` lambdas (`V:type/StatusEffect.java:175-199`); there is no parser for `TransitionHandler` in `ContentParser` (only `ClassMap` names it). Affected: 9 `affinity(...)` lambdas, 10 `opposite(...)` calls, 13 `init(() -> …)` blocks (`SFStatusEffects.java` passim), the `acidded` `update` override (`:241-264`), and the two callbacks injected into **vanilla** `freezing`/`tarred` (`SFOverride.java:457-461`, `:500`). |
| 8 | Draw / parts / effects (85 custom `drawer`s, 91 `RegionPart`, 58 lambda effects) | **partial** | Every `DrawBlock`, `DrawPart`, `PartProgress`-op and effect class referenced by the mod is in `ClassMap`/the `ContentParser` parsers (`V:mod/ContentParser.java:198-229`), and `DrawPart.load(block.name)` is resolved from the visit stack (`V:mod/DataPatcher.java:406-411`), so `DrawMulti`/`DrawTurret`/`RegionPart`/`HaloPart` chains convert. Impossible: `new Effect(lambda)` — `Effect` itself is **not** in `ClassMap` (it is a `classParsers` entry that resolves a `type` through `ClassMap` or a `Fx.*` string, `V:mod/ContentParser.java:72-84`), so the 58 inline effects + the 8 `SFFx.TriExplosion(...)` sites + `SFFx.chemFlame` can only be replaced by a standard effect subclass/`Fx` or dropped. |
| 9 | Tech tree (1 root + 249 nodes, 86 attachment points) | **partial** | DP builds nodes from the `research` key on each content (`V:mod/ContentParser.java:1337-1418`), supports `parent`, `requirements`, `objectives`, `planet` and `root`, and auto-adds a `Produce` objective for items/liquids (`:1377-1379`). Therefore the 249 `node()/nodeProduce()` calls can be inverted into per-content `research` blocks. Impossible: the **root** node `nodeRoot("tiberia", frontCore, …)` (`SFTechTree.java:25`) is attached to the mod's own planet, which DP cannot load; `addToNode(...)` on vanilla nodes is expressible only as a `parent:` reference on the new content (there is no "add child to vanilla node" asset), and `research.getBoolean("root")` roots are global (`:1394-1397`), so a "tiberia root" is not reproducible. |
| 10 | Planets / sectors / campaigns / maps (1 planet, 1 preset, 2 `.msav`) | **impossible** (planet/sector) / **partial** (maps) | `ContentAsset.loadableContent` = item, block, liquid, status, unit, weather — planets and sectors are explicitly not loadable (`V:mod/data/ContentAsset.java:12-13`, enforced at `V:mod/DataPatcher.java:125-128`). The planet needs code anyway: `generator = new SerpuloPlanetGenerator()`, `meshLoader`/`cloudMeshLoader` lambdas, `ruleSetter`, `campaignRuleDefaults`, `bannedBlocks` (`SFPlanets.java:22-70`). The tech-tree root ties to it. Maps **are** transportable (`V:maps/Maps.java:170` reads `maps/` from any mod), but their content headers store MappableContent **names** (`V:io/MapIO.java:198-207`) which are `sfire-mod-*` today and `dp-*` under DP (§5.1), and only 4 vanilla renames are auto-mapped (`V:io/SaveFileReader.java:69-74`). |
| 11 | Sounds / bundles / sprites | **partial** | Sounds: the 9 `Sound` fields are loaded by a reflective walk over `SFSounds.class.getFields()` (`SFSounds.java:20-44`) — a converter can enumerate them statically and DP resolves sound strings by asset name or `Sounds.*` field (`V:mod/ContentParser.java:310-326`). Bundles: DP bundles are a first-class asset type (`DataAssetType.bundle`, `V:mod/data/DataAssetType.java:10`) but the key must be `type + "." + currentMod.name + "-" + name + "."` = `block.dp-<name>.name` (`V:mod/ContentParser.java:938-959`), so all 886 SF keys need rewriting. Sprites: DP images are always registered as `dp-` + name (`V:mod/DataImagePacker.java:24`, `:113`; `V:mod/DataManager.java:74`) and there is **no** `sprites-override` concept in the DP asset path, so the 23 `sprites-override` files that exist to *replace vanilla* art (lancer, crawler, flare, horizon, retusa, vela, corvus, ui/logo, ui/error) have no direct DP carrier; the only mechanisms are (a) a `patch` that assigns the vanilla content's `region` to a `dp-` region (possible because images are packed before patches, `V:mod/DataManager.java:221-226`) **(inferred, unverified)** or (b) renaming the shipped file so the double-prefix guard produces the vanilla name (blocked: the guard only *skips* a prefix, it cannot remove one, `V:mod/Mods.java:409-411`), and (c) the duplicate-region collision of `defense-platform-nuke-missile`. |

Summary: **no** feature group is `fully` expressible in a DP. Ten of the eleven rows are `partial` (they load and
play but with the enumerated behavioural losses), and the one hard boundary is planets/sectors in row 10
(`impossible`); the `.msav` maps in the same row are transportable only after a namespace rewrite (§5.1).

---

## 5. Ordered gap list for "reproduce all mod functionality in a DP"

Ordered by (value lost × how much of the mod it touches) ÷ (cost to work around).

### 5.1 Namespace migration `sfire-mod-*` → `dp-*` (blocks everything else)

- **What is lost today**: nothing yet, but every DP output silently renames content. The DP engine parses all DP
  content with one hardcoded pseudo-mod whose name is `"dp"` (`V:mod/DataPatcher.java:34-38`) and binds names as
  `mod + "-" + name` (`V:mod/ContentParser.java:509,586,622,694,709,723`); the Java mod's names come from
  `Vars.content.transformName` → `currentMod.name + "-" + name` (`V:ctype/MappableContent.java:11`,
  `V:core/ContentLoader.java:184-186`). Consequence: 886 bundle keys (`block.sfire-mod-*`), the 2 shipped `.msav`
  content headers, `SaveFileReader.modContentNameMap` (4 vanilla entries only, `V:io/SaveFileReader.java:69-74`),
  and any existing save become dangling.
- **DP construct that could carry it**: bundles are a DP asset type and can be re-keyed; `.msav` content headers are
  a plain UTF string table (`V:io/MapIO.java:203-207`) that can be rewritten offline; there is **no** in-game
  fallback for arbitrary mod renames.
- **What the converter must compute/generate**: a total rename map (content name → `dp-` + local name; weapon names
  must *not* be pre-prefixed because `ContentParser` prefixes them itself, `V:mod/ContentParser.java:362-371`;
  region references keep the mod prefix but change it), a rewritten `bundle*.properties`, and rewritten `.msav`
  headers (or a documented decision to drop the 2 mod maps).

### 5.2 Custom `BulletType` behaviour (12 sites → 10 turrets + 2 units)

- **What is lost**: `ShieldBreakBullet` (percent-of-current-shield damage + `shbreakEffect`, and its
  `hittable/reflectable/absorbable = false` constructor side-effects), `PowerupBullet` (damage ramp per pierce with
  a cap; `pierce = true` from the ctor), `SizeDamageBullet` (damage scaled by `hitSize/basicSize`). Affected
  contents (site → owner, computed from the declaration enclosing each site): `sizhao`, `cuowei` ×3, `cimai` ×2,
  `kuosan`, `zhulin`, `luolong`, `defensePlatform1` (all turrets) and the units `hover5`, `utv`.
- **Carrier**: `BasicBulletType` fields — `pierce`, `pierceCap`, `pierceDamageFactor`, `armorMultiplier`,
  `damage`, `splashDamage*`. A faithful *approximation* is possible for `PowerupBullet`
  (`pierceDamageFactor`/`pierceCap`) and for `ShieldBreakBullet` (there is no shield-percent field at all).
- **Converter work**: substitute the base class, restore **constructor-set** fields that the initializer never
  repeats (`pierce`, `reflectable`, `absorbable`, `hittable`, `sprite`), and emit an explicit `degraded` diagnostic
  per site. Never emit a bare `BasicBulletType` with the ctor args dropped — that changes damage and pierce.

### 5.3 Custom `Block` subclasses (6 classes, 11 blocks)

- **What is lost**: enhancer ammo channel (2), memory-armor growth + regen (2), power-surplus overdrive (2),
  `extraPower` ramp + `heat` sensor + extra save fields (2), bullet interception + self-destruct (2), core-count
  limit + custom bars (1).
- **Carrier**: `PowerTurret`+`ammoTypes` (partial stand-in for the enhancer), `Wall` (+ static `armor`),
  `Drill` (+ `drillTime`), `ConsumeGenerator`, `Wall`, `CoreBlock`. All are in `ClassMap`; all the *fields* the mod
  sets are ordinary block fields except the six custom ones listed in `docs/JAVA_TO_DP_MAPPING_V1597.md` §8.
- **Converter work**: map each custom class to its vanilla base, drop unknown fields, keep the custom-class-only
  fields' effect documented, and check the dropped fields are not referenced by the mod's own `setBars`/`setStats`
  strings (`bar.drillspeed`, `bar.boost`, `bar.healdelay`, `bar.armorup`, `bar.lastcorenumber`,
  `stat.minpowerneed`, `stat.maxcore`, `stat.damagemul`, `stat.extrapowermul`, `stat.armoraciddown`,
  `stat.wallmaxarmor`, `stat.regendelay` — all 7 `stat.*` and 3 of the `bar.*` keys in the bundle are only used by
  these classes; `SF:src/SFire/expand/blocks/*.java`).

### 5.4 Lambda `Effect`s (58 inline + 8 `TriExplosion` sites + `SFFx.chemFlame`)

- **What is lost**: all custom-drawn visuals for 22 blocks, 5 units, 5 vanilla patches, 1 status (`coldBreak`,
  `SF:src/SFire/content/SFStatusEffects.java:403`) and 2 in `SFFx` itself.
- **Carrier**: `Effect` subclasses in `ClassMap`/`ContentParser` — `ParticleEffect`, `WaveEffect`, `MultiEffect`,
  `WrapEffect`, `RadialEffect`, `ExplosionEffect`, `SeqEffect`, `SoundEffect`, `NoiseEffect`, `TriangleEffect`, or
  an `Fx.*` string (`V:mod/ContentParser.java:72-84`).
- **Converter work**: pattern-match the lambda body; if it only writes standard `ParticleEffect`-shaped values,
  synthesize; if it contains draw calls (`Drawf.tri`, `Lines.circle`, `Fill.square`, `Draw.z`) → no equivalent, so
  emit `Fx.none` + an `error`-severity diagnostic. `SFFx.TriExplosion(lifetime, particles, minsize, size, stroke,
  color)` (`SFFx.java:19-33`) is a *parameterized* factory and can be inlined per call site only as `Fx.none`
  unless a standard triangular/explosion effect is acceptable; the 8 call sites are listed in §2.5.

### 5.5 Status callbacks (`affinity`/`opposite`/`update`)

- **What is lost**: reaction damage/status transitions (9 affinities), duration cancellation (10 opposites),
  `acidded`'s armor corrosion, and the two callbacks injected into vanilla `freezing`/`tarred`.
- **Carrier**: the `opposites`/`affinities` sets (display only) and the numeric `transitionDamage` field
  (`V:type/StatusEffect.java:31,67,119-133`); no parser can build a `TransitionHandler`.
- **Converter work**: set `transitionDamage` where the source proves a damage number (e.g. `negative`/`postive`
  `transitionDamage = 18`, `breakdown` 18, `echoFlame` 600, `coldBreak` 50, `overFreezing` 30,
  `chemicalFlame` 30 — `SFStatusEffects.java:145,168,301,328,401,442,493`), keep the sets, and report each dropped
  handler. The vanilla callbacks in `SFOverride.java:457-461,500` must become a `patch` that only adds the
  `affinities` set entry.

### 5.6 Tech tree reconstruction (250 nodes)

- **What is lost**: the `tiberia` tree root and every parent-child relation that pointed at that root; also the 35
  `nodeProduce` produce-objectives (of the 249 `node()`/`nodeProduce()` calls inside `load()`).
- **Carrier**: per-content `research: {parent, requirements, objectives, planet}` (`V:mod/ContentParser.java:1337-1418`);
  items/liquids get `Produce` automatically (`:1377-1379`).
- **Converter work**: invert `addToNode(parent, …{ node(child) })` into `child.research.parent = parent`, resolve
  `nodeProduce`, drop `new SectorComplete(planetaryTerminal)` (`SFTechTree.java:34-36`) if the objective cannot be
  expressed (it can: `SectorComplete` is in `ClassMap` and the `Objective` parser handles objects,
  `V:mod/ContentParser.java:343-354`), and decide the fate of the root (only expressible if re-parented onto a
  vanilla planet, which the mod's content does not use).

### 5.7 Planet / sector / campaign shell

- **What is lost**: `tiberia` (hex mesh, cloud mesh, atmosphere, `startSector`, `ruleSetter` with
  `bannedBlocks.addAll(overdriveDome, overdriveProjector, smallHeatRedirector)`, `campaignRuleDefaults.rtsAI`,
  `allowSectorInvasion`, … `SFPlanets.java:18-70`), `frozenFront` (`SFSectorPresets.java:16-20`), the 5 sector
  sprites, and `TechFix`'s `shownPlanets`/tech assignments.
- **Carrier**: none — planets/sectors are outside `ContentAsset.loadableContent` (`V:mod/data/ContentAsset.java:13`).
- **Converter work**: report as `unsupported` (not silently), and provide the per-content `shownPlanets` /
  `requiredPlanets` substitutes that `TechFix` computed, since those *are* plain fields on `UnlockableContent`
  (`TechFix.java:20-71`).

### 5.8 `drawer`/`parts` fidelity (85 drawers, 91 `RegionPart`)

- **What is lost**: any `DrawBlock` that is an anonymous subclass *overriding* a method, any `PartFunc` lambda in a
  part, and `DrawPart.load(...)` name resolution for parts whose parent could not be pushed on the visit stack.
- **Carrier**: the full `DrawBlock`/`DrawPart`/`PartProgress` type set and the `parts` `Seq` (all in `ClassMap`),
  with parent resolution handled by `DataPatcher.created` (`V:mod/DataPatcher.java:396-417`).
- **Converter work**: verify `parts.add(new X("suffix"){{…}})` field-wise conversion, emit `parts` as an array, and
  keep `PartProgress` chains (the parser has a hardcoded op list, `V:mod/ContentParser.java:1159-1180`).

### 5.9 Vanilla `SFOverride` as `patch` assets (49 contents, 16 nested bullets)

- **What is lost**: nothing structurally, but three concrete mechanics need care: (a) read-modify-write patches
  (`rotateSpeed += 2`, `range += 80` on `tsunami`) require vanilla value snapshots; (b) nested-object merges on
  vanilla `BulletType`s that are shared objects (salvo/tsunami/swarmer/cyclone/fuse/spectre); (c) adding a *second*
  consumer (`consumePower`) to blocks that already have one.
- **Carrier**: `patch` assets with the v160.5 operators — `"+"`/add for `Seq`/array/`ObjectSet`, `"-"` for map-key
  removal, index assignment, `consumes.add/remove` (`V:mod/DataPatcher.java:460-602`, `V:mod/ContentParser.java:523-570`).
- **Converter work**: emit one patch per vanilla content with the same field paths, and snapshot the vanilla values
  it needs (the converter already has a snapshot mechanism for exactly this class of problem,
  `docs/JAVA_TO_DP_MAPPING_V1597.md` §6.1).

### 5.10 Vanilla sprite overrides (23 `sprites-override` files) and the region collision

- **What is lost**: the mod's re-art of `lancer`, `crawler`, `flare`, `horizon`, `retusa`, `vela`, `corvus` and the
  mod `logo`/`error` icons; DP has no override folder and always registers `dp-` + basename
  (`V:mod/DataImagePacker.java:24,113`).
- **Carrier**: a `patch` on the vanilla content that assigns its `TextureRegion` fields to the `dp-` region names
  (images are packed before patches, `V:mod/DataManager.java:221-226`; `TextureRegion` parser at
  `V:mod/ContentParser.java:119-134`) — **inferred**, needs a runtime test because `Block.load()` re-derives
  `region = Core.atlas.find(name)` (`V:world/Block.java`, `load()` body) whenever content is reloaded.
- **Converter work**: emit either those `region` patches or declare the 23 override files `unsupported`, and
  de-duplicate `defense-platform-nuke-missile` (2 files → 1 region name, §1.3).

---

## 6. Trap list / known crash risks

Every entry below is a place where the mod's behaviour lives in **code, not data**, so a DP conversion that
"registers fine" can still break at load or during play. Ordered by risk.

### 6.1 Custom `Building` subclasses (highest risk — the block loads, the build class is wrong)

| Behaviour | Citation | Failure mode after conversion |
|---|---|---|
| `EnhancedPowerTurret.PowerTurretBuild.useAmmo/hasAmmo/peekAmmo/acceptItem/acceptStack/shoot` | `SF:src/SFire/expand/blocks/EnhancedPowerTurret.java:60-143` | DP `PowerTurret` has no item ammo: any existing map/save with items queued into `guangyin`/`poxiao` loses them; `shoot()`'s dual-type branch (enhanced pattern vs normal pattern) cannot exist, so `enhancedPattern` fire rates vanish. |
| `MemoryWall.MemoryWallBuild.collision` / `updateTile` writing `armor` | `SF:src/SFire/expand/blocks/MemoryWall.java:65,78,87-102` | `armor` resolves to the shared `Block.armor` (`V:world/Block.java:195`, damage at `V:entities/comp/BuildingComp.java:1767`, sensor at `:2125`). DP `Wall` has one static armor; the per-hit growth, the regen-driven reset to 0 and `setBars`' `armor/maxArmor` bar are all gone. A `patch` cannot restore it (no hook). |
| `ExplodeWall.ExplodeWallBuild.updateTile` (`Groups.bullet.intersect`, bullet damage/removal, `kill()`), `onDestroyed` (`Damage.damage` + `destroyEffect`), `drawSelect` | `SF:src/SFire/expand/blocks/ExplodeWall.java:78-119` | DP `Wall` never runs `updateTile`, so the "bullet shield at low HP then self-destruct" is dead code; `flags = BlockFlag.turret` / `priority = TargetPriority.turret` (`:47-48`) also changes whether enemies target the wall. |
| `PressureDrill.PressureDrillBuild.updateTile` power-balance factor | `SF:src/SFire/expand/blocks/PressureDrill.java:55-98` | DP `Drill` keeps a fixed rate; the two drills lose their whole identity (power surplus = speed). |
| `GasTurbineGenerator.GasTurbineGeneratorBuild.updateTile/warmup/totalProgress/sense(LAccess.heat)/write/read` | `SF:src/SFire/expand/blocks/GasTurbineGenerator.java:65-143` | DP `ConsumeGenerator` produces a flat `powerProduction`; `extraPower`/`powerUpSpeed` become dead fields, the logic `heat` sensor reads vanilla semantics, **and** the extra `write`/`read` floats mean any save written by the Java build cannot be read by the DP build (and vice versa) — a save-compat break, not just a balance change. |
| `SFCore.canBreak/canReplace/canPlaceOn/setBars` | `SF:src/SFire/expand/blocks/SFCore.java:22-51` | DP `CoreBlock` loses the per-team core cap (`maxNumber = 7`) and the "last core" bar; placement rules default to vanilla, which changes campaign balance (the mod's own description advertises this rule). |

### 6.2 Custom `BulletType`s (loads, then silently behaves differently)

`PowerupBullet.handlePierce` (`SF:…/expand/bullets/PowerupBullet.java:21-42`),
`ShieldBreakBullet.hitEntity` (`:29-59`), `SizeDamageBullet.hitEntity` (`:22-46`). All three **also** set
constructor-only state (`pierce = true`, `reflectable = false`, `hittable = false`, `absorbable = false`,
`sprite`), which a field-only conversion drops. Concrete trap: `SF:…/SFBlocks.java:9599` and `:9695` are
`fragBullet`/`ammo` entries, so a degraded conversion changes not just the turret's direct hit but its whole
fragmentation damage. Sites: `SFBlocks.java:7210, 7688, 7742, 7768, 8184, 8271, 8899, 9599, 9695, 9850`;
`SFUnitTypes.java:6836, 8543`.

### 6.3 Custom `StatusEffect` override

`acidded` is an anonymous `StatusEffect` subclass with an extra field and a per-tick override
(`SF:src/SFire/content/SFStatusEffects.java:241-264`): it decays `unit.armor` toward 0 at `armorAcidDown/60` per
tick, switches `effectChance`/`intervalDamage` once armor is 0, and adds a `setStats` entry (`stat.armoraciddown`).
DP statuses are plain `new StatusEffect(mod_name + "-" + name)` (`V:mod/ContentParser.java:150`, `:723`) with no
`update` hook, so armor corrosion disappears while the status still applies. Same shape for the two callbacks
injected into vanilla statuses at `SF:src/SFire/content/SFOverride.java:457-461` (adds an `overFreezing` affinity
to `freezing`) and `:500` (`tarred.affinities.add(this)`).

### 6.4 Custom `Effect` lambdas (visual, but load-order sensitive)

58 live sites (§2.5). Beyond lost visuals, two concrete crash/regression shapes:
`SF:src/SFire/content/SFFx.java:19-33` is a factory whose call sites pass `hitColor` etc. — a converter that treats
it as a "named effect" will emit an unknown name; and any `Effect` that also deals damage or applies a status
(the `SFOverride` `differentialGenerator.destroyBullet` effect chain at `:618-656`, and `SFFx.chemFlame` used
inside the `chemicalFlame` affinity at `SFStatusEffects.java:498`) is behavioural, not cosmetic.

### 6.5 Custom `DrawBlock` subclasses / `PartFunc` lambdas

85 `drawer = new …(){{…}}` sites, 22 distinct `DrawBlock` classes, all in `ClassMap` — the risk is not the type but
(i) constructor arguments that the initializer does not repeat (`new DrawRegion("-rot1", 6f)`,
`new DrawLiquidRegion(Liquids.water)`, `new DrawLiquidTile(SFLiquids.nitrate)`,
`SF:…/SFBlocks.java:2980-2982`, `:3008`), and (ii) `DrawPart.load(name)` resolution relying on the visit stack, so
a part nested under a *promoted* content (the 4 inline missile units, `SFUnitTypes.java:796` etc.) resolves its
region against the wrong parent name (`V:mod/DataPatcher.java:396-417` handles the normal case; a promotion changes
the parent).

### 6.6 Unit entity `constructor` and `controller` semantics

- All 62 live units assign `constructor = UnitTypes.X.constructor` (11 × `stell`, 8 × `flare`, 7 × `corvus`,
  7 × `elude`, 6 × `risso`, 5 × `zenith`, 3 × `omura`, 3 × `mega`, 3 × `gamma`, 2 × `reign`, 2 × `assemblyDrone`,
  1 × each of `dagger`/`oct`/`atrax`/`quad`/`eclipse` — 62 live sites; the only other `constructor` assignment in
  the file, `constructor = EntityMapping.map(3)` at `SF:…/SFUnitTypes.java:652`, sits inside the commented-out
  `thunderclap` block `:650-678` and is therefore **not** live). In DP this must become the `type:` string
  (entity constructor) resolved by the hardcoded switch in `V:mod/ContentParser.java:886-899`. A wrong mapping
  produces a unit whose entity class does not match its movement model (e.g. a `TankUnitType` exported as `flying`)
  — it loads, then misbehaves or breaks its `TankUnit`-only fields.
- `aiController = SuicideAI::new` (`SF:…/SFUnitTypes.java:681`), `DefenderAI::new` (`:3053`),
  `BuilderAI::new` (`:7345`, `:7477`, `:8097`, `:8165`), `controller = u -> new AssemblerAI()` (`:9021`, `:9042`),
  `defaultCommand = UnitCommand.rebuildCommand` (`:7346`, `:7478`). All five AI classes are in `ClassMap`, and
  DP accepts `controller`/`aiController`/`defaultController` **strings** (`V:mod/ContentParser.java:660-669`,
  `V:mod/DataPatcher.java:624-629`). The trap is the `Func<Unit, UnitController>` lambda form: a converter that
  copies `controller` verbatim emits a JsonValue where a string is expected, and
  `resolveController` will fall back to `FlyingAI` if the name is unknown (`:1217-1221`) — a silently wrong AI.

### 6.7 `write`/`read` and save-compat

`GasTurbineGeneratorBuild.write`/`read` (`SF:…/expand/blocks/GasTurbineGenerator.java:131-143`) extend the vanilla
save payload with two floats. Any DP block that takes the vanilla `ConsumeGeneratorBuild` produces a different byte
length for the same block, so a save round-trip across the Java→DP switch is not safe (compounded by the rename in
§5.1, which means the old block name is not found at all).

### 6.8 Fields the mod *does not* use (checked, so they can be ruled out)

`config(...)`/`configurable`, `teamRegion`, `customShadow`, `outlineIcon`, `buildType` and `drawPlan` occur **0**
times in live code across `src/` (`buildType` appears once, inside the dead comment block
`SF:src/SFire/expand/blocks/SFCore.java:56-103`). So the classic DP hazards of a custom `Building` factory, a
non-standard `teamRegion` array, a custom shadow and an icon-outline override are *not* present in 4.0.6 — worth
stating explicitly because it means the DP conversion has no `config`-semantics or icon-outline work to do.
`configurable` being absent also means no `lastConfig`/`config()` logic to port.

### 6.9 Load-time (not gameplay) crash class: `checkNullFields`

`ContentParser` throws on any non-`@Nullable`, non-primitive field left `null`
(`V:mod/ContentParser.java:1245-1261`), and `DataPatcher` refuses objects that instantiate new `UnlockableContent`
inside a field (`V:mod/DataPatcher.java:681-684`). The SF source has 22 declarations that are never assigned
(12 unit + 10 block fields, §1.2) plus 3 units whose JAR assignment is missing (§1.5); a converter that emits JSON
from *declarations* rather than from *assignments* will emit null/incomplete content and fail at load. The known-null traps for this mod are
`ItemStack.item`/`LiquidStack.liquid` (16 `LiquidStack` and 34 `ItemStack` sites), `UnitPlan.unit/time/requirements`
(17 `new UnitPlan` + 2 `new AssemblerUnitPlan`), `StatusFieldAbility.effect` (16 sites), and
`LiquidBulletType.liquid` (all `new LiquidBulletType(SFLiquids.x)` sites).

---

## 7. Verification notes / reproduction

Everything in this document is reproducible with read-only operations:

- Content counts: `javap -p -c -cp work/samples/downloads/Saturation-Firepower-4.0.6.jar SFire.content.SFBlocks`
  (count `putstatic …:Lmindustry/world/Block;`) and `… SFire.content.SFUnitTypes` (count
  `putstatic …:Lmindustry/type/UnitType;`); compare with the indent-8 declaration sites in the tagged source.
- Block/unit type histograms: regex `^ {8}(name) = new (Type)(` over `SFBlocks.java` / `SFUnitTypes.java`,
  with comment ranges excluded (`SFBlocks.java:318-347`, `SFBlocks.java:10848-10859`, `SFUnitTypes.java:76-89`,
  `SFUnitTypes.java:650-678`).
- ClassMap coverage: extract `classes.put("…")` from `V:mod/ClassMap.java` (501 entries) and diff against every
  `new X(` constructor name in `SF:src/**` — this is how the "only 10 gameplay classes are outside ClassMap" result
  in §4 was obtained (the full non-ClassMap list is: `Effect` ×58 (a plain constructor call, so these produce no
  anonymous class — a class-file superclass scan therefore shows **zero** `Effect` subclasses, which is expected),
  `ShieldBreakBullet`, `PowerupBullet`,
  `SizeDamageBullet`, `PressureDrill`, `EnhancedPowerTurret`, `ExplodeWall`, `GasTurbineGenerator`, `MemoryWall`,
  `SFCore`, `HexSkyMesh`, `HexMesh`, `MultiMesh`, `TechNode`, `SerpuloPlanetGenerator`, `TiberiaPlanetGenerator`,
  `Sound`, `Bar`, `Stat`, `Rect`, `Vec2`, `Color`, `Rand`, `BaseDialog`, `RuntimeException`, `CoolantSeparator`
  (commented) — the first ten are the gameplay surface, the rest is UI/util/planet plumbing).
- The `dp-` namespace claim: `V:mod/DataPatcher.java:34-38` + `:141` (parse always uses `dpMod`), and
  `V:mod/DataImagePacker.java:24` (`regionPrefix = "dp-"`), `V:mod/ContentParser.java:941`
  (`type + "." + currentMod.name + "-" + name + "."` for bundle keys).
- The Java-mod prefix claim: `V:ctype/MappableContent.java:10-13` + `V:core/ContentLoader.java:184-186`, plus
  `V:mod/Mods.java:387-412` for sprite region names.

No file outside this report was created or modified; scratch output (`javap` dumps, extracted class files) lives
in `%TEMP%\sfjar`.
