# JavaClaw 功能验收报告：2026-09-09

## 范围与当前结论

本轮在 macOS JavaFX Desktop 中检查了本机模型配置、使用和聊天流程，并验证生成期间继续输入、停止及跨对话切换后草稿保留。10:54 的真实回复已有同一 Thread 的配置、消息和受控重启前后 SDK 对照证据。设置中心 30 个入口均已打开；这只证明入口可到达，不能视为全部业务动作、外部连接和异常恢复已验收。

原 IDEA 数据库及备份中未能定位原 Provider 和历史 Turn，因此改用工作区“功能验收 2026-09-09”和 1 条本机 LM Studio 验收 Provider。第一次重启 IDEA 指定目录后，SDK 返回 0 个工作区和 0 个 Provider，先前验收记录的位置未定位。随后重新配置的工作区与 Provider 在[重启前](functional-acceptance-2026-09-09/idea-state-before-restart.txt)和[重启后](functional-acceptance-2026-09-09/idea-state-after-restart.txt)标识及版本一致，但这两份记录没有检查 Thread。首次 `JAVACLAW_PERSIST_OK 42` 的界面观察缺少同线程提交证据，现撤销其作为成功发送或聊天持久化的验收依据；不能据此声称重启丢失了对话数据。

10:54 重新选用模型后，真实 UI 收到 `JAVACLAW_RESTART_VERIFY_1054 42`。同一 Thread `7f0b8afb-3358-4bd0-aeca-896042ec5824` 的 Provider 精确引用 `provider-d3600803-f6c8-4173-a505-a20af0ae34c2@2`、Thread 配置、recent、用户/助手消息和 ready 预览均通过 SDK 检查；[受控重启前](functional-acceptance-2026-09-09/idea-conversation-before-controlled-restart.txt)、[受控重启后](functional-acceptance-2026-09-09/idea-conversation-after-controlled-restart.txt)与[最终构建后](functional-acceptance-2026-09-09/idea-conversation-after-final-build.txt)完整输出逐字节一致。最终真实界面也恢复了这两条消息及选定模型。真实模型请求均访问本机服务，未调用付费模型。

## 已确认的实际操作

| 编号 | 操作与观察 | 验证边界 |
| --- | --- | --- |
| UI-01 | CUA 打开全部 30 个设置入口。 | 页面到达不等于该页所有业务功能通过。 |
| UI-02 | 搜索“定时任务”“记忆”“技能”曾进入说明含关键词的其他页面，随后手动进入目标页。 | 已据此修复标题优先级；最终界面复验归入文末门禁。 |
| UI-03 | 网站会话页面暴露非法 operation。 | 已修正 operation 契约；不能以首次打开记录证明网站业务可用。 |
| UI-04 | 两步向导配置本机 LM Studio，点击“保存并使用”后返回聊天。 | 证明新配置到聊天的实际贯通，不代表所有模型协议和鉴权方式。 |
| UI-05 | 短聊天收到 `JAVACLAW_UI_OK 42`。 | 本机无鉴权聊天请求成功。 |
| UI-06 | 长回复生成期间输入新草稿、滚动到顶部、停止、新建第二对话、切回原对话，新草稿完整保留。 | 覆盖该实际操作序列；原长 Turn 的最终终态未留存，不能据此证明模型已经停止。取消回执、并发迟到、历史分页和失败分支由自动回归补充。 |
| UI-07 | “本地检查”返回 Provider 已由运行时解析；输入确认文本后验证进入 pending，无手工刷新即显示“验证成功 · 耗时24803毫秒 · 输入/输出令牌30/225”。 | 验证确认流程已收到真实模型结果；本地检查本身不等于联网验证。 |
| UI-08 | 密钥库最初显示已锁定、系统凭据服务不可用、凭据 0；原生测试复现主密钥读取长度无效。修复后 12 项凭据测试通过，含 1 项真实 Keychain 存取、替换、删除闭环。 | 原生测试使用临时随机标识并完成清理；不据此推断已有业务 Vault 已解锁。 |
| UI-09 | localhost Embedding 实际发送 1 次请求，返回 1 个 1024 维向量。 | 证明本机向量端点可调用，不等于知识库导入、索引和检索全部通过。 |
| UI-10 | 首次 `JAVACLAW_PERSIST_OK 42` 观察缺少同线程提交证据，撤销作为成功验收依据。10:54 真实 UI 收到 `JAVACLAW_RESTART_VERIFY_1054 42`；同线程 `7f0b8afb-3358-4bd0-aeca-896042ec5824` 的 Provider@2、配置、recent、消息和 ready 状态在受控重启前后、最终构建后完整 SDK 输出一致。 | 受控重启及最终构建对照通过，不代表恢复了早期未定位对话。 |

