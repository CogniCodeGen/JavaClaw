# V3 审查修复验证记录

日期：2026-09-08。状态：七项修复及追加退订修复完成；分段完成全模块 verify、47 图 UI 重放及最终产物健康检查。
本记录承接[七项修复计划](../proposals/chat-webview-memory-v3-review-fixes.md)，不替换此前
[2527 条测试的历史验收](chat-webview-memory-v3-functional-ui-validation.md)。

## 修复范围与回归

| 项目 | 最终行为 | 维护回归 |
| --- | --- | --- |
| R1 会话导航 | 重复选择幂等；切换释放展示订阅且不取消服务端 Turn；历史恢复、迟到启动和终态均校验导航代次及 SDK 实例；恢复未完成时禁止再次发送 | [DesktopConversationNavigationTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/DesktopConversationNavigationTest.java)、[DesktopLegacyHistoryTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/DesktopLegacyHistoryTest.java)、[DesktopPresenterTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/DesktopPresenterTest.java)、[DesktopTurnStreamCoordinatorTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/DesktopTurnStreamCoordinatorTest.java) |
| R2 结构化历史 | 服务端按已知 Core/Coding Schema 生成有界摘要；工具成功/失败、审批原因及错误码在 WebView/原生简版共用标题与样式 | [ItemHistoryRepositoryTest](../../javaclaw-app-server/src/test/java/com/javaclaw/server/persistence/ItemHistoryRepositoryTest.java)、[HistoryStructuredSummaryTest](../../javaclaw-app-server/src/test/java/com/javaclaw/server/persistence/HistoryStructuredSummaryTest.java)、[TranscriptPresenterTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/view/TranscriptPresenterTest.java) |
| R3 草稿与刷新 | 自动读取返回时校验编辑代次和 dirty；无变化不重建节点；主动加载阻止底层键盘编辑，失败恢复交互 | [ViewSchemaRefreshTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/settings/ViewSchemaRefreshTest.java)、[ViewSchemaSettingsPageTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/settings/ViewSchemaSettingsPageTest.java) |
| R4 连续冲突 | 普通提案与后台学习统一使用 ACTIVE 参与者；已被替代记录不进入新冲突 | [MemoryConflictRecoveryTest](../../javaclaw-builtin-extensions/src/test/java/com/javaclaw/builtin/extensions/MemoryConflictRecoveryTest.java)、[MemoryLearningResourceTest](../../javaclaw-builtin-extensions/src/test/java/com/javaclaw/builtin/extensions/MemoryLearningResourceTest.java) |
| R5 旧墓碑冲突 | 新删除在事务内保护 PENDING 引用；旧墓碑读取历史的实际 revision，允许用户明确拒绝候选关闭冲突，不恢复记忆 | [MemoryConflictRecoveryTest](../../javaclaw-builtin-extensions/src/test/java/com/javaclaw/builtin/extensions/MemoryConflictRecoveryTest.java)、[MemoryConflictDecisionTest](../../javaclaw-builtin-extensions/src/test/java/com/javaclaw/builtin/extensions/MemoryConflictDecisionTest.java) |
| R6 精确配置回显 | 恢复审批、推理和精确目录引用；首次初选与用户选择分开，目录同 ID 升版不静默升级；损坏元数据保持未选 | [MemoryLearningSelectionViewTest](../../javaclaw-builtin-extensions/src/test/java/com/javaclaw/builtin/extensions/MemoryLearningSelectionViewTest.java)、[ViewSelectionProjectionTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/ViewSelectionProjectionTest.java)、[ViewPageCursorStateTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/settings/ViewPageCursorStateTest.java)、[ViewInitialSelectionTest](../../javaclaw-extension-spi/src/test/java/com/javaclaw/extension/spi/ViewInitialSelectionTest.java) |
| R7 文档续租 | 每次续租绑定页面代次、句柄及请求身份；相对导航中停止父续租，迟到回调不覆盖子版本 | [DocumentPreviewLeaseTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/document/DocumentPreviewLeaseTest.java)、[DocumentPreviewPaneTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/document/DocumentPreviewPaneTest.java) |

所有改动继续使用 Desktop → SDK → App Server、Protocol v3、data-v6 和 14 个模块。
初选声明使用现有 ViewQueryResult.values 的版本化可选元数据，没有新增 RPC 或数据库 migration。
默认测试使用固定模型、临时 H2/工作区及可控 Future，不调用付费模型。

## 有界恢复与真实限制

- 历史摘要最多 4096 UTF-16 单位，读取 65536 字符前缀和 4096 字符后缀；整页继续受响应预算约束。
  这是可读摘要，不是完整 payload 查看器；大字段及未知 Schema 仍明确受限。
- 未协商新历史能力的旧服务端需要逐页定位末尾，只保留最近 100 条；检查取消、序号推进及恢复时间预算。
  无法在 10 秒内确认末尾，或末尾 Item 超过既有 8MiB UTF-16 缓存预算而丢失身份时，明确失败并封锁发送，
  可通过重选会话重试；超大尾消息需要升级支持摘要的新服务端。新服务端使用尾部 history 查询。
