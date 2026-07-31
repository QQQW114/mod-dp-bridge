# Web UI 与 HTTP API

Web UI 是 CLI 之上的本地任务管理层，用于上传一个 Mod/CP/DP、查看实时转换日志、终止转换、下载产物与审阅报告。它复用同一套转换器和报告模型，不在浏览器或 Web 服务中重新实现转换规则。

> [!IMPORTANT]
> 当前 Web 服务面向个人、本机或受信任内网使用。它没有账号、权限、租户、配额计费、病毒扫描或对象存储集成。默认只监听 `127.0.0.1`，并校验 HTTP `Host` 白名单以降低 DNS rebinding 风险；该白名单不替代身份认证。不要把未配置认证和 TLS 的实例直接暴露到公网。

## 功能

- 拖拽或文件选择上传 Mod 目录压缩包、ZIP/JAR、CP/DP 或 HJSON/JSON/JSON5；
- 创建、排队并启动转换作业；
- 显示作业状态和转换进度；
- 以类似 Windows Terminal 的视图实时显示 stdout/stderr，并保留完整日志；
- 终止排队中或运行中的转换；
- 下载生成的 `*-dp-v159.7.zip`；
- 下载该作业的全部日志归档；
- 完成后分别展示已转换、降级、排除、不支持和失败项，详细内容默认折叠；
- 下载或查看原始 `report.json`。

网页文件选择器支持 `.zip`、`.jar`、`.hjson`、`.json` 和 `.json5`。浏览器不能直接上传普通目录，请先把 Mod 目录压缩为 ZIP；CLI 仍支持直接传入本机目录。

Web UI 不改变转换边界：科技树、新地图、GUI、网络协议和输入 Mod 的任意运行时代码仍不会被迁移或执行；地图导入和服务器真实地图加载仍需人工验证。

## 构建与运行

需要 JDK 21。Windows 工程路径含中文时，优先使用项目包装脚本：

```powershell
.\scripts\gradle.ps1 build :bridge-web:installDist
.\bridge-web\build\install\mod-dp-bridge-web\bin\mod-dp-bridge-web.bat
```

Linux/macOS：

```bash
./gradlew build :bridge-web:installDist
./bridge-web/build/install/mod-dp-bridge-web/bin/mod-dp-bridge-web
```

启动后默认访问：

```text
http://127.0.0.1:8080/
```

前端是嵌入 `bridge-web` classpath 的静态 HTML/CSS/JavaScript，不要求 Node.js，也没有独立的前端构建步骤。

开发时也可直接运行：

```powershell
.\scripts\gradle.ps1 :bridge-web:run
```

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---:|---|
| `MOD_DP_BRIDGE_HOST` | `127.0.0.1` | HTTP 监听地址。只有在外层已有访问控制时才应改为 `0.0.0.0` 或 `::`；通配监听时还必须显式配置对外域名/IP。 |
| `MOD_DP_BRIDGE_ALLOWED_HOSTS` | `localhost,127.0.0.1,::1` | 额外允许的 HTTP `Host`，用逗号分隔。只写主机名或 IP，不写协议、端口或路径。具体的非通配监听地址会自动加入；`0.0.0.0` / `::` 不会自动放行任意主机名。 |
| `MOD_DP_BRIDGE_PORT` | `8080` | HTTP 监听端口。 |
| `MOD_DP_BRIDGE_WORK_DIR` | `work/web-jobs` | 上传、任务工作目录、日志和产物的存放位置；相对路径按服务进程工作目录解析。 |
| `MOD_DP_BRIDGE_MAX_UPLOAD_MIB` | `64` | 单个 HTTP 上传的硬上限 MiB；转换器还会继续执行归档条目数、展开大小和压缩比检查。 |
| `MOD_DP_BRIDGE_MAX_EXPANDED_MIB` | `512` | 传给 CLI 的归档最大总展开 MiB。公开服务应结合磁盘/内存预算调低。 |
| `MOD_DP_BRIDGE_MAX_ARCHIVE_ENTRIES` | `20000` | 传给 CLI 的归档最大条目数。 |
| `MOD_DP_BRIDGE_MAX_CONCURRENT_JOBS` | `1` | 同时运行的 CLI 子进程数；其余任务排队。 |
| `MOD_DP_BRIDGE_MAX_QUEUED_JOBS` | `8` | 等待队列最大任务数，超过后拒绝新任务。 |
| `MOD_DP_BRIDGE_MAX_SSE_CLIENTS` | `32` | 同时保持的 SSE 客户端连接上限；实际硬上限为 `120`，更大配置会被截断为 `120`。 |
| `MOD_DP_BRIDGE_JOB_RETENTION_HOURS` | `24` | 完成、失败或取消任务的保留时长。到期后任务及其上传、日志和产物可被清理。 |
| `MOD_DP_BRIDGE_SERVER_JAR` | 未设置 | 可选可信 B480 Server JAR；设置后传给 CLI 进行 DataPatcher apply 验证。该 JAR 会被执行。 |
| `MOD_DP_BRIDGE_SERVER_TIMEOUT_SECONDS` | `60` | 使用 Server JAR 时传给 CLI 的单个验证进程超时秒数。 |
| `MOD_DP_BRIDGE_JAVA` | 当前 JVM 对应的 Java | 启动隔离 CLI 子进程时使用的 Java 可执行文件。 |
| `MOD_DP_BRIDGE_CLI_CLASSPATH` | 由分发包/运行环境推导 | CLI 子进程 classpath。仅在自定义部署布局或开发排错时覆盖。 |