## 30 个设置入口

入口名称与路由取自 [ManagementCenterWindow](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/settings/ManagementCenterWindow.java) 和 [SettingsPageRegistry](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/settings/SettingsPageRegistry.java)。自动测试列表示仓库已有的覆盖层次，其本轮执行结果统一记录在最终门禁。

| 序号 | 入口 | 能力与自动测试 | 真实 UI 边界 |
| --- | --- | --- | --- |
| 01 | 外观 | 主题、字号、密度预览与保存/取消；JavaFX 页面及偏好状态测试。 | 已打开；不同平台的视觉和持久恢复未据此确认。 |
| 02 | 模型服务 | 连接、密钥、目录、容量、验证和使用；Presenter、JavaFX、SDK、服务端及本地假 HTTP 测试。 | 本机配置、使用、检查和真实验证已执行；其他协议/鉴权另验。 |
| 03 | Agent Studio | 角色、指令、模型和能力限制；状态、草稿保护、JavaFX 及 SDK 测试。 | 已打开；真实角色组合与工具执行未全覆盖。 |
| 04 | 学习策略 | 工作区学习规则和技能建议；ViewSchema、学习选择与执行测试。 | 已打开；用户真实学习资料和长期运行未验收。 |
| 05 | 权限方案 | 只读标准方案、复制版本和有效权限；JavaFX、SDK 与服务端权限测试。 | 已打开；真实授权组合和撤权需独立环境。 |
| 06 | 密钥库 | 主密钥保护、锁定与脱敏状态；页面、Vault 服务和系统凭据测试。 | 原生凭据闭环已通过；已有 Vault 解锁不作推断。 |
| 07 | 无人值守授权 | 定时规则、工具和额度约束；页面、SDK、授权服务及调度测试。 | 已打开；实际无人值守执行未全覆盖。 |
| 08 | 私网授权 | 精确地址预览、确认和撤销；状态、SDK 与网络隔离测试。 | 已打开；真实私网目标未默认访问。 |
| 09 | MCP 外部工具 | 配置、目录、健康检查和 OAuth；JavaFX、MCP 服务及协议测试。 | 已打开；外部 MCP 服务和 OAuth 账号需独立配置。 |
| 10 | 网站会话 | 站点视图、会话与凭据；页面、operation 契约及受控浏览器测试。 | 已发现并修复契约错误；真实网站登录未据此通过。 |
| 11 | 内置扩展 | 可选能力启停与固定 Host 状态；Presenter、JavaFX、SDK 与扩展契约测试。 | 已打开；每项真实扩展运行未全覆盖。 |
| 12 | 第三方扩展 | 安装、升级、健康检查和卸载；页面、RPC、签名与恢复测试。 | 已打开；真实第三方 Bundle 兼容性未验收。 |
| 13 | 信任公钥 | 指纹、导入、确认与撤销；页面、草稿及签名安全测试。 | 已打开；未默认修改用户已有信任。 |
| 14 | 扩展回收站 | 恢复与精确确认清除；页面和扩展恢复测试。 | 已打开；未默认永久清除用户条目。 |
| 15 | 工作区 | 创建、改名、归档和执行默认；状态、JavaFX、SDK 与服务端生命周期测试。 | 已创建验收工作区；全部跨窗口刷新组合由回归补充。 |
| 16 | 编程环境 | 工具链、依赖准备和终端进度；页面、SDK、受控执行与原生隔离测试。 | 已打开；真实项目构建和平台工具链未全覆盖。 |
| 17 | 项目约定 | 层级、摘要、冻结与安全文件名；页面、Presenter 和服务端解析测试。 | 已打开；未静默修改用户项目指令。 |
| 18 | 隔离工作区恢复 | 中断、补丁、备份、恢复与清理；页面、服务端路径及原生文件测试。 | 已打开；未对用户仓库执行破坏性恢复。 |
| 19 | 后台任务 | 检查点、暂停、恢复与取消；页面、SDK 和任务监督器测试。 | 已打开；真实长任务恢复未全覆盖。 |
| 20 | 计划 | 草稿、决策、提交与执行；ViewSchema、SDK 和计划领域测试。 | 已打开；完整真实计划执行未验收。 |
| 21 | 循环任务 | 目标、迭代、验证与停止；ViewSchema、SDK 和循环执行测试。 | 已打开；长期迭代及恢复未实测。 |
| 22 | 工作流 | 输入绑定、节点执行和恢复；视图、SDK、身份与执行边界测试。 | 已打开；真实外部节点链未全覆盖。 |
| 23 | 规格驱动开发（SDD） | 规格、审批、实现和验证证据；视图、SDK 与领域执行测试。 | 已打开；完整项目验收链未实测。 |
| 24 | 定时任务 | 规则、触发、记录和失败恢复；视图、SDK、调度及授权绑定测试。 | 搜索误导航后手动打开；真实定时触发未全覆盖。 |
| 25 | 记忆 | 来源、历史、冲突和图谱；视图、领域行为、恢复与服务链测试。 | 搜索误导航后手动打开；用户既有记忆未评估。 |
| 26 | 知识库 | 导入、提取、索引和检索；视图、SDK、领域及 Embedding 服务链测试。 | 已打开，向量端点已实际调用；10:48 构建的全新发行镜像的原生 DOCX 提取通过，完整导入/索引/检索链尚未验收。 |
| 27 | 技能 | 草稿、资源、发布和能力边界；视图、SDK、生命周期与资源执行测试。 | 搜索误导航后手动打开；10:48 构建的全新发行镜像的原生 Java/JShell 执行通过，用户真实技能发布未验收。 |
| 28 | 服务连接 | 协议协商、断线与重连；页面、SDK 连接失败和服务生命周期测试。 | 已打开；协议提示已修正，平台重连组合由回归补充。 |
| 29 | 运行与启动 | launcher、保活、退出、登录启动和托盘；页面、启动进程与原生测试。 | 已打开并修复启动入口；OS 登录启动和托盘另验。 |
| 30 | 诊断 | 脱敏状态、复制和导出；JavaFX 页面、SDK 及诊断快照测试。 | 已打开；不因此断言全部子系统健康。 |

