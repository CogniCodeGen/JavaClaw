# JavaClaw 4.0 实施与发布状态

更新时间：2026-08-29。代码与本机测试版已补齐本轮能力恢复链路；正式 Release 尚未批准。
现状与证据以 [能力矩阵](upgrade-acceptance.md) 和 [验证报告](upgrade-verification.md) 为准，
不再沿用此前“已有 CRUD，但领域状态机/页面待实现”的旧状态。

| 阶段 | 已落地内容 | 验证边界 |
|---|---|---|
| P0 基线 | 固定 509f197 UI、Codex Prompt commit、MCP Schema、协议与历史 migration 哈希 | 旧源码只作对照，不读取 3.x 数据 |
| P1 UI 外壳 | 原翡翠主题/九主题、侧栏、输入、消息、导航与 SDK 连接状态 | 当前 macOS 真实窗口；非全屏幕尺寸像素等价 |
| P2 Kernel / Prompt | 同一 ModelInvocationService、TurnScope、共享预算、资料分层、类型化输出与快照 | 三家真实 SDK + 本地 Responses/SSE 假服务；无真实效果 A/B |
| P3 执行能力 | 文件/补丁/JShell/文档 Worker、图片/OCR、PTY、Broker 和能力快照 | macOS 原生通过；Linux/Windows 原生结果另需 Runner |
| P4 自动化 | Plan 采用、Loop、Workflow 八节点、SDD、Schedule、Evaluation、Checkpoint、EffectReceipt | 假模型/假工具 + 真实 H2 行为验收 |
| P5 知识 | Memory/Persona/版本/确认、Knowledge generation、Skill Bundle/脚本/学习与维护 Turn | 来源与权限/版本门禁；真实检索质量另评估 |
| P6 外部与协作 | Browser 交互/登录/加密会话、准确端点/有限通信授权、父子预算与工作树恢复 | 当前原生浏览器与真实 Git；不替用户登录或发送消息 |
| P7 客户端 | 八 SDK 领域客户端、CLI 命令、原风格领域页面、Markdown/附件、Prompt/规则/工作树管理 | 真实产品窗口与 SDK transcript |
| P8 清理与发行 | 九模块、单调用链、FFM/进程白名单、格式/注释、jlink/包/SBOM/哈希/签名门禁 | 本机未签名测试版；跨平台正式签名发布未执行 |

## 存储

现有 V001/V002 保持不变；新增迁移不覆盖历史校验和：

- V003：执行定义、步骤、预算、检查点、执行凭据和 SDD 状态。
- V004：Memory/Persona/关系、版本和 Knowledge index generation。
- V005：Skill 版本、资源与学习提案。
- V006：站点、加密会话、私网端点及有限工具授权。
- V007：历史上曾加入项目规则表，现仅保留发行模板档案与模型调用快照。
- V008：低优先级知识维护 inbox 与学习设置。
- V009：在自动备份后删除旧项目规则及修订表，新增按 Thread/覆盖序号持久化的 ConversationWindow。

工作树恢复复用 V002 和已有幂等记录，不修改表结构；意图/备份先提交，Git 操作不在数据库事务中。
状态、Item、Event、Outbox 与执行检查点由原事务链提交；外部结果未知不自动重试发送。

已存在的 v4 文件库有待应用 migration 时，先全量验证历史 checksum/顺序，再在数据根
`migration-backups/` 写 H2 一致性 ZIP 及 SHA-256。目录 0700、文件 0600（Windows 为所有者独占 ACL）。
备份失败立即拒绝升级；没有新 migration 的重启不重复备份。H2 DDL 可能隐式提交，不能声称整体事务可回滚。
备份与部分失败文件都不自动删除，也不自动覆盖恢复到用户数据库。

ZIP 只包含 H2 数据库，不包含外部附件 blob、工作树文件或平台配置目录中的凭据主密钥；这些原目录必须保留。
需要恢复时先停止 App Server、保留原数据根，在独立恢复目录核验备份再决定切换，不能直接覆盖运行中的数据库。
新建空根不制造无意义备份；发现 3.x 或未标记非空根时只检查并拒绝，不修改其内容和权限。

## 调用链与边界

`Desktop/CLI → SDK → Transport → Session/Router → 8 组 Handler → UseCase → Adapter`。
Browser、MCP、Hook、JShell、解析 Worker 不进入 App Server JVM。
Kernel 不依赖 Spring/JavaFX/JDBC/H2/MCP，SDK 公共签名不泄漏协议，UI 不接触数据库。
第三方模型日志不能占用 stdio 协议输出；进程关闭先停止重连、终止持有的服务，再释放读端。

## 尚需外部环境的发布与联调条件

1. 在 Linux arm64/x64、Windows x64、macOS x64 原生环境实际运行同一门禁；不以 macOS arm64 成功代替。
2. 取得 Apple/Windows/Linux 正式签名凭据并完成所有平台同版本汇总；当前不创建 Release/tag。
3. 获得用户授权后进行真实 Provider/MCP 和有费用上限的提示词质量对照。
4. 固定规格长稳、3.x 合成基线性能对照与锁定浏览器版本安全审阅。

Ollama、Deliverance、桌面 Agent 控制、内置邮件、3.x 迁移和远程入口仍按已确认决定排除。
