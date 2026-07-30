# B480 客户端导入故障诊断与修复方案（2026-07-30）

状态：**真实 Desktop 已确认上一候选 ZIP 可以正确导入和加载；建造栏组合图、构件大小写和单位/炮塔描边已在转换器侧修复并生成新候选包，等待真实 Desktop 复测。地图退出崩溃已定位为 B480 客户端上游 `DataImagePacker.unload()` 缺陷，不能由纯 DP 无损修复。**

本文记录 Saturation Firepower 首次 Desktop 导入失败、随后同一客户端会话可能对大量新内容连续报错的根因，以及转换器侧采用的兼容方案。本文优先于旧产物说明；旧自动化验证仍可作为 HJSON/DataPatcher 基线，但不能继续作为客户端可导入证明。

## 已否决的旧 ZIP

以下 ZIP 已由真实客户端导入结果证明不可用，不得再次交付或称为候选包：

`work\saturation-static-20260730-060256\sfire-mod-dp-v159.7.zip`

SHA-256：

`B085C6533CC43367CBC488DB201D0414F3B212DDDF6B5D8C081C6482755569BE`

它通过了 Headless `DataManager.load` / `DataPatcher.apply`，但 Desktop atlas 生成阶段失败。由此再次确认：Headless apply 通过不能替代真实 Desktop 地图导入。

当前最终候选 ZIP：

`work\saturation-outlinefix-final-20260730-075936\sfire-mod-dp-v159.7.zip`

SHA-256：`EEEA579FA05CA961A7B9297DF908419369BA0480E3877F4FACE237DA2F4EFBA9`。

本轮 CLI 退出码为 0，真实 B480 `DataPatcher.apply` 结果为 2634 个资产、358 个根 Content、689 个加入内容对象、0 failed、0 warning。该最终候选尚待用户进行 Desktop 复测；此前的 `saturation-clientfix-20260730-070717` 已由用户确认能够正确导入和进入地图。

## 真实 Desktop 复测结果

用户对上一候选包 `saturation-clientfix-20260730-070717` 的实际客户端结果：

- DP 能够正确导入并加载，原先 OreBlock 越界和首次导入 `key cannot be null` 不再阻止进入地图；
- 大部分炮塔本体贴图正常；
- 大部分炮塔和由多个构件组成的工厂在建造栏中缺少底座/组合图标；
- 少数炮塔构件 region 为空，已观察到疑似重复命名空间名称 `dp-cimaidp-cimai`；
- 退出地图时客户端会崩溃；编辑器地图没有核心时也会崩溃；
- 用户评价当前效果已满足基本要求。

因此本轮已经证明“真实客户端可导入并进入地图”这一硬边界，但尚未证明地图退出/重开稳定、无核心编辑器场景稳定、所有组合图标完整或服务器实际加载该地图。

建造栏底座/组合图标缺失确实由 content-hash sentinel 绕过客户端 `createIcons` 所致。转换器现已离线生成炮塔及多层工厂的 `block-dp-<name>-full.png`，并为单位、腿、履带、武器、炮塔主体及 RegionPart 生成目标兼容描边。最终候选共离线生成 413 张 PNG，其中 87 张 full icon、326 张 outline/outlined sprite；仍有 152 条无法完整离线合成的项目，统一以 `B480_OFFLINE_CONTENT_SPRITES_PARTIAL` 明确报告，没有静默忽略。

退出地图与无核心编辑器地图退出均已由 `last_log.txt` 精确定位到同一 B480 上游缺陷：`Logic.reset -> DataManager.unload -> DataImagePacker.unload` 最终在 `ObjectSet.remove(null)` 抛出 `IllegalArgumentException: key cannot be null`。旧包主 atlas 为 4 页、约 50,525,073 padded pixels；强行压为一页需删除或缩放约三分之二资产，因此不存在可接受的纯 DP 无损规避。正确修复需要修改客户端 `DataImagePacker.unload()`；最小补丁见 `docs/patches/DataImagePacker-unload-fix.patch`。