## 本轮缺陷修复

| 问题 | 实现与回归重点 | 状态 |
| --- | --- | --- |
| launcher 启动服务后未正常启动 Desktop | 使用普通 `main` 入口，覆盖真实进程启动及 Scene/Stage 初始化顺序。 | 已实现 |
| 网站页非法 operation | 对齐网站操作名、注册契约和 ViewSchema，增加契约回归。 | 已实现 |
| 聊天滚动被重建和位置校正打断 | 保留未变化的消息节点，仅在越过可见缓冲区或布局变化时更新；上滚立即退出自动跟随。 | 32/500 条消息的真实 WebKit 对照中，24 次滚轮输入均移动 288px，与原生页面一致；滚动期间节点增加、删除、重挂载均为 0。[对照数据](functional-acceptance-2026-09-09/scroll-response.json) |
| 普通聊天也显示“打开文档”按钮 | 仅实际文档引用显示文件名超链接，普通用户/助手消息没有文档入口。 | 真实 UI 与聊天/文档回放通过。[普通消息与文档链接](functional-acceptance-2026-09-09/chat-history-document-links.png) |
| 多按钮工具栏被压缩或挤出窗口 | 动作栏按宽度换行，保留状态说明、按钮完整性和正文空间。 | 已实现 |
| 模型菜单缺少颜色令牌并产生大量 CSS 异常 | 在独立弹窗 Scene 根节点加载设计令牌，打开时继承所属窗口当前主题、字号及密度。 | 旧实现稳定复现，修复后真实弹窗跨 9 个主题重开通过，CSS 警告为 0。[旧实现](functional-acceptance-2026-09-09/chat-model-popup-before-output.txt) / [修复后](functional-acceptance-2026-09-09/chat-model-popup-fixed-output.txt) |
| 向导长名称布局、保存期间工作区可变 | 约束长模型/工作区文本，保存期间锁定目标，失败复用精确版本重试。 | 已实现 |
| 向导“仅保存”等次按钮文字被截断 | ButtonBar 按完整文字的首选宽度分配空间；保留主按钮换行及原按钮顺序。 | 旧实现稳定复现，修复后 3 项真实 JavaFX 布局回归通过 |
| 使用已有模型时目录迟到回执提前解除工作区锁定 | 刷新期间拒绝应用，应用期间冻结目标文案和选择器；失败后保留原模型精确版本供重试。 | 双延迟回执 JavaFX 回归通过，纳入最终完整构建 |
| 保存中草稿被覆盖、容量列表与详情错位 | 冻结在途写入；拒绝切换时恢复整个选择状态；丢弃同时清理容量草稿。 | 已实现 |
| 搜索误入说明命中的页面、旧协议文案 | 标题匹配优先，完整标题输入时导航并保护草稿；版本引用协议常量。 | 已实现 |
| macOS 主密钥存入后读取为空或长度无效 | `security -q -i` 通过 stdin 接收单命令，保持 ACL；写后读回比对、字节清零和无密钥回显断言。 | 12 项测试通过，含真实 Keychain 闭环 |
| 历史代次、旧流尾部和发送重复/迟到结果 | 隔离历史请求代次、补全 legacy tail、发送幂等与草稿身份保护。 | 已实现 |
| 切换对话后取消错误 Turn 的风险 | 取消操作绑定原始 Turn 和连接，切换后仍取消原任务；连接替换或关闭后拒绝迟到执行。 | 已实现，取消导航回归通过 |
| 后台任务窄窗口中列表、详情被压缩 | 内层详情取消误继承的 620px 最小宽度；任务列表保留最小宽度，长标题和详情按实际宽度换行。 | 已实现，真实 Scene 回归通过 |
| 无对话时标题仍提示前往配置 | 依据当前工作区显示名称或选择工作区提示，避免已有模型时继续引导配置。 | 已实现，2 项回归通过 |
| Knowledge / Skill 子进程无法加载 jlink 库 | 将镜像内经过真实路径校验的 lib 目录纳入只读执行根。 | 10:48 构建的全新发行镜像的 DOCX、Java、JShell 共 3 项原生业务复验全部通过 |
| Browser 子进程与 Playwright 无法启动 | 已修复独立 JVM 库、锁定驱动装配、每实例临时目录和清理。 | 严格原生登录仍失败，详见[独立限制报告](browser-native-sandbox-2026-09-09.md) |
| Knowledge 解析成功但客户端报协议错误 | POI 的初始化日志污染 stdout；主入口先独占协议输出，再把库日志导向 stderr。 | 2 项冷 JVM 回归通过；10:48 构建的全新发行镜像的原生 DOCX 提取及客户端解帧复验通过 |
| 构建清理可能删除发行目录内运行数据 | `pre-clean` 和重建前遇到 `distribution/data-v6` 即拒绝清理；发行归档同时排除运行数据。 | 3 项真实 Ant 临时夹具及最终 Packaging 验证通过 |
| Golden 测试遗漏后台线程异常、受到用户窗口偏好影响 | 测试隔离个人偏好，按实际 FX 调度等待稳定状态；后台及 FX 未捕获异常均计入失败。 | 未修改基准图片，完整 Golden 结果见最终门禁 |

