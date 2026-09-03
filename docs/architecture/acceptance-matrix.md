# JavaClaw 5 验收矩阵

`本机已验证` 只表示当前 macOS aarch64 工作区已有可重放的自动化或原生证据。2026-09-02 的最新工作树已通过
完整 `mvn clean verify`，15 个 Reactor 模块全部成功；这不代表跨平台发行门禁已经通过。CI、原生、签名和公证项
必须由对应目标 Runner 产生证据，不能由跨平台契约测试或静态 mock 替代。

| 领域 | 验收证据 | 当前证据 | 发布判定 |
|---|---|---|---|
| Protocol v2 | 147 个方法的严格 params/result Schema、JSON-RPC、initialize、协商、framing、错误映射 | 本机聚焦测试已验证 | 必须通过 |
| 排除边界 | 无旧协议/数据兼容、Rust/Codex 后端、Codex Plugin、Ollama、本地模型、邮件/Webhook、宿主输入控制、Raw Cookie 导出或 `HOST_FULL_ACCESS` | 模块/历史标识门禁及生产源码扫描已验证 | 必须保持 |
| Core 数据 | 空目录 baseline、事务、幂等、revision、Item/Event/Outbox | 本机聚焦测试已验证 | 必须通过 |
| Rollout | 一致性快照、sequence、哈希链和 manifest 校验 | 本机聚焦测试已验证 | 必须通过 |
| Turn Harness | stream、tool loop、预算、取消、背压、持久化 intent/checkpoint、EffectReceipt 与 `UNKNOWN_OUTCOME` | Agent Runtime、App Server 与本机完整 Reactor 门禁已通过 | 本机通过 |
| Provider/Profile | 四类 Adapter、逐模型用途、精确 Embedding 绑定、有界目录发现、不可变普通配置、H2 内原子凭据提交及提交后 fail-closed registry 交换、版本化 Profile/Permission 预设、显式计费验证、热替换、Prompt Draft/采纳 | 本机假服务与聚焦测试已验证；Provider+Vault+Profile+Turn 的同库重启链，以及生产 Embedding→Knowledge HYBRID→Agent 工具检索链均已通过 | 必须通过 |
| OpenAI Responses | reasoning summary、opaque state、native compaction | 本机契约测试已验证 | 必须通过 |
| Permission/Tool | 冻结目录、搜索展开、revision、撤权、审批、有效权限 trace | 本机聚焦测试已验证 | 必须通过 |
| Secret Vault | SealedSecret、AES-GCM AAD、轮换、锁定、reset、明文扫描 | JVM/本机聚焦测试存在；Linux/Windows 系统凭据设施待 Runner | 阻断 |
| macOS Native | Seatbelt、PTY、资源、Git Worktree 生命周期 | 本机原生测试已验证 | 必须通过 |
| Linux Native | bubblewrap、PTY、资源、安装启动，x64/arm64 | 待目标 Runner | 阻断 |
| Windows Native | Named Pipe、AppContainer、Restricted Token、Job Object、ConPTY、ACL 恢复、安装启动 | 实现与跨平台契约测试存在，待 Windows x64 Runner | 阻断 |
| MCP Host | 固定 `2026-07-28`、OAuth 2.1/PKCE、Catalog、Broker、实时复核、SDK、管理页 | 完整纵切与分模块聚焦测试存在；五 Runner 真实 OAuth/Chromium 回执待 | 阻断 |
| Automation extensions | Plan/Loop/Workflow/SDD/Schedule Definition/Execution、Outbox、checkpoint、恢复与领域不变量 | 五领域管理纵切已实现，Builtin 与本机完整 Reactor 门禁已通过 | 本机通过 |
| Memory/Knowledge/Skill | 历史、提案、Generation、Draft/Published、冻结目录与管理 | 三领域纵切已实现，Builtin、Knowledge Worker 与本机完整 Reactor 门禁已通过 | 本机通过 |
| Site/Browser | Broker-only 网络、Vault 会话、登录、遮罩、无 Cookie 泄漏、Site Secret 创建/轮换/永久清除且不可读回 | 纵切、假 Worker 与泄漏扫描存在；五 Runner 真实 Chromium/Sandbox 证据待 | 阻断 |
| Managed Worktree | 父子 Thread、隔离根、Patch、备份、零部分写 apply | 管理与运行纵切、聚焦测试及 App Server/完整 Reactor 门禁已通过 | 本机通过 |
| Third-party Bundle | digest staging、签名、信任、升级、监督、配额、隔离、恢复、Trash | 完整管理纵切、聚焦测试及 App Server/完整 Reactor 门禁已通过 | 本机通过 |
| Extension notification | 无正文 `extension/event`、持续接收、合并失效、权威刷新、dirty 草稿保护 | SDK、Desktop 与本机完整 Reactor 门禁已通过 | 本机通过 |
| Desktop | SDK-only、不可变状态、受限 renderer、重连、29 个管理入口、交互状态 | 54 张 macOS Scene 只锁定设置中心壳/外观页在九主题、三密度、100% 字号和两窗口下的表现，导航目录另行断言 29 个入口；29 页正文、多状态、其余字号及 Linux/Windows 视觉证据待 | 阻断至目标 Runner 通过 |
| 后台生命周期 | lease、60 秒退出、Schedule 登录启动、托盘与脱敏诊断 | 诊断已聚合 Extension 隔离和 launcher/tray 真实状态；三平台真实交互待 Runner | 阻断 |
| 代码质量 | Spotless、Checkstyle、依赖分析、包循环与架构测试 | 生产包循环已清除并加入门禁；本机完整 `mvn clean verify` 已通过 | 本机通过 |
| 覆盖率 | Core 90/80，外围 80/70 | 全模块覆盖率门禁已通过；App Server 为 90.63%/80.00%，Browser 为 81.62%/71.45%，Packaging 为 82.58%/76.67%，Desktop 为 88.41%/73.10% | 本机通过 |
| 供应链 | 隔离 Worker image、固定 Chromium、SBOM、许可、双层哈希、签名、公证与 attestation | macOS jlink 发行目录已通过隔离 data-v5 的 Protocol v2 健康检查；本机 SBOM、许可与哈希证据存在，五 Runner、真实 Browser capability receipt、签名与公证待 | 阻断 |

## 发布规则

任何标记为阻断的行都不能通过 waiver、跳过测试、降低覆盖率或静态 mock 改为通过。修复后必须附上可重放命令、
Runner/架构信息、产物哈希和失败日志位置。本机全仓门禁不能代替尚缺的目标平台、真实 Browser、签名或公证证据。
