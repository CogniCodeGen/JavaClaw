# 聊天卡顿修复与验证

日期：2026-09-09。环境：macOS arm64、JDK 25、JavaFX 26.0.2。
使用真实 JavaFX 主窗口、WebKit 和本地假 RPC 服务；不读取用户聊天，不调用模型。

## 本轮修改

- 主窗口按连接、选择、正文、输入请求和操作状态分别更新。重复事实不再通知整壳；
  正文更新不重设工作区、对话、审批列表或无关提示。目录确实变化时仍同步选择、名称和详情。
- 输入框更新发送按钮时不再滚动聊天。各对话的草稿、审批选择和阅读策略继续保留。
- WebView 正常时不重建隐藏的备用聊天列表；主动选择简版或页面失败时，同步补上最新内容。
  简版复用持久消息投影及 JSON 解析器，只在内容变化、首次显示或恢复跟随时定位到底部。
- 后台请求和待显示结果分别合并为单槽。按请求身份丢弃过期成功、失败和清空后的结果；
  切换工作区立即撤销旧页面引用，旧快照不能覆盖新对话或空界面。
- 仅转换最终 500 条展示窗口；未变化消息复用已编码行及对应引用。
  缓存限制为 500 行和约 8MiB 估算预算，超过预算时保留稳定子集，避免顺序淘汰造成每帧全部重算。
  已提交消息窗口保留不可变集合，去除高频头部插入复制。

相关实现：
[主窗口](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/shell/DesktopShellController.java)、
[目录绑定](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/shell/ShellCatalogBindings.java)、
[备用列表](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/shell/ShellWebSurfaces.java)、
[渲染队列](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/view/ChatProjectionWork.java)、
[消息缓存](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/view/ChatProjectionRenderer.java)。

## 相同主窗口夹具的前后对照

修复前使用上一轮构建保留的 Desktop jar；两侧执行相同的 `DesktopShellRenderRegressionTest` 夹具。
流式场景加载 100 条历史，以 20Hz 提交 24 个正文片段，同时继续编辑草稿。
另以非空原生转录列表验证 24 次逐字输入，先用实际 `scrollTo` 校准公开事件监听器。

| 可观察操作 | 修复前 | 修复后 |
| --- | ---: | ---: |
| 流式回放期间工作区目录变更通知 | 29 | 0 |
| 流式回放期间会话目录变更通知 | 29 | 0 |
| 流式回放期间相同审批列表变更通知 | 29 | 0 |
| 24 次逐字输入触发的原生滚动命令 | 24 | 0 |

两侧输入事件数相同；中间状态允许由现有 50ms 机制合并，最终正文和草稿必须完整。
修改后的测试确认最后一个片段已经出现在实际 WebView，且输入草稿、选中会话正确。
真实重命名、创建和切换会话、审批清空仍触发相应更新，各会话草稿分别恢复。
这里统计的是确定的多余操作，不将这些数字转换成所有设备的帧率或延迟提升倍数。

回归入口：[DesktopShellRenderRegressionTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/DesktopShellRenderRegressionTest.java)。
测试在断言前输出 `SHELL_RESPONSE` 计数，可从 Maven 测试输出复查。

## 边界覆盖

- 健康页面连续更新 20 次时，隐藏备用列表不发生变更；立即降级时显示最后一条快照。
  页面恢复后停止备用投影，再次自动降级仍使用最新正文。
- 500 条历史加 1 条暂态回复连续更新 24 帧，窗口内 499 条历史只各投影一次。
  超出字节预算后再次渲染不会把窗口内已缓存消息全部淘汰。
- 同对话新请求替换、旧失败迟到、跨工作区、清空后再次使用和关闭后的回调，均不应用旧结果。
  文档目标变化会同时更新行版本和引用表，窗口外和外工作区的显式文件引用不进入当前引用表。

## 验证范围

现有原生滚动、惯性、DOM 复用和阅读锚点修复保留；本轮不改变布局、CSS 或模型请求协议。
宿主继续使用完整 JSON 快照及既有 50ms/ack 提交机制，尚未改为增量传输协议。
配置保存后的后台目录补读取保持原有流程，不将异步网络等待描述为本轮已消除的界面线程开销。
Windows/Linux 和各类物理鼠标、触控板尚未逐平台验证；没有据此宣称所有场景完全无卡顿。

## 最终交付检查

- 已执行全仓库 `mvn spotless:apply`，检查实际修改；格式与 Checkstyle 检查通过。
- 执行 `mvn spotless:check checkstyle:check verify`，上游模块全部通过。
  桌面两项旧测试用“相同状态的通知”判断迟到对账完成，因状态去重而超时；
  改为等待 UI 回调实际执行，同时保留最新正文、终态以及不得触发重复通知的断言。
- 用 `mvn spotless:check checkstyle:check install -rf :javaclaw-desktop` 续跑通过。
  Desktop 511 项测试、1 项 JavaFX Golden 集成测试、Packaging 89 项测试及覆盖率门禁均完成。
- 各模块最终汇总为 2,758 项：2,733 项通过、25 项按平台或发布/联网环境条件跳过，零失败、零错误。
  这不是五平台原生发布验收，也没有启用真实模型联网测试。
- `git diff --check` 通过，macOS arm64 发布包和本地 Maven 构建产物已更新。

首轮新增主窗口测试还修正了运行中 Turn 的初始化等待：运行期间 `busy=true` 是正常状态，
因此等待精确的活动 Turn、流订阅、配置就绪和实际可见正文，而不是等待任务结束。
前后计数对照使用的是修正后的同一夹具，未放宽最终消息和草稿断言。