## 证据与限制

系统凭据修复[测试摘要](functional-acceptance-2026-09-09/native-credential-fixed-summary.txt)；完整日志：`/tmp/javaclaw-functional-native-credential-fixed.log`，统计为 12 项、0 失败、0 错误、0 跳过。原始失败保留于 `/tmp/javaclaw-functional-native-credential.log`。Keychain 测试仅使用随机临时标识，不读取或改写已有用户条目。

自动测试覆盖状态机、typed SDK/RPC、真实 JavaFX 控件、本地假 HTTP、扩展业务及原生隔离。模拟网关和假服务不替代实际账号验证；本机 LM Studio 成功不代表公网模型、OAuth、第三方 Bundle 或其他平台可用。Linux/Windows、其他架构、系统登录启动及真实浏览器外部环境须分别报告执行与跳过情况。

IDEA 一键启动直接运行 `AppServerMain`，没有三个 `*.worker.image-root` 参数；这是[发行契约](../release.md)明确保留的开发限制。知识库文件导入和重建索引、网站快照/交互登录、Browser 承载的 MCP OAuth、已发布 Skill 的 Java/JShell 执行需要通过发行启动器装配校验后的独立镜像。已有知识索引查询、网站配置管理和 Skill 内容管理不因此停用。本轮原生 Worker 探针显式传入发行镜像，不能据此声称 IDEA 默认入口具有上述 Worker 能力。

