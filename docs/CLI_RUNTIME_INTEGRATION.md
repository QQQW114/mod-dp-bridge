# `runtime-convert` 本地运行时转换主线

最后更新：2026-08-01（Asia/Shanghai）

## 定位

`runtime-convert` 是编译型 Java/Kotlin Mod 的当前主线。它与不执行输入代码的
`convert INPUT` 分开，不会由文件扩展名或静态流程隐式启用。本地 Web UI 可以在
操作员预先启用运行时能力、作业再次确认信任后，使用固定参数列表代理该命令。

```text
可信发布 JAR
  -> 官方 Mindustry v159.7 Server 在独立 JVM 中真实加载
  -> Vars.content 三阶段快照
  -> Item/Liquid/StatusEffect 动态映射
  -> 可选源码的 Block/Unit AST 候选
  -> 官方 DataPatcher 单调筛选
  -> 复用 BridgeConverter 正式打包
  -> 结构、Server 资产发现、最终 DataPatcher.apply
```

`bridge-web` 是仅 loopback 的本地任务管理层，保留静态/可信运行时双模式。它默认
关闭运行时执行，不允许浏览器上传或指定 Server JAR，也不允许请求注入任意 CLI 参数。
这不改变本页所述的执行信任边界。

## 固定目标运行时

当前只接受 Mindustry 官方 `v159.7` Release `server-release.jar`（项目目标
v159.7/B480），预检会校验精确 SHA-256：

```text
e41289c32bcf765eb50fa131e6b515d741e20f7843fb567d3aa949e7461f22ab
```

同一个 pinned JAR 同时用于：

1. 加载原 Mod 并产生运行时快照；
2. 筛选可选 Block/Unit 候选；
3. 对最终 DP 执行 Server 资产发现和 `DataPatcher.apply`。

因此，当前不是“任意 Server JAR”接口，也未实现使用旧版源运行时的双运行时
迁移。本就无法在官方 v159.7 启动的 146–158 发布 JAR 不在当前动态主线的
可保证范围内。

## 命令

先构建本地 CLI 分发包：

```powershell
.\scripts\gradle.ps1 :bridge-cli:installDist --no-daemon
```

只提供发布 JAR 时：

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat runtime-convert `
  --mod-jar "C:\path\to\trusted-mod.jar" `
  --server-jar "C:\path\to\official-v159.7-server-release.jar" `
  --allow-mod-execution `
  -o ".\out\trusted-mod-runtime"
```

同时提供匹配的源码目录或源码 ZIP 时：

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat runtime-convert `
  --mod-jar "C:\path\to\trusted-mod.jar" `
  --source "C:\path\to\matching-source.zip" `
  --server-jar "C:\path\to\official-v159.7-server-release.jar" `
  --allow-mod-execution `
  --runtime-timeout 180 `
  --server-timeout 60 `
  --hybrid-max-rounds 8 `
  -o ".\out\trusted-mod-runtime" `
  --overwrite
