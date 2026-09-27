# 原版覆盖（Vanilla Override）实现方案

状态：**设计已定，代码未实现**（2026-09-27 决议）。本轮只完成方案与测量；下一轮按本文件执行。

## 一、决议

| 项 | 取值 |
|---|---|
| 开关 | `--vanilla-overrides=on\|off`，**默认 on**（还原 SF 对原版的改动） |
| 范围 | 方块 + 单位 + 状态效果 + 物品/液体（全量） |
| 引擎 | **路线 1：运行时差分 + 静态监视表**（非 AST 翻译） |
| 产物 | `patches/vanilla-overrides.hjson`（`DataAssetType.patch` 根目录），随 DP ZIP 与 `server-assets/` 一同交付 |

之所以选路线 1：SFOverride 的 497 条变更里有 3 条"读取已有条目再改"、28 条"整对象替换"、
13 条"集合插入对象"，其中相当部分靠 AST 求值无法忠实翻译；而运行时差分天然覆盖这四类，
且与本项目"运行时是权威"的既有原则一致。

## 二、SFOverride 4.0.6 变更测绘（实测）

| 变更类型 | 条数 |
|---|---:|
| 直接字段赋值（标量） | 374 |
| 嵌套字段赋值 `((T) Blocks.x).y = v` | 79 |
| 集合插入对象 `ammoTypes.put/putAll` | 13 |
| 读取已有条目再改 `var q = …get(k); q.f = v` | 3 |
| 整对象替换 `= new …()` | 28 |

涉及原版方块（去重，47 个）：
`additiveReconstructor, airFactory, armoredConveyor, blastDrill, combustionGenerator, coreFoundation,
coreNucleus, coreShard, cyclone, defense, differentialGenerator, distribution, exponentialReconstructor,
foreshadow, fuse, groundFactory, impactReactor, impulsePump, junction, lancer, laserDrill, meltdown,
multiplicativeReconstructor, navalFactory, parallax, plastaniumConveyor, platedConduit, power, production,
ripple, rtgGenerator, salvo, scatter, siliconCrucible, siliconSmelter, spectre, steamGenerator, storage,
swarmer, tetrativeReconstructor, titaniumConveyor, tsunami, units, unloader, wave`
（`defense/production/power/distribution/storage/units` 是 import 通配符，不是内容名，扫描时需排除。）

另：`UnitTypes.` 43 处、`StatusEffects.` 38 处、`Items.` 57 处、`Liquids.` 15 处引用需一并纳入监视表。

## 三、执行步骤与触点

### 1. 监视表（静态、有界、不执行）
新增：`bridge-cli` 内 `VanillaWatchListScanner`。
- 输入：`--source`（目录或 ZIP），复用 `SafeSourceReader` 的条目/字节预算与路径校验。
- 逻辑：仅读 `*.java` 文本，正则扫描 `(Blocks|UnitTypes|StatusEffects|Items|Liquids)\.([A-Za-z0-9_]+)`，
  去重、排序、上限（建议 512 名/类型），排除通配符 import 段。
- 输出：按类型分组的名表，通过新 CLI 参数传给 extractor（例如
  `--watch-vanilla-block salvo,scatter,…` / `--watch-vanilla-unit …` / …）。
- 关闭开关时**不产生监视表**，extractor 不做任何原版捕获。

### 2. Extractor 捕获（`bridge-runtime-extractor`）
- `RuntimeExtractorMain.parse` 增加 watch 参数，写入 probe 启动命令与 `runtime-extractor-command.txt`。
- `runtime-snapshot-support.java.template`：
  - 新增 `captureVanillaFields(name, type)`：复用既有 `captureParserFields`（对原版内容而言
    fallback 就是它自己的 ClassMap 类），只捕获 watch 命中的内容；
  - 在 `PRE_CONTENT_INIT` 与 `FINAL_AFTER_MOD_INIT` 各捕获一次，**在 probe 内做差分**，
    只把变化字段写入快照，避免快照膨胀（预算：每类型 ≤512 内容、每内容 ≤256 变化字段、
    差分总量沿用既有快照字节预算）；
  - 新快照节：`vanillaOverrides: { "block": { "salvo": { "health": {typed value}, ... } } }`；
  - 记录被监视但在运行时不存在的内容名（`VANILLA_WATCH_TARGET_MISSING`）。
