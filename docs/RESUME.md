# 交接与恢复（新会话从这里开始）

最后更新：2026-09-27。本文件的作用：**让一个没有本次对话上下文的会话，靠仓库 + 磁盘就能原样接手**。

## 一、当前主线

- 项目：`mod-dp-bridge`（Mindustry Mod/CP/DP → Data Assets 转换器）。
- 主线目标：**绑定 Saturation Firepower（SF）**，生产目标版本 **Mindustry v160.5**（v159.7 保留为可接受目标）。
- 最近两个提交：
  - `f8e6977` Retarget the main line to Saturation Firepower on Mindustry v160.5
  - `50771d7` Record the vanilla-override design and SFOverride mutation survey
- 工作树干净，全仓测试通过（`scripts/gradle.ps1 test`，133+ 项）。

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
3. **原版差分要做"对照空跑"**：原版内容自身的 `init()` 会自然改变某些字段（如 gas 液体的 alpha），
   必须用一次"不装 Mod 的原版运行"作为对照，只保留两次运行之间的差异。
4. **绝不整块丢弃内容**：只要根 parser 可构造就必须产出；嵌套武器/部件/特效缺失一律降级保留并给出原因码
   （词表见 `VANILLA_OVERRIDE_PLAN.md` 第四节）。
5. 用户可读中文；提交信息与代码注释用英文（与仓库既有风格一致）。

## 六、下一步（用户已确认的优先级）

1. **原版覆盖**：开关 `--vanilla-overrides=on|off`（**默认 on**），范围含方块+单位+状态+物品/液体，
   引擎为"运行时差分 + 静态监视表"。按 `VANILLA_OVERRIDE_PLAN.md` 第一至五节执行。
2. **进一步还原单位/建筑**：缺失武器/部件不丢单位，只做削减 + 明确原因（同上词表）；
   长期方向是 `docs/RUNTIME_OBJECT_GRAPH_PLAN.md` 的纯运行时对象图（schema v3）。
3. 之后：闭包 + 部署形态指纹验证阶段（`SF_GAP_ANALYSIS.md` 第三节第 3 项）。
