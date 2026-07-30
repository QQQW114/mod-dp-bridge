# mod-dp-bridge MVP 阶段验证报告

报告时间：2026-07-30 02:47（Asia/Shanghai）

## 1. 阶段结论

当前 MVP 已证明以下路线在工程上可行：

1. 普通纯数据 Mod 的静态 content/asset 迁移与 `dp-` 命名空间改写；
2. 已有 v159 DP 的安全、确定性、字节保持重打包；
3. Legacy CP 到标准 patch asset 的 best-effort 转换；
4. 展开服务器资产、DP ZIP、逐文件报告、完整日志和可选 B480 冷启动；
5. 真实 v159.7 Desktop 能导入至少一个普通 Mod 转换结果和一个已有 DP 重打包结果。

项目已经跨过“理论可行”阶段，进入**可供技术用户试用和继续兼容开发的 CLI MVP**。但尚不适合宣传为“任意 146+ Mod 一键无损转换”，也不适合在无人审查的公开网站中直接把所有输入标记为成功。

### 当前整体可行度估计

以下百分比是工程判断区间，不是自动化测试通过率：

- **既定 MVP 能否做成：80%–90%，高可行。**
  纯数据内容、资产、报告和 v159.7 部署链路已经跑通，剩余工作主要是扩大类型矩阵和补齐人工/客户端验证。
- **当前实现对目标范围的成熟度：约 65%–75%。**
  基础内容注册和一个真实炮塔逻辑已有 Desktop 证据，但地图持久化、音频、更多工厂/状态/天气/Effect/Draw/Consume/AI 组合仍缺乏系统验证。
- **代码型 Mod 的自动玩法转换可行度：当前接近 0%。**
  Java/JS/Kotlin 定义的玩法内容没有被执行或翻译；工具只能搬运静态资源并给出阻断性错误。除非人工改写为 HJSON，不能称为转换成功。

## 2. 适用对象

### 当前最适合

1. **已有 v159 Data Pack**
   - 需要安全检查、确定性重打包、服务器展开目录和报告；
   - `惊鸿3` 已提供真实 Desktop 和 Headless 证据。
2. **以 `assets/content` JSON/HJSON 为主的普通 Mod**
   - 顶层内容属于 Item、Block、Liquid、Status、Unit、Weather；
   - 嵌套类型使用 v159.7 原版 `ClassMap`；
   - 能接受报告驱动的人工修复和 Desktop 复测。
3. **较简单的 Legacy CP**
   - 可包装为 `patches/<name>.hjson`；
   - 如果触发兼容正则修复，必须人工核对语义。
4. **服务器维护者和 Mod 作者**
   - 能阅读 JSON/Markdown 报告；
   - 能提供 v159.7 客户端日志、地图和测试反馈；
   - 能根据 unresolved/unsupported 项补 HJSON。

### 当前不适合

- 主要内容由 Java/JS/Kotlin 创建的 Mod；
- 依赖自定义类、方法覆盖、反射、网络协议或 GUI 的 Mod；
- 依赖 Planet/Sector、Mod 自带地图、科技树或 sprites-override 作为主要玩法的 Mod；
- 需要保证兼容 146–158 原运行时行为的 Mod；
- 要求无人值守后直接宣称“全部功能正常”的网站服务。

## 3. 已验证层级

| 层级 | 当前状态 | 证据 | 仍未证明 |
| --- | --- | --- | --- |
| 构建/单元测试 | 通过 | 完整 Gradle build；20 项测试通过 | 更大语料和长期兼容 |
| 安全读取 | 通过代表样本 | ZIP Slip 拒绝且越界审计为空；压缩限制测试 | 所有恶意压缩格式 |
| 静态转换 | 通过代表样本 | 普通 Mod、Legacy CP、已有 DP、负面资源样本 | 所有嵌套字段和历史语法 |
| v159.7 结构验证 | 已实现 | 路径、根目录、类型、名称冲突、资源引用 | 客户端 atlas/audio 行为 |
| B480 Headless | 多样本通过 | minimal 9、legacy CP 1、惊鸿 17、亚龙 76、Saturation 静态资产 1628 | 贴图、声音、地图内嵌和玩法完整性 |
| Desktop 普通 Mod 导入 | 代表样本通过 | minimal 导入 9，三个目标 content 正确注册 | 武器、持久化、更多内容类型 |
| Desktop 已有 DP | 代表样本通过 | 惊鸿导入 17，炮塔注册与用户所测原逻辑正常 | 两个音效、全部视觉/generated 细节 |
| 地图保存/退出/重开 | 未形成证据 | — | DP 内嵌持久化硬边界 |
| 多人目标地图 | 未形成证据 | — | 服务端地图加载和客户端同步 |

