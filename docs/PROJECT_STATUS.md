# 项目进度

最后更新：2026-07-30 08:02（Asia/Shanghai）

## 当前结论

项目已实现可运行的无前端 CLI，并完成高价值 Java Mod 样本 Saturation Firepower 的确定性 Java AST 静态转换。转换器不编译、不加载、不反射、不执行输入 Mod 代码，而是把可由 Mindustry v159.7/B480 Data Assets 表达的对象图导出为 HJSON，并对不能等价表达的行为逐项降级和报告。

## B480 Desktop 导入已通过；描边/组合图修复候选待复测，退出崩溃属于客户端上游

真实客户端测试已否决此前的 Saturation ZIP。旧包触发了 B480 固定 `700x700` environment atlas 多页静默丢失；随后还出现上游 `DataImagePacker.unload` 的 `key cannot be null`，并可能污染同一客户端会话，使后续导入产生级联错误。

当前修复主线包括：173 张 environment sprite 单页规划、地形 HJSON `variants` 同步降级、OreBlock 运行时别名，以及按 Content hash 生成 sentinel 以绕开首次导入的 `createIcons -> reloadImages -> unload` 路径。详细记录见：`docs/B480_CLIENT_IMPORT_FIX_20260730.md`。

上一候选 ZIP `saturation-clientfix-20260730-070717` 已由用户确认能在真实 Desktop 正确导入并进入地图。针对用户随后反馈的建造栏组合图、少数镜像构件和单位/炮塔缺描边问题，转换器已完成确定性离线生成与命名规范化。

当前最终候选 ZIP：`work\saturation-outlinefix-final-20260730-075936\sfire-mod-dp-v159.7.zip`；SHA-256：`EEEA579FA05CA961A7B9297DF908419369BA0480E3877F4FACE237DA2F4EFBA9`；大小 6,958,884 bytes（约 6.64 MiB）。真实 B480 `DataPatcher.apply`：2634 assets、358 根 Content、689 added content、0 failed、0 warning。

本轮新增/修复：87 个炮塔或多层工厂 full icon、326 个 outline/outlined sprite，共 413 张离线生成 PNG；329 个唯一 content-hash sentinel；`*-R/-L.png` 规范为 `*-r/-l.png` 并同步改写显式引用；共享武器 generated atlas 名去重；`cimai` 的 full/preview/主体与所有 RegionPart outline 已生成；`liemei-barrel-R.png` 已规范输出为 `liemei-barrel-r.png`。仍有 152 条离线图标缺失或不支持项，均由 `B480_OFFLINE_CONTENT_SPRITES_PARTIAL` 汇总报告。

退出地图与无核心编辑器地图退出崩溃已精确定位为 B480 客户端 `DataImagePacker.unload()` 上游错误：`ObjectSet.remove(null)` 导致 `IllegalArgumentException: key cannot be null`。纯 DP 无法在保留全部资产的前提下修复；正确方案是应用 `docs/patches/DataImagePacker-unload-fix.patch` 修改客户端。项目状态仍为 **PARTIAL**：最终候选尚待 Desktop 复测，退出崩溃不能宣称已由 DP 修复，服务器真实地图加载也仍未验证。

以下产物只保留为旧 Headless 自动化基线，**不是可继续测试或交付的客户端候选**：

`work\saturation-static-20260730-060256`

DP ZIP：

`work\saturation-static-20260730-060256\sfire-mod-dp-v159.7.zip`

SHA-256：

`B085C6533CC43367CBC488DB201D0414F3B212DDDF6B5D8C081C6482755569BE`

该旧产物通过了 v159.7 静态结构检查和真实 B480 `DataManager.load` / `DataPatcher.apply`，但已被 Desktop 导入失败否决。新包现已通过真实 Desktop 导入和进入地图；项目状态仍为 **PARTIAL**，因为退出/无核心场景会崩溃、部分组合图缺失且服务器真实地图加载尚未验证。

集中验证记录见：`docs/SATURATION_FINAL_VALIDATION_20260730.md`。

## 已完成的主干能力

