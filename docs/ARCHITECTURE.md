# 架构设计

最后更新：2026-07-30 06:05（Asia/Shanghai）

## 设计目标

`mod-dp-bridge` 是一个确定性、离线、面向服务器的 Mindustry Mod/CP/DP → v159.7 Data Assets 转换器。主干转换不执行输入代码，也不依赖 Agent。核心原则是：**尽力转换、绝不静默丢弃、产物与未转换项同时交付**。

当前只有一个目标适配器：Mindustry v159.7/B480。旧 Mod 版本号只表示“尽力读取静态数据”，不是对应历史运行时兼容层。

## 能力边界

### 硬边界

- 生成根目录合法的 DP ZIP。
- 生成可放入服务器 `config/assets` 的展开目录。
- 保留每个已规划输入文件的最终状态和原因。
- 最终必须经过真实 v159.7 Desktop 地图导入、保存重开和真实服务器地图/存档加载。当前可自动执行结构检查、B480 DataPatcher apply 和辅助的 server asset discovery，但不会伪造地图加载结果。

### 软边界

- HJSON/CP/DP 顶层内容：Item、Block、Liquid、Status、Unit、Weather。
- Java AST 根内容：Item、Liquid/CellLiquid、StatusEffect、UnitType 和 Block；Weather 暂不是 Java exporter 的生成类型。
- Java AST 嵌套数据：尽力保留 v159.7 原版 `ClassMap` 可表达的 Weapon、Bullet、Effect、Draw/Part/Shoot、Ability、Consume、AI 等对象图，并可将内嵌 UnitType 提升为独立 Content。
- Java 源码不再因“存在代码”而统一阻断；确定性 JavaParser AST exporter 只解释受限的纯数据子集，不编译、加载、反射或执行输入类。
- 自定义 Java/JS/Kotlin 类行为、方法覆写、lambda/callback、反射、GUI、网络和客户端逻辑仍不在数据表达能力内；只有命中明确降级规则时才保留其原版基类字段，其余会删除、替换、标记 `degraded/unsupported` 并记录源位置。

### 明确排除

- `research`/科技树
- Planet/Sector
- Mod 自带 maps
- scripts
- sprites-override
- 自定义 GUI、网络协议和客户端界面

“地图可导入”指将生成 DP 导入或嵌入测试/目标地图，不代表迁移 Mod 自带地图。

## 模块

```text
bridge-model
  报告、诊断、清单、fileResults、验证阶段、JSON/Markdown

bridge-java-static
  JavaParser AST 清单与确定性 Java -> HJSON 静态导出、降级与 contentResults

bridge-target-api
  TargetValidator 接口和目标选项

bridge-converter
  安全读取、输入识别、转换计划、HJSON/CP、命名空间、资源检查、打包

bridge-target-1597
  v159.7 静态结构验证、可选真实 B480 DataPatcher apply、辅助 Server 资产发现

bridge-cli
  命令行、日志、报告合并、退出码、失败文件
```

## 转换流水线

```text
目录 / ZIP / JAR / JSON / HJSON / JSON5
        |
        v
SafeSourceReader
  路径规范化、ZIP Slip、大小/数量/压缩比限制、SHA-256
        |
        v
SourceDetector
  MOD / LEGACY_CP / DATA_PACK
        |
        v
ConversionPlanner
  分类每个文件、建立候选输出、排除/不支持项、冲突检查
        |
        +---------------- MOD ----------------+
        |                                     |
        |  StaticSourceExporter SPI           |
        |    -> JavaParser AST 静态导出       |
        |    -> contentResults/诊断/生成 HJSON |
        |  符号表 -> ModNamespaceRewriter     |
        |  HJSON 规范化 -> 移除 research      |
        |  bundle/generated 路径重写          |
        |                                     |
        +------------- DATA_PACK -------------+
        |                                     |
        |  文本解析和静态检查                  |
        |  输出文本字节保持不变                |
        |                                     |
        +------------- LEGACY_CP -------------+
        |                                     |
        |  原生 HJSON / 有限兼容修复           |
        |  输出 patches/<slug>.hjson           |
        +-------------------------------------+
        |
        v
AssetReferenceValidator + 名称/图标结构检查
        |
        v
PlannedOutputFile + ConvertedFile
        |
        v
DeterministicPackager
  server-assets/ + 稳定 DP ZIP
        |
        v
ConversionReport
  inventory + fileResults + diagnostics + validation stages
        |
        v
Mindustry1597StructuralValidator
        |
        +-- 可选 -- Mindustry1597ContentApplyValidator(--server-jar)
        |              真实 DataManager.load / DataPatcher.apply
        |
        +-- 辅助 -- Mindustry1597ServerValidator(--server-jar)
                       仅文件发现/冷启动
        |
        v
仍需真实 Desktop 地图导入、音画和玩法验证，
以及服务器实际加载携带 DP 的地图/存档
```

