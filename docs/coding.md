# 在同一会话中聊天与编程

JavaClaw 使用现有 Thread 和 Turn 完成问答、项目检查、修改与运行测试，不需要切换 `chat` / `code` 模式，
也不要求更换为专门 Role。能否使用 Coding 工具取决于本次任务冻结的工具目录、能力范围与实际权限。

## 准备工作区

1. 在 Desktop 的设置与管理中心选择目标 Workspace，打开“编程环境”。选择项目所需的托管 JDK、Maven、Gradle、
   Node、npm、pnpm、Python 或 pip；在“托管工具链”中选择可信发行条目并安装。安装是可观察、可取消的后台 Job，
   只下载、校验和解包工具链，不执行项目脚本。保存环境后，新 Turn 使用服务端解析并冻结的选择。
2. 在 PermissionProfile 页面配置并选择适合该 Workspace 的权限版本：明确文件读取/写入根目录、所需可执行文件、
   运行时间和资源上限，以及允许的 Coding 工具与审批规则。需要 PTY 时显式允许交互终端。内置“受限对话”
   （`standard`）不会因为安装工具链或选择软件开发 Role 而获得项目读写、进程或网络权限；能力收窄也不能增加权限。
3. 在“编程环境”中填写准备阶段可用的公共 HTTPS 仓库主机，并在权限方案中允许对应主机及 443 端口。
   例如 npm 项目使用 `registry.npmjs.org`，pip 通常需要 `pypi.org` 和 `files.pythonhosted.org`。
   仓库配置与网络权限需要同时满足；普通构建、测试和 PTY 仍默认断网。私有源凭据不在当前支持范围内。

“允许原生依赖安装脚本”控制准备阶段的脚本策略。关闭后，不能保证禁用项目逻辑的包管理器会明确拒绝准备；
这不是所有包管理器通用的“仅下载”开关。准备失败时可能仍有锁文件或源文件变化，应查看实际结果和 Diff。

## 请求准备、修改与测试

直接在原会话中描述任务，例如：

> 先介绍这个项目，再调用 dependencies_prepare 准备 npm 依赖；修复失败的测试，断网运行验证，展示 Diff 和退出码。

`dependencies_prepare` 是当前 Turn 内受审批、预算和取消约束的工具。模型会根据可用工具和请求安排操作；
设置页、工具链安装 Job 和普通管理 RPC 不直接执行依赖准备。准备阶段由原生包管理器经受控代理访问已授权仓库，
结束后撤销联网租约，再启动新的断网测试进程。缺少工具链、权限、系统组件或兼容版本时会返回实际失败。
完成后可继续在同一会话提问，命令失败本身也不要求另建会话。

## 查看输出与取消

Desktop 的会话进度侧栏显示运行中命令、依赖准备和终端的只读输出；转录保留命令状态、退出码、文件 Diff 及恢复目录。
“编程环境”的“准备状态”显示最近准备记录。输出有界，界面保留尾部；通过会话原有取消入口取消整个 Turn。
首版没有人工终端 stdin 输入框，模型的受治理终端工具仍属于当前 Turn。

CLI 使用同一 `turn-start` 命令。例如，对已有 Thread 执行：

```bash
bin/javaclaw-cli turn-start '<thread-id>' '准备 npm 依赖，修复测试并断网验证'
```

运行中的输出、审批和最终消息写入标准错误；标准输出仍是最终 `TurnStartResult` JSON，退出码契约保持不变。
Ctrl-C、交互提示中的 `/cancel` 和取消确认规则见 [CLI 使用说明](cli.md)。子命令的非零退出码可成为模型修复依据，
不等于 CLI 最终 Turn 的退出码。脚本模式不会自动批准待审批工具。

程序化观察使用 `client.builtins().coding()`：先以 `executions(workspaceId, threadId, turnId)` 查询服务器返回的
执行资源，再按操作类型调用 `commandOutput`、`preparationOutput` 或 `terminalOutput`。后者的输出位于返回值的
`output()`。传入 `CodingResults.OutputRead(resourceId, offsetBytes, maxBytes)`，使用响应的 `nextOffsetBytes`
继续分页；不要用字符串长度推算字节游标。末页无新字节时停止追页，运行中稍后再查询。
目录过滤与资源 ID 不授予额外权限；SDK 仍由服务端校验读取范围。未知状态或 Schema 保留文本，不当作执行成功。

## 当前验证范围

截至 2026-09-07，本机 macOS arm64 的公开依赖准备与后续断网验证结果如下：

| 包管理器 | 实测结果 |
|---|---|
| npm | 原生安装、生命周期脚本及断网测试通过 |
| pnpm | 原生安装、独立 store、生命周期脚本及断网测试通过 |
| pip | 托管 venv 安装及断网导入、版本断言通过 |
| Maven | 空依赖缓存准备、真实 JUnit 测试与断网复验通过 |
| Gradle | macOS 严格网络策略下失败；动态本机 UDP/TCP 通信尚不满足隔离边界 |

同一 Thread 的三 Turn npm 流程已使用固定模型替身和真实 Harness、工具链、沙箱、H2 完成验收；未调用付费模型，
不代表真实模型效果评估。详见 [公开仓库验收](evidence/coding-public-repository-acceptance.md) 和
[工具链安装记录](evidence/coding-toolchain-installation-validation.md)。

Windows 文件替换目前有两次移动之间目标短暂不存在的窗口，尚未满足单次原子替换要求。
另外四个 Runner（macOS x64、Linux arm64/x64、Windows x64）及 Windows 辅助服务的真实签名、安装、升级、修复、
卸载仍未验收；不能将代码存在、跨编译或条件跳过计为通过。限制与待验项目见
[统一编程架构](architecture/unified-coding-agent.md)、[网络与五 Runner 验收](evidence/coding-network-validation.md)；
界面检查见 [客户端状态证据](evidence/coding-client-state-review.md)。
