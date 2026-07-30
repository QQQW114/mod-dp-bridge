# Java → Mindustry v159.7 Data Assets 映射规范

最后更新：2026-07-30 06:05（Asia/Shanghai）

本文件记录 `bridge-java-static` 将 Java Mod 静态导出为 Mindustry v159.7/B480 Data Assets（以下简称 DP）时必须遵守的映射、降级和报告边界。它既是实现清单，也是压缩上下文后继续开发的恢复文档。

机器可读规则：

- `config/java-to-dp-v1597.hjson`
- `config/mindustry-v1597-classmap.json`

源码硬参考：

- `Mindustry-v159.7-source/core\src\mindustry\mod\ContentParser.java`
- `Mindustry-v159.7-source/core\src\mindustry\mod\DataPatcher.java`
- `Mindustry-v159.7-source/core\src\mindustry\mod\ClassMap.java`
- `Mindustry-v159.7-source/core\src\mindustry\mod\data\ContentAsset.java`

已固定的源码 SHA-256：

| 文件 | SHA-256 |
|---|---|
| `ClassMap.java` | `50B6DE2DBBAF69B2A6B4C5F036C2DD74A0AEBC30D444B5C6994EA8A1990B4C72` |
| `ContentParser.java` | `99B983B14F3C8737CCE3C3A9C8686D73DB4A02EFC92F24194307FB26A27A82F8` |
| `DataPatcher.java` | `4D2EBA8B0AD4B2BE826138A02769E6CCD7384B2DB379E6B73309782C65B8F973` |

## 1. 不可突破的运行时边界

### 1.1 DP 可新增的顶层 Content

v159.7 `ContentAsset.loadableContent` 只允许：

- Item
- Block
- Liquid
- StatusEffect
- UnitType
- Weather

Planet、SectorPreset、科技树、地图生成、GUI、网络协议和 Mod 自带地图不进入转换目标。它们必须以 `excluded` 或 `unsupported` 明确出现在报告中，不能静默忽略。

### 1.2 类型必须来自有效 ClassMap

`DataPatcher` 会设置：

- `allowClassResolution = false`
- `allowAssetLoading = false`
- `allowPatching = false`

因此输出中不得使用 FQCN，也不得尝试解析输入 Mod 的自定义 Java 类。所有 `type`、`template`、Weapon、Bullet、Effect、Ability、Draw、Consume、AI 等多态类型都必须使用 v159.7 `ClassMap` 的有效短名，并且还必须满足目标字段的可赋值类型。

`config/mindustry-v1597-classmap.json` 包含：

- 497 次源码 `classes.put`；
- 496 个最终有效 key；
- 唯一重复 key 为 `DuctBridgeBuild`，最终值是 `DuctBridge.DuctBridgeBuild`。

“短名存在于 ClassMap”只是必要条件，不代表该类适合作为顶层 Content，也不代表它具有当前解析上下文所需的无参构造器。转换器必须同时做：

1. ClassMap 白名单检查；
2. 根 Content 类型检查；
3. 目标字段可赋值类别检查；
4. 已知构造器映射检查。

### 1.3 不能在普通字段内创建新的 UnlockableContent

`DataPatcher` 遇到应为 `UnlockableContent` 的字段对象时会警告 `New content must not be instantiated` 并拒绝该值。因此 Java 中内嵌的 `new MissileUnitType(...)`、`new UnitType(...)` 等必须提升为独立 `content/units/*.hjson`，原字段只保留对该独立 Content 的字符串引用。

Saturation Firepower 中已由当前 exporter 提升并通过 B480 apply 的对象：

- `knocker-missile`
- `blade-missile`
- `sundown-missile`
- `defense-platform-nuke-missile`

提升过程使用嵌套 UnitType 的确定性名称、构造器和 initializer 字段生成新的 unit HJSON，原 Bullet/Weapon 对象图中只保留 `dp-*` Content 引用。同名冲突、递归提升或名称不可静态确定时必须停止并报告，不得内嵌一个 DataPatcher 必然拒绝的新 Content 对象。

### 1.4 null 字段会使内容加载失败

`ContentParser` 在反序列化后调用 `checkNullFields`。因此不能输出“看起来结构正确、但关键构造器参数从未写入字段”的对象。高风险对象包括：

- `ItemStack.item`
- `LiquidStack.liquid`
- `StatusFieldAbility.effect`
- `UnitPlan.unit/time/requirements`
- `AssemblerUnitPlan.unit/time/requirements`
- `LiquidBulletType.liquid`

