# 可见 Browser 原生前置验证（2026-09-14）

本轮可见 Chromium 能力**仍未通过**；不能生成 `browser-interactive-v1.capability`，也不能把基础监护测试
通过写成真实窗口成功。没有使用账户、Cookie、外部页面或付费模型，没有全局 Mach 前缀放行或无沙箱浏览器回退。

## 原生探针

环境为 macOS 26.5.2（25F84）、arm64、JDK 25；SDK 来自本机 Xcode。
探针源码和二进制只放在 `javaclaw-native-hosts/target/native-browser-probe/`，不作为产品原生依赖或 JNI 引入。

- `bootstrap_probe.c` 使用当前 SDK 的 `<servers/bootstrap.h>`，先分配 requestor receive right，再调用
  `bootstrap_subset`。结果 `subset_a=125 (unknown error code)`，未进入登记或浏览器启动，退出码 12。
- `BootstrapFfmProbe.java` 使用 Java FFM 独立对照，结果如下：

  ```text
  mach_port_allocate=0
  bootstrap_subset=125
  subset_created=false
  requestor_destroy=0
  ```

- 两种探针已在宿主环境重新运行，结果一致，排除了 Codex 外层执行沙箱造成的误报。
- 本机 SDK 将 `bootstrap_subset` 标注为自 macOS 10.10 废弃。125 不是该 SDK 公开的 bootstrap 错误常量；
  本轮不猜测其内部名称，也不把旧版 launchd 头文件契约视为当前系统成功证据。

## 已实现的边界

NativeHost 新增显式 `startInteractive(command, idleTimeout)` 与 `SandboxedWorkerLease`；普通 Worker 的
1 秒至 10 分钟描述约束不变。闲置墙钟期限独立限制为 1 秒至 15 分钟，由可信宿主动作续期，页面后台活动不能续期。

独立 monitor helper 持有控制目录、Mach requestor 权利和目标进程树。控制目录不授予 Worker；Worker
stdin/stdout 仍专供协议帧。macOS 在启动目标前验证两个私有 namespace 的互相不可见与宿主不可见，再安装实例
bootstrap special port。任何原生失败直接终止启动，本机返回 `MAC_BOOTSTRAP_UNAVAILABLE: bootstrap_subset returned 125`。

可见策略只增加精确 WindowServer/LaunchServices 查询、必要的两项 IOKit 类、本实例 scratch 内 Unix IPC，
以及私有 namespace 内固定 Chromium rendezvous 前缀；没有扩大普通 Worker 策略。TCP、UDP、DNS、其他 Unix
socket 继续由默认拒绝阻断。Windows/Linux 沿用现有平台 builder，未声称本轮完成对应可见验收。

监护处理宿主退出、闲置到期、显式关闭及进程树资源超限。监护保留已观察的 ProcessHandle 身份，并回收
POSIX 进程组和已知后代；Worker 根进程在受控关闭前自行退出时，不签发成功回执，避免把未观察到的子进程
当作已经清理。异常退出使 `lease.process().onExit()` 异常完成，保留私有控制记录，不能触发成功的 scratch 清理。

这仍不是针对任意快速 fork、脱离进程组或 reparent 后代的内核级整树证明。现有进程观察有竞态窗口；即使未来
私有 bootstrap 探测成功，也必须补齐逃逸后代、双实例、崩溃回收的真实门禁，不能仅凭 cat/sleep 单测发布能力。

宿主环境的定向构建、Spotless、Checkstyle 与 `SandboxedWorkerLeaseTest`、`SandboxedWorkerContractsTest`
共 12 项测试通过、0 跳过。记录位于 `/tmp/javaclaw-native-resident-test.log`；这些测试使用原生隔离的 cat/sleep
夹具，只证明租约和监护行为，不证明可见 Chromium 能力。后续 `SandboxedWorkerLeaseTest` 新增
“根在首次观察窗口内退出不能签发整树回收成功回执”竞态夹具，该类 7 项在宿主环境通过。

策略解析首次发现 `remote unix-socket` 在本机 Seatbelt 不是有效 filter。现按系统自带
`/System/Library/Sandbox/Profiles/com.apple.CryptoTokenKit.ctkahp.sb` 的直接 `subpath` 路径规则修正，
只匹配实例 scratch，不新增 TCP/UDP allow。修正后的完整策略解析测试已在宿主环境通过；同轮 `SandboxedWorkerLeaseTest` 8 项和
`MacInteractiveProfileTest` 1 项全部通过、0 跳过，日志 `/private/tmp/javaclaw-browser-server-test.log`。
这只证明策略可解析与上述租约拒绝边界；最终可见 smoke 的原生前置条件仍不成立。

## 现代 XPC / launchd 调查结论

当前 SDK 的 `xpc(3)` 明确说明 XPC 刻意不支持动态 bootstrap 服务登记；`xpcservice.plist(5)` 中的
Application namespace 按应用建立并由嵌入的 XPC service bundle 填充。`launchctl(1)` 的 `pid/<pid>` domain
描述的是该进程可见、可由 `xpc_connection_create` 访问的 XPC 服务；它没有给出替代动态 Chromium
`bootstrap_check_in` 的公开契约。GUI 与 user domain 的 Mach 名字空间还存在共享，不能把每个 job label
或每个 PID domain 直接等同于私有 Mach rendezvous 空间。

当前 SDK 提供 `posix_spawnattr_setspecialport_np`，可安装已有特殊端口；它不负责创建私有服务 namespace。
两项候选 `xpc_domain_create`、`xpc_domain_create_with_bootstrap` 在当前运行库中均未找到导出符号。

发布链另设 `verifyInteractiveProcessContainment()` 硬门禁：当前三平台均返回
`INTERACTIVE_TREE_CONTAINMENT_UNVERIFIED`。Linux 已有 `--unshare-all`、`--die-with-parent` 的 PID namespace；
Windows 已有暂停创建后装配 kill-on-close Job Object，但这些源码边界没有本轮常驻可见链的真实 Runner
回收证据，不能以静态存在视为已验收。启动入口与能力 smoke 都执行该门禁；当前 macOS 先返回更早的 bootstrap 125。
这使可见浏览器仍无法在当前平台启用，是未完成的发布条件，不能用修改 marker 绕过。

因此本轮没有基于未公开 XPC routine、全局 launchd 作业注册或扩大 Mach 权限继续试错。真正支持当前 macOS
可见 Chromium 仍需要经过单独验证的现代域方案或等价的受限 IPC 实现，并证明父子通信、双实例隔离、原始网络
拒绝、崩溃与关闭回收；现有代码的正确结果是能力不可用。

参考：[Apple bootstrap.h](https://github.com/apple-oss-distributions/launchd/blob/main/liblaunch/bootstrap.h)、
[Apple XPC 文档](https://developer.apple.com/documentation/xpc)。本机 SDK 手册为上述现代行为判断的直接依据。
