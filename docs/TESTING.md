# 测试与回归语料

最后更新：2026-08-01（Asia/Shanghai）

## 测试原则

测试分为五层：

1. 单元测试：解析、重写、报告、安全和结构规则；
2. CLI 转换：检查 ZIP、`server-assets/`、日志、JSON/Markdown 报告；
3. v159.7/B480 Headless apply harness：真正调用 `DataManager.load` / `DataPatcher.apply`；
4. v159.7 Server 地图加载：读取已内嵌 DP 的地图/存档；
5. v159.7 Desktop 人工测试：地图导入、图集、generated、音频和实际玩法。

前四层不能替代第五层。特别是 Headless **无法完整验证贴图、atlas region、generated hash、Draw/Effect 或音频解码/播放**。
此外，普通 Server 冷启动中的 `Loaded N data asset files.` 只是文件发现，不代表 `DataPatcher.apply`。

Web UI/API 测试是与上述五层正交的交付层测试：它验证上传、队列、取消、日志和下载没有改变 CLI 语义，但不会新增第六种“DP 可用性验证”。Web 作业显示 `succeeded` 时，转换报告仍可能是 `PARTIAL`。

任何结果在 Desktop 地图导入未完成前都应保持 `PARTIAL`，不能描述为最终兼容。

当前 Saturation 最终自动化记录：`docs/SATURATION_FINAL_VALIDATION_20260730.md`。

最终产物目录：

`work\saturation-static-20260730-060256`

历史小样本自动化矩阵仍保留在 `work/e2e-final2-20260730-022440/`；用户已提供两项真实 v159.7 Desktop 代表结果，见“已完成的 Desktop 实测”。

## 构建与自动化测试

当前工作区路径包含中文。必须优先使用 ASCII Junction 包装脚本：

```powershell
.\scripts\gradle.ps1 build --no-build-cache --no-daemon
```

仅运行主要测试模块：

```powershell
.\scripts\gradle.ps1 :bridge-model:test :bridge-target-api:test :bridge-target-1597:test :bridge-converter:test :bridge-java-static:test --no-build-cache --no-daemon
```

Web 模块测试：

```powershell
.\scripts\gradle.ps1 :bridge-web:test --no-build-cache --no-daemon
```

不要在本机直接以 `gradlew.bat test` 作为首选，因为 Gradle Test Worker 的 classpath argfile 在中文路径下曾发生编码损坏。

当前测试数量：

| 模块 | 测试数 | 当前结果 |
| --- | ---: | --- |
| `bridge-model` | 5 | 通过 |
| `bridge-target-api` | 1 | 通过 |
| `bridge-target-1597` | 7 | 通过 |
| `bridge-converter` | 18 | 通过 |
| `bridge-java-static` | 22 | 通过 |
| `bridge-web` | 6 | 通过 |
| 合计 | 59 | 通过 |

当前覆盖：

- 报告 JSON round-trip 和五种 `fileResults` Markdown 展示；
- 普通 Mod 内容/资产、排除项和 unsupported 分类；
- Legacy CP 包装和旧语法兼容 warning；
- 已有 DP 的确定性重打包、文本字节保持和 generated sprite pair；
- Mod 内容、patch、bundle、资源和 generated 路径的 namespace 迁移；
- 嵌套目录音频路径迁移为 Data Assets 实际注册 basename；
- 字节相同的普通同名 sprite 确定性去重；
- sound/music 共享音频 namespace 的 basename 冲突拒绝；
- 明确缺失的 namespaced sprite/sound 引用和结构阶段失败；
- ZIP Slip、压缩比限制和公共外层目录剥离；
- v159.7 合法目录、非法 Planet 内容和全局 content basename 冲突。
- 正式 B480 DataPatcher apply 协议、失败/warning 判定与 `SERVER_LOAD=NOT_RUN` 语义；
- Java AST 内容声明、表达式、局部变量、构造器/builder、Weapon copy、Consume/ammo/plans/upgrades；
- 4 个内嵌 MissileUnitType 提升、受限确定性循环展开和跨 Content 赋值；
- 自定义 Block/Bullet/Effect 降级、v159.7 不接受字段移除和逐项诊断；
- 固定目标 v159.7 原版对象快照（当前为 tsunami-slag Bullet）与不执行输入代码的约束。
- 音频文件头检测、逐文件 `AUDIO_CONTAINER_EXTENSION_MISMATCH` warning，以及保持原名/字节、不自动转码。
- Web 健康检查、静态页面、未允许 Host 与跨站状态修改拒绝；真实 multipart 上传后启动隔离 CLI，并读取结果 ZIP、报告和日志归档；准备目录/日志失败必须进入终态，上传预约创建失败会回滚容量；运行中的结果产物不可提前下载；取消运行中任务会终止 CLI 及子进程，取消后仅提供已完成的日志归档；并发 SSE 事件序号保持严格单调。