构造器无法完整映射时，应删除整个嵌套对象并报告，或让所属 Content 进入 `failed/unsupported`；不得输出必然含 null 的半成品。

## 2. 命名空间规则

静态导出器生成的 `StaticGeneratedFile.namespace` 应为 `SOURCE`，而不是 `TARGET`。

原因：`ContentParser` 在读取 Weapon 后会自行执行：

```java
weapon.name = currentMod.name + "-" + weapon.name;
```

本项目的 DP Mod 名为 `dp`。如果导出器预先把 Weapon 名称写成 `dp-*`，运行时可能得到 `dp-dp-*`，导致 weapon region 与贴图引用错配。

正确管线：

1. Java AST 导出器保留源 Mod 的局部命名语义；
2. `ModNamespaceRewriter` 把 Content 和资产引用重写为 `dp-*`；
3. 对 Weapon 等嵌套 member name，只剥离一次源 Mod 前缀，不提前添加 `dp-`；
4. 由 `ContentParser` 在运行时添加 `dp-`。

根 Content 的文件 basename 保持局部名称，如 `content/blocks/foo.hjson`；地图内旧的 `sourceMod-foo` 仍需要单独的 `sourceMod-foo -> dp-foo` 迁移表。

## 3. 根 Content 构造器映射

| Java 根对象 | 输出目录 | DP 选择器 | 构造器参数 |
|---|---|---|---|
| `new Item(name[, color])` | `content/items` | 无需 `type` | `arg0 -> filename/name`，`arg1 -> color` |
| `new Liquid(name[, color])` | `content/liquids` | 无需 `type` | 同上 |
| `new CellLiquid(name[, color])` | `content/liquids` | `type: CellLiquid` | 同上 |
| `new StatusEffect(name)` | `content/statuses` | 无需 `type` | `arg0 -> filename/name` |
| `new UnitType(name)` 及内置子类 | `content/units` | `template` | `arg0 -> filename/name` |
| `new BlockSubclass(name)` | `content/blocks` | `type` | `arg0 -> filename/name` |
| `new Floor(name[, variants])` | `content/blocks` | `type: Floor` | `arg1 -> variants` |
| `new OreBlock(ore)` / 相关重载 | `content/blocks` | `type: OreBlock` | 必须恢复 ore/name 构造语义，不能只写空对象 |

Unit 根对象使用 `template` 选择 UnitType Java 类；`type` 字段另用于 unit entity constructor。未知 template 不能静默退回普通 UnitType，应提前标记为降级或失败。

自定义根类绝不能原样输出。只有命中显式降级表时才可替换为内置基类；否则该 Content 为 `unsupported`。

## 4. 普通构造器和对象图映射

完整参数顺序见 `config/java-to-dp-v1597.hjson` 的 `constructorMappings`。实现时优先覆盖下列族：

### 4.1 Stack 和计划

- `ItemStack(item, amount)` → `{item, amount}`
- `LiquidStack(liquid, amount)` → `{liquid, amount}`
- `PayloadStack(content, amount)` → `{item/content, amount}` 或目标解析器接受的字符串形式
- `UnitPlan(unit, time, requirements)` → `{unit, time, requirements}`
- `AssemblerUnitPlan(unit, time, requirements)` → `{unit, time, requirements}`

这些是普通结构体，不应无条件添加多态 `type`。

### 4.2 Weapon

- `Weapon()`
- `Weapon(name)`：`arg0 -> name`
- `PointDefenseWeapon([name])`
- `RepairBeamWeapon([name])`

Weapon copy helper：

- `copy()`
- `copyRotate(...)`
- `copyRotRel(...)`

无法追溯被复制对象时必须报告，不得悄悄创建默认名为 `weapon` 的新对象。

### 4.3 BulletType

必须按具体构造器恢复参数，至少包括：

- `BulletType`
- `BasicBulletType`
- `ArtilleryBulletType`
- `MissileBulletType`
- `FlakBulletType`
- `FireBulletType`
- `LiquidBulletType`
- `LaserBulletType`
- `ExplosionBulletType`
- `ContinuousFlameBulletType`
- `ContinuousLaserBulletType`

例如速度、伤害、sprite、liquid 等经常来自构造器而不是 initializer 字段；仅输出 `type` 会产生严重行为偏差，LiquidBullet 还可能因 liquid 为空而失败。

