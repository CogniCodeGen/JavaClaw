# 编程网络与原生隔离增量验证

本记录只说明当前工作树的实际证据。测试不调用付费模型；安全与失败边界使用本地夹具。后续真实官方小流下载与
Maven 完整安装 Job 的证据另见[托管工具链生产下载与安装验证](coding-toolchain-installation-validation.md)，不以
访问公共仓库成功替代 SSRF、撤权或 OS 隔离验证。macOS 上的条件跳过不计作 Linux/Windows 通过。

## 已有证据

- `ArtifactHttpReaderTest` 3 项、`PinnedArtifactDownloaderTest` 4 项正式 Maven 回归通过：严格 HTTP framing、固定 DNS、
  安全 HTTPS/443 重定向逐跳重新固定地址、不安全重定向拒绝、大小上限、取消后关闭待决流并禁止迟到写入。
  不安全跳转包括 HTTP 降级、URI userinfo 凭据、非 443 与私网 DNS；不能将这些拒绝断言概括为禁止全部 302。
  生产下载器明确支持最多 5 次 301/302/303/307/308，正向测试包含跨主机 302 后成功读取正文。
- `WorkspaceFileAccessIntegrationTest` 在实际 macOS Seatbelt 内验证固定 Java Worker 的读取、准备/应用/恢复补丁、
  调用级较窄读权限以及缺失父目录的“不存在”快照。
- `MacProxyIsolationTest` 在实际 Seatbelt 中验证精确 IPv4 代理端口可用，其他 IPv4 端口、同端口 IPv6 及 OFFLINE 网络
  不可用。它不证明其他平台的内核边界。
- `LinuxProxyBridgeTest` 使用本机 Unix Socket 验证双向流、half-close 与清理；`LinuxSocketPolicyTest` 执行 x64/arm64
  BPF 决策矩阵。真实 `LinuxProxyIsolationTest` 仅在 Linux Runner 执行，不以缺少 backend 为条件跳过。
- Windows 租约/协议/清理测试包含 REVOKE → Broker callback → CLOSE 顺序、重复与并发 close 等待终态、错误传播。
  这些是平台无关自动测试，不能代替 Windows 上的 FFM 与内核执行证据。
- CI 的 Windows 固定步骤解析打包 PowerShell，在 RUNNER_TEMP 用 MSVC 编译 guard，再在固定 Program Files 测试目录
  安装真实服务。临时本机代码签名证书签署 guard/Java，受保护 ACL 与固定路径保持生产校验；Maven 使用该固定 JDK。
  always 清理先卸载服务，再删除测试信任和私钥并恢复 JDK，安排在发行打包之前。没有生产签名绕过。
  当前 macOS 环境没有 PowerShell/MSVC，尚未取得该步骤的运行回执。

## 本轮回归

- CONNECT 的 `CommandProxyBrokerTest` 9 项、`CommandProxyLifecycleTest` 7 项、`ConnectRequestReaderTest` 6 项全部通过。
  覆盖精确主机/端口、公私混合 DNS 与重绑定、原始 Socket 流/half-close、双向字节预算、并发和待决请求限额、
  取消/撤权/绝对与单调期限、空闲超时、迟到 Socket 注册、严格 framing 与请求头上限。
- 目录句柄加固前的 Native Hosts 全量：148 项，141 通过、7 平台条件跳过，0 失败/错误。包括真实 macOS 写根不可删除但子文件可删除、
  精确代理端口隔离、文件 Worker 分页/inventory，以及已有描述符上的 F_GETFL/F_SETFL 和 FileChannel.lock/tryLock。
  目录读取另需 F_GETFD/F_SETFD；只开放这些具体命令及刷盘，不开放任意 fcntl。结果不代表 Windows/Linux 内核验证。
- `ManagedCommandDirectoriesTest` 3 项与 `CodingDataBoundaryTest` 3 项通过：独立临时根、缓存子目录污染、管理
  祖先/最终根 symlink、项目/管理目录包含与别名拒绝、权威工作树标志仅允许自身根。真实 ManagedWorktree 集成另行回归。
- `ProjectToolchainNativeTest` 已在 App Server 的实际依赖环境中通过。文件 Worker 仅加载固定 Native Hosts/API，避免
  大量宿主 JAR 在受限描述符预算下导致入口加载失败；只授予实际调用的文件权限和资源上限。
