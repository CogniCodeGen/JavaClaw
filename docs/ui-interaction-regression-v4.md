# JavaClaw v4 交互差异矩阵与回归审查

状态：已按本矩阵实现；最终验证结果见第 9 节
审查日期：2026-08-30
历史交互来源：`509f1970e3a569a6da76e0525312d2b10041675e`
视觉规范：[JavaClaw UI 设计基线与回归审查规范](ui-design-regression-baseline.md)
视觉审查：[JavaClaw v4 UI 回归审查报告](ui-regression-review-v4.md)

## 1. 判定原则

本报告不把“页面能打开、按钮存在、截图稳定”判定为交互完成。每项能力必须形成可观察闭环：

1. 用户能从稳定入口到达操作；
2. 操作进入明确状态，重复触发不会产生重复副作用；
3. 成功、校验失败、服务端失败和结果未知互不混淆；
4. 离开并重新读取后，服务端持久结果与界面结论一致；
5. 草稿、焦点、选择和滚动位置不会被无关后台请求或响应式切换静默破坏。

v4 继续使用 SDK-only Desktop、单一管理窗口、三栏响应式主窗口和 `Thread → Turn → Item`。历史交互只提供成熟原则，
不恢复旧 Runtime、Store、Spring Context、Session/Conversation/Run 模型或已淘汰的安全能力。

## 2. 冻结差异矩阵