### 4.4 Effect、Draw、Part、Shoot、Ability、Consume

机器规则已覆盖常见：

- `ParticleEffect`、`WaveEffect`、`ExplosionEffect`、`MultiEffect`、`WrapEffect`、`RadialEffect`
- `RegionPart`、`PartMove`
- 常见 `ShootPattern`
- 常见 `DrawBlock`
- 常见 Ability，包括具有必填构造参数的 `StatusFieldAbility`
- 常见 Consume
- `Color`、`Rect`、`Vec2`

`Color.valueOf(...)` 和四参数 `new Color(r,g,b,a)` 应在转换期计算为 6/8 位 RGBA 字符串。不要输出数值颜色或 `{r,g,b,a}` 对象。

## 5. Builder 调用映射

### 5.1 方块需求与消耗

| Java builder | DP 目标 |
|---|---|
| `requirements(category, visibility, ItemStack...)` | `category`、`buildVisibility`、`requirements` |
| `consumePower(x)` | `consumePower`/等价 ConsumePower 对象 |
| `consumePowerBuffered(x)` | 缓冲电力 Consume |
| `consumeItem(item[, amount])` | item consume |
| `consumeItems(ItemStack...)` | item consume 数组 |
| `consumeLiquid(liquid, amount)` | liquid consume |
| `consumeLiquids(LiquidStack...)` | liquid consume 数组 |
| `consumeCoolant(...)` | coolant consume |
| `consume(Consume)` | `consumes` 集合 |
| `.boost()` / `.optional()` / `.update()` | 修改前一个 Consume 的对应标志 |

链式 modifier 必须绑定到刚刚创建的 Consume 对象，而不是误写成 Block 根字段。

### 5.2 map/set/list builder

- `ammo(k1, v1, k2, v2...)` → HJSON object map
- `ObjectMap.put/putAll` → HJSON object map
- `ObjectMap.of/OrderedMap.of` → HJSON object map
- `Attributes.set(attribute, value)` → attributes object
- `Seq.add/addAll`、`ObjectSet.add/addAll` → 扁平数组/集合
- `Seq.with`、`ObjectSet.with` → 数组/集合

`addAll(array)` 必须展开一层，不能生成 `[[a,b]]`。`ammo` 和 ObjectMap 不能退化成交替元素数组，否则目标字段类型错误。

### 5.3 状态关系

- `opposite(a, b...)`：可写入 `opposites` 展示集合；Java 的持续时间抵消处理不能由 DP 安装，必须标记 `degraded`。
- `affinity(other, handler)`：可写入 `affinities` 展示集合；`TransitionHandler` lambda 必须丢弃并明确报告。

不要声称自定义 affinity 已“转成 generic transitionDamage”，除非转换器确实从源码安全推导并显式设置了等价 `transitionDamage`。仅保留集合并不会自动恢复 Java 回调行为。

## 6. 静态工厂、常量和安全求值

可支持：

- `ItemStack.with(...)`、`ItemStack.mult(...)`
- `LiquidStack.with(...)`
- `Seq.with(...)`、`ObjectSet.with(...)`
- `ObjectMap.of(...)`、`OrderedMap.of(...)`
- `Color.valueOf(...)`
- `Fx.*`、`Sounds.*`、`Interp.*`、`Blending.*`、`CacheLayer.*`
- `BuildVisibility.*`、`Category.*`、`Attribute.*`
- 已知音效加载 helper，仅提取确定的资源名，不执行加载
- `PartProgress` 常量与已知链式变换

静态表达式允许范围：

- 字面量、数组、对象 initializer；
- `+ - * / %` 等可确定数值运算；
- 已知常量引用；
- 可界定次数的经典常量 `for(init; condition; update)` 循环；
- 只返回安全表达式的简单 helper；
- 可证明无副作用的 copy/factory。

受限 `for` 循环的安全契约：

- 初始值、条件和 update 必须能在当前局部数值环境中确定；
- 循环体只能包含已支持的赋值、局部声明、builder 调用、空语句或嵌套的受限 `for`；
- 单个循环最多展开 64 次；
- 每个生成 Content 声明在根 initializer 与所有嵌套对象间共享 4096 次总预算；
- 最大嵌套深度为 8。

超过任一上限或出现未允许控制流时，必须记录诊断并放弃该展开，不得执行循环代码。

