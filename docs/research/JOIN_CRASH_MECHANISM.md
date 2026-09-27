# 多人服务器崩溃机制（v160.5 源码级分析）

最后更新：2026-09-27。结论基于 Mindustry **v160.5** 源码（`work/samples/Mindustry-160.5/core/src`）与本地实测。

## 一、进服时的真实数据流（服务端 → 客户端）

`mindustry/net/NetworkIO.java:30-75` `writeWorld(player, os)`：

| 顺序 | 调用 | 内容 |
|---:|---|---|
| 1 | `writer.writeDataPatches(stream, false)` | **服务端当前的整份 DP 资产表**（content/patch 内嵌；贴图/音频/bundle 只发 32 字节 hash） |
| 2 | rules / mapLocales / tags / wave / tick / 随机种子 | |
| 3 | `writer.writeContentHeader(stream)` | **按 id 顺序的内容名字表** |
| 4 | `writer.writeMap(stream)` | 方块按 **id** 写入 |
| 5 | `writer.writeEntityMapping` / `writeTeamBlocks` / `writeWorldEntities` | 实体、**建造计划**、单位 |
| 6 | markers / custom chunks | |

客户端侧 `NetworkIO.loadWorld`：`readDataPatches` → … → `readContentHeader`（建立 id→内容映射）→ `readMap` → `readEntities`。

## 二、id 不是靠"两边 id 一致"，而是靠"名字解析"

`mindustry/io/SaveVersion.java:627-644`：

```java
public void readContentHeader(DataInput stream){
    ...
    map[type.ordinal()][j] = content.getByName(type, name);   // 用名字在客户端本地解析
    content.setTemporaryMapper(map);
}
```

推论：**客户端不需要与服务器 id 相同**；只要**同名内容在客户端存在**即可。
反之，**名字缺失 → 映射项为 null → 后续所有按 id 的访问都会炸或静默替换**。

## 三、两处没有保护的 id 访问（崩溃点）

### 1. 建造中的方块（对应"有人建造时新玩家进服立刻崩"）

`SaveVersion.java:398-417` `writeTeamBlocks`：把每队的 `TeamData.plans`（**玩家正在建造的幽灵计划**）写成
`x / y / rotation / block.block.id / config`。
`SaveVersion.java:471-493` `readTeamBlocks`：

```java
short x = readShort(), y = readShort(), rot = readShort(), bid = readShort();
var obj = TypeIO.readObject(reads);
if(set.add(Point2.pack(x, y))){
    data.plans.addLast(new BlockPlan(x, y, rot, content.block(bid), obj));   // ← 无 null 检查
}
```

- 只要新客户端解析不出那个方块，`BlockPlan.block == null`；
- 之后任何一次使用 `plan.block`（渲染建造虚影、`size`、校验、同步）就 NPE → **进服即崩**；
- 只有"建造中"才会走这条路径 —— 与现象完全一致。

### 2. 地图地面/覆盖层（对应"服务器多人时经常崩"）

`SaveVersion.java:292-…` `readMap`：**方块 id 有 `Blocks.air` 兜底，floor/overlay 没有**。
配合 `MapIO.java:153`、`MapIO.java:235`（`((Floor)overlay).wallOre`）会出现 `ClassCastException`/NPE；
`world/Tile.java:240-243` 的 `setBlock` 在 null 时也会 NPE。
另外 `ContentLoader.getByID` 在映射项为 null 时会**静默替换为 `contentMap[0]`**，表现为"没崩但行为诡异/贴图错乱"。

### 3. 兜底路径：资产只发 hash

`writeDataPatches` 对非内嵌资产（贴图/音频/bundle）只发 32 字节 hash（`SaveVersion.java:604-625`）；
客户端若无对应缓存文件，则 `Log.warn("Asset @: cache file not found.", asset.path)`（`SaveVersion.java:587-590`），
该资产在客户端缺失 → 建造图/图标/音效缺失，也可能引发渲染路径异常。

## 四、已用本地实验排除的假设

| 假设 | 实验 | 结论 |
|---|---|---|
| 官方 160.3 与 160.5 内容 id 表不同导致漂移 | 同一份 DP 分别用官方 160.3（你生产环境的 `server-2026.09.13.B495.jar` 实为官方 160.3）与官方 160.5 加载并指纹 | **完全相同**（block 447 / item 37 / liquid 15 / unit 70，全局表 SHA-256 `eb96ea86…`）→ 排除 |
| 我们的 DP 加载顺序不确定 | 同一 jar、同一 DP 连跑两次 | **完全一致** → 排除 |
| DP 格式在 v160.5 被 gate | `DataPatcher.patchFormatVersion` 仍为 `2` | v159.7 产物在 160.5 可加载 |

## 五、由此得到的确定结论

崩溃的必要条件是：**客户端手里缺少服务端所引用的那个 DP 内容（按名字）**。可能来源：

1. **产物不完整**：DP 只有 Item/Liquid/Status（例如纯运行时路径的 45 项），而地图/server 引用了 Block/Unit（原 mod 有 252 个方块、63 个单位）→ 名字缺失 → 必崩。
2. **两端不是同一份 DP**：客户端导入的 DP 版本与服务器 `config/assets` 的版本不同（用户迭代过多次），或客户端根本没导入。
3. **混入额外内容提供者**：MindustryX 等 fork 自报 `type=official`、`build=160.x`，能通过 `NetServer.java:282-285` 的 build 检查，但其内容表与官方不同 → 混用时按 id 的路径（建造计划、地图方块）会错位。

> 官方版本之间（160.3 ↔ 160.5）不会因此崩；真正致命的是"内容集不一致"，而不是"小版本不同"。

## 六、因此需要做的产品侧改动

1. **内容名字级闭包**：DP 必须包含原 mod 运行时注册的全部可被地图引用的内容（含 Block/Unit）。
   只发 Item/Liquid/Status 的产物在服务器场景必然触发上述崩溃。
2. **产物指纹校验**：对同一产物的两种部署形态（`server-assets/` 目录 vs DP ZIP）各加载一次，
   比较"按类型 id|名字"指纹；不一致即失败。指纹同时写入报告，便于现场比对。
3. **两端一致性提示**：报告与 Web UI 明确要求"服务器与客户端必须使用同一份 DP（同一 SHA-256）"，
   并把 DP ZIP 的 SHA-256 作为运维核对项。
4. **崩溃现场采集**：见 `docs/SF_CRASH_TRIAGE.md`（官方客户端会自动写
   `%APPDATA%\Mindustry\crashes\crash-report-*.txt`）。

## 七、拿到堆栈后可立即区分的分支

| 堆栈特征 | 含义 | 处理 |
|---|---|---|
| `SaveVersion.readTeamBlocks` → `BlockPlan.<init>` / `plan.block` NPE | 建造计划方块名缺失 | 补全该方块的内容声明 |
| `SaveVersion.readMap` → `MapIO.colorFor` / `((Floor)overlay).wallOre` | floor/overlay 名缺失（多为地图来自更全的 DP/整 mod） | 补全 floor/overlay 内容 |
| `Tile.setBlock` NPE | 同上，方块缺失 | 同上 |
| 服务端日志 `Asset @: cache file not found.` | 客户端缺贴图/音频缓存 | 让客户端持有同一份 DP ZIP |
| 客户端长时间停在 "determining assets" | 服务端 `writeRequiredAssets` 抛 `Invalid asset (missing hash)` | 检查外部资产 hash/缓存 |