- 上述联合功能回归因并发开发临时跳过全仓风格生命周期步骤；受影响文件另外执行定向 Spotless apply/check 和
  Checkstyle，无违规。主任务仍须执行最终全仓格式、静态检查与 verify，不能把此次功能测试标为完整发布门禁。

## 最新 Native Hosts 正式 verify

2026-09-07 19:40:59（北京时间）在 macOS arm64 完成 Native Hosts 及其上游模块的正式 Maven `verify`：
**198 项，181 执行通过、17 项条件跳过，0 失败、0 错误**。跳过项为 16 项异平台检查及 1 项未启用的系统凭据检查。
依赖声明检查和 JaCoCo 覆盖率门禁均通过，回执为
[原生批处理输出 verify 日志](/tmp/javaclaw-native-stream-verify.log)，SHA-256：
`b94f06a3c1b122eadedf4ebda48b9a3b31cea7fac1046ecf35e7f28cfc6295ea`。
这不是全仓最终发布门禁回执，17 项条件跳过不能记作 Windows/Linux 原生验证。
随后完整 `clean verify` 在 20:32:40 通过，Native 仍为上述 198/181/17；完整各模块与跳过清单见
[最终构建记录](unified-coding-build-validation.md)。本机门禁通过不补足未运行的原生和安装验收。

本次包含 `SandboxBatchObservationTest` 5 项真实 POSIX 沙箱测试与 `SandboxOutputObservationTest` 5 项契约测试：
批处理在执行中观察共享预算内的 stdout/stderr，旧 API 不增加 PTY 权限，回调异常触发本执行受控取消并保留清理异常。
输出任务不调用 `cancel(true)` 或 `shutdownNow`；进程及自有管道关闭后有界等待观察者退出。超过排空和清理期限时，
明确报告仍有观察者活跃，不能宣称清理成功。真实共享 `FileChannel` 测试验证该超时及执行线程外部中断不会关闭
观察者使用的共享通道，迟后释放观察者仍可写入。输入任务只拥有该进程 stdin，沿用其独立取消策略。

以下按时间记录的 148、180 项等回归仍保留为历史证据，不将多次运行计数相加，也不以它们代替本次 198 项结果。

## 目录句柄与补丁并发加固

后续检查确认，先 `toRealPath` 再用普通 Path 打开不能抵抗祖先目录替换；固定 Worker 同时可读 Java 运行库，
因此不能把 OS 的运行库读取许可当作项目路径边界。当前实现使用 POSIX `openat` 的逐段 `O_NOFOLLOW` 目录能力，
最终列举和文件操作使用 `SecureDirectoryStream`。祖先目录仅请求搜索权限；固定根不因再次解析链接而重授权。
本 Turn 的较窄文件权限根也必须维持冻结的规范路径；调用时发现它成为链接或路径别名即拒绝，不能把新 `realPath`
指向的 Workspace 内兄弟目录视为新授权。已选择的路径别名须在创建/冻结阶段显式规范化。
Windows 使用真实卷路径和 `NtCreateFile` 的 `RootDirectory` / `OBJ_DONT_REPARSE`，不以 Java Path 操作作为回退。
Windows 授权和恢复使用同一文件句柄，而非再次按名字修改 ACL。Windows 的原生执行仍须对应 Runner 验证。

POSIX 修改使用 `renameatx_np(RENAME_SWAP)` / `renameat2(RENAME_EXCHANGE)` 原子交换：目标持续存在，实际旧 inode
同时进入恢复槽；创建使用不可覆盖移动，删除移入恢复槽。Windows 当前仍使用先保存旧 inode、再不可覆盖安装提案
的两阶段移动，目标可能短暂不存在。这是相对最初原子替换目标的明确平台差异，不能计作 Windows 原子替换已通过。
原语缺失或跨文件系统移动均失败，不退回“检查目标后普通 rename”。多文件操作、文件系统与数据库不是一个原子
事务，也不把摘要检查描述为原子 CAS。文件和日志刷盘不构成对所有文件系统断电语义的保证。

成功提交也保留原 inode：外部编辑器可能持有旧 fd 并在最后一次摘要校验后继续写入。交换后发现实际旧摘要不符时，
立即停止并报告需要恢复，不自动反复交换当前文件。回退前已发现当前摘要变化则保留当前路径；若变化发生在最后
预检与交换之间，实际当前 inode 仍保存在恢复槽，立即停止并报告需要恢复。后者不能保证用户编辑始终留在当前路径，
但不会通过删除捕获的版本来掩盖冲突。
恢复目录记录路径映射和顺序事件，位置进入结果及执行证据。普通文件工具禁止直接读取或修改该保留命名空间，
列举/搜索跳过它，inventory 披露排除范围。拥有整个 Workspace 写权限的命令仍可能删除恢复目录；原始准备快照
另在内部事实存储保留，不能声称恢复目录对已授权的任意命令具有不可变性。