禁止：

- 编译、类加载、反射、执行输入字节码；
- 运行 Gradle/Maven 或 Mod 初始化逻辑；
- I/O、网络、线程、系统属性和不受限循环；
- 运行时世界/实体状态依赖；
- 无法证明纯函数性的任意 helper。

无法求值必须形成带源码位置和表达式文本的诊断。

### 6.1 固定目标原版对象 snapshot

某些 Java Mod 会直接取出原版 Content 内部已构造的嵌套对象，而 DataPatcher 既不能按 Java 对象身份引用它，也不会执行原表达式。这类情况只能使用**精确目标版本 snapshot**：

1. 规则必须针对归一化后的完整 Java 表达式精确匹配，不做模糊推断；
2. 字段值必须来自项目锁定的 v159.7/B480 Mindustry 源码，不来自执行输入 Mod；
3. snapshot 必须显式包含目标解析器无参构造时不会自动恢复的构造器副作用字段；
4. 每条规则都产生 `JAVA_VANILLA_OBJECT_SNAPSHOT_APPLIED` 诊断，并属于 v159.7 目标适配数据；更换目标版本时必须重新核对。

当前已有规则仅精确匹配：

```java
((LiquidTurret)Blocks.tsunami).ammoTypes.get(Liquids.slag)
```

并生成与 v159.7/B480 原版 tsunami 熔渣弹一致的 `LiquidBulletType` 数据 snapshot，包括 `liquid/lifetime/speed/damage/status/hitColor/lightColor` 等字段。未命中明确 snapshot 的任意原版对象查询仍是不可求值表达式，不得假定为同类对象。

## 7. Unit entity 与 AI 映射

基础 entity constructor：

| Java entity | DP `type` |
|---|---|
| `UnitEntity` | `flying` |
| `MechUnit` | `mech` |
| `LegsUnit` | `legs` |
| `UnitWaterMove` | `naval` |
| `PayloadUnit` | `payload` |
| `TimedKillUnit` | `missile` |
| `TankUnit` | `tank` |
| `ElevationMoveUnit` | `hover` |
| `BuildingTetherPayloadUnit` | `tether` |
| `CrawlUnit` | `crawl` |

Saturation 引用的 vanilla UnitTypes 已核对：

| Vanilla unit | entity type |
|---|---|
| flare、zenith、eclipse、gamma | `flying` |
| elude | `hover` |
| corvus、atrax | `legs` |
| reign、dagger | `mech` |
| omura、risso | `naval` |
| oct、quad、mega | `payload` |
| stell | `tank` |
| assembly-drone | `tether` |
| `EntityMapping.map(3)` | `flying` |

Controller 只允许转换到 v159.7 ClassMap 中可构造的 AI 短名。任意自定义 AI supplier/lambda 应删除并报告。

## 8. 自定义类降级表

Saturation Firepower 当前有 8 个需要显式降级的自定义类型：

| 源类 | DP 基类 | 可保留 | 明确丢失 |
|---|---|---|---|
| `EnhancedPowerTurret` | `PowerTurret` | 标准炮塔字段、标准 consume、普通 bullet/weapon 数据 | enhancer 物品槽、强化弹丸、强化射击模式、强化次数逻辑 |
| `ExplodeWall` | `Wall` | 标准墙字段；标准 `destroyEffect` 可保留 | 低血量拦截弹丸、自毁范围伤害、自定义统计/波形参数 |
| `GasTurbineGenerator` | `ConsumeGenerator` | 标准 generator 字段，**包括基类已有的 `warmupSpeed`** | `extraPower`、`powerUpSpeed` 及自定义功率曲线 |
| `PressureDrill` | `Drill` | 标准 drill 字段 | 根据电网功率余额动态加速 |
| `SFCore` | `CoreBlock` | 标准 core 字段 | 多核心数量限制、特殊拆除/替换规则 |
| `PowerupBullet` | `BasicBulletType` | 标准 BasicBullet 字段 | 每次穿透后增伤 |
| `ShieldBreakBullet` | `BasicBulletType` | 标准 BasicBullet 字段 | 按当前盾量百分比伤害、专用破盾效果逻辑 |
| `SizeDamageBullet` | `BasicBulletType` | 标准 BasicBullet 字段 | 按目标体积增伤 |

所有自定义类降级均是高严重度 `degraded`，即使产物可加载也不能计为高保真 converted。

## 9. Effect 降级