构建数据目录取证：原 POM 在 initialize 阶段无条件删除整个 `target/distribution`，`mvn clean` 也会删除它；发行启动器在未显式指定数据根时默认使用程序目录内的 `data-v6`。这是已确认的危险组合，现已加拒绝清理护栏。由于缺少之前进程的完整参数，尚不能将未定位的验收记录归因于这次删除；已有 IDEA 数据库未被重置。

### IDEA 同线程受控重启

服务正常关闭后，仅对数据库副本使用 `IFEXISTS=TRUE;ACCESS_MODE_DATA=r` 查询 `CORE.COMMAND_RESULT` 的命令名称、数量和最早/最晚提交时间，未读取 payload、密钥或原库。[原始只读审计输出](functional-acceptance-2026-09-09/idea-command-commit-audit.txt)中时间为 UTC；换算北京时间后，10:21–10:25 只有 `provider/update` 和 `thread/create` 各 1 次，没有 `thread/execution/update`、`execution/recent/update` 或 `turn/start` 的提交证据。因此首次界面观察不能定位到一个已提交对话，不能据此判定重启丢数据。

10:54 后三类命令各提交 1 次：Thread 配置于 10:54:07.641243，recent 于 10:54:07.986027，`turn/start` 于 10:54:36.360411。SDK 受控重启前后记录均为同一工作区 `b97fbb0e-3f4a-4bee-ae4f-f96ca5b55f25`、同一 Thread、相同 Provider@2；Thread 配置与 recent 均为 revision 1，Item 数为 2，用户/助手标记均存在，预览无 blocker 且 ready。两份完整输出经 `cmp` 比较一致，详见[重启前证据](functional-acceptance-2026-09-09/idea-conversation-before-controlled-restart.txt)与[重启后证据](functional-acceptance-2026-09-09/idea-conversation-after-controlled-restart.txt)。