| 领域 | `509f197` 成熟原则 | 本轮前差异及原因 | 分类 | v4 处理与证据 |
|---|---|---|---|---|
| 会话查找 | 搜索、分区和可预测选择 | v4 初始迁移只保留平面 Thread 列表，因为先完成 SDK 读取链 | Desktop | 增加活跃/已归档/全部、结果数量及今天/本周/更早分组 |
| 批量会话 | 多对象操作逐项反馈 | 初始列表没有选择状态，也没有部分成功模型 | Desktop | 选择管理、全选、归档、恢复、删除；成功项移除，失败项保留选择与原因 |
| 工作区管理 | 创建后仍可重命名、删除登记 | 初始 v4 只有创建和选择，迁移时漏掉生命周期尾部 | Desktop | 恢复重命名与删除；删除只移除登记，关联 Thread 拒绝理由由服务端返回 |
| Thread 草稿 | 切换上下文不丢未发送输入 | 输入框直接绑定当前页面，没有 Thread 级草稿所有权 | Desktop | `ConversationDraftStore` 按 Workspace/Thread 保存文本、附件、Profile 和最近已发送文本 |
| 独立提交 | 一个 Thread 上传不应锁死另一个 Thread | 全局 submission guard 把后台忙误当输入状态 | Desktop | 改为 `SubmissionKey` 级上下文；同一 Thread 防重复，不同 Thread 可并发提交 |
| 多附件 | 可逐项审查、移除和失败恢复 | 初始 SDK UI 只允许一个文件，源于最小迁移壳 | Desktop | 多选/拖放/逐项移除；单文件 256 MiB 协议校验；上传并发固定为 2 |
| 附件失败 | 任一失败不启动业务动作 | 上传和 Turn 启动没有整体提交边界 | Desktop | 所有上传成功后才启动 Turn；失败保留完整草稿并尽力释放未引用附件 |
| Composer 状态 | 输入操作只受当前 Run/Turn 影响 | 全局 `busy` 会被刷新、主题等后台请求占用 | Desktop | `ComposerActivity` 独立表达空闲、提交、活动、steer、等待交互、中断和取消上传 |
| steer/停止 | 活动任务允许追加并可确认中断 | 初始 v4 只支持开始 Turn | Desktop | 文本在活动 Turn 中走 `steer`；停止失败恢复原状态；停止从不清除草稿 |
| Transcript 层级 | 消息、工具、审批和错误有上下文 | 整个 Turn 被压成文本块，来自 DTO 到页面的直接最小映射 | Desktop | `TranscriptProjector` 按 Turn 投影用户、助手、计划、执行、交互、产物和错误 Item |
| 流式内容 | delta 属于正在生成的助手消息 | 即时流曾显示在输入区上方，和持久消息割裂 | Desktop | delta 进入助手占位块；持久 Item 到达后替换并按 sequence 去重 |
| 消息操作 | 复制、引用、导出与继续路径就近可达 | 初始 v4 无消息级操作，因为 Item 元数据尚未投影 | Desktop | 助手提供复制/引用/导出/关联执行/新分支重试；用户提供复制/引用/编辑重试 |
| 历史修改 | 不破坏审计历史 | 旧 UI 允许清空/删除消息，但不适配不可变 Item | 有意不兼容 | 不提供物理删除；用“从此 Turn 建立分支”替代，源 Thread 不变 |
| 滚动跟随 | 用户阅读旧内容时不抢位置 | 列表刷新总是滚到底部 | Desktop | `TranscriptFollowState` 区分近底部/主动上滚并累计新消息；展开详情不重置位置 |
| 审批与输入 | 请求应在产生它的上下文内回答 | 初始 v4 依赖通知或弹窗，重连恢复不可靠 | Desktop | Approval/UserInput 作为 transcript 内联卡片；持久 Item 是权威来源 |
| 防重复交互 | 提交后到持久确认前不可重复 | 仅按钮瞬时禁用，刷新后可能重新出现 | Desktop | `InteractionSubmissionTracker` 按 approval/request ID 保持 in-flight/awaiting-persistence 并在快照中对账 |
| 执行元数据 | 只展示可信 Profile/Provider/模型/Token | Desktop 没有类型化来源，不能安全猜测 | 接口扩展 | `thread/execution/summary` 与 `ThreadExecutionSummaryInfo`，不返回 Secret、原始 JSON 或隐藏推理 |
| 原子重试分支 | 分支与启动不能留下半完成状态 | Desktop 组合两个请求会出现竞态并错误复用权限 | 接口扩展 | `thread/retryInNewBranch` 由服务端组合分支与新 Turn；启动失败会事务清理分支及内部幂等记录，重新校验 Profile、附件、审批和沙箱 |
| 处理进度 | 显示阶段与安全执行摘要 | 初始 v4 只有 transcript，宽屏信息失衡 | Desktop | 思考/规划/执行/整理四阶段及工具、文件、MCP、子任务、交互、Token；费用未知显示“—” |
| 抽屉 | 窄屏临时层必须可退出且焦点可恢复 | 早期响应式只隐藏栏位，没有完整 modal 行为 | Desktop | 遮罩、外部点击、Esc、焦点约束/恢复；小于 960 px 时左右抽屉互斥 |
| 键盘 | 高频流程有等价键盘操作 | 架构迁移只留下 Enter，其他命令未重新接线 | Desktop | 恢复新对话、设置、侧栏、聚焦输入、MCP、帮助、发送、换行、停止和上键历史 |
| 管理页 dirty | 切对象、切页和关闭前保护草稿 | 12 页共用展示壳但没有生命周期契约 | Desktop | `ManagementPageLifecycle` + `ManagementEditSession`，统一保存/放弃/取消与 Ctrl/Cmd+S |
| 保存重试 | 校验/服务端失败后仍能再次保存 | 保存中的 `canSave` 没有在失败后重算 | Desktop | 保存开始禁用；确定失败后保持 dirty 并恢复 `canSave`；成功才推进基线 |
| revision 冲突 | 绝不覆盖服务端较新版本 | 初始页只显示一般错误，无法区分冲突 | Desktop | 保留本地草稿，显示冲突横幅、服务端摘要和“放弃本地并重新加载” |
| 资源 busy | 写操作只锁定目标资源 | 全局 busy 同时锁定无关导航/表单 | Desktop | `CommandContext` 只禁用发起命令的按钮；全局指示器只报告并发，不作为输入锁 |
| 读取取消 | 切页后旧响应不能覆盖新页 | 无页面代次和请求分类 | Desktop | 页面 generation 拒绝迟到结果；纯读取 Future 可取消，已提交写请求不伪装取消或自动重放 |
| Workflow | 图编辑需要结构反馈和执行轨迹 | 通用 JSON/表单无法表达节点、边与 Inspector | Desktop | 节点画布、连线摘要、Inspector、即时可达性/引用/访问预算校验，仍保存原 Node DTO |
| SDD | 规格、设计、任务、执行、核验分段 | 初始页只显示规格 TextArea 和文档清单 | Desktop | 五段 Tab、OpenSpec 预览采用、任务文档摘要、执行 Items 和验收条件 |
| Knowledge 统计 | 列表应显示索引权威状态 | Desktop 逐条读取会形成 N+1，内部存储也不应泄露 | 接口扩展 | `knowledge/source/stats` 批量返回 generation、片段数、模式、时间和脱敏失败摘要 |
| Cron 预览 | 保存前应看到时区下的未来触发 | 客户端自行算会与服务端 Quartz 语义漂移 | 接口扩展 | `schedule/preview` 由服务端校验并返回 1–20 次触发；页面默认显示未来五次 |
| Plugin/MCP 安全 | 安装/连接先审权限与认证边界 | 通用表单把凭据、来源和普通配置混排 | Desktop | Plugin ZIP 审查签名/来源/权限并释放未引用审查附件；MCP HTTPS 分步认证；Plugin stdio 只读；Secret 不回显 |
| 微动效/Toast | 状态变化有克制反馈且不阻断输入 | 初始 v4 为减少视觉变量移除了旧情感语法 | Desktop | 恢复消息淡入、成功 pop、错误 shake 和 WindowToast；同节点只保留一个动画，支持 reduceMotion |
| 原始推理/费用 | 只展示可公开且可信的结果 | 旧界面可能用占位或零值造成误解 | 有意不兼容 | 不展示隐藏推理、原始事件 JSON；未知费用保持“—” |
| 旧扩展能力 | 权限边界优先于表面兼容 | Plugin 3、可信 JAR、任意 stdio、本地模型资产不符合 v4 安全边界 | 有意不兼容 | 界面不给入口；替代路径为 Plugin 4、声明式 stdio、HTTPS MCP 和云 Provider |