## 故障一：B480 environment atlas 只能安全使用一页

B477、B480 以及所核查的当前 Mindustry 源码都为 Data Patch environment 图片分配固定的 `700 x 700` `PixmapPacker`。关键问题不是 PNG 损坏，而是：

1. packer 可以实际生成多页；
2. `DataImagePacker.pack` 只发布 `environmentPacker.getPages().first()`；
3. 后续页会被静默丢弃，通常不会产生能直接说明原因的警告；
4. 丢失 region 后，`OreBlock.createIcons` 可能取得 `1 x 1` 空白 Pixmap，最终在 `Pixmap.getA` / `PixmapRegion.getA` 越界。

旧 Saturation ZIP 在 `sprites/blocks/environment/` 下有 370 张 PNG。用精确 B480 `PixmapPacker(700, 700, 2, true)` 重放后生成 4 页，24 张矿物候选图全部落在第 3 页（零基页号 2），因此不可能由客户端看见。

已精确复现失败的四个内容为：

- `dp-dark-coal`
- `dp-dark-scrap`
- `dp-dark-thorium`
- `dp-dark-titanium`

详细 bytecode、精确客户端 harness 和复现记录见：

`work\diagnostics\client-b480\REPORT.md`

## 173 张 environment sprite 的兼容规划

修复方案不再把原 Mod 的整个 `blocks/environment` 目录原样塞进保留区。当前针对 Saturation 的目标规划为 **173 张必须驻留 environment atlas 的 sprite**；该数字是新包的规划目标，仍须在重新生成后由精确 B480 packer 确认最终只有一页。

规划规则：

1. 只保留运行时确实通过 cached terrain texture 读取的 Floor、OverlayFloor、ShallowLiquid、SteamVent、OreBlock、Cliff、StaticWall 等 region。
2. 普通建筑、UI、工具图、无已转换 cached-terrain owner 的图片移到普通多页 patch atlas，basename 保持不变。
3. 删除仅供离线加工的 `*-autotile.png`、`*-tiled.png` 源 sheet；运行时 bitmask region 单独保留。
4. 删除可选的 `StaticWall *-large` mosaic，保留普通墙体变体。
5. 普通 `32 x 32` 地形/矿物/墙体最多保留 2 帧；大于 `32 x 32` 的变体（例如 SteamVent）最多保留 1 帧。
6. autotile 不能按普通数字尾缀误删；例如 `reforced-floor-1` 的 `name-0..46` 运行时 bitmask region 必须全部保留。
7. 最终必须以精确 B480 Guillotine/PixmapPacker 的“一页”结果作为发布门槛，不能只依赖 raw/padded pixel 面积估算。

此兼容会降低少量地形随机外观多样性，但不应删除地图玩法所需的内容定义。被移动、删减和降级的每个文件必须进入转换报告。

## HJSON `variants` 同步重写

只删除 PNG 而保留原 HJSON `variants` 会让运行时继续查找不存在的 region。因此转换器在规划阶段读取 Block HJSON，并将发生降级的 `variants` 同步改写为实际保留帧数：

- 普通受管地形类型最多为 `2`；
- SteamVent 或含大型普通帧的内容最多为 `1`；
- autotile 使用独立规则，不能改写成普通 variant 语义；
- `variants = 0` 的内容不能因为文件名前缀相似而被误判为多帧内容。

报告诊断使用 `B480_ENVIRONMENT_VARIANTS_REDUCED`；metadata 包含 `b480EnvironmentReducedVariantSprites` 和 `b480EnvironmentRewrittenVariantContents`。这使用户能够明确看到哪些内容牺牲了外观帧数。

## OreBlock 运行时别名

下述 sentinel 方案会绕过 `OreBlock.createIcons`，因此不能再依赖该方法把 itemDrop 名称自动生成为正式 OreBlock region。转换器必须在 environment atlas 中直接补齐运行时名称。

