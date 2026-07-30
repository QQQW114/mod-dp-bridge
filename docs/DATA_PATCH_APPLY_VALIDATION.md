# v159.7 DataPatcher apply 验证

最后更新：2026-07-30 04:55（Asia/Shanghai）

## 为什么必须单独验证 apply

Mindustry B480 专用服务器冷启动时的：

```text
Loaded N data asset files.
Server loaded.
```

只能证明 `ServerControl` 扫描到了 `config/assets` 中的文件并完成普通冷启动。没有读取携带
Data Assets 的地图/存档时，该路径不会调用 `Vars.state.data.load(...)`，也就不会调用
`DataPatcher.apply(...)`。

因此项目现在把两件事分开：

1. `Mindustry1597ServerValidator`：仅记录 ServerControl 文件发现和进程冷启动；
2. `Mindustry1597ContentApplyValidator`：真正执行 B480 `DataManager.load` / `DataPatcher.apply`。

第 1 项不再把 `RUNTIME` 或 `SERVER_LOAD` 标为 `PASSED`。

## 实现与安全边界

正式实现：

- `bridge-target-1597/src/main/kotlin/io/github/moddpbridge/target/v1597/Mindustry1597ContentApplyValidator.kt`
- `bridge-target-1597/src/main/resources/io/github/moddpbridge/target/v1597/DpApplyHarness.java`

运行方式：

1. 工具将内置、固定的 `DpApplyHarness.java` 写入隔离临时目录；
2. 用操作员提供的可信 v159.7/B480 Server JAR 启动独立 JVM；
3. 按 B480 `ServerControl` 的 DataAsset 规则读取生成的 content/patch/bundle/sprite/sound/music；
4. 创建原版基础 Content，然后调用 `Vars.state.data.load(allAssets)`；
5. 逐 Content/Patch 输出机器可读结果、警告、失败原因和源文件路径；
6. 超时或完成后终止隔离 JVM，删除临时目录。

不执行的内容：

- 不编译输入 Mod 源码；
- 不加载输入 JAR/class；
- 不反射输入自定义类；
- 不执行 Gradle/脚本/Mod 入口；
- 不使用 Agent/LLM。

`DataPatcher` 本身还会设置 `allowClassResolution=false`、`allowAssetLoading=false`、
`allowPatching=false`，因此输入 HJSON 不能借此解析并实例化 Mod 的任意 Java 类。

## 失败判定

对每个 `ContentAsset`，任一条件成立都视为失败：

- `asset.errored == true`；
- `asset.content == null`；
- `asset.content.hasErrored() == true`；
- Content 已被 `finishParsing` / `init` / `postInit` 从 `Vars.content` 移除；
- MappableContent 名称不再绑定到该 Content 实例。

这比只检查 `ContentAsset.errored` 更严格。`ContentParser.finishParsing()` 产生的延迟错误可能不会及时把
`asset.errored` 设为 true，但 Content 已经 `hasErrored()` 并被从注册表移除。

对 `PatchAsset`，`error == true` 视为失败。资产扫描/readOverride 失败也单独报告。

## 报告语义

提供 `--server-jar` 时：

- DataPatcher apply 完成且无失败：`RUNTIME = PASSED`；
- apply 完成但有失败：`RUNTIME = FAILED`，转换状态 `REJECTED`，CLI 退出码 2；
- harness 超时/崩溃/协议不完整：`RUNTIME = FAILED`；
- 普通 Server 冷启动只记入 metadata：`serverAssetDiscovery`、`serverDiscoveredAssetFiles`；
- 因为没有加载真实地图/存档：`SERVER_LOAD = NOT_RUN`；
- Desktop 未被调用：`MAP_IMPORT = NOT_RUN`。

不提供 `--server-jar` 时：

- `RUNTIME = NOT_RUN`；
- `SERVER_LOAD = NOT_RUN`；
- 报告 warning：`DATA_PATCH_APPLY_NOT_RUN`。

完整 harness 原始输出保存到：

`logs/data-patch-apply.log`

普通 Server 文件发现日志保存到：

`logs/server-asset-discovery.log`

## 已完成验证

### 最小正向 Item

- 1 ContentAsset；
- `DataPatcher.apply` 完成；
- failed=0，warnings=0；
- `RUNTIME = PASSED`；
- `SERVER_LOAD = NOT_RUN`；
- CLI exit 0。

### 故意错误 Block

`type: DefinitelyNotABlock`：

- `DATA_PATCH_CONTENT_FAILED`；
- `DATA_PATCH_APPLY_WARNING: Type not found: DefinitelyNotABlock`；
- failed=1；
- `RUNTIME = FAILED`；
- `SERVER_LOAD = NOT_RUN`；
- CLI exit 2。

### Saturation 旧基线产物

对 `work/java-static-b480-baseline-20260730-0435/server-assets` 的真实 apply 结果：

```text
DPBRIDGE_RESULT  1982  354  0  1628  34  78  449
```

字段依次为：总 DataAsset、顶层 ContentAsset、PatchAsset、外部资产、失败资产、warning、仍在
`Vars.content` 中且归属 `DataPatcher.dpMod` 的 Content 对象数。最后的 449 可包含嵌套 Bullet 等 Content，
不能当成顶层文件数。

结果：exit 10，34 个顶层 Content 失败，78 个 parser/apply warning。其中只有 8 个直接
`asset.errored`，其余主要是 `finishParsing` 之后 `content.hasErrored` 并从内容注册表移除。

这也证明旧报告仅根据 `Loaded 1982 data asset files.` 标注 `RUNTIME/SERVER_LOAD PASSED` 是错误的。

## 仍未覆盖

即使 `RUNTIME = PASSED`，仍不证明：

- Desktop 可导入 DP ZIP；
- 地图保存、退出、重开后 Content 可恢复；
- 服务器已加载携带 DP 的真实地图；
- PNG/atlas/generated hash 客户端有效；
- OGG/MP3 可解码、播放；
- Weapon/Bullet/Effect/Draw/Ability/Consume/AI 的实际手感和视觉正确。

因此 `SERVER_LOAD` 只能在未来真正加载携带 DP 的地图/存档后才能标为 `PASSED`。