- R3 测试在真实 JavaFX Scene 的焦点控件上发送 KEY_TYPED，R7 用可控 Future 与单调时钟读数重放竞争。
  它们不等同于操作系统输入法、休眠唤醒或网络抖动的人工验收。
- 本轮仍只验证 macOS arm64；Windows/Linux、其他发行 Runner、安装签名、输入法和跨 DPI 未取得真实回执。
- 既有后台任务列表长名称裁切、WebKit 彩色 emoji 字形问题、大图性能和边展示覆盖限制继续保留。
  不声明未经真实对照测试的 WebView 性能提升或模型记忆质量提升。

## 最终门禁与 UI 证据

最终验证于 2026-09-08 12:45:49（Asia/Shanghai）结束，覆盖全部 15 个 Reactor project。全仓 clean verify
尝试耗时 15 分 56 秒，父项目及 12 个上游模块通过，Desktop 多进程 WebKit 探针失败；补充测试诊断后，
按 SHA-256 核对并缓存本轮上游产物，从 Desktop 恢复两个下游项目的完整 clean verify，耗时 1 分 20 秒，
Desktop 与 Packaging 均成功。生产源码从最终 UI 重放至恢复门禁结束保持一致。

```sh
mvn -s /private/tmp/javaclaw-webview-maven-settings.xml spotless:apply clean verify spotless:check checkstyle:check
mvn -s /private/tmp/javaclaw-webview-maven-settings.xml -f /private/tmp/javaclaw-review-fixes-verified-reactor-install/pom.xml initialize
mvn -s /private/tmp/javaclaw-webview-maven-settings.xml -rf :javaclaw-desktop spotless:apply clean verify spotless:check checkstyle:check
```

临时 settings 仅指定官方 Maven Central 镜像，未修改个人或全局配置；缓存安装仅使用已验证的父 POM 和
12 个上游 JAR，不关闭测试或覆盖率门禁。安装描述、产物哈希、两段原始构建日志、各模块覆盖率、完整跳过清单、
本机发行 ZIP 与清单的 SHA-256 记录在[机器可读证据](chat-webview-memory-v3-review-fixes-validation.json)。

| 门禁 | 最终结果 |
| --- | --- |
| 全仓测试 | 2582 条记录，2557 项通过、25 项条件跳过，0 失败、0 错误 |
| Desktop | 366 项单测通过；另有 1 项 Golden 集成测试通过，已计入全仓总数 |
| 既有 Golden | 54 张 appearance 截图逐字节比较通过，55 个基准文件（含清单）未改写 |
| 格式与架构 | Spotless、Checkstyle、声明依赖、模块边界及包循环检查通过 |
| 覆盖率 | 14 个模块原门槛全部通过；App Server 行/分支为 91.58% / 80.65%，Desktop 为 88.21% / 71.61% |
| 打包健康 | distribution 内置运行时执行 javaclaw-health，独立临时 data-v6，退出码 0 |

25 项条件跳过分别来自 App Server 6 项、Native Hosts 17 项、Packaging 2 项，涉及未启用的真实 Provider、
公网工具链验收、系统凭据及其他平台/原生环境。条件跳过不计作通过，也不因本机构建通过改为跨平台已验收。
先前定向运行和独立 UI 重放不重复计入 2582 条测试记录。

## 完整验证中追加修复的问题

第一轮完整构建在 AppServer 的 1041 条测试记录中出现一处失败：[AppServerStdioLifecycleTest](../../javaclaw-app-server/src/test/java/com/javaclaw/server/AppServerStdioLifecycleTest.java) 的三秒
退出断言将 H2/扩展冷启动也计入预算。超时栈仍位于初始化；分段实测首次初始化约 3970ms，EOF 约 49ms，
关闭约 135ms。测试现将初始化放在断言前，三秒仍完整覆盖 EOF → serve 返回 → Components.close，
默认 60 秒后台空闲窗口不变。修改后的维护测试连续六次通过；临时注入等待后台空闲的错误实现，仍准确在
3000ms 被同一断言拒绝。没有修改生产关停逻辑，也没有放宽时限。

第二轮服务端 1041 条测试记录为零失败、六项条件跳过，随后依赖检查指出历史摘要解析直接使用
jackson-core 却只依赖传递引入。App Server 已显式声明该依赖，沿用父 POM 锁定的 2.18.3，未升级版本。
随后提前编译及依赖检查覆盖全部业务模块；该预检查在 Packaging 因仅执行 test-compile、未完成上游 package
而停止，因此不计作完整构建成功，仍以最终标准生命周期的 clean verify 为准。

