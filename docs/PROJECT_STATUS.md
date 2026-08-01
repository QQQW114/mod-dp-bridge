# 项目进度

最后更新：2026-08-01（Asia/Shanghai）

## 当前结论

项目 0.2.0 主线是**可信本地运行时转换 CLI + 默认关闭运行时能力、仅 loopback 的本地 Web UI**：

```text
可信发布 JAR
  -> 官方 Mindustry v159.7 独立 JVM 真实加载
  -> Vars.content 三阶段快照
  -> Item / Liquid / StatusEffect 动态映射
  -> 可选源码 AST Block / Unit 候选
  -> 官方 DataPatcher 单调筛选
  -> 发布 JAR 资产
  -> 正式打包
  -> 官方 DataPatcher.apply 最终复验
```

该链路已在 New Horizon 2.2.1 的发布 JAR + 对应源码 ZIP 上完整自动跑通，最终复现此前手工实验的 **143 个 Content、0 failed、0 warning** 基线。当前可以认为“根据 Mindustry 真实加载结果反推并生成 DP”已经从可行性实验进入可用原型阶段；Web UI 只编排同一 CLI，不增加映射能力。

准确边界仍是：DataPatcher 可加载不等于完整玩法等价；自定义 Java 方法、回调、实体、AI、GUI、网络协议、科技树和 Mod 新地图不迁移。

## New Horizon 自动 E2E 与人工结果

输入文件名：

```text
NewHorizonMod.2.2.1.jar
NewHorizonMod-2.2.1.zip
```

官方 Server：

```text
work/mindustry-v159.7-server-release.jar
SHA-256: E41289C32BCF765EB50FA131E6B515D741E20F7843FB567D3AA949E7461F22AB
```

最终本地回归输出目录（`work/` 不入库）：

```text
work/runtime-convert-new-horizon-final2-20260801/
```

DP ZIP：

```text
work/runtime-convert-new-horizon-final2-20260801/NewHorizonMod.2.2.1-dp-v159.7.zip
```

ZIP SHA-256：

```text
86721D815437D7039CF950E56C409039D79B800C7D2D6EEAC8175E692C7F61FE
```

### 自动流水线结果

| 指标 | 结果 |
|---|---:|
| 官方运行时实际注册 Content | 382 |
| 三阶段 Content 数 | 382 / 382 / 382 |
| JAR class 与源码关联 | 1572 / 1572 |
| JAR 资产与源码关联 | 1602 / 1602 |
| 动态生成 Item/Liquid/Status | 56 |
| Hybrid Block/Unit 候选 | 141 |
| Hybrid 接受 | 87 |
| 接受 Block / Unit | 85 / 2 |
| Hybrid 拒绝 / unresolved | 54 / 0 |
| 最终根 Content 文件 | 143 |
| 最终外部资产 | 1635 |
| DataPatcher added content | 145 |
| DataPatcher failed / warning | 0 / 0 |
| 最终报告 error | 0 |

最终 Content 结果：

| 状态 | 数量 |
|---|---:|
| converted | 29 |
| degraded | 114 |
| unsupported | 239 |
| failed | 0 |

按类型的主要结果：

- Item：18 converted；
- Liquid：10 converted + 7 degraded；
- Status：1 converted + 20 degraded；
- Block：85 degraded + 196 unsupported；
- Unit：2 degraded + 38 unsupported；
- 其余 Weather/未知根类型保持 unsupported。

所有 Hybrid 接受项均保持 `degraded`。它们通过了官方 parser/apply，但其 Java-only 行为没有被宣称为已迁移。

### 验证状态

- `STRUCTURE = PASSED`
- `RUNTIME = PASSED`
- `MAP_IMPORT = NOT_RUN`
- `SERVER_LOAD = NOT_RUN`

以上是自动报告中记录的阶段。用户随后在真实 v159.7/B480 客户端确认产物能够加载并出现新内容，但炮塔缺少弹药，退出地图时客户端崩溃；服务器地图/存档加载仍未执行。

这说明导入和注册链路可用，但功能验收不通过。New Horizon 大量依赖自行编写的 Java 内容与行为，不适合作为项目总体兼容率样本；它更适合用于说明 DataPatcher 零失败、零警告和客户端能加载仍不能证明玩法等价。

## 已完成的主线能力

### 运行时提取

- 新命令：`runtime-convert`；
- 必须显式 `--allow-mod-execution`；
- 只接受固定 SHA-256 的官方 v159.7/B480 Server JAR；
- 在独立 JVM、隔离工作目录中加载发布 Mod JAR；
- 在 `PRE_CONTENT_INIT`、`POST_CONTENT_INIT`、`FINAL_AFTER_MOD_INIT` 捕获三阶段快照；
- 支持 `PRE ⊆ POST ⊆ FINAL` 的合法晚注册 Content；
- 映射时使用每个 Content 最早可用的权威阶段；
- 不调用 Mod 可覆写的 `Content.getContentType()` 或任意对象 `toString()`；
- 修复 Arc `Seq.items` 实际 `Object[]` 导致的泛型数组强转崩溃；
- worker 默认最大 heap 1024 MiB，并有超时、单记录、字段、容器、根快照和总快照字节预算。

### 动态映射

- 版本绑定模块：`bridge-runtime-mapper`；
- 当前直接生成 Item、Liquid/CellLiquid、StatusEffect；
- 捕获 Unit/Block 根 parser 字段，作为后续纯运行时 mapper 数据基础；
- 对未支持 Content 逐项保留 fallback、来源位置和诊断；
- 0 个动态 I/L/S 的 Block/Unit-only Mod 仍可进入 Hybrid；若最终仍没有任何内容声明，则在打包前明确失败，不生成空 DP。

