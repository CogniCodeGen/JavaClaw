# V3 功能闭环与 UI 对比验收记录

日期：2026-09-08。状态：**最终完整门禁及两项功能重放通过，41 张原始截图已归档**。
UI 对比发现并修复本轮问题；后台任务列表裁切与既有 emoji 字形问题仍保留，不能据此称全部 UI 验收通过。

本记录对应用户提出的“全面的功能验证测试，和 UI 对比验证”，补充上一轮
[构建及展示层验证记录](chat-webview-memory-v3-validation.md)。它不将上一轮完整构建通过等同于全部产品交互通过，
也不改写上一轮的测试计数和截图审阅事实。

最终完整构建结果、逐模块计数、条件跳过原因、日志摘要、截图及发行产物哈希，统一以
[本轮机器记录](chat-webview-memory-v3-functional-ui-validation.json)为准。
机器记录包含最终完整构建、两项重放、打包运行时健康检查及全部 41 张原始 PNG 的尺寸与 SHA256。
每图均有对应元信息；SDK 页面另外保留原生文本快照，便于区分完整页面状态和当前视口可见内容。

## 验收范围与证据层次

本轮覆盖聊天 WebView、文档预览、托管定时任务、后台学习、批次恢复、记忆冲突及图谱，保留现有
Desktop → SDK → App Server 架构边界。测试使用独立临时工作空间和 data-v6，不修改用户现有数据，不调用付费模型。

| 证据层次 | 本轮实际执行方式 | 可以支持的结论 | 不能据此推导的结论 |
| --- | --- | --- | --- |
| 服务端功能闭环 | 真实 App Server 组合根、H2、Job Supervisor、Turn Harness、确定性模型及真实 Quartz 触发 | 调度、学习、来源、取消、重启和冲突状态在实际服务端协作 | 真实模型的提取质量、所有 OS 后台运行行为 |
| SDK 与生产 UI | 生产 FXML / Presenter → SDK → Unix domain socket → App Server → H2；程序化触发控件事件及应用 JS 桥 | 客户端、通知、协议、数据来源和真实页面连接可用 | 用户逐项通过 OS 鼠标键盘操作或输入法验收 |
| 聊天与文档组件 | 维护型夹具、真实 Stage / WebKit、只读文档 Gateway、状态与像素断言 | Markdown、图片、分页、引用、简版、恢复及阅读位置 | 该组件夹具本身涵盖服务端授权全链路 |
| UI 比较 | 同内容原生 / Web 图、真实 Scene snapshot、人工阅读原始 PNG | 本轮样式差异及已捕获画面的可读性 | OS 合成后的光学可见时刻、其他平台或全部外观组合 |
| 完整门禁 | 全仓库 clean verify 及格式、架构、覆盖率、Golden、发行检查 | 最终 BUILD SUCCESS，2502 项通过、25 项条件跳过 | 跳过项不是已执行通过；完整构建不替代所有平台及 OS 人工操作验收 |

本机环境为 macOS arm64、JDK 25、JavaFX 26.0.2。精确 OS 版本、命令、采样时间和运行时信息由机器记录保留。
原生 CUA `getApp` 工具调用超时，因此本记录不称为“OS 人工点击验收”。本轮使用生产控件事件、Presenter 意图、
受限 JS 桥和真实 Scene 像素；原始 PNG 经过人工审阅。

## 五项真实 H2 功能闭环

本轮增加并执行以下五项集成场景。确定性模型替换外部 Provider，服务端持久化、调度、预算、取消及派生 Turn
执行路径使用真实实现；不能将这些场景描述为只有内存状态或模型返回值的测试。

