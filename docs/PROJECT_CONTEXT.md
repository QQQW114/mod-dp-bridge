# 持久上下文

最后更新：2026-08-01（Asia/Shanghai）

本文件用于上下文压缩、新会话和任务交接。恢复工作时先阅读：

- `README.md`
- `docs/PROJECT_STATUS.md`
- `docs/WEB_UI.md`
- `docs/CLI_RUNTIME_INTEGRATION.md`
- `docs/DYNAMIC_RUNTIME_EXTRACTION.md`
- `docs/RUNTIME_SNAPSHOT_SCHEMA.md`
- `docs/RUNTIME_MAPPING_STATUS.md`
- `docs/HYBRID_RUNTIME_SOURCE_PLAN.md`
- `docs/RUNTIME_ASSET_STAGING.md`

## 当前主线

0.2.0 当前主线是本地 CLI 与默认关闭运行时能力、仅 loopback 的本地 Web UI：

```text
可信发布 JAR
-> 官方 v159.7 独立 JVM 加载
-> 三阶段 Vars.content 快照
-> 动态 Item/Liquid/Status 映射
-> 可选源码 AST Block/Unit 候选
-> 官方 DataPatcher 单调筛选
-> JAR 资产 + 正式打包
-> 最终官方 DataPatcher.apply
```

不使用 javaagent。源码不执行、不编译、不运行 Gradle/Maven；发布 JAR 和 Server JAR 会执行。Web 只编排同一 CLI，不增加转换或隔离能力。

## 固定安全约束

- 动态命令：`runtime-convert`；
- 必须显式 `--allow-mod-execution`；
- 官方 Mindustry v159.7/B480 Server JAR SHA-256：
  `E41289C32BCF765EB50FA131E6B515D741E20F7843FB567D3AA949E7461F22AB`；
- 独立 JVM 不是安全沙箱，只处理可信 JAR；
- Mod JAR 是运行时内容和资产权威；
- 源码只提供静态候选，不能覆盖 JAR资产；
- Web 运行时能力服务端默认关闭（fail-closed）；`start-web.bat` 会探测 `work/mindustry-v159.7-server-release.jar`，存在且通过固定 SHA-256 校验即自动开启，`-NoRuntime` 可显式关闭；
- 每个 Web 运行时作业必须再次确认信任，Server JAR 不能由浏览器上传或指定；
- Web 仅允许 loopback，没有认证、授权、租户隔离或沙箱，不得用作远程上传服务；
- 目标只支持官方 v159.7/B480。

## 当前已验证基线

New Horizon 2.2.1 自动 E2E 已通过：

```text
input JAR:
<local-downloads>\NewHorizonMod.2.2.1.jar

input source ZIP:
<local-downloads>\NewHorizonMod-2.2.1.zip

official server:
work/mindustry-v159.7-server-release.jar

output:
work/runtime-convert-new-horizon-final2-20260801/

DP ZIP:
work/runtime-convert-new-horizon-final2-20260801/NewHorizonMod.2.2.1-dp-v159.7.zip

ZIP SHA-256:
86721D815437D7039CF950E56C409039D79B800C7D2D6EEAC8175E692C7F61FE
```

上述 `work/` 是本地验证目录，受 `.gitignore` 排除，不随公开仓库分发。

结果：

```text
382 runtime Content
1572/1572 JAR classes linked to source
1602/1602 JAR assets linked to source
56 dynamic Item/Liquid/Status
141 hybrid candidates
87 accepted = 85 Block + 2 Unit
54 rejected, 0 unresolved
143 final content files
1635 external assets
DataPatcher added=145
failed=0
warnings=0
report errors=0
CLI exit=0
```

验证阶段：

```text
STRUCTURE = PASSED
RUNTIME = PASSED
MAP_IMPORT = NOT_RUN
SERVER_LOAD = NOT_RUN
```

后续人工结果：客户端能够加载并出现新内容，但炮塔缺少弹药，退出地图时崩溃。
因此 New Horizon 不适合作为总体兼容率样本；它只证明自动提取、筛选、打包和导入链路
能够运行，不能证明玩法语义等价。服务器地图/存档加载仍未执行。

## 关键实现事实

### Extractor

- `PRE_CONTENT_INIT`、`POST_CONTENT_INIT`、`FINAL_AFTER_MOD_INIT` 三阶段；
- 允许单调晚注册，不允许已有 Content 消失；
- 使用每项最早可用快照作为权威字段源；
- 不调用 Mod override/toString；
- Arc `Seq.items` 必须按 `Object[]` 访问；
- ServerLoadEvent 后通过 posted task 延迟 final snapshot，避免抢在 Mod handler 前退出；
- 默认 worker `-Xmx1024m`，有快照大小和对象读取预算。

### Runtime mapper

- `bridge-runtime-mapper` 当前直接生成 Item、Liquid/CellLiquid、StatusEffect；
- Block/Unit 根字段已经进入快照，但通用动态对象图映射尚未完成；
- 未支持项逐项记录，不能静默丢弃；
- Block/Unit-only Mod 可以以空动态 baseline 进入 Hybrid；最终仍无内容时打包前失败。

### Hybrid

