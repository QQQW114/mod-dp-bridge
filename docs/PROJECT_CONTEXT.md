# 持久上下文

最后更新：2026-08-01（Asia/Shanghai）

本文件用于在上下文压缩、新会话或任务交接后恢复项目约束和当前事实。继续开发前，还应阅读：

- `docs/PROJECT_STATUS.md`
- `docs/ARCHITECTURE.md`
- `docs/TESTING.md`
- `docs/WEB_UI.md`
- `docs/DATA_PATCH_APPLY_VALIDATION.md`
- `docs/JAVA_STATIC_SATURATION_BASELINE.md`
- `docs/SATURATION_FIREPOWER_ASSESSMENT.md`
- `docs/SATURATION_FINAL_VALIDATION_20260730.md`
- `docs/B480_CLIENT_IMPORT_FIX_20260730.md`

## B480 Desktop 客户端当前实测状态

旧 Saturation ZIP 已在真实 Desktop 导入中失败，**不得再交付**：

`work\saturation-static-20260730-060256\sfire-mod-dp-v159.7.zip`

旧 SHA-256：`B085C6533CC43367CBC488DB201D0414F3B212DDDF6B5D8C081C6482755569BE`。

根因包括 B480 固定 `700x700` environment atlas 只发布第一页，以及 `DataImagePacker.unload` 可能抛出 `key cannot be null` 并污染当前客户端 atlas 状态。转换器已加入 173 张 environment sprite 规划、HJSON `variants` 同步重写、OreBlock 运行时别名和 generated content-hash sentinels。完整诊断与重测要求见 `docs/B480_CLIENT_IMPORT_FIX_20260730.md`。

上一候选 ZIP `saturation-clientfix-20260730-070717` 已由用户确认能由真实 Desktop 正确导入并进入地图，因此“客户端可导入/加载”硬边界已取得实证。

当前最终候选 ZIP：`work\saturation-outlinefix-final-20260730-075936\sfire-mod-dp-v159.7.zip`；SHA-256：`EEEA579FA05CA961A7B9297DF908419369BA0480E3877F4FACE237DA2F4EFBA9`。该包已通过转换器测试、完整构建和真实 B480 `DataPatcher.apply`，尚待用户 Desktop 复测。

本轮已完成：炮塔/多层工厂 full icon 离线组合；单位、腿、履带、武器、炮塔主体和 RegionPart 描边；镜像构件 `R/L` 到 `r/l` 规范化；共享武器 generated atlas 名去重；Unit `joint-base/treads` 与 DrawTurret 底座解析修正。最终生成 87 个 full icon、326 个 outline/outlined sprite，共 413 张 PNG，并保留 329 个唯一 content-hash sentinel。仍有 152 条无法完整生成的图标项，集中记录为 `B480_OFFLINE_CONTENT_SPRITES_PARTIAL`。

`dp-cimaidp-cimai` 不是实际双重命名空间 bug；精确检查表明 `cimai` 原始构件可解析，视觉缺失来自 outline 未离线生成，现已补齐。唯一真实镜像大小写缺失 `liemei-barrel-R.png` 已规范输出为 `liemei-barrel-r.png`。

退出地图和无核心编辑器地图退出崩溃均已定位为 B480 客户端 `DataImagePacker.unload()` 的 `ObjectSet.remove(null)` 上游缺陷。纯 DP 无法在不删除/缩放约三分之二 atlas 资产的情况下规避；正确修复必须修改客户端。诊断与补丁：`docs/B480_EXIT_UNLOAD_CRASH_20260730.md`、`docs/patches/DataImagePacker-unload-fix.patch`。后续优先级：1）用户复测最终候选的描边、建造栏组合图、`cimai`、`liemei`；2）根据复测结果补规则；3）验证服务器实际加载携带该 DP 的地图。

以下目录仅保留为旧 Headless 自动化基线，不是当前客户端候选：

`work\saturation-static-20260730-060256`

其中 DP ZIP 为：

`sfire-mod-dp-v159.7.zip`

SHA-256：

`B085C6533CC43367CBC488DB201D0414F3B212DDDF6B5D8C081C6482755569BE`

旧便捷指针 `work/LATEST_SATURATION_DIR.txt`、`work/LATEST_SATURATION_ZIP.txt`、
`work/LATEST_SATURATION_SHA256.txt`、`work/LATEST_DETERMINISM_CHECK.txt` 在新包重生成前也只能视为旧基线。

## 用户确认的目标

