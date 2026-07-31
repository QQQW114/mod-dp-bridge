# Unit / Block 运行时对象图方案

最后更新：2026-08-01（根 fallback 字段采集已实现；嵌套对象图与 mapper 尚未实现）

当前 extractor 已完成本方案第 1～3 步的最小基础：从实际 v159.7 ContentParser 的内部 Arc Json
取得 fallback class 字段元数据，并只读取同一官方 CodeSource 的 Field。UnitType/Block 根的标量、
颜色、Content 引用、原版 Fx 和安全容器现可冻结；不支持的对象仍是 opaque。本文其余对象图、专用
adapter、生命周期选择和 mapper/回读比较仍是后续方案，不能视为已实现。

## 目标

把已经在官方 v159.7 JVM 中加载完成的 `UnitType`、`Block` 及其嵌套对象，转换成
`ContentParser` 可重新构造的惰性数据，而不是反编译 Mod 字节码或执行源码构建脚本。

该阶段的关键不是“读取更多反射字段”，而是建立一个与目标 `ContentParser` 完全对齐、
有界且可审计的反向编码器。

## 关键发现：直接复用目标 parser 的字段元数据

v159.7 `ContentParser.readFields()` 最终使用其内部 Arc `Json` 的：

```java
parser.getFields(targetClass)
```

`Json.getFields()` 返回每个可读字段的 `FieldMetadata`，其中包含：

- 实际 `Field`；
- 字段类型；
- 容器元素类型；
- map key 类型。

硬参考：

```text
Mindustry-master/core/src/mindustry/mod/ContentParser.java
  classParsers: 71+
  parser: 412+
  readFields: 1270+

MindustryX-main/Arc/arc-core/src/arc/util/serialization/Json.java
  getFields: 157+
```

因此 extractor 不需要手工维护 UnitType、数十种 Block、Weapon、Bullet、Ability 等全部字段名。
更可靠的做法是：

1. 为实际对象确定最近的 v159.7 `ClassMap` fallback；
2. 从目标 `ContentParser` 的 Arc `Json` 读取 **fallback class** 的字段元数据；
3. 只通过这些 target-owned `Field` 从实际 runtime object 取值；
4. 自定义子类新增字段不会进入可映射字段，只进入 `customFields` 损失报告；
5. mapper 再次根据同一版本的固定 schema/类型规则校验，不信任 snapshot 中的任意字段名。

这比 `runtimeClass.getFields()` 更安全，也比为每个 Block 类型人工抄字段更不易漂移。

## 为什么不能直接调用 Json 序列化整个对象

- `Json.writeValue(runtimeObject)` 会按实际 Mod 子类遍历，混入自定义字段；
- Content、TextureRegion、Sound、Prov、Cons、AI provider 等会形成无意义或不可构造对象；
- 对象图存在共享引用和潜在循环；
- 默认 serializer 可能输出 ContentParser 不接受的形式；
- 不能允许调用 Mod 自定义 serializer、getter 或 `toString()`；
- 全量对象容易泄漏运行时内部状态而不是声明数据。

所以只能读取 target parser 明确认可的字段，并使用自有反向编码规则。

## 对象图节点

建议 schema v3 增加稳定的节点表：

```json
{
  "root": {"kind": "objectRef", "id": 1},
  "objects": [
    {
      "id": 1,
      "runtimeClass": "newhorizon...",
      "fallback": {"parserName": "UnitType", "runtimeClass": "mindustry.type.UnitType"},
      "fields": {"health": {"kind": "number", "value": 1000}}
    }
  ]
}
```

同一 Java identity 第一次出现时分配递增 object id；再次出现时只输出 `objectRef`。当前递归栈中
再次遇到同一 identity 时记录 cycle，而不是继续遍历。

建议预算：

```text
每个根 Content 最多 32768 节点
最大深度 16
单容器最多 8192 项
字符串最大 1 MiB
单根序列化 JSON 最大 32 MiB
```

超预算必须成为该字段的 opaque/failed 结果，不得截断后伪装成完整映射。

## 反向编码类型

### 直接值

- null、boolean、byte/short/int/long、float/double；
- String、enum；
- `Color` 使用 RGBA hex；
- `Vec2/Vec3/Mat3D` 仅按 ContentParser 已支持形式；
- 原版静态值使用 owner + field + parserValue。

### Content 引用

所有 `Content`/`UnlockableContent` 引用必须编码：

```json
{"kind":"contentRef","contentType":"item","name":"new-horizon-hard-light"}
```

mapper 统一重写到 `dp-` namespace。引用边界外、未迁移或外部依赖 Mod 的 Content时，必须保留原
引用并给出 unresolved/external dependency 诊断。

