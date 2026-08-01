# mod-dp-bridge

将 Mindustry Mod、旧式 CP 和已有 Data Pack **尝试并尽力迁移**为 Mindustry **v159.7 / B480 Data Assets（DP）**。

当前主线同时提供本地 CLI 和仅监听 loopback 的本地 Web UI。对可信的已编译 Java Mod，工具使用官方 Mindustry v159.7 独立 JVM 真实加载 Mod，读取实际注册的 Content，再结合可选源码 AST 候选和官方 `DataPatcher.apply` 筛选，生成可审计的 DP；声明式 Mod、CP 和已有 DP 仍可使用不执行输入代码的静态模式。

> [!IMPORTANT]
> 本项目是实验性转换工具，旨在帮助DP作者开发，不要相信其转换稳定性。转换成功只表示产物通过了当前自动检查，不表示 Java Mod 的全部行为已经无损还原。正式使用前必须审阅 `report.md`、`runtime-mapping.json`、`hybrid-report.json` 和日志，一定在真实客户端地图及服务器中测试。

本项目不属于 Mindustry 官方项目，也不保证任意 Mod 都能无损转换。

## 当前设计

```text
可信发布 JAR
  -> 官方 Mindustry v159.7 独立 JVM 真实加载
  -> Vars.content 三阶段快照
  -> Item / Liquid / StatusEffect 动态映射
  -> 可选源码 AST 生成 Block / Unit 候选
  -> 官方 DataPatcher 单调筛选候选
  -> 从发布 JAR 选择贴图、音频、bundle 等资产
  -> 生成 DP ZIP 与 server-assets
  -> 再次执行官方 DataPatcher.apply 最终验证
```

权威关系：

- **发布 JAR**：实际注册 Content、运行时类型及资产的权威来源；
- **源码目录/ZIP**：只作为非执行的静态候选来源，不运行 Gradle、Maven、Java、Kotlin 或脚本；
- **官方 v159.7 Server JAR**：负责真实 Mod 加载和最终 DP parser/apply 验证；
- **报告与日志**：明确记录接受、降级、排除、不支持、失败和筛选剔除项。

项目不对发布 JAR 做通用反编译。

Web UI 只是这两种 CLI 模式之上的任务管理层。运行时能力默认关闭；启用后仍只允许本机 loopback 使用，如你想要部署网站，需改动项目并自行负责安全问题。

## 两种转换模式

### 1. `runtime-convert`：编译 Java Mod 主线

适用于已经发布的 Java Mod JAR。必须同时提供固定版本的官方 Mindustry v159.7 Server JAR，并显式确认会执行 Mod。

| 输入 | 是否必需 | 用途 |
|---|---:|---|
| 已构建 Mod `.jar` | 是 | 真实加载、内容注册和资产权威 |
| 官方 Mindustry v159.7/B480 Server `.jar` | 是 | 加载 Mod、验证生成 DP；SHA-256 必须匹配项目固定值 |
| 对应源码目录或源码 `.zip` | 否 | 为运行时确认的 Block/Unit 提供 AST 数据候选 |

当前动态映射重点生成：

- Item；
- Liquid / CellLiquid；
- StatusEffect。

提供对应源码时，还会尝试补充：

- Block，包括炮塔、工厂、生产、运输、电力、防御及环境方块；
- Unit；
- 上述根 Content 中由 v159.7 Data Assets 可表达的 Weapon、Bullet、Ability、Effect、Draw、Part、Shoot、Consume 等嵌套数据。

源码候选只有在满足运行时注册、名称、kind、parser fallback、JAR class/源码行号来源约束，并通过官方 DataPatcher 零失败、零警告筛选后才会进入最终 DP。被接受的内容仍标为 `degraded`：parser 可加载不代表方法覆写、回调和自定义 Java 行为已迁移。

### 2. `convert`：安全静态模式

适用于：

- JSON/HJSON/JSON5 声明式 Mod；
- 源码目录或源码 ZIP 的确定性 Java AST 尽力导出；
- 旧式 CP/PatchSet；
- 已有 v159 DP 的检查与重打包。

此模式不执行输入 Mod。对于只含 `.class`、没有对应源码的 Java JAR，它不能恢复其中的可执行内容；此类输入应优先使用 `runtime-convert`。

## 项目边界

### 尽力迁移

- 物品、流体、状态效果；
- 单位、武器、弹药、Ability 和原版可表达 AI 字段；
- 炮塔、工厂、生产、运输、电力、存储、防御、环境和地形方块；
- Draw、RegionPart、Shoot、Effect 等可由 v159.7 Data Assets 表达的对象；
- bundle、普通/generated sprite、音效和音乐；
- 单位/炮塔描边及炮塔、工厂建造栏组合图；
- 内容和资产引用到 `dp-` 命名空间的迁移。

