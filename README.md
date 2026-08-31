<div align="center">

# JavaClaw 4.0

**Codex 风格、本机优先、协议驱动的 Java Agent Runtime**

![Java](https://img.shields.io/badge/Java-25-orange)
![Protocol](https://img.shields.io/badge/JSON--RPC-2.0-blue)
![Storage](https://img.shields.io/badge/Store-H2-004088)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

JavaClaw 4.0 是一个由本机 App Server 承载的 Agent Runtime。JavaFX Desktop、CLI 和第三方
本机应用都通过 Java SDK 使用同一套 JSON-RPC v1；产品状态统一表示为
`Thread → Turn → Item`。项目吸收 Codex 的核心/客户端分离、事件恢复、审批与沙箱分离等原则，
但保留 Java 25、JavaFX、Spring AI、H2 和多云 Provider，不兼容 Codex 私有协议。

## 当前 4.0 架构与能力边界

当前提供 4.0 预发布测试版：保留原桌面设计体系，已接入 Loop/Workflow/SDD、Memory/Skill 版本与学习、
隔离文件/文档/OCR、Browser/站点、有限通信授权和工作树恢复，全部复用同一新内核。
正式跨平台发布仍要求原生 Runner 与签名凭据；本机验证不等于所有平台通过。
逐项实现、证据与外部验收条件见 [能力矩阵](docs/architecture/upgrade-acceptance.md)。

| 领域 | 实现 |
|---|---|
| Runtime | 单 Thread 单活动 Turn、Routing Agent Kernel、steer/interrupt、配置快照、崩溃恢复 |
| 存储 | H2 v4、编号 migration、事务内投影/事件/Outbox、附件、Secret、Feature 与诊断表 |
| 协议 | 严格 JSON-RPC 2.0 v1、stdio/UDS/Windows Named Pipe、游标恢复、未知 Item 前向兼容 |
| 流式 | Item start/delta/complete/fail；delta 不消耗持久 sequence；慢客户端 resync |
| 客户端 | 八领域 typed SDK、重连与进程监督、CLI 人类/JSON 输出、原风格 SDK-only JavaFX；Markdown、附件及领域管理页面 |
| 模型 | OpenAI、Anthropic、Google 流式对话；OpenAI/Google Embedding |
| Feature | Plan 显式采用、模型压缩；有限 Loop、八节点 Workflow、SDD/OpenSpec、Schedule；Memory/Knowledge/Skill 版本、确认与学习 |
| Prompt | 版本化中文模板、可信度分层、逐调用快照、Codex 层级 AGENTS.md、原生/摘要上下文压缩 |
| 工具 | Schema → Policy → Hook → Approval → Sandbox → 限流/脱敏 → Journal |
| 扩展 | Plugin Manifest 4.0、Ed25519、ZIP 安装、进程外常驻 MCP/Hook/Service、隔离健康状态 |
| 协作 | 父子共享预算/配额、Git 合成基线、三方合并、备份与恢复页；清理阻止活动执行，非 Git 单写者 |
| 安全 | JDK FFM、Seatbelt+PTY、bubblewrap+seccomp+PTY、Windows AppContainer/Job+ConPTY、Network Broker |
| 发行 | jlink/jpackage、SBOM、许可、SHA-256 与正式签名门禁；各平台包必须由对应 Runner 实际验收，不等同于已发布 |

4.0 不包含 Ollama、Deliverance、本地模型资产、TCP/WebSocket、远程访问、多租户，也不迁移
任何 3.x 数据或 Plugin API。根 `src`、旧 Session/Conversation/Run 主模型、Callback、
Spring 工作区 Context、可信 JAR 扩展和 Plugin 3 示例已经从 4.0 源树删除。

## Reactor

| 功能领域 | 模块 | 内部边界 |
|---|---|
| 公共契约 | `javaclaw-api`、`javaclaw-protocol` | Core/Sandbox API 与 JSON-RPC Wire Schema |
| Agent | `javaclaw-agent-runtime` | Kernel、Turn Runtime、Tool、Conversation、Automation、Knowledge |
| 服务端 | `javaclaw-app-server` | Transport、H2、Cloud Model、Plugin/MCP、进程级装配 |
| 原生辅助进程 | `javaclaw-native-hosts` | FFM、三平台 Sandbox Launcher、Windows Named Pipe Host |
| 浏览器 | `javaclaw-browser-service` | 进程外 Playwright |
| 客户端 | `javaclaw-client`、`javaclaw-desktop` | Java SDK/CLI 与 SDK-only JavaFX UI |
| 启动与发行 | `javaclaw-packaging` | 统一 Launcher、jlink/jpackage、SBOM、签名与发布 |

Reactor 精确包含 9 个领域模块。架构测试在包级强制 Kernel/Runtime 无
Spring/JavaFX/JDBC/JSON/Store，Desktop 只依赖 Client，原始 FFM 只在
`javaclaw-native-hosts` 的未导出包中，进程创建点只允许出现在 Launcher、SDK Supervisor 与
Windows Transport Host。

## 构建与验证

要求 JDK 25、Maven 3.9+：

```bash
# 每次开发后统一格式，并检查公共契约注释；常规构建在 validate 阶段只检查、不改写
mvn spotless:apply
mvn spotless:check checkstyle:check

# 干净的完整发布前门禁：协议、UDS 对端凭据、原生沙箱、性能与全部模块
# 首次在该平台构建/测试 Browser 时，先显式准备匹配的 Chromium 与许可证
mvn -pl javaclaw-browser-service -am -DskipTests -Djavaclaw.browser.install verify
# Linux 若缺系统图形依赖，为上条增加 -Djavaclaw.browser.with-deps；GUI 测试使用原生桌面或 xvfb-run
mvn clean -Djavaclaw.require.native.sandbox=true \
  -Djavaclaw.performance.gate=true verify

# 单独调试当前平台原生沙箱；不能用 assumption/skip 掩盖失败
mvn -Djavaclaw.require.native.sandbox=true \
  -pl javaclaw-native-hosts -am test

# direct runtime 与 SDK/App Server p50/p95 协议开销门禁
mvn -Djavaclaw.performance.gate=true -pl javaclaw-client -am test

# jlink/ZIP/SBOM/许可/健康脚本
mvn -Djavaclaw.distribution -pl javaclaw-packaging -am verify
javaclaw-packaging/target/distribution/bin/javaclaw-health

# 当前平台未签名测试安装包
mvn -DskipTests -Djavaclaw.distribution -Djavaclaw.native.package \
  -pl javaclaw-packaging -am verify
```

正式发行还必须增加 `-Djavaclaw.release`。缺少 Apple 签名与公证、Linux GPG 或 Windows
Authenticode/时间戳凭据时，构建在 `validate` 阶段失败。GitHub Actions 已配置 macOS arm64/x64、
Linux arm64/x64、Windows x64 原生 Runner 的同版本协议、沙箱、安装包和健康门禁；
不代表这些平台已经全部验证通过，本机实际结果见 [验证记录](docs/architecture/upgrade-verification.md)。

## 运行

### 开发调试：直接运行代码入口（推荐）

1. 将根 `pom.xml` 作为 Maven 项目导入，项目 SDK 和 Maven 使用 **JDK 25**，等待依赖同步完成。
2. 选择仓库提供的 **JavaClaw** Application 运行配置，点击 Run/Debug；它直接调用唯一产品入口
   [`JavaClawLauncher.main()`](javaclaw-packaging/src/main/java/com/javaclaw/launcher/JavaClawLauncher.java)。
3. 手动创建 Application 配置时，Main class 填 `com.javaclaw.launcher.JavaClawLauncher`，
   classpath 选择 **javaclaw-packaging** 模块，运行前保留 Build；IDE 自动编译依赖模块。

这是普通 Java `main`，可以直接设置断点和调试 UI/SDK 启动流程；不执行 `run.sh`/`run.cmd`，
不需要预先生成发行包，不需要另外启动 App Server，也不需要手工设置 JavaFX `--module-path`。
App Server 按新架构由 SDK 启动为独立子进程；需要调试服务端时应对该子进程单独附加调试器，
而不是把 Server 类重新嵌入 Desktop JVM。
共享 IDEA 配置使用 Java argfile 缩短 classpath；manifest classpath 的本地依赖也能被启动器识别。
`JavaClawDesktop` 是由产品入口调用的 JavaFX 生命周期类，不再暴露第二个 `main`；不要使用单文件运行模式。

### 终端便捷脚本（非开发必需）

下面的脚本只负责在终端中构建/定位 classpath，并最终调用同一个 JavaClawLauncher；
它不是开发调试入口，也不包含另一套启动逻辑。

```bash
# macOS / Linux：构建当前源码的发行目录，然后启动桌面
./run.sh

# 显式复用已有发行目录；修改源码后应使用不带该参数的启动命令
./run.sh --no-build

# 查看帮助，不构建、不打开窗口
./run.sh --help
```

Windows 使用 `run.cmd`，也支持 `--no-build` 和 `--help`。脚本可从其他工作目录调用，并支持
项目路径中的空格和中文；默认构建需要 Maven 3.9+，`--no-build` 使用发行目录自带的 Java。

默认构建执行 `mvn -B -DskipTests -Djavaclaw.distribution -pl javaclaw-packaging -am package`，
保留 Spotless、Checkstyle 与依赖门禁，不生成 DMG/MSI 等安装包。任何构建失败都会停止启动，
不会自动运行旧产物；`--no-build` 遇到发行目录缺失或不完整时也会明确报错。

首次打包前按上面的 `javaclaw.browser.install` 命令准备浏览器；这是显式下载，可能需要数百 MiB。
发行物会包含与锁定 Playwright driver 一致的 Chromium/headless shell/ffmpeg、许可证和哈希。
缺少精确资源时构建拒绝继续，不会在用户启动应用时静默下载。
可用 `JAVACLAW_BROWSER_ASSET_DIR` 指定构建资源目录；不要将用户浏览器登录配置作为发行资源。

### 发行目录与配置

```bash
# 与 IDE 共用同一个 Launcher；安装包中的 JavaClaw 图标也指向该入口
javaclaw-packaging/target/distribution/bin/javaclaw

# CLI 使用领域命令，不提供绕过 SDK 的 raw rpc 入口
javaclaw-packaging/target/distribution/bin/javaclaw-cli --help
```

Desktop 通过 SDK 管理自己的 App Server，关闭窗口会清理本次创建的后台进程。Native Host 和
Browser Service 仍然按需运行，启动器不会绕过沙箱，也不会为打开主界面调用云模型或自动下载浏览器。
未配置模型凭据时可以打开界面；实际对话仍需先配置 Provider。

OpenAI 兼容端点必须实现 Responses API。Provider 页可填写 `http(s)://host:port` 或已经包含
`/v1` 的地址；裸主机地址会自动补为 `/v1`。显式自定义端点可不保存 API Key，适用于默认免鉴权的
LM Studio 等本地服务；需要鉴权的服务仍应保存凭据。兼容端点默认使用摘要压缩，只有确认服务实现
`POST /v1/responses/compact` 后才应启用 `nativeCompaction`。

Provider 的“对话模型”是端点默认值，不会静默覆盖已经版本化的 Profile。实际 Turn 始终使用所选
Profile 中的 Provider 和模型标识；更换兼容模型后，应在“智能体”页同步检查 Chat、Plan、Loop、
Workflow、SDD、Schedule 和 Subagent。只实现 Chat Completions、但没有 `POST /v1/responses` 与
Responses SSE/tool calling 的端点，不能满足当前 Agent Runtime。

默认情况下，数据、凭据主密钥、日志和缓存全部位于程序目录下：

```text
<program>/.javaclaw/data-v4
<program>/.javaclaw/config-v4
<program>/.javaclaw/cache-v4
```

不会回退到用户主目录或系统缓存目录。可用 `JAVACLAW_PROGRAM_DIR` 整体指定程序本地根，也可使用
`JAVACLAW_DATA_DIR`、`JAVACLAW_CONFIG_DIR` 和 `JAVACLAW_CACHE_DIR` 分别覆盖。
程序或安装目录必须允许当前用户写入；只读目录会在启动阶段明确失败，不会悄悄改存到其他位置。
高级接入继续支持 `JAVACLAW_APP_SERVER_COMMAND_JSON`、`JAVACLAW_APP_SERVER_CLASSPATH`、
macOS/Linux 的 `JAVACLAW_APP_SERVER_SOCKET` 和 Windows 的
`JAVACLAW_WINDOWS_TRANSPORT_COMMAND_JSON` / `JAVACLAW_WINDOWS_PIPE`。
显式命令优先于显式 classpath，再优先于自动定位；连接外部服务时，关闭桌面只断开连接。
`JAVACLAW_SANDBOX_MODULE_PATH` 兼容原有单目录，也支持平台路径分隔符连接的多个目录或 JAR；
默认值由启动器按 Native Host 的模块依赖自动计算，不需要手动填写。

默认数据根为 `<program>/.javaclaw/data-v4`。它必须为空或带 v4 格式标记；旧的
`~/.javaclaw`、3.x 或未标记非空目录不会被读取、迁移或删除。凭据以 AES-256-GCM 存入 H2，主密钥位于数据库外并要求当前
用户独占权限；查询、日志、事件和诊断包永不返回明文凭据。

### 常见启动失败

| 现象 | 处理方式 |
|---|---|
| JavaFX runtime components / module not found | 使用普通 `JavaClawLauncher` 入口和 packaging 模块 classpath，重新同步 Maven；移除旧运行配置里手写的 JavaFX 模块参数 |
| 缺少 App Server / Native Host | 确认 IDE 已编译 packaging 的依赖模块；终端重新执行不带 `--no-build` 的脚本 |
| Spotless、Checkstyle 或依赖门禁失败 | 修复实际构建错误，不关闭检查，不回退到旧产物 |
| 握手失败、数据目录被拒绝 | 查看同一控制台的 App Server 日志；保留原目录，可用 `JAVACLAW_DATA_DIR` 指向新的空目录 |

## 文档

- [仓库目录、模块职责与本机数据边界](docs/repository-layout.md)
- [代码格式、中文注释与开发检查清单](docs/coding-style.md)
- [本次格式与注释规范化验收记录](docs/coding-style-verification.md)
- [统一启动入口与各平台验收记录](docs/launcher-verification.md)
- [仓库协作约定](AGENTS.md)
- [总体架构](docs/architecture/README.md)
- [509f197 UI 设计基线与回归审查规范](docs/ui-design-regression-baseline.md)
- [JavaClaw v4 UI 回归审查报告与固定视觉证据](docs/ui-regression-review-v4.md)
- [JavaClaw v4 交互差异矩阵与回归审查](docs/ui-interaction-regression-v4.md)
- [完整升级设计：原 UI、旧能力与提示词](docs/architecture/full-upgrade-design.md)
- [旧能力与原 UI 验收矩阵](docs/architecture/upgrade-acceptance.md)
- [Codex 提示词来源、版本与适配记录](docs/architecture/prompt-provenance.md)
- [本轮升级验证记录](docs/architecture/upgrade-verification.md)
- [迁移与原生验证状态](docs/architecture/migration-status.md)
- [安全威胁模型](docs/architecture/threat-model.md)
- [协议 v1 Schema](javaclaw-protocol/src/main/resources/schema/protocol-v1.schema.json)
- [ADR](docs/architecture/adr)

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

Copyright (c) 2026 CogniCodeGen
