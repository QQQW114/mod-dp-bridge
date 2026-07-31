# 动态运行时提取主线

最后更新：2026-08-01（Asia/Shanghai）

## 决策

对编译型 Java/Kotlin Mod，项目不再试图仅从 JAR 内文本或源码 AST 猜测 Mindustry
实际注册结果。当前主线为：

```text
可信发布 JAR
  -> 官方 v159.7 Server 真实加载
  -> 观测 Vars.content 的三阶段实例状态
  -> 中立 typed snapshot
  -> v159.7 严格白名单 mapper
  -> 可选 runtime-guided 源码候选
  -> 官方 DataPatcher 筛选和最终验证
```

这条主线不使用 javaagent，不修改输入 Mod JAR，也不执行源码仓库的 Gradle、Maven
或脚本。Web 部署已退出主线；动态模式只是本地 CLI 中、用户显式启用的高信任流程。

## 版本和执行边界

当前源运行时与目标运行时是同一个 pinned 官方 Mindustry `v159.7`
`server-release.jar`（项目目标 v159.7/B480）：

```text
SHA-256 e41289c32bcf765eb50fa131e6b515d741e20f7843fb567d3aa949e7461f22ab
```

CLI 会在执行前拒绝其他 Server JAR，并要求：

```text
--allow-mod-execution
```

输入 Mod JAR 会拥有当前用户的 JVM、文件和网络权限。独立进程、工作目录、堆限额、
快照字节预算和超时为故障隔离，不是恶意代码沙箱。不信任的 JAR 应在外部 VM/
容器内处理。

当前并未实现“146–158 源 Server + v159.7 目标 Server”双运行时。因旧 API、缺失
依赖、原生库或加载崩溃而无法在官方 v159.7 启动的 JAR，当前无法获得可用的完整
运行时快照。

## 输入权威关系

推荐输入：

```text
发布 JAR + 可选的对应源码目录/ZIP + pinned 官方 Server JAR
```

- **发布 JAR**：决定实际加载了什么、注册了什么、运行时字段值以及可打包的
  bundle/sprite/sound/music 字节。
- **源码**：只用于静态 AST 候选和 JAR class `LineNumberTable` 的精确来源关联。
- **Server JAR**：决定 Mod 加载生命周期、官方 `ClassMap`/parser schema 和最终 DataPatcher
  行为。

当 JAR 与源码不一致时，JAR 始终胜出。源码中存在但运行时未注册的 Content 不得
进入产物，源码资产不得覆盖 JAR 资产。

## 提取机制

### 受信 Probe

Extractor 用精确 Server JAR 和 JDK 17 `javac` 临时编译项目自带 Probe。Probe 在
Mindustry 注册基础/Mod Content 之前确认 `Vars.content` 为空，再替换为只增加跟踪和快照
能力的 `ContentLoader` 子类。

它记录 `handleMappableContent()` 的注册栈，并在 `ContentLoader.init()` 前后冻结快照。
最终快照不会在 Probe 的第一个 `ServerLoadEvent` listener 中立即退出；它经两次
`Core.app.post` 延后，使 Mod 的 event handler 和其已投递工作先获得执行机会。

### 三阶段快照

每个目标 Mod Content 保留：

1. **PRE_CONTENT_INIT**：`createModContent()` 已完成，`Content.init()` 尚未运行；
2. **POST_CONTENT_INIT**：`Content.init()` / `postInit()` 已运行；
3. **FINAL_AFTER_MOD_INIT**：全部 `Mod.init()` 完成，并在 `ServerLoadEvent` handler 获得运行
   机会后冻结。

`PRE_CONTENT_INIT` 是声明型字段的首选来源；后两阶段用于发现 Mindustry 生命周期派生值和
Mod 后续修改。如果内容在后续阶段合法晚注册，快照契约允许注册 key 集合单调增长，
但最终阶段必须覆盖最终实际注册集。缺失必要阶段或不满足快照契约会使提取失败。

## typed snapshot 安全规则

不能对 Content 直接调用 Arc `Json.toJson()`，也不能递归反射整个运行时对象图：

- `MappableContent` serializer 可能只写名称；
- 实例包含纹理、UI、函数、entity provider、缓存和循环引用；
- Mod 可覆写 getter、`toString()`、serializer 或修改全局 parser/ClassMap 状态；
- DataPatcher 禁止解析 Mod 自定义类名。

因此快照仅使用提前冻结的官方 `ClassMap` fallback 和目标 parser 字段元数据，通过
`Field.get()` 读取目标类所有的字段。不调用 Mod getter、`toString()`、`equals()` 或任意
serializer。

快照为每个 Content 保留：

- 名称、ContentType、runtime class、完整继承链；
- 最近、可构造的官方 `ClassMap` fallback；
- 注册调用栈；
- 三阶段的 typed fields；
- 自定义字段名、覆写方法、custom-only 方法和已知 callback 损失；
- 不可安全表达的 `opaque` 值及原因。

