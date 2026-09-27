# 交接与恢复（新会话从这里开始）

最后更新：2026-09-27。本文件的作用：**让一个没有本次对话上下文的会话，靠仓库 + 磁盘就能原样接手**。

## 一、当前主线

- 项目：`mod-dp-bridge`（Mindustry Mod/CP/DP → Data Assets 转换器）。
- 主线目标：**绑定 Saturation Firepower（SF）**，生产目标版本 **Mindustry v160.5**（v159.7 保留为可接受目标）。
- 最近提交：
  - `b943185` Capture and reproduce Mod changes to target content as a Data Patch（原版覆盖全链路 + 测试）
  - `1c576c2` Add the resume and handover note for a fresh session
  - `50771d7` Record the vanilla-override design and SFOverride mutation survey
- 工作树干净，全仓测试通过（`scripts/gradle.ps1 test`）。

## 二、必读文档（按顺序）

1. `docs/research/VANILLA_OVERRIDE_PLAN.md` — **下一个任务**：原版覆盖（开关、监视表、运行时差分、原因词表、验收标准）。
2. `docs/research/SF_GAP_ANALYSIS.md` — SF 4.0.6 → v160.5 的内容闭包现状与已修项。
3. `docs/research/JOIN_CRASH_MECHANISM.md` — 多人进服崩溃的源码级机制（为什么"缺内容"会崩）。
4. `docs/research/SF_406_FUNCTION_INVENTORY.md` — SF 全量功能清单与可表达性矩阵、崩溃风险点（68KB，证据带 path:line）。
5. `docs/research/TARGET_1605_DP_DELTA.md` — v159.7→v160.5 的 DP 机制差异。
6. `docs/SF_CRASH_TRIAGE.md` — 崩溃现场采集流程（客户端崩溃报告自动落盘位置）。

## 三、磁盘上的样本与工具（**未入库**，在 `work/` 下）

| 用途 | 路径 | SHA-256 |
|---|---|---|
| 目标服务端（官方 v160.5） | `work/samples/downloads/mindustry-v160.5-server-release.jar` | `0bd327c6c3d551e7e8fdab7b695517f809baacca3b1f5cb1c1a8dd74836620e0` |
| 被转换 Mod（SF 4.0.6 官方 release） | `work/samples/downloads/Saturation-Firepower-4.0.6.jar` | `895b76ba8a6a4e5eeb358c583a70056ba2d808260694fb20b16031c553c07ba6` |
| SF 源码（tag `v-4.0.6`，commit `db983066`） | `work/samples/Saturation-Firepower-4.0.6/` | — |
| Mindustry v160.5 源码（sparse，仅 core/src） | `work/samples/Mindustry-160.5/core/src/` | — |
| 旧目标服务端（官方 v159.7） | `work/mindustry-v159.7-server-release.jar` | `e41289c32bcf765eb50fa131e6b515d741e20f7843fb567d3aa949e7461f22ab` |
| 生产环境实际服务端（官方 **160.3**） | `..\mdtdo\mdtserver\server-2026.09.13.B495.jar` | — |
| 内容 id 表指纹探针（自写） | `work/probe/ContentIdProbe.java` | — |

重新获取：

```text
https://github.com/Anuken/Mindustry/releases/download/v160.5/server-release.jar
https://github.com/RA2EXE/Saturation-Firepower/releases/download/v-4.0.6/Saturation-Firepower-4.0.6.jar
git clone --depth 1 --branch v-4.0.6 https://github.com/RA2EXE/Saturation-Firepower
git clone --depth 1 --branch v160.5 --filter=blob:none --sparse https://github.com/Anuken/Mindustry   # 然后 sparse-checkout set core/src
```

可实测产物（上一轮验证通过，闭包 372/372）：

```text
work/sf406-v1605-final1/Saturation-Firepower-4.0.6-dp-v160.5.zip
size   7,245,754 bytes
sha256 0003334AF584F2F113890A8ADA3018FE18912C0612ACA8978517668D169AF288
server-assets/  work/sf406-v1605-final1/server-assets/   (2337 个外部资产)
```

**原版覆盖（已接通，默认 on）**：带 `--source` 运行时，产物内会多出 `patches/vanilla-overrides.hjson`，
把该 mod 对原版的改动一并带进 DP。关键中间产物与实测数字（SF 4.0.6 + v160.5）：

```text
-vanilla-overrides.json      # 探针四相位 → 判定（在输出目录根部）
-vanilla-override-mapping.json  # patch 映射 + 削减明细（原因码）
work/sf406-v1605-override4/  # 一轮完整运行（exit 0，含 @NoPatch 过滤）
  DP ZIP 7,246,646 bytes
  sha256 6D1FBF802DF3F5FA36812E54A0B1A9127FCB591C3AD9867C00AC7A4D2D83CADF
  watch 116 名 / 18756 个可 patch 字段 → 233 处改动（loadTime=91, afterInit=142）
  patch 30 个内容 / 60 个字段（2341 字节）；削减 173（引用未迁移 147 + 嵌套对象 26）
  DataPatcher.apply: content=372 added=711 failed=0 warnings=0
  provenance: SFire/{SFOverride,TechFix,SFBlocks,SFUnitTypes,SFLiquids,SFStatusEffects,SFPlanets,SFTechTree}.class
```

（`override3` 与 `override4` 的 patch 字节完全一致，说明判定是确定性的；差别只在捕获时多跳过了
1384 个 `@NoPatch` 字段。）