PowerShell 示例：

```powershell
$env:MOD_DP_BRIDGE_HOST = "127.0.0.1"
$env:MOD_DP_BRIDGE_ALLOWED_HOSTS = "localhost,127.0.0.1,::1"
$env:MOD_DP_BRIDGE_PORT = "8080"
$env:MOD_DP_BRIDGE_WORK_DIR = "D:\mod-dp-bridge-jobs"
$env:MOD_DP_BRIDGE_MAX_UPLOAD_MIB = "64"
$env:MOD_DP_BRIDGE_MAX_EXPANDED_MIB = "512"
$env:MOD_DP_BRIDGE_MAX_ARCHIVE_ENTRIES = "20000"
$env:MOD_DP_BRIDGE_MAX_CONCURRENT_JOBS = "1"
$env:MOD_DP_BRIDGE_MAX_QUEUED_JOBS = "8"
$env:MOD_DP_BRIDGE_MAX_SSE_CLIENTS = "32"
$env:MOD_DP_BRIDGE_JOB_RETENTION_HOURS = "24"
.\bridge-web\build\install\mod-dp-bridge-web\bin\mod-dp-bridge-web.bat
```

若通过域名或内网 IP 访问，将浏览器实际使用的主机名/IP 加入白名单（不写 `http://` / `https://` 和端口）：

```powershell
$env:MOD_DP_BRIDGE_ALLOWED_HOSTS = "bridge.example.com,192.0.2.10"
```

若启用真实 B480 验证：

```powershell
$env:MOD_DP_BRIDGE_SERVER_JAR = "C:\path\to\trusted-v159.7-B480-server.jar"
.\bridge-web\build\install\mod-dp-bridge-web\bin\mod-dp-bridge-web.bat
```

`MOD_DP_BRIDGE_SERVER_JAR` 指向的程序会在服务器主机上执行，只能使用来源可信、版本匹配的 JAR。上传的 Mod/JAR 则始终作为不可信数据读取，不会被加载或执行。

## 作业生命周期

```text
multipart upload
      |
      v
   queued ---- cancel ----> cancelled
      |
      v
   running --- cancel ----> cancelled
      |
      +------ exit 0 ------> succeeded
      |
      +------ non-zero ---> failed
```

每个作业使用不可猜测的 UUID 和独立工作目录。服务端会净化客户端文件名，将转换放在独立 CLI JVM 中，并合并捕获 stdout/stderr。取消运行中作业时会终止 CLI 进程及其后代；取消不是事务回滚，界面和 API 会保留取消前已经写入的日志供审阅。

同一进程内的并发量由 `MOD_DP_BRIDGE_MAX_CONCURRENT_JOBS` 限制。当前队列和状态主要属于单实例本地服务语义，不应把多个实例直接指向同一个工作目录来充当分布式队列。

任务索引当前保存在 Web 进程内存中。重启服务不会恢复或续跑先前任务；旧 UUID 目录会作为孤立工作目录留到保留期清理。需要长期保存的结果应在重启或到期前下载。

## HTTP API

