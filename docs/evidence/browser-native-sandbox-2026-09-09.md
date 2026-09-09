# Browser 原生沙箱验收限制（2026-09-09）

本机严格 Browser 原生验收**未通过**，不能发布交互登录或 OAuth 的能力回执。普通聊天、模型验证和 SDK
回放的结果不替代这一门禁；无头浏览器也不能替代交互窗口验收。

## 环境与范围

- macOS 26.5.2、arm64、JDK 25；Playwright 1.52.0、Chromium 136.0.7103.25（revision 1169）。
- Chromium、FFmpeg 和驱动均来自本机已核验的缓存或锁定 Maven 依赖，没有联网安装。
- 探针仅使用独立临时目录和固定本地页面，没有账户、Cookie、用户配置或外部模型请求。
- 原始失败是 `BrowserNativeSandboxSmokeTest` 登录启动阶段的 `RPC stream ended`，见
  `/tmp/javaclaw-functional-clean-verify.log`。生产标准错误仍保持丢弃，避免记录页面或凭据。

## 最新生产策略复验

2026-09-09 10:15:23–10:15:30（Asia/Shanghai）直接重跑原
`BrowserNativeSandboxSmokeTest.五平台Runner只在原生Sandbox登录和OAuth私有回调成功后分别发布能力`，
没有替换测试断言、客户端或生产沙箱策略。完整记录为
`/tmp/javaclaw-functional-browser-native-latest.log`。

- 日志的 `REACTOR_CLASS` 确认验收类来自 `javaclaw-packaging/target/test-classes/`，
  `BrowserWorkerClient`、`SandboxedWorkerCommand` 来自对应模块本轮编译的 `target/classes/`，
  使用的是最新宿主类，而非早期 `/tmp` 原型覆盖类。
- 打包镜像中的 `bin/java`、`lib/libjli.dylib`、`driver/node`、`driver/package/cli.js`
  均通过普通文件及镜像内真实路径检查，日志记录了其大小。
- 原登录断言仍失败：外层为 `Browser login startup failed`，内层为
  `Browser login ended: BROWSER_REQUEST_FAILED`。这比早期 `RPC stream ended` 更具体，
  但仍未到达登录 `READY`；后续登录保存、OAuth 回调及两项能力回执发布没有执行成功。
- 同一测试区间的 macOS unified log 记录 Chromium PID 66792 被拒绝查询
  `com.apple.windowserver.active` 与 `com.apple.coreservices.launchservicesd`。
  这是当前生产策略的实际阻断位置；下文 Mach rendezvous 失败属于另一次临时显示权限原型，
  不能混写成这一轮生产复验已推进到的阶段。
- 进程退出后的清理检查为
  `PRIVATE_SCRATCH remaining instance directories after exit: 0`，确认本机本轮失败路径没有遗留
  Worker 实例子目录。该结论不代表浏览器启动成功，也不代表所有平台均验证为零残留。

## 已实现并保留在当前工作树的部分

| 问题 | 证据与修复 |
| --- | --- |
| 独立 JVM 在进入 Worker 前退出 | 原生沙箱禁止映射镜像 `lib/libjli.dylib`；只授权镜像内 `lib/` 后，`java -version` 正常退出。 |
| Playwright 从可写目录执行 Node | 默认解压的 `work/playwright-java-*/node` 被正确拒绝；构建时提取锁定驱动到镜像 `driver/`，通过 `playwright.cli.dir` 使用，只读驱动初始化成功。 |
| 临时目录不能清理 | 普通 Worker 保持禁止删除；Browser 显式声明受控 scratch，仅允许清理子项，保护根及只读、执行目录。 |
| 多个 Browser Worker 共用临时根 | 每次启动分配独立子根，重绑临时目录和写权限；单个实例退出只回收自己的目录。 |
| 未知架构被当成 x64 | 驱动装配仅接受已有五种平台组合，拒绝其他架构，不发布不匹配的镜像。 |

临时目录回收使用目录身份检查及不跟随符号链接的安全目录句柄；文件系统不支持
`SecureDirectoryStream` 时只删除空实例根，非空内容保留在该实例的隔离目录并告警，不执行路径递归。
本机零残留证据不能替代这类文件系统及其他 OS Runner 的回收验收。

## 未合入的显示权限实验

以下对照只存在于 `/tmp/javaclaw-browser-startup-probe/`，没有改变生产沙箱策略：

1. 两项精确窗口服务查询消除了 `HIServices._RegisterApplication` 的 `SIGABRT`：
   `com.apple.windowserver.active`、`com.apple.coreservices.launchservicesd`。
2. 仅允许 scratch 子路径的 Unix IPC 后，Chromium 的 `SingletonSocket` 绑定成功推进；TCP、UDP、DNS、
   其他 Unix socket、外部应用打开及 AppleEvent 继续拒绝。
3. 根据崩溃栈和同一 PID 的拒绝记录，精确开放 `IOPMrootDomain` / `RootDomainUserClient` 的对照消除了
   `IONotificationPortGetRunLoopSource(null)` 的崩溃。
4. 随后仍在 Chromium `base/apple/mach_port_rendezvous.cc:410` 失败：
   `bootstrap_check_in org.chromium.Chromium.MachPortRendezvousServer.<PID>: Permission denied (1100)`。
   `local-name-prefix` 对照未解决登记，不能据此声称已经具备实例隔离。

没有尝试开放全局 Mach 名称前缀，也没有开放宿主 HOME、密钥库、剪贴板或任意 Mach 服务。探针中的
`exit=0` 仅表示诊断程序退出；交互成功必须实际到达 `BROWSER_READY` 或正式 smoke 的登录、OAuth 断言，
本轮没有达到。最后一轮对照记录为 `/tmp/javaclaw-browser-startup-probe/latest-local-mach-probe.txt`。

现有 NativeHost 尚无私有 bootstrap namespace 的创建与生命周期契约。后续需要证明 Chromium 父子进程
可以通信、两个独立实例不能访问彼此的 Mach 服务，再决定是否引入这一能力；不能用扩大前缀授权代替
隔离证明。严格原生门禁应继续如实失败，不能关闭测试或将跳过记作通过。