| 场景 | 主要步骤与断言 | 维护入口 |
| --- | --- | --- |
| 托管调度 → 学习 → 冲突 → 图谱 | 创建两个工作空间及公开对话；绑定托管调度；阻住学习模型时第二次触发必须跳过重叠；完成后 Occurrence 收敛。校验一次模型 Turn、空工具目录、16000 / 2000 token 与 120 秒预算、工作空间隔离和待审冲突。并发写入使旧 head 裁决失败；新裁决替代旧记忆，并更新图谱、有效时间及检索结果 | [MemoryServiceChainIntegrationTest](../../javaclaw-app-server/src/test/java/com/javaclaw/server/extension/MemoryServiceChainIntegrationTest.java) |
| 运行中停用与明确跳过 | 立即执行后停用学习；禁止再次发起，当前结果不得发布；保存 UNKNOWN 批次并进入等待处理。明确跳过并重新启用后只处理后续增量，不回写已经跳过的证据 | 同上 |
| 取消传播与可恢复批次 | 对真实学习 Job 请求取消；子 Turn、Job 与调度 Occurrence 最终均取消；无建议发布，原批次保留 UNKNOWN，不被当成成功或自动重试 | [MemoryRecoveryIntegrationTest](../../javaclaw-app-server/src/test/java/com/javaclaw/server/extension/MemoryRecoveryIntegrationTest.java) |
| UNKNOWN 跨服务端重启 | 首次输出非法内容形成等待处理批次；关闭后用同一 H2 重启。普通周期不能绕过 UNKNOWN；另一工作空间不能跳过该批次。明确重试沿用原冻结证据，之后仅处理新增对话；空增量不再调用模型 | 同上 |
| 真实时间触发及明确低风险策略 | 使用系统时钟，把托管任务首次触发时间设为约三秒后；由 Quartz 实际触发，不用手动调用伪装定时执行。核对 occurrence 的时间、定义和版本；明确 AUTO_LOW_RISK 后，符合策略的 USER 原文进入有效记忆，工具目录仍为空 | [MemoryTimedScheduleIntegrationTest](../../javaclaw-app-server/src/test/java/com/javaclaw/server/extension/MemoryTimedScheduleIntegrationTest.java) |

以上为五项本轮新增闭环，不是整个项目的测试总数。这些场景已纳入本轮最终 clean verify，统计使用全新 clean
输出，不将先前定向执行重复计数。

## 本轮发现并修复的问题

| 问题 | 修复及不变量 | 验证依据 |
| --- | --- | --- |
| 派生学习 Turn 经过延迟装配端口后没有到达真实派生执行入口 | `DeferredTurnOrchestrationPort.executeDerived` 显式委托真实实现，继续走来源排除路径；不退回普通 execute | 派生端口契约回归及上述真实学习闭环 |
| resolve 完成后、FX 接管前切换文档会遗漏句柄；worker 排队任务被取消也可能吞掉回收责任 | Future 完成直接安排 FX 所有权移交；当前 epoch 才能接收版本，过期或关闭状态释放句柄；读取任务在接管后启动，不承担尚未接管句柄的唯一清理责任 | [DocumentPreviewPaneTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/document/DocumentPreviewPaneTest.java)覆盖接收已排队、worker 被占用、迟到结果及撤权 |
| 正常聊天切到简版后未提交流正文及历史入口缺失 | 原生投影加入暂态正文、未完成状态和历史分页，按最终消息 ID 去重；不生成新的持久 Item | [ShellChatFallbackTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/shell/ShellChatFallbackTest.java) |
| 简版用户向上阅读时新分片把列表拉回末尾 | 原生滚轮、键盘和滚动条阅读暂停跟随；程序定位不冒充用户操作；用户可明确返回最新 | 同上；保持同一 Presenter 跟随状态 |
| WebView 重试或故障恢复后跳回最新，丢失阅读位置 | 宿主保存有界的跟随状态及消息 ID / 相对位置锚点；按代次、上下文和版本接收，切上下文清空；只重建展示，不重新发起对话 | 历史前插、持续输出、显式重试及自动恢复场景 |
| 多个 JavaFX 进程争用默认 WebKit profile 目录 | 首次 load 前使用 PID 与随机后缀组成的进程独占临时目录；同 JVM 复用，只尽力删除本 owner 创建的路径，不扫描其他目录、不跟随符号链接 | [WebSurfaceRuntimeTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/web/WebSurfaceRuntimeTest.java)含两个独立 JavaFX 进程同时显示 |
| WebKit 有标签但没有图谱圆点；先前加入 moveTo 只消除空路径异常 | 保留普通 Canvas 路径模式，以四段 Bézier 曲线构造完整椭圆；填充和选中边框仍由 Cytoscape 负责，不修改 vendor bundle 或伪造节点像素 | 真实节点中心像素断言；浅、深主题实际节点及橙色选中边框 |
| 图谱字体不随外观字号变化，单节点首次自动放大成巨字 | Canvas 显式使用原生主题探针的字号与 DOM 计算字体；首次布局缩放至多为 1，继续允许用户主动放大 | [GraphAppearanceAcceptanceTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/web/GraphAppearanceAcceptanceTest.java)覆盖字号预览和取消 |
| 空聊天欢迎区偏上，欢迎文字与角色字号偏离原生层级 | 欢迎区恢复居中，标题 24px、标记 44px、间距 12px；角色恢复 11px 及粗体 | 同内容原生 / Web 空态及聊天截图 |
| 窄文档侧栏的操作按钮挤压 | 原生工具栏允许换行，保留完整动作名称；正文水平滚动继续由文档区域承担 | 窄栏按钮边界断言及截图 |
| 文档撤权后仍可能复显旧 WebKit 纹理 | 清空原生正文及句柄后 suspend 旧宿主，丢弃原 WebView、桥及纹理，再以新的空状态恢复；不能仅靠清 DOM 或空状态 ACK 推断原生帧已清除 | 撤权及关闭场景检查原生 / Web 内容及真实快照，旧渲染实例不能复显 |
| 最小窗口人工决议的长字段名省略 | 字段标签允许换行，保持现有表单结构与样式 | 补采深色最小窗口已显示完整时间字段名，未再省略 |