### 放弃

- 科技树和 `research`；
- Planet、Sector 和 Mod 自带新地图；
- GUI、自定义客户端界面；
- 自定义网络协议；
- Java/Kotlin/JavaScript 的任意运行时回调、反射、原生库和完全自定义逻辑；
- 无法由 v159.7 Data Assets 模型表达的自定义实体、绘制和特效行为。

“不转换 Mod 自带地图”不影响 DP 的使用：可把生成的 DP 导入已有地图或新建地图。

### 版本边界

- 开发目标：**Mindustry v159.7 / Build 480**；
- 工具可尝试读取面向 146 及以上版本的输入，但没有 146–158 历史运行时模拟器；
- 原 Mod 若本身无法在官方 v159.7 加载，`runtime-convert` 也无法绕过其 API/依赖不兼容；
- 非官方 MindustryX/MDT Server JAR 不作为当前动态主线的验证依据。

## 安全警告

> [!CAUTION]
> `runtime-convert` 会在独立 JVM 中真正执行所提供的 Mod JAR 和官方 Server JAR。独立进程并非安全沙箱：其仍拥有当前用户可用的文件、网络和系统权限。需确认只处理信任的 JAR，请不要不对本项目做更改便直接部署网站。

同一警告适用于本地 Web UI 的运行时模式：浏览器选择 JAR 后，本机 Web 服务会以其所属系统用户的权限执行该 JAR。取消任务、超时、临时目录和进程树终止只用于故障隔离，不能约束恶意代码。

安全措施包括：

- 必须显式传入 `--allow-mod-execution`；
- 官方 Server JAR 必须匹配固定 SHA-256；
- Mod 加载、DP 筛选和最终验证均在独立 JVM/临时目录中运行；
- extractor 设置内存、时间、内容记录、字段、容器和总快照字节预算；
- 输入归档拒绝绝对路径、`..`、ZIP Slip、异常路径、超大条目和过高压缩比；
- 源码只读取 Java 文本并解析 AST，不运行构建系统；
- 运行前后校验 Mod JAR 与 Server JAR 指纹，防止处理中被替换；
- Web 运行时模式要求操作员预先启用，并要求每个作业再次确认信任；
- Web 仅允许 loopback，本身没有认证、授权或租户隔离，禁止暴露到公网或受信任内网供他人上传。

## 环境要求

- JDK 21（构建；产物目标 Java 17）；
- Git；
- 首次构建时可访问 Gradle 分发和 Maven Central；
- 官方 Mindustry v159.7/B480 Server JAR。

项目固定的官方 Server JAR SHA-256：

```text
E41289C32BCF765EB50FA131E6B515D741E20F7843FB567D3AA949E7461F22AB
```

## 构建

```powershell
git clone https://github.com/QQQW114/mod-dp-bridge.git
cd mod-dp-bridge
.\scripts\gradle.ps1 :bridge-cli:installDist :bridge-web:installDist --no-daemon
```

Windows 中文路径下应优先使用 `scripts/gradle.ps1`。该脚本通过 ASCII Junction 运行 Gradle，避免 Test Worker classpath 参数文件编码问题。

CLI 安装目录：

```text
bridge-cli/build/install/bridge-cli/
```

Web 安装目录：

```text
bridge-web/build/install/mod-dp-bridge-web/
```

构建 0.2.0 发布压缩包：

```powershell
.\scripts\gradle.ps1 test :bridge-cli:distZip :bridge-web:distZip --no-daemon
```

输出位于：

```text
bridge-cli/build/distributions/bridge-cli-0.2.0.zip
bridge-web/build/distributions/mod-dp-bridge-web-0.2.0.zip
```

发布包不会捆绑 Mindustry Server JAR；运行时用户必须自行提供并通过固定 SHA-256 校验。

## 使用方法

### 编译 Java Mod：JAR + 可选源码

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat runtime-convert `
  --mod-jar "C:\path\to\ExampleMod.jar" `
  --source "C:\path\to\ExampleMod-source.zip" `
  --server-jar "C:\path\to\Mindustry-v159.7-server.jar" `
  --allow-mod-execution `
  --runtime-timeout 180 `
  --server-timeout 60 `
  --hybrid-max-rounds 8 `
  -o ".\out\example-runtime" `
  --overwrite
```

没有源码时删除 `--source`：

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat runtime-convert `
  --mod-jar "C:\path\to\ExampleMod.jar" `
  --server-jar "C:\path\to\Mindustry-v159.7-server.jar" `
  --allow-mod-execution `
  -o ".\out\example-runtime"