最终构建前后，在没有服务进程打开数据库的条件下，文件大小均为 700416 字节，SHA-256 均为 `9d386a72a39aba34199b32658b35b2995e311915ff3795dad730af25787d0d0a`。[数据库对照](functional-acceptance-2026-09-09/idea-database-build-preservation.json)证明这轮 clean、verify 和续跑没有改写 IDEA 数据库。11:29:33 重建的发行程序再次读取同一 Thread，完整 SDK 输出与受控重启前逐字节一致；CUA 复看确认用户和助手消息、模型选择都已恢复。早期缺少提交证据时的失败输出另存为[首次已提交对话之前的检查](functional-acceptance-2026-09-09/idea-conversation-before-verified-submit.txt)，没有把它改写成成功记录。

### Knowledge / Skill 原生业务补验

首次使用生产 `KnowledgeWorkerRuntimeFactory.command` 与 `KnowledgeWorkerClient.extract` 解析本地 DOCX 时，客户端收到 `RPC frame length is outside the configured limit`。同一原生 Sandbox 命令的诊断输出证明：POI 首次加载产生的 `Log4j API could not find a logging provider` 文本位于 stdout 的长度前缀之前，而后面的业务帧已经包含正确文档正文。失败记录为 `/tmp/javaclaw-worker-business-ni72e949/probe.log`，因此仅检查 `java -version` 不能证明业务协议可用。

修复在 `KnowledgeWorkerMain.main` 中先保存原 stdout 作为协议专用输出，再将 `System.out` 指向 stderr，之后才初始化 JSON 和解析器；入口不存在提前加载解析器的静态字段或静态初始化块。显式输入/输出流的 `run` 契约保持不变，生产启动器继续丢弃 stderr。有效 DOCX 和损坏 DOCX 分别在全新子 JVM 中执行，均断言 stdout 恰好只有一帧；损坏文档仍返回 `KNOWLEDGE_EXTRACTION_FAILED`。[冷 JVM 回归日志](functional-acceptance-2026-09-09/knowledge-stdio-cold-jvm-output.txt)

首次修复验证中，Knowledge 使用 **修复覆盖镜像（overlay image）**：克隆当时的打包镜像到 `/tmp`，仅替换新编译的 `KnowledgeWorkerMain.class`，未改原发行目录；Skill 使用当时的实际发行镜像。该历史记录仍保留在 [overlay 业务结果 JSON](functional-acceptance-2026-09-09/worker-business-overlay-results.json) 和 [overlay 运行日志](functional-acceptance-2026-09-09/worker-business-overlay-output.txt)，其路径未改写为最终发行路径。

2026-09-09 10:48:08 完成第一轮通过的 `clean verify` 后，再次直接使用全新 `javaclaw-packaging/target/distribution` 运行相同探针：**3 项运行、3 项通过、0 失败、0 跳过**。本次 Knowledge 和 Skill 均使用该目录的实际打包镜像，没有 overlay、类替换或镜像修改。下表记录这次 fresh 复验；全部调用生产 Factory / Layout / Client 与 macOS 原生 Sandbox，使用独立临时数据目录，未调用模型、网络或外部工具。

| 检查 | 业务断言 | 实际结果与边界 |
| --- | --- | --- |
| Knowledge DOCX | 提取临时生成的单段 DOCX；返回输入 SHA-256、正文和 POI 解析器指纹。 | fresh 镜像通过：正文 `JavaClaw local document 42`，指纹 `knowledge-worker-5.0.0/pdfbox-3.0.4/poi-5.4.1`；不包含知识库完整导入、索引和检索。 |
| Skill Java | 生产 Layout 创建 Java source 命令，执行 `6 * 7`。 | 通过：退出 0，输出 `JAVACLAW_SKILL_JAVA=42`；不包含用户技能发布和授权界面流程。 |
| Skill JShell | 生产 Layout 创建本地 JShell 命令，执行 `6 * 7`。 | 通过：退出 0，输出 `JAVACLAW_SKILL_JSHELL=42`；不包含网络或第三方代码兼容性。 |