所有 API 位于 `/api` 下。错误响应使用 HTTP 状态码和 JSON 错误信息；客户端不能把 HTTP 2xx、CLI 退出码 `0` 或作业 `succeeded` 理解为无损转换，最终仍应阅读 `report.json`。

### 健康检查

```http
GET /api/health
```

用于确认 Web 进程可响应，不代表转换器、Server JAR 或 Desktop 地图导入已经验证。

响应包含 `status`、服务器时间、当前活跃任务数、并发上限和上传字节上限，可用于本地存活探针；当前没有独立的“依赖已就绪”探针。

### 创建作业

```http
POST /api/jobs
Content-Type: multipart/form-data
```

multipart 文件字段规范名为 `file`（`mod` 仅作为兼容别名）。上传成功后返回 `201 Created` 和作业快照；作业可能立即进入 `running`，也可能因并发限制保持 `queued`。

```bash
curl -F "file=@MyMod.zip" http://127.0.0.1:8080/api/jobs
```

作业快照结构示例：

```json
{
  "id": "a UUID",
  "fileName": "MyMod.zip",
  "status": "running",
  "progress": 62,
  "phase": "planning",
  "createdAt": "ISO-8601 timestamp",
  "startedAt": "ISO-8601 timestamp or null",
  "finishedAt": null,
  "exitCode": null,
  "message": "正在规划内容与资源转换",
  "resultAvailable": false,
  "reportAvailable": false,
  "logsAvailable": false
}
```

`phase` 是稳定的英文阶段 token，面向用户的中文说明位于 `message`。`progress` 是从稳定 CLI 阶段日志推导的 0–100 展示值，不是逐文件精确完成百分比；报告仍以最终文件和 Content 结果为准。完整日志归档只在任务进入终态后开放下载，因此 `running` 示例中 `logsAvailable` 为 `false`。

### 查询与取消

```http
GET  /api/jobs
GET  /api/jobs/{id}
POST /api/jobs/{id}/cancel
```

`GET /api/jobs` 返回当前进程内仍保留的作业列表。`DELETE /api/jobs/{id}` 是取消操作的兼容别名；新客户端应使用明确的 `POST .../cancel`。

状态为：

| 状态 | 含义 |
|---|---|
| `queued` | 已安全保存上传，等待可用转换槽位。 |
| `running` | 独立 CLI 进程正在运行。 |
| `succeeded` | CLI 正常完成并生成可供审阅的结果；不等于无损转换。 |
| `failed` | CLI 非零退出、启动失败或 Web 作业内部失败。 |
| `cancelled` | 用户终止了排队或运行中的作业。 |

对终态任务重复取消不会重新启动任务。任务不存在或已过保留期时返回 `404`。

错误响应格式为：

```json
{"error":"machine_readable_code","message":"Human-readable message."}
```

### 实时事件

```http
GET /api/jobs/{id}/events
Accept: text/event-stream
```

该端点使用 Server-Sent Events。事件类型包括：

- `snapshot`：当前完整作业快照；
- `log`：`{"sequence": number, "line": string}` 新增终端日志；
- `progress`：进度发生变化后的完整作业快照；
- `status`：生命周期变化后的完整作业快照。

每条事件的 `data` 是 JSON。浏览器断线后应重新请求作业快照；SSE 是实时显示通道，持久日志归档才是完整审计依据。

### 报告与下载

```http
GET /api/jobs/{id}/report
GET /api/jobs/{id}/download/result
GET /api/jobs/{id}/download/logs
```

- `report` 返回原始 `report.json`；报告尚未生成时返回非成功状态；
- `result` 下载转换后的 DP ZIP；
- `logs` 下载该作业的全部日志归档，包括 Web 捕获的 CLI 输出和转换器落盘日志。

对应文件尚未生成时返回 `404`，不会用空的 `200` 下载伪装成功。

`/api/jobs/{id}/result` 和 `/api/jobs/{id}/logs` 是下载端点的兼容短别名。

失败或取消任务不保证存在结果 ZIP 或标准报告，但应尽量保留终端输出、`failure-report.txt` 和已生成的诊断文件。下载响应的文件名仅由服务端生成，不信任上传时的原始路径。

## 安全部署

### Host 白名单与 DNS rebinding

