# Runtime snapshot schema v2

最后更新：2026-08-01（Asia/Shanghai）

## 状态

bridge-runtime-extractor 已从“只列出注册 Content”升级为三生命周期、立即冻结的 typed runtime snapshot。

2026-08-01 的下一增量已为 `UnitType` 与 `Block` 根对象加入 **目标 parser 字段采集**。这只表示
extractor 能安全冻结 fallback 可见的根字段；runtime mapper 尚未据此生成单位、炮塔或工厂，嵌套
Weapon/Bullet/Ability/Draw/Consume 等对象图也仍未实现。

动态模式仍会执行输入 Mod 的 Java 字节码，只适合可信本地输入。独立 JVM、临时目录和超时是故障隔离，不是恶意代码沙箱。

## 三个快照阶段

Extractor 保留三个全局阶段，但允许 Mod 在后续生命周期合法晚注册 Content：

1. PRE_CONTENT_INIT：`createModContent()` 已完成、`ContentLoader.init()` 尚未调用；
2. POST_CONTENT_INIT：`Content.init()` / `postInit()` 已完成，但 `Mod.init()` 尚未全部完成；
3. FINAL_AFTER_MOD_INIT：所有 `Mod.init()` 已完成；在 `ServerLoadEvent` 分发完成后，worker
   连续两次通过 `Core.app.post` 延后，先让其他 event handler 和它们已投递的应用队列任务
   获得执行机会，然后才冻结最终快照。

快照键集合契约是：

```text
PRE_CONTENT_INIT ⊆ POST_CONTENT_INIT ⊆ FINAL_AFTER_MOD_INIT
```

已出现的 Content 不得在后续阶段消失，但可以在 POST 或 FINAL 首次出现。registration trace
必须与 FINAL 快照的 key 集合完全相同，FINAL 快照又必须与 worker 最后读取的实际
`Vars.content` 目标 Mod 注册集完全相同。每个阶段的计数只要与该阶段自身记录数一致；
不再要求 PRE/POST 数量等于最终 Content 数。

Mapper 为每个 Content 选择它**最早可用**的阶段作为权威声明来源：优先 PRE，晚注册项
则使用 POST 或 FINAL，并产生生命周期降级诊断。该 Content 出现之后的阶段必须连续存在，
且其字段键集合必须一致；后续阶段值只用于识别原版生命周期派生值和 Mod 改写，
不能无字段级规则地覆盖最早声明。

键集合倒退、FINAL/最终 registry 不一致、身份元数据冲突、出现后阶段不连续或字段集
变化，都会使 extractor/mapper fail closed，残留文件保留供诊断。

## 顶层结构

整体 schemaVersion 为 2。每项 Content 包含：

~~~json
{
  "name": "example-mod-example-item",
  "contentType": "item",
  "runtimeClass": "example.Content$1",
  "registrationStack": [],
  "runtimeSnapshots": {
    "preContentInit": {},
    "postContentInit": {},
    "finalAfterModInit": {}
  }
}
~~~

对晚注册 Content，它首次出现之前的 phase 值为 JSON `null`；`finalAfterModInit`
必须始终存在且为有效对象。

每个阶段快照包含：

- classAncestry：实际运行时继承链；
- sourceClassMapFallback：源运行时 ClassMap 中最近、可构造的映射；
- fields：当前第一阶段白名单字段；
- customFields：目标原版 fallback 以下的自定义实例字段；
- overriddenMethods：会被 fallback 丢失的覆写方法；
- customOnlyMethods：目标父类中没有对应签名的自定义方法；
- declaredLosses：已确认包含 callback/运行时行为、不能直接数据化的状态。

sourceClassMapFallback 不能当成最终 v159.7 类型结论。未来支持 v146～158 源运行时时，target mapper 必须在独立 v159.7 schema/JVM 中再次计算 targetClassMapFallback。

## RuntimeValue

每个字段值均保存 declaredType，并使用显式 kind：

- null
- boolean
- number：数值以字符串保存，并附 numberType，避免 JSON 精度或 NaN 表达问题；
- string
- enum
- color：小写 8 位 RGBA；
- contentRef：contentType、完整运行时 name、ownerMod；
- staticRef：当前覆盖可信 mindustry.content.Fx identity；
- array：数组和可信 arc.struct collection；
- opaque：明确保存不能安全遍历或不能数据化的原因。

安全约束：

- 只使用 Field.get() 读取白名单字段；
- UnitType/Block 只使用实际 v159.7 `ContentParser` 内部 Arc `Json.getFields(fallbackClass)` 返回的
  `FieldMetadata.field`，绝不使用 Mod runtime class 的字段查找；
- UnitType/Block fallback 与 Field declaring class 必须和官方根类具有相同 ClassLoader 及 CodeSource，
  Mod 把自定义子类塞进全局 ClassMap 也不会使其成为可信 fallback；