## CLI 基本命令

只做转换和静态结构验证：

```powershell
.\scripts\gradle.ps1 :bridge-cli:run --args='convert "<INPUT>" -o "<OUTPUT>" --overwrite'
```

增加真实 B480 parser/DataPatcher apply 验证（同时记录 Server 文件发现）：

```powershell
.\scripts\gradle.ps1 :bridge-cli:run --args='convert "<INPUT>" -o "<OUTPUT>" --overwrite --server-jar "<path-to-v159.7-B480-server.jar>" --server-timeout 30'
```

应检查：

- CLI 逐行显示全部转换和诊断日志；
- `logs/conversion.log` 内容完整；
- 使用服务器 JAR 时存在 `logs/data-patch-apply.log` 和 `logs/server-asset-discovery.log`；
- `report.json` 与 `report.md` 的状态、错误数量和阶段一致；
- `fileResults` 数量等于成功完成规划后的扫描文件数；
- CLI 返回码与报告一致。

## 标准输出检查

成功生成产物时，输出目录至少应包含：

```text
<output>/
  <name>-dp-v159.7.zip
  server-assets/
  report.json
  report.md
  logs/
    conversion.log
    data-patch-apply.log          # 仅 --server-jar；真实 ContentParser/DataPatcher 输出
    server-asset-discovery.log    # 仅 --server-jar；普通冷启动/文件发现
```

致命失败时应包含：

```text
failure-report.txt
failure-diagnostics.json    # 有结构化诊断时
logs/conversion.log
```

DP ZIP 根目录必须直接是受支持目录，不能是 `<archive-name>/content/...`。

## fileResults 验收

成功完成规划后，每个扫描文件必须恰好出现一次：

- `copied`：二进制资产或要求保持原字节的已有 DP 文本；
- `converted`：规范化、namespace、bundle key 或路径发生转换；
- `excluded`：地图、脚本、Planet/Sector、sprites-override 等产品策略排除；
- `unsupported`：不支持的目录、格式、扩展或能力；
- `failed`：预留的文件级失败状态。

检查每项是否有 source path；有输出时是否有 output path；excluded/unsupported/failed 是否有具体原因；相关诊断代码是否关联到源文件。

如果输入在安全扫描或解析早期发生致命错误，标准 `fileResults` 可能尚未构造，此时应验收 failure report，而不是要求一个伪造的完整标准报告。

## 已有 DP / generated hash 回归

已有 DP 的 content 和 patch 文本必须：

1. 能被解析；
2. 可执行静态资源引用检查；
3. 输出字节与输入条目逐字节相同；
4. 报告 `DATA_PACK_TEXT_PRESERVED`；
5. 整个输出 ZIP 在相同输入和工具版本下确定性一致。

原因是文本格式变化可能改变 Mindustry ContentAsset hash，从而使现有：

`sprites/generated/<type>_<hash>/...`

目录失效。

注意：字节保持只能避免工具主动破坏已有 hash，不能证明输入 DP 的 hash 本来就是正确的。最终仍需 Desktop 客户端观察 generated/atlas。

