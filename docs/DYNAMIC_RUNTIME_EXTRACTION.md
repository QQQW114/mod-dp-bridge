# 动态运行时提取主线

最后更新：2026-08-01（Asia/Shanghai）

## 决策

项目主线由“只静态解析输入 Mod”调整为：

1. 在独立 JVM 和隔离工作目录中，用匹配版本的 Mindustry Server 加载发布 JAR；
2. 以实际注册到 `Vars.content` 的对象作为权威输入；
3. 同时提供源码仓库时，只做静态来源关联、继承分析和报告增强，不用源码覆盖 JAR 的运行结果；
4. 将运行时对象导出为与 Mindustry 版本无关的中立 IR；
5. 将自定义类型回退到 v159.7 `ClassMap` 中最近、可由 DataPatcher 构造的原版类型；
6. 生成 DP 后再次通过真实 v159.7 `DataManager.load` / `DataPatcher.apply`，并与原 Mod 快照做字段级差异比较。

网站部署不再是项目目标。动态模式会执行第三方 Mod 代码，定位为本地、显式启用的高级转换流程。

## 输入权威关系

推荐同时提供：

```text
发布 JAR + 对应源码目录/源码 ZIP + 匹配的 Mindustry Server JAR
```

- 发布 JAR：决定实际注册了什么、最终字段值是什么；
- 源码仓库：用于把注册栈、class 调试信息和资源哈希关联回文件/行号；
- Server JAR：决定加载生命周期、原版类型注册表和目标 DataPatcher 行为。

若 JAR 与源码不匹配，JAR 仍是权威。源码关联必须降低置信度或禁用，禁止把源码中存在但运行时未注册的 Content 混入输出。

### 源运行时与目标运行时

当前 MVP 先使用同一个官方 v159.7 Server JAR 完成“加载 Mod”和“验证生成 DP”两件事。这能优先覆盖已经可以在 159.7 启动、但无法由静态 AST 完整恢复的 Java Mod。

为了继续覆盖“146+ 发布、但其 JAR 已无法直接在 159.7 启动”的旧 Mod，IR 和 CLI 必须预留双运行时模式：

```text
source-server.jar（与原 Mod 匹配，146+） -> 运行时快照/中立 IR
target-server.jar（v159.7/B480）         -> schema 映射、DP apply 与差异验证
```

双运行时不能保证修复所有旧 Mod：若原发布 JAR 连匹配的旧 Server 都无法加载、依赖缺失，或玩法依赖无法映射的自定义逻辑，仍会失败或降级。但它能避免把“旧 API 导致不能在 159.7 直接启动”误判成“内容完全不可转换”。在双运行时完成前，命令和报告必须明确标记当前只验证了“源 Mod 可在所给 v159.7 运行时加载”的范围。

## 已验证的最小原型

测试输入：

```text
C:\Users\qw114\Downloads\NewHorizonMod.2.2.1.jar
C:\Users\qw114\Downloads\NewHorizonMod-2.2.1.zip
```

运行时：

```text
C:\Users\qw114\Desktop\other\mdt保留\mod-dp-bridge\work\mindustry-v159.7-server-release.jar
```

这是从 Mindustry 官方 `v159.7` Release 下载的 `server-release.jar`：

```text
size: 19158443 bytes
sha256: E41289C32BCF765EB50FA131E6B515D741E20F7843FB567D3AA949E7461F22AB
version.properties: number=8, build=159.7, modifier=release, type=official
```

同样的 382 项结果也在本地 MDT/MindustryX B480 Server JAR 上复现；正式基线使用官方 JAR，避免把服务器分支行为误当成上游行为。

### 真实加载结果

可信 Probe Plugin 在临时 `config/mods` 中与 New Horizon 一同加载。New Horizon 在约 2 秒的进程运行中完成 `loadContent`，Probe 在 `ServerLoadEvent` 导出结果后退出。

实际属于 `new-horizon` 的注册对象：