### 输入、安全与输出

- 支持目录、ZIP/JAR、JSON/HJSON/JSON5 文本输入。
- 识别普通 Mod、旧 CP PatchSet 和已有 v159 DP。
- 安全扫描目录和压缩包，限制 ZIP Slip、文件数、单文件/总大小、压缩比和路径长度。
- 不执行输入 Java、JavaScript、Kotlin、Gradle 或 Maven 代码。
- 输出：
  - `<name>-dp-v159.7.zip`
  - `server-assets/`
  - `report.json`
  - `report.md`
  - `logs/conversion.log`
  - `logs/data-patch-apply.log`
  - `logs/server-asset-discovery.log`
- ZIP 使用稳定条目顺序和时间戳；最终 Saturation 产物已复打包验证 SHA-256 一致。

### 声明式 Mod、已有 DP 与 Legacy CP

- 转换 Item、Block、Liquid、Status、Unit、Weather 根 Content，以及可由 v159.7 `ClassMap` 表达的嵌套对象。
- 递归移除 `research` 并报告。
- `ModNamespaceRewriter` 通过符号表迁移内容、patch、bundle、sprite、sound、music 和部分 generated 引用到 `dp-`。
- 已有 DP 的 content/patch 文本保持原始字节，避免破坏 generated content hash。
- Legacy CP 输出为 `patches/<slug>.hjson`，并对部分旧社区语法提供有警告的兼容修复。
- maps、Planet/Sector、科技树、脚本、GUI/网络和 `sprites-override` 按范围明确排除。

### Java AST 静态导出

`bridge-java-static` 已进入主干，不再是“尚未实现”的规划项。当前能力包括：

- JavaParser AST 扫描和内容声明符号表；
- 字面量、常量、算术/布尔表达式、颜色、枚举和内容引用；
- 匿名初始化器、方法局部常量和已知链式赋值；
- requirements、ItemStack/LiquidStack、Consume、ammo、plans、upgrades；
- Unit entity/controller/defaultCommand；
- Weapon、Bullet、Ability、Effect、Draw、Part、Shoot 的大量 v159.7 构造器和字段；
- `Weapon.copy/copyRotate/copyRotRel`；
- 数组、Seq、Map、二维 upgrade 数组；
- 受限且确定性的 `for`/嵌套循环静态展开；
- 对已生成 Content 的跨语句字段赋值；
- 内嵌 `MissileUnitType` 提升为独立 Unit HJSON；
- 自定义 Block/Bullet 到内置父类的显式降级；
- 自定义或 lambda Effect 到 `Fx.none` 的明确近似；
- v159.7 不接受字段的转换期移除和诊断；
- 固定目标原版对象快照：当前用于恢复 `Blocks.tsunami` 的 slag ammo Bullet，不加载游戏运行时。

当前明确支持的高级静态语义：

- 4 个内嵌导弹单位已提升：`knocker-missile`、`blade-missile`、`sundown-missile`、`defense-platform-nuke-missile`；
- 7 个确定性循环已展开；
- 5 个跨 Content 赋值已应用；
- 加载期随机表达式采用确定性中点近似并报告；
- `SFBlocks.tieliu` 的 tsunami-slag `fragBullet` 已由绑定 v159.7 的固定快照恢复；
- 当前 `JAVA_FIELD_EXPRESSION_OMITTED = 0`。

### 资源与引用

- 复制 PNG、音频资产和 bundle；不重新编码或按容器改扩展名。
- `AssetReferenceValidator` 检查已知 region/sprite/icon/sound/music 字段。
- 相同 basename PNG 字节相同则确定性去重，不同则拒绝碰撞。
- sound/music 共享运行时 namespace，冲突会拒绝。
- Headless 只验证路径、hash、注册和引用解析，不能验证客户端解码、atlas 或播放。

### 正式 v159.7 验证