本轮没有以关闭安全校验、降低覆盖率、扩大 suppression、修改历史 migration 或更新截图参考图来消除失败。

## 聊天与文档：八个场景、21 张组件原始图

维护入口为 [ChatDocumentAcceptanceReplay](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/acceptance/chatdocument/ChatDocumentAcceptanceReplay.java)，
与 JUnit 使用同一场景。该层使用确定性文档 Gateway，单独记录其边界，不将它误写为真实服务端文件授权测试。

| 场景 | 重点 | 代表证据 |
| --- | --- | --- |
| 同内容原生 / Web 对比 | 原生生产单元格与 Web 投影使用相同消息，核对配色、标题、正文和引用表现 | [原生](chat-webview-memory-v3/functional-ui/chat-document/chat-native-reference.png)、[Web](chat-webview-memory-v3/functional-ui/chat-document/chat-web-comparison.png) |
| 空态层级与居中 | 欢迎区位置、标记、标题和角色字号 | [原生空态](chat-webview-memory-v3/functional-ui/chat-document/chat-native-welcome.png)、[Web 空态](chat-webview-memory-v3/functional-ui/chat-document/chat-web-welcome.png) |
| 流式完成、引用与大正文 | 暂态被相同消息身份的持久正文替代；文件/代码入口可达；超大正文有完整文档入口；切换不保留旧消息 | [流式](chat-webview-memory-v3/functional-ui/chat-document/chat-streaming.png)、[完成及引用](chat-webview-memory-v3/functional-ui/chat-document/chat-completed-references.png)、[大正文](chat-webview-memory-v3/functional-ui/chat-document/chat-large-body.png) |
| 阅读锚点与恢复 | 历史前插、追加输出、显式重试和自动恢复后保留同一可见消息及相对位置 | [锚点](chat-webview-memory-v3/functional-ui/chat-document/chat-history-anchor.png)、[前插](chat-webview-memory-v3/functional-ui/chat-document/chat-history-prepended.png)、[重试](chat-webview-memory-v3/functional-ui/chat-document/chat-history-after-retry.png)、[自动恢复](chat-webview-memory-v3/functional-ui/chat-document/chat-history-after-recovery.png) |
| Markdown 与相对资源 | 标题、表格、代码、相对图片和链接经只读 Gateway 展示 | [Markdown](chat-webview-memory-v3/functional-ui/chat-document/document-markdown.png)、[相对文本](chat-webview-memory-v3/functional-ui/chat-document/document-relative-text.png) |
| 代码行与分页 | 目标行、前后页、健康页面简版和重试均保留正文 | [目标行](chat-webview-memory-v3/functional-ui/chat-document/document-code-target.png)、[简版](chat-webview-memory-v3/functional-ui/chat-document/document-code-native.png)、[恢复](chat-webview-memory-v3/functional-ui/chat-document/document-code-recovered.png) |
| 图片、撤权及不支持格式 | PNG、GIF 首帧、撤权后的内容清空、不支持格式的已验证元信息 | [PNG](chat-webview-memory-v3/functional-ui/chat-document/document-image-png.png)、[GIF](chat-webview-memory-v3/functional-ui/chat-document/document-image-gif.png)、[撤权](chat-webview-memory-v3/functional-ui/chat-document/document-revoked.png)、[不支持格式](chat-webview-memory-v3/functional-ui/chat-document/document-unsupported.png) |
| 窄侧栏 | 上一页、下一页、重新读取、简版及关闭按钮完整可见，允许换行 | [工具栏](chat-webview-memory-v3/functional-ui/chat-document/document-narrow-toolbar.png) |