## 输入读取与安全模型

`SafeSourceReader` 只把输入视为数据：

- 目录按相对路径扫描；ZIP/JAR 在内存快照中读取，不执行或安装；
- 拒绝绝对路径、`..`、路径穿越和异常长路径；
- 限制输入大小、条目数、单条目大小、总展开大小和压缩比；
- 可剥离只有一个公共目录的压缩包外层；
- 为源文件和输出记录大小与 SHA-256。

输入中的 Java、class、JAR 内代码、JavaScript、Gradle/Maven 构建均不会执行。Java 源文本只会交给 JavaParser 建立 AST，随后由项目内置的确定性规则求值；它不使用输入 classpath，也不解析或调用任意 helper 实现。可选服务器验证只执行项目内置固定 harness 和用户显式提供、应当可信的 Server JAR。

## 输入类型识别

### 普通 Mod

满足任一条件：存在 `mod.hjson/mod.json`、存在 `assets/` 根、输入扩展名为 JAR。

Mod namespace 来自 metadata `name`，按 Mindustry `LoadedMod.name` 规则转换为小写并把空格替换为连字符。该 namespace 供引用迁移使用。

## JavaParser AST 静态导出

`bridge-java-static` 通过 `StaticSourceExporter` SPI 接入 MOD 路径。它使用 JavaParser 仅解析源文本，建立 Content 声明、常量和引用符号表，再把可证明的原版数据对象图渲染成 HJSON。生成文件保持源 Mod 命名语义，随后交给共享 `ModNamespaceRewriter` 转换为 `dp-*`。

当前主干可处理：

- Item、Liquid/CellLiquid、StatusEffect、UnitType、Block 根声明；
- Weapon、Bullet、Ability、Effect、Draw/Part/Shoot、Consume 和常见计划/stack/map/list 对象；
- 字面量、安全数值运算、已知常量、Color、内容/资产引用、方法局部常量、匿名 initializer 局部变量；
- requirements/consume/ammo/plans/upgrades、builder 修饰、Weapon copy helper、链式赋值；
- 已生成 Content 之间可静态确定的字段赋值，例如 `((Floor) quartzSand).decoration = this`；
- 将嵌套 `UnitType`/`MissileUnitType` 提升为独立 `content/units/*.hjson`，并在原字段写入 Content 引用；
- 受限的经典 `for(init; condition; update)` 静态展开。

`for` 展开只接受可静态求值的数值初始值、比较和增减/数值更新，循环体只能包含已支持的赋值、局部声明、builder 调用、空语句或再次受限的 `for`。硬上限为：

- 单个循环最多 64 次；
- 每个生成 Content 声明在根 initializer 和所有嵌套对象中共享 4096 次总预算；
- 循环嵌套深度最多 8 层。

无法求值或超限时停止展开并生成带源位置的诊断，不会回退到 Java 执行。已知自定义类可按显式表降级为原版基类，但自定义字段、回调、方法覆写和任意 helper 不会被假定等价。

### Legacy CP

输入必须是单个 JSON/HJSON/JSON5 文件，根对象至少包含 item、block、liquid、status、unit、weather 之一。输出统一包装为 patch asset。

### 已有 DP

根目录出现 `content/patches/bundles/sprites/sounds/music` 时识别。已有 DP 被认为已经处于 `dp-` 命名空间，不执行普通 Mod 的 namespace 迁移。

## ConversionPlanner 与文件状态

规划器必须给每个可完成规划的源文件一个 `ConvertedFileStatus`：

