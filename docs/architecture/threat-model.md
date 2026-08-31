# JavaClaw 4.0 安全威胁模型

状态：实现基线 v1，日期：2026-08-27。

## 1. 保护资产与攻击者

保护工作区、Git 元数据、H2 transcript/附件、模型和插件凭据、App Server 完整性、其他 Thread
隔离与本机可用性。不可信输入包括模型输出、恶意仓库、Plugin/MCP/Hook、异常客户端和本机其他
用户进程。4.0 不把“已取得当前用户任意代码执行并可调试 App Server”的攻击者作为多租户边界。

## 2. 安全不变量

1. Agent 控制进程只从 Sandbox Supervisor → 独立 Launcher 启动。
2. 最终权限是 Tool、Profile、Plugin 与 Server ceiling 的交集；审批不能移除 permanent
   protected roots。
3. `.git`、`.javaclaw`、数据根和凭据目录默认不可写。
4. 第三方字节码永不加载进 App Server JVM。
5. 自研原生代码只使用 JDK 25 FFM；原始 FFM 仅在 `javaclaw-native-hosts` 的未导出包，
   调用者仍只有独立 Launcher 与 Windows Transport Host 入口。
6. ALLOWLIST 不给普通子进程原始网络；HTTP 通过 Network Broker。
7. 投影、事件与 Outbox 同事务；Thread sequence 唯一；delta 不进入持久 sequence。
8. 慢客户端不能阻塞 Turn，也不能形成无界队列。
9. 凭据不进入工具环境、事件、日志或诊断包。
10. 平台不能精确实施策略时隐藏 capability 并失败关闭。

## 3. 控制矩阵

| 威胁 | 控制 | 自动验证 |
|---|---|---|
| 路径/符号链接/reparse 逃逸 | 规范路径、唯一 Workspace 根、Seatbelt/bwrap/AppContainer、Windows root reparse 拒绝 | SandboxPaths、平台后端和原生矩阵 |
| protected root 覆盖 | Seatbelt deny 后置；bwrap ro-bind 后置；Windows 显式 deny ACE | 后端结构与攻击用例 |
| Windows ACL 污染 | 每次执行独立 AppContainer SID；保存/恢复 DACL；恢复失败永久锁 Workspace | WorkspaceLockingSandboxExecutorTest + Windows 原生测试 |
| 子进程逃逸 | POSIX 独立进程组；bwrap die-with-parent；Windows Job kill-on-close/配额 | 超时、取消、子进程树测试 |
| 伪终端逃逸/控制注入 | POSIX slave 精确授权并设控制终端；Linux 同时安装 seccomp；Windows ConPTY 控制帧 nonce 校验且目标仍在 AppContainer Job | 三平台 native PTY、resize、signal、取消测试 |
| 网络绕过/SSRF | sandbox 无原始网络；Broker 每次 DNS/连接/重定向复核 host/port/IP，拒绝私网/链路本地 | HttpNetworkBrokerTest |
| Plugin 注入 | Manifest 无 Class 入口；进程外 SandboxSession；Schema/输出/超时上限 | ArchitectureBoundaryTest、PluginRuntimeTest |
| 重启风暴 | 1/2/5/10/30/60 秒退避；10 分钟 5 次进入 QUARANTINED | PluginService/Runtime tests |
| 协议重放 | 幂等键+请求 hash；expectedRevision；sequence cursor | H2 与 App Server tests |
| 慢客户端 | 1024 条/8 MiB 有界队列；先丢 delta、一次 resync、必要时断开 | BoundedJsonlSender/RuntimeEventBus tests |
| 凭据泄漏 | 数据库外 256-bit 主密钥、AES-GCM、owner-only 权限、Secret metadata-only DTO | H2SecretStore/diagnostics tests |
| 压缩包攻击 | 数量/大小/比例/路径/符号链接限制，staging 后原子移动 | PluginBundleInstallerTest |
| 思维链泄漏 | 只持久 reasoning summary | Domain/Schema 审查 |

## 4. 平台边界

- macOS：Seatbelt profile 内联传递，系统只读路径与 Workspace roots 明确列举；strict native test
  已在本机通过。
- Linux：bubblewrap 使用 namespace、capability drop、无网络 namespace 与 protected ro-bind；目标
  exec 前安装 no-new-privileges 和架构校验 seccomp BPF；bwrap/seccomp 任一不可用时 capability 不可用。
- Windows：独立 Transport Host 通过当前 SID DACL、remote-client reject 和客户端 SID 复核保护
  Named Pipe。Sandbox 使用一次性 AppContainer、Restricted Token、Low IL、临时 ACL、
  继承句柄白名单和 Job Object；目标挂起后先入 Job 再 resume。ConPTY attribute 与 AppContainer
  security capabilities 在同一次受限进程创建中提交，输入/resize/signal 走独立有界控制桥。
- HOST_FULL_ACCESS 在 Windows 无法同时保证 permanent protected roots，因此当前拒绝，不会裸执行。
- macOS/Linux PTY 由 FFM 管理真实 master/slave 和前台进程组；Windows 使用 ConPTY。pipe session
  不接受 resize，Windows pipe session 也不伪造 console interrupt。

## 5. 剩余风险

- Windows/Linux 新增原生路径尚未在本机操作系统执行，必须以原生 CI 结果作为发布证据。
- Linux Landlock 仍未作为回退启用；只有 bwrap+seccomp 完整路径可用，缺失任一组件时失败关闭。
- 同一 OS 用户已经拥有 App Server 调试/注入能力时，UDS/Named Pipe 不能提供多租户隔离。
- 文件系统 TOCTOU 仍依赖 OS 沙箱作为最终边界，应用层路径检查本身不被视为安全边界。
- 500 慢订阅者与 p50/p95 门禁已有自动测试；不同原生 Runner 的长期资源曲线仍需随发行记录审查。
