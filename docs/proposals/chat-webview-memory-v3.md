# JavaClaw 聊天、预览、后台任务与记忆实施计划 V3

状态：用户于 2026-09-07 授权实施，2026-09-08 已实现并通过本机完整验收；其他平台发行验收待执行。
本计划替代 V2 的待定决策；测试、产物及真实限制见[最终验收记录](../evidence/chat-webview-memory-v3-validation.md)。

## 固定边界

- 保留 509f197 布局、CSS 令牌与原生输入、审批、导航、表单。WebView 仅用于聊天正文、文档和图谱。
- Desktop 仅通过 SDK；App Server 是唯一组合根和 H2 owner；Harness 不识别学习阶段。
- 文档采用 CommonMark 与自有 WebView；右侧“待处理／文档”，首期 Markdown、代码、文本、PNG/JPEG/GIF 首帧，只读。
- JDK 25、JavaFX 26.0.2、CommonMark 0.30.0、highlight.js 11.11.1、Cytoscape.js 3.33.1。
- 保留 Protocol v3/data-v6、14 模块与现有隔离 Worker；无新调度器、Node 服务或图数据库。

## 消息流与 WebView

- 能力 core.turn-stream-v1；subscribe/unsubscribe 是连接控制 COMMAND，event 是 EVENTS/WATERMARK 通知，list 是分页查询。
- STARTED/TEXT_DELTA/COMMITTED/CLOSED/TURN_FINISHED 使用稳定调用身份、预留 messageItemId、独立 cursor/previousCursor。
- publish 返回前提交；公开文本增量只写公开流日志，不再同时写旧 model 文本增量日志；最终 Message Item 仍持久化完整正文。
  50ms 是持久记录投递批量窗口，批量不得删除单条 cursor。
- Journal 涉及 Item 时按 Thread→Turn→checkpoint/Item→Event；纯 delta 只锁 Turn，之后不取 Thread/checkpoint 写锁。
- intent/STARTED、Message/checkpoint/COMMITTED、Turn 终态/TURN_FINISHED 分别同事务；同一 Turn identity 在锁内分配。
- SDK 先登记订阅；日志单 drain 消除空窗；128 事件/256KiB 批次，16KiB 片段，5s 水位与慢写断连，15s 失活恢复。
- SDK 入队限额256条/1MiB；单调用正文超过1,048,576个UTF-16单位时裁剪到约一半尾部，保持Unicode边界和绝对offset。
  累计超过2,097,152个UTF-16单位时清理已提交/关闭调用正文；从Java已应用cursor恢复，页面从Java快照恢复，不重放模型调用。
- 连接幂等按 method/key/请求摘要；关闭 ID 不重激活，容量有界；旧客户端不接收新通知。
- 共用 WebSurfaceHost，三个区域各复用一个 WebView。FX/HTML 同背景，强引用桥、代次、ready 与 render revision。
- 5s 超时重建一次后原生降级；隐藏暂停绘制计时；关闭释放监听、桥、图片和任务。
- 聊天历史每页100，DOM最多128，Java展示缓存500条且32MiB；该预算不代表JVM/WebKit进程RSS上限。
  UI50ms、Markdown200ms，解析移出FX线程。
- 活动正文超过32,768个UTF-16单位以纯文本尾部更新，完整正文超过1,048,576个UTF-16单位转文档分页；保持阅读锚点。
  这两项为Java字符串单位，文档Markdown的2MiB限制另按源文件字节计。
- 本地静态资源、CSP、转义原始HTML、固定桥动作；外部链接交系统浏览器；不承诺原生崩溃可进程内恢复。

## 文档权限与资源

- DocumentReference 区分 Attachment、WorkspaceFile、MessageContent；文件引用使用 workspaceId/sourceItemId/referenceSelector。
- 服务器从来源 Item 推导 Turn、根、路径、行号与摘要，验证类型化结果或真实 Markdown 链接；客户端不选择权限。
- PreviewReadAuthority 交集来源冻结 ceiling、历史与当前 Profile、Workspace/system、冻结 Role 和父级约束，再收窄为只读。
- 已完成 Turn 可以提供证据；缺来源或冻结材料时拒绝读取当前文件，不使用默认 Profile 或临时 grant 扩权。
- resolve/readChunk/resolveResource/renew/close；读取、资源解析和续租都重验当前权限及句柄冻结上限。
- 根绑定、Workspace安全锁、Worktree清理、符号链接与保留目录都必须校验；相对资源不能越过授权根。
- 快照位于已验证 data-v6/cache/document-preview/serverInstanceId 下，所有权受控，句柄绑定连接与Workspace。
- 磁盘总预算128MiB、单源64MiB、每连接8句柄；创建前预留，staging/重试计费；变化最多重试一次，不静默换版。
- 5min闲置租约，可见文档60s续租，隐藏停止；关闭/断连/过期/重启回收，Worker退出前不释放其占用。
- chunk256KiB、Markdown2MiB、文本500行、长行16KiB分段、图片10MiB/2000万像素。
- Attachment真正channel分块读取和摘要校验，不提高RPC帧上限；不支持格式显示元信息及原因。