服务端会先规范化并校验每个请求的 HTTP `Host`。默认允许 `localhost`、`127.0.0.1` 和 `::1`；`MOD_DP_BRIDGE_HOST` 是具体非通配地址时也会自动加入。其他域名/IP 必须通过 `MOD_DP_BRIDGE_ALLOWED_HOSTS` 逗号分隔列出，否则返回 `421` 和 `host_not_allowed`。

`0.0.0.0` 和 `::` 是监听通配地址，它们不会被当作“允许所有 Host”。因此监听通配地址、通过容器端口映射或使用反向代理时，都必须显式列出浏览器实际访问的域名/IP。白名单值只写主机名/IP，不写协议、端口或路径。

这项检查用于降低攻击者借浏览器 DNS rebinding 访问本地服务的风险，但 `Host` 本身不是身份凭据。**Host 白名单不替代认证、授权、TLS 或反向代理限流。**

### 本机使用（推荐）

保持默认 `127.0.0.1`，限制工作目录 ACL，仅让当前服务账号可读写。工作目录中可能包含第三方 Mod 原文件、转换产物、本机绝对路径和日志，不应同步到公开目录。

### 受信任内网或公网

当前版本没有内置认证。若确需远程访问，至少应：

1. Web 服务仍监听回环地址，由 Caddy/Nginx/Traefik 等反向代理提供 HTTPS；
2. 用 `MOD_DP_BRIDGE_ALLOWED_HOSTS` 显式列出对外域名/IP，并保留浏览器原始 `Host` 转发给后端；
3. 在代理层启用强认证和授权，不允许匿名上传、读取报告或下载他人的作业；
4. 同时在代理和应用设置请求体上限、连接/读取超时及速率限制；
5. 使用非管理员、无交互登录权限的专用系统账号；
6. 把 `MOD_DP_BRIDGE_WORK_DIR` 放在独立、容量受控且不可执行的位置；
7. 监控磁盘、内存、CPU、子进程数和任务清理；
8. 不挂载 Docker socket、SSH 密钥、源码仓库凭据或其他宿主机秘密；
9. 对公开下载前的输入 Mod、转换产物和日志执行许可证及隐私审阅。

应用层上传上限不能替代反向代理和操作系统资源限制。压缩包炸弹、ZIP Slip、异常路径及归档条目限制仍由 CLI 的 `SafeSourceReader` 二次检查。

服务端会拒绝浏览器标记为 cross-site 的状态修改请求，并在存在 `Origin` 时要求其 authority 与 `Host` 一致；静态响应还设置 CSP、禁止嵌入 frame 并关闭不需要的浏览器权限。这些是纵深防御，不是账号认证的替代品。

示例 Nginx 片段（认证方式需由部署者补充）：

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $http_host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;       # 保持 SSE 实时输出
    proxy_read_timeout 1h;
    client_max_body_size 64m;
}
```

启动后端前还需配置例如 `MOD_DP_BRIDGE_ALLOWED_HOSTS=bridge.example.com`（只写域名，不写协议或端口）。Nginx 的 `$http_host` 保留浏览器原始 `Host`，包括非默认外部端口；`$host` 可能丢失该端口，导致 Origin authority 与后端所见 `Host` 不一致并返回 `403`。

`Host` 必须与浏览器所见站点一致，否则 Web 服务的 Origin/Host 同源检查会拒绝状态修改请求并返回 `403`。未列入白名单的 `Host` 会在此前被以 `421 host_not_allowed` 拒绝。仅设置 Host 白名单和 `client_max_body_size` 不构成完整安全部署；在认证完成前不要监听公网接口。

## 数据保留与故障排查

- 作业到期后可能被清理，因此应及时下载 DP、日志和报告；
- `succeeded` 但没有可下载 ZIP：检查终端日志、Web 服务日志和工作目录权限；
- SSE 中断但转换仍在运行：刷新页面或重新查询 `GET /api/jobs/{id}`，完成后下载完整日志；
- 作业长期 `queued`：检查并发上限以及先前任务是否仍在运行；
- 作业 `failed`：优先下载全部日志，并检查转换输出中的 `failure-report.txt`、`failure-diagnostics.json` 或 `report.json`；
- 终止后仍看到短暂日志：进程终止和管道收尾存在时间差，以最终 `cancelled` 状态为准；
- B480 apply 失败：确认 `MOD_DP_BRIDGE_SERVER_JAR` 精确匹配 v159.7/B480 且来源可信。

转换结果的人工验收步骤见 [`TESTING.md`](TESTING.md)。