```

环境需要 JDK 17 或更高版本，不能是缺少 `javac` 的精简 JRE。Extractor 会对精确的
pinned Server JAR 编译项目自带的 Probe。

### 参数语义

- `--mod-jar`：必填。已构建的发布 JAR，是实际注册 Content 和资产字节的权威来源。
- `--server-jar`：必填。必须通过官方 v159.7 JAR 的固定哈希校验。
- `--allow-mod-execution`：必填的显式同意。未提供时预检直接失败。
- `--source`：可选。只静态解析 Java 文件并建立 class/行号来源；不执行 Gradle、
  Maven、脚本或源代码，不从源码树复制资产。
- `--runtime-timeout`：原 Mod 加载与快照子进程的有效超时。超时时终止子进程树。
- `--server-timeout`：每次候选 `DataPatcher` 试验、最终 Server 发现和最终
  `DataPatcher.apply` 子进程的有效超时。
- `--hybrid-max-rounds`：源码候选单调筛选的最大候选轮数；基础 runtime-only 验证不消耗该轮数。
- `--mod-id`：仅在 Mod 描述符检测有歧义时覆盖内部 Mod 名。
- `--overwrite`：只清理该输出目录中由 `runtime-convert` 管理的旧产物，不跟随符号链接。

## 安全模型

`--allow-mod-execution` 不是普通的确认提示，而是一条信任边界：

- 输入 Mod JAR 会以当前用户的文件、网络和 JVM 权限执行；
- 官方 Server JAR 也会执行；
- 独立 JVM、一次性 data/config 目录、堆限额、快照字节预算和超时只是故障隔离，
  **不是恶意代码沙箱**；
- 源码输入不执行，但仍有 ZIP 路径、展开大小、文件数、单文件和 AST 产物预算。

对不信任的 Mod，应在用户自行管理的 VM/容器中运行整个工具。本地 Web UI 同样
不是沙箱；它只允许 loopback 且没有认证、授权或租户隔离，不得作为公共、内网共享
或远程上传服务。

## 实际管线

### 1. 预检和输入指纹

管线检查路径、显式执行同意、超时/轮次参数，并记录 Mod JAR 与 Server JAR
的 SHA-256。映射前、打包前和最终验证前会重新校验指纹，防止转换期间输入被替换。

### 2. 独立 JVM 真实加载

CLI 启动 `bridge-runtime-extractor`；extractor 再启动隔离的 Mindustry worker。受信 Probe
在原版 Content 注册表为空时安装只增加观测能力的 `ContentLoader` 子类，不使用
javaagent，不修改输入 Mod 字节码。

每个目标 Mod Content 保留：

1. `PRE_CONTENT_INIT`：内容已注册，`Content.init()` 尚未执行；
2. `POST_CONTENT_INIT`：`Content.init()/postInit()` 已执行；
3. `FINAL_AFTER_MOD_INIT`：全部 `Mod.init()` 和 `ServerLoadEvent` handler 获得执行机会后，
   再通过应用队列延后冻结最终快照。

### 3. 动态 mapper

当前默认 `RuntimeSnapshotV2MappingStage` 已接通到正式打包与验证。它从三阶段
typed snapshot 生成：

- `Item`；
- `Liquid` / `CellLiquid`；
- `StatusEffect`；
- 来自发布 JAR 的 bundle、sprite、sound、music。

Block/Unit 根对象已有有界字段快照，但纯动态 mapper 当前仍不将其生成为 DP；
嵌套 Weapon/Bullet/Ability/Draw/Consume 等也没有通用对象图还原。

### 4. 可选源码候选

提供 `--source` 时，静态导出的 Block/Unit 声明必须通过所有关联门槛：

- 动态 mapper 已将对应运行时 Content 明确标为 `UNSUPPORTED`；
- kind、名称、输出路径、命名空间和官方 `ClassMap` fallback 精确匹配；
- 源文件路径经严格规范化，源文件未出现 parse failure 或 error diagnostic；
- 候选声明的正行号必须存在于 source index 关联的发布 JAR class `LineNumberTable`
  中，且 class entry 必须真实存在于权威 JAR；
- 不使用模糊 path alias，不采用源码资产。

通过来源关联只能证明“此数据声明对应发布 class”，不能证明 Java 行为已迁移。

### 5. DataPatcher 单调筛选

候选阶段先用官方 `DataPatcher.apply` 验证 runtime-only base，且要求零 failed、零 warning。
然后对全量候选执行严格的减小集合筛选：

1. 只允许 `DATA_PATCH_CONTENT_FAILED`、`DATA_PATCH_APPLY_WARNING`、
   `DATA_ASSET_READ_FAILED` 中可精确归属到候选输出路径的诊断移除候选；
2. 移除后再次 apply，用后续轮次发现依赖闭包失效；
3. 任何无法归属、Harness/协议/超时/打包异常或轮次不收敛，都不猜测删除对象；
4. 不能安全完成候选归因时，保留未改动的 runtime-only base，并将候选标记为
   rejected/unresolved。

该筛选只为候选分类，不取代最终验证。

### 6. 正式打包与最终 apply

定稿的 `RuntimePreparedConversion` 交给 `BridgeConverter.convertRuntimePrepared`，复用现有路径
冲突检查、命名空间处理、贴图/音频/bundle 处理、确定性 `server-assets/` 和 ZIP 打包。

正式产物会再次使用完整 JAR 权威资产执行：

1. `Mindustry1597StructuralValidator`；
2. Server 资产目录发现；
3. 官方 v159.7 `DataPatcher.apply`。

最终 apply 失败或产生 warning 时，产物不得被报告为已验证成功。候选试验通过也不代表
炮塔开火、工厂生产、单位 AI/实体、Desktop atlas、音频解码、地图导入或存档重开已验证。

## 资产与 Java 行为边界

- 发布 JAR 始终是 bundle/sprite/sound/music 字节的唯一权威；
- 源码目录中同名、更新或“更完整”的资产不得覆盖 JAR；
- 候选 HJSON 被接受只表示官方 parser/apply 可接受该数据声明；
- Java 方法覆写、lambda/callback、custom build class、自定义实体/AI、网络与 GUI 行为不会
  被 DP 通用迁移；
- 被采用的候选仍以 `DEGRADED` 报告，对应 `.class` 的原文件级结果不会因“数据声明
  被补充”而伪装成 Java 行为已转换，并保留 `HYBRID_EXECUTABLE_BEHAVIOR_UNMIGRATED`。

## 产物

```text
<output>/
  runtime-snapshot.json
  runtime-mapping.json
  source-index-report.json                     # 提供 --source 且索引成功时
  hybrid-report.json                           # 运行混合候选阶段时
  runtime-pipeline.json
  <mod-name>-dp-v159.7.zip
  server-assets/
  report.json
  report.md
  logs/
    conversion.log
    runtime-extractor.log
    runtime-extractor-command.txt
    runtime-work/run-.../
      headless.log
      registration-traces.tsv
      registration-phases.tsv
      snapshot-pre-content-init.tsv
      snapshot-post-content-init.tsv
      snapshot-final-after-mod-init.tsv
    hybrid-selection/attempt-.../data-patch-apply.log
    server-asset-discovery.log
    data-patch-apply.log