Saturation 需要的逻辑别名为：

- `chromium1..2 -> ore-chromium1..2`
- `fermium1..2 -> ore-fermium1..2`
- `rubidium1..2 -> ore-rubidium1..2`
- `strontium1..2 -> ore-strontium1..2`

输出路径仍使用目标 `dp-` 命名空间对应的实际 basename。若 exact sprite 和 itemDrop fallback 均不存在，转换必须以 `B480_ORE_RUNTIME_ALIAS_MISSING` error 失败，而不能输出一个已知会显示 `env-error` 的包。成功生成的别名使用 `B480_ORE_RUNTIME_ALIASES_ADDED` 报告。

## 故障二：`DataImagePacker.unload` 的 `key cannot be null`

用户截图中的第二条堆栈为：

```text
IllegalArgument: 'key cannot be null.'
ObjectSet.locateKey
ObjectSet.remove
DataImagePacker.unload
DataManager.reloadImages
DataManager.regenerateContentSprites
```

这是 B480/Mindustry 客户端实现中的上游问题，而不是某个 HJSON 字段解析失败：pack 阶段把 patch textures 加入 `Core.atlas`，unload 阶段却在遍历 `patchAtlas.getTextures()` 时从同一集合删除。多页/重载状态下迭代可能产生 `null` 并触发 `remove(null)`；即便未立即抛出，也可能在 `Core.atlas` 留下已 dispose 的陈旧 texture。

这也解释了为什么首次报错后，在同一客户端会话再次加载会“有概率对几乎所有新内容连续报错”：客户端 atlas 状态可能已经被污染。转换器无法修改用户客户端的这段源码，只能避免让首次导入进入该路径。

## generated content-hash sentinel

转换器为每个发出的根 Content 添加一个 1 x 1 opaque PNG：

```text
sprites/generated/<type>_<Mindustry-Base32-content-hash>/bridge-sentinel-*.png
```

父目录严格使用 B480 `ContentAsset.hashData()` 对应的 SHA-256/Base32 编码。只要该 content hash 下已有 generated 图片，`DataManager` 就不会在首次导入时执行有问题的：

```text
createIcons -> reloadImages -> DataImagePacker.unload
```

sentinel 的名字不对应任何运行时 atlas region，只占一个不透明像素。它是对 B480 上游 unload bug 的确定性规避，不是客户端源码修复；因此重复替换已激活的 DP 时仍不能保证安全。报告诊断使用 `B480_CONTENT_REGENERATION_SENTINELS_ADDED`，metadata 使用 `b480ContentRegenerationSentinels`。

## 用户重新测试的硬性步骤

新 ZIP 生成后必须这样测试：

1. **完全退出并重启 Mindustry/MindustryX 客户端**，不要在出现过旧包异常的会话中继续覆盖导入。
2. 使用新建地图，或确认地图中不存在旧 Saturation DP 资产；不要直接覆盖已被旧包污染的编辑器状态。
3. 只导入新生成的 ZIP，不再使用上文已否决的 SHA-256。
4. 若仍失败，立即保留当次 `last_log.txt`、截图和测试地图，不要连续覆盖导入多次，以免混入 atlas 污染后的级联错误。
5. 日志中的 `A mod with the name 'mindustryx' is already imported.` 是重复 MindustryX Mod 问题，与本 DP 修复无关，应单独清理。

## 发布前最小验证门槛

新包交付用户前至少完成：

- 完整 Gradle 测试通过；
- 重新转换 Saturation，无转换 error/failed；
- 精确 B480 environment packer 得到 **1 page**，并核对计划的 173 张保留 sprite；
- 8 个 OreBlock 的运行时 region/alias 可解析；
- B480 Headless DataPatcher apply 通过；
- 新 ZIP 路径和 SHA-256 写入本文以及项目状态文档。

真实 Desktop 导入、地图保存重开和服务器加载仍由用户继续验证。在这三项完成前，项目状态必须保持 `PARTIAL`。