- `COPIED`：二进制或要求保持原字节的文本直接复制；
- `NORMALIZED`：文本规范化、命名空间重写、bundle key 或路径发生迁移；
- `EXCLUDED`：产品策略明确排除，例如 map、script、Planet/Sector；
- `UNSUPPORTED`：格式、扩展、目录或能力尚不支持；
- `FAILED`：模型预留给文件级失败。当前致命解析异常通常会中止计划并进入 failure report，因此标准报告中该类多为零。

转换报告映射为 copied、converted、excluded、unsupported、failed。`inventory.ignored` 同时收录 excluded 和 unsupported 的源文件以保留兼容，但精确分类以 `fileResults` 为准。

规划完成前还执行 v159 名称空间冲突规则：

- 普通 sprite 不同目录若 basename 相同且 PNG 字节完全一致，可确定性保留路径排序后的一个副本，其余记录为 excluded，并产生 `IDENTICAL_SPRITE_DEDUPLICATED`；
- 同 basename sprite 若字节不同，不会静默选取，后续以 sprite collision 拒绝；
- 一个普通 sprite 与一个 generated sprite 的同名对具有 v159 precedence 语义，保留二者；
- sounds 内部、music 内部 basename 必须唯一；
- sound 和 music 实际共享 v159 音频 namespace，跨目录同 basename 以 `AUDIO_NAME_COLLISION` 拒绝。

## ModNamespaceRewriter

### 为什么必须迁移

普通 Mod 加载时的自定义内容和资源通常注册为 `<mod-name>-<local-name>`。v159 Data Assets 的新内容则共享固定 `dp-<local-name>` 名称。如果只复制文件，requirements、ammoTypes、weapon、region、sound、bundle 和 patch 引用可能继续指向旧 Mod 名称。

### 符号表

重写前从最终候选中建立：

- 按 ContentKind 分类的内容 basename；
- sprite/sound/music 的源 basename；
- 原 Mod 运行时名称；
- DP 目标运行时名称。

只有符号表能确认的名称才自动迁移。

### 重写范围

- 已知内容引用字段和数组，如 requirements、items、liquids、unit、status、upgrades 等；
- map 型字段，如 ammoTypes、capacities 的对象键；
- patch 内容对象键；在普通 Mod patch 的顶层 content 类型桶中，符号表可确认的本地键也会迁移，例如 `block: { wall: ... }` 变为 `block: { dp-wall: ... }`；
- Weapon 或类似成员的 `name`；
- region/sprite/icon/texture、sound、music 字段；
- 音频源路径别名，例如 `sounds/subdir/shot.ogg` 对应引用 `subdir/shot`，迁移为 Data Assets 按 basename 注册的 `dp-shot`；
- bundle properties 的内容 key；
- 可识别的类别前缀 generated sprite 路径。

描述、详情、localized name 等字面文本不做全文替换。

### 诊断与风险

- 成功重写：`MOD_REFERENCE_REWRITTEN`、`BUNDLE_NAMESPACE_REWRITTEN`、`SPRITE_NAMESPACE_PATH_REWRITTEN`。
- 汇总：`MOD_NAMESPACE_MIGRATED`。
- 明显属于旧 namespace 但符号表无匹配：`UNRESOLVED_MOD_REFERENCE`，严重级别为 error。

这是字段和符号驱动的静态迁移，不理解自定义代码、处理器字符串、反射或未知字段。因此它降低风险但不能证明 namespace 完整闭包。

## 已有 DP 的文本字节保持

Mindustry 的 ContentAsset/generated 资源可能使用内容文本的精确哈希。对已有 DP 重新格式化 JSON/HJSON，即使语义不变，也可能使：

`sprites/generated/<content-type>_<hash>/...`

失效。

因此 DATA_PACK 路径采用以下策略：

1. 解析 content/patch 文本以确认可读并执行静态引用检查；
2. 不删除 `research`，不重排键，不改变空白或注释；
3. 输出原始文本字节；
4. 只对 ZIP 条目顺序、时间戳和容器进行确定性重打包。

`DATA_PACK_TEXT_PRESERVED` 表示采用了该策略。该策略只能保护已有哈希，不证明原 DP 本身的 generated 路径正确。

普通 Mod 路径必须规范化和改名，因此其内容哈希可能变化。任何原带 generated 资源都必须由 Desktop 客户端验证。

## Legacy CP 兼容层

