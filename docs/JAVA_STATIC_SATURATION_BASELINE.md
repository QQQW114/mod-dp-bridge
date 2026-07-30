# Saturation Firepower Java 静态导出基线

最后更新：2026-07-30 06:04（Asia/Shanghai）

本文件保留 Java AST exporter 的历史首轮基线，并记录当前最终基线。历史数字用于比较规则改进，不能作为当前能力结论。

## 固定输入

`<local-Saturation-Firepower-source>`

目标：Mindustry v159.7 / B480，Data Patch format 2。

安全约束：转换器只解析 Java AST，不编译、不加载、不反射、不执行输入 Mod 代码。

## 历史首轮 Java AST 基线

历史输出：

`work\java-static-b480-baseline-20260730-0435`

历史 ZIP SHA-256：

`EB5C02F8E054A75CC9DCC87B8A61BD668D74DF764D1DA6DE04B72934979EC84C`

首轮结果：

- 扫描 1764 个输入文件；
- JavaParser 解析 25 个 Java 文件，0 个语法失败；
- 生成 354 个顶层 Content；
- 其他资产 1628 个，总计 1982；
- 92 converted、262 degraded、0 unsupported、0 failed；
- 四个内嵌 `MissileUnitType` 尚未提升，因此不是 358 个目标 Content 的完整注册。

历史分类：

| 类型 | converted | degraded | 合计 |
|---|---:|---:|---:|
| Item | 15 | 0 | 15 |
| Liquid | 5 | 1 | 6 |
| Status | 8 | 14 | 22 |
| Unit | 0 | 59 | 59 |
| Block | 64 | 188 | 252 |
| **合计** | **92** | **262** | **354** |

### 历史验证语义更正

历史普通 Server 日志：

```text
Loaded 1982 data asset files.
Server loaded. Type 'help' for help.
```

这只表示 `ServerControl` 发现文件并完成冷启动，未加载地图时不会 apply DP。早期把该日志标作 `RUNTIME/SERVER_LOAD PASSED` 是错误的。

后续用正式 `Mindustry1597ContentApplyValidator` 重测同一历史 `server-assets/`：

```text
DPBRIDGE_RESULT 1982 354 0 1628 34 78 449
```

正确解释：

- 354 个根 Content 中 34 个 apply 失败；
- parser/apply warning 78；
- harness exit 10；
- `STRUCTURE = PASSED`；
- `RUNTIME = FAILED`；
- `MAP_IMPORT = NOT_RUN`；
- `SERVER_LOAD = NOT_RUN`。

因此历史首轮基线是 exporter 缺失修复前的负向比较点，不是“可注册、可玩”证明。

### 历史主要缺口

首轮曾有：

- `JAVA_FIELD_EXPRESSION_OMITTED` 131；
- requirements、consume、ammo、plans/upgrades 大面积缺失；
- Weapon/Bullet/Ability/Effect/Draw 构造器和字段覆盖不足；
- Unit entity/controller 未恢复；
- 常量循环和跨 Content 赋值未处理；
- 四个导弹单位未提升；
- 自定义 Block/Bullet、方法覆写和 Effect lambda 未形成完整降级策略。

这些结论仅描述历史产物；不得继续写成当前“未实现”事项。

## 当前最终基线

最终输出目录：

`work\saturation-static-20260730-060256`

关键文件：

- `sfire-mod-dp-v159.7.zip`
- `server-assets/`
- `report.json`
- `report.md`
- `logs/conversion.log`
- `logs/data-patch-apply.log`
- `logs/server-asset-discovery.log`

ZIP 大小：5,348,839 bytes。

ZIP SHA-256：

`B085C6533CC43367CBC488DB201D0414F3B212DDDF6B5D8C081C6482755569BE`

相同输入和当前工具版本完成复打包后 SHA-256 一致。

### 当前结果摘要

| 指标 | 当前值 |
|---|---:|
| 扫描源文件 | 1764 |
| 根 Content | 358 |
| 外部资产 | 1628 |
| Data Assets 合计 | 1986 |
| converted Content | 295 |
| degraded Content | 63 |
| excluded / unsupported / failed Content | 0 / 0 / 0 |
| report info / warning / error | 31 / 60 / 0 |

当前分类：