- 已优先完成可开源的转换核心；当前又实现了本地 Web UI。公网网站部署仍是可选后续目标，不能牺牲 CLI 确定性和安全边界。
- 将 Mindustry 146+ Mod 尽力静态迁移到 v159.7/B480 Data Pack。
- 产品主干不得依赖 LLM Agent，转换必须确定、可审计、可复现。
- 不编译、不加载、不反射、不执行输入 Mod 的 Java/JS/Kotlin/Gradle/Maven 代码。
- 允许使用 Java AST 和受限静态求值，把能由 v159.7 数据系统表达的对象图编译为 HJSON。
- 尽力迁移直接服务地图玩法的 Item、Liquid、Status、Unit、Block、Terrain、Turret、Factory、Weapon、Bullet、Ability、Effect、Draw、Consume、AI 及其贴图、音效和 bundle。
- 放弃科技树、Planet/Sector、Mod 自带地图、GUI、网络、自定义客户端界面和全局 `sprites-override`。
- 禁止静默丢弃；未转换、降级、排除、不支持和失败项必须进入报告并可追踪到源码位置。
- 输出 DP ZIP、展开的 `server-assets/`、完整日志、JSON/Markdown 报告。
- 最终产物需要由真实 v159.7 Desktop 地图编辑器和匹配 B480 服务器验证。

## Web UI 当前状态与边界

- `bridge-web` 已实现本地 Web UI 和 HTTP API，前端为嵌入 classpath 的原生 HTML/CSS/JavaScript，无独立 Node.js 工程。
- 网页支持拖拽/选择 `.zip`、`.jar`、`.hjson`、`.json`、`.json5`；普通目录需先压缩，CLI 仍可直接读取目录。
- 必要功能已包含：开始/终止转换、排队、阶段进度、SSE 实时终端日志、DP ZIP/全部日志下载、默认折叠的完成/降级/排除/不支持/失败报告。
- Web 不直接调用转换器内部 API，而是每任务启动独立 `bridge-cli` JVM；因此 CLI 退出码、`report.json` 和落盘日志仍是事实来源。
- 默认地址为 `127.0.0.1:8080`；工作目录默认 `work/web-jobs`；默认上传 64 MiB、并发 1、排队 8、SSE 连接 32（硬上限 120）、保留 24 小时。
- 安全措施包括 Content-Length 与流式上传上限、文件名净化、UUID 目录、CLI 二次归档检查、HTTP `Host` 白名单/DNS rebinding 防护、Origin/Host 同源检查、CSP、SSE 上限和取消时终止进程树。白名单默认含 `localhost`、`127.0.0.1`、`::1`；非通配监听地址自动加入，`0.0.0.0` / `::` 远程访问则要求 `MOD_DP_BRIDGE_ALLOWED_HOSTS` 显式列出域名/IP。
- 当前没有认证、授权、用户/租户隔离、分布式队列、对象存储、病毒扫描或任务重启恢复。Host 白名单不替代认证，不能把实例直接裸露到公网；完整环境变量、API 和反向代理要求见 `docs/WEB_UI.md`。
- Web 作业状态 `succeeded` 只代表 CLI 正常结束，不会把转换报告的 `PARTIAL` 自动升级为最终可用。

## 硬边界与软边界

### 硬边界

1. DP ZIP 根目录直接包含 `content/patches/sprites/sounds/music/bundles`，不能额外套目录。
2. 生成展开的 `server-assets/`，可部署到服务器 `config/assets`。
3. 每个完成规划的源文件以及每个 Java Content 都必须有明确结果和诊断。
4. v159.7 结构检查和正式 `DataManager.load` / `DataPatcher.apply` 必须无失败后，才可称为 Headless 注册通过。
5. Desktop 导入、地图保存重开和服务器加载目标地图必须以真实测试为准，不能由静态检查或冷启动推断。

### 软边界

- 顶层新 Content 仅限 v159.7 Data Assets 原生支持的 Item、Block、Liquid、Status、Unit、Weather。
- Weapon、Bullet、Effect、Draw、Ability、Consume、AI 等通过上述根 Content 的嵌套对象迁移。
- 目标行为存在于 v159.7 `ClassMap` 且能用字段、集合或 parser 特例表达时，优先高保真迁移。
- 自定义 Java 类可按显式映射降级为内置父类，但必须报告丢失字段和行为。
- 任意方法覆写、lambda 回调、反射、网络/UI、运行时世界查询以及完全自定义绘制/逻辑不能自动等价转换。
- 146–158 输入属于尽力解析；当前唯一目标运行时仍是 v159.7/B480，不提供历史版本运行器。

## 固定目标版本与可信参考