| ContentType | 数量 |
|---|---:|
| block | 281 |
| unit | 40 |
| status | 21 |
| item | 18 |
| liquid | 17 |
| sector | 2 |
| weather | 1 |
| planet | 1 |
| loadout_UNUSED | 1 |
| **总计** | **382** |

项目玩法边界内的 item/liquid/status/unit/block 共 377 项。旧静态路径只发现 308 项：18 item、17 liquid、21 status、10 unit、242 block。动态加载额外发现 30 个单位和 39 个方块，直接证明该方向解决了自定义声明类型和辅助注册逻辑造成的大量漏项。

原始实验资料：

```text
work/runtime-poc-manual-20260801-035823/run/runtime-load.log
work/runtime-poc-manual-20260801-035823/run/runtime-load-official.log
work/runtime-poc-manual-20260801-035823/run/runtime-content-summary.json
```

### 原版父类回退

对 382 个注册对象沿运行时继承链查找 v159.7 `ClassMap.classes`：

- 381/382 能找到原版映射父类；
- 唯一没有映射的是项目边界外的自定义 `loadout_UNUSED` 数据库条目；
- 40 个单位可回退为 38 个 `UnitType` 和 2 个 `MissileUnitType`；
- 281 个方块中包含 25 个 `GenericCrafter`、18 个 `ItemTurret`，以及大量可识别的原版炮塔、发电、运输、液体和环境父类；
- 95 个方块只能回退到基础 `Block`，这些项目的功能降级风险最高，必须逐项报告，不能标为完整转换。

原始实验资料：

```text
work/runtime-fallback-poc-20260801-040129/run/runtime-fallback.log
```

### 无 javaagent 的注册来源追踪

`ServerLauncher` 的顺序是：

```text
Vars.init()
  -> Vars.content = new ContentLoader()
  -> mods.load()              # 构造可信 Probe 和输入 Mod
content.createBaseContent()
mods.loadScripts()
content.createModContent()
content.init()
```

Probe Plugin 构造器执行时，`Vars.content` 已创建但尚未注册基础/Mod Content。因此可以在确认注册表为空后，把它替换为只增加观测能力的 `ContentLoader` 子类：

- `handleMappableContent()` 调用 `super` 后记录当前注册栈；
- `init()` 在 `super.init()` 前保存 pre-init 快照，在之后保存 post-init 快照；
- 不修改输入 Mod 字节码，不需要 JVM `javaagent`，也不需要维护 Mindustry 二进制补丁。

New Horizon 已验证的精确来源示例：

```text
new-horizon-hard-light
  newhorizon.content.NHItems$1.<init>(NHItems.java:23)
  newhorizon.content.NHItems.load(NHItems.java:23)

new-horizon-ancient-artillery
  newhorizon.content.blocks.TurretBlock$1.<init>(TurretBlock.java:61)
  newhorizon.content.blocks.TurretBlock.load(TurretBlock.java:61)
  newhorizon.content.NHBlocks.load(NHBlocks.java:2157)

new-horizon-macrophage
  newhorizon.content.NHUnitTypes$17.<init>(NHUnitTypes.java:991)
  newhorizon.content.NHUnitTypes.load(NHUnitTypes.java:991)
```

原始实验资料：

```text
work/runtime-trace-poc-20260801-040511/run/runtime-trace.log
work/runtime-trace-poc-20260801-040511/run/runtime-trace-official.log
```

## 运行时 IR 要求

不能直接调用 Arc `Json.toJson(content)`：

- Mindustry 为 `MappableContent` 注册了“只写名称”的 serializer；
-普通反射会包含缓存、UI、纹理、函数、实体构造器、`minfo` 和循环引用；
- 自定义运行时类名不能出现在 DP 中，因为 DataPatcher 设置 `allowClassResolution=false`。

IR 至少应保存：

