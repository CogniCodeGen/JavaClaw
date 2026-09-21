# 模型设置单页编辑器验收

本记录对应设置页内的统一模型配置编辑器。截图来自实际 JavaFX `ManagementCenterWindow`，包含全局导航、工作区栏和固定动作区，使用内存 Gateway 与 1,000 条模拟模型目录，不使用真实 API Key 或付费模型。

## 布局与恢复截图

| 目标窗口逻辑尺寸 | 连接设置 | 模型管理 | 保存准备失败 | 返回模型区 |
|---|---|---|---|---|
| 880×620 | [连接](single-page-connect-880x620.png) | [模型](single-page-models-880x620.png) | [错误](single-page-prepare-failure-880x620.png) | [恢复](single-page-recovered-models-880x620.png) |
| 1040×720 | [连接](single-page-connect-1040x720.png) | [模型](single-page-models-1040x720.png) | [错误](single-page-prepare-failure-1040x720.png) | [恢复](single-page-recovered-models-1040x720.png) |
| 1440×900 | [连接](single-page-connect-1440x900.png) | [模型](single-page-models-1440x900.png) | [错误](single-page-prepare-failure-1440x900.png) | [恢复](single-page-recovered-models-1440x900.png) |

每张图附有同名 `.metadata.txt`。本次环境为 macOS / aarch64、JavaFX 26.0.2，实际原生窗口 outputScale 与 renderScale 均为 2.0。PNG 使用 `Scene.snapshot`，按逻辑像素输出；不能把 PNG 像素尺寸误称为 2× 导出。

880×620 与 1040×720 的实际窗口尺寸符合目标；1440×900 受当前 macOS 可用工作区约束，实际窗口为 1440×886，Scene 为 1440×858。上表文件名保留测试目标尺寸，实际尺寸以元数据为准。

模型目录为虚拟化 ListView。布局检查既约束窗口边界，也约束 TitledPane 的真实内容边界；窄窗逐项聚焦详情字段并检查滚动视口内可见性，避免控件虽然仍在 Scene 内却被父容器裁剪。两条选择通过真实 Scene 的空格事件完成，失败后的模型属性、ListView 实例和选择全部保留。

## 验证记录

- 已执行 `mvn spotless:apply` 并检查 diff；`git diff --check` 通过。
- `mvn spotless:check checkstyle:check` 通过。
- `ProviderConfigurationEditorLayoutTest`、`ProviderSetupModelFormTest`、`ProviderSetupLayoutTest` 共 22 个测试通过。
- SDK 的 `ProviderConfigurationModulePathTest` 在独立 named-module JVM 中通过 KEEP / REPLACE 准备、摘要兼容和输入清零断言，没有使用 `--add-opens`。
- 完整执行 `mvn -q spotless:check checkstyle:check verify -Djavaclaw.provider.evidence=/tmp/javaclaw-model-ui-final-evidence`，退出码为 0；本目录 12 张 PNG 及其元数据均来自该轮验证。
- Surefire 报告共 3,655 项测试，0 失败、0 错误、26 项按条件跳过；Failsafe 的管理中心 Golden 集成测试 1 项通过。Desktop 的 859 项 Surefire 测试全部通过。
- 26 项跳过包括 17 项其他操作系统专属测试、3 项未启用的原生系统凭据或浏览器烟测，以及 6 项需显式启用的真实网络、本地 Live 或工具链归档验收；未将它们记为已通过。

本地目录路由测试覆盖预设使用的根路径、`/v1`、`/api/v1`、`/compatible-mode/v1` 及尾斜杠，检查 Bearer、Anthropic、Gemini 与 NONE 的实际请求路径和鉴权头。Anthropic 同时检查默认 `anthropic-version` 与显式覆盖；模拟服务不执行模型推理。

## 已知边界

- 未验证 Windows、Linux 或其他原生 DPI 比例。
- 未连接真实厂商账号；本地路由测试不证明账号地域、权限、TLS、外网可达性或实际模型能力。
- 未迁移、删除或重置用户数据库；运行中的旧 App Server、旧数据目录兼容性需要按项目现有启动契约处理。
- 编译和测试覆盖当前工作树，包括任务开始前已有的未提交改动；本任务未自动提交工作树。