- Mindustry：v159.7 / Build 480
- 硬参考 commit：`c9686eb5d0ae5dd47ee02c40f99f7d5018ccbc8c`
- Data patch format：2
- DP 新内容共享固定运行时命名空间：`dp-`
- 当前 B480 测试 JAR：
  `<path-to-v159.7-B480-server.jar>`

`--server-jar` 不会鉴定 JAR commit；调用者必须提供可信、匹配的版本。当前 JAR 带 MDT/MindustryX 日志，与官方纯净 JAR 仍可能存在差异。

## 重要路径

- 工程根目录：
  `<repository-root>`
- Mindustry 源码硬参考：
  `Mindustry-v159.7-source`
- MDT 服务器与脚本：
  `<local-mindustry-server>`
- external CP/DP 语料：
  `<local-external-cp-corpus>`
- Saturation Firepower：
  `<local-Saturation-Firepower-source>`

父仓库包含大量与本项目无关的修改。不得清理、覆盖或提交无关文件。

## 技术栈和构建约束

- Kotlin/JVM
- JDK 21 构建，Java 17 字节码目标
- Gradle 9.2.1 / Kotlin DSL
- JavaParser（Java AST）
- Picocli
- HJSON Java
- kotlinx.serialization
- JUnit 5
- JDK 内置 `HttpServer`、Server-Sent Events、原生 HTML/CSS/JavaScript（Web UI）
- GPLv3

路径含中文，必须优先使用 ASCII Junction 包装脚本：

```powershell
.\scripts\gradle.ps1 build --no-build-cache --no-daemon
```

不要把直接运行 `gradlew.bat test` 作为首选；Gradle Test Worker 的 classpath argfile 曾在中文路径下损坏。

## 模块职责

- `bridge-model`：报告、清单、诊断、验证阶段、输出和 `fileResults/contentResults` 模型。
- `bridge-target-api`：目标版本验证器接口。
- `bridge-java-static`：不执行输入代码的 Java AST 扫描、受限求值、内容对象图导出、显式降级与逐项诊断。
- `bridge-converter`：安全读取、输入识别、HJSON/CP 解析、Java exporter 接入、命名空间重写、资源检查和打包。
- `bridge-target-1597`：v159.7 结构验证、正式 DataPatcher apply harness 和服务器文件发现。
- `bridge-cli`：命令行、日志、报告合并、失败报告和可选 B480 验证。
- `bridge-web`：HTTP API、上传与任务队列、独立 CLI 子进程、SSE、产物/日志下载和嵌入式 Web UI。

## 当前转换行为

### 输入识别

1. 有 `mod.hjson/mod.json`、`assets/`、源码或 JAR 时按普通 Mod 处理。
2. 单文本文件且根对象包含 item/block/liquid/status/unit/weather 时按 legacy CP 处理。
3. 根目录含 `content/patches/bundles/sprites/sounds/music` 时按已有 DP 处理。

### 普通数据 Mod

- JSON/HJSON content 和 patch 会解析、递归移除 `research` 并规范化输出。
- `ModNamespaceRewriter` 依据实际内容和资源符号表，将可证明的内容、patch、bundle、sprite、sound、music 和 generated 引用迁移到 `dp-`。
- 不做描述文本全文替换；无法解析的明显原 Mod 引用会形成 error。

### Java Mod 静态导出

- 使用 JavaParser 建立 AST；不编译、不实例化、不执行输入类。
- 当前必须在输入中看到 `.java` 源码；不对仅含 `.class` 的发布 JAR 做字节码反编译。缺少源码时可复制的资产仍会输出，但未接管的可执行代码必须形成 `MOD_CODE_NOT_EXECUTED`/error。
- 当前可处理内容声明、匿名初始化器、局部常量、算术/布尔/颜色表达式、内容引用、数组/Seq/Map、requirements、consume、ammo、plans/upgrades，以及大量原版 Weapon/Bullet/Ability/Effect/Draw/Part/Shoot 构造器和字段。
- 支持受限确定性 `for` 循环展开和对已生成 Content 的跨语句字段赋值。
- 支持 `Weapon.copy/copyRotate/copyRotRel` 等已知复制模式。
- 对自定义 Block/Bullet 使用显式内置类型降级；Java-only 字段和方法明确报告。
- 自定义 Effect factory 或 lambda 无等价数据表达时替换为 `Fx.none`，并标记 degraded。
- v159.7 DataPatcher 不接受的字段在输出前删除并报告，避免把已知 parser warning 留到运行时。
- 运行期随机数不能复现时可采用确定性中点近似，并产生 `JAVA_RANDOM_EXPRESSION_APPROXIMATED`。
- 未求值表达式不会静默忽略，产生精确源位置和字段诊断。

