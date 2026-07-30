# Saturation Firepower 最终自动化验证记录（2026-07-30）

记录时间：2026-07-30（Asia/Shanghai）

本文件集中记录当前最终代码对 Saturation Firepower 的转换、测试、确定性和 B480 DataPatcher apply 结果。它是交给用户进行 Desktop/地图/服务器验证的自动化基线。

## 输入与目标

输入 Mod：

`<local-Saturation-Firepower-source>`

目标：

- Mindustry v159.7 / Build 480
- Data Patch format 2
- 固定 DP 内容命名空间 `dp-`

可信测试 JAR：

`<path-to-v159.7-B480-server.jar>`

安全约束：转换器不编译、不加载、不反射、不执行输入 Mod 的 Java 或其他代码；只运行项目内置固定 apply harness 和操作员提供的 B480 JAR。

## 构建与测试

路径包含中文，使用 ASCII Junction 包装脚本：

```powershell
.\scripts\gradle.ps1 build :bridge-cli:installDist --no-build-cache --no-daemon
```

当前 Gradle/JUnit XML 汇总：

| 模块 | tests | failed | skipped |
|---|---:|---:|---:|
| `bridge-model` | 5 | 0 | 0 |
| `bridge-target-api` | 1 | 0 | 0 |
| `bridge-target-1597` | 7 | 0 | 0 |
| `bridge-converter` | 15 | 0 | 0 |
| `bridge-java-static` | 22 | 0 | 0 |
| **合计** | **50** | **0** | **0** |

结论：50 tests all pass。

## 最终转换命令

```powershell
.\bridge-cli\build\install\bridge-cli\bin\bridge-cli.bat convert `
  ".\资源参考\Saturation-Firepower-main" `
  -o ".\work\saturation-static-20260730-060256" `
  --overwrite `
  --server-jar "<path-to-v159.7-B480-server.jar>" `
  --server-timeout 60
