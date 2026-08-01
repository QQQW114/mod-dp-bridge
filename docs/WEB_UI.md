# 本地 Web UI 与 HTTP API

`bridge-web` 是 CLI 之上的本地任务管理层。0.2.0 提供两种明确分离的作业模式：

- `static`：调用 `convert`，把输入只作为数据读取，不执行上传 Mod；
- `runtime`：调用 `runtime-convert`，真实执行用户明确信任的发布 Mod JAR，并可使用一个可选源码 ZIP 补充静态候选。

Web 层不重新实现转换规则，也不会提高 DP 的表达能力。它负责文件接收、队列、独立
CLI 进程、进度、实时日志、取消、报告和下载。

> [!CAUTION]
> 运行时模式会以 Web 服务所属系统用户的文件、网络和系统权限真正执行上传的 Mod
> JAR 和操作员配置的官方 Server JAR。独立 JVM、临时目录、预算、超时和进程树终止
> 都不是恶意代码沙箱。只可处理来源明确且完全信任的发布 JAR。

## 支持范围与部署边界

本版本只支持同一台机器上的单个可信操作员：

- 默认监听 `127.0.0.1:8080`；
- 运行时能力默认关闭；
- 启用运行时能力时，监听地址必须解析为 loopback，否则 `runtimeReady=false`；
- 没有账号、认证、授权、租户隔离、病毒扫描、对象存储或分布式队列；
- 不支持公网、远程上传、受信任内网共享或多用户部署；
- `Host`、Origin 和 `Sec-Fetch-Site` 检查只是浏览器侧纵深防御，不是身份认证；
- 任务工作目录可能包含第三方 Mod 原 JAR、源码、转换产物、本机路径和完整日志。

即使只监听 loopback，同一用户会话中的其他本地进程也可能访问该端口。对不能完全
信任的 Mod，应在专用低权限账户、虚拟机或容器内运行整个 Web/CLI 进程。

## 功能

- Mindustry 风格的本地页面；
- 静态/可信运行时模式显式切换，不按文件扩展名静默启用执行；
- 静态模式拖拽或选择一个 ZIP/JAR/HJSON/JSON/JSON5；
- 运行时模式选择一个必需发布 JAR 和一个可选匹配源码 ZIP；
- 每个运行时作业必须勾选“信任并执行”确认；
- 创建、排队、启动和取消作业；
- 显示稳定阶段 token、展示进度和类似终端的 stdout/stderr；
- 下载最终 DP ZIP；
- 下载 Web 日志、转换日志和运行时审计文件；
- 读取 `report.json`，折叠展示 converted、degraded、excluded、unsupported 和 failed；
- 服务重启前可查询当前进程内的作业列表。

浏览器不能直接上传普通目录。静态目录输入和源码目录必须先压缩为 ZIP；CLI 仍支持
直接传入本机目录。

## 构建与运行

需要 JDK 21 构建，输出字节码目标为 Java 17。Windows 工程路径含中文时优先使用：

```powershell
.\scripts\gradle.ps1 :bridge-web:installDist --no-daemon
```

安装目录：

```text
bridge-web/build/install/mod-dp-bridge-web/
```

### Windows 快捷启动

从仓库根目录运行：

```powershell
.\start-web.bat
.\start-web.bat -ServerJar "C:\path\to\official-v159.7-server-release.jar"
.\start-web.bat -Port 8081 -WorkDir "D:\bridge-work"
```

也可直接调用 `scripts/start-web.ps1`。仓库首次启动会在缺少安装目录时自动运行
`:bridge-web:installDist`；解压发布压缩包后，同一脚本直接使用包内 `bin/` 和 `lib/`，
不需要 Gradle。相对 `-WorkDir` 按仓库或分发包根目录解析，`-NoBrowser` 禁止服务
就绪后自动打开浏览器。

快捷脚本始终强制 `MOD_DP_BRIDGE_HOST=127.0.0.1`。未传 `-ServerJar` 时，它会显式
设置 `MOD_DP_BRIDGE_ENABLE_RUNTIME=false` 并清除继承的 Server JAR 环境变量；传入时
则要求普通非符号链接文件并在启动前校验下面的固定 SHA-256。启动器在前台运行，
控制台保留完整日志，可用 `Ctrl+C` 停止。