### 已有 DP

- content/patch 文本会解析并做静态引用检查，但保持原始字节，避免改变 generated content hash。
- ZIP 外层以稳定顺序重打包；不要求整个 ZIP 与输入逐字节相同，但被保留条目必须一致。
- 已有 DP 不执行普通 Mod namespace 迁移；错误引用由验证器报告。

### Legacy CP

- 输出 `patches/<slug>.hjson`，递归移除 `research`。
- 原生 HJSON 失败后可尝试有限旧语法修复，包括部分 `+=`、未引号键和裸 token。
- 使用兼容模式会产生 warning，不能宣称覆盖全部社区 CP 方言。

### 资源

- PNG 和受支持路径中的音频文件按二进制复制；当前不做客户端解码、atlas 构建、音频转码或播放验证。
- 同 basename PNG 字节相同则确定性去重并报告，不同则拒绝碰撞。
- sound/music 共享 v159 音频 namespace，冲突会被拒绝。
- bundle key 对普通 Mod 执行符号驱动重写。
- `sprites-override`、maps、Planet/Sector 和非目标代码按政策排除并报告。

## 报告与阶段语义

报告包含 target、source、status、summary、inventory、`fileResults`、`contentResults`、diagnostics、validationStages、outputs 和 metadata。

Content 结果：

- `CONVERTED`：可加载 HJSON 已生成，未发现该 Content 的已知语义降级。
- `DEGRADED`：可加载 HJSON 已生成，但至少有 Java-only 行为、字段或近似替换。
- `EXCLUDED/UNSUPPORTED/FAILED`：分别表示产品排除、无法表达或转换失败。

阶段含义：

- `STRUCTURE`：目录、扩展名、根类型、名称冲突和静态引用检查。
- `RUNTIME`：真实执行 B480 `DataManager.load` / `DataPatcher.apply`。
- `MAP_IMPORT`：真实 Desktop 编辑器导入；自动 harness 当前不运行。
- `SERVER_LOAD`：真实服务器加载携带 DP 的地图/存档；普通冷启动不计入。

普通服务器日志 `Loaded N data asset files.` 只表示发现文件，不能冒充 DataPatcher apply 或地图加载成功。

Web API 的 HTTP 2xx、健康检查或作业 `succeeded` 也不能冒充 Desktop/服务器地图验证；必须继续查看 `report.json` 的转换状态和 validation stages。

## 当前最终自动化事实

最新 Saturation 产物：

`work/saturation-outlinefix-final-20260730-075936`

| 指标 | 结果 |
|---|---:|
| 扫描源文件 | 1764 |
| 根 Content HJSON | 358 |
| 外部 bundle/sprite/audio/generated 资产 | 2276 |
| Data Assets 合计 | 2634 |
| converted | 295 |
| degraded | 63 |
| excluded / unsupported / failed Content | 0 / 0 / 0 |
| report info / warning / error | 139 / 64 / 0 |
| 离线生成 full icon / outline | 87 / 326 |
| 离线生成总数 / 明确缺失项 | 413 / 152 |
| B480 apply failed | 0 |
| B480 apply warnings | 0 |
| B480 added content | 689 |

验证阶段：

- `STRUCTURE = PASSED`
- `RUNTIME = PASSED`
- `MAP_IMPORT = NOT_RUN`
- `SERVER_LOAD = NOT_RUN`

`added content = 689` 是 apply 后加入 `Vars.content` 的对象计数，包含嵌套 Bullet 等，不等于 358 个根 HJSON 文件。

Java 静态导出关键事实：

- 358/358 目标玩法 Content 均生成：15 Item、6 Liquid、22 Status、63 Unit、252 Block。
- 4 个内嵌 `MissileUnitType` 已提升为独立 Unit：`knocker-missile`、`blade-missile`、`sundown-missile`、`defense-platform-nuke-missile`。
- 7 个确定性 `for`/嵌套循环已静态展开。
- 5 个对已生成 Content 的跨语句赋值已应用。
- 一处加载期随机数被确定性近似为中点 5，并明确报告。
- `SFBlocks.tieliu` 的 `fragBullet = ((LiquidTurret) Blocks.tsunami).ammoTypes.get(Liquids.slag)` 已用固定目标 v159.7 的 `tsunami + slag` 内置对象快照恢复，并报告 `JAVA_VANILLA_OBJECT_SNAPSHOT_APPLIED`；转换过程没有加载或执行游戏/输入 Mod。
- 当前 `JAVA_FIELD_EXPRESSION_OMITTED = 0`。这不表示没有语义降级；63 个 degraded 仍包含自定义 Java Block/Bullet 行为、状态回调、lambda/自定义 Effect、随机数中点近似和 v159.7 不接受字段等明确损失。
- 10 个音频资产的文件名均以 `.ogg` 结尾，但文件头审计为 5 个 OGG、3 个 MP3 容器、2 个 WAV 容器；5 个不匹配文件现在逐项报告 `AUDIO_CONTAINER_EXTENSION_MISMATCH`，输出仍保持原名/字节且不转码，Desktop 解码和播放待验证。
- 额外本机 QA：原始 1616 个 PNG 全部通过 Pillow `verify()`，10 个音频全部可由 PyAV 解码至少一帧；最终 ZIP 共含 2264 个 PNG 条目，其中新增 413 张离线组合/描边图。该检查不代表 Mindustry Desktop atlas/Arc/SoLoud 已全部通过。v159.7 源码中 `DataAudioLoader` 对内容寻址、无扩展名的缓存文件调用 `Sound.createStream` / `Music.create`，Java 层不会仅根据原 `.ogg` 后缀选择解码器。