- 只处理 runtime 真注册、动态 mapper unsupported 的 Block/Unit；
- 名称、kind、fallback 必须匹配；
- source-index 必须把源码精确链接到发布 JAR class；
- candidate declaration line 必须命中该 class `LineNumberTable`；
- FAILED source outcome、parse ERROR、路径异常、来源不完整均 fail-closed；
- accepted 内容仍为 DEGRADED；
- Java class fileResult 保持原 unsupported/excluded，并增加 Java 行为未迁移诊断。

### Candidate selector

- 先验证 runtime base；
- 再验证全部候选；
- 精确按候选路径移除 warning/failure；
- 重验依赖闭包；
- 协议、Harness、无法归属或轮次耗尽时回退 runtime base；
- 最终正式 apply 必须 0 failed / 0 warning。

## 主要文件

```text
bridge-runtime-extractor/src/main/java/io/github/moddpbridge/runtimeextractor/RuntimeExtractorMain.java
bridge-runtime-extractor/src/main/resources/runtime-trace-probe.java.template
bridge-runtime-extractor/src/main/resources/runtime-snapshot-support.java.template

bridge-runtime-assets/src/main/kotlin/io/github/moddpbridge/runtimeassets/RuntimeAssetStager.kt
bridge-runtime-mapper/src/main/kotlin/io/github/moddpbridge/runtimemapper/RuntimeSnapshotMapper.kt

bridge-java-static/src/main/kotlin/io/github/moddpbridge/javastatic/hybrid/RuntimeStaticHybrid.kt
bridge-java-static/src/main/kotlin/io/github/moddpbridge/javastatic/hybrid/RuntimeStaticHybridInputs.kt

bridge-cli/src/main/kotlin/io/github/moddpbridge/cli/RuntimeConvertCommand.kt
bridge-cli/src/main/kotlin/io/github/moddpbridge/cli/RuntimeConversionPipeline.kt
bridge-cli/src/main/kotlin/io/github/moddpbridge/cli/RuntimeExtractorProcess.kt
bridge-cli/src/main/kotlin/io/github/moddpbridge/cli/RuntimeHybridSourceSelection.kt
bridge-cli/src/main/kotlin/io/github/moddpbridge/cli/MonotonicDataPatchCandidateSelector.kt

bridge-web/src/main/kotlin/io/github/moddpbridge/web/WebConfig.kt
bridge-web/src/main/kotlin/io/github/moddpbridge/web/JobManager.kt
bridge-web/src/main/kotlin/io/github/moddpbridge/web/MultipartUpload.kt
bridge-web/src/main/kotlin/io/github/moddpbridge/web/BridgeWebServer.kt
```

## 构建与测试

Windows 中文路径必须优先使用：

```powershell
.\scripts\gradle.ps1 ... --no-daemon
```

运行时主线重点测试：

```powershell
.\scripts\gradle.ps1 `
  :bridge-runtime-extractor:test `
  :bridge-java-static:test `
  :bridge-runtime-assets:test `
  :bridge-runtime-mapper:test `
  :bridge-converter:test `
  :bridge-cli:test `
  --no-daemon --rerun-tasks
```

运行时重点测试集：102/102 通过；0.2.0 最终全仓为 131/131 通过。新增的
13 项 Web 测试覆盖双文件、执行同意、fail-closed、Request-ID、取消竞态、终态产物与审计下载。

构建 CLI 与 Web：

```powershell
.\scripts\gradle.ps1 :bridge-cli:installDist :bridge-web:installDist --no-daemon
```

New Horizon E2E：

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat runtime-convert `
  --mod-jar "<local-downloads>\NewHorizonMod.2.2.1.jar" `
  --source "<local-downloads>\NewHorizonMod-2.2.1.zip" `
  --server-jar ".\work\mindustry-v159.7-server-release.jar" `
  --allow-mod-execution `
  --hybrid-max-rounds 8 `
  -o ".\work\runtime-convert-new-horizon-final2-20260801" `
  --overwrite `
  --runtime-timeout 180 `
  --server-timeout 60
```

## 当前不能声称

即使 final apply 为 0 failed / 0 warning，也不能声称：

- 85 个 Block 和 2 个 Unit 的玩法完整等价；
- 全部炮塔开火、工厂生产、单位 AI、Ability、Effect 和 Consume 已验证；
- Desktop atlas、full icon、outline、音频播放无误；
- 地图保存、退出、重开无误；
- B480 服务器已加载携带 DP 的真实地图/存档；
- 自定义 Java 方法、回调、实体、GUI、网络和科技树已经迁移。

## 下一步优先级

1. 用更少自定义 Java 行为的代表 Mod 建立实际兼容样本。
2. 根据炮塔缺弹药等真实失败扩展纯运行时 Unit/Block mapper 和缺失项诊断。
3. 用服务器加载实际地图/存档。
4. 完成本地 Web 双模式、loopback、安全开关和安装分发包烟测。
5. 继续保证所有缺失、降级、剔除和失败项都有日志与报告。

## 历史但仍有价值的材料

- Saturation Firepower 静态 AST 迁移和 Desktop 导入：`docs/SATURATION_FINAL_VALIDATION_20260730.md`；
- B480 atlas/import 修复：`docs/B480_CLIENT_IMPORT_FIX_20260730.md`；
- B480 退出崩溃：`docs/B480_EXIT_UNLOAD_CRASH_20260730.md`；
- 客户端上游补丁：`docs/patches/DataImagePacker-unload-fix.patch`；
- Web UI 早期静态实现的 Git 历史仍可用于回归；当前规范见 `docs/WEB_UI.md`。