21 张为图像数量，8 项为功能场景数量，二者不能相加充当测试数。逐图元信息、尺寸、摘要及场景目录随截图归档。

## 真实 SDK 与生产 UI：20 张原始图

维护入口为 [SdkUiAcceptanceDesktop](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/settings/SdkUiAcceptanceDesktop.java)、
[SdkUiAcceptanceReplay](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/settings/SdkUiAcceptanceReplay.java)及
[MemoryUiAcceptanceServer](../../javaclaw-app-server/src/test/java/com/javaclaw/server/extension/MemoryUiAcceptanceServer.java)。
服务端夹具预置可追溯的公开对话、记忆冲突及学习作业；Desktop 本身只经 SDK 取得这些数据，不直接读取测试 H2。

新页面覆盖两个有明确含义的外观组合：

- Emerald、标准密度、100% 字号、标准管理窗口。
- Midnight、紧凑密度、120% 字号、最小管理窗口。

管理窗口外框目标为 1040 × 740 和 880 × 620；Scene 原始 PNG 在本机通常为 1040 × 712 和 880 × 592，
窗口装饰不计入 Scene。主聊天及文档为 1280 × 820 的 Scene。实际尺寸以每张元信息为准。

| 页面与状态 | 图数 | 代表证据 |
| --- | ---: | --- |
| 聊天历史、增量、完成、引用文档 | 4 | [历史](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-chat-history.png)、[流式](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-chat-stream.png)、[完成](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-chat-completed.png)、[文档](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-document-reference.png) |
| 记忆图谱首屏与节点详情，各两个组合 | 4 | [标准图谱详情](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-memory-graph-detail-standard.png)、[深色图谱详情](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-memory-graph-detail-dark-minimum.png) |
| 冲突列表与人工决议，各两个组合 | 4 | [标准决议](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-memory-conflicts-detail-standard.png)、[最小深色决议](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-memory-conflicts-detail-dark-minimum.png) |
| 后台学习配置与批次审计，各两个组合 | 4 | [标准批次审计](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-memory-background-learning-detail-standard.png)、[深色批次审计](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-memory-background-learning-detail-dark-minimum.png) |
| 托管定时任务，两个组合 | 2 | [标准任务](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-schedule-standard.png)、[深色任务](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-schedule-dark-minimum.png) |
| 后台 Job 列表和学习作业详情，两个组合 | 2 | [标准 Job](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-jobs-standard.png)、[深色 Job](chat-webview-memory-v3/functional-ui/sdk-ui/sdk-jobs-dark-minimum.png) |

### 采样时序纠正

初次人工审图发现三处采样偏早：历史图仍是欢迎空态，Job 图仍是“正在读取”，完成图的文字虽完整但 Turn
仍为 RUNNING。这些图片不能作为相应终态已经展示的证据，不能仅凭文件名或 WebKit ACK 判定成功。

重放入口已增加真实目标状态等待：

- 历史图等待目标权威 Item ID 出现在实际 DOM 中。
- 完成图等待流终态 COMPLETED、活动 Turn 清空及输入恢复，再检查持久引用。
- 文档图等待目标正文和相对图片实际加载完成。
- Job 图等待至少两个真实作业，选中 conversation-learning，并等待已完成状态和原生详情。