普通 Mod 的文本会规范化、删除 `research` 并进行 namespace 重写，内容 hash 可能变化。普通 Mod 自带 generated 资源必须作为高风险项进行 Desktop 测试。

## ModNamespaceRewriter 回归

最小语料至少应包含：

- 一个 Item；
- 一个引用该 Item 的 Block requirements；
- 一个 ammoTypes map key；
- 一个 Unit/Weapon member name；
- 一个 Bullet sprite；
- 一个 RegionPart name；
- 一个 patch key；
- 一个普通 Mod patch 的顶层内容桶本地键，例如 `block: { wall: {...} }`；
- bundle 的 item/block key；
- sprite、sound；
- 一个类别前缀的 generated sprite 文件名。

预期：

- 运行时内容引用使用 `dp-<name>`；
- Weapon 等成员名符合当前 Mod 上下文规则，不残留错误双 namespace；
- bundle key 使用 `item.dp-*`/`block.dp-*`；
- patch 中无论原键是 `<mod>-wall` 还是可由符号表确认的本地 `wall`，都迁移为 `dp-wall`；
- custom sprite/sound 引用使用目标 DP 名称；
- `sounds/subdir/shot.ogg` 一类嵌套音频引用 `subdir/shot` 迁移为实际注册名 `dp-shot`；
- generated 文件路径按规则迁移；
- 报告含具体重写诊断；
- 不做描述文本的全局替换。

负面用例应故意引用不存在的 `<mod>-missing` sprite/sound。预期：

- 产物仍生成，便于人工补文件；
- 出现 `UNRESOLVED_MOD_REFERENCE`；
- 对应文件的 `diagnosticCodes` 包含该错误；
- STRUCTURE 为 `FAILED`；
- CLI 返回非零。

还应包含两个名称碰撞用例：

- 不同普通 sprite 路径下同 basename、字节完全相同：只输出一个确定性副本并出现 `IDENTICAL_SPRITE_DEDUPLICATED`；
- `sounds/foo.ogg` 与 `music/foo.ogg`：因共享 v159 音频 namespace，转换以 `AUDIO_NAME_COLLISION` 拒绝。

## AssetReferenceValidator 回归

应覆盖：

- 资源存在但引用 basename 未使用 `dp-`；
- 显式 `dp-` sprite/sound 缺失；
- Unicode 自定义 sprite 缺失；
- RegionPart/DrawRegion `name`；
- `*Sound`、`*Music`、`*Region`、`*Sprite`、`*Icon`、texture 字段和数组；
- 无常规内容 icon 时的 warning。

不要把静态检查结果解释为完整闭包。以下内容仍可能漏检：

- 未知自定义字段；
- 运行时拼接字符串；
- Java/JS/处理器逻辑；
- 看起来像原版英文名称但实际缺失的资源；
- PNG/音频文件内部损坏。

## Legacy CP 回归

### 正常 CP

- 单文件根对象含 item/block/liquid/status/unit/weather；
- 输出位于 `patches/<slug>.hjson`；
- `SourceKind` 为 `legacyCp`；
- `research` 被移除并报告；
- `fileResults` 为 converted；
- 可选 B480 冷启动能加载 patch。

### 兼容修复 CP

输入包含旧式未引号键、内联裸 token 或 `+=`。预期：

- 原生解析失败后尝试兼容模式；
- 输出为规范 HJSON；
- 出现 `LEGACY_CP_COMPATIBILITY_REPAIR` warning；
- 用户必须对比原始 CP 语义，不能仅凭服务器加载通过认为完全正确。

### 失败 CP

- 原生解析和兼容修复都失败；
- 诊断包含输入路径和两次解析错误摘要；
- 写 failure report；
- 不生成误导性的成功 DP。

## 已提交自建语料

### 正向