- 三阶段单调性规则不适用于原版内容（原版内容在 PRE 就存在），差分只比较 PRE 与 FINAL。

### 3. Mapper → patch HJSON（`bridge-runtime-mapper`）
新增 `VanillaOverridePatchMapper`：
- 读取 `vanillaOverrides`，按类型产出 `patches/vanilla-overrides.hjson`；
- 字段规格：为 block/unit/status/item/liquid 建立**标量白名单**（`health/armor/speed/reload/range/
  damage/statusDuration/…`），复用 `RuntimeSnapshotMapper` 的既有权值转换与
  `contentRef`/`color`/`enum` 编码；
- **禁止**产出 `requiredPlanets`（headless 服务器会在 `DataPatcher.java:224-247` 永久丢弃该 patch，
  导致服务端与客户端内容集分叉 —— 即已知崩溃类别）；
- 值不可表达时**不写该字段**，改为削减记录（见下）。

### 4. 打包与验证
- `RuntimePreparedConversion` 增加 patch 文件条目（`patches/vanilla-overrides.hjson`），
  `BridgeConverter.convertRuntimePrepared` 既有 patch 语义可直接复用
  （`isOriginalPatchPath` 已知该前缀）；
- 最终 `DataPatcher.apply` 必须仍为 `failed=0 / warnings=0`；
- 报告新增 `metadata.vanillaOverrides = on|off`、`patchCount`、`changedFieldCount`。

## 四、"绝不整块丢弃"硬规则与原因词表

规则：**只要根 parser 可构造，就必须产出该内容**；嵌套武器/部件/特效/回调缺失一律降级保留。

原因词表（每条削减都带 code + 说明 + 源位置）：

| 原因码 | 含义 |
|---|---|
| `UNREPRESENTABLE_BY_TARGET_MODEL` | 游戏机制无法实现对应能力（如 `MemoryWall` 受击成长护甲、`ExplodeWall` 拦截子弹、子弹 `handlePierce` 回调） |
| `CUSTOM_BUILD_CLASS_REDUCED` | 自定义 `Building` 子类被替换为原版基类 |
| `MOD_ONLY_FIELD_OMITTED` | mod 独有字段，目标 parser 不存在 |
| `LAMBDA_EFFECT_APPROXIMATED` | lambda/自定义 Effect 无法表达，已近似或置空 |
| `NESTED_OBJECT_OMITTED` | 嵌套对象（Weapon/Part/Ability）无法编码 |
| `VANILLA_WATCH_TARGET_MISSING` | 监视命中的原版内容在运行时不存在 |

报告新增"削减汇总"：按内容列出被削减的字段/对象、原因码、源位置，保证"缺失项有明确原因"。

## 五、验收标准

1. `--vanilla-overrides=on` 时产出 `patches/vanilla-overrides.hjson`，`--vanilla-overrides=off` 时完全不产出，
   且报告明确记录开关状态与跳过原因；
2. 47 个被引用的原版方块中，标量/嵌套标量改动全部进入 patch；不可表达项出现在削减汇总；
3. 官方 v160.5 `DataPatcher.apply` 仍为 `failed=0 / warnings=0`；
4. 新增单测：监视表扫描边界、watch 命中/未命中、差分只输出变化字段、patch 不携带 `requiredPlanets`、
   开关关闭时零原版侧效应；
5. 实测一轮 SF 4.0.6 + v160.5，输出对比数据（patch 条目数、变更字段数、削减数）。

## 六、风险

- **多人一致性**：patch 会作用于服务器上所有地图；必须要求服务端与客户端使用同一份 DP
  （同 SHA-256），并确保 patch 不含 `requiredPlanets`。
- **快照膨胀**：监视表可能覆盖数百个原版内容；差分必须在 probe 内完成，快照只保留变化字段。
- **误判为"变化"**：原版内容的 `init()` 派生字段（如 gas liquid 的 alpha）在 PRE→FINAL 间会自然变化。
  差分必须排除"由目标自身 init 推导"的字段，否则会把无害推导写成 patch。这是本方案最需要小心的一点，
  实现时应对每个变化字段额外做一次"仅原版运行时"的对照（不装 mod 的空跑快照），只保留两次运行之间的差异。
