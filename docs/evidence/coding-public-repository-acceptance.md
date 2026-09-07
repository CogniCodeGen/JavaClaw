# 公开仓库与托管工具链实际验收

日期：2026-09-07。平台：macOS arm64。本记录使用当前工作树、真实 Seatbelt、生产 `CodingPlatform`、
生产 CONNECT Broker、真实 H2 和发行目录中的官方归档。未调用付费模型，统一 Harness 使用固定模型替身。
其他四个 Runner 尚未执行，不能据此记为通过。

## 执行方法

官方归档先按 `coding/toolchains-v1.json` 的大小与 SHA-256 校验，再通过 `PublishedToolchainFixture` 的真实安装流程
解包到隔离测试 `data-v6`。npm 与 Node、pip 与 Python 复用同内容制品。运行使用应用托管 JDK，不以宿主 JDK
替代已声明的版本。

验证调用维护中的 `PublishedDependenciesPreparationTest.prepareAndRun`：依赖准备由已授权 Turn 调用
`dependencies_prepare`，进程返回并关闭代理租约后，再由 `command_run` 启动新的 OFFLINE 进程。命令成功必须具备
真实退出码；Java 项目还检查实际测试报告。为定位失败，部分重跑只选择一个包管理器，并复用已经安装、校验过的归档；
这不等于整个五类工具链测试类一次通过，也不等于完整 Maven `verify` 门禁通过。

明确授权的公开目标为 `repo.maven.apache.org:443`、`registry.npmjs.org:443`、`pypi.org:443`、
`files.pythonhosted.org:443`。代理不解密 TLS，不提供“仅下载”的应用层保证。测试未加入源站凭据。

## 当前结果

| 工具链 | 实际项目 | 本机结果 |
| --- | --- | --- |
| Node 22.18.0 / npm 10.9.3 | 安装 is-number 7.0.0，postinstall 写入标记，断网 npm test 验证依赖和标记 | 通过 |
| Node 22.18.0 / pnpm 10.14.0 | 原生安装、生命周期脚本、独立 store，断网 pnpm test | 通过 |
| Python 3.12.11 / pip 24.3.1 | 创建托管 venv，安装 idna 3.10，断网 Python 导入与版本断言 | 通过 |
| Temurin 25.0.1+8 / Maven 3.9.11 | 从空依赖缓存下载 JUnit 4.13.2 与原生插件，编译运行测试，再断网复验 | 通过 |
| Temurin 25.0.1+8 / Gradle 9.1.0 | Java 编译与 JUnit 项目测试 | 严格网络策略下失败 |

npm/pnpm 日志：`/tmp/javaclaw-real-dependencies-probe-4.log`；pip 日志：
`/tmp/javaclaw-real-dependencies-selected-5.log`。这些临时路径属于本机调试证据，不保证长期保留。
Maven 第九轮复用了部分依赖缓存；第十轮在应用代理修复后重新使用空依赖缓存，日志为
`/tmp/javaclaw-real-maven-10.log`。断网进程结束后的 Surefire 报告包含 `tests="1"`、`failures="0"`、
`errors="0"`、`skipped="0"`。第八轮非预期取消的具体异常未捕获，不将后续成功当作该次原因的直接证明。
第十轮复制正式编译的 12 个类目录到独立临时运行时，避免仓库 clean 构建影响正在运行的验收。

Maven 的默认 HTTPS Host 头兼容问题已修复并通过 CONNECT 专项测试：仅允许请求行的精确 `host:443` 对应
省略默认 443 的同一 Host 头，不改变目标路由或授权。旧重跑在约 180 秒时被夹具授权期限截断，下载中的 JAR
保留原生失败证据。真实联网验收夹具已将准备权限上限改为 600 秒、Turn 上限改为 30 分钟；生产用户预算不变。

## 实际兼容修复

- macOS 系统 `/bin/sh` 选择器需要读取固定的 `/private/var/select/sh`，并启动固定系统 shell。npm 明确使用
  平台 shell，避免在 Workspace 祖先的 `node_modules/.bin` 搜索宿主程序。
