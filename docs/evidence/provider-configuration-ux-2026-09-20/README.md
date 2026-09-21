# 模型选择与保存反馈验收（2026-09-20）

## 本轮边界

本轮仅调整 Desktop 的 Provider 业务表单、交互状态及回归测试，沿用既有 Presenter / SDK / Protocol / Provider 服务调用链。
没有新增 RPC、数据库 migration、依赖或权限，也未修改 Harness、Turn、Sandbox、Provider 服务端事务及密封凭据机制。
工作树中已有的完整配置 API 和其他功能继续保留，不提交 Git。

## 交互结果

- 上方只显示模型名称和 ID，支持名称/ID 独立搜索；勾选后在下方配置用途、图片能力与向量维度。
- 多个已选模型通过下方选择器切换；搜索、目录回执和选择切换保留各模型属性及尚未填完的维度文本。
- 手动添加默认收起，空 ID 的添加按钮禁用；折叠中的待确认输入仍提示并阻止最终保存。
- 批量用途仅在多选时按需展开，未知用途不会从模型名称推测。
- 保存成功后显示服务、模型数量和启停状态；底部“关闭”以及 Esc 均关闭窗口，不重复提交。
- 明确失败后显示“尚未保存”和具体原因，密钥清除后提供“返回填写密钥”；刷新或迟到目录不会掩盖保存错误。
- 结果不明时保持原有查询原回执流程，不能关闭或另发保存来假定撤销；成功回执确认后才能进入成功页。

## 回归范围

`ProviderSetupModelFormTest` / `ProviderSetupModelSelectionTest` 覆盖 ID 搜索、上下区职责、未知用途、批量设置、
未确认手动输入、切换后的属性保留及目录返回时的光标保留。

`ProviderSetupWizardFxTest` / `ProviderConfigurationKeyboardTest` / `ProviderConfigurationLifecycleTest` /
`ProviderSetupWorkflowTest` 覆盖保存成功、关闭与 Esc、关闭前放弃草稿确认、明确失败保留原因、密钥库失败、版本冲突、
迟到目录、密钥补录、未知回执只查询不重放，以及宿主销毁后的清理。
`CoreSettingsPagesTest` 验证成功回调刷新服务选择且不隐式切换对话模型。

## 验证命令

- `mvn spotless:apply`：通过，已检查 diff。
- `mvn spotless:check checkstyle:check`：通过。
- `mvn clean verify`：通过；15 个构建项目全部成功，耗时 23:58 min。共 3576 项测试，3550 项执行通过、26 项按既有条件跳过，失败和错误均为 0。完整统计见 [validation.json](validation.json)。

初次定向运行发现一处旧管理页测试仍断言保存后立即关窗；已按新的明确成功页契约更新为确认结果后关闭，随后由完整门禁覆盖。

## 截图

使用固定 Gateway 数据，通过真实 JavaFX Scene 渲染；没有真实密钥、模型请求或数据库读写。
主窗口分别为 880×620 和 1040×720，所属配置弹窗分别为 660×540 和 660×620（PNG 为不含系统标题栏的 Scene）。
正常状态下所有底部按钮可见；展开手动区或长错误时内容可滚动，底部状态与按钮固定。

| 状态 | 880×620 主窗口 | 1040×720 主窗口 |
| --- | --- | --- |
| 服务列表 | [查看](provider-list-normal-880.png) | [查看](provider-list-normal-1040.png) |
| 空列表 | [查看](provider-list-empty-880.png) | [查看](provider-list-empty-1040.png) |
| 连接表单 | [查看](provider-connection-880.png) | [查看](provider-connection-1040.png) |
| 搜索勾选与下方用途 | [查看](provider-models-normal-880.png) | [查看](provider-models-normal-1040.png) |
| 目录失败手动配置 | [查看](provider-models-error-880.png) | [查看](provider-models-error-1040.png) |
| 保存成功与关闭 | [查看](provider-save-success-880.png) | [查看](provider-save-success-1040.png) |
| 保存失败与补录密钥 | [查看](provider-save-failure-880.png) | [查看](provider-save-failure-1040.png) |

## 平台限制

仅在 macOS arm64、JDK 25 验证界面与键盘。Windows、Linux 和 macOS x64 未做本轮真实 UI 验收。
测试采用固定数据和本地假服务，不调用付费模型，也不宣称特定线上服务地址已通过验证。
全仓原生/联网测试继续遵守原有平台及显式启用条件；跳过项以 `validation.json` 的实际结果为准。
