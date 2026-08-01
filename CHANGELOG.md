# Changelog

本项目遵循语义化版本号。由于转换器仍处于实验阶段，`0.x` 版本之间可能调整命令、报告和 HTTP API。

## Unreleased

### 修复

- 修复第三方 Mod 元数据/content 中以裸 `"`、`'` 或 `"""` 书写的多行字符串导致 JSON/HJSON 解析直接失败的问题（hjson-java 不接收字符串内裸换行）。原生解析失败后自动改写为转义单行字符串，并发出 `MULTILINE_STRING_COMPATIBILITY_REPAIR` 警告；legacy CP 路径同样接入该修复。

### 变更

- `scripts/start-web.ps1` / `start-web.bat` 默认开启运行时转换：未传 `-ServerJar` 时自动探测仓库内 `work/mindustry-v159.7-server-release.jar`，存在且通过固定 SHA-256 校验即启用运行时；默认 JAR 缺失时回退静态模式并提示。新增 `-NoRuntime` 参数可显式关闭。

## 0.2.0 - 2026-08-02

### 新增

- 新增可信本地 `runtime-convert` 主线：在独立 JVM 中使用固定的官方 Mindustry v159.7/B480 Server JAR 真实加载发布 Mod JAR，并提取三阶段 `Vars.content` 快照。
- 新增运行时 Item、Liquid/CellLiquid、StatusEffect 映射，以及基于运行时注册、可选源码 AST、JAR class/行号来源和官方 DataPatcher 的 Block/Unit 候选筛选。
- 新增发布 JAR 资产归属、冲突检查、正式打包和最终零失败、零警告 DataPatcher 验证。
- 本地 Web UI 新增显式双模式：保留安全静态转换，并可在操作员预先启用后提交“发布 JAR + 可选源码 ZIP”的可信运行时转换任务。
- Web 作业继续提供队列、进度、终端日志、取消、结果下载、完整审计日志和折叠报告。
- 新增 Windows `start-web.bat` / `scripts/start-web.ps1` 快捷启动器，支持仓库首次自动构建与解压分发包直接运行，并可显式启用可信运行时模式。

### 安全

- 运行时 Web 功能默认关闭，并且只允许 loopback 本机使用。
- 启用运行时模式必须由操作员配置固定官方 Server JAR、显式允许 Mod 执行；每个运行时作业还必须再次确认信任输入 JAR。
- Mod JAR 会以 Web/CLI 进程所属用户的文件、网络和系统权限真实执行。独立 JVM、临时目录、预算、超时和进程树终止都不是恶意代码沙箱。
- 源码目录或源码 ZIP 只作静态候选，不编译、不执行，也不运行 Gradle、Maven 或脚本。
- Web UI 没有认证、授权或租户隔离，不支持公网、远程上传或多用户部署。

### 验证与边界

- 发布前最终全仓自动测试为 131/131 通过，0 failed / 0 errors / 0 skipped。
- New Horizon 2.2.1 的 JAR + 源码自动链路生成 143 个 Content、1635 个外部资产，最终 DataPatcher 为 0 failed / 0 warning。
- 用户在真实客户端确认该产物能够加载并出现新内容，但炮塔缺少弹药，退出地图时崩溃。该 Mod 大量依赖自定义 Java 行为，不适合作为 DP 兼容率样本，也不能把自动 apply 或客户端导入成功解释为玩法等价。
- 目标版本仍仅为 Mindustry v159.7 / Build 480；GUI、网络协议、科技树、Planet/Sector、Mod 地图及通用自定义 Java 行为不迁移。