优先使用 HJSON 原生解析。失败后，有限兼容器会尝试：

- `+=` 转换为旧 patch operator 可接受形式；
- 为部分未引号键添加引号；
- 为内联对象/数组中的部分裸 token 添加引号。

成功后产生 `LEGACY_CP_COMPATIBILITY_REPAIR` warning。该层是 best effort 正则修复，可能错误处理复杂字符串或特殊语法，不能当作完整旧 CP 语言解释器。

## AssetReferenceValidator

### 索引

从最终候选输出建立：

- sprite 目标运行时名；
- sound 目标运行时名；
- music 目标运行时名；
- 原始 basename 到目标名的映射。
- 对音频额外记录相对路径别名，以便把嵌套目录引用迁移到实际 basename 注册名。

普通资源通常注册为 `dp-<basename>`；部分 generated sprite 保留类别前缀并把内部 namespace 迁移为 `dp`。

### 静态检查

递归遍历 JSON/HJSON，在已知字段中识别 sprite、sound 和 music 引用。主要错误：

- `SPRITE_REFERENCE_NOT_DP_PREFIXED`
- `AUDIO_REFERENCE_NOT_DP_PREFIXED`
- `SPRITE_REFERENCE_MISSING`
- `AUDIO_REFERENCE_MISSING`

此外，规划器对非 Weather 内容检查一组常规图标名，找不到时产生 `CONTENT_ICON_NOT_FOUND` warning。

### 非能力

验证器不会：

- 解码 PNG；
- 解码或播放 OGG/MP3；
- 创建客户端纹理 atlas；
- 执行 Draw/Effect；
- 解析任意未知字段或运行时拼接引用；
- 判定所有未命中的英文名称是缺失还是原版资源。

因此它是静态风险探测器，不是资源闭包证明。

### 音频容器 magic 检查

资产检查会读取常见 MP3、WAV 和 OGG 容器的 magic bytes，并与文件扩展名比较。容器与扩展名不一致时生成 `AUDIO_CONTAINER_EXTENSION_MISMATCH` warning，但不在转换器中转码或更改字节：静态改名或转码都可能改变质量、循环点、许可边界或目标客户端的实际兼容性。

这一诊断表示“必须 Desktop 解码验证”，不表示 B480 headless DataPatcher 会拒绝该资产；headless apply 不解码外部音频。如 Arc/SoLoud 在精确客户端版本不能解码，应由人工转码为真正的 OGG 或 MP3，同时保留资源 basename 和引用闭包。

## 打包

`DeterministicPackager` 使用按路径排序的 `PlannedOutputFile`：

- 写入展开的 `server-assets/`；
- 写入无外层目录的 DP ZIP；
- 固定 ZIP entry 时间；
- 计算 ZIP SHA-256 和逻辑树 hash。

二进制资产在没有路径重写时保持字节不变。普通 Mod 文本会规范化；已有 DP content/patch 文本按前述策略保持原字节。

## 报告模型

`ConversionReport` 是 JSON 的机器真相，Markdown 是人类可读视图。包含：

- source/target；
- overall status；
- summary 和 inventory；
- 每文件 `fileResults`；
- diagnostics；
- STRUCTURE、RUNTIME、MAP_IMPORT、SERVER_LOAD 阶段；
- 输出路径、大小和 hash；
- 扩展 metadata。

`report.md` 对五种文件状态逐类列出 source、output、reason、diagnosticCodes。未转换项不能只存在于聚合 warning 中。

致命错误发生在报告构造前时，CLI 改写 `failure-report.txt` 和可选 `failure-diagnostics.json`，避免生成看似完整但实际上缺失文件状态的标准报告。

## 验证层次

### 1. 转换期静态检查

HJSON 可读性、命名空间、资源引用、名称碰撞、图标惯例。

### 2. v159.7 结构验证

验证 ZIP/目录路径、合法根目录、扩展、顶层内容类型、全局 content basename、sprite 规则。通过后仍为 PARTIAL。

### 3. 可选 Headless parser/DataPatcher apply

`Mindustry1597ContentApplyValidator`：

