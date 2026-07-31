# 文档索引

## 面向使用者

- [`PROJECT_STATUS.md`](PROJECT_STATUS.md)：当前实现程度、实测结果与剩余风险。
- [`TESTING.md`](TESTING.md)：自动化验证和 Desktop/服务器人工验收流程。
- [`WEB_UI.md`](WEB_UI.md)：本地 Web UI、HTTP API、环境变量和安全部署说明。
- [`B480_CLIENT_IMPORT_FIX_20260730.md`](B480_CLIENT_IMPORT_FIX_20260730.md)：B480 atlas、离线图标生成与退出崩溃边界。
- [`B480_EXIT_UNLOAD_CRASH_20260730.md`](B480_EXIT_UNLOAD_CRASH_20260730.md)：退出/重置崩溃的字节码级诊断；对应最小客户端补丁位于 [`patches/DataImagePacker-unload-fix.patch`](patches/DataImagePacker-unload-fix.patch)。
- [`DATA_PATCH_APPLY_VALIDATION.md`](DATA_PATCH_APPLY_VALIDATION.md)：结构检查、DataPatcher apply 和服务器加载之间的区别。

## 面向开发者

- [`ARCHITECTURE.md`](ARCHITECTURE.md)：模块与转换流水线。
- [`JAVA_TO_DP_MAPPING_V1597.md`](JAVA_TO_DP_MAPPING_V1597.md)：Java AST 到 v159.7 DP 的映射规则。
- [`JAVA_STATIC_EXPORT_SPI.md`](JAVA_STATIC_EXPORT_SPI.md)：静态导出扩展接口。
- [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md)：项目约束和继续开发所需的持久上下文。

## 历史验证记录

- [`MVP_VALIDATION_REPORT.md`](MVP_VALIDATION_REPORT.md)
- [`JAVA_STATIC_SATURATION_BASELINE.md`](JAVA_STATIC_SATURATION_BASELINE.md)
- [`SATURATION_FIREPOWER_ASSESSMENT.md`](SATURATION_FIREPOWER_ASSESSMENT.md)
- [`SATURATION_FINAL_VALIDATION_20260730.md`](SATURATION_FINAL_VALIDATION_20260730.md)

历史记录中的 `work/...` 路径指向开发期间的本地产物，这些大文件不会提交到 Git。当前公开结论以根目录 [`README.md`](../README.md) 和 [`PROJECT_STATUS.md`](PROJECT_STATUS.md) 为准。