### 只启用静态模式

不设置运行时开关即可启动：

```powershell
.\start-web.bat
# 或直接运行已构建的底层启动器：
.\bridge-web\build\install\mod-dp-bridge-web\bin\mod-dp-bridge-web.bat
```

访问：

```text
http://127.0.0.1:8080/
```

### 启用可信运行时模式

准备精确的官方 Mindustry v159.7/B480 Server JAR，然后设置：

```powershell
# 推荐：快捷启动器会设置并校验这些关键变量
.\start-web.bat -ServerJar "C:\path\to\official-v159.7-server-release.jar"

# 高级/调试方式：手动启动底层分发程序
$env:MOD_DP_BRIDGE_HOST = "127.0.0.1"
$env:MOD_DP_BRIDGE_SERVER_JAR = "C:\path\to\official-v159.7-server-release.jar"
$env:MOD_DP_BRIDGE_ENABLE_RUNTIME = "true"
$env:MOD_DP_BRIDGE_RUNTIME_TIMEOUT_SECONDS = "120"
$env:MOD_DP_BRIDGE_SERVER_TIMEOUT_SECONDS = "60"
$env:MOD_DP_BRIDGE_HYBRID_MAX_ROUNDS = "8"
.\bridge-web\build\install\mod-dp-bridge-web\bin\mod-dp-bridge-web.bat
```

固定 Server JAR SHA-256：

```text
E41289C32BCF765EB50FA131E6B515D741E20F7843FB567D3AA949E7461F22AB
```

启动配置只决定运行时入口是否可用。浏览器提交每个运行时作业时仍必须包含
`allowModExecution=true`；否则服务拒绝任务。

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---:|---|
| `MOD_DP_BRIDGE_HOST` | `127.0.0.1` | HTTP 监听地址。支持的使用方式是 loopback；启用运行时能力时非 loopback 会使运行时不可用。 |
| `MOD_DP_BRIDGE_ALLOWED_HOSTS` | `localhost,127.0.0.1,::1` | 额外允许的 HTTP `Host`，逗号分隔，只写主机名/IP。不是认证。 |
| `MOD_DP_BRIDGE_PORT` | `8080` | HTTP 监听端口。 |
| `MOD_DP_BRIDGE_WORK_DIR` | `work/web-jobs` | 上传、日志、输出和任务目录；相对路径按服务工作目录解析。 |
| `MOD_DP_BRIDGE_MAX_UPLOAD_MIB` | `64` | 一个请求中全部上传文件合计的硬上限 MiB；运行时 JAR 与源码 ZIP 共用该预算。 |
| `MOD_DP_BRIDGE_MAX_EXPANDED_MIB` | `512` | 静态 `convert` 的归档总展开上限；运行时源码另受 CLI/runtime AST 预算限制。 |
| `MOD_DP_BRIDGE_MAX_ARCHIVE_ENTRIES` | `20000` | 静态 `convert` 的归档条目上限。 |
| `MOD_DP_BRIDGE_MAX_CONCURRENT_JOBS` | `1` | 同时运行的独立 CLI 进程数。 |
| `MOD_DP_BRIDGE_MAX_QUEUED_JOBS` | `8` | 等待队列容量，超过后拒绝新任务。 |
| `MOD_DP_BRIDGE_MAX_SSE_CLIENTS` | `32` | SSE 客户端上限；实现硬上限为 120。 |
| `MOD_DP_BRIDGE_JOB_RETENTION_HOURS` | `24` | 终态任务保留时间；到期后工作目录可清理。 |
| `MOD_DP_BRIDGE_SERVER_JAR` | 未设置 | 本机操作员配置的可信官方 B480 Server JAR。静态模式可用于 apply 验证；运行时模式必需。浏览器不能覆盖。 |
| `MOD_DP_BRIDGE_SERVER_TIMEOUT_SECONDS` | `60` | Server 发现、候选筛选和 apply 子进程超时秒数。 |
| `MOD_DP_BRIDGE_ENABLE_RUNTIME` | `false` | 是否允许本地 Web 创建运行时作业。接受 `true/false`、`1/0`、`yes/no`、`on/off`。 |
| `MOD_DP_BRIDGE_RUNTIME_TIMEOUT_SECONDS` | `120` | Mod 加载和运行时快照子进程超时秒数。 |
| `MOD_DP_BRIDGE_HYBRID_MAX_ROUNDS` | `8` | 可选源码 Block/Unit 候选单调筛选的最大轮数。 |
| `MOD_DP_BRIDGE_JAVA` | 当前 JVM 的 Java | 启动 CLI 子进程的 Java 可执行文件。 |
| `MOD_DP_BRIDGE_CLI_CLASSPATH` | 由分发包推导 | 自定义开发布局的 CLI classpath；普通用户不应覆盖。 |