| 路径 | 用途 | 当前预期 |
| --- | --- | --- |
| `fixtures/self-authored/minimal-data-mod/` | Item、Wall、Unit/Weapon、bundle、PNG；namespace 迁移与服务器加载 | 转换和结构检查无 error；B480 加载 9；Desktop 导入 9，且三个目标 content 正确注册；武器/持久化等仍待测 |
| `fixtures/self-authored/legacy-cp/minimal-old-cp.hjson` | 旧 CP HJSON/PatchSet | 输出 patch HJSON；语法兼容和字段保留 |

### 负向

| 路径 | 当前预期 |
| --- | --- |
| `fixtures/negative/zip-slip-relative.zip` | 解包前拒绝路径穿越，不在目标外创建文件 |
| `fixtures/negative/missing-resource-mod/` | 明确报告缺失 sprite/sound 等资源；STRUCTURE 失败或至少有 error，绝不静默成功 |
| `fixtures/negative/malformed-old-cp.hjson` | 原生和兼容解析失败，写出有路径和原因的 failure report |

自建小型 fixture 采用 CC0-1.0。新增 fixture 时不得复制许可证不明的第三方资产。

## 本地 external-cp 语料

路径：

`<local-external-cp-corpus>`

这些是本地/服务器上传样本，没有统一可再分发许可证，不应直接提交到开源仓库。CI 缺少该目录或 hash 不同时应跳过，不应失败。

| 样本 | 主要覆盖 | 当前事实/预期 |
| --- | --- | --- |
| `惊鸿3.zip` | 已有 DP、1 block、generated PNG、OGG | E2E Headless 加载 17 且全部条目字节保持；Desktop 导入 17，炮塔注册和原逻辑正常；音效与全部视觉细节未逐项确认 |
| `亚龙组合包 (1).zip` | 17 content、58 PNG、1 OGG、缺失引用 | 静态验证报告 6 个 error；Headless 仍 `Loaded 76 data asset files.` 并进入 `Server loaded.`，直接证明服务器加载不能替代贴图/音频验证；不得宣称可用 |
| `区块生存CP.hjson` | 约 7.35MB 大型 HJSON CP | 测试资源限制、性能、日志和 legacy 解析；尚未作为完整兼容证明 |
| `奇妙双科cp.json` | 大型严格/近严格 JSON CP | Patch 对象覆盖 |
| `仙古2.8cp单文件整合.json` | `.json` 中含宽松语法/注释 | HJSON/兼容解析 |
| `生锈的铜的cp预览版.json` | 小型 block/liquid CP | 快速 CP 回归 |
| `锈铜墙.json` | 极小 CP | 冷启动基线 `Loaded 1 data asset files.` |

## Saturation Firepower 验收边界

参考：

`<local-Saturation-Firepower-source>`

最终自动化记录：`docs/SATURATION_FINAL_VALIDATION_20260730.md`。

最终产物：

`work\saturation-static-20260730-060256\sfire-mod-dp-v159.7.zip`

SHA-256：

`B085C6533CC43367CBC488DB201D0414F3B212DDDF6B5D8C081C6482755569BE`

当前 Java AST 转换验收结果：

- 扫描 1764 个源文件；
- 生成 358 个根 Content：15 Item、6 Liquid、22 Status、63 Unit、252 Block；
- 复制/转换 1628 个 bundle/sprite/audio 外部资产；
- DP 合计 1986 个 Data Assets；
- Content 结果为 295 converted、63 degraded、0 excluded、0 unsupported、0 failed；
- 报告为 31 info、60 warning、0 error；
- 文件级结果为 1622 copied、11 converted、27 excluded、104 unsupported、0 failed；
- 4 个内嵌 MissileUnitType 已提升为独立 Unit；
- 7 个确定性循环已展开；
- 5 个跨 Content 赋值已应用；
- 一处加载期随机表达式被替换为确定性中点 5 并报告；
- `tieliu` 的 tsunami-slag `fragBullet` 已由绑定 v159.7 的固定原版对象快照恢复；
- `JAVA_FIELD_EXPRESSION_OMITTED = 0`；
- 同一最终输入和工具版本复打包得到相同 ZIP SHA-256。
- 10 个音频资产的文件名均为 `.ogg`；文件头审计为 5 个 OGG、3 个 MP3 容器、2 个 WAV 容器。5 个不匹配文件逐项产生 `AUDIO_CONTAINER_EXTENSION_MISMATCH`；转换器保持原名和字节，未自动转码，必须由 Desktop 验证解码/播放。