```

CLI exit：0。

## 最终产物

输出目录：

`work\saturation-static-20260730-060256`

DP ZIP：

`work\saturation-static-20260730-060256\sfire-mod-dp-v159.7.zip`

ZIP size：5,348,839 bytes。

ZIP SHA-256：

`B085C6533CC43367CBC488DB201D0414F3B212DDDF6B5D8C081C6482755569BE`

展开资产：

`work\saturation-static-20260730-060256\server-assets`

Server assets tree hash：

`B3C878283184DC313ED69938D31006D5DC8DE0574240761088CDD64345E8D01F`

该值不是把目录当作普通文件计算的 SHA-256。它来自
`DeterministicPackager.treeHash(plan.outputFiles)`：按 `/` 分隔的相对路径排序，依次向 SHA-256
写入 4-byte big-endian UTF-8 路径长度、路径字节、8-byte big-endian 内容长度和文件字节。
对最终 `server-assets/` 的 1986 个文件独立按同一算法重算，得到相同 hash；重算总内容字节
5,878,706，与 `report.json` 的 `serverAssets.sizeBytes` 一致。

报告与日志：

- `work\saturation-static-20260730-060256\report.json`
- `work\saturation-static-20260730-060256\report.md`
- `work\saturation-static-20260730-060256\logs\conversion.log`
- `work\saturation-static-20260730-060256\logs\data-patch-apply.log`
- `work\saturation-static-20260730-060256\logs\server-asset-discovery.log`

便捷指针文件：

- `work\LATEST_SATURATION_DIR.txt`
- `work\LATEST_SATURATION_ZIP.txt`
- `work\LATEST_SATURATION_SHA256.txt`
- `work\LATEST_DETERMINISM_CHECK.txt`

## 转换统计

| 指标 | 结果 |
|---|---:|
| 扫描源文件 | 1764 |
| 根 Content HJSON | 358 |
| 外部 bundle/sprite/audio | 1628 |
| Data Assets 合计 | 1986 |
| converted Content | 295 |
| degraded Content | 63 |
| excluded Content | 0 |
| unsupported Content | 0 |
| failed Content | 0 |
| report info | 31 |
| report warning | 60 |
| report error | 0 |

Content 分类：

| 类型 | converted | degraded | 合计 |
|---|---:|---:|---:|
| Item | 15 | 0 | 15 |
| Liquid | 6 | 0 | 6 |
| Status | 10 | 12 | 22 |
| Unit | 49 | 14 | 63 |
| Block | 215 | 37 | 252 |
| **合计** | **295** | **63** | **358** |

文件级规划：

| fileResult | 数量 |
|---|---:|
| copied | 1622 |
| converted | 11 |
| excluded | 27 |
| unsupported | 104 |
| failed | 0 |
| **合计** | **1764** |

文件级 unsupported 主要来自源码工程文件、旧备份/非 Data Assets 路径和不支持扩展。它与 Content 级 `unsupported = 0` 是不同统计维度。

## Java AST 静态恢复要点

- 25 个 Java 源文件被解析，354 个顶层声明被发现；
- 4 个内嵌 `MissileUnitType` 被提升，因此最终生成 358 个根 Content；
- 提升单位：`knocker-missile`、`blade-missile`、`sundown-missile`、`defense-platform-nuke-missile`；
- 7 个确定性 `for`/嵌套循环被展开；
- 5 个对已生成 Content 的跨语句赋值被应用；
- 一处加载期随机数被确定性替换为中点 5，并报告 `JAVA_RANDOM_EXPRESSION_APPROXIMATED`；
- `SFBlocks.tieliu` 的 `((LiquidTurret) Blocks.tsunami).ammoTypes.get(Liquids.slag)` 已通过绑定 v159.7 的固定原版对象快照恢复；
- 快照使用产生 `JAVA_VANILLA_OBJECT_SNAPSHOT_APPLIED`，不加载游戏对象；
- `JAVA_FIELD_EXPRESSION_OMITTED = 0`。

63 个 degraded 仍明确包含自定义 Block/Bullet 行为、状态回调、方法覆写、lambda/custom Effect、随机数近似和目标字段过滤等损失。它们已被 B480 接受，不代表与原 Java 行为等价。

## 音频容器审计

最终产物含 10 个音频资产，文件名都使用 `.ogg`，但按文件头 magic 审计：

| 实际容器 | 数量 | 文件 |
|---|---:|---|
| OGG (`OggS`) | 5 | `boom.ogg`、`explosionbig.ogg`、`lancer.ogg`、`laser.ogg`、`release.ogg` |
| MP3 (`ID3`) | 3 | `flying.ogg`、`hugeExplosion.ogg`、`missileX.ogg` |
| WAV (`RIFF/WAVE`) | 2 | `mapNew.ogg`、`mapWarning.ogg` |

5 个容器/扩展名不匹配文件各产生一项 `AUDIO_CONTAINER_EXTENSION_MISMATCH` warning。转换器保持原扩展名和原始字节，没有自动转码。本次 Headless apply 不解码或播放音频，因此不能据此判断 Desktop 是否接受这些文件；它们必须进入人工音频测试。

源码审计表明，v159.7 `DataAudioLoader` 把内容寻址缓存文件交给 `Sound.createStream` / `Music.create`；该缓存文件名本身不携带原扩展名，Arc 再委托 SoLoud 加载字节。因此 Java 层并不是简单依据 `.ogg` 后缀选择解码器，但不同 Desktop 平台的本地后端仍需实测。

额外的本机文件级烟雾测试中，1616 个 PNG 全部通过 Pillow `verify()`，10 个音频全部可由 PyAV 解码至少一帧，失败数均为 0。这只是通用解码器的文件健全性检查，不等价于 Mindustry atlas 打包、Arc/SoLoud 播放或游戏内引用验证。

## B480 DataPatcher apply

正式 harness 调用 B480：

```text
Data Assets total = 1986
Content Assets    = 358
Patch Assets      = 0
External Assets   = 1628
Added Content     = 689
Failed Assets     = 0
Apply Warnings    = 0
Harness exit      = 0
```

验证阶段：

| Stage | Status | 结论 |
|---|---|---|
| `STRUCTURE` | `PASSED` | v159.7 目录、扩展、根类型和静态规则通过 |
| `RUNTIME` | `PASSED` | B480 `DataManager.load` / `DataPatcher.apply` 完成，failed=0、warnings=0 |
| `MAP_IMPORT` | `NOT_RUN` | 未调用 Desktop 地图编辑器 |
| `SERVER_LOAD` | `NOT_RUN` | 未加载携带 DP 的真实地图/存档 |

`Added Content = 689` 包含解析后加入 `Vars.content` 的嵌套 Bullet 等对象，不等于 358 个根 HJSON 文件。

报告中的 60 warning 是转换器主动披露的降级/资产审查项，其中 5 个来自音频容器/扩展名不匹配；它们不是 B480 apply warning，两者不得混淆。

## 确定性验证

在相同输入、当前代码和参数下执行复打包，两个 ZIP 的 SHA-256 均为：

`B085C6533CC43367CBC488DB201D0414F3B212DDDF6B5D8C081C6482755569BE`

确定性复验目录：

`work\determinism-final-20260730-060338`

结论：最终 DP ZIP 的稳定条目顺序、时间戳和内容在本次复验中保持确定性一致。

确定性不证明游戏语义正确，只证明同一转换输入得到同一产物。

## 交给用户的 Desktop 测试清单

### 1. 导入

1. 使用精确 v159.7/B480 Desktop 客户端，最好是干净测试配置。
2. 在地图编辑器的 Data Assets/DP 导入功能中选择最终 ZIP；不要作为 Java Mod 安装。
3. 记录客户端显示的导入资源数和完整日志。
4. 确认没有 content parse、atlas、region、generated、sound 或 namespace error。

### 2. 内容注册

检查数据库/编辑器中：

- 15 Item；
- 6 Liquid；
- 22 Status；
- 63 Unit，包括 4 个提升的导弹 Unit；
- 252 Block，包括地形、运输、电力、工厂和炮塔。

### 3. 代表性玩法

- 放置地形、墙、运输、电力和存储方块；
- 给工厂提供物品/液体/电力，确认 Consume、配方和产出；
- 给炮塔提供弹药/液体/电力，实际开火并检查 Bullet、伤害、状态、Effect 和音效；
- 生成代表性单位，检查移动、寻敌、Weapon、Ability 和死亡效果；
- 重点测试报告中的 63 个 degraded Content；
- 重点测试自定义 Bullet 降级使用者和 `tieliu` 的 frag Bullet；
- 检查 8 个 `CONTENT_ICON_NOT_FOUND` 对象在客户端是否真的缺图标。

### 4. 贴图和音频

- 检查普通 sprite、outline、heat、cell、treads、weapon、DrawPart 和 Effect；
- 检查所有关键 atlas region/generated；
- 触发并确认 10 个音频资产可解码、可播放且引用正确；文件名均为 `.ogg`，但文件头审计为 5 个 OGG、3 个 MP3 容器、2 个 WAV 容器，需重点验证后两类；
- 截图或录制关键炮塔、工厂和单位。

### 5. 地图持久化

1. 保存测试地图到新文件。
2. 完全退出客户端，而不只是返回菜单。
3. 重启客户端并重新打开地图。
4. 确认自定义 Content、方块、单位和资产仍存在。
5. 导出测试地图并保留 `.msav`。

## 交给用户的服务器测试清单

1. 使用与目标一致的 B480 服务器 JAR。
2. 部署最终 `server-assets/`，或按项目既定 Data Assets 部署方式加载。
3. 让服务器实际加载用户从 Desktop 导出的目标地图/存档。
4. 检查内容 ID、方块/单位反序列化、规则和同步错误。
5. 至少连接一个真实 v159.7 Desktop 客户端。
6. 短时测试放置、生产、生成、移动和开火。
7. 保存服务器日志、客户端日志、地图、截图/视频和实际结果。

只有真实地图加载完成后，`SERVER_LOAD` 才能从 `NOT_RUN` 改为 `PASSED/FAILED`。

## 当前结论边界

本次自动化已经证明：

- 358 个目标根 Content 和 1628 个外部资产被生成；
- v159.7 结构检查通过；
- B480 DataPatcher 对 1986 个 Data Assets 返回 0 failed、0 warnings；
- 50 项自动化测试通过；
- DP ZIP 复打包 hash 一致；
- 未转换/降级/排除/不支持项均有报告。

本次自动化**没有证明**：

- Desktop 已成功导入 Saturation DP；
- PNG/OGG 客户端解码、atlas、generated 和播放通过；
- 地图保存、完全退出、重开通过；
- 所有单位、炮塔、工厂、武器、Effect、Consume、AI 和状态行为正确；
- B480 服务器已加载携带该 DP 的真实地图/存档；
- 多人同步通过。

所以最终状态仍为 **PARTIAL，等待用户进行 Desktop、地图持久化和真实服务器地图测试**。
