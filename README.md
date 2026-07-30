# mod-dp-bridge

将 Mindustry Mod、旧式 CP 和已有 Data Pack **尽力静态迁移**为 Mindustry **v159.7 / B480 Data Assets（DP）**。

> [!IMPORTANT]
> 本项目目前是实验性 CLI，目标版本固定为 **v159.7 / B480**。转换成功表示生成了可审计、可继续测试的 DP 候选，**不表示原 Mod 的全部 Java 行为已被等价还原**。使用前请阅读生成的 `report.md`，正式部署前请在真实客户端和服务器地图中验证。

项目坚持三个原则：

1. **尽力转换地图玩法内容**：物品、流体、状态、单位、方块、炮塔、工厂、武器、弹药、特效以及相关贴图和音频；
2. **不执行输入 Mod**：不编译、不加载、不反射、不运行输入中的 Java、JavaScript、Kotlin、Gradle 或 Maven 代码；
3. **不静默丢弃**：所有转换、降级、排除、不支持和失败项都写入日志及 JSON/Markdown 报告。

本项目不属于 Mindustry 官方项目，也不保证任意 Mod 都能无损转换。

## 当前能力

| 能力 | 当前状态 |
|---|---|
| 输入目录、ZIP/JAR、JSON/HJSON/JSON5 | 支持 |
| 普通声明式 Mod | 支持 |
| 已有 v159 DP 重打包/检查 | 支持，并尽量保持 content/patch 原始文本字节 |
| 旧式 CP/PatchSet | 支持原生 HJSON；部分社区旧语法会尝试兼容修复并警告 |
| Java Mod | 支持**带 `.java` 源码**的确定性 AST 静态导出 |
| Item、Liquid、Status、Unit、Block | 重点支持 |
| Weapon、Bullet、Ability、Effect、Draw/Part/Shoot、Consume | 在 v159.7 原版 `ClassMap` 可表达范围内尽力转换 |
| bundle、sprite、sound、music | 复制、改名、引用检查；不自动转码音频 |
| 单位/炮塔描边、炮塔及多层工厂建造栏组合图 | 确定性离线生成；不能生成的项目会集中报告 |
| v159.7 结构验证 | 自动执行 |
| B480 `DataManager.load` / `DataPatcher.apply` | 提供可信 Server JAR 后可选执行 |
| Web UI / HTTP API | 暂无，待 CLI 核心稳定后再考虑 |

### Java 静态导出

Java 源码只会被 JavaParser 解析为 AST。转换器对受限、可证明的纯数据表达式求值，并生成 HJSON；不会使用输入 Mod 的 classpath，也不会调用其中的 helper 实现。

当前能够处理的典型结构包括：

- 根 Content：`Item`、`Liquid`/`CellLiquid`、`StatusEffect`、`UnitType`、`Block`；
- 嵌套对象：Weapon、Bullet、Ability、Effect、Draw、Part、Shoot、Consume、常见 AI/计划/Stack/Map/List；
- 字面量、常量、安全算术和布尔表达式、Color、内容/资产引用；
- 匿名 initializer、方法局部常量、链式赋值、常见 builder 和 copy helper；
- requirements、consume、ammo、plans、upgrades；
- 内嵌 `UnitType`/`MissileUnitType` 提升为独立 unit content；
- 受限的确定性 `for` 展开：单循环最多 64 次、单 Content 总预算 4096 次、最多 8 层嵌套。

自定义类字段、方法覆写、lambda/callback、运行时随机逻辑和特殊 Effect 只有在存在明确规则时才会降级为原版可表达形式，否则会被标为 `degraded`、`unsupported` 或错误。

> 仅含 `.class` 的发布 JAR 不会被反编译。此类输入仍可迁移其中的声明式 HJSON、贴图和音频，但无法从字节码恢复的可执行 Content 会明确报错，而不会伪装为已转换。

## 项目边界

### 重点迁移

- 物品、流体、状态效果；
- 单位、武器、弹药、Ability 和 AI 的原版可表达字段；
- 炮塔、工厂、运输、电力、存储、防御、生产、环境和地形方块；
- Draw、RegionPart、Shoot、Effect 等 DP 可表达对象；
- bundle、普通/generated sprite、音效和音乐；
- Mod 运行时名称到 `dp-` 命名空间的内容及资产引用迁移；
- 单位/炮塔 outline、炮塔/多层工厂 full icon 等必要客户端资源。

### 明确不转换

