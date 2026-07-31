# JAR 运行时权威 + 源码 Block/Unit 候选

最后更新：2026-08-01（已接入 `runtime-convert`）

## 状态与结论

本文件名保留 `PLAN`，但核心管线已实现，不再只是手工实验：

```text
pinned 官方 v159.7 真实加载发布 JAR
  -> 三阶段 runtime snapshot 确认真实注册项
  -> 动态 mapper 生成 Item/Liquid/StatusEffect runtime base
  -> 静态 Java 导出器生成惰性 Block/Unit 候选
  -> runtime identity + fallback + JAR class 行号来源严格关联
  -> 官方 DataPatcher 单调筛选
  -> 与未改动的 runtime base 合并
  -> 使用完整 JAR 资产正式打包
  -> 正式 DataPatcher.apply 再验证
```

这条路线不让源码替代运行时事实。发布 JAR 仍决定实际注册 Content 和资产字节；
源码只能提供对应于 JAR class 调试行号的惰性数据声明候选。

Web 已退出主线。该系统只在本地 CLI 中运行，且必须显式传入
`--allow-mod-execution`。

## 固定版本与权威输入

当前候选筛选与正式验证只使用 pinned 官方 Mindustry `v159.7` Release Server JAR
（项目目标 v159.7/B480）：

```text
SHA-256 e41289c32bcf765eb50fa131e6b515d741e20f7843fb567d3aa949e7461f22ab
```

权威关系：

| 输入 | 允许作用 | 禁止作用 |
|---|---|---|
| 发布 Mod JAR | 实际运行时注册、class provenance、bundle/sprite/sound/music 字节 | 无 |
| 源码目录/ZIP | Java AST 声明候选、源文件路径与行号定位 | 执行 build/脚本，添加 runtime 未注册内容，覆盖 JAR 资产 |
| pinned Server JAR | 加载生命周期、官方 fallback/parser、DataPatcher 试验与正式 apply | 替换为其他版本或修改版 JAR |

## 三阶段 runtime 前置条件

混合阶段必须使用 schema v2 三阶段快照：

1. `PRE_CONTENT_INIT`；
2. `POST_CONTENT_INIT`；
3. `FINAL_AFTER_MOD_INIT`。

候选只能补充运行时真实注册，且动态 mapper 已明确标记为 `UNSUPPORTED` 的
`Block` / `Unit`。Item、Liquid、StatusEffect 仍以动态 mapper 为准，不被 AST 候选覆盖。

候选根 type 必须与 runtime snapshot 的官方 `ClassMap` fallback 一致。对 Unit 可显式将
Mod 自定义 template 收窄为 runtime 已证明的 `UnitType` 或 `MissileUnitType`，但必须单独
标记 `HYBRID_UNIT_TEMPLATE_REPLACED`，并且只有 clean DataPatcher 候选结果后才能采用。

## 严格 JAR class 行号来源

这是混合模式的关键 fail-closed 边界。一个 AST 声明不会因“文件名看起来像”而被接受。
它必须通过以下链路：

```text
AST 输出 content path
  -> 唯一 source outcome
  -> 安全规范化的 source path
  -> source-index 对应的发布 JAR class entry
  -> sourceCoversRuntimeLines == true
  -> 非空 runtime LineNumberTable
  -> AST 声明正行号存在于该 class 行号集
```

具体规则：

1. 候选必须有唯一、安全的 source path 结果；
2. 只允许精确规范化路径匹配；仅可对经验证的单一 GitHub 外层目录做一次统一根
   调整，不使用宽松 basename/path alias；
3. source index 记录的 JAR class path 必须真实存在于权威发布 JAR 文件结果中；
4. AST 声明位置必须有正整数行号，且该行号必须在关联 class 的 runtime line number 集中；
5. 源文件的 static outcome 是 `FAILED`，或该文件有 error-severity parse diagnostic 时，即使 exporter
   产生了 partial AST 候选也必须拒绝；
6. NUL、控制字符、绝对路径、驱动器/冒号、`.`/`..` 段、超长路径或歧义路径一律拒绝。

一个候选可对应一个或多个经行号验证的 JAR class entry，但产物中的 `sourcePaths`
只指向这些 JAR entry，而不将本地源码文件当作打包输入。