- Content 名称、ContentType、实际类、完整继承链；
- 最近的目标 `ClassMap` 父类；
- pre-init 与 post-init 字段快照；
- 注册调用栈及其 JAR/source 定位结果；
- 可序列化字段、目标字段类型、值和引用；
- 被丢弃字段及原因；
- Java 方法覆写、lambda、controller/entity provider 等行为风险；
- 资产最终 region/audio/bundle 名称及 JAR/source 路径；
- 每个字段的 `exact`、`coerced`、`fallback`、`dropped` 状态。

字段只从目标原版父类的可解析 schema 中读取。自定义子类新增字段默认不输出；若未来有明确、可验证的降级规则，再单独映射。

## 快照时机

必须同时保留：

1. **pre-init**：`createModContent()` 完成、`Content.init()` 尚未运行；最接近声明时配置，避免把 init 派生值重复写回 DP；
2. **post-init**：原 Mod 完整初始化后的实际状态，用于识别自定义 `init/postInit` 引入的差异和行为损失。

DP 生成主要使用 pre-init。pre/post 差异无法由目标原版 `init()` 自然重建时，应形成明确降级诊断。

## 目标序列化规则

- 根 Content 仅处理 v159.7 `ContentAsset.loadableContent` 与项目玩法边界的交集；
- 类型仅允许 v159.7 `ClassMap.classes` 和 `ContentParser` 特殊 parser 能构造的对象；
- 自定义类沿父类链回退，禁止输出自定义全限定类名；
- Content 引用写目标命名空间名称，而非递归展开；
- Weapon/Bullet/Ability/Effect/Draw/Part/Shoot/Consume 等按目标 parser 的专用语法编码；
- 对象图使用 identity/cycle 检测，循环、回调和运行时服务对象不得递归序列化；
- Block 消耗应重建为 `consumes` 伪字段，不能直接导出内部 consumer/cache 字段；
- `@NoPatch` 不能被简单等同于“新 Content 禁止设置”：例如新建 Block 时仍需要 `size`。应以 ContentParser 的创建语义和真实 apply 验证为准；
- 每个生成文件必须再次由真实 B480 DataPatcher apply，parser warning 也应进入报告。

## 安全边界

动态模式会运行第三方 JAR，独立 JVM 与临时工作目录只提供故障隔离，不是完整恶意代码沙箱。当前本地 MVP 必须：

- 通过独立 Java 进程运行；
- 使用一次性 data/config 目录；
- 设置内存和超时；
- 捕获完整 stdout/stderr；
- 超时后终止进程树；
- 默认不执行源码仓库中的 Gradle/Maven/脚本；
- CLI 明确提示输入 JAR拥有当前用户权限；
- 不恢复或部署 Web 服务。

若未来需要处理不可信来源，应要求用户在外部 VM/容器中运行整个 CLI。Windows 本机仅靠 ClassLoader、SecurityManager 或工作目录不能构成安全边界。

## 许可证

动态 Probe 只使用公开 Mindustry API 并由匹配 Server JAR 加载。若后续复制或修改 Mindustry 源码/二进制，应按上游许可证保留声明、公开相应修改并更新 `THIRD_PARTY_NOTICES.md`。输入 Mod 的源码和资产许可证仍由使用者负责，转换报告应保留来源信息。

## 实现顺序

1. 正式化可信 Probe Plugin 和独立进程驱动，输出版本化 runtime snapshot JSON；
2. 实现 JAR class/asset 与源码目录/ZIP 的非执行关联；
3. 导出注册栈、pre-init/post-init、实际类与 ClassMap fallback；
4. 建立最小 IR encoder，先覆盖 Item、Liquid、StatusEffect；
5. 扩展 UnitType、Weapon、Bullet、Ability；
6. 扩展 Block、Consume、Draw、Turret/Factory 特殊结构；
7. 复制并按实际加载语义处理 bundle/sprite/audio；
8. 生成 DP 后执行真实 apply，并与原始快照做语义差异报告；
9. 用 New Horizon 作为主压力样本，再回归 Saturation Firepower 和已有 DP/CP。