- 科技树和 `research`；
- Planet、Sector 与 Mod 自带地图；
- GUI、自定义客户端界面；
- 网络协议和多人同步代码；
- JavaScript/Kotlin/Java 的任意运行时代码、反射及原生库；
- `scripts`、`sprites-override`；
- 无法由 v159.7 Data Assets 原生模型表达的完全自定义行为。

“不转换 Mod 自带地图”不影响使用生成的 DP：你仍可在 v159.7 地图编辑器中把 DP 导入现有或新建地图。

### 版本含义

工具会尝试读取面向 Mindustry 146 及以上版本制作的 Mod，但当前只有一个输出适配器：**v159.7 / B480**。它不是 146–158 的历史运行时模拟器，也不会自动修复所有旧 API 差异。输入越接近 v159、越依赖原版 Content 字段，成功率通常越高。

## 环境要求

- JDK 21（构建工具链；生成字节码兼容 Java 17）；
- Git；
- 首次构建时可访问 Maven Central 和 Gradle 分发服务；
- 可选：可信的 Mindustry v159.7/B480 Server JAR，用于真实 parser/apply 验证。

## 获取与构建

```bash
git clone https://github.com/QQQW114/mod-dp-bridge.git
cd mod-dp-bridge
```

Windows PowerShell：

```powershell
.\scripts\gradle.ps1 build :bridge-cli:installDist
```

`scripts/gradle.ps1` 会通过 ASCII Junction 运行 Gradle，用于规避 Windows 下工程路径含中文时 Test Worker classpath 参数文件的编码问题。若路径只含 ASCII，也可直接运行：

```powershell
.\gradlew.bat build :bridge-cli:installDist
```

Linux/macOS：

```bash
./gradlew build :bridge-cli:installDist
```

CLI 安装目录：

```text
bridge-cli/build/install/bridge-cli/
```

## 快速使用

### 1. 转换

Windows：

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat convert `
  "<Mod目录、ZIP/JAR、CP.hjson或已有DP.zip>" `
  -o ".\out\my-conversion"
```

Linux/macOS：

```bash
./bridge-cli/build/install/bridge-cli/bin/bridge-cli convert \
  "<input>" \
  -o "./out/my-conversion"
```

输出目录已存在且允许替换本工具生成的同名产物时，显式加入 `--overwrite`：

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat convert `
  ".\MyMod-main" `
  -o ".\out\my-mod" `
  --name "my-mod" `
  --overwrite
```

### 2. 可选：使用真实 B480 JAR 验证

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat convert `
  ".\MyMod-main" `
  -o ".\out\my-mod" `
  --overwrite `
  --server-jar "C:\path\to\trusted-v159.7-B480-server.jar" `
  --server-timeout 60
```

该选项会在隔离临时目录和独立 JVM 中运行项目内置的固定 harness，真实调用 B480 的 `DataManager.load` / `DataPatcher.apply`。它仍然**不会加载携带该 DP 的地图或存档**。

> [!CAUTION]
> 输入 Mod 始终只按数据读取；但 `--server-jar` 指定的 JAR 会被 Java 执行。只可使用你信任且来源明确的 Server JAR。

### 3. 审阅报告

至少检查：

- `report.md` 中所有 `degraded`、`unsupported`、`failed`；
- `diagnostics` 中的 `warning` 和 `error`；
- 是否存在缺失 sprite/audio、命名冲突、Java 行为降级；
- `STRUCTURE` 和可选 `RUNTIME` 阶段是否通过。

### 4. 导入与部署

- **客户端**：在精确的 v159.7/B480 Desktop 地图编辑器中，通过 Data Assets/DP 导入功能选择生成的 `*-dp-v159.7.zip`。不要把它当作 Java Mod 安装。导入后保存地图，完全重启客户端，再重开地图验证。
- **服务器**：`server-assets/` 是 ZIP 的展开形式。备份服务器数据后，将其**内部内容**按原目录结构合并到目标服务器工作目录的 `config/assets/`，再让服务器实际加载携带这些 Data Assets 的地图/存档。

正式验收至少应覆盖：内容注册、建造栏图标、单位/炮塔/工厂行为、贴图与音效、地图保存重开、服务器真实地图加载和客户端连接。

## CLI 选项

