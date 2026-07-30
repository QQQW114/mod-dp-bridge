# Saturation Firepower 转换可行性评估

最后更新：2026-07-30 06:04（Asia/Shanghai）

评估对象：

`<local-Saturation-Firepower-source>`

目标运行时：Mindustry v159.7 / B480 Data Assets。

最终验证记录：`docs/SATURATION_FINAL_VALIDATION_20260730.md`。

## 结论摘要

Saturation Firepower 已证明：**不引入 Agent、也不执行输入 Mod 代码，仍可通过确定性的 Java AST 静态编译恢复典型 Java Mod 的大部分标准内容对象图。**

当前最终转换已生成全部 358 个目标根 Content 和 1628 个外部资产；295 个 Content 标为 converted，63 个标为 degraded，Content 的 unsupported/failed 均为 0。真实 B480 `DataManager.load` / `DataPatcher.apply` 对 1986 个 Data Assets 返回 0 failed、0 warnings，并加入 689 个 Content 对象。

这解决的是“能否静态转成 B480 接受的 DP”问题，不等于已经证明“完整玩法等价”。63 个 degraded 中仍包含自定义 Block/Bullet 行为、状态回调、Java 方法覆写、lambda/custom Effect、随机数近似和目标版本不接受字段等明确损失；Desktop 导入、贴图音效、地图保存重开及服务器地图加载尚未测试。

因此当前可行性判断为：

- **标准 Item/Liquid/Block/Unit/Weapon/Bullet/Factory/Turret 数据对象：高可行；**
- **使用内置 ClassMap 类型但初始化语法复杂的对象：已证明可通过持续扩充确定性规则覆盖；**
- **自定义 Java 类但可接受父类降级的内容：可注册、部分玩法保留；**
- **任意方法、回调、网络/UI、完全自定义绘制/逻辑：DP 无法等价表达；**
- **客户端资产、地图持久化和多人服务器：必须真实测试。**

## 为什么该 Mod 是高价值样本

该 Mod 自身声明 `minGameVersion: 159`，问题不是旧版本 API 本身，而是绝大多数玩法内容在 Java `loadContent()` 中创建，`assets/content` 没有可直接搬运的声明式对象。

Mindustry 没有“将已加载 Java Mod 自动导出为 DP”的官方逆转换接口：

- Java Mod 可执行任意 JVM 代码；
- DP 由 `ContentParser`/`DataPatcher` 创建或修改白名单内置对象；
- DP 设置 `allowClassResolution = false`，不能加载 Mod 自定义 FQCN；
- 因此转换器必须充当受限静态编译器，而不是调用游戏的现成导出功能。

Saturation 同时包含大量单位、炮塔、工厂、Consume、Ability、DrawPart、复杂 Bullet/Effect、自定义类和原版覆盖，足以检验工具是否具有实际用途。

## 历史基线

### assets-only 阶段

接入 Java AST 前的历史产物：

`work\e2e-saturation-firepower\sfire-mod-dp-v159.7.zip`

历史结果：

- 扫描 1764 个文件；
- 仅输出 1628 个静态资产，0 个 Java 定义 Content；
- CLI 因 `MOD_CODE_NOT_EXECUTED` 返回 2；
- 普通服务器只发现 1628 个文件；
- ZIP SHA-256：`AF3577A68CC4CE77C5F4191DB9F3F793E09978653A522714C5BEC89F8BDA86A8`。

该历史 ZIP 只能证明资产搬运，不是玩法转换。

### 首轮 Java AST 阶段

首轮 exporter 生成 354 个根 Content，92 converted、262 degraded；正式 apply 重测发现 34 个失败、78 个 warning。其意义是暴露构造器、builder、字段和循环缺口，不是可加载证明。

详见 `docs/JAVA_STATIC_SATURATION_BASELINE.md`。

## 实际内容盘点

主加载顺序见：

`<local-Saturation-Firepower-source>\src\SFire\SFireMod.java`

### 目标玩法 Content

| 类型 | 数量 | 根类情况 |
|---|---:|---|
| Item | 15 | 全部内置 `Item` |
| Liquid | 6 | 5 `Liquid`，1 `CellLiquid` |
| Status | 22 | 根类均为 `StatusEffect`，部分含回调/覆写 |
| Unit | 63 | 59 个字段单位 + 4 个内嵌 MissileUnitType |
| Block | 252 | 244 个内置根类 + 8 个自定义 Block 类 |
| **合计** | **358** | 本项目目标范围 |

不在产品目标范围：

- Planet 1；
- SectorPreset 1；
- 科技树；
- Mod 自带地图；
- GUI、网络和运行时脚本；
- 全局 `sprites-override`。

原 Mod 共注册约 360 个 `UnlockableContent`，本项目目标迁移其中直接服务地图玩法的 358 个。

### 重要嵌套对象

| 对象 | 数量 | 静态可表达性 |
|---|---:|---|
| Weapon | 116 | 全部为 v159 内置 Weapon 家族 |
| BulletType | 329 | 317 个内置类型；12 个使用 3 种自定义 Bullet 类 |
| Effect | 680 | 631 个内置声明式类型；49 个直接 lambda Effect |
| Attribute | 2 | 可由 v159 parser 注册 |