### 资产

- `bridge-runtime-assets` 从发布 JAR 选择 bundle、sprite、generated、sound、music 等资产；
- 发布 JAR 是资产唯一权威，源码资产不会覆盖它；
- 保留精确来源、冲突、碰撞、排除和未支持结果；
- 正式打包仍会执行已有命名空间重写、引用检查、B480 环境贴图规划、full icon 和 outline 生成规则。

### Runtime-guided 源码 Hybrid

- 源码目录/ZIP只读取 Java 文本并解析 AST，不运行 Gradle/Maven/源码代码；
- 只补运行时真实注册且动态 mapper 为 unsupported 的 Block/Unit；
- 约束名称、Content kind、三阶段 parser fallback；
- 要求源码路径与 release JAR class 精确关联，候选声明行必须命中 class `LineNumberTable`；
- source outcome FAILED、Java parse ERROR 或来源不完整时 fail-closed；
- 有源码文件数、单文件、展开总量、候选数、生成文件和生成总字节预算；
- 被接受的 Java class 文件结果仍保持 unsupported/excluded，不会因数据声明被补充而伪装成 Java 行为已迁移。

### DataPatcher 单调筛选

1. 先验证 runtime-only base，必须 0 failed / 0 warning；
2. 验证全部候选；
3. 只按精确 content 路径剔除归属明确的 failure/warning；
4. 重验剩余集合，识别依赖闭包；
5. 无法安全归属、协议错误或超出轮次时回退 runtime base；
6. 正式打包后再执行一次完整官方 DataPatcher.apply；
7. 最终必须 `failedAssets=0` 且 `warningCount=0`。

候选剔除属于可选项筛选，记为 warning 和明确未转换结果，不再把已经剔除的候选污染为最终产物 error。

## 安全模型

动态模式会执行 Mod，**独立 JVM 不是安全沙箱**。Mod 仍拥有当前用户可用的文件、网络和系统权限。因此：

- 只处理可信发布 JAR；
- 建议在低权限账户、虚拟机或容器中运行；
- 运行前后校验 Mod JAR 和 Server JAR SHA-256；
- 本地 Web UI 的运行时能力默认关闭，操作员必须配置固定 Server JAR 和显式执行开关；
- 每个 Web 运行时作业还必须再次确认信任，Server JAR 不能由浏览器上传或指定；
- Web 只允许 loopback，没有认证、授权、租户隔离或恶意代码沙箱，不得作为公网、内网共享或远程上传服务。

## 自动化测试

0.2.0 发布前最终全仓测试为 **131/131 通过**，0 failed / 0 errors / 0 skipped。以下是运行时重点测试集：

| 模块 | 测试数 |
|---|---:|
| bridge-runtime-extractor | 12 |
| bridge-runtime-mapper | 4 |
| bridge-java-static | 32 |
| bridge-runtime-assets | 9 |
| bridge-converter | 20 |
| bridge-cli | 25 |
| **合计** | **102** |

覆盖内容包括：晚注册、三阶段删除拒绝、快照预算、Seq Object[]、精确行号来源、partial AST fail-closed、源码/生成预算、空动态 baseline、单调候选剔除、依赖闭包、runtime base 回退、最终严格 apply 等。

全仓额外包含 `bridge-model` 5、`bridge-source-index` 3、`bridge-target-api` 1、`bridge-target-1597` 7 和 `bridge-web` 13 项测试。Web 覆盖双文件上传、执行同意、fail-closed、Request-ID、201 后启动、命令构造、取消进程树、终态产物校验和运行时审计下载。

## 静态转换能力

`convert` 命令仍保留并支持：

- JSON/HJSON/JSON5 声明式 Mod；
- 带源码 Java Mod 的确定性 AST 静态导出；
- 旧 CP/PatchSet；
- 已有 v159 DP 重打包；
- Saturation Firepower 的 358 根 Content 静态转换、离线 full icon/outline 和真实 Desktop 导入历史验证。

Saturation 的客户端退出/无核心地图退出崩溃已定位为 B480 `DataImagePacker.unload()` 上游问题，详见：

- `docs/B480_CLIENT_IMPORT_FIX_20260730.md`
- `docs/B480_EXIT_UNLOAD_CRASH_20260730.md`
- `docs/patches/DataImagePacker-unload-fix.patch`

这些静态历史证据仍有价值。`convert` 继续作为 CLI 和 Web UI 的安全静态模式，与可信运行时模式明确分离。

## 当前硬边界

1. 仅支持官方 Mindustry v159.7/B480 输出和验证。
2. 原 Mod 必须能在官方 v159.7 上加载；旧 API、缺失依赖或非官方运行时专用 Mod 会在 extractor 阶段失败。
3. JAR-only 情况下，当前直接动态根映射仍主要是 Item/Liquid/Status；Block/Unit 高覆盖依赖匹配源码。
4. 混淆、无 `LineNumberTable`、源码与发布 JAR 不一致会降低或阻断 Hybrid。
5. 方法覆写、事件回调、自定义实体/AI/Effect、GUI、网络协议、科技树和新地图不迁移。
6. DataPatcher 通过不能证明真实玩法、渲染、音频、保存重开或服务器地图联机通过。

## 下一步

1. 以更接近原版 Data Assets、较少自定义 Java 行为的 Mod 建立代表性兼容样本。
2. 根据炮塔缺弹药等真实失败继续扩展动态 Unit/Block 对象图 mapper 和缺失项诊断。
3. 用匹配 B480 服务器加载实际地图/存档。
4. 完成本地 Web 双模式、loopback 和执行同意回归，并从安装分发包启动烟测。
5. 保持 Web 仅本机使用，不把任意 Mod 执行能力包装为公网或多用户服务。