1. 将项目内置、固定的 `DpApplyHarness.java` 写入隔离临时目录；
2. 用用户提供的可信 B480 JAR 启动独立 JVM；
3. 初始化原版基础 Content；
4. 对生成资产真正调用 `Vars.state.data.load(...)` / `DataPatcher.apply(...)`；
5. 检查直接与 `finishParsing/init/postInit` 延迟错误；
6. 输出逐 Content/Patch 诊断和完整日志，然后删除临时目录。

输入 Mod 的 Java/class/Gradle/脚本不会被编译、加载或执行。

apply 通过可以把 RUNTIME 标为 PASSED，但仍不加载地图，所以 SERVER_LOAD 保持 NOT_RUN。

### 4. 辅助 Server 文件发现

`Mindustry1597ServerValidator`：

1. 创建临时工作区；
2. 把 `server-assets/` 复制到 `config/assets`；
3. 启动用户提供的 Server JAR；
4. 等待 `Loaded N data asset files.` 和 `Server loaded.`；
5. 收集明显错误、退出码和完整日志；
6. 删除临时目录。

这只是 ServerControl 文件发现和普通冷启动检查。`Loaded N data asset files.` 不证明
DataPatcher apply，不更新 RUNTIME/SERVER_LOAD 为 PASSED。

### 5. Desktop 人工验证

当前必须由用户完成。包括 DP 导入、地图保存/重开、图集、generated、音频、武器/工厂/单位行为和地图部署。详细步骤见 `TESTING.md`。

目前已有两项人工证据：minimal 普通 Mod 的 9 个资源及三个目标 content 正确注册；`惊鸿3` 的 17 个资源导入、炮塔注册和核心原逻辑正常。它们证明 Desktop 路线具备可行性，但不是完整类型矩阵。

现有 CLI 不会把用户口头/截图测试自动写回原 `report.json`，因此这些转换报告中的 MAP_IMPORT 仍可能是 `NOT_RUN`。人工证据以持久文档和未来测试附件记录，不能篡改成自动验证结果。

## Saturation Firepower 当前端到端证据

2026-07-30 的最终 headless 样本位于 `work/saturation-static-20260730-060256`：

- 读取 25 个 Java 源文件，找到 354 个顶层 Content 声明；
- 额外提升 4 个嵌套 `MissileUnitType`，最终生成 358 个 Content HJSON；
- 保留 1628 个外部资产，DataPatcher 总计接收 1986 个资产；
- contentResults 为 295 `converted`、63 `degraded`、0 `unsupported`、0 `failed`；
- B480 `DataManager.load` / `DataPatcher.apply` 为 0 failed、0 warning，`addedContent=689`；
- 转换报告有 60 个 warning，其中 5 个是 `AUDIO_CONTAINER_EXTENSION_MISMATCH`：3 个 `.ogg` 实为 MP3、2 个 `.ogg` 实为 WAV；字节保持不变且未转码；
- STRUCTURE 和 RUNTIME 通过；MAP_IMPORT 和 SERVER_LOAD 均为 NOT_RUN；
- DP ZIP SHA-256 为 `B085C6533CC43367CBC488DB201D0414F3B212DDDF6B5D8C081C6482755569BE`。

这一证据证明当前静态导出对一个大型典型 Java Mod 已能产生 B480 parser/apply 接受的内容图，但 63 个降级 Content 仍有已知的 Java-only 字段或回调丢失。Headless harness 不解码客户端 PNG/音频、不构建完整客户端 atlas，也不导入或加载地图，因此状态仍为 `PARTIAL`。

## 状态原则

- 静态结构通过但未做 Desktop：`PARTIAL`。
- 结构或 DataPatcher apply 验证失败：最终报告为 `REJECTED`，CLI 返回非零。
- 仅 Headless 通过不得升级为 SUCCESS。
- SUCCESS 只能保留给全部硬验证完成且没有未接受错误的未来流程。

## 后续扩展点

- 增加字段级 namespace/asset 规则，而不是引入全局字符串替换。
- Desktop Worker：真实导入地图、生成/验证 atlas 和 generated 资源。
- 扩充确定性 JavaParser AST 映射、目标版本 snapshot 和诊断，不把执行输入 Java 或引入 Agent 作为主干兼容方案。
- 多目标版本适配器：需要独立的目标源码、ClassMap 和运行时验证，不能把 v159.7 规则直接宣称兼容 146–158。
- 网站：应复用现有 CLI/报告模型，放在转换核心稳定之后。