[fresh 业务结果 JSON](functional-acceptance-2026-09-09/worker-business-fresh-results.json) 和 [fresh 运行日志](functional-acceptance-2026-09-09/worker-business-fresh-output.txt) 逐字节保存本次输出，包含实际发行 argv、lib 执行根、文档 digest 和业务结果。临时证据目录为 `/tmp/javaclaw-worker-business-ehint07x`，运行入口为 `/tmp/javaclaw-worker-business-probe/run.py`；传入的唯一发行根为仓库内 `javaclaw-packaging/target/distribution`。这些结果证明本机打包 Worker 的业务执行链，不代表其他平台、用户技能发布或知识库全链路验收。

11:29:33 完成最后的 Desktop / Packaging 验证并重新装配发行镜像后，原生业务探针再次通过：**3 项运行、3 项通过、0 失败、0 跳过**。这轮也没有 overlay、替换类或改写镜像；实际临时目录为 `/tmp/javaclaw-worker-business-suf0ktcv`，所有子进程已退出。[最终镜像业务结果](functional-acceptance-2026-09-09/worker-business-final-results.json)和[原始输出](functional-acceptance-2026-09-09/worker-business-final-output.txt)与前两轮证据分别保留。

## 最终验证结果

构建身份：`dev_v0.3.3` / `1d3c123b` 的未提交工作树，JavaClaw `6.0.0-SNAPSHOT`，JDK 25、Maven 3.9.0、JavaFX 26.0.2；执行平台为 macOS 26.5.2 arm64。未提交工作树，未修改 Golden 基准图片或 `chat-surface.css`。

| 检查 | 结果 |
| --- | --- |
| Spotless apply / check、Checkstyle | 全仓库检查通过；最后仅修改的弹窗测试夹具又单独通过格式和静态检查。 |
| 最终各模块测试报告 | 2841 项：2818 通过、0 失败、0 错误、23 跳过；由第二轮完整构建的服务端模块及修正后 Desktop / Packaging 续跑组成，具体构建过程见下文。 |
| JavaFX | 556 项 Desktop 单元测试通过；另 1 项 Golden 集成测试完成 54 张图的比较，无未捕获 FX / 后台异常。 |
| 实际本机模型 | `LocalProviderAcceptanceTest` 通过，耗时 37.175 秒；包含真实聊天、模型验证、Embedding、记忆与 Skill 审核链。 |
| 原生 Worker | 最终发行镜像的 DOCX 提取、Java 和 JShell 共 3 项业务复验通过；只使用本地临时内容。 |
| SDK / JavaFX 回放 | SDK 回放通过，生成 26 张图；聊天/文档回放 9 个场景通过，生成 22 张图。 |
| IDEA 数据与重启 | 构建前后数据库 SHA-256 一致；最终重建程序读取的同线程配置、recent、用户/助手消息和 ready 状态与受控重启前一致。 |
| 最终真实界面 | 聊天历史、模型选择、服务名搜索、设置精确导航均通过；新增日志没有 CSS 解析或未捕获 Java 异常。 |
| 浏览器严格原生登录 / OAuth | **未通过**。独立正式 smoke 返回 `BROWSER_REQUEST_FAILED`，不能发布对应能力验收成功。 |

第一轮完整 `mvn clean verify` 于 10:48:08 通过全部 15 个 Reactor 项目。随后真实界面暴露模型菜单 CSS 令牌缺失，补充修复和回归后执行第二轮完整构建：所有 Desktop 之前的模块通过，但新弹窗测试的静态外观设置受前序全局监听影响，出现 1 项夹具断言失败。仅将夹具改为生产使用的外观预览流程，保留主题、字号、颜色、边界和零 CSS 警告断言；[有全局管理器时通过](functional-acceptance-2026-09-09/popup-global-manager-fixture-fixed.txt)，[换回原生产实现仍失败](functional-acceptance-2026-09-09/popup-original-production-regression.txt)。