## 候选发现条件

除来源校验外，每个候选还必须满足：

1. content kind 是 `Block` 或 `Unit`；
2. runtime `(kind, local name)` 是唯一真实注册项；
3. static output path 符合对应 kind 的规范 content 路径；
4. static output name 与 runtime 完整注册名对应；
5. static target type 精确等于 runtime fallback，或满足被显式允许的 Unit fallback 替换规则；
6. 没有与 runtime 已生成文件、其他候选路径、大小写规范路径或 content basename 冲突；
7. 不读取或采用源码树资产。

不满足门槛的候选在 DataPatcher 之前就被拒绝，并以稳定 diagnostic code 写入
`hybrid-report.json` 和最终 content/file 结果。

## 资源预算

源码目录/ZIP 是不执行输入，但仍需要防止压缩包和 AST 产物放大。默认限制包含：

- source archive 最大 entry 数；
- 最大 Java 文件数；
- 单 Java 文件与 Java 总展开字节；
- 最大 Block/Unit 候选数；
- 最大静态生成文件数、单文件字节和生成总字节；
- 压缩比、路径长度、snapshot 文件和 source-index report 字节。

超出任一预算时混合阶段 fail closed，不会将 partial 候选混入 runtime base。

## DataPatcher 单调筛选

默认算法是确定性、只减不增的候选集合：

### 0. runtime-only 基线

先将不包含任何可选 Block/Unit 候选的 runtime base 交给官方 DataPatcher。基线必须：

```text
failedAssets == 0
warningCount == 0
```

如果基线本身不 clean，候选不得被用来掩盖基础错误；所有候选暂留，并保留未改动的
runtime base 交由最终正式 apply 判定是否拒绝。

### 1. 全量候选

第一个候选轮次包含全部通过静态门槛的候选。每轮在新的尝试目录中物化声明，
然后执行 pinned 官方 DataPatcher harness。

### 2. 精确路径归因

只允许以下 path-bearing diagnostic code 移除候选：

```text
DATA_PATCH_CONTENT_FAILED
DATA_PATCH_APPLY_WARNING
DATA_ASSET_READ_FAILED
```

诊断中的路径必须规范化后精确对应当轮的某一候选 content path。无路径、路径歧义、
不在当轮候选集或任何非白名单诊断都不得触发猜测删除。

### 3. 减小集合重验

移除已归因候选后重新 apply。若新诊断是因依赖被移除而发生，后续轮次将其标记为
dependency-closure rejection。只有当剩余集合零 failed/零 warning 时，该集合才是 accepted。

### 4. 安全回退

以下任一情况都回退到未修改的 runtime-only base：

- 候选打包或 DataPatcher 子进程失败；
- Harness/协议错误或超时；
- blocking diagnostic 不能完整、精确地归属到候选路径；
- 本轮失败但没有可安全移除的候选；
- 达到 `--hybrid-max-rounds` 仍不收敛。

回退时 `acceptedPaths` 为空，已精确判定的拒绝项保留 rejected，其余项保留 unresolved。
可选混合阶段异常也由上层 pipeline 捕获，不会污染 runtime base。

## 候选试验与正式产物的区别

候选试验为了减少重复打包，只物化 content 声明，不复制数千个外部 sprite/audio/bundle。
筛选完成后，正式 `RuntimePreparedConversion` 仍包含完整 JAR 权威资产，交给
`BridgeConverter.convertRuntimePrepared`。

正式 ZIP 和 `server-assets/` 打包完成后，pipeline 会再执行：

1. 结构验证；
2. Server 资产发现；
3. 完整的 pinned v159.7 `DataPatcher.apply`。

只有此次正式 apply 通过，才能将最终产物标记为验证通过。

## 报告语义

`hybrid-report.json` 必须包含：

- 候选发现数、通过门槛数和发现阶段拒绝数；
- 每个候选的 runtime name/kind/fallback、static target type、source path、JAR class provenance；
- runtime-only baseline 轮次；
- 每个候选轮次的 tested/removed 路径、DataPatcher 计数、日志和决策；
- accepted/rejected/unresolved 路径与稳定原因；
- unattributed diagnostic 和安全回退原因。