```

查看完整帮助：

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat runtime-convert --help
```

### 声明式 Mod、CP 或已有 DP

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat convert `
  "C:\path\to\input.zip" `
  -o ".\out\static-conversion" `
  --overwrite
```

可加入可信 Server JAR执行 parser/apply 验证：

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat convert `
  "C:\path\to\input.zip" `
  -o ".\out\static-conversion" `
  --overwrite `
  --server-jar "C:\path\to\Mindustry-v159.7-server.jar" `
  --server-timeout 60
```

### 本地 Web UI

Web UI 提供显式双模式：

- **静态模式**：上传一个 ZIP/JAR/HJSON/JSON/JSON5，调用 `convert`，不执行输入 Mod；
- **可信运行时模式**：上传一个已构建 Mod JAR，并可选上传对应源码 ZIP，调用 `runtime-convert`。

Windows 仓库可直接双击 `start-web.bat`，或在 PowerShell 中运行：

```powershell
.\start-web.bat
```

首次从仓库启动时会自动构建 Web 分发目录；解压 `mod-dp-bridge-web-0.2.0.zip` 后运行包内同名脚本则不需要 Gradle。未提供 Server JAR 时脚本会明确关闭运行时执行，只保留静态模式。

要启用可信 JAR + 可选源码转换，显式传入固定官方 v159.7/B480 Server JAR：

```powershell
.\start-web.bat -ServerJar "C:\path\to\Mindustry-v159.7-server.jar"
.\start-web.bat -Port 8081 -WorkDir "D:\bridge-work"
```

脚本会固定监听 `127.0.0.1`、验证 Server JAR SHA-256，并在服务就绪后打开浏览器；使用 `-NoBrowser` 可禁止自动打开。运行时模式下，浏览器中的每个作业仍必须确认信任并执行该 JAR。

```text
http://127.0.0.1:8080/
```

不要把启用了运行时模式的 Web 服务监听到非 loopback 地址。完整环境变量、HTTP API、双文件上传和安全说明见 [`docs/WEB_UI.md`](docs/WEB_UI.md)。

## `runtime-convert` 输出

```text
<output>/
├─ <mod-name>-dp-v159.7.zip
├─ server-assets/
├─ report.json
├─ report.md
├─ runtime-pipeline.json
├─ runtime-snapshot.json
├─ runtime-mapping.json
├─ source-index-report.json       # 仅提供 --source 时
├─ hybrid-report.json             # 仅运行混合候选阶段时
└─ logs/
   ├─ conversion.log
   ├─ runtime-extractor.log
   ├─ runtime-extractor-command.txt
   ├─ data-patch-apply.log
   ├─ server-asset-discovery.log
   ├─ runtime-work/
   └─ hybrid-selection/
      └─ attempt-*/data-patch-apply.log
```

重点文件：

- `runtime-pipeline.json`：每一阶段是否执行、成功或回退；
- `runtime-snapshot.json`：Mod 在官方运行时实际注册的 Content 三阶段快照；
- `runtime-mapping.json`：动态 mapper 的成功、未支持和资产选择结果；
- `hybrid-report.json`：源码候选、DataPatcher 每轮筛选、接受/剔除/未决路径；
- `report.md` / `report.json`：最终内容、文件、诊断和验证总报告；
- `logs/`：完整运行日志，不应只看终端最后几行。

若可选源码索引或 AST 候选阶段失败，程序会保守回退到 JAR 动态基础结果并写明原因；不会把未经验证的源码候选静默加入产物。

## 判断结果

Content 结果：

| 状态 | 含义 |
|---|---|
| `converted` | 已生成目标数据，当前未发现已知语义损失 |
| `degraded` | 已生成可加载候选，但 Java-only 行为、字段或模板存在损失/替换 |
| `excluded` | 按项目边界主动排除 |
| `unsupported` | 当前 mapper 或 DP 模型无法表达 |
| `failed` | 该项转换失败 |

验证阶段：

| 阶段 | 含义 |
|---|---|
| `STRUCTURE` | ZIP、目录、根 Content、命名和引用静态检查 |
| `RUNTIME` | 官方 B480 `DataManager.load` / `DataPatcher.apply` |
| `MAP_IMPORT` | 真实 Desktop 地图编辑器导入；当前需人工测试 |
| `SERVER_LOAD` | 真实服务器加载携带 DP 的地图/存档；当前需人工测试 |

`DataPatcher.apply` 的 0 failed / 0 warning 只证明数据可解析和注册，不证明炮塔开火、工厂生产、单位 AI、自定义 Effect、音频播放或地图保存重开与原 Mod 等价。

人工验收：