查看实时帮助：

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat convert --help
```

| 选项 | 默认值 | 说明 |
|---|---:|---|
| `INPUT` | 必填 | 输入目录、ZIP/JAR、JSON/HJSON/JSON5 文件 |
| `-o, --output <dir>` | `./out/<input-name>` | 输出目录 |
| `--name <name>` | 从输入推导 | 覆盖输出 ZIP 的基础名称 |
| `--overwrite` | `false` | 允许替换已有的同名生成产物 |
| `--max-input-mib <n>` | `64` | 输入文件/压缩包最大 MiB |
| `--max-expanded-mib <n>` | `128` | 压缩包展开后最大总 MiB |
| `--max-entries <n>` | `2048` | 压缩包最大条目数 |
| `--server-jar <jar>` | 不启用 | 使用可信 v159.7/B480 Server JAR 执行隔离验证 |
| `--server-timeout <sec>` | `30` | 每个 Mindustry 验证进程超时秒数 |
| `-h, --help` | — | 显示帮助 |
| `-V, --version` | — | 显示版本 |

## 输出内容

```text
<output>/
├─ <name>-dp-v159.7.zip       # 可由地图编辑器导入的无外层目录 DP ZIP
├─ server-assets/             # 展开的 HJSON 与资源树，可部署到 config/assets
├─ report.json                # 机器可读的完整转换事实
├─ report.md                  # 人工审阅报告
└─ logs/
   ├─ conversion.log          # 完整转换日志
   ├─ data-patch-apply.log    # 仅使用 --server-jar 时生成
   └─ server-asset-discovery.log