无效布尔值会导致配置加载失败。运行时 readiness 的保守检查包括：

1. `MOD_DP_BRIDGE_ENABLE_RUNTIME=true`；
2. `MOD_DP_BRIDGE_HOST` 解析得到的地址全部是 loopback；
3. Server JAR 存在、是普通文件、不是符号链接且可读；
4. Server JAR SHA-256 与固定官方值完全匹配。

`runtimeReason` 可能为：

- `runtime_execution_disabled`；
- `runtime_requires_loopback_binding`；
- `runtime_server_jar_unavailable`；
- `runtime_server_jar_unreadable`；
- `runtime_server_jar_sha256_mismatch`。

这些是机器可读原因，不应包含本机绝对路径。

## 输入与权威关系

### 静态模式

| 输入 | 必需 | 行为 |
|---|---:|---|
| `file` | 是 | ZIP/JAR/HJSON/JSON/JSON5；只作为数据读取，不执行输入代码。 |

静态模式不能同时提交运行时字段或执行同意。旧别名 `mod` 不再接受；未知字段一律拒绝，
避免客户端拼写错误被静默解释成另一种请求。

### 可信运行时模式

| 输入 | 必需 | 行为 |
|---|---:|---|
| `modJar` | 是 | 必须为 `.jar`；会在官方 v159.7 独立 JVM 中真实加载，是注册 Content、class 和资产权威。 |
| `source` | 否 | 必须为 `.zip`；只解析 Java AST 和来源关系，不编译、不执行、不供应权威资产。 |
| `allowModExecution` | 是 | 必须精确确认执行风险；当前接受不区分大小写的 `true`。 |

Server JAR 不属于上传字段，只能由本机操作员通过环境变量配置。服务拒绝未知字段、
重复字段、空文件、错误扩展名、过多 multipart part 和组合上传超限；失败时清理该次已写入的临时文件。

## 作业生命周期

```text
multipart upload
      |
      v
 upload received
      |
      +-- 201 写入失败 --> abandoned（不启动 CLI）
      |
      v
   queued ---- cancel ----> cancelled
      |
      v
   running --- cancel ----> cancelled
      |
      +------ exit 0 + 有效 report/DP ZIP ------> succeeded
      |
      +------ non-zero / 终态产物无效 ---------> failed
```

每个作业使用客户端生成、服务端校验并一次性声明的随机 UUID 和独立目录。并发量由
`MOD_DP_BRIDGE_MAX_CONCURRENT_JOBS` 限制，其余作业进入有界队列。

服务只会在 `201 Created` 已成功写回后把任务提交给执行器。客户端请求 UUID 同时就是
预定作业 ID；若浏览器在上传阶段请求终止，前端会先调用
`POST /api/jobs/{requestId}/cancel` 登记 tombstone，再中止上传连接。即使作业尚未创建，
服务也会保留取消声明；若上传仍完成解析则直接把该作业置为 `cancelled`，而不是启动 CLI
或留下未知任务。若首次 tombstone 请求因短暂网络故障失败，前端仍会终止上传，
并在后台按同一 UUID 再执行两次精确取消；重试间会精确查询该 UUID 是否已成为作业或
进入终态，不使用文件名或时间窗口猜测。页面仍保持“已终止”；后台重试仍失败时会在终端日志
中保留请求 UUID 供人工核对。若创建请求因网络错误丢失响应，前端会先按同一 UUID 精确查询作业，
恢复后继续 SSE；已经请求取消时则再次确认取消。

`succeeded` 只表示对应 CLI 返回 0，且 `report.json` 可解析、其中唯一
`dataPackZip` 位于作业输出目录内，并通过无符号链接、大小、SHA-256 和 ZIP 可读性校验；
不表示 Mod 无损转换。运行时
报告仍可能包含大量 `degraded`、`unsupported` 或人工未验证项。

