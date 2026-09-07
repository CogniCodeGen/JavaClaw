# CLI 前台执行

发行包的 `bin/javaclaw-cli`（Windows 为 `bin\javaclaw-cli.cmd`）通过 Java SDK 启动并拥有一个 stdio
App Server。`turn-start` 持有连接和服务进程，直到服务端报告 `COMPLETED`、`FAILED` 或 `CANCELLED`。
命令需要已有的 Thread；执行选择和权限仍由服务端解析、校验和冻结。

```bash
bin/javaclaw-cli workspace-list
bin/javaclaw-cli role-list
bin/javaclaw-cli turn-start '<thread-id>' '检查当前项目中的测试失败'
bin/javaclaw-cli turn-start '<thread-id>' '总结项目结构' --non-interactive > result.json
```

`turn-start` 的标准输出保留一个 `TurnStartResult` JSON 对象，包含最终权威 Turn 和冻结配置；进度、
已完成的模型消息、审批和输入提示写入标准错误。终态后仍读取本 Turn 的最终消息，分页输出不会重复已打印消息。
连接中断或取消未获确认时，不输出虚构的终态；标准错误保留 Turn ID，以便通过 SDK 查询真实结果。

## 执行参数

所有选择均可省略，缺省时继承服务端配置。引用中的版本必须明确指定，不会自动采用 latest。

| 参数 | 含义 |
|---|---|
| `--role <id> <revision>` | 精确 Agent Role |
| `--provider <endpoint-id> <revision> <model>` | 精确 Provider 和模型 |
| `--permission <id> <version>` | 精确 PermissionProfile |
| `--approval <policy>` | `ApprovalPolicy` 枚举值，服务端继续执行审批约束 |
| `--reasoning <preference>` | `ReasoningPreference` 枚举值 |
| `--budget <input-tokens> <output-tokens> <tool-calls> <child-threads> <seconds>` | 本 Turn 的预算覆盖 |
| `--capabilities <name,name,...>` | 能力收窄；空字符串明确禁用全部能力 |
| `--idempotency-key <key>` | 同一请求重试使用的幂等键；同键不能替换请求正文 |
| `--non-interactive` | 即使存在终端，也强制使用脚本模式 |

直接运行 Java 主类时，用 `--` 分隔 CLI 参数和 App Server 启动命令：

```text
java -cp <classpath> com.javaclaw.client.cli.JavaClawCli turn-start <thread-id> <message> [options] -- <server executable> [server arguments...]
```

发行启动器已经追加服务端命令，不需要再次传入 `--`。服务端命令按参数数组启动，不经 shell 展开。

## 同一会话中的编程任务

继续对已有 Thread 使用 `turn-start`；没有独立 chat/code 模式或依赖安装子命令。
在消息中请求 `dependencies_prepare`，由当前 Turn 的工具权限与审批执行。运行中的命令、准备和 PTY 输出写入
标准错误，并保留有界尾部读取；标准输出 JSON 与下文退出码不变。查看工具链和环境可使用已有管理命令：

```bash
bin/javaclaw-cli coding catalog '<workspace-id>'
bin/javaclaw-cli coding toolchains '<workspace-id>'
bin/javaclaw-cli coding environment '<workspace-id>'
bin/javaclaw-cli coding job '<job-id>'
```

管理命令不执行项目脚本。环境与显式权限配置、工具链安装 Job、SDK 输出分页和当前平台限制见
[编程使用说明](coding.md)。取消仍作用于整个 Turn，不提供人工 PTY 输入协议。

## 人工交互

存在交互终端时，审批展示工具身份、风险、说明、参数摘要和到期时间。输入 `approve` / `批准` 明确批准，
或 `deny` / `拒绝` 明确拒绝；空行、其他文本均不会批准。拒绝后由 Harness 将其作为工具结果继续处理。

结构化输入展示问题和响应 Schema，答案必须是一行 JSON 对象，例如 `{"choice":"继续","confirmed":true}`。
CLI 复用 Protocol 的受限 Schema 校验，检查必填字段、类型和允许值；不会生成默认答案。单行输入最多 64 KiB。

每次输入绑定已展示请求的 ID、revision 和代次。请求过期或版本变化后，迟到答案会被丢弃；版本冲突后重新展示
权威请求，旧答案不会自动重投。阻塞终端读取不会阻止观察 Turn 的完成和取消。

交互提示中输入 `/cancel`、终端 EOF 或 Ctrl-C 会进入有界取消流程。CLI 通过 SDK 请求取消并等待服务端确认，
默认最多等待两秒；无法确认时明确报告 Turn ID 和未知结果。关闭本地进程本身不能证明取消已落盘。

## 脚本模式与退出码

没有交互终端或指定 `--non-interactive` 时，CLI 不读取管道内容作为授权。无需人工输入的任务正常运行至终态。
待审批操作通过 SDK 明确拒绝，原因记录为“无人工交互宿主”，Harness 决定后续执行；必须人工回答的输入请求
则触发取消并返回退出码 `2`，避免脚本永久等待。程序化审批和输入使用现有 Java SDK 的 `approvals()` 和 `inputs()`
接口；CLI 没有双向 JSON Lines 协议。

| 退出码 | 含义 |
|---:|---|
| `0` | 服务端确认执行成功 |
| `1` | 执行、连接或终端 I/O 失败 |
| `2` | 参数错误，或脚本无法提供必要的人工输入 |
| `130` | 用户取消，或服务端报告 Turn 已取消 |

必要输入和用户取消的退出码表达停止原因；是否已进入服务端终态，以输出结果和取消确认信息为准。
终端、Ctrl-C 和原生进程行为的验证范围见[架构修复验收](evidence/v6-review-fixes-validation.md)。