补采图已再次人工核对：历史消息实际可见，完成图不再显示 RUNNING 且发送按钮恢复；两个 Job 页面均已加载真实
作业及所选学习作业详情，标准窗口可见“已完成”；深色最小窗口的人工决议时间字段名已经换行完整展示。
这关闭了上述采样时序及字段名省略的证据问题。最小 Job 窗口的状态详情部分位于纵向视口外，终态同时由重放
中的权威状态及页面文本断言确认，不称为该截图已经展示全部详情。

最终交付使用 clean verify 后重新执行维护脚本取得的图与摘要，SDK 重放使用新建的临时 H2 和工作区。
最终 41 张 PNG 已再次核对尺寸和 SHA256，并审阅关键状态；此前的欢迎空态、加载态及提前采样图片不作为终态证据。

### 人工审图结论

已审阅样本中，图谱圆点与选中边框清楚可见，单节点不再自动放大为巨字；浅色和深色 120% 字号均可读。
右侧文档中的标题、表格、相对图片及代码显示正常，长代码具有水平滚动入口，操作按钮换行后可见。
学习批次具有 COMMITTED 数据，冲突列表具有 PENDING 数据，托管任务具有对应学习计划。

最小窗口的宽表格会发生最右列局部位于横向视口外的情况；不将一张静态截图解释为所有列已经完整展示。
Job 页左侧列表在详情出现后较窄，长名称和状态被裁切，完整值在右侧详情可读；此为尚未修复的可读性问题，
不将当前图解释为左侧长名称已完整显示。对照 HEAD，AutomationJobSettingsPage 列表配置、ListDetailPane
分栏比例与 DetailCell 宽度策略均已有，本轮的新学习任务长名称使该问题更明显，本轮未修改该布局策略。
人工决议长字段名省略已通过标签换行修复并在补采图中确认。
图谱 SDK 样本为一个节点、零关系；上述视觉结论不能证明多节点关系线及大图的表现，也不外推到全部页面组合。

## 509f197、HEAD 与本轮 UI 差异归因

对比基线为 `509f197` 与本轮工作树之前的 `HEAD=ff03b878d76ed23ee53e7f3882039285c8ce046c`。
旧版资料仅用于表现层对比，没有恢复旧 Runtime、Spring Context 或数据读取链。

`509f197:src/main/resources/css/chat.css` 的 `.root` 令牌块与当前
`javaclaw-desktop/src/main/resources/css/design-tokens-controls.css` 对应块字节一致，SHA256 均为
`d6ca6b31c8cc2fd41aab15f28d30dba1b5f93192a992fe3b42cacedd2efe0551`。
该代码基线默认就是 Emerald；旧文档中的蓝色截图不能单独用来判定本轮主题漂移。

| 观察项 | 归因 |
| --- | --- |
| 左栏 pref/min/max 宽度 240 / 200 / 280 | 三者保持一致 |
| 右栏由旧版 280 改为 340 | HEAD 已有的 V6 布局；本轮未改此 FXML 宽度 |
| 旧版头像、时间、模型徽章与当前简化角色行不同 | HEAD 原生 TranscriptCell 已仅展示 title / body；不能归咎于本轮 WebView |
| 输入区运行配置、审批面板及统一设置中心 | HEAD 已有的 V6 结构 |
| 聊天宿主、文档 / 待处理页签、Web 图谱 | 本轮已授权新增；继续复用原生外壳、组件及主题令牌 |
| 欢迎区位置、欢迎标题 / 图标和角色字号 | 本轮发现的偏差，已对齐原生层级 |
| 正文 Markdown、代码边框和引用卡片 | 新渲染能力带来的表现差异；通过同内容原生 / Web 图明确展示，不声称全文像素相同 |
| Web 卡片与旧原生 VBox 的内边距、阴影和边框细节 | 不具备逐像素相同条件；保留颜色与角色卡片语言，并在原始对比图中记录实际差异 |

### 字号和密度传递