取消运行中作业时，服务会重复发现并终止 CLI 进程树，覆盖 extractor、Mindustry worker
和验证子进程。取消是 best effort：它不是事务回滚，也不能阻止恶意代码提前创建脱离
进程树的进程或完成文件/网络操作。取消任务不开放结果 ZIP 或标准报告，但会保留已完成
的 Web/CLI 日志供审阅；取消后的日志包不包含残留报告或运行时审计 JSON。

任务索引保存在 Web 进程内存中。重启不会恢复或续跑旧任务；旧 UUID 目录会由保留期
清理。需要长期保存的结果应及时下载。

## HTTP API

所有 API 位于 `/api`。错误响应使用 HTTP 状态码和：

```json
{"error":"machine_readable_code","message":"Human-readable message."}
```

### 健康检查

```http
GET /api/health
```

响应至少包含服务状态、时间、活跃任务数、并发/上传上限，以及：

```json
{
  "status": "ok",
  "runtimeReady": false,
  "runtimeReason": "runtime_execution_disabled"
}
```

健康检查只表示 Web 进程可响应。`runtimeReady=true` 只表示本机配置、loopback 和固定
Server JAR 预检通过，不表示某个 Mod 能加载或某个 DP 能导入。

### 创建静态作业

```http
POST /api/jobs
Content-Type: multipart/form-data
X-Mod-DP-Bridge-Request-ID: <UUID>
```

```bash
curl -H "X-Mod-DP-Bridge-Request-ID: 11111111-1111-4111-8111-111111111111" \
  -F "mode=static" -F "file=@MyDataMod.zip" http://127.0.0.1:8080/api/jobs
```

该请求头为必需字段，必须是合法 UUID。浏览器每次点击开始转换都会生成新的 UUID；
服务把它原样写入作业快照，用于上传响应丢失时精确找回并取消同一请求，不能复用文件名
或时间窗口猜测任务。

### 创建可信运行时作业

只有 `runtimeReady=true` 时才可提交：

```bash
curl -F "mode=runtime" \
  -H "X-Mod-DP-Bridge-Request-ID: 22222222-2222-4222-8222-222222222222" \
  -F "modJar=@TrustedMod.jar" \
  -F "source=@TrustedMod-source.zip" \
  -F "allowModExecution=true" \
  http://127.0.0.1:8080/api/jobs
```

没有源码时省略 `source`。不得把未知、不可信或仅因下载热度较高的 JAR提交到该入口。

成功创建返回 `201 Created`、`Location` 和作业快照。作业快照包含：

```json
{
  "id": "UUID",
  "requestId": "客户端请求 UUID",
  "fileName": "TrustedMod.jar",
  "sourceFileName": "TrustedMod-source.zip",
  "mode": "runtime",
  "status": "queued",
  "progress": 0,
  "phase": "queued",
  "createdAt": "ISO-8601",
  "startedAt": null,
  "finishedAt": null,
  "exitCode": null,
  "message": "正在等待可用的转换槽位。",
  "resultAvailable": false,
  "reportAvailable": false,
  "logsAvailable": false
}
```

### 查询与取消

`POST /api/jobs/{id}/cancel` 和 `DELETE /api/jobs/{id}` 均可取消已存在任务。若 `{id}` 是
一个已经声明但仍在 multipart 上传/响应阶段的请求 UUID，同一路由会先登记取消并返回
`202`；后续同 UUID 作业不得启动。重复使用已经声明过的请求 UUID 会被拒绝。

```http
GET  /api/jobs
GET  /api/jobs/{id}
POST /api/jobs/{id}/cancel
```

`DELETE /api/jobs/{id}` 是取消兼容别名。终态为 `succeeded`、`failed` 或 `cancelled`。

### 实时事件

```http
GET /api/jobs/{id}/events
Accept: text/event-stream
```

事件包括：

- `snapshot`：当前完整作业快照；
- `log`：带单调 `sequence` 的新日志行；
- `progress`：阶段/进度变化后的完整快照；
- `status`：生命周期变化后的完整快照。

进度是从稳定 CLI 日志推导的展示值，不是逐 Content 精确百分比。运行时阶段可能包括
`runtime-preflight`、`runtime-extraction`、`source-index`、`runtime-mapping`、
`hybrid-selection`、`validating` 和 `finalizing`。