每个源码候选的最终语义为以下之一：

- 接受：通过 clean 官方 DataPatcher 筛选；
- 发现阶段拒绝：runtime identity/fallback/来源/路径/冲突不满足；
- DataPatcher warning 拒绝；
- DataPatcher failure/资产读取拒绝；
- 依赖闭包拒绝；
- 自动筛选无法安全归因的 unresolved。

全部原始候选 apply 输出位于：

```text
logs/hybrid-selection/attempt-*/data-patch-apply.log
```

## Java 行为未迁移

源码 AST 候选只补充 DataPatcher 能表达的声明数据。即使候选被接受：

- content-level 结果仍是 `DEGRADED`；
- 对应 JAR `.class` 的原文件级状态保持，不会被修改为“可执行代码已转换”；
- class file result 仅合并候选 output path，并增加
  `HYBRID_STATIC_SUPPLEMENT_APPLIED` 与 `HYBRID_EXECUTABLE_BEHAVIOR_UNMIGRATED`；
- 静态导出器原有字段/方法/lambda/callback 损失必须继续出现在 content result 和 diagnostic 中；
- clean parser/apply 只证明声明可构造，不证明 custom build class、炮塔开火、工厂生产、单位
  实体/AI、自定义 Effect 或存档语义等价。

## New Horizon 自动 E2E

输入：

```text
<local-downloads>\NewHorizonMod.2.2.1.jar
<local-downloads>\NewHorizonMod-2.2.1.zip
```

完整 `runtime-convert --source` 已通过，并自动复现早期手工筛选集：

```text
382 runtime Content
18 Item + 17 Liquid + 21 StatusEffect = 56 动态基础 Content
141 个 DataPatcher-eligible 候选（131 Block + 10 Unit）
87 个 accepted（85 Block + 2 Unit）
54 个 DataPatcher-rejected
0 unresolved
正式合计 143 Content
正式外部资产 1635
最终 DataPatcher failed = 0
最终 DataPatcher warnings = 0
最终 report errors = 0
```

自动运行保留目录：

```text
work/runtime-convert-new-horizon-final2-20260801/
```

其 `runtime-pipeline.json` 中 extraction、source index、mapping、hybrid selection、packaging 和
DP validation 全部为 `passed`。`hybrid-report.json` 记录 111 个发现阶段拒绝、141 个入选
候选、87 个接受项、54 个 DataPatcher 拒绝项和 0 unresolved。

早期手工对照保留目录：

```text
work/hybrid-exact-newhorizon-20260801/
work/hybrid-units-newhorizon-20260801/
work/hybrid-clean-newhorizon-20260801/
```

该自动 E2E 证明严格 JAR class 行号来源、候选发现、单调筛选、JAR 权威资产打包和
正式 apply 已经连通；仍不能将 clean parser/apply 解释为 Java 行为或实际地图玩法完全等价。

## 与纯运行时对象图的关系

混合路线扩大了“发布 JAR + 对应开源仓库”的实用范围，但不替代纯运行时 mapper：

- 用户只提供 JAR 时，当前仍只能动态生成 Item/Liquid/StatusEffect；
- 源码不匹配 JAR，或 JAR 缺少可用行号表时，候选会 fail closed；
- Kotlin/生成代码、helper 循环、动态注册表可能无法形成 AST 候选；
- 未来的有界运行时 Weapon/Bullet/Ability/Draw/Consume 对象图仍是“只有 JAR”输入的必要方向；
- 无论是 AST 还是运行时对象图，都不能把任意 Java 方法变成纯 DP。

## 当前硬边界

- 只支持 pinned 官方 v159.7 Server JAR；
- 必须显式 `--allow-mod-execution`；
- 发布 JAR 是运行时和资产权威；
- 源码只解析，不构建、不执行、不供应资产；
- 必须有三阶段 runtime snapshot 和严格 JAR class 行号来源；
- 不迁移 GUI、网络、脚本、科技树、Planet/Sector 和 Mod 新地图；
- 候选通过 DataPatcher 仍只能标记数据声明已补充、Java 行为未迁移；
- 最终产物必须再经正式完整 DataPatcher.apply，并与报告/日志一起使用。