## 4. 真实 Desktop 结果

### 4.1 minimal 普通 Mod

转换输出定义：

- `content/blocks/fixture-wall.hjson`
- `content/items/fixture-alloy.hjson`
- `content/units/fixture-drone.hjson`
- 2 个 bundle properties
- 4 个 sprite PNG

合计 9 个 Data Assets 文件。

用户实测：

- Desktop 导入 9 个资源；
- `dp-fixture-wall` 正确注册；
- `dp-fixture-drone` 正确注册；
- `dp-fixture-alloy` 正确注册。

可确认：

- DP ZIP 能被真实客户端读取；
- Item、Block、Unit 三种顶层内容在该样本中注册成功；
- 普通 Mod 到固定 `dp-` namespace 的基础迁移有效。

不能据此确认：

- `fixture-drone` 武器、弹丸、效果是否实际正常；
- 所有 sprite/icon 是否正确；
- 地图保存后完全退出并重开是否仍存在；
- 未包含在此 fixture 中的音频、工厂、液体、状态、天气等类型。

#### 已解释的数据库范围副作用

用户同时看到 4 个额外星球标签和原版 `heat-source`。产物审计确认 minimal DP 没有 Planet/Sector 文件、patch，也没有定义 `heat-source`；DP 顶层本身也不允许创建 Planet。

v159.7 源码核查已定位具体机制：

- 原版运行时存在 7 个 Planet；数据库原先通常已有 Serpulo/Erekir 标签。
- `fixture-wall` 有 requirements，但未设置 `shownPlanets`。
- 自定义需求物品的 `shownPlanets` 为空，因此 `isOnPlanet()` 对任意星球返回可用。
- `Block.postInit()` 把墙自动加入全部 6 个可登陆星球，数据库因此额外收集 Gier、Notva、Tantros、Verilus 四个标签。
- `heat-source` 是原版 `sandboxOnly` 方块，且 `allDatabaseTabs = true`，所以随新增标签在制造分类显示。

结论：转换包没有错误注册 Planet 或 `heat-source`，但未指定 Planet scope 会产生可见的数据库标签扩展/UI 副作用。它不阻断地图玩法；后续应增加明确诊断，或提供不改变地图逻辑的可选默认 scope 策略。

### 4.2 `惊鸿3.zip`

转换输出：

- 1 个 Block content；
- 2 个 OGG；
- 14 个普通/generated PNG；
- 合计 17 个条目。

自动审计已确认输入和输出路径集合相同，17 个条目全部逐字节相同，保留了 ContentAsset/generated hash 关系。

用户实测：

- Desktop 导入 17 个资源；
- 惊鸿炮塔正确注册；
- 炮塔原逻辑功能正常。

这为“已有 DP 安全重打包”提供了当前最强证据。可以确认该炮塔在用户测试范围内的核心逻辑没有被重打包破坏。

仍不能扩大解释为：

- 两个音效都已触发并播放正确；
- 每一个 heat/outline/preview/generated region 都逐项验证；
- 所有可能存在的 Effect、Draw、Consume 等嵌套路径都被单独验证。

## 5. Headless 结论与盲区

Headless 冷启动是必要但不充分的门槛。

`亚龙组合包 (1).zip` 有 5 个缺失 sprite 引用和 1 个未使用 `dp-` 的自定义音效引用，STRUCTURE 为 FAILED；然而 B480 仍报告 `Loaded 76 data asset files.` 和 `Server loaded.`。

Saturation Firepower 只有静态资源输出、0 declarative content，STRUCTURE 因 `MOD_CODE_NOT_EXECUTED` 失败；B480 仍可加载 1628 个静态资产。