B480 正式 apply：

```text
assets=1986
content=358
external=1628
addedContent=689
failed=0
warnings=0
```

阶段必须记录为：

- `STRUCTURE = PASSED`
- `RUNTIME = PASSED`
- `MAP_IMPORT = NOT_RUN`
- `SERVER_LOAD = NOT_RUN`

60 个转换 warning 是主动披露的降级和资产审查项，其中 5 个来自音频容器/扩展名不匹配；不能与 B480 apply 的 `warnings=0` 混淆。63 个 degraded Content 仍可能缺少自定义 Block/Bullet 行为、状态回调、Java 方法覆写或 lambda/自定义 Effect；“全部根 HJSON 被接受”不等于“全部行为等价”。

当前自动化仍不解码 PNG 或音频容器，不构建客户端 atlas，也不导入地图。测试只能确认文件、hash、引用、HJSON 注册以及 Headless apply；客户端表现必须按后文人工步骤验证。

## Headless DataPatcher apply 验收

通过条件：

- 使用可信 v159.7/B480 JAR；
- 隔离 harness 进程在超时前退出；
- 日志出现 `DPBRIDGE_PROTOCOL` 和唯一 `DPBRIDGE_RESULT`；
- 没有 `DPBRIDGE_FATAL`、Content/Patch/read error；
- 每个 Content 在 `finishParsing/init/postInit` 之后仍无 error 并留在 `Vars.content`；
- `report.json` 的 RUNTIME 为 PASSED；
- SERVER_LOAD 仍为 NOT_RUN，直到真实服务器加载携带 DP 的地图/存档。

Headless apply 通过只证明 B480 parser/DataPatcher 已接受并注册数据对象。它不证明：

- DP ZIP 能由 Desktop 导入；
- 地图保存后能内嵌并恢复内容；
- PNG 可解码或 atlas region 存在；
- generated sprite hash 正确；
- 音频资产可解码并播放；
- Weapon/Bullet/Effect/Draw 可视与手感正确；
- 工厂生产、Consume、AI、状态和天气实际行为正确；
- 目标地图在多人服务器中可玩。

## Web UI / HTTP API 验收

Web 验收的目标是证明“同一 CLI 能被安全、可观察地编排”，而不是重新判断转换语义。至少覆盖以下四组测试。

### 1. 自动化接口测试

现有 `bridge-web` 集成测试使用临时工作目录、随机回环端口和仓库内自建小语料，并真正启动隔离 CLI；不依赖 Desktop、外部 Mod 或公网。后续队列/取消等专项测试可使用可控的假进程入口。完整最低断言：

- `GET /api/health` 返回成功且 Content-Type 正确；
- 默认回环 Host 可访问；未列入白名单的 Host 返回 `421 host_not_allowed`，监听通配地址不会隐式放行任意 Host；
- `POST /api/jobs` 接受规范 multipart 字段 `file`（兼容别名 `mod`），返回 `201` 和 UUID；
- 缺少文件、空文件、非法 multipart 和超上限被拒绝；路径型、控制字符或超长文件名被净化为安全叶文件名；
- 同一任务依次出现合法状态，不允许终态回到 `running`；
- 并发上限为 1 时第二个任务保持 `queued`，首个完成后才启动；
- 取消 queued 任务不会启动 CLI；取消 running 任务会结束进程及其后代并进入 `cancelled`；
- stdout/stderr 被合并、按序落盘，并可通过 SSE 收到；
- SSE 至少包含初始 snapshot 和终态，客户端断开不会取消任务；
- 成功任务的 `/download/result`、`/download/logs`、`/report` 返回正确内容和安全文件名；
- 作业仍在 `queued` / `running` 时，即使工作目录中已出现 ZIP，也不允许提前下载为最终结果；
- 准备作业目录或创建日志失败时，任务必须进入 `failed` 终态，不得永久停留在 `running`；
- 未生成对应产物时下载端点返回明确的非成功状态，而不是空的 200 文件；
- 未知 UUID、已清理任务及非法路径返回 404/400，不泄漏宿主机绝对路径；
- 清理只删除专用工作目录中的过期 UUID 作业，不越界删除。