- `Mindustry1597StructuralValidator` 检查目录、扩展、根类型、basename 冲突和 generated 规则。
- `Mindustry1597ContentApplyValidator` 在隔离 JVM 中运行项目内置固定 harness，真实调用可信 B480 JAR 的 `Vars.state.data.load(...)` / `DataPatcher.apply(...)`。
- harness 不编译或执行输入 Mod 代码。
- 普通 Server 冷启动只用于资产文件发现，不能作为 apply 成功证据。
- 未加载携带 DP 的地图/存档时，`SERVER_LOAD` 必须保持 `NOT_RUN`。

## 当前自动化结果

### 测试

当前测试汇总：53 项全部通过，0 failed，0 skipped。

| 模块 | 测试数 |
|---|---:|
| `bridge-model` | 5 |
| `bridge-target-api` | 1 |
| `bridge-target-1597` | 7 |
| `bridge-converter` | 18 |
| `bridge-java-static` | 22 |
| **合计** | **53** |

### Saturation Firepower 最终转换

| 指标 | 结果 |
|---|---:|
| 扫描源文件 | 1764 |
| 根 Content | 358 |
| 外部资产 | 2276 |
| Data Assets 总数 | 2634 |
| converted Content | 295 |
| degraded Content | 63 |
| excluded / unsupported / failed Content | 0 / 0 / 0 |
| 报告 info / warning / error | 139 / 64 / 0 |
| 离线生成 full icon / outline | 87 / 326 |
| 离线生成图合计 | 413 |
| 明确报告的离线生成缺失项 | 152 |

按 Content 类型：

| 类型 | converted | degraded | 合计 |
|---|---:|---:|---:|
| Item | 15 | 0 | 15 |
| Liquid | 6 | 0 | 6 |
| Status | 10 | 12 | 22 |
| Unit | 49 | 14 | 63 |
| Block | 215 | 37 | 252 |
| **合计** | **295** | **63** | **358** |

64 个 warning 是转换报告中的明确降级/资产审查警告，其中 5 个为逐文件 `AUDIO_CONTAINER_EXTENSION_MISMATCH`，另含 152 条离线图标缺失/不支持项目的汇总诊断；它们不是 B480 parser/apply warning。正式 apply 结果为：

```text
assets=2634
content=358
external=2276
addedContent=689
failed=0
warnings=0
```

验证阶段：

- `STRUCTURE = PASSED`
- `RUNTIME = PASSED`
- `MAP_IMPORT = NOT_RUN`
- `SERVER_LOAD = NOT_RUN`

文件级规划结果：

- copied：1416
- converted：115
- excluded：129
- unsupported：104
- failed：0

文件级 unsupported 主要是工程文件、旧备份/非 Data Assets 目录和不支持扩展；它与 358 个目标 Content 的 `unsupported = 0` 是不同统计维度。

## 已完成的真实 Desktop 代表测试

### minimal 普通数据 Mod

- Desktop 导入 9 个资源。
- `dp-fixture-wall`、`dp-fixture-drone`、`dp-fixture-alloy` 正确注册。
- 4 个额外星球标签和原版 `heat-source` 已确认是 `shownPlanets` 缺省导致的原版数据库展示副作用，不是转换包注册 Planet。
- 尚未完成地图保存重开、武器、音效和服务器地图联测。

### `惊鸿3.zip`

- Desktop 导入 17 个资源。
- 惊鸿炮塔注册成功，用户确认所测核心逻辑正常。
- 输入/输出 17 个条目路径和字节保持一致。
- 两个音效、全部 generated/region 和地图持久化未逐项验证。

上述两个较小样本不能单独外推为 Saturation 已通过 Desktop；不过 Saturation 上一候选包随后已由用户确认能够正确导入并进入地图。当前描边/组合图最终候选仍需单独复测。

## 当前降级边界

Saturation 的 63 个 degraded Content 已生成并被 B480 接受，但仍含明确损失：