- Node、Python 和 Java 运行库需要特定的 socket 初始化选项，Python 的目录遍历需要 `F_DUPFD_CLOEXEC`。
  Seatbelt 仅增加实际需要的选项和 fcntl 命令；它们不新增可连接端点。精确端口、UDP、IPv6、其他本机 TCP 与
  `NO_PROXY=*` 旁路仍由原生隔离测试验证。
- python-build-standalone 的 Unix venv 保留可执行文件符号链接，使 `@executable_path` 的运行库定位继续指向
  托管发行版；Windows 采用自己的 venv 布局。ensurepip 失败时保留有界真实 stderr，不把环境创建失败记为成功。
- Maven 直接使用托管 JDK 启动 ClassWorlds，同时显式读取有界项目 JVM 配置、识别多模块根并记录真实启动证据。
- 独立回归证实，代理在远端 EOF 时无条件中断上传线程，可能中断共享权限文件通道。现改为关闭两端 Socket
  并有界等待线程退出。该测试在旧代码下失败、修复后通过；仍保留每次连接和传输时的授权检查，不凭性能推测放宽策略。

## 未满足的发行条件

Gradle 9.1 的文件锁协调器会绑定动态本机 UDP，编译与测试 worker 还使用动态本机 TCP。实际去除 daemon 后仍需要
这些通信；调整 JVM 参数不能消除它们。macOS 当前只有宿主网络上的精确代理端口权限，故真实 Gradle 构建失败。
调试中的隔离探针与临时实验未进入生产代码，未通过允许整个 localhost 绕过要求。是否暂不提供 macOS Gradle 构建，
或引入独立虚拟化执行环境，仍待范围确认。

完整交付仍要求五个 Runner 各自的原生安装、公开仓库准备、独立断网构建与旁路测试，以及 Windows 辅助服务的
真实签名、安装、崩溃清理、升级和卸载证据。条件跳过和跨平台编译不能填补这些结果。

## 统一会话的实际 NPM 验收

`PublishedCodingHarnessTest` 在 macOS arm64 的正式 Maven 聚焦运行中通过，日志为
`/tmp/javaclaw-coding-stream-focused.log`。它使用固定模型替身和真实 `DefaultTurnHarness`，在同一 Thread 的三个
Turn 中完成聊天、发现工具、读取项目、通过生产代理安装 is-number、修改代码、断网测试失败、修复、断网测试通过
和继续聊天。调用真实托管 Node/npm、Seatbelt、H2 与 CAS，没有调用付费模型。

断言检查 9 组工具调用及结果、3 个真实 Command、5 个 EffectReceipt、两次文件修改的前后摘要、原 inode 恢复备份、
依赖清单和锁文件证据、最终 checkpoint，以及准备结束后已经撤销的网络租约。首次聚焦运行的备份断言错误地要求
成功补丁没有恢复目录；现按生产契约验证保留的旧内容，未删除备份或放宽文件修改策略。

复跑参数为 `-Dtest=PublishedCodingHarnessTest -Djavaclaw.codingHarnessNetworkAcceptance=true
-Djavaclaw.toolchainArchives=/tmp/javaclaw-toolchain-catalog`，配合 App Server 及其 Maven 依赖模块执行。聚焦测试通过
不替代全仓质量门禁、真实模型效果评估或另外四个 Runner 的验收。官方安装下载的独立证据见
[工具链安装记录](coding-toolchain-installation-validation.md)。

五 Runner 工作流显式启用 `javaclaw.codingNetworkAcceptance=true` 和
`javaclaw.codingHarnessNetworkAcceptance=true`，同时提供经过校验的官方归档目录。
这使公开依赖准备和完整 NPM 会话成为 CI 实际检查；已知 macOS Gradle 失败仍会阻断该要求，
不通过关闭验收属性或条件跳过把它记成通过。工作流配置本身不代表远端 Runner 已执行。