标准 Effect 类应正常导出。`new Effect(lambda)`、匿名 Effect 覆写和自定义绘制 factory 不可安全静态复刻：

1. 若能证明只对应一个标准 Effect 数据对象，可生成该标准对象并标记近似；
2. 否则输出 `none`/`Fx.none`；
3. 报告源位置、原表达式和所属字段；
4. 如果 callback 还承担伤害、状态、生成实体等非视觉行为，严重度提升为 error。

不能把任意 `Effect(lambda)` 伪装成空 `ParticleEffect`，因为这会掩盖行为丢失并可能产生无意义配置。

## 10. Saturation Firepower 对实现优先级的约束

对 25 个 Java 文件使用 JavaParser `BLEEDING_EDGE` 扫描，无解析错误。顶层 inventory：

| Content | 数量 |
|---|---:|
| Item | 15 |
| Liquid | 6 |
| StatusEffect | 22 |
| Unit | 59 |
| Block | 252 |
| **顶层合计** | **354** |

另有 4 个内嵌 MissileUnitType，提升后目标玩法 Content 合计 358。

高频嵌套构造器说明仅支持 Item/Liquid/Status 不足以验证实用性：

| 构造器 | 次数 |
|---|---:|
| `ParticleEffect` | 302 |
| `BasicBulletType` | 115 |
| `Weapon` | 105 |
| `WaveEffect` | 103 |
| `MultiEffect` | 102 |
| `RegionPart` | 89 |
| `ExplosionEffect` | 85 |
| `DrawMulti` | 55 |
| `UnitType` | 54 |
| `GenericCrafter` | 37 |
| `ItemStack` | 36 |
| `ArtilleryBulletType` | 26 |
| `MissileBulletType` | 24 |
| `PartMove` / `ShootBarrel` / `StatusEffect` | 各 22 |
| `UnitPlan` / `LiquidBulletType` / `ItemTurret` / `FlakBulletType` | 各 20 |
| `UnitEngine` | 18 |
| `PowerTurret` | 17 |
| `StatusFieldAbility` | 16 |

上述优先族目已进入当前主干，包括 Block/Unit 根导出、requirements/consume、stack/plan/map、Weapon/Bullet/Ability、Draw/Part/Shoot、copy helper、受限循环、内嵌 unit 提升、标准 Effect 与 lambda Effect 诊断。后续优先级不再是“能否生成根文件”，而是扩大精确构造器/字段覆盖、减少有证据的降级项，并完成客户端和地图玩法验证。

## 11. 当前 exporter 实现状态与风险边界

`JavaAstStaticExporter.export()` 当前已确定性实现：

1. 发出 Item、Liquid/CellLiquid、StatusEffect、Unit 和 Block 根 HJSON，并区分 `type`/`template`；
2. 映射常见 Weapon、Bullet、Ability、Effect、Draw、Part、Shoot、Consume、Stack 和 Plan 构造器；
3. 处理 requirements、consume modifier、ammo/ObjectMap、Seq/ObjectSet、factory plans 和 reconstructor upgrades；
4. 求值字面量、安全算术、常量、颜色、内容引用、方法局部常量、匿名 initializer 局部变量和链式赋值；
5. 支持 Weapon `copy/copyRotate/copyRotRel`、`PartProgress` 常量与已知变换、常见原版常量与 Unit entity/controller/defaultCommand 映射；
6. 展开受限经典 `for`，并在同一声明的根/嵌套 initializer 中共享循环预算；
7. 对已生成 Content 执行可确定的 cross-content assignment，并用 `JAVA_CROSS_CONTENT_ASSIGNMENT_APPLIED` 报告；
8. 把内嵌 `UnitType`/`MissileUnitType` 提升为独立 Content，Saturation 的 4 个 missile unit 已全部生成；
9. 对不在 v159.7 目标字段集内的输出字段进行删除并报告，避免将已知 unknown-field 交给 DataPatcher；
10. 对少量无法按 Content 身份引用的原版嵌套对象使用精确 v159.7/B480 snapshot，当前包括 tsunami 熔渣 `LiquidBulletType`。

不可突破的保真度边界仍然是：