关闭开关：`--vanilla-overrides=off`（Web 端取消勾选“还原该 mod 对原版内容的修改”）。
设计细节与被源码推翻的两处原始判断见 `docs/research/VANILLA_OVERRIDE_PLAN.md` 第七、八节。

**可交付实测的 DP（当前最新，5 轮实测中第 5 轮）**：

```text
work/dp-test-sf406-v1605/                       # 测试包（见包内 README-测试说明.md）
  Saturation-Firepower-4.0.6-dp-v160.5.zip      7,247,210 bytes
  sha256 B4CB419CE195D6CBA9512E374C8EFBDBEAC93FD3AA26935D579AA72ABE010E4C
  config-assets/                                2710 个文件（= 服务端 <server>/config/assets）
  install-to-server.ps1                         一键安装（自动备份已有 config/assets）
  verification.json / 验收对照表.txt / patches-vanilla-overrides.hjson
  # 部署：copy config-assets/* → <server>/config/assets/，客户端 v160.5 且不要装 sfire-mod
```


## 四、环境事实与实测命令

- JDK 21（`JAVA_HOME=C:\Program Files\Java\jdk-21`），Gradle wrapper 9.2.1；仓库路径含中文，**必须**用包装脚本（它通过 ASCII junction 运行，避免 test worker classpath 编码问题）：

```powershell
.\scripts\gradle.ps1 test :bridge-cli:installDist --no-daemon
.\scripts\gradle.ps1 test --rerun-tasks --no-daemon        # 强制全量
```

- git 身份只在本仓库内配置（`QQQW114 <qwrt114@163.com>`），未改全局。
- SF E2E（本轮一直用这一条）：

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat runtime-convert `
  --mod-jar ".\work\samples\downloads\Saturation-Firepower-4.0.6.jar" `
  --source ".\work\samples\Saturation-Firepower-4.0.6\src" `
  --server-jar ".\work\samples\downloads\mindustry-v160.5-server-release.jar" `
  --allow-mod-execution -o ".\work\<新目录>" `
  --runtime-timeout 300 --server-timeout 240 --hybrid-max-rounds 8
```

- 闭包校验（运行时注册名 ↔ 产物声明）：读 `runtime-snapshot.json` 的 `contents[]`，
  按"剥离**全部** `<mod>-` 前缀"归一化后与 `server-assets/content/<kind>/*.hjson` 的 basename 比对；
  参考本轮结果：runtime 374、可映射 372、emitted 372、missing 0（Planet/Sector 按边界不迁移）。

## 五、纪律与坑（踩过的）

1. **不要 dump 原始大 JSON**（`hybrid-report.json` 有 300+ 路径）。改成脚本只打印汇总。
2. **patch 绝不能携带 `requiredPlanets`**：headless 服务器会在 `DataPatcher.java:224-247` 永久丢弃该 patch，
   导致服务端与客户端内容集分叉 —— 正是已知崩溃类别。
3. **原版差分不需要"对照空跑"**（原设计曾如此假设，已被源码推翻）：探针在 `createModContent()` 入口取
   干净基线，四相位都在同一次运行内。原因见 `VANILLA_OVERRIDE_PLAN.md` 第八节。
4. **静态扫描拿到的是 Java 字段名**（`Blocks.titaniumConveyor`），而注册表与 patch 用的是内容名
   （`titanium-conveyor`）。探针按"注册表名 → 声明类字段 → kebab"顺序解析，产物里同时保留
   `name`（字段名）与 `runtimeName`（注册表名）；patch 必须用后者。
5. **每个 `RuntimePreparedFile` 必须有真实 release-JAR provenance**，否则转换器以
   `RUNTIME_PREPARED_PROVENANCE_MISSING` 硬失败。patch 的 provenance 由监视表命中的源码文件
   （`.java` → `.class`）解析得到。
6. **`@NoPatch` 字段不能进 patch**（DataPatcher 会记 warning，而验证要求 0 warning）：探针在捕获原版字段时
   直接跳过它们（`Block.size`/`hasItems`/`buildType` 等）。
7. **绝不整块丢弃内容**：只要根 parser 可构造就必须产出；嵌套武器/部件/特效缺失一律降级保留并给出原因码
   （词表见 `VANILLA_OVERRIDE_PLAN.md` 第四节）。
8. 用户可读中文；提交信息与代码注释用英文（与仓库既有风格一致）。

## 六、下一步（用户已确认的优先级）

1. ~~**原版覆盖**~~ **已完成**（`b943185`）：开关 `--vanilla-overrides=on|off`（默认 on），
   范围含方块+单位+状态+物品/液体，引擎为"同轮四相位运行时差分 + 静态监视表"。
2. **进一步还原单位/建筑**：缺失武器/部件不丢单位，只做削减 + 明确原因（词表见
   `VANILLA_OVERRIDE_PLAN.md` 第四节）。原版覆盖已证明 `ammoTypes`/`weapons` 这类嵌套对象仍是最大一类削减
   （26 条 `NESTED_OBJECT_OMITTED`），长期方向是 `docs/RUNTIME_OBJECT_GRAPH_PLAN.md` 的纯运行时对象图（schema v3）。
3. 之后：闭包 + 部署形态指纹验证阶段（`SF_GAP_ANALYSIS.md` 第三节第 3 项）。
4. 小项：`vanilla-overrides.hjson` 目前只覆盖标量/数组标量；`shownPlanets`/`databaseTabs` 因为引用未迁移的
   星球（`sfire-mod-tiberia`）一律丢弃 —— 若将来迁移 planet/sector，应重新纳入。
