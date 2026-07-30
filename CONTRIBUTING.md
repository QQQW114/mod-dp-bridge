# Contributing

感谢参与 mod-dp-bridge。项目目前固定面向 Mindustry v159.7 / B480，并坚持确定性、可审计和不执行输入 Mod 的安全边界。

## 开发环境

- JDK 21；
- Git；
- Windows、Linux 或 macOS；
- 首次构建需要访问 Gradle 分发服务和 Maven Central。

构建并运行全部测试：

```bash
./gradlew build :bridge-cli:installDist
```

Windows 路径含非 ASCII 字符时建议使用：

```powershell
.\scripts\gradle.ps1 build :bridge-cli:installDist
```

## 提交要求

1. 不得编译、加载、反射或执行待转换 Mod 的代码。
2. 新增转换规则必须是确定性的，并为降级、遗漏和失败提供诊断码。
3. 修复解析或资产规则时应添加最小自制 fixture 或单元测试。
4. 不要提交第三方 Mod、服务器 JAR、转换产物、`work/`、构建目录或本机配置。
5. 涉及 Mindustry 版本行为时，请注明目标 build/commit 和对应源码依据。
6. 提交日志或报告前请移除本机路径、用户信息以及无权再分发的资源。

## Pull Request 建议

- 说明输入类型及受影响 Content；
- 列出新增或变化的诊断码；
- 附上测试命令与结果；
- 区分“结构/apply 通过”和“真实 Desktop/服务器地图通过”；
- 如包含第三方资源，补充来源、版本、许可证及必要的 notice。

更完整的架构和测试说明见 [`docs/`](docs/README.md)。