### 报告与下载

```http
GET /api/jobs/{id}/report
GET /api/jobs/{id}/download/result
GET /api/jobs/{id}/download/logs
```

- `report` 返回 CLI 的原始 `report.json`；
- `result` 只返回 `report.json.outputs` 中声明、大小和 SHA-256 均匹配、位于作业输出目录且路径无符号链接的 DP ZIP；
- `logs` 返回 Web 捕获日志、`output/logs/` 下的转换器日志和存在的报告/运行时审计文件；
- 排队、运行和取消作业不能下载最终结果或标准报告；
- 缺少文件时返回明确非成功状态，不用空的 `200` 伪装成功。

运行时作业应重点审阅：

```text
report.json
report.md
runtime-pipeline.json
runtime-snapshot.json
runtime-mapping.json
source-index-report.json     # 提供 source 时
hybrid-report.json           # 运行 Hybrid 时
logs/conversion.log
logs/runtime-extractor.log
logs/data-patch-apply.log
logs/hybrid-selection/**
```

## Web 状态不等于转换成功

以下事实都不能证明玩法等价：

- HTTP `2xx`；
- `/api/health` 返回 `ok`；
- `runtimeReady=true`；
- 作业状态 `succeeded`；
- `DataPatcher.apply` 为 0 failed / 0 warning；
- Desktop 能导入并显示新内容。

New Horizon 2.2.1 已出现这一边界：自动链路为 143 个 Content、0 failed、0 warning，
真实客户端也能加载并出现新内容，但炮塔缺少弹药，退出地图时崩溃。该 Mod 大量依赖
自定义 Java 行为，不适合作为兼容率样本。

最低人工验收仍包括代表性炮塔弹药/开火、工厂生产、单位 AI、图集/描边/构件、音频、
地图保存重开、退出流程，以及匹配 B480 服务器实际加载地图/存档。

## 本机安全检查

启动前：

1. 确认页面只通过 `127.0.0.1`、`localhost` 或 `::1` 访问；
2. 校验 Server JAR 来源和固定 SHA-256；
3. 校验 Mod 发布来源、版本和原作者签名/哈希（如有）；
4. 使用非管理员、无敏感凭据的专用账号或 VM；
5. 限制 `MOD_DP_BRIDGE_WORK_DIR` ACL、容量和备份/同步范围；
6. 不在同一环境暴露 SSH 密钥、浏览器令牌、源码仓库凭据或其他秘密；
7. 确认输入 Mod 许可证允许转换和使用其资产。

完成后：

1. 终止 Web 服务并确认没有本项目遗留 Java 进程；
2. 下载并审阅报告与全部日志；
3. 清理不再需要的原 JAR、源码和工作目录；
4. 分享日志或产物前删除本机绝对路径、用户名和其他隐私；
5. 在真实 Desktop 和服务器中完成剩余人工验证。

## 故障排查

- 运行时选项不可用：请求 `/api/health`，查看 `runtimeReason`；
- `runtime_server_jar_sha256_mismatch`：不要绕过，重新获取精确官方 v159.7/B480 JAR；
- `runtime_requires_loopback_binding`：把 `MOD_DP_BRIDGE_HOST` 改回 `127.0.0.1` 或 `::1`；
- 作业长期 `queued`：检查并发上限和前一作业是否仍在运行；
- 作业 `failed`：下载日志，检查 `failure-report.txt`、`failure-diagnostics.json`、`runtime-pipeline.json` 和转换日志；
- SSE 中断：刷新页面或重新查询作业；SSE 不是持久日志；
- 取消后仍短暂出现日志：进程树发现、终止和管道收尾存在时间差，以最终状态和进程检查为准；
- 结果下载被拒绝：服务会校验报告声明、路径、大小、SHA-256 和 ZIP 可读性，优先检查报告与落盘文件是否一致；
- Desktop 可加载但功能缺失：这是转换兼容问题，不是 Web 作业成功语义；按 `report.json`、运行时审计文件和真实客户端日志补充兼容。

完整测试矩阵见 [`TESTING.md`](TESTING.md)，CLI 运行时细节见
[`CLI_RUNTIME_INTEGRATION.md`](CLI_RUNTIME_INTEGRATION.md)。