WebThemeProbe 从原生 CSS 计算后的 Label 取得颜色、字体及字号；WebSurfaceHost 检测计算值变化并传给网页。
聊天和文档正文继承该字号，图谱 Canvas 在本轮补上显式字体传递。预览和取消继续使用原有外观管理器，
没有增加第二套用户主题配置。

既有密度规则作用于 platform-page、platform-section-card 和 platform-detail-cell。HEAD 原生聊天本身
没有独立的密度间距规则，因此不能把 Web 聊天固定内边距单独判为本轮丢失密度能力。文档和图谱外层原生组件
仍按上述规则响应密度。

### 54 张 Golden 的准确范围

现有 ManagementCenterGoldenIT 的 54 图矩阵是 **9 主题 × 3 密度 × 2 窗口规格**，字号固定为 STANDARD。
它渲染生产设置中心的 **appearance 页**，不连接 App Server，不包含聊天正文、文档或记忆图谱，
不能用来替代本轮新增页面的截图验收，也不能声称已经覆盖所有字号。

本轮没有更新既有 Golden 基线、截图夹具或比较规则。工作树中已有的 27 张参考图及 manifest 变化来自上一轮
JavaFX 26 的滚动条末端像素审阅，详见[上一轮 Golden 审阅](chat-webview-memory-v3-golden-review.md)；
本轮没有再次借这些参考图吸收新的产品 UI 变化。

## 最终门禁记录

2026-09-08 08:42:33（UTC+08:00）完成，耗时 17 分 08 秒，结果为 **BUILD SUCCESS**。
14 个模块及父项目合计 15 个 Reactor project 全部 SUCCESS，执行命令为：

```bash
mvn -s /private/tmp/javaclaw-webview-maven-settings.xml spotless:apply clean verify spotless:check checkstyle:check
```

临时 settings 仅使用官方 Maven Central 镜像解决本机缓存仓库身份差异，未修改个人或全局配置。
原始日志为 `/private/tmp/javaclaw-functional-final-verify.log`，已原样归档为
[完整构建日志](chat-webview-memory-v3/functional-ui/full-verify.txt)，SHA256 为
`734b947d2c13e9b2ce51c220180155cd767078d90fb00a68b7d7a317f1893113`。

共 **2527 项测试记录：2502 项通过、25 项条件跳过、0 失败、0 错误**。Desktop 的 333 项包含 332 项单测和
1 项 Golden 集成测试；该 Golden 覆盖 54 张 appearance 图，不能将这 54 张另外加到测试总数中。

| 模块 | 测试记录 | 通过 | 条件跳过 |
| --- | ---: | ---: | ---: |
| api | 105 | 105 | 0 |
| extension-spi | 66 | 66 | 0 |
| protocol | 149 | 149 | 0 |
| agent-runtime | 42 | 42 | 0 |
| model-adapters | 63 | 63 | 0 |
| builtin-contracts | 67 | 67 | 0 |
| builtin-extensions | 201 | 201 | 0 |
| native-hosts | 201 | 184 | 17 |
| browser-service | 47 | 47 | 0 |
| app-server | 1035 | 1029 | 6 |
| knowledge-worker | 7 | 7 | 0 |
| client | 122 | 122 | 0 |
| desktop | 333 | 333 | 0 |
| packaging | 89 | 87 | 2 |

25 项条件跳过包括其他 OS 的原生路径、系统凭据前置、未启用的真实 Provider 及需要显式归档或网络配置的
Coding / Browser 验收。逐项测试名及跳过原因保留在机器记录中，不能计为已经执行通过。

| 项目 | 本轮最终状态 |
| --- | --- |
| Spotless apply、spotless:check、checkstyle:check | 通过；0 Checkstyle violations |
| 全模块 clean verify、覆盖率及架构检查 | 通过原有门槛；App Server 行 / 分支 91.57% / 80.58%，Desktop 87.86% / 71.51%；其余模块见机器记录 |
| 测试通过 / 跳过 / 失败 / 错误计数 | 2502 / 25 / 0 / 0，共 2527 项，不复用上一轮计数 |
| 原有 54 图 Golden | 通过；范围仍仅 appearance 页、标准字号，未修改参考图 |
| 最终组件重放 8 场景 / 21 图 | 最终构建产物重放退出码 0；21 张原图及元信息已归档、摘要核对通过 |
| 最终 SDK UI 20 图及补采时序 | 新临时工作区重放退出码 0；20 张原图、文本及元信息已归档，终态等待断言通过 |
| 发行、许可证、SBOM、ZIP | 构建与校验通过；产物摘要见机器记录，不等于原生安装 / 签名验收 |
| 打包运行时健康检查 | distribution/bin/javaclaw-health 使用内置运行时和临时 data-v6，退出码 0 |
| git diff --check | 通过 |