### 2. 本地 API smoke test

先构建，并在终端 A 前台启动：

```powershell
.\scripts\gradle.ps1 :bridge-web:installDist
& ".\bridge-web\build\install\mod-dp-bridge-web\bin\mod-dp-bridge-web.bat"
```

在终端 B 执行健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
```

创建任务并轮询（`fixtures` 中应选择许可允许提交且能快速完成的 ZIP 语料）：

```powershell
$response = Invoke-RestMethod `
  -Method Post `
  -Uri http://127.0.0.1:8080/api/jobs `
  -Form @{ file = Get-Item ".\path\to\fixture.zip" }

$id = $response.id
do {
  Start-Sleep -Milliseconds 250
  $job = Invoke-RestMethod "http://127.0.0.1:8080/api/jobs/$id"
  $job.status
} while ($job.status -in @("queued", "running"))
```

成功后检查：

```powershell
Invoke-WebRequest "http://127.0.0.1:8080/api/jobs/$id/download/result" -OutFile ".\web-result.zip"
Invoke-WebRequest "http://127.0.0.1:8080/api/jobs/$id/download/logs" -OutFile ".\web-logs.zip"
Invoke-WebRequest "http://127.0.0.1:8080/api/jobs/$id/report" -OutFile ".\web-report.json"
```

测试完成后回到终端 A 按 `Ctrl+C`，确认 Web 进程及其仍在运行的转换子进程均已退出。

不要把上述 smoke test 的 Web `succeeded` 状态写成 Desktop 已通过；继续校验 ZIP hash、报告状态和人工地图导入。

### 3. 浏览器人工测试

在 Chromium/Firefox 至少各完成一次：

1. 点击选择文件和拖拽上传都能正确显示文件名与大小；
2. 未选择文件时不能开始，上传期间不会重复提交；
3. 运行后状态、进度和终端日志持续更新，长行、中文、ANSI/控制字符不会破坏页面；
4. 日志区域可滚动，自动跟随不会阻止用户向上阅读历史；
5. 终止按钮只在可取消状态启用，确认后任务最终变为 `cancelled`；
6. 转换成功后 DP、全部日志和原始报告下载可用；
7. 转换完成、降级、排除、不支持和失败项数字与 `report.json` 一致；
8. 详细分组默认折叠，展开后原因和诊断码可读；
9. CLI 失败、浏览器 SSE 断线、报告缺失和下载失败都有明确提示；
10. 刷新页面不会终止服务端正在运行的 CLI；重新连接后至少能查询最终状态和下载持久日志。

同时检查小屏幕下的基本可用性、键盘焦点、按钮禁用状态和文本对比度。Mindustry 风格不能以牺牲错误可读性或可访问性为代价。

### 4. 安全与资源测试

- 分别测试无 `Content-Length` 的流式超限、伪造较小 `Content-Length` 后实际超限和代理层超限；
- 上传文件名包含 `..`、绝对路径、Windows 盘符、控制字符、同形 Unicode 和超长名称；
- 上传 ZIP Slip、过多 entry、高压缩比和展开超限语料，确认 Web 与 CLI 两层均拒绝；
- 同时提交超过并发上限的任务，确认不会无限创建运行进程；
- 运行任务中触发取消，确认 Java/验证子进程没有遗留；
- 将工作目录权限设为不可写、磁盘空间不足或 CLI classpath 错误，确认任务失败且日志不泄漏敏感环境变量；
- 到期清理前后检查任务、上传、日志、报告和产物的生命周期一致；
- 从另一台主机确认默认 `127.0.0.1` 不可访问；远程部署时验证反向代理认证、HTTPS、上传限制、SSE 不缓冲和超时。

公开部署前还必须人工审阅日志中的本机路径、输入名称和第三方资产许可证。完整安全部署建议见 `WEB_UI.md`。

## 已完成的 Desktop 实测

### minimal 普通 Mod DP

用户报告：

- Desktop 导入数量：9；
- `dp-fixture-wall` 正确注册；
- `dp-fixture-drone` 正确注册；
- `dp-fixture-alloy` 正确注册。

输出目录审计恰好包含：3 个 content、2 个 bundle、4 个 sprite，共 9 个 Data Assets 文件。这一结果可标记为：

- DP ZIP 可被真实 Desktop 读取；
- minimal 样本的 Item/Block/Unit 顶层注册通过；
- `dp-` namespace 迁移对这三个内容名通过。

尚未验证：

- `fixture-drone` 的 Weapon/Bullet 实际开火；
- 所有 sprite/icon/generated 视觉细节；
- 音频（该 fixture 本身没有音频）；
- 地图保存、完全退出客户端、重开后的持久化；
- 导出地图并由服务器/联机客户端加载。

用户还观察到 4 个额外星球标签和原版 `heat-source`。它们不在转换输出中：minimal DP 没有 `content/planets`，也没有 `heat-source` content 文件。

源码核查结果：

1. v159 原版共有 7 个 Planet；原有数据库内容通常只引入 Serpulo/Erekir 标签。
2. `fixture-wall` 有 requirements，但没有显式 `shownPlanets`；自定义需求物品的 `shownPlanets` 也为空。
3. `Block.postInit()` 将该墙自动分配到所有可登陆星球，进而新增 Gier、Notva、Tantros、Verilus 四个数据库标签。
4. `heat-source` 是原版 sandbox block，且 `allDatabaseTabs = true`，所以会在新增标签的制造分类中出现。

因此这是一项可复现的 Planet scope/数据库 UI 副作用，不是新 Planet 或 `heat-source` 被转换包注册。后续测试应验证显式 `shownPlanets` 或可选默认 scope 是否能消除标签扩展，同时不影响地图玩法。

### `惊鸿3.zip`

用户报告：

- Desktop 导入数量：17；
- 惊鸿炮塔正确注册；
- 炮塔原逻辑功能正常。

这与输出的 1 个 Block、2 个 Sound、14 个 Sprite 共 17 项一致。结合所有 17 个 ZIP 条目与输入逐字节相同，可标记为：

- 已有 DP 安全重打包通过真实 Desktop 导入；
- 惊鸿 Block 注册通过；
- 该炮塔的用户所测核心原逻辑通过。

仍不能标记为已验证：

- 两个 OGG 是否均被触发并正确播放；
- 每一个普通/generated sprite 和 heat/outline/preview region；
- 未被用户实际触发的嵌套 Effect/Draw/Consume 等路径；
- 保存地图后完全退出/重开和多人服务器目标地图加载。

## v159.7 Desktop 人工测试步骤

已有两个样本完成了首次导入/注册验证；以下剩余步骤仍阻塞最终成功声明。

### 准备

1. 使用与目标一致的 v159.7/B480 Desktop 客户端；记录客户端来源和 build。
2. 备份客户端数据目录，最好使用干净测试配置，避免其他 Mod/DP 污染结果。
3. 保留转换输出中的 DP ZIP、`report.json`、`report.md` 和全部日志。
4. 先处理报告中所有 error；若决定带 error 测试，必须在结果中明确说明。

### 导入与持久化

1. 启动 Desktop 客户端并打开地图编辑器。
2. 创建一个新的空白测试地图或打开专用测试地图。
3. 使用 v159.7 的 Data Assets/DP 导入功能选择生成的 `*-dp-v159.7.zip`；不要把它误当普通 Java Mod 安装。
4. 观察导入过程和客户端日志，记录所有 parse、content、atlas、region、sound 或 generated 错误。
5. 保存地图到一个新文件。
6. 关闭地图编辑器并完全退出客户端。
7. 重新启动客户端并重新打开刚保存的地图。
8. 确认自定义内容仍存在，且没有因 DP 内嵌、hash 或命名空间变化而丢失。

### 内容和玩法检查

在沙盒/无限资源条件下逐项测试：

1. **Item/Liquid**：内容列表、名称、图标、颜色；向容器/管道/工厂输入并读取。
2. **Block/Terrain**：放置每个关键方块，检查尺寸、贴图、旋转、DrawPart、覆盖层和拆除。
3. **Turret/Weapon/Bullet**：提供弹药/电力/液体，实际开火；检查弹丸 sprite、命中、Effect、状态、音效、射程和伤害。
4. **Factory/Consume**：检查 requirements、配方输入输出、单位生产、液体/电力消耗和 payload。
5. **Unit/AI**：生成每种关键单位，检查图标、主体、武器、移动、目标选择、Ability 和死亡效果。
6. **Status/Weather**：施加状态、触发 affinity/opposite/effect；启动天气并检查客户端效果。
7. **Bundle**：检查至少默认语言和中文名称/描述，确认 key 已迁移到 `dp-`。
8. **Assets**：重点检查报告涉及的显式 region/sprite/sound/music 和所有 generated 目录资源。

### 地图与服务器联测

1. 再次保存并导出测试地图，保留 `.msav`。
2. 将生成的 `server-assets/` 部署到匹配 B480 服务器，或先用 `--server-jar` 执行冷启动。
3. 让服务器加载导出的目标地图；观察内容反序列化、方块/单位 ID、规则和网络同步日志。
4. 至少连接一个真实 Desktop 客户端进行短时联机，重新测试放置、生成和开火。

当前自动化已完成资产文件发现和独立 B480 DataPatcher apply，但尚未加载用户导出的真实地图/存档，也未连接客户端。

### 必须回传的证据

请保存并提供：

- 客户端精确版本/build；
- 导入的 DP ZIP 文件名和 SHA-256；
- `report.json`；
- 客户端日志（尤其首次导入和重开地图后的日志）；
- 服务器日志；
- 测试地图 `.msav`；
- 内容面板、关键贴图、开火/工厂/单位的截图或短视频；
- 按下表填写的结果。

## 人工测试回报模板

```text
客户端版本：
服务器 JAR：
输入 Mod/CP/DP：
输出 DP SHA-256：

DP 导入：通过 / 失败
地图保存：通过 / 失败
退出重开：通过 / 失败
服务器资产冷启动：通过 / 失败
服务器加载目标地图：通过 / 失败 / 未测试

Item/Liquid：
Block/Turret/Factory：
Unit/Weapon/Bullet：
Status/Weather：
贴图/图标/generated：
音效/音乐：
Bundle/本地化：

客户端错误日志：
服务器错误日志：
未转换或手工修复项：
截图/视频/地图路径：
```

## 最终验收门槛

一个转换样本只有同时满足以下条件才能标为最终可用：

1. 报告没有未处理的 error/failed；
2. unsupported/excluded 项均符合产品范围或已由用户接受；
3. 静态 v159.7 结构验证通过；
4. 匹配 B480 的正式 DataPatcher apply 通过；普通 Server 冷启动只能作为资产发现证据；
5. Desktop DP 导入、保存、退出重开通过；
6. 关键贴图、generated、音效和地图玩法通过；
7. 如用于多人服务器，目标地图联机加载通过。

在第 5–7 项没有证据前，状态保持 `PARTIAL`。