因此服务器加载只能证明 DataManager/content 初始化没有被明显解析错误阻断，不能证明：

- PNG 和 atlas region 正确；
- generated hash 在客户端匹配；
- OGG/MP3 可播放；
- 武器、Effect、Draw、Factory、Consume、AI 等行为完整；
- Java 定义玩法内容已转换。

## 6. 硬限制

1. 目标仅为 v159.7/B480；没有 146–158 历史运行器。
2. 顶层仅支持 Item、Block、Liquid、Status、Unit、Weather。
3. 嵌套对象必须能由 v159.7 原版 `ClassMap` 表达。
4. Java/JS/Kotlin 代码不执行、不翻译；出现时 `MOD_CODE_NOT_EXECUTED` 为 error。
5. research、Planet/Sector、Mod maps、GUI、网络、sprites-override 明确排除。
6. 静态资源验证使用字段启发式，不是完备资源闭包证明。
7. 当前没有 PNG/OGG 解码器和 Desktop Worker。
8. Legacy CP 正则兼容可能有损。
9. 当前没有网站、任务队列或多租户隔离部署。

## 7. 各输入类型可行度

| 输入类型 | 当前估计 | 说明 |
| --- | --- | --- |
| 已有 v159 DP | 90%–95% | 字节保持、Headless 和惊鸿 Desktop 样本均通过；仍需每包检查自身资源错误 |
| v159 附近纯 HJSON Mod | 75%–85% | 基础 namespace 和三种 content 已 Desktop 通过；复杂嵌套类型矩阵不足 |
| 146–158 纯数据 Mod | 50%–70% | 可尽力解析，但格式/ClassMap 演进和 generated 规则没有历史运行器证明 |
| Legacy CP | 60%–75% | 服务器加载样本通过；兼容修复和 Desktop 玩法仍需更多验证 |
| Java/JS/Kotlin 玩法 Mod | 自动玩法转换接近 0% | 只能提取静态资源并报告代码阻断；必须人工 HJSON 化 |

## 8. 当前发布定位

当前适合发布为：

- GPLv3 开源仓库；
- 面向技术用户的 CLI MVP/技术预览；
- 明确要求阅读报告和执行 Desktop 测试的转换辅助工具。

当前不适合发布为：

- 对所有 146+ Mod 承诺成功的一键转换器；
- 无人工审查的公共上传网站；
- Java Mod 自动移植器。

## 9. 后续优先级

### P0：闭合当前硬边界

1. minimal 和惊鸿分别执行地图保存、完全退出客户端、重开和地图导出。
2. 用导出的目标地图完成服务器加载和真实客户端联机。
3. 为缺少 `shownPlanets` 的内容增加 scope 诊断，并评估可选默认 scope，消除已确认的数据库标签扩展副作用。
4. 实测 minimal Unit 的武器/弹丸；实测惊鸿两个音效和关键 generated/heat region。

### P1：扩大纯数据玩法矩阵

1. 增加 Turret、Factory、Liquid、Status、Weather 样本。
2. 增加 Effect、DrawPart、Ability、Consume、AI、Payload、ammoTypes 等嵌套组合。
3. 为每类建立 Desktop 证据和预期截图/日志，而不只依赖 Headless。
4. 扩展资源引用字段，同时避免全局字符串替换。

### P2：工程化与发布

1. 加入可签署的人工验证附件或独立验证记录，不伪改自动报告阶段。
2. 完善发行包、README、许可证和第三方语料说明。
3. 核心稳定后再评估 Desktop Worker 和网站。

### 暂不进入主干

- Agent/LLM 参与每次转换；
- 直接执行不可信 Mod 构建或代码；
- 宣称自动转换 Java 自定义行为。

## 10. 最终判断

本项目的核心方向可行，且已有真实客户端证据支持：

- **已有 v159 DP 的安全重打包已经达到较高可信度；**
- **纯 HJSON 普通 Mod 的基础内容迁移已经证明可工作；**
- **报告驱动的“尽力转换 + 明确失败项”路线是可实施的。**

目前最需要的不是推翻架构，而是补齐 Desktop 持久化、音画、复杂嵌套类型和地图联机验证。代码型 Mod 仍是明确硬边界，不应纳入当前自动转换成功范围。
