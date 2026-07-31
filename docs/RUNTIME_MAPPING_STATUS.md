# 运行时 JAR → v159.7 DP 映射状态

最后更新：2026-08-01（Asia/Shanghai）

## 一句话状态

`runtime-convert` 已经从“只产生 snapshot”接通为可生成 DP 的本地主线：纯动态
路径稳定生成 Item/Liquid/StatusEffect；提供严格对应源码时，会增加
JAR class 行号来源可验证的 Block/Unit AST 候选，并由官方 DataPatcher 单调筛选。

管线结尾会对正式打包产物再执行一次完整 `DataPatcher.apply`。候选 parser/apply
通过不等于 Java 行为或地图玩法已语义等价。

## 固定运行时和安全开关

当前仅支持 pinned 官方 Mindustry `v159.7` Release Server JAR（项目目标
v159.7/B480）：

```text
SHA-256 e41289c32bcf765eb50fa131e6b515d741e20f7843fb567d3aa949e7461f22ab
```

该 JAR 同时用于加载原 Mod、筛选混合候选和验证最终 DP。动态模式必须显式传入：

```text
--allow-mod-execution
```

发布 JAR 以当前用户权限执行。独立 JVM 是故障隔离，不是恶意代码沙箱。Web 已退出
主线；`bridge-web` 仅保留历史静态功能，不执行或代理 `runtime-convert`。

## 当前管线

```text
官方 v159.7 真实加载发布 JAR
  -> PRE_CONTENT_INIT / POST_CONTENT_INIT / FINAL_AFTER_MOD_INIT
  -> schema v2 typed snapshot
  -> RuntimeSnapshotMapper
  -> runtime-only RuntimePreparedConversion
  -> [可选] source index + Java AST Block/Unit candidates
  -> [可选] DataPatcher 单调筛选
  -> BridgeConverter.convertRuntimePrepared
  -> 确定性 server-assets + DP ZIP
  -> structure + server discovery + 正式 DataPatcher.apply
```

输入 Mod JAR 的 SHA-256 会在映射前、打包前重新核对；Server JAR 在预检中执行固定哈希
策略，在最终验证前再核对指纹。

## 支持矩阵

| 内容 | 当前来源 | 当前结果 | 重要边界 |
|---|---|---|---|
| `Item` | 三阶段 runtime snapshot | 动态生成 | 只输出 v159.7 mapper 白名单字段 |
| `Liquid` / `CellLiquid` | 三阶段 runtime snapshot | 动态生成 | 自定义方法/效果降级 |
| `StatusEffect` | 三阶段 runtime snapshot | 动态生成 | callback、transition handler、自定义 Effect 不迁移 |
| `Block` | runtime 注册 + 可选源码 AST | 候选生成并筛选 | 必须精确 fallback/名称/行号来源；仅 clean apply 可接受 |
| `Unit` | runtime 注册 + 可选源码 AST | 候选生成并筛选 | 可将根 template 收窄为已证明的 `UnitType`/`MissileUnitType`，仍为降级 |
| JAR 资产 | 发布 JAR | 正式打包 | 仅 bundle/sprite/sound/music 玩法边界；源码资产永不覆盖 |
| 科技树/Planet/Sector/Mod 新地图 | 不采用 | 不迁移 | 项目硬边界 |
| GUI/网络/脚本 | 不采用 | 不迁移 | DP 不提供任意 Java 行为容器 |

### 还不是“纯运行时 Block/Unit 映射”

Extractor 已按官方 fallback parser schema 冻结 Block/Unit 根字段，但默认动态 mapper
当前不从这些字段直接生成 Block/Unit。原因是 Weapon、Bullet、Ability、Draw、Part、
Consume、Effect、工厂 plan、炮塔 ammo 等嵌套对象仍缺少完整、有界、可审计的通用编码。

当前扩大实用范围的工程方案是 runtime-guided AST 候选，而不是将整个 Mod 对象图
无限反射输出。

## 动态映射报告

`runtime-mapping.json` 对每个 runtime Content 记录：

- 实际 class、继承链、注册栈和三阶段 fallback；
- 选择的权威阶段与生命周期差异；
- 输出字段、coercion/fallback、丢弃字段和 opaque 原因；
- `converted`、`degraded`、`unsupported`、`failed`、`excluded` 结果；
- 自定义字段、方法覆写、custom-only 方法和 callback 损失；
- 每个 JAR 条目的文件级处理结果和输出路径。

任意快照字段名、自定义 `type` 或 opaque 对象都不能绕过 mapper 白名单注入
ContentParser。

## 混合候选的严格入选条件

可选源码只能补充动态 mapper 已明确标记为 `UNSUPPORTED` 的 Block/Unit。每个候选
必须同时满足：

1. runtime kind、完整注册名、输出本地名与输出路径唯一；
2. AST target type 与三阶段可接受的官方 `ClassMap` fallback 一致；
3. 源文件精确路径通过 source index 关联到权威 JAR class；
4. AST 声明的正行号必须存在该 class 的 runtime `LineNumberTable` 中；
5. 源文件没有 parse failure 或 error diagnostic，不采用 partial AST 结果；
6. 不与动态输出、其他候选或 basename 命名空间冲突。

候选使用的 HJSON 可来自源码 AST，但它的 `sourcePaths` 只能指向已验证的 JAR
class 条目；资产仍只来自 JAR。

## DataPatcher 单调筛选状态

自动筛选器已接入 `runtime-convert --source`：

1. 先验证 runtime-only base，要求 `failedAssets == 0` 且 `warningCount == 0`；
2. 从全量候选开始；
3. 只移除能被 `DATA_PATCH_CONTENT_FAILED`、`DATA_PATCH_APPLY_WARNING`、
   `DATA_ASSET_READ_FAILED` 精确归属的 content path；