四个原本内嵌的 Missile Unit 已在最终转换中提升为独立 Unit HJSON：

- `knocker-missile`
- `blade-missile`
- `sundown-missile`
- `defense-platform-nuke-missile`

## 当前最终结果

产物目录：

`work\saturation-static-20260730-060256`

ZIP SHA-256：

`B085C6533CC43367CBC488DB201D0414F3B212DDDF6B5D8C081C6482755569BE`

| 指标 | 结果 |
|---|---:|
| 扫描源文件 | 1764 |
| 根 Content | 358 |
| 外部资产 | 1628 |
| Data Assets | 1986 |
| converted | 295 |
| degraded | 63 |
| Content excluded / unsupported / failed | 0 / 0 / 0 |
| report info / warning / error | 31 / 60 / 0 |

按类型：

| 类型 | converted | degraded | 合计 |
|---|---:|---:|---:|
| Item | 15 | 0 | 15 |
| Liquid | 6 | 0 | 6 |
| Status | 10 | 12 | 22 |
| Unit | 49 | 14 | 63 |
| Block | 215 | 37 | 252 |
| **合计** | **295** | **63** | **358** |

B480 正式 apply：

```text
assets=1986
content=358
external=1628
addedContent=689
failed=0
warnings=0
```

阶段：

- `STRUCTURE = PASSED`
- `RUNTIME = PASSED`
- `MAP_IMPORT = NOT_RUN`
- `SERVER_LOAD = NOT_RUN`

## Java 静态语义覆盖证据

最终样本不只是扫描声明，还实际覆盖了：

- requirements、Consume、ammo、ItemStack/LiquidStack；
- Weapon/Bullet/Ability/Effect/Draw/Part/Shoot；
- Unit entity、controller、defaultCommand；
- factory plans、reconstructor upgrades、payload；
- 匿名初始化器、方法局部常量、链式赋值和 Weapon copy；
- 7 个受限且确定性的 `for`/嵌套循环展开；
- 5 个对已生成 Content 的跨语句赋值；
- 4 个嵌套 Unit promotion；
- 自定义 Block/Bullet 的显式父类降级；
- 自定义/lambda Effect 的明确近似；
- DataPatcher 不接受字段的目标版本过滤；
- 加载期随机值的确定性中点近似；
- 固定目标 v159.7 原版对象快照。

`SFBlocks.tieliu` 使用：

```java
((LiquidTurret) Blocks.tsunami).ammoTypes.get(Liquids.slag)
```

作为 `fragBullet`。最终转换没有执行游戏，而是使用绑定 v159.7/B480 源码数据的固定 `tsunami + slag` Bullet 快照，并输出 `JAVA_VANILLA_OBJECT_SNAPSHOT_APPLIED`。当前 `JAVA_FIELD_EXPRESSION_OMITTED = 0`。

另一处加载期随机表达式被替换为确定性中点 5，并输出 `JAVA_RANDOM_EXPRESSION_APPROXIMATED`。这保证复现性，但不保留原随机分布。

## 高保真、降级与不可迁移边界

### 可高保真静态迁移

- 15 Item 和 6 Liquid 的普通字段；
- 大多数内置 Status 数值、Effect 引用和普通关系数据；
- 244 个内置根类 Block；
- 63 个内置根类 Unit（含提升后的 4 个导弹单位）；
- 内置 Weapon、BulletType、Ability、Draw、Part、ShootPattern、Consume、ItemStack、UnitPlan；
- requirements、ammo、plans、upgrades、attributes 和确定性集合操作；
- 可由固定目标源码快照安全恢复的少量原版嵌套对象。

“可高保真”仍需 Desktop 实际玩法验证；B480 parser 接受不能证明数值、视觉和手感完全正确。

### 必须降级但仍可注册

8 个自定义 Block：

| 内容 | 原类 | DP 基类 | 主要丢失行为 |
|---|---|---|---|
| `explosive-armor`、`explosive-armor-large` | `ExplodeWall` | `Wall` | 弹丸拦截、自毁范围伤害、自定义统计 |
| `gas-turbine` | `GasTurbineGenerator` | `ConsumeGenerator` | 特殊暖机、增幅和功率曲线 |
| `energe-drill`、`heavy-drill` | `PressureDrill` | `Drill` | 按电网余量动态加速 |
| `front-line-core` | `SFCore` | `CoreBlock` | 多核心数量、拆除/替换规则 |
| `guangyin`、`poxiao` | `EnhancedPowerTurret` | `PowerTurret` | 强化物品槽、强化弹丸/射击模式 |

12 个自定义 Bullet 实例：

- `PowerupBullet`：降级为 `BasicBulletType`，丢失穿透后增伤；
- `ShieldBreakBullet`：降级为 `BasicBulletType`，丢失按盾量伤害；
- `SizeDamageBullet`：降级为 `BasicBulletType`，丢失按目标体积增伤。

