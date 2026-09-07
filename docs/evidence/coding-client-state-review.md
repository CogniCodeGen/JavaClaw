# Coding 客户端输出与设置状态检查

日期：2026-09-07。平台：本机 macOS aarch64、JDK 25、JavaFX 25。此记录补充既有管理中心 Golden；未改写原有 54 张 Golden，也不把它们视为本次新增状态的覆盖证据。

## 已实现接线

- 新增独立 `javaclaw.coding/execution-list/v1` Schema，仍使用既有 `extension/query`；`execution/list` 的输入为 `{}`，Workspace、可选 Thread/Turn 为查询过滤，服务端逐项重新授权。没有改变旧 Core、Output 或冻结 Turn 的 wire DTO。
- SDK 提供类型化执行目录及 `command/output`，与原有 `preparation/output`、`terminal/output` 共用字节游标。CLI 和 Desktop 使用同一个 `CodingExecutionPoller`：一次目录查询，最多 16 页，每页 16 KiB，每项保留 16 Ki 字符尾部。目录最多 100 项；Desktop 汇总显示限制为 64 Ki 字符。
- CLI 每秒最多一次目录读取；Turn 终态最多补读三轮，遇无游标进展即停止，仍有内容时提示使用 SDK 分页。Desktop 约每秒观察当前 Thread，在途请求结束前不叠加请求；切换作用域、断开、重连、停止观察会使旧结果失效。没有人工 stdin 或新的执行模式入口。
- Coding 设置以两秒间隔观察当前 Workspace 最近依赖准备摘要，最多展示十条；离页停止观察，迟到结果不覆盖新 Workspace。取消仍使用会话的 Turn 取消入口。
- 未知 Item Schema、未来 Coding 工具 revision 或无法类型化的 Core ToolResult 保留为最多约 16 Ki 字符文本；不把相似 JSON 当成已知 Coding 成功事实。所有输出页清除 Unicode Cc 控制字符，保留换行和制表符；分片 ESC/C1 不会传给宿主终端执行。
- 模型容量未声明时明确显示平台运行默认窗口 32768、输出 4096，并说明这些不是 Provider 容量声明。容量 RPC 使用原有独立契约，不改变旧 Provider DTO。

## 实际检查

独立编译使用仓库当前源码并输出到 `/tmp/javaclaw-client-live-classes`，未占用 Maven target。Contracts、Client、Desktop 主源码编译通过。锁定 Palantir 2.97.0 定向格式化和 Checkstyle 14.0.0 检查通过；正式 Maven 全量门禁由根任务统一执行。

测试日志：`/tmp/javaclaw-client-live-assertions.log`（11 项基础断言）；`/tmp/javaclaw-client-live-fx-assertions.log`（10 项 JavaFX、设置、PTY 与转录断言，两组包含一项准备输出测试重叠，不相加为独立数量）。覆盖真实 Extension RPC 分页、终态尾页、中文与控制字符显示、未知修订回退、单在途读取、重连迟到结果、设置页切换和只读面板。UTF-8 原始字节分页的服务端正确性由服务端输出测试另行验证，此客户端检查不代替它。

新增相关测试类：`CodingExecutionPollerTest`、`CliCodingOutputTest`、`DesktopCodingOutputControllerTest`、`CodingPreparationStatusTest`、`CodingExecutionPanelTest`；扩展了 `CodingContractsTest`、`CodingToolResultTranscriptTest`，同步了 `CodingExtensionTest`（31 个 Schema）、`CodingSettingsPageTest` 和 `TranscriptPresenterTest`。

## 截图审阅

以下为生产 JavaFX 控件和生产格式化器的确定性状态夹具截图；用于检查布局、换行、可读性与输出尾部，不是实际联网 Harness 的屏幕录制。真实三 Turn 端到端证据由公开仓库 Harness 验收单独记录。截图只覆盖默认翡翠主题、标准字号和密度；未验证 Windows/Linux 视觉状态。

- [编程环境与准备状态](coding-client-states/coding-settings-preparation.png)：原有 FormSection、选择控件和按钮保持基线；准备状态在配置和托管工具链之间可读。完整设置页使用原有滚动容器，底部内容通过滚动查看。
- [命令失败尾部、Diff 与后续成功](coding-client-states/coding-command-diff-output.png)：340px 只读进度区域显示失败尾部及退出码；左侧保留 Diff、原文件恢复目录、成功输出及继续聊天正文。右侧滚动位置在尾页，早期内容可滚动查看。
- [依赖准备运行中](coding-client-states/coding-command-diff-preparation.png)：同一输出控件的 RUNNING、准备日志与证据记录进度可读；没有终端输入控件。

三张 PNG 均已实际打开检查；没有重叠、异常裁切或新增外观体系。文件摘要见 [sha256.tsv](coding-client-states/sha256.tsv)。

## 最终正式回归

本轮完整 `clean verify` 已通过：Client 109 项、Desktop 284 项单测及 1 项覆盖 54 图的 Failsafe Golden，均无失败、
错误或跳过。包含运行中输出、空 UNKNOWN 终端不循环追页、保留尾部、设置作用域取消及迟到响应回归。
正式结果与覆盖率见[完整构建记录](unified-coding-build-validation.md)，不重复累计上面的独立测试数量。