### 资产引用

- Sound/Music：记录原始逻辑名、解析后的 JAR entry（若可确定）和 basename；
- TextureRegion：只记录可证明的 atlas region name，不递归纹理对象；
- Effect：优先映射 `Fx` 静态成员，其次 v159.7 ClassMap Effect 对象；自定义 lambda保持 opaque。

### 容器

- Java array、Arc `Seq`、`ObjectSet`；
- `ObjectMap`、`ObjectIntMap`、`ObjectFloatMap`；
- `ItemStack`、`LiquidStack`；
- map顺序必须规范化；有语义顺序的 Seq/array保持原顺序。

### 可构造嵌套对象

优先实现：

- `Weapon`；
- `BulletType` 及 v159.7 ClassMap 子类；
- `Ability`；
- `ShootPattern`；
- `DrawBlock`、`DrawPart`；
- `Consume`；
- parser可表达的 `Effect`；
- UnitFactory plan、upgrades、ammo map 等固定结构。

每个对象都沿实际继承链寻找最近的 `ClassMap` 类型。fallback 必须满足 ContentParser 对应
`make/resolve` 路径可构造；仅仅是父类并不代表可加载。

## 生命周期字段选择

默认声明字段从 `PRE_CONTENT_INIT` 读取，因为生成的 DP 对象还会执行目标 v159.7 自身的
`init()`。例如 gas Liquid 的 alpha、gasColor 和 boilPoint 会由基础 `Liquid.init()` 再次正确计算，
直接写 FINAL 值可能造成重复变换。

但必须保留三阶段差异，并按字段判断：

- 若变化来自相同 fallback 的目标基础 `init()`，使用 PRE；
- 若变化只来自自定义 override/callback，fallback 不会重放它，字段应标记 degraded，未来可在证明
  FINAL 值再次经过 fallback init 仍幂等时选择 FINAL；
- 无法证明时不能静默选择。

## Unit 最小里程碑

1. UnitType/MissileUnitType 基础标量、颜色、Content/asset引用；
2. `weapons` Seq；
3. Weapon 标量、Sound/Effect/region引用；
4. BulletType 对象与原版子类；
5. Ability 数组；
6. immunities、commands、engines、requirements 等容器；
7. DataPatcher apply 后对生成 Unit 重新做字段快照，与源 FINAL 快照分类比较。

New Horizon 的 40 个 Unit 均有自定义类方法或父类 override，因此即使基础对象图完整，也几乎都会
至少是 degraded；但绝大部分普通数值、武器和原版 Bullet/Ability仍有望恢复。

## Block 最小里程碑

按价值和风险分组，不应一次开启所有 281 项：

1. `Floor`、`OreBlock`、`StaticWall`、`Wall`、`Prop`、`TallBlock`；
2. `GenericCrafter`；
3. Storage/PowerNode/Battery/Generator/Solar；
4. Conveyor/Router/Junction/Bridge/Duct/Liquid运输；
5. Drill/Pump；
6. ItemTurret/PowerTurret/Laser/Continuous/PointDefense/TractorBeam；
7. Force/Overdrive/Core/Unit工厂；
8. 只能回退到基础 `Block` 的 95 项。

基础 `Block` fallback 只能恢复共有属性，不能伪装为原工厂/炮塔功能。若 custom build class 或自定义
Block方法决定核心玩法，该项必须 degraded/unsupported，即使 ContentParser 能注册一个同名方块。

## 特殊字段

`ContentParser` 对以下数据有专用语义，不能当普通字段盲写：

- `consumes`：通过 `readBlockConsumers()` 解析；
- `requirements`；
- turret `ammoTypes`；
- UnitFactory plans、Reconstructor upgrades；
- `research`（项目边界明确移除）；
- `type`：只能由 mapper根据批准 fallback 生成，snapshot不能提供；
- patch/add/remove 合并语法。

这些字段应由专用 adapter 从运行时对象重建。

## 验证

每新增一个根类型必须同时具备：

1. 最小 fixture：原版类、原版嵌套对象、Content引用和资产引用；
2. 自定义子类 fixture：验证 fallback、custom字段/方法损失；
3. 恶意/畸形 snapshot：验证任意字段/type不能注入；
4. mapper → converter →结构检查；
5. 官方 v159.7 `DataPatcher.apply`；
6. 源 runtime FINAL 与生成 runtime FINAL 的字段差异报告；
7. New Horizon 分批实测，禁止一次开启全部类型后只看“parser 通过”。

DataPatcher apply 通过只证明可注册，不证明炮塔开火、工厂生产、单位 AI、atlas、音频或地图保存重开。