该轮正式联合 Maven 的 Native 28 项全部通过，包括 `WorkspaceDirectoryBoundaryTest` 5 项、inventory 4 项与
真实文件 Worker 2 项。5 项边界测试覆盖父目录被外部链接替换、不覆盖已存在目标、成功后旧 fd 晚写可找回、回退
保留并发编辑和目录条目预算；另在本机真实 Seatbelt 中执行读取、准备、提交、旧 fd 晚写及恢复目录隐藏验证成功。
随后新增 `WorkspaceAtomicPatchTest` 3 项与 `WorkspaceFileWorkerContractTest` 6 项，连同边界 5 项在独立目录编译并
通过全部 14 项实际断言。原子交换测试覆盖持续读取无缺失、交换时实际旧版本冲突与回退最后检查后的竞态；Worker
测试把真实二进制帧送入与 main 共用的处理入口，覆盖全部文件操作、恢复路径、恶意帧和单项合法但累计超限的快照。
真实 Seatbelt Worker 的原子交换与旧 fd 晚写保留也已通过，正式全量覆盖率门禁仍由主任务执行。
这些目录修改没有新增 Seatbelt 读取或网络许可；Windows NT 句柄调用与 Linux `renameat2` 仍需真实平台门禁。

后续正式 Native 全量记录为 180 项：163 执行通过、17 平台条件跳过，0 失败/错误，日志为
`/tmp/workspace-security-focus.log`（2026-09-07）。该轮包含上述原子交换与二进制 Worker 契约；后续新增的
无正文 stat 查询和 PTY 持久边界另行回归，不将不同运行的数量相加作为一次全量结果。

`CodingTerminalRepositoryTest` 的 8 项真实 H2 独立断言已通过：原始字节跨块读取、旧快照尾部上限、并发追加冲突、
Workspace/Turn 归属复核、输入仅存摘要与未知送达不重发、终态冲突、启动恢复未知结果、索引损坏拒绝，以及终态
事实与 Turn 状态迁移的共同事务回滚。生产修复对应这些边界；正式联合结果及最终覆盖率仍以主任务回执为准。

macOS 原生网络探针另已在 `NO_PROXY=*` 下复验允许的 TCP、其他 TCP、同端口 IPv6 及同端口 UDP；UDP 由宿主确认
没有收到报文。探针保留 Node/libuv 所需的 `SO_OOBINLINE` 初始化，避免只用默认 Java Socket 掩盖真实运行库失败。
Linux 同样增加宿主 UDP 未收到报文断言，但本机不将它当成 Linux 内核通过。

## 必须保留的五 Runner 门禁

macOS arm64/x64、Linux arm64/x64、Windows x64 均需实际安装托管工具链并在准备阶段运行 Maven、Gradle、npm、pnpm、
pip 原生命令，覆盖运行库与生命周期脚本、缓存读写以及构建/测试 OFFLINE。所有步骤必须传递真实受信 Turn 权限，
不能只验证代理环境变量或沙箱配置文本。

五 Runner 工作流已按 OS、arch 与发行目录摘要缓存归档；每次运行仍用固定下载脚本复验大小和 SHA-256，再把真实
归档目录传给 clean verify。下载或实际工具链执行失败会使任务失败，不能通过缺少缓存/归档的条件跳过来宣称通过。
联网准备另由显式 `javaclaw.codingNetworkAcceptance` 开关控制；在开关启用并取得对应 Runner 回执前，归档/离线
执行成功不能被计作 Maven、Gradle、npm、pnpm、pip 的真实联网依赖安装均已通过。

Windows 还需：MSVC 最终链接 FirewallAPI；签名 MSI 首装、升级、修复、卸载；安装 UAC 与 Program Files ACL；受限 Pipe
认证及句柄取消；允许精确端点但禁止其他 TCP/UDP/DNS/IPv6；服务/Broker 崩溃、Job 子进程终止、重启残留策略清理；
清理前后其他应用 loopback 授权保持存在。Linux 还需真实 namespace/UDS relay 断连、原始 Socket/io_uring 旁路拒绝和
进程树回收。缺少对应系统回执时，相关平台状态保持未验证。