- 自定义 Block 类降级为内置父类，Java-only 建造/伤害/功率/强化逻辑丢失；
- 自定义 Bullet 降级为 `BasicBulletType`，体积增伤、破盾、穿透增伤等自定义行为丢失；
- Java 方法覆写和状态 `TransitionHandler` 无法安装；
- 自定义 Effect factory 和 lambda 绘制 Effect 只能替换为 `Fx.none` 或保留其余数据；
- v159.7 DataPatcher 不接受的字段被移除；
- 一处加载期随机值已替换为固定中点 5，因此分布语义不等价；
- 4 个 Content 缺少常规图标候选；另有 152 条离线 full/outline 合成缺失或不支持项，均已在报告中明确列出，必须由 Desktop 实际观察。

这些不是程序失败，而是 DP 运行时表达能力或当前静态映射的可审计降级。报告必须与产物一起交付。

## 尚未完成与主要风险

1. **当前最终候选尚未 Desktop 复测**：上一候选已成功导入并进入地图，但不能把该结论自动外推到本轮新增的 413 张离线生成贴图。
2. **客户端资产盲区**：不能声称全部 2264 个 PNG ZIP 条目、10 个音频资产的 atlas region、generated 或触发播放均已通过。10 个音频文件名均以 `.ogg` 结尾，但文件头审计为 5 个 OGG、3 个 MP3 容器、2 个 WAV 容器；5 个不匹配项已报告 `AUDIO_CONTAINER_EXTENSION_MISMATCH`，当前保持原名和原始字节，不自动转码。
3. **地图持久化未测试**：保存、完全退出、重开后的 DP 内嵌恢复未验证。
4. **玩法未测试**：关键单位、炮塔、工厂、弹丸、Consume、AI、状态、Effect 的实际行为未验证。
5. **服务器地图加载未测试**：当前只完成 DataPatcher apply 和普通文件发现，没有加载携带 DP 的真实地图/存档。
6. **自定义 Java 行为不可等价表达**：Agent 也不能扩大 v159.7 DP 的运行时能力。
7. **固定目标快照有版本绑定**：`tsunami + slag` 快照只对当前 v159.7/B480 目标负责，升级版本必须重新核对源码。
8. **146–158 无独立运行器**：旧 Mod 只做尽力静态迁移。
9. **仅编译 JAR 的 Java 逻辑未反编译**：Java AST exporter 要求 `.java` 源码。只含 `.class` 的发布 JAR 可迁移资产/声明式内容，但其字节码玩法逻辑会明确报 error；当前高价值 Saturation 验证使用的是完整源码目录。
10. **网站未实现**：当前结果是 CLI/开源主干，网站仍为可选后续项。

## 当前硬成功标准

某一转换产物只有同时满足下列条件，才可称为最终可用：

1. 转换报告无未处理 error/failed；
2. degraded/excluded/unsupported 均已审查并被接受；
3. v159.7 结构验证和真实 B480 DataPatcher apply 通过；
4. v159.7 Desktop 导入 DP ZIP 成功；
5. 地图保存、完全退出客户端、重开后内容仍存在；
6. 代表性物品、液体、地形、炮塔、工厂、单位、武器、状态、贴图、特效和音效通过人工测试；
7. 匹配 B480 服务器加载用户导出的目标地图/存档并完成短时联机测试。

当前最终候选已完成第 1–3 项；第 4 项只有上一候选取得实证，本轮包仍待复测，第 5–7 项也未完成，因此状态必须保持 `PARTIAL`。

## 下一步

1. 将最终 ZIP 和报告交给用户用 v159.7/B480 Desktop 导入。
2. 在编辑器中放置代表性地形、运输、电力、炮塔、工厂和单位，实际生产与开火。
3. 检查报告中的 63 个 degraded Content、4 个常规 Content 图标警告及 152 条离线合成缺失项，重点验证高级炮塔与单位。
4. 检查所有关键贴图、Effect、DrawPart 和 10 个音频资产；特别验证 3 个 MP3 容器和 2 个 WAV 容器使用 `.ogg` 扩展名时，目标 Desktop 是否仍能正确解码和播放。
5. 保存地图、完全退出、重启并重新打开。
6. 导出测试地图，由匹配 B480 服务器实际加载；保留客户端/服务器日志、地图和截图/视频。
7. 根据真实测试结果再决定是否补规则、处理版本快照或进入网站化阶段。