## 3. 主窗口状态机

| 状态 | 主按钮 | 附件 | 停止 | 持久化/失败规则 |
|---|---|---|---|---|
| 空闲无草稿 | 禁用发送 | 可添加 | 隐藏 | 不创建请求 |
| 有草稿 | 发送 | 多附件可编辑 | 隐藏 | 服务端接受 Turn 后才清除完全相同的草稿快照 |
| 上传/提交中 | 显示提交中并防同 Thread 重复 | 保留预览，可取消上传 | 尚无 Turn 时隐藏 | 任一上传失败不启动 Turn；迟到成功附件尽力释放 |
| Turn 活动 | 有文本时为追加指令 | 禁用并说明原因 | 可见 | 追加成功只清除相同文本，附件仍保留 |
| 等待审批/回答 | 发送禁用，草稿仍保存 | 保留 | 可见 | 只能通过对应内联卡片，以 ID 防重复提交 |
| 中断中 | 禁用 | 保留 | 显示处理中 | 等待服务端确认；失败恢复中断前活动状态 |
| 失败 | 恢复实际可用状态 | 保留 | 按服务端 Turn 状态 | 显示可恢复错误，不把结果未知冒充失败或成功 |

独立 Thread 的上传/提交使用不同 `SubmissionKey`，因此切换 Thread 后可以开始另一项提交；同一 Thread 的重复点击则明确拒绝。
上传池只有两个并发许可，避免多个大文件无限占用虚拟线程和服务端上传会话。

## 4. Transcript、滚动与内联交互

- transcript 由持久 `ThreadSnapshot`、有序事件和当前 delta 投影，不读取旧 Store，也不在 Desktop 解析内部配置。
- 助手/用户消息操作都以 Turn 为历史边界。重新生成和编辑重试调用原子分支接口，源 Thread 不变。
- 当视口接近底部时自动跟随；用户上滚后只增加“新消息”计数。点击提示才恢复跟随。
- 每个 Thread 在进程内保存首个可见行位置；切换会话、Profile 或附件不会串用位置。
- Approval 与 UserInput 提交后保持禁用，直到快照中对应持久 Item 已解决；通知只唤醒刷新。
- 进度项可按关联 Item 打开显式查看器；安全摘要不包含隐藏推理或原始 JSON。

## 5. 管理窗口生命周期

所有 12 个领域页都实现 `ManagementPageLifecycle`：

- `dirty`：当前资源是否偏离已确认基线；
- `canSave`：dirty 且当前没有保存请求；
- `loadState`：首次加载、就地刷新、就绪或读取错误；
- `requestSave/discard/cancelReads/dispose`：页面切换、对象切换、Ctrl/Cmd+S 和窗口关闭共用同一状态机。

首次加载 overlay 只覆盖内容区；刷新保留已显示内容；写操作由目标按钮独立 busy。revision 冲突不会自动 merge 或覆盖，
而是保留本地草稿并提供服务端摘要。页面离开会取消纯读取，但不会声称已撤销正在服务端执行的写请求。

领域页的专属补齐包括：

- Settings：外观、云 Provider、连接诊断；凭据只显示已配置/替换。
- Agent Studio：身份与模型、提示词、工具权限、预算；Prompt 优化先预览差异再采用。
- Memory：类型统计、搜索、最近更新、内容/来源/历史分组及固定/恢复/删除。
- Knowledge：状态筛选、generation、片段数、检索模式、索引时间、失败摘要和检索/版本/重建。
- Skill：指令、资源/脚本和历史；导入、导出、采纳、启停、保存、卸载分别反馈。
- Automation：Loop 预算与停机条件、Workflow 图编辑器、SDD 五段视图；dirty 运行时明确选择版本。
- Schedule：Cron/时区即时校验、未来五次预览、启停、立即运行和历史。
- Plugin：ZIP、签名/来源、权限和确认安装审查；保持 Plugin 4。
- MCP：HTTPS、认证、健康、工具发现、OAuth 分组；Plugin 声明的 stdio 只读。
- Sites/项目约定/工作树恢复：补齐加载、空、错误、资源 busy、危险确认、冲突和关联 Thread 导航。