1. 用精确的 v159.7/B480 Desktop 导入 DP；
2. 在地图中放置代表性的方块、炮塔、工厂和单位；
3. 检查建造栏底图、full icon、outline、构件、特效和音效；
4. 保存地图，完全退出客户端，再重开；
5. 用匹配 B480 服务器实际加载该地图/存档并联机测试。

## New Horizon 2.2.1 

使用发布 JAR 与对应源码 ZIP 执行完整 `runtime-convert`：

| 指标 | 结果 |
|---|---:|
| 官方运行时 Content | 382 |
| 动态 Item/Liquid/Status | 56 |
| runtime-guided Block/Unit 候选 | 141 |
| DataPatcher 接受 | 87（85 Block + 2 Unit） |
| 最终 Content / 外部资产 | 143 / 1635 |
| 最终 DataPatcher failed / warning | 0 / 0 |
| 最终报告 error | 0 |

## 当前已知限制

1. 纯动态 mapper 当前重点覆盖 Item、Liquid 和 StatusEffect；没有源码时，大量 Block/Unit 会明确保持 unsupported。
2. 有源码时只采用运行时确认且 DataPatcher 严格通过的 Block/Unit 候选，不保证覆盖全部内容。
3. 自定义 Java 类、方法覆写、lambda、事件处理、实体实现和网络/UI 逻辑不能通用变成 DP。
4. 原 Mod 若依赖其他 Mod、旧版 API 或非官方运行时而无法在官方 v159.7 加载，extractor 会失败并保留日志。
5. Headless apply 不验证 Desktop atlas、真实渲染、音频播放或地图持久化。
6. 音频当前不自动转码；扩展名与容器不一致会报告并保留原始字节。

## Web UI 状态

`bridge-web` 在 0.2.0 中作为本地任务管理入口，提供静态/可信运行时双模式。它默认关闭运行时执行，只允许 loopback；没有认证、授权、租户隔离或安全沙箱，不支持公网部署和远程用户上传。

运行时作业接收发布 JAR 和可选源码 ZIP。Server JAR 只能由本机操作员配置，不能通过网页上传或由请求指定。网页可查看进度与终端日志、取消任务、下载 DP 和完整日志，并折叠显示完成、降级、排除、不支持和失败项。

## 开发

主要模块：

| 模块 | 职责 |
|---|---|
| `bridge-model` | 报告、诊断、文件/Content 结果和验证阶段模型 |
| `bridge-source-index` | JAR class/资产与可选源码的静态来源索引 |
| `bridge-runtime-extractor` | 官方运行时独立 JVM 加载、三阶段快照与预算控制 |
| `bridge-runtime-assets` | 发布 JAR 资产选择、碰撞和来源报告 |
| `bridge-runtime-mapper` | v159.7 动态快照到 DP 根内容的版本绑定映射 |
| `bridge-java-static` | 不执行源码的 Java AST 导出与 runtime-guided Block/Unit 候选 |
| `bridge-converter` | 安全读取、命名空间、资源检查、离线贴图和确定性打包 |
| `bridge-target-1597` | v159.7 结构检查和官方 DataPatcher harness |
| `bridge-cli` | 两种命令、子进程编排、单调筛选、日志和最终报告 |
| `bridge-web` | 默认关闭运行时能力、仅 loopback 的本地静态/可信运行时任务 UI |

当前版本为 **0.2.0**，变更记录见 [`CHANGELOG.md`](CHANGELOG.md)。运行测试：

```powershell
.\scripts\gradle.ps1 test --no-daemon
```

提交问题时请附上：目标 Mindustry build、完整 CLI 命令、`runtime-pipeline.json`、`runtime-mapping.json`、`hybrid-report.json`（如有）、`report.json`、`report.md` 和 `logs/`。公开前请先脱敏并确认输入 Mod 许可证允许分享。

## 额外声明

本项目为vibe coding产物，使用模型为gpt5.6-sol，开发阶段仅以饱和火力与New Horizon作测试mod，效果仅对DP开发有帮助作用，不能够且不可能完整转换mod，实际对于其他mod的转换以及转换产物可用性也未进行验证
饱和火力能够较好的转换大部分内容，而New Horizon几乎不能转换单位与炮塔
项目正处于初期开发阶段，可能存在较多bug，如发现问题请积极提交


## 许可证

本项目以 [GNU General Public License v3.0](LICENSE) 发布。

第三方组件、Mindustry 参考资源和测试语料边界见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)：

- 转换器许可证不会替你重新许可输入 Mod；
- 转换产物中的源码、贴图、音频和其他资产仍受原作者许可证约束；
- 使用、分发或公开转换产物前，请自行确认原 Mod 许可证允许相应行为；