| 类型 | converted | degraded | 合计 |
|---|---:|---:|---:|
| Item | 15 | 0 | 15 |
| Liquid | 6 | 0 | 6 |
| Status | 10 | 12 | 22 |
| Unit | 49 | 14 | 63 |
| Block | 215 | 37 | 252 |
| **合计** | **295** | **63** | **358** |

文件级结果：

| fileResult | 数量 |
|---|---:|
| copied | 1622 |
| converted | 11 |
| excluded | 27 |
| unsupported | 104 |
| failed | 0 |

文件级 unsupported 是源码工程文件、旧备份、非 Data Assets 路径和不支持扩展，不等同于 Content 结果；358 个目标 Content 的 unsupported/failed 均为 0。

### 相对首轮已实现的关键能力

- 四个内嵌 `MissileUnitType` 已提升为独立 Unit，总根 Content 从 354 达到 358；
- requirements、Consume、ammo、plans、upgrades、Unit entity/controller 已覆盖；
- Weapon/Bullet/Ability/Effect/Draw/Part/Shoot 的常用构造器、字段和复制模式已覆盖；
- 7 个确定性 `for`/嵌套循环已静态展开；
- 5 个对已生成 Content 的跨语句赋值已应用；
- 一处加载期随机数采用确定性中点 5，并输出 `JAVA_RANDOM_EXPRESSION_APPROXIMATED`；
- 自定义 Block/Bullet/Effect 和状态回调形成显式 degraded 报告；
- v159.7 不接受的字段会在输出前删除并报告；
- `SFBlocks.tieliu` 对 `((LiquidTurret) Blocks.tsunami).ammoTypes.get(Liquids.slag)` 的读取已由绑定 v159.7 的固定原版对象快照恢复，输出 `JAVA_VANILLA_OBJECT_SNAPSHOT_APPLIED`；
- 当前 `JAVA_FIELD_EXPRESSION_OMITTED = 0`。

固定原版快照是目标版本源码数据的可审计常量，不是加载游戏对象，更不是执行输入 Mod。升级 Mindustry 目标版本时必须重新核对该快照。

## B480 正式 apply 结果

最终 harness 结果：

```text
assets=1986
content=358
patches=0
external=1628
addedContent=689
failed=0
warnings=0
exit=0
```

阶段：

- `STRUCTURE = PASSED`
- `RUNTIME = PASSED`
- `MAP_IMPORT = NOT_RUN`
- `SERVER_LOAD = NOT_RUN`

`addedContent=689` 包含解析后加入 `Vars.content` 的嵌套对象，不等于 358 个根 HJSON 文件。报告中的 60 warning 是转换器主动披露的降级/资产警告，其中 5 个为 `AUDIO_CONTAINER_EXTENSION_MISMATCH`；它们不是 B480 parser/apply warning。

## 当前仍然存在的降级

63 个 degraded Content 不代表 parser 失败；它们已生成并被 B480 接受，但含至少一项明确损失或近似：

- 自定义 Block 类降级为内置父类；
- 自定义 Bullet 的体积增伤、破盾或穿透增伤逻辑丢失；
- Java 方法覆写和状态 TransitionHandler 无法安装；
- lambda/custom Effect 替换为 `Fx.none`；
- v159.7 不接受字段被移除；
- 随机表达式使用中点，分布语义不等价；
- 部分内容缺少常规 icon 候选。

因此 `295 converted / 63 degraded` 是比首轮 `92 / 262` 明显更强的静态语义覆盖，但不能转换成“玩法还原百分比”或“客户端完全兼容”声明。

## 仍需用户完成的验证

当前自动结果只能证明 HJSON 注册和引用解析通过，不能证明：

- Desktop 能导入该 ZIP；
- PNG/OGG 可解码，atlas region/generated 和音效触发正确；
- 地图保存、完全退出、重开后内容保持；
- 关键单位、炮塔、工厂、Consume、AI、Effect、状态符合预期；
- B480 服务器加载携带 DP 的真实地图/存档；
- 多人同步通过。

当前准确表述是：**358 个目标根 Content 与 1628 个外部资产已生成，B480 DataPatcher 以 0 failed / 0 warnings 接受；63 个 Content 有明确降级，Desktop 和真实地图服务器测试尚未运行。**