- 不调用 Mod getter、toString()、equals() 或 Arc Json.toJson()；
- 不遍历 Mod 自定义 Iterable；
- 自定义 Number 子类不会调用其数值转换或 toString，而会写为 UNSUPPORTED_NUMBER_SUBCLASS；
- 自定义/组合 Effect 当前写为 CUSTOM_OR_COMPOSED_EFFECT；
- 设有深度、节点数和集合长度预算。

## 第一阶段字段范围

已按固定、已验证字段表完整冻结以下根 Content 的目标字段：

- Item
- Liquid，并为源 fallback CellLiquid 预留额外字段；
- StatusEffect

UnitType/Block 当前采用另一层级：

- 依据最近的官方 v159.7 ClassMap fallback（如 `MissileUnitType`、`ItemTurret`、
  `GenericCrafter`）读取该 fallback 的目标 parser 字段元数据；
- 直接值、颜色、Content 引用、原版 Fx、数组和可信 Arc collection 沿用现有 RuntimeValue 编码；
- 尚未支持的目标字段对象明确为 opaque，而不是递归调用 serializer；
- 每个 Unit/Block 根共享 8192 节点预算，字段按名称排序；
- 排除由根 parser 预处理的伪字段：通用 `name/description/research/type`，Unit 的
  `template/requirements/controller/defaultController/waves`，Block 的 `consumes`；
- `requirements` 对 Block 仍是普通、可配置字段，因此不会被误删。

这一步没有建立可构造嵌套对象图，也没有改变 mapper 的支持范围。其他 Content 仍只输出三阶段
ancestry、源 ClassMap fallback、自定义字段和方法覆写。

Status 特殊处理：

- opposites / affinities 保存为 Content 引用集合；
- 运行时 transition callback 不直接序列化；
- 非空 transition map 标记为 STATUS_TRANSITION_HANDLERS_REQUIRE_REBUILD；
- 自定义 initblock 标记为 CUSTOM_STATUS_INIT_CALLBACK_NOT_SERIALIZED；
- target mapper 可用 v159.7 ContentParser 的标准 opposite/affinity 规则重建原版语义，自定义 handler 仍需报告。

## 2026-08-01 验证

### 自编 fixture

输入：

~~~text
work/runtime-fixture-mod/runtime-fixture.jar
~~~

结果：

- 3/3 Content；
- 三阶段均为 3；
- Item 数值/颜色、Liquid Status/Fx 引用、Status opposite/Fx 均形成 typed value；
- gas=true Liquid 的 pre→post 颜色变化被正确识别；
- 输出 JSON 可由标准 JSON parser 读取。

产物：

~~~text
work/runtime-fixture-snapshot-v2-final.json
work/runtime-fixture-extractor-v2-final/run-20260731-205459-3540/headless.log
~~~

### New Horizon 2.2.1

官方 v159.7 Server JAR + 发布 JAR：

- 382/382 Content；
- pre/post/final、registration trace 均为 382，键集合一致；
- 18 Item、17 Liquid、21 Status 无目标白名单字段缺失；
- 12 个 Status 自定义/组合 Effect 明确输出 opaque；
- 7 个 gas Liquid 出现 pre→post 差异；
- 143 个 Block、40 个 Unit、20 个 Status、7 个 Liquid 检测到自定义覆写方法；
- 输出约 2.43 MB。

产物：

~~~text
work/runtime-new-horizon-snapshot-v2-final.json
work/runtime-new-horizon-extractor-v2-final/run-20260731-205523-23028/headless.log
~~~

### UnitType/Block parser 字段采集增量

使用同一 New Horizon 发布 JAR 与官方 v159.7 Server JAR重新提取：

- 382/382 Content，三阶段完整；
- 40 个 Unit，每阶段各 269 个有序 fallback 字段；
- 281 个 Block，每阶段按 fallback 各 220～291 个有序字段；
- 所有 Unit/Block fallback runtime class 均来自 `mindustry.*` 官方 Server JAR；
- Item/Liquid/Status 字段数保持 14/22/27；
- 输出约 28.5 MB；
- 目标对象无法按现有安全值类型表达时均明确 opaque。

验证产物：

~~~text
work/root-field-capture-new-horizon-snapshot-2.json
work/root-field-capture-new-horizon-run-2/
~~~

## 下一步

1. 为 UnitType/Block 建立 target mapper 字段白名单与 lifecycle 选择规则；
2. 建立 Weapon/Bullet/Ability、Draw/Consume 等有界对象图编码，不能把当前 opaque 当成成功；
3. 对特殊 requirements/consumes/ammo/plans/upgrades 使用专用 adapter；
4. 通过真实 v159.7 DataPatcher.apply 二次加载并做字段差异比较；
5. 分批开启 Unit 与 Block fallback，不能因“根字段已采集”就宣称功能已经迁移。