## 6. 向后兼容接口

本轮只增加四组 additive RPC/SDK，不改变原 record 构造、现有方法、数据库权威边界或安全策略：

| RPC | SDK | 安全/兼容约束 |
|---|---|---|
| `thread/execution/summary` | `ThreadClient.executionSummary` | 只返回可信展示字段与 Token，不返回 Secret、原始配置或隐藏推理 |
| `thread/retryInNewBranch` | `ThreadClient.retryInNewBranch` | 服务端单入口分支并启动；失败事务回滚分支与幂等记录，源 Thread 不变，权限与附件重新校验 |
| `knowledge/source/stats` | `KnowledgeClient.sourceStats` | Workspace 批量读取，失败原因脱敏，避免 Desktop N+1 |
| `schedule/preview` | `AutomationClient.previewSchedule` | 只校验和计算，不保存；count 限制 1–20 |

旧客户端无需调用这些方法即可继续工作；Desktop 对执行摘要不可用保持兼容降级，不伪造字段。

## 7. 有意不恢复及替代路径

| 不恢复能力 | 原因 | v4 替代路径 |
|---|---|---|
| 单条消息物理删除/清空历史 | 破坏不可变 Item、审计与重连一致性 | 从目标 Turn 新建分支；归档或删除整个 Thread |
| 隐藏推理、原始事件 JSON | 不属于可公开产品状态，存在安全与误导风险 | 展示阶段、计划、执行摘要和持久产物 |
| 未知费用显示 0 | 会把未知误报为免费 | 显示“—” |
| 旧 Session/Conversation/Run | 与 v4 Thread/Turn/Item 权威模型冲突 | 使用 Thread 分支、归档、压缩与 Item 投影 |
| 本地模型资产、内置邮件、Plugin 3、可信 JAR、任意 stdio MCP | 已超出 v4 支持和安全边界 | 云 Provider、Plugin 4、声明式 stdio 或 HTTPS MCP |

## 8. 回归门禁

- `DesktopFeatureCatalog` 不再只登记页面文字，强制每项填写入口、操作、状态变化和持久结果。
- 状态单测覆盖草稿快照、Composer、滚动跟随、流式去重、进度投影、内联交互 ID 和 Workflow 图校验。
- SDK 内存 JSON-RPC 旅程覆盖切换 Thread 后两项附件提交并发进行、各自启动一个 Turn。
- App Server/SDK 兼容测试覆盖四组新 RPC 的路由、映射、边界校验和持久结果。
- 每个管理页必须继承共享生命周期；源码契约拒绝重新把全局 `busy` 绑定为页面按钮禁用条件。
- JavaFX/真实 Desktop smoke 负责入口、键盘、抽屉焦点、保存状态和持久重读；视觉 Golden 只负责主题与布局。

## 9. 验证记录

已完成并通过：

- `mvn -o spotless:apply`
- `git diff --check`
- `mvn -o spotless:check checkstyle:check`
- Protocol、Runtime、App Server、SDK、Desktop 状态与旅程测试；Desktop 42 项、App Server 161 项均为零失败
- 真实 Desktop smoke：九主题、三种主窗口尺寸、12 个管理页双尺寸、关键反馈状态与 SDK 持久重读
- macOS/JDK 25 显式更新 45 张 Golden 后，重新启动独立候选窗口执行只读像素门禁；零差异图
- 完整 10 模块 `mvn -o verify`：374 项通过，零失败/错误；Windows 专属传输测试与默认关闭的原生 Desktop smoke 共 2 项条件跳过，后者已在上述独立命令中通过

所有夹具继续使用隔离目录与本地合成 Provider，不调用付费模型或用户真实服务。Windows/Linux 不做跨字体像素硬失败，
但仍执行结构、状态、键盘和截图审查；本机没有声称完成 Windows/Linux 原生窗口验证。

## 10. 验收结论规则

只有同时满足下列条件才可标记交互回归通过：无静默草稿丢失、无重复副作用、无全局 busy 误锁、核心流程可纯键盘完成、
响应式抽屉焦点正确、所有领域写操作都有可恢复反馈。任何一项仅有按钮或截图证据，都只能标记“视觉/结构存在”，不能标记“交互完成”。