为续跑安装已经验证的当前上游产物后，执行 `mvn verify -rf :javaclaw-desktop`，保留相同验收参数。Desktop 和 Packaging 于 11:29:33 全部通过，耗时 1 分 55 秒；没有再次重复执行已通过的服务端测试。这里没有将第二轮报告为一次不间断的成功 `clean verify`。[各次构建身份、摘要和结果](functional-acceptance-2026-09-09/build-runs.json)

完整构建采用以下参数；模型请求只访问本机 LM Studio，续跑使用相同参数。

```bash
mvn clean verify \
  -Djavaclaw.require.native.credential=true \
  -Djavaclaw.playwright.browsers.path=/private/tmp/javaclaw-functional-playwright \
  -Djavaclaw.live.local=true \
  -Djavaclaw.live.base-uri=http://127.0.0.1:1234/v1 \
  -Djavaclaw.live.chat-model=qwen3.5-9b-uncensored-hauhaucs-aggressive \
  -Djavaclaw.live.embedding-model=text-embedding-qwen3-embedding-0.6b \
  -Djavaclaw.live.embedding-dimensions=1024
```

跳过的 23 项分别是 17 项其他操作系统用例、5 项未提供真实发行工具链/公网依赖输入的可选验收，以及 1 项默认不开启的 Packaging 浏览器原生 smoke。最后一项已单独按严格模式执行并失败，不能把常规门禁通过当成浏览器发行能力通过。[逐模块统计与跳过原因](functional-acceptance-2026-09-09/test-results.json)

此前还执行了 `mvn -pl javaclaw-browser-service -am verify -Djavaclaw.require.native.sandbox=true -Djavaclaw.require.native.credential=true`：665 项中 649 通过、16 项其他平台跳过，0 失败/错误；该范围不包含 Packaging 的 Chromium 登录测试。[严格 Worker 模块结果](functional-acceptance-2026-09-09/worker-native-results.json)及[浏览器失败证据与未合入实验](browser-native-sandbox-2026-09-09.md)

GUI 回放入口为 `scripts/replay-sdk-ui-acceptance.py` 和 `scripts/replay-chat-document-acceptance.py`。代表性画面：[聊天回复](functional-acceptance-2026-09-09/sdk-chat-completed.png)、[普通消息与文档链接](functional-acceptance-2026-09-09/chat-history-document-links.png)、[窄窗口后台任务](functional-acceptance-2026-09-09/sdk-jobs-dark-minimum.png)、[窄文档工具栏](functional-acceptance-2026-09-09/document-narrow-toolbar.png)。[滚动对照数据](functional-acceptance-2026-09-09/scroll-response.json)和[聊天/文档场景索引](functional-acceptance-2026-09-09/chat-document-replay-index.json)保留实际测量边界，不构成其他机型的 FPS 或延迟承诺。

最终 CUA 复看同时验证了“定时任务／记忆／技能”精确搜索进入正确页面，设置工作区一直保留“功能验收 2026-09-09”；聊天模型菜单可以按 `LM Studio` 服务名称搜索并返回原对话。[最终真实界面检查记录](functional-acceptance-2026-09-09/final-gui-review.json)及[该次启动新增日志](functional-acceptance-2026-09-09/final-gui-appended-log.txt)。长 Turn 的早期真实停止操作没有保留终态证据；自动 `DesktopCancellationNavigationTest` 的 3 项用例通过，覆盖取消原 Turn 的 `CANCELLED` 回执、切换对话不误取消、连接关闭后拒绝迟到写入。

仍未解决的是 macOS 严格浏览器登录/OAuth，以及已有业务 Vault 的解锁。没有清空旧密钥库，没有扩大 Chromium 的全局 Mach 权限。Linux/Windows、其他架构、系统登录启动、外部 MCP/OAuth 账号、第三方 Bundle 及真实发行工具链尚未完成对应环境验收。IDEA 与发行 Worker 的能力差异按上文及发行契约保留，不能宣称全部功能已通过。