4. 对减小集合重新 apply，直到得到零 warning/零 failure 集合或达到轮次上限；
5. 无法归属、Harness/协议/打包/超时故障或不收敛时，不接受未证明候选，安全回退未修改的
   runtime-only base；
6. 基线本身不 clean 时也不允许用候选“掩盖”基础失败，所有候选被暂留，
   正式最终 apply 仍会决定产物是否拒绝。

`hybrid-report.json` 保留发现阶段拒绝、每轮候选集、精确诊断、accepted/rejected/
unresolved 路径和原因。每轮原始 apply 输出位于
`logs/hybrid-selection/attempt-*/data-patch-apply.log`。

## 正式打包和最终验证

候选筛选仅处理声明集。正式阶段将筛选后的声明与完整 JAR 权威资产交给
`BridgeConverter.convertRuntimePrepared`，复用现有计划器和确定性打包器。

打包后必须全部执行：

1. DP ZIP 结构验证；
2. pinned Server 对 `server-assets/` 的资产发现；
3. pinned 官方 v159.7 `DataPatcher.apply`。

最终 `report.json` / `report.md` 以这一次正式 apply 为准。候选试验的 clean 结果不能
替代完整资产产物的最终验证。

## 已验证的纯动态基线

### 自编 fixture

```text
work/runtime-fixture-mod/runtime-fixture.jar
work/mindustry-v159.7-server-release.jar
work/runtime-pipeline-fixture-e2e-20260801-next/
```

| 项目 | 结果 |
|---|---:|
| 运行时注册 | 3 |
| 生成 Content | 3 |
| converted / degraded | 2 / 1 |
| unsupported / failed | 0 / 0 |
| DataPatcher added | 3 |
| DataPatcher failed / warnings | 0 / 0 |

该 fixture 证明 runtime mapper、converter、确定性 ZIP、Server 发现和正式 apply 已连通。

### New Horizon 2.2.1 纯动态

```text
work/runtime-convert-new-horizon-20260801-fixed/
```

| 项目 | 结果 |
|---|---:|
| runtime Content | 382 |
| 动态 Item/Liquid/Status | 56 |
| converted / degraded | 29 / 27 |
| unsupported / failed | 326 / 0 |
| 选中 JAR 资产 | 1575 |
| DataPatcher content / added | 56 / 56 |
| DataPatcher failed / warnings | 0 / 0 |
| structure / discovery / apply | 通过 |

该数字是纯动态 Item/Liquid/Status 的已完成基线，不包含 Block/Unit 自动混合筛选。

## New Horizon 2.2.1 混合自动 E2E

完整 `runtime-convert --source` 已通过，保留运行：

```text
work/runtime-convert-new-horizon-final2-20260801/
```

| 项目 | 结果 |
|---|---:|
| runtime Content | 382 |
| 动态 Item/Liquid/StatusEffect | 56 |
| static 生成 Block/Unit 声明 | 252 |
| DataPatcher-eligible 候选 | 141（131 Block + 10 Unit） |
| 发现阶段拒绝 | 111 |
| 单调筛选接受 | 87（85 Block + 2 Unit） |
| DataPatcher 筛选拒绝 | 54 |
| unresolved | 0 |
| 正式 content 文件 | 143 |
| 正式外部资产 | 1635 |
| 最终 DataPatcher failed / warnings | 0 / 0 |
| 最终 report error | 0 |

`runtime-pipeline.json` 中 preflight、runtime extraction、source index、runtime mapping、hybrid
selection、packaging 和 DP validation 全部为 `passed`。最终报告为 `partial`，原因是仍有明确的
Java 行为损失和 239 个 unsupported Content，不是打包或 DataPatcher 错误。报告汇总：

```text
converted = 29
degraded = 114
unsupported = 239
failed = 0
errors = 0
```

该自动运行精确复现了早期手工对照的 `56 + 85 + 2 = 143` 零 warning/零 failure
集合。手工目录仍保留为历史对照：

```text
work/hybrid-exact-newhorizon-20260801/
work/hybrid-units-newhorizon-20260801/
work/hybrid-clean-newhorizon-20260801/
```

## Java 行为报告语义

被 DataPatcher 接受的 Block/Unit 候选仍为 `DEGRADED`：

- 它证明数据声明可由官方 parser 构造；
- 它不证明对应 `.class` 的可执行方法已迁移；
- 对应 class 的文件级状态保持原有 `UNSUPPORTED`/`EXCLUDED` 语义，仅合并已补充的
  output path；
- 报告增加 `HYBRID_EXECUTABLE_BEHAVIOR_UNMIGRATED`；
- 静态导出器原有方法、lambda、字段与回退损失不得因候选被接受而删除。

## 当前硬边界

- 只接受 pinned 官方 v159.7 Server JAR；
- 无法在 v159.7 加载的旧 JAR 尚无双运行时兼容；
- 缺依赖、原生库失败或 Mod 启动崩溃时不能伪造快照；
- 发布 JAR 是运行时和资产权威，源码仅是严格候选；
- 不迁移科技树、Planet/Sector、Mod 新地图、GUI、网络协议和脚本；
- DP 无法通用安装 Java 方法、lambda、自定义实体或 custom build class；
- clean DataPatcher apply 只证明可解析/应用，不证明炮塔、工厂、单位、Desktop atlas、音频和
  地图存档语义已全部等价。

任何产物都应与 `runtime-mapping.json`、可选 `hybrid-report.json`、`runtime-pipeline.json`、
`report.json`、`report.md` 和全部 `logs/` 一起使用。
