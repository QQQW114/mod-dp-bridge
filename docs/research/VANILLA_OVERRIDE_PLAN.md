# 原版覆盖（Vanilla Override）实现方案

状态：**已实现**（`b943185`；开关 `--vanilla-overrides=on|off`，默认 on）。本文件保留原始设计，
并在第八节记录实现期间用源码实测纠正的两处关键判断。

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

## 七、实现记录（最终形态）

| 项 | 实现 |
|---|---|
| 监视表 | `VanillaWatchListScanner`（bridge-converter，公开 API）：只读 `*.java` 文本，正则 `(Blocks\|UnitTypes\|StatusEffects\|Items\|Liquids)\.<name>`，跳过通配符 import 行，去重排序，单类型 ≤512、总量 ≤1024，溢出写诊断 |
| 捕获 | 探针模板在**同一次运行**内写四个相位 TSV（`vanilla-<phase>.tsv`），行标记 `header/content/field/missing`，除标记外每列 Base64URL |
| 判定 | `VanillaOverrideExporter`（bridge-runtime-extractor，纯逻辑可单测）把字段判为 `LOAD_TIME`（`PRE != BASE`）或 `AFTER_INIT`（`FINAL != POST`），导出值一律取 `FINAL` |
| patch | `VanillaOverridePatchMapper`（bridge-runtime-mapper）产出 `patches/vanilla-overrides.hjson`；顶层键为单数类型名（`block/item/liquid/status/unit`），值为严格 JSON（JSON ⊂ HJSON） |
| 削减 | 每个被丢弃字段都带原因码：`NESTED_OBJECT_OMITTED`、`REFERENCED_CONTENT_NOT_MIGRATED`、`UNREPRESENTABLE_BY_TARGET_MODEL`、`VALUE_TRUNCATED`；报告 `vanilla-override-mapping.json` |
| 开关 | `--vanilla-overrides=on\|off`（默认 on）；关闭 / 无 `--source` / 源码无引用时报告 `<stage status="notRun">` 并给出 `skipReason` |
| 测试 | 导出器分类、扫描器边界、patch 映射、worker 命令行顺序、**模板↔导出器往返**（编译真实模板对着官方 jar 跑） |

## 八、实现期间被推翻的两个判断（源码证据）

原始设计假定"PRE 是未被 mod 触碰的原版状态"，据此只需 PRE↔FINAL 差分。实测否定：

1. **SF 的覆盖发生在 `loadContent()`，不是 `init()`。**
   `SFireMod.loadContent()` 末尾调用 `SFOverride.load()`（`src/SFire/SFireMod.java:43`），而
   `loadContent()` 由 `ContentLoader.createModContent()` 触发（`ContentLoader.java:77-81`），
   `createModContent()` 又早于 `content.init()`（`ClientLauncher.java:166-186`）。旁证：本项目自己的
   运行产物 `snapshot-pre-content-init.tsv` 有 374 行，即 PRE 相位上 mod 内容（以及 SF 对原版的改动）
   早已存在。
   ⇒ PRE 不是干净基线。

2. **因此不需要"对照空跑"，而是需要在同一次运行内更早取基线。**
   探针的 `TrackingContentLoader` 现在重写 `createModContent()`，在调用 `super` 之前先把被监视的原版
   内容落盘（`BEFORE_MOD_CONTENT`）——此时尚无任何 mod 代码运行过。四个相位于是各自有明确语义：

   | 相位 | 含义 |
   |---|---|
   | `BEFORE_MOD_CONTENT` | 干净原版（mod 内容创建之前） |
   | `PRE_CONTENT_INIT` | mod 声明期改动之后、目标自身 init 之前 |
   | `POST_CONTENT_INIT` | 目标自身 `init()/postInit()` 之后 |
   | `FINAL_AFTER_MOD_INIT` | 所有 `Mod#init()` 之后（如 SF 的 `TechFix`） |

   判定规则因此是"两个时刻任一被改动"：`PRE != BASE` 或 `FINAL != POST`，导出值取 `FINAL`
   （因为 DP patch 在目标 init **之后**才应用，写最终值才等价于 mod 的实际运行状态）。
   只有目标自己推导出来的字段（两个条件都不成立）永远不会进 patch，噪声问题自然消失。

3. **patch 确实能改原版内容**（原始设计的关键前提，已用源码确认）：
   `DataPatcher.apply` 对每条 patch 走 `assign(root, "block.salvo", …)` →
   `resolve(root,"block")` 返回 `nameToType.get("block")` 对应的 `Vars.content.getNamesBy(block)`
   （`DataPatcher.java:60-64, 730-736`），即**名称→内容**映射，原版内容同样在表内；随后按目标
   Json schema 逐字段赋值，`@NoPatch` 字段会被拒绝。patch 在 `state.data.load(...)`
   （`SaveVersion.java:598/652`）时应用，即游戏/进服加载地图时，晚于 `content.init()`。

4. **"名字"有歧义，而且踩过坑。** 静态扫描得到的是声明字段名（`Blocks.titaniumConveyor`、
   `Items.blastCompound`），注册表与 patch 用的是内容名（`titanium-conveyor`、`blast-compound`）。
   第一版实现直接用字段名去 `getByName`，结果 116 个监视名里 36 个"查不到"（全是多词名），
   这些覆盖被静默丢掉（只留下 `missingWatchTargets` 记录）。
   现在探针按三级解析：注册表原名 → **声明类公有静态字段**（`mindustry.content.Blocks` 等，反射取值，
   不执行 mod 代码） → kebab 变体；并在产物里同时记录 `name`（监视名）与 `runtimeName`（注册表名）。
   实测：修好后 116/116 全部解析，patch 30 个内容全部使用 `titanium-conveyor` 这类正确键名。
   （附带教训：`kebab` 规则只覆盖 88% 的字段声明，不能单独作为方案。）

5. **两个"看起来该做的事"其实必须拒绝，否则转换器/验证会失败。**
   - 每个 `RuntimePreparedFile` 都要有真实 release-JAR provenance，否则
     `RUNTIME_PREPARED_PROVENANCE_MISSING` 硬失败；patch 的 provenance 取"命中监视表的源码文件
     `.java` → 打包的 `.class`"，实测解析出 8 个条目（`SFire/content/SFOverride.class`、
     `SFire/TechFix.class`、`SFBlocks`、`SFUnitTypes` …）。
   - `@NoPatch` 字段（`Block.size/hasItems/hasPower/buildType` 等）被 DataPatcher 拒绝并记 warning，
     而验收要求 `warnings=0`；探针在捕获原版字段时直接跳过它们，并且为此加了往返测试
     （夹具改 `Block.size`，断言它不会出现在覆盖里）。

6. **顺带修掉的两个未受测缺陷**（都由新测试抓出来，不是由 E2E 抓出来的）：
   - worker 命令行的 `-D` 属性被追加到程序参数区（必须在 `-cp` 之前），且按相位生成的属性名漏了 `-D`
     前缀 —— `RuntimeExtractorWorkerCommandTest` 现在守着这个顺序契约。
   - 探针写 TSV 时内容名没有 base64 编码，而导出器按编码列解码（`salvo` 长度 %4==1 直接抛
     `Last unit does not have enough valid bits`）—— `VanillaWatchRoundTripTemplateTest` 现在编译真实模板、
     对着官方 jar 跑、再把输出喂给导出器，专门守列契约。