## 调度、学习与有效记忆

- LearningDefinition 使用既有 SchedulableDefinitionProvider、execution/start 和 ExtensionExecutionReceipt。
- 复用 DEFINITION 子Job投递链，取消 V2 新增异步 Action 返回协议。
- 新增受限 ScheduleDefinitionBindingPort；Host绑定调用者，只允许协调自己贡献的Definition。
- Schedule拥有唯一绑定记录，键为Workspace/扩展/Definition，保存generation、目标revision、ScheduleId和绑定revision。
- Memory事务保存参数和绑定意图；提交后幂等提交短协调Job，现有Supervisor推进；restore补投递未确认意图。
- 重新绑定保留用户修改的时间/时区/名称/启停；托管目标及执行配置只从学习设置修改，服务端同样校验。
- 删除推进generation且解除绑定，旧意图不复活；用户明确重新安排才建新generation。
- 保留SKIP_IF_RUNNING、DO_NOT_CATCH_UP；取消持久请求后安全收敛，Occurrence跟踪子Job到终态。
- 首个Schedule启用注册登录启动，最后一个禁用注销；页面用失效通知及可见时15s对账。
- Memory保留旧DTO，新增MemoryEffectivity、LearningDefinition/Batch、EvidenceReference、ConflictCase、Entity/Assertion及Workspace head。
- Effectivity保存ACTIVE/SUPERSEDED、版本、替代关系、半开时间区间。自然语言条件不可自动判定，不注入模型。
- search/v2返回条件与证据；所有模型入口统一有效性过滤。旧search只返回有效且无条件记录；旧写不能抹掉条件或复活被替代记忆。
- 历史无effectivity记录解释为ACTIVE无条件；删除沿用tombstone；图谱只是可重建投影。
- 学习默认关闭，显式启用后固定回扫30天；默认6h首次触发，支持显式立即执行。
- 每Workspace至多一个活动学习Job，每Job一批：扫描200Item、1次Turn、输入16k/output2k tokens、120s。
- visibleCapabilities必须显式空集合且工具预算0；冻结空目录，不新增工具grant。
- ConversationEvidencePort有界分页固定上界；Core只保存通用完成索引，学习策略和游标归Memory。
- 排除隐藏内容、工具参数、未完成/未知结果及学习自身；区分用户原文、可验证观察与助手陈述。
- 批次冻结证据后在事务外调用模型，事务内复核/提交；batch+attempt幂等，未知结果不被下一周期绕过。
- 自动发布仅逐字合格低风险事实；摘要、推断、关系与冲突都需确认。发布权限取启动与当前策略更严格者。
- 所有有效性/策略/pin/delete/restore/冲突/批次写先CAS Workspace head，再稳定键序写记录；冲突只重做本地校验。
- 候选、有效记忆、冲突、回执、游标同事务；checkpoint恢复读回执，用户expectedRevision冲突保留草稿。
- 冲突提供保留原记忆、采用新记忆、编辑合并、条件并存；拒绝/删除保留去重依据。

## 图谱与交付门禁

- 平台ViewRenderSession按Workspace/页面/schema持有组件；同schema刷新apply数据，不整页撤下重建。
- Graph节点按ID增量更新，位置只保留会话内；隐藏停止布局、离页释放，旧epoch失效。
- Graph选择走ViewSchema policy、数据源、dirty和revision保护，renderer不调用SDK；修改仍用原生表单。
- 初始200节点、展开50、总500节点/1000边、有限breadth-first；原生降级仍可处理冲突。
- 阶段：P0契约基线→P1流→P2宿主聊天→P3文档；P4后台、P5有效记忆可并行；P6学习图谱→P7发行。
- 必测持久化/通知竞态/断线/背压/权限/缓存预算/三处绑定崩溃窗口/删除不复活/批次恢复/并发head/页面生命周期。
- 文字接收至可见P95目标250ms；测1万条历史、持续输出、首屏/滚动/FX响应/CPU/RSS，不能用ACK替代窗口证据。
- 保留五Runner、真实安装包、输入法/DPI/焦点/字体/主题与原生门禁；不降覆盖率、不以付费模型默认测试。
- Golden 比较门禁保持；实测 JavaFX 26 的截图布局传播变化后，按既有审阅流程更新仅滚动条末端有差异的27张参考图。
  原“不更新 Golden”决策的调整依据、逐图像素与旧新摘要见[专项审阅](../evidence/chat-webview-memory-v3-golden-review.md)。
- 遵守中文Javadoc、并发事务契约、Spotless/Checkstyle/完整verify。未验收的平台与性能如实记录。