Desktop 的首轮整体验证还发现本地阅读回归：会话协调器的连接检查阻止了断线/缓存正文的暂停跟随。
现允许本地 following 状态独立更新，SDK 读取继续受连接和导航代次约束；原生滚轮、键盘和滚动条回归均保留。
另一条新增导航测试在活动 Turn 发布后立即断言后台订阅数量，现等待真实订阅建立再检查幂等性，
没有改变生产订阅时序或延长既有测试等待上限。失败日志与修复后的证据分别保留。

恢复失败后的重试也接通了真实入口：点击当前会话行或按 Enter 都转发明确激活，同一运行中会话仍保持幂等。
[DesktopShellThreadActivationTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/DesktopShellThreadActivationTest.java)
使用生产 FXML、Stage 和 JavaFX 点击/键盘事件，覆盖失败重试以及运行时不重复启动、订阅或读取历史。
仅在临时目录移除新增激活处理后，两项测试均失败；修复后两项通过。没有新增按钮或修改样式。

最终 SDK 重放进一步发现退订与共享连接写帧的竞争。服务端现在协作式关闭订阅，不再 interrupt 借用共享 UDS
的发送线程，合并等待后复核关闭标记，已在途发送仍受原五秒硬截止约束。
[TurnStreamUnsubscribeTest](../../javaclaw-app-server/src/test/java/com/javaclaw/server/rpc/TurnStreamUnsubscribeTest.java)
使用真实 SocketChannel 和受控写入窗口：旧实现确定抛出 ClosedByInterruptException，新实现允许退订后同连接
history 查询往返，并验证空闲线程结束、后续通知停止。两项测试连续三轮通过，既有五秒硬截止测试也通过。
该失败回放未保留底层异常，不能声称已排除所有其他断线原因；新增的验收诊断会记录首个传输异常链及选择、
busy、活动 Turn、历史和流状态，不改变生产连接或原等待阈值。

## 生产 UI 与组件重放

追加退订修复前的重放曾在第二次会话切换时失败：选择已更新，但共享 SDK 连接报告 local RPC connection failed。
该次失败的截图与日志单独保留，不混入通过截图。修复后先完成所有业务模块的预编译及静态检查，再以全新
临时工作区和空截图目录运行 SDK/UI，26 张均通过；聊天/文档八个组件场景和 21 张截图也重新通过。
下列 47 张通过截图均对应追加退订修复后的源码，回放前后核对 1627 个生产文件哈希一致。

- `scripts/replay-sdk-ui-acceptance.py` 已完成 26 张原始截图：生产 FXML / Presenter → SDK → UDS → App Server → H2。
  R1 在亮暗两组外观下生成中离开并返回，确认同一个活动 Turn、busy 与发送禁用；完成后同一输入只有一条持久用户消息。
  R2 核对真实工具成功/失败、错误码/原因及对应 CSS 类；R6 重开后核对 EVERY_CALL、HIGH 和角色、模型、权限的精确引用。
- `scripts/replay-chat-document-acceptance.py` 已通过八个功能场景、生成 21 张原始截图，保留 WebView/原生对照、
  白屏恢复、历史锚点、长正文、文档相对资源、图片、撤权和不支持格式的覆盖。
- SDK 外观复核为 Emerald / 标准 / 100% 与 Midnight / 紧凑 / 120%，沿用既有布局、令牌和交互语言。
  聊天主壳两组均为 1280×820 Scene；设置中心分别为 1040×740 与 880×620 外框，实际 Scene 高度为 712/592。
  聊天截图文件名中的 dark-minimum 是复用的用例标识，不能解释为主壳已缩至最小窗口的额外验收。
  暗色历史截图中错误说明在视口下方，亮色完整截图及 DOM 断言覆盖了该内容；不将单张截图解释成所有字段均可见。
  宽表格、长详情与任务列表的既有裁切限制仍保留。
- 截图属于真实 JavaFX Scene 像素，未修图；不是 OS 合成后光学观测。测试未使用用户工作空间、真实凭据或付费模型。
  WebView 与原生对照保留相同内容和主题语义，字体度量、内边距与换行存在渲染差异，不声明两者逐像素相同。

## 多进程 WebKit 探针的未确认退出

最后一轮全仓尝试中，WebSurfaceRuntimeTest 发现 first 探针已报告 ready，但约三秒后的存活检查为 false。
原测试自动清理了临时输出，未取得退出码，也未找到对应时间的原生崩溃证据，因此没有可证实的根因。
维护测试现保留失败目录、PID、退出码、完整输出及 hs_err 路径，探针记录就绪、释放和关闭时序；
原目录隔离、进程存活、正常退出断言及十秒期限均保留，没有修改生产 WebKit 运行逻辑。

两个测试隔离重复五轮通过，十个子进程均在 release 后正常退出；恢复的完整 Desktop verify 也通过。
该现象仍记录为未确认原因的历史失败，不声明已修复原生崩溃；今后再次失败可直接保留定位证据。
原失败日志、五轮子进程时序及恢复门禁分别归档，条件与平台限制继续适用。