```

若转换在标准报告生成前失败，输出目录会包含 `failure-report.txt`，存在结构化诊断时还会写出 `failure-diagnostics.json`。

ZIP 条目顺序和时间戳固定；报告会记录产物大小、SHA-256 和展开资源树 hash，便于复现与审计。

## 如何理解报告

### 总体状态

| 状态 | 含义 |
|---|---|
| `SUCCESS` | 保留给未来“所有必需验证均完成”的结果；当前 CLI 通常不会给出此状态 |
| `PARTIAL` | 已生成候选产物，但存在降级或仍需 Desktop/服务器地图人工验证 |
| `REJECTED` | 结构或 runtime apply 发现不可接受错误；产物只能用于排错 |
| `FAILED` | 转换流程未能完成 |

### Content 结果

- `converted`：已生成可表达的数据内容，未发现该声明的已知语义损失；
- `degraded`：已生成可加载候选，但一个或多个源行为被替换、近似或删除；
- `excluded`：按项目范围主动排除；
- `unsupported`：当前实现或 DP 模型无法转换；
- `failed`：该声明转换失败。

### 文件结果

- `copied`：按字节复制；
- `converted`：发生规范化、内容导出、命名空间或路径重写；
- `excluded` / `unsupported` / `failed`：原因和关联诊断码会逐文件列出。

### 验证阶段

| 阶段 | 自动化程度 |
|---|---|
| `STRUCTURE` | 自动检查 v159.7 路径、扩展、内容类型、basename 和 generated 规则 |
| `RUNTIME` | 仅在指定 `--server-jar` 时真实执行 B480 parser/apply |
| `MAP_IMPORT` | 当前必须在真实 Desktop 人工验证 |
| `SERVER_LOAD` | 当前必须让真实服务器加载携带 DP 的地图/存档 |

**退出码 `0` 不等于无损转换。**它只表示当前静态检查及已启用的 apply 验证没有发现 error；只要地图导入或服务器地图加载尚未验证，报告仍可保持 `PARTIAL`。

CLI 退出码：

| 退出码 | 含义 |
|---:|---|
| `0` | 产物已生成，未发现静态/apply error |
| `2` | 产物已生成，但报告为 rejected 或包含必须处理的 error |
| `3` | 输入被拒绝或转换无法生成标准产物；查看 failure report |
| `4` | 未预期的内部错误 |

## Saturation Firepower 实测

[RA2EXE/Saturation-Firepower](https://github.com/RA2EXE/Saturation-Firepower) 是当前高价值回归样本。针对其带源码的 Java Mod 工程，当前版本得到：

| 指标 | 结果 |
|---|---:|
| 根 Content | 358 |
| `converted` / `degraded` | 295 / 63 |
| Content `unsupported` / `failed` | 0 / 0 |
| 外部资产 | 2276 |
| Data Assets 总数 | 2634 |
| B480 DataPatcher apply | 0 failed，0 warning |
| 离线生成 full icon / outline | 87 / 326 |
| 明确报告的离线图标缺失/不支持项目 | 152 |
| 自动化测试 | 53 passed，0 failed |

真实 B480 Desktop 已能导入生成 ZIP 并进入地图；最新测试反馈中，大部分单位、炮塔、工厂、建造栏组合图和描边已达到预期。该证据说明项目已能处理大型典型 Java Mod 的大部分原版可表达数据，但 **63 个 degraded Content 不等于原 Java 专用行为已被还原**，服务器真实地图加载仍需继续验证。

完整进度和技术记录见：

- [项目状态](docs/PROJECT_STATUS.md)
- [架构设计](docs/ARCHITECTURE.md)
- [测试与人工验收](docs/TESTING.md)
- [Java → DP 映射规则](docs/JAVA_TO_DP_MAPPING_V1597.md)
- [B480 DataPatcher 验证语义](docs/DATA_PATCH_APPLY_VALIDATION.md)
- [B480 客户端导入与已知问题](docs/B480_CLIENT_IMPORT_FIX_20260730.md)

## 已知局限

1. **仅针对 v159.7/B480。**未来其他版本应使用独立 target adapter、ClassMap 和 runtime 验证，不能直接复用并宣称兼容。
2. **DP 不是 Java 运行时。**自定义类、回调、方法覆写、脚本和完全自定义特效无法通用还原。
3. **源码决定 Java 转换上限。**仅含 class 的 JAR 不进行字节码反编译。
4. **Headless apply 不验证客户端表现。**它不会完整构建 Desktop atlas、播放音频或测试真实地图持久化。
5. **音频不自动转码。**扩展名与容器不一致时只警告并保留原字节，避免不可控质量和许可变化。
6. **已有 DP 的文本尽量保持字节。**这是为了保护 generated content hash；普通 Mod 因重写命名空间必须规范化文本，仍需客户端检查 generated 资源。
7. **B480 存在上游退出崩溃。**大型/多页 Data Assets 场景在退出地图或无核心编辑器地图退出时，可能触发 `DataImagePacker.unload()` 的 `key cannot be null`。该问题无法在不破坏资产的前提下由纯 DP 修复，应由客户端上游修补；详见上述 B480 客户端文档。

## 安全模型

- 拒绝绝对路径、`..`、ZIP Slip、异常长路径和危险归档结构；
- 限制输入大小、条目数、单条目/总展开大小和压缩比；
- 输入 Mod 的源码、class、脚本和构建文件永不执行；
- Java 语义仅通过确定性 AST 规则解释；
- 输出使用确定性路径排序、固定 ZIP 时间戳和 hash；
- 只有用户显式提供的 `--server-jar` 会被执行，必须视为受信任程序。

报告和日志可能包含输入名称及本机绝对路径。公开提交 Issue 前请先检查并脱敏；也请确认你有权分享输入 Mod、日志和转换后资产。

## 开发

模块划分：

| 模块 | 职责 |
|---|---|
| `bridge-model` | 报告、诊断、文件/Content 结果和验证阶段模型 |
| `bridge-java-static` | JavaParser AST 静态导出与降级记录 |
| `bridge-target-api` | 目标版本验证接口 |
| `bridge-converter` | 安全读取、输入识别、转换规划、命名空间、资产检查、打包 |
| `bridge-target-1597` | v159.7 结构检查与 B480 隔离验证 |
| `bridge-cli` | Picocli 命令、日志、报告合并和退出码 |

运行测试：

```powershell
.\scripts\gradle.ps1 test
```

提交问题时，建议附上：工具版本、目标 Mindustry build、CLI 命令、`report.json`、`report.md`、`logs/`、客户端/服务器日志，以及最小可复现输入；上传前请先脱敏并确认输入许可。

## 额外声明

本项目为vibe coding产物，使用模型为gpt5.6-sol，初版为8小时内完成开发，且仅以饱和火力为参照mod，实际对于其他mod的转换产物可用性未进行验证，项目当前可能存在较多bug，请积极提交

## 许可证

本项目以 [GNU General Public License v3.0](LICENSE) 发布。

第三方组件、Mindustry v159.7 参考资源及测试语料边界见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

- 转换器的许可证不会替你重新许可输入 Mod；
- 转换产物中的源码、贴图、音频及其他资产仍受原作者许可证和相关权利约束；
- 使用、分发或公开托管转换产物前，请自行确认原 Mod 许可证允许相应行为；
- `bridge-converter/src/main/resources/io/github/moddpbridge/converter/mindustry-v159/turret-bases/` 中的 v159.7 炮塔底座参考 PNG 来源于 [Anuken/Mindustry](https://github.com/Anuken/Mindustry)，并依其 GPLv3 条款使用和分发。

完整的第三方组件版本、来源、许可证选择及本地参考样本边界见
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