- 不编译、加载、反射或执行输入 Java，也不引入 Agent 推测代码语义；
- 自定义 Block/Bullet 类只能按显式降级表保留原版基类数据，自定义字段和方法逻辑仍丢失；
- lambda Effect、Status affinity/transition handler、AI supplier、方法覆写与任意绘制/世界回调仍只能替换、删除或降级；
- 未命中已知构造器、字段、helper 或精确 snapshot 的表达式仍要报告，不得用任意默认对象掩盖；
- DataPatcher 0 failed/0 warning 只证明 HJSON 解析、引用、注册和 init/postInit 通过，不证明降级 Java 行为、客户端图集/音频或地图玩法正确。

### 11.1 Saturation 最终 headless baseline

`work/saturation-static-20260730-060256/report.json` 的最终证据：

| 指标 | 结果 |
|---|---:|
| Java 源文件 | 25 |
| 顶层 Content 声明 | 354 |
| 提升嵌套 Unit | 4 |
| 生成 Content | 358 |
| 外部资产 | 1628 |
| DataPatcher 总资产 | 1986 |
| `converted` / `degraded` | 295 / 63 |
| `unsupported` / `failed` | 0 / 0 |
| DataPatcher `failed` / `warnings` | 0 / 0 |
| DataPatcher `addedContent` | 689 |
| 转换报告 warning | 60 |
| `AUDIO_CONTAINER_EXTENSION_MISMATCH` | 5（3 个 MP3、2 个 WAV） |

5 个音频文件均使用 `.ogg` 扩展名，但 magic bytes 显示其中 3 个实际是 MP3 容器、2 个实际是 WAV 容器。转换器保留字节不变且不自动转码；这些 warning 不是 DataPatcher warning，因为 headless apply 不解码客户端音频。必须在精确 v159.7 Desktop 版本测试，失败时再人工转码并保留 basename。

DP ZIP SHA-256：`B085C6533CC43367CBC488DB201D0414F3B212DDDF6B5D8C081C6482755569BE`。STRUCTURE 和 RUNTIME 为 PASSED；MAP_IMPORT 与 SERVER_LOAD 为 NOT_RUN。因此该 baseline 是“可交付客户端测试”证据，不是“完整还原”证据。

## 12. 报告契约

每个无法完整转换的表达式/字段/对象都至少记录：

- 稳定诊断 code；
- severity；
- source path、line、column；
- source symbol / 所属 Content；
- Java 表达式或字段名；
- 采取的处理：保留、近似、替换、删除、提升、失败；
- 目标类型/输出路径；
- 人工补充建议。

Content 级结果：

- `converted`：静态已覆盖且没有已知行为丢失；
- `degraded`：可加载，但存在已知近似或行为丢失；
- `unsupported`：未生成该 Content；
- `failed`：尝试生成但结构无效或服务器验证失败；
- `excluded`：明确超出产品范围。

若一个根 Content 中删除了 Weapon、Bullet、Ability、Consume、Effect 等直接玩法对象，该根 Content 不能仍报告为完整 `converted`。普通 B480 server 冷启动中的 `Loaded N data asset files.` 甚至只是文件发现，不代表 DataPatcher apply；即使新的 apply harness 完整通过，也只证明 parser/注册/init 无错，不代表行为正确。最终仍需要 Desktop 地图导入、放置、开火/生产、保存、重开和多人服务器加载验证。

## 13. 验收建议

每轮 exporter 扩展后，对 Saturation 至少记录：

1. inventory 总数与生成 Content 数；
2. 每类 converted/degraded/unsupported/failed 数；
3. 丢失的 builder、构造器和字段 Top N；
4. B480 DataPatcher apply 的 assets/content/added/failed/warnings 及原始日志，并把 server 冷启动文件发现与 apply 结果分开；
5. 生成 ZIP 的 SHA-256 和确定性复跑结果；
6. Desktop 导入资源数；
7. 地图编辑器中 Item/Block/Unit 是否出现；
8. 代表性炮塔、工厂、单位、状态、弹药、音效、贴图是否工作；
9. 保存后退出重开是否仍可加载；
10. 所有未转换项是否可从 `report.json` 和 `report.md` 找到。

服务器/客户端没有明确通过前，不得把 Saturation 标记为“完整可转换”。

当前 2026-07-30 baseline 已完成上述 1–5 的 headless 部分：358 Content / 1986 总资产、295 converted / 63 degraded、DataPatcher 0 failed / 0 warning，且最终 ZIP hash 已记录。第 6–9 项中针对 Saturation 的 Desktop 地图导入、音画、实际玩法、保存重开与服务器地图加载仍未验证。