数组、可信 Arc 容器、字符串、单对象、每根节点数、深度、单记录字节和整体快照字节
都有硬预算。不可表达对象写入 `opaque`，而不是触发任意对象行为。

## 当前 mapper 范围

纯动态 mapper 当前仅正式生成：

- `Item`；
- `Liquid` / `CellLiquid`；
- `StatusEffect`。

三阶段快照已能捕获 Block/Unit 官方 fallback 根字段，但纯动态 mapper 不会因此
就输出 Block/Unit。Weapon、BulletType、Ability、Draw、Consume、Effect 等嵌套对象图尚未有
通用、可审计的完整编码器。

当提供匹配源码时，系统可以为运行时真实注册且动态 mapper 标记为
`UNSUPPORTED` 的 Block/Unit 生成 AST 候选。候选必须与发布 JAR class 的源路径和
`LineNumberTable` 声明行精确匹配，然后再由官方 DataPatcher 单调筛选。

## 资产

资产字节只从发布 JAR 选择。当前服务器玩法边界包含：

- `bundles/*.properties`；
- `sprites/**/*.png`；
- `sounds/**/*.(ogg|mp3)`；
- `music/**/*.(ogg|mp3)`。

maps、scripts、shaders、textures、GUI 等边界外目录不迁移。Mindustry 的 basename/加载顺序
冲突必须显式决策并报告，不能静默选择。源码树的资产即使哈希不同，也只能记录
差异，不得覆盖 JAR。

## DataPatcher 的两种角色

### 候选筛选

可选源码 Block/Unit 必须先和 runtime-only base 分开试验。单调筛选器：

1. 先要求 runtime-only base 零 failed/零 warning；
2. 试验全部候选；
3. 只按 DataPatcher 报告的精确 content path 移除可归因候选；
4. 反复验证减小后的集合，发现依赖闭包问题；
5. 无法归因、子进程异常或不收敛时安全回退 runtime-only base，不猜测接受候选。

### 正式最终验证

候选试验可省略外部资产以减少重复打包。筛选完成后，正式产物使用完整 JAR
权威资产重新打包，并再执行一次完整的 v159.7 `DataPatcher.apply`。该次正式 apply
才是最终报告的应用级权威；失败或 warning 会拒绝验证结果。

## Java 行为不迁移

动态加载可以观测“对象最后是什么”，但 DP 无法安装任意 Java 方法。以下内容必须
保留为降级/未迁移：

- 方法覆写、lambda、callback、自定义 Effect 逻辑；
- custom build class、实体实现、AI provider/controller；
- GUI、网络协议、脚本、科技树、Planet/Sector 和 Mod 新地图逻辑；
- 完全自定义且无原版/DataPatcher 可表达类型的效果。

混合候选即使通过零 warning/零 failure apply，也仍标为 `DEGRADED`，并保留
`HYBRID_EXECUTABLE_BEHAVIOR_UNMIGRATED`。

## 已验证基线

### 运行时注册与动态 mapper

New Horizon 2.2.1 在 pinned 官方 v159.7 上已证明：

```text
382 runtime Content
18 Item + 17 Liquid + 21 StatusEffect = 56 动态生成 Content
1575 个正式 JAR 资产
DataPatcher: 56 added / 0 failed / 0 warning
```

保留产物：

```text
work/runtime-convert-new-horizon-20260801-fixed/
```

### New Horizon 混合自动 E2E

完整 `runtime-convert --source` 已自动复现早期手工 143 Content 基线：

```text
382 runtime Content
56 动态 Item/Liquid/StatusEffect
141 个 DataPatcher-eligible 候选（131 Block + 10 Unit）
87 个候选被接受（85 Block + 2 Unit）
143 个正式 content 文件
1635 个正式外部资产
最终 DataPatcher: 0 failed / 0 warning
最终 report: 0 error
```

保留产物：

```text
work/runtime-convert-new-horizon-final2-20260801/
```

早期手工对照仍保留在：

```text
work/hybrid-exact-newhorizon-20260801/
work/hybrid-units-newhorizon-20260801/
work/hybrid-clean-newhorizon-20260801/
```

自动化结果证明候选发现、行号来源、单调筛选、正式资产打包和最终 apply 已连通；
它仍不证明 Java 行为或实际地图玩法完全等价。

## 验证仍不覆盖的事项

Headless 结构检查、Server 资产发现和 DataPatcher.apply 能证明 DP 可被官方数据系统
读取，但不能证明：

- Desktop atlas 组装和描边贴图最终效果；
- 音频解码和实际播放；
- 带核心/无核心编辑器地图、退出地图、存档重开；
- 炮塔开火、工厂计划、单位实体/AI 与自定义特效的语义等价。

最终产物必须与 `runtime-mapping.json`、可选 `hybrid-report.json`、`report.json`、
`report.md`、`runtime-pipeline.json` 和 `logs/` 一起审阅。