当前测试汇总为 59 项全部通过：

- `bridge-model`：5
- `bridge-target-api`：1
- `bridge-target-1597`：7
- `bridge-converter`：18
- `bridge-java-static`：22
- `bridge-web`：6（Host/跨站拒绝与静态页面；真实 multipart → 独立 CLI → 结果、报告、日志端到端；准备失败必须进入终态且运行中产物不可提前下载；取消运行中任务会终止进程树，并且只开放已完成的日志归档；上传预约失败会回滚容量；并发 SSE 事件序号保持单调）

## 已有真实 Desktop 事实

- minimal 普通 Mod：Desktop 导入 9 个资源；`dp-fixture-wall`、`dp-fixture-drone`、`dp-fixture-alloy` 正确注册。
- 同一测试出现的 4 个额外星球标签和原版 `heat-source`，经源码核查是未指定 `shownPlanets` 触发的数据库展示副作用，不是 DP 注册了 Planet 或新方块。
- `惊鸿3.zip`：Desktop 导入 17 个资源；惊鸿炮塔正确注册，用户确认所测核心逻辑正常；输入/输出 17 个条目字节保持一致。
- Saturation 上一候选包 `saturation-clientfix-20260730-070717`：用户确认能够正确导入并进入地图；大部分炮塔贴图正常，基础玩法达到可接受水平。随后反馈的建造栏组合图、构件大小写和描边问题已在当前候选修复，但当前候选尚未复测。

上一候选已证明 Saturation 的内容主干可以由 Desktop 导入；该事实不能自动外推为当前新增 413 张离线生成贴图也全部正确。

## 当前不能声称的内容

即使 Saturation 的 B480 apply 为 0 failed / 0 warnings，也只证明 HJSON 注册和引用解析通过。当前没有证据证明：

- 当前最终候选已由 Desktop 成功导入；
- 当前最终候选的全部 atlas region/generated sprite、单位/炮塔描边和建造栏 full icon 均正确；
- OGG/MP3 可解码并在正确时机播放；
- 地图保存、完全退出客户端、重开后仍能恢复内容；
- 关键单位、炮塔、工厂、Consume、AI、Effect 和状态行为符合原 Mod；
- B480 服务器已加载携带该 DP 的真实地图或存档；
- 多人客户端与服务器同步通过。

因此当前准确状态仍是 **PARTIAL，但已达到可交付真实客户端测试的高价值阶段**。

## 恢复开发时的优先顺序

1. 查看当前源码和 `git status`，不要处理父仓库无关修改。
2. 使用 `scripts/gradle.ps1` 运行完整构建，并确认 59 项测试无失败。
3. 以 `work/saturation-outlinefix-final-20260730-075936/report.json`、`report.md` 和验证日志作为当前基线。
4. 审查 63 个 degraded Content，区分仍可新增确定性映射的遗漏与 DP 运行时本就无法表达的 Java 行为；固定原版对象快照必须绑定 v159.7 并可审计。
5. 需要判断贴图、音效、地图持久化或玩法时停止静态推测，按 `docs/TESTING.md` 交给真实 v159.7 Desktop 测试。
6. Desktop 通过后保存并导出测试地图，再由匹配 B480 服务器实际加载该地图；在此之前 `SERVER_LOAD` 必须保持 `NOT_RUN`。
7. 修改或部署 Web UI 前阅读 `docs/WEB_UI.md`；保持默认回环监听，远程访问必须配置 `MOD_DP_BRIDGE_ALLOWED_HOSTS`，并通过带 TLS、认证、请求限制且保留原始 `Host` 的反向代理。
