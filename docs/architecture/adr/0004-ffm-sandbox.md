# ADR-0004：JDK FFM 与失败关闭沙箱

- 状态：Accepted，三平台代码完成；非本机后端等待原生 CI
- 日期：2026-08-27

## 决策

- JavaClaw 自研原生接口只使用 JDK 25 FFM；禁止 JNI、JNA、`System.loadLibrary` 和自带 bridge。
- 原始 FFM 只能位于命名模块 `com.javaclaw.nativehosts` 的未导出包，且仅由独立 Launcher 与 Windows
  Transport Host 两个辅助进程使用。
- App Server 通过继承的 stdin/stdout 发送一次性策略和随机 nonce，不从临时文件读取安全策略。
- 审批不能绕过沙箱；Tool、Turn、Server 策略只做权限交集。
- 平台无法精确实现策略时拒绝执行，不退化为裸进程。
- 模式为 `READ_ONLY`、`WORKSPACE_WRITE`、`HOST_FULL_ACCESS`；最后一种必须显式审批。

## 平台后端

- macOS：Seatbelt profile；受保护路径规则最后应用；FFM 设置 CPU/文件描述符限制并管理真实 PTY。
- Linux：bubblewrap namespace/capability 隔离，目标 exec 前安装 no-new-privileges + seccomp BPF；
  POSIX PTY 复用相同边界；无精确 allowlist 时失败关闭。
- Windows：一次性 AppContainer SID、Restricted Token、Low IL、临时 grant/deny ACL、继承句柄
  白名单与 Job Object；ConPTY 与 AppContainer attributes 在同一次进程创建中提交；ACL 恢复失败
  永久锁定 Workspace。

## 当前限制

Linux 尚无 Landlock 等价回退，必须同时具备 bubblewrap 与 seccomp；普通子进程不实现 hostname
allowlist，而是通过 Network Broker 获取能力型 HTTP。Windows HOST_FULL_ACCESS 无法同时保证
permanent protected roots，因此拒绝而不裸执行。pipe session 不伪装 PTY；Windows pipe session
不伪造 console interrupt。