9 份既有 V001–V004 SQL 与 HEAD 字节一致；Protocol 仍为 v3、方法目录 172 项、数据目录 data-v6，保持 14
模块。当前 ZIP 为 485232841 字节，SHA256 为
`68e64e807801e804c9be7341717dfcc96294c94a8570951fd5a9bd795bb69173`。

两项可维护重放命令如下；它们读取最近一次 Maven 生成的测试 classpath，需先完成上述构建。在本轮 macOS 上
执行通过，不能把 UDS 路径脚本当作已经在 Windows 上验证的入口。

```bash
python3 scripts/replay-chat-document-acceptance.py /private/tmp/javaclaw-final-chat-document
python3 scripts/replay-sdk-ui-acceptance.py /private/tmp/javaclaw-final-sdk-ui
javaclaw-packaging/target/distribution/bin/javaclaw-health
```

组件目录中撤权图已无原图片特征像素；历史前插、重试和自动恢复的正文区域与锚点图像素一致，仅滚动条变化。
定向场景、程序化生产交互、人工审图和完整构建各自保留证据边界，不互相替代。

### 首次定向执行中的未归因失败

本轮最初的定向命令曾在既有 `SandboxedWorkerContractsTest.macOS原生Worker仅通过管道交换且调用方拥有进程`
失败：管道回显预期 11 字节，实际为 0。[原始日志](chat-webview-memory-v3/functional-ui/initial-targeted-failure.txt)
保留该次失败，临时原路径为 `/private/tmp/javaclaw-memory-chain-test.log`。
本轮没有通过修改该原生测试断言、跳过它或降低门槛处理此失败。之后最终原始全量 clean verify 中该类 6 项
测试全部执行通过，未再次观察到该失败；**首次失败根因尚未查明**，不能据后续通过宣称它已经得到确定性修复。

## 真实限制与未验证项

- 本轮仅有 macOS arm64 的本机证据；未执行其他四个 Runner 的同轮回执、Windows / Linux 输入法、DPI、
  焦点、安装、签名、公证或登录启动验收。
- 新页面只覆盖两个明确外观组合，未覆盖 9 主题 × 3 密度 × 4 字号 × 所有尺寸的完整交叉矩阵。
- 程序化控件事件、JS 桥动作和 Scene snapshot 不等同于 OS 人工鼠标键盘流程、可访问性读屏或光学时延测试。
- WebView 的页面超时恢复不能保证进程级 WebKit 原生崩溃恢复。临时 profile 在强制终止或 OS 锁延迟释放后
  可能残留于 OS 临时目录，清理不会强制解锁或扫描其他 owner。
- 本机 WebKit 彩色 emoji 的既有字形异常仍未修复；JavaFX 25 / 26 均可复现，原生 Label 正常。
  保留简版入口，不声称所有字体已经通过。
- 文档支持 Markdown、代码、文本及 PNG / JPEG / GIF 首帧；PDF、Office 等只显示已验证元信息和不支持原因。
- 本轮没有对真实模型提取准确率、冲突识别质量或长期记忆质量进行付费或外部模型评估。
- 定时任务依赖 App Server 持续运行。真实 Quartz 短时触发、取消及 H2 重启回归不等于睡眠唤醒、多日运行、
  系统注销和所有 OS 服务管理行为均已验收。
- 原生 / Web 同内容视觉对比没有建立旧 UI 与新 UI 的等条件性能基准，本报告不声明性能提升。
- 既有 Coding 验收中的 macOS Gradle、Windows 原子替换等限制不因本轮聊天展示验证而消失。