```

`runtime-pipeline.json` 记录每个阶段的 passed/failed/notRun 与产物。`runtime-mapping.json`
记录动态映射、降级和 unsupported；`hybrid-report.json` 记录候选发现、每轮 apply、
accepted/rejected/unresolved；`report.json` / `report.md` 以正式打包与最终 apply 为准。

## New Horizon 2.2.1 自动 E2E

已用发布 JAR + 对应源码 ZIP 运行完整 `runtime-convert --source`：

```text
work/runtime-convert-new-horizon-final2-20260801/
```

| 阶段 | 结果 |
|---|---:|
| runtime 真实注册 | 382 |
| 动态 Item/Liquid/StatusEffect | 56 |
| DataPatcher-eligible Block/Unit 候选 | 141（131 Block + 10 Unit） |
| 单调筛选接受 | 87（85 Block + 2 Unit） |
| 正式 content 文件 | 143 |
| 正式外部资产 | 1635 |
| 最终 DataPatcher failed / warnings | 0 / 0 |
| 最终 report error | 0 |

`runtime-pipeline.json` 的 extraction、source index、mapping、hybrid selection、packaging 与
DP validation 阶段均为 `passed`。该结果复现了早期 143 Content 手工基线，但仍仅证明
管线和官方 parser/apply 可接受，不代表 Java 方法或实际地图玩法已完全等价。

后续真实客户端测试确认 DP 能加载并出现新内容，但炮塔缺少弹药，退出地图时崩溃；
服务器地图/存档加载未测试。New Horizon 大量依赖自定义 Java 行为，不适合作为总体
兼容率样本，这项人工结果反而验证了本节的成功语义边界。

## 成功语义

`runtime-convert` 返回 `completed` 只表示：

- 官方 pinned v159.7 加载与三阶段快照完成；
- mapper/可选候选阶段产生了至少一个内容声明；
- 正式 ZIP 与 `server-assets/` 打包完成；
- 结构、Server 资产发现和最终 `DataPatcher.apply` 通过。

它不等于“无损转换”或“地图中所有玩法已证明等价”。即使客户端已经成功导入，用户
仍需在带核心/无核心地图、存档重开、退出流程、单位、炮塔和工厂场景中验证。