状态和方法：

- `acidded` 可保留普通持续/间隔伤害，但不能保留逐帧护甲腐蚀方法覆写；
- opposite/affinity 关系可保留展示/数据，但 DP 不能安装 Java `TransitionHandler`；
- 自定义 `setStats/update` 等方法不能转成 HJSON。

特效：

- 内置 Effect 对象有直接数据基础；
- lambda 或自定义 Effect factory 无等价类型时替换为 `Fx.none` 并报告；
- 视觉损失通常不阻止注册，但可能显著影响高级炮塔表现。

### 明确不可迁移

- 任意 Java 方法和回调；
- 自定义类的运行时逻辑；
- 反射、线程、文件/网络访问；
- GUI 和自定义客户端界面；
- Planet/Sector、科技树和 Mod 地图；
- `sprites-override` 的全局原版图集替换；
- 目标 v159.7 `ClassMap` 外且无安全父类降级的类型。

Agent 不能改变这些 DP 运行时硬边界；最多只能辅助人为编写映射。本项目不把 Agent 放入产品主干是合理的。

## 原版覆盖

`SFOverride.java` 修改原版 Block、Unit 和 Status。普通字段、requirements、ammo 表和内置对象替换可以在静态证据充分时生成 patch 或应用到输出；运行时回调和全局图集替换不能保留。

最终样本已证明 5 个跨 Content 确定性赋值可以应用，包括 `quartzSand`、`waveConveyor`、`rearmoredConveyor`、`silisteelConduit` 和 `reArmoredConduit`。这不等于 `SFOverride` 的所有原版修改均已完整恢复；报告仍是判断依据。

## 资产覆盖

输出外部资产 1628 个，主要包括：

- 1616 个 sprite；
- 10 个音频资产：文件名均以 `.ogg` 结尾，但文件头为 5 个 OGG、3 个 MP3 容器、2 个 WAV 容器；5 个不匹配项逐文件报告 `AUDIO_CONTAINER_EXTENSION_MISMATCH`；
- 2 个 bundle。

源树的一处普通 sprite basename 重复但字节相同，已确定性去重。19 个 `sprites-override` PNG 因全局替换语义不可表达而排除。maps 和其他工程/旧备份文件也按政策排除或标为文件级 unsupported。

资源搬运和 hash 已自动验证，但当前 Headless 测试不会：

- 解码 PNG；
- 创建 Desktop atlas；
- 验证所有 region/generated；
- 解码或播放 OGG；
- 验证 Effect/Draw 的客户端渲染。

所以不能声称“贴图音效已通过”。

## 可行度判断

以下是当前证据支持的边界，不是营销式兼容百分比：

| 维度 | 当前判断 |
|---|---|
| 根 Content 生成 | 358/358 已完成 |
| B480 HJSON 注册 | 358 根文件、0 apply failure、0 apply warning |
| 无已知降级 Content | 295/358 |
| 有明确降级但仍注册 | 63/358 |
| 普通外部资产搬运 | 1628 个已输出和 hash 记录 |
| Java 任意行为等价 | 不可承诺 |
| Desktop/地图/多人 | 尚未测试 |

从源码结构看，350/358 个根 Content 使用内置根类，约 97.8%；这解释了为何“不引入 Agent”的静态转换路线能覆盖大多数注册对象。但嵌套自定义 Bullet、状态回调、Effect 和高级炮塔集中承载重要玩法，因此不能把 97.8% 根类覆盖解释成 97.8% 行为等价。

面向用户的准确结论：

> 该工具已经能够把 Saturation Firepower 的全部目标物品、液体、状态、单位和方块生成成 B480 接受的 DP 根 Content，并保留大量原版数据对象；63 个内容有明确降级。是否达到“大部分玩法可用”，现在需要真实 Desktop 和地图服务器测试，而不是继续依赖 Headless 推断。

## 下一步验收

1. 用 v159.7/B480 Desktop 导入最终 ZIP，记录导入日志和资源数量。
2. 检查数据库中的 15 Item、6 Liquid、22 Status、63 Unit、252 Block。
3. 在编辑器放置代表性地形、运输、电力、工厂、普通炮塔和高级炮塔。
4. 生成代表性单位，实际移动、寻敌和开火。
5. 检查关键 Bullet、Effect、DrawPart、Ability、Consume、状态和 10 个音频资产，尤其验证扩展名为 `.ogg` 的 3 个 MP3/2 个 WAV 容器。
6. 重点检查 63 个 degraded Content、自定义 Bullet 使用者和 8 个缺 icon 警告。
7. 保存地图，完全退出客户端，重开并确认内容恢复。
8. 导出地图，让匹配 B480 服务器实际加载；至少连接一个 Desktop 客户端进行短时联机。

在第 1、7、8 项有证据前，必须保持：

- `MAP_IMPORT = NOT_RUN`
- `SERVER_LOAD = NOT_RUN`
- 状态 `PARTIAL`

不得声称客户端贴图/音效、地图保存重开或服务器地图加载已经通过。
