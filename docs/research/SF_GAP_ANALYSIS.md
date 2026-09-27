# Saturation Firepower 4.0.6 → v160.5 差距分析（首次实测）

实测日期：2026-09-27。输入：`Saturation-Firepower-4.0.6.jar`（sha256 `895b76ba…7ba6`）+
tag `v-4.0.6` 源码；目标：官方 **v160.5** server-release（sha256 `0bd327c6…20e0`）。

产物目录：`work/sf406-v1605-hybrid2/`
DP ZIP：`Saturation-Firepower-4.0.6-dp-v160.5.zip`

## 一、当前结果

| 指标 | 数值 |
|---|---:|
| 运行时注册内容（本 mod） | **374** |
| 产物内容声明 | **361** |
| 其中 即时运行时映射（Item/Liquid/Status） | 45 |
| 其中 runtime-guided Block/Unit 候选 | 316（Block 253 + Unit 63） |
| 外部资产 | 2307 |
| DataPatcher 候选筛选 | accepted 316 / rejected 1 / unresolved 0 |
| 最终官方 v160.5 `DataPatcher.apply` | total 2668 / content 361 / patch 0 / external 2307 / **failed 0 / warning 0** / added 697 |
| CLI | exit 0，pipeline `completed` |

对比历史 v159.7 静态基线：358 内容 / 1628 资产 / 0 failed / 0 warning。
本次为 **v160.5 + runtime 确认来源**，资产数显著更多（2307），且 provenance 更强。

## 二、名字级闭包缺口（13 项）

按类型统计（runtime vs 产物）：Item 15/15、Liquid 7/7、Status 23/23、Unit 63/63、
**Block 264/253（缺 11）**、Weather 0/0，另有 Planet 1、Sector 1 未迁移。

### 缺 11 个方块的确切原因（来自 `hybrid-report.json`）

| # | 方块 | 原因码 | 具体原因 | 修法 |
|---:|---|---|---|---|
| 1 | `explosive-armor` | `HYBRID_FALLBACK_TYPE_MISMATCH` | AST 目标类型 `Wall` ≠ 运行时 fallback `Block` | 以**运行时 fallback 为准**生成（降级但存在），不要丢弃 |
| 2 | `explosive-armor-large` | 同上 | 同上（`ExplodeWall` 自定义 build 类不在 ClassMap） | 同上 |
| 3 | `guangyin` | 同上 + `JAVA_CUSTOM_FIELD_OMITTED` | AST `PowerTurret` ≠ 运行时 fallback `Turret` | 同上 |
| 4 | `poxiao` | 同上 + `JAVA_LAMBDA_EFFECT_APPROXIMATED` | 同上 | 同上 |
| 5 | `memory-wall` | `HYBRID_FALLBACK_TYPE_MISMATCH` | AST `MemoryWall` ≠ 运行时 fallback `Wall` | 同上 |
| 6 | `memory-wall-large` | 同上 | 同上 | 同上 |
| 7-10 | `ore-chromium` / `ore-fermium` / `ore-rubidium` / `ore-strontium` | `HYBRID_RUNTIME_REGISTRATION_MISSING` | mod 把方块名写成 `ore-sfire-mod-<item>`，运行时全名 `sfire-mod-ore-sfire-mod-<item>`；只剥一层前缀导致匹配失败 | 名字归一化匹配（允许运行时名以 `<mod>-ore-` 等前缀+AST 局部名结尾） |
| 11 | `large-landing-pad` | DataPatcher 警告 → 严格剔除 | `[large-landing-pad.hjson]: Unknown field 'spacing' for class 'RadialEffect'` | 刷新到 **v160.5 字段 schema**（`spacing` 不是 v160.5 `RadialEffect` 字段），不要为此丢方块 |

> 全部 11 项的共同点：**都是"可以以降级形式存在"的内容**。当前策略选择丢弃，会直接造成
> 地图/建造计划引用这些方块时客户端解析不到（见 `JOIN_CRASH_MECHANISM.md`）。

Planet `sfire-mod-tiberia`、Sector `sfire-mod-frozenFront` 属项目既定边界（Planet/Sector 不迁移）。

## 三、尚未覆盖的功能面（下一步）

1. **SFOverride（`src/SFire/content/SFOverride.java`，31KB）**：对原版内容的修改目前**完全没有产出 patch 资产**
   （本产物 patch=0）。这既是"功能还原"缺口，也影响 `requiredPlanets` 等分支行为。
2. **11 个方块的闭包缺口**（上表）。
3. **产物身份校验**：需要在管线内自动比对
   - 运行时注册名字集 vs 产物声明集（本次缺口即由此发现）；
   - `server-assets/` 目录 vs DP ZIP 两种部署形态加载后的内容 id 表指纹是否一致。
4. **纯运行时对象图映射**（`docs/RUNTIME_OBJECT_GRAPH_PLAN.md` 的 schema v3）：目前 Block/Unit 依赖源码 AST
   候选；对无源码场景仍然缺能力。

## 四、建议的执行顺序

1. **闭包修复（11 个方块）**：三类根因分别处理，产出 374/374 名字级闭包。
2. **管线内闭包 + 指纹校验**：把"运行时注册名 ↔ 产物声明"和"server-assets ↔ ZIP 内容表指纹"
   做成正式验证阶段，缺口直接失败并给出清单（防止此类崩溃再次静默出海）。
3. **SFOverride patch 产出**：把对原版内容的修改表达为 `patches/` 资产。
4. 纯运行时对象图映射（长期）。
