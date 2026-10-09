# Java 桌面能力的便携发行

首版发行目标是 macOS 14+ 和 Windows 11。Linux Provider 在此版本返回不可用。
外层 `JavaClaw/` 必须位于用户可写位置，不要安装在 Windows `Program Files`；
macOS `.app` 位于 `runtime/`，其签名内容在运行时保持只读。

```text
JavaClaw/
  runtime/
    JavaClaw.app/                 macOS：包含 JDK 25 和应用依赖
    JavaClaw/                     Windows/Linux：包含 JDK 25 和应用依赖
  plugins/
  data/
```

每个平台的发行包只包含该平台对应的应用镜像。桌面平台适配器使用 Java
实现，通过 JDK 25 FFM 直接调用操作系统提供的库；不再编译或分发项目自建
的 C／C++／Objective-C++ 桌面库。启动 macOS
`runtime/JavaClaw.app` 或 Windows `runtime/JavaClaw/JavaClaw.exe`。
应用从自身 JAR 的代码位置定位外层目录，因此不依赖当前工作目录。
`data/` 保存应用管理的数据库、浏览器资产、截图、日志和临时文件；
`plugins/` 保存可写扩展。操作系统管理的授权记录仍由操作系统保存。
Linux 启动 `runtime/JavaClaw/bin/JavaClaw`；此包可运行主程序与服务插件，
当前版本不提供 Linux 桌面原生能力。

## 装配前置条件

先用 JDK 25 和 Maven 构建主 JAR：

```bash
mvn --batch-mode --no-transfer-progress -DskipTests package
```

装配脚本默认读取 `target/javaclaw-<version>.jar`。macOS 和 Linux 可用
`JAVACLAW_HOST_JAR`、Windows 可用 `-HostJar` 指定其他已完成构建的主 JAR。
如果发行版还包含现有的 Deliverance 插件，须先完成其签名与
`target/distribution/plugins/` 暂存步骤；脚本会把已有的插件目录纳入外层
`plugins/`，不会自行跳过插件签名验证流程。
脚本调用 `mvn dependency:copy-dependencies`，把运行依赖放入应用镜像。
Java 源码及系统 FFM 绑定随主 JAR 构建，无单独原生编译步骤。
已有输出目录会导致失败，
避免覆盖用户的 `data/` 和 `plugins/`。

macOS 发行机需要 JDK 25 `jpackage`、系统 `codesign`／`plutil`，以及有效的
Developer ID Application 签名身份：

```bash
export CODESIGN_IDENTITY='Developer ID Application: Example (TEAMID)'
scripts/assemble-portable-macos.sh --output /absolute/path/to/JavaClaw
```

脚本把屏幕采集用途说明写入 `.app/Contents/Info.plist`，随后重新签署
`.app`，并用 `codesign --verify --strict` 检查应用与内置 Java，
正式发行拒绝临时的 ad-hoc 签名。
`codesign --timestamp` 需要能访问 Apple 时间戳服务。构建结束后，
`runtime/` 被标记为只读。

本机目录与启动器定位验证可显式使用未签名开发模式：

```bash
scripts/assemble-portable-macos.sh --development-unsigned --offline \
  --output "$PWD/target/portable/JavaClaw-dev"
```

开发模式跳过证书签名与正式验签；修改 `Info.plist` 后，脚本用临时
ad-hoc 签名保持 `.app` 完整性，产物不能作为发行包。正式装配始终要求
`CODESIGN_IDENTITY`，并验证 `.app` 与内置 Java 的 Developer ID Application 签名。
`--offline` 仅在 Maven 所有运行依赖已缓存时使用。

Windows 发行机需要 JDK 25、Maven、`signtool.exe` 和可用于代码签名的证书。
无需 MSVC、CMake 或 C++/WinRT 头文件；签名工具可独立安装。
证书指纹可以从签名证书的
`Get-ChildItem Cert:\CurrentUser\My` 输出取得：

```powershell
$env:JAVACLAW_CODESIGN_THUMBPRINT = '<40 个十六进制字符>'
scripts/assemble-portable-windows.ps1 -OutputDirectory 'C:\release\JavaClaw'
```

脚本对 JavaClaw 启动器和内置 `java.exe` 签名，用
`signtool verify /pa` 与 `Get-AuthenticodeSignature` 校验证书指纹。
其时间戳服务 URL 可通过 `-TimestampUrl` 指定；离线装配需要先准备
可访问的时间戳服务。Windows 的只读文件属性仅防止误改，不能阻止
拥有该目录写权限的用户替换文件。

Linux 可在 JDK 25 和 Maven 环境中装配：

```bash
bash scripts/assemble-portable-linux.sh --output /absolute/path/to/JavaClaw
```

所有平台显式设置 `jlink` 选项，保留服务插件隔离进程使用的 `bin/java`
（Windows 为 `bin/java.exe`），并在装配时使用插件的固定 JVM 参数执行
`-version`；若运行时或必需模块缺失，装配失败。插件不会回退到系统 Java。

## GitHub 发行工作流

`deliverance-release.yml` 从 Maven 的 `project.build.finalName` 读取主工件名，
签署 Deliverance 插件并将签名者固定到主 JAR，再为五个平台目标生成完整便携目录。
macOS 和 Windows 复用上述签名装配脚本，Linux 复用对应的 JDK 镜像装配脚本。
ZIP 包含外层 `JavaClaw/runtime/`、`JavaClaw/plugins/` 与 `JavaClaw/data/`，
macOS 和 Linux 归档保留可执行权限及运行时内部的符号链接。所有目标成功后才发布标签。

在 GitHub 的 `release` 环境中配置原有的四项
`DELIVERANCE_JARSIGNER_*` 插件签名 secrets，并增加平台代码签名 secrets：

| 平台 | Secret | 内容 |
| --- | --- | --- |
| macOS | `JAVACLAW_MACOS_CERTIFICATE_P12_BASE64` | 含私钥的 Developer ID Application PKCS#12 文件的 Base64 |
| macOS | `JAVACLAW_MACOS_CERTIFICATE_PASSWORD` | PKCS#12 密码 |
| macOS | `JAVACLAW_MACOS_CODESIGN_IDENTITY` | 完整的 `Developer ID Application: ...` 身份名称 |
| Windows | `JAVACLAW_WINDOWS_CERTIFICATE_PFX_BASE64` | 含私钥的代码签名 PFX 文件的 Base64 |
| Windows | `JAVACLAW_WINDOWS_CERTIFICATE_PASSWORD` | PFX 密码 |

工作流只在对应平台导入签名证书，完成后清理临时私钥文件、macOS 临时钥匙串和
Windows 导入的签名证书。缺少平台签名证书会阻止该平台归档，不生成开发签名发行物。

## 完整性与验收

发行脚本会拒绝插件与数据目录中的符号链接、Windows reparse point，
只允许 `jpackage` JDK 在 `runtime/` 内部解析的符号链接。桌面 FFM 适配器
加载操作系统管理的库，不从插件目录、应用数据目录、工作目录或历史开发
产物加载桌面实现。`runtime/native/` 不再是发行布局的一部分。
启动前检查目标目录可写与迁移数据格式，
格式 3 升级会在独占启动锁下清空此应用目录内的旧 `data/` 与 `plugins/`，
具体行为见 [4.0 升级说明](upgrade-4.0.md)。

上述签名检查发生在**发行装配时**。发行物仍应通过可信渠道分发，并在
最终归档前再次核对应用、内置 Java 和插件的签名与哈希。

在目标系统实机验收时，还需验证遮挡窗口预览、弹窗切换、多显示器缩放、
最小化与权限撤销、目标重启、前台接管授权、会话关闭及任意工作目录启动。
跨平台系统调用行为不能由 macOS 上的 Maven 测试代替。

## 公开后台 API 与升级

默认输入方式为 `BACKGROUND_STRICT`，仅使用当前观察控件公开的 AX/UIA
能力。设置中的“系统输入”是用户明确选择的独立策略；会话策略不可变，
不支持的控件、快捷键或坐标操作不会触发前台升级或剪贴板输入。
电脑应用访问总开关仍控制两种策略。严格后台权限只申请采集与辅助功能，
不要求 macOS Post Event 权限。

平台探测直接检查系统能力与授权；Java 中没有自定义桌面 ABI 版本或扩展
导出要求。升级只需重新构建并发布 Java 应用镜像。旧发行目录中遗留的
自建库不会被加载，也不应复制进新发行物。系统调用缺失、授权不足或平台
不支持时返回明确的不可用／不支持结果，不能回退到历史库或外部程序。

Windows 捕获通过 WinRT／COM 直接使用 Windows Graphics Capture 与 Direct3D 11，
UIA 语义操作也由 Java 直接调用公开 COM 接口。绑定的 GUID、虚表和结构布局
以微软 SDK 的 [Windows Graphics Capture 头文件](https://raw.githubusercontent.com/microsoft/win32metadata/main/generation/WinSDK/RecompiledIdlHeaders/winrt/windows.graphics.capture.h)、
[Direct3D 11 头文件](https://raw.githubusercontent.com/microsoft/win32metadata/main/generation/WinSDK/RecompiledIdlHeaders/um/d3d11.h) 和
[UI Automation 头文件](https://raw.githubusercontent.com/microsoft/win32metadata/main/generation/WinSDK/RecompiledIdlHeaders/um/UIAutomationClient.h) 为依据。
这些资料用于核验手写 Java FFM 绑定，不构成构建或运行依赖。

点击目标区域、帧时效和几何守卫由纯 Java 实现，复用已有捕获与派发接口，
不要求点击守卫扩展符号或额外 C++ 策略文件。平台绑定、参数布局、守卫和
调度通过 Java 测试验证。Java 校验与系统实际派发间
存在时间间隔，实机验收仍须覆盖期间窗口变化及部分动作的未知结果。

预览使用非焦点 Java `JWindow`，按同帧逻辑尺寸显示并在超屏时等比缩小。
手动缩放、迷你和最大化保留，恢复时采用最新目标尺寸。软件光标和状态仅
绘制在预览层。捕获未声明可靠 alpha 时，预览采用矩形；
仅未来捕获明确提供可靠透明信息且系统支持时才保留透明轮廓。
“人工输入”按钮打开 JavaFX 面板：先等待当前派发完成，再以目标级租约暂停
自动输入；提交仍需要当前观察、明确控件和插入／整值设置能力。关闭面板
使旧观察失效后恢复自动输入，未知结果屏障仍保留。

实机发行验收须分别记录 macOS 与 Windows 的以下结果：

- 用户在其他应用持续打字、移动鼠标时，后台控件操作不污染输入、不移动系统光标。
- 预览首次显示、隐藏重现、缩放、迷你、最大化和停止均保留原键盘接收窗口。
- 多屏 DPI、目标 resize、超屏缩放和透明能力降级说明正确；旧代次坐标被拒绝。
- 插入保留原值，整值设置允许清空；没有公开文本接口时明确不支持。
- 目标激活、权限撤销、超时和未知结果阻止后续输入；不能自动切换策略或重复派发。
- 关闭、跨会话重开和人工输入租约均不能绕过目标级未知输入屏障。

macOS 上的无输入契约测试与 Java 焦点测试不能替代 Windows UIA 实机验收。

### 2026-10-09 Java／FFM 迁移验证

`src/main/native`、`src/test/native` 和旧 jextract 生成绑定已移除。
窗口发现、应用目录与启动、截图、AX／UIA 语义操作及显式系统输入均由
Java 实现；只有操作系统接口通过 JDK FFM 调用。未新增运行依赖。
CSS／FXML 保留，发行用 Shell／PowerShell 脚本只负责应用镜像装配与签名，
不再编译、复制或签署自建桌面库。

本轮定向回归运行 230 项，229 项通过，1 项 Windows 实机截图测试因当前为
macOS 跳过；随后 Windows 兼容性补测 34 项全部通过，其中新增 2 项。
两轮共覆盖 232 个不同用例，231 项通过，1 项跳过。命令如下：

```bash
mvn -o -Djavaclaw.native.public.tests=true \
  '-Dtest=com.javaclaw.desktop.**.*Test,!DesktopCapabilityContextTest,!DesktopObservationConditionBindingTest,RequiredDesktopOpenDiscoveryTest,SpringAiInteractionContinuationIntegrationTest' test
mvn -o -Djavaclaw.native.public.tests=true \
  '-Dtest=WindowsBindingsTest,WindowsUiaTest,WinRtClosedHandlerTest,NativePublicApiSmokeTest,NativeDesktopSystemPermissionServiceTest' test
mvn -o -DskipTests package
```

覆盖范围包括 Java 点击区域守卫、会话关闭与目标实例绑定、语义 token
校验、投递前真实帧刷新、文本输入聚焦、部分输入的 UNKNOWN 屏障、Windows
绑定布局与版本／窗口过滤条件，以及 macOS Objective-C block 的晚到回调
与释放生命周期。macOS 只读系统 FFM 测试实际运行通过，未申请权限、
启动应用或派发真实输入。

扩大桌面回归时，`DesktopCapabilityContextTest` 的 2 项失败与
`DesktopObservationConditionBindingTest` 的 1 项失败，在恢复迁移前实现的
隔离源码快照中同样复现：前者的测试代理拒绝 `snapshotWindowTracking`，
后者的观察条件证据数量预期为 1、实际为 0。上述定向命令排除了这两个
测试类；这些结果不代表全仓测试全部通过。

主 JAR 打包成功，检查未包含旧生成绑定、自建 `.dylib`／`.dll` 或目录传输
codec。源码扫描无 C／C++／Objective-C 等文件；CSS／FXML 差异与迁移前
快照一致。macOS 与 Linux 装配脚本通过 `bash -n`，当前环境未执行
PowerShell 装配或正式发行签名。

当前工具进程无屏幕录制、辅助功能与系统输入授权，且没有可用的
WindowServer 会话；真实 macOS 截图、AX 写入及系统输入仍需授权实机验收。
当前没有 Windows 实机，WGC 捕获、UIA 写入及 SendInput 也仍需实机验收。
Windows 可显式运行 `-Djavaclaw.windows.ffm.smoke=true
-Dtest=WindowsCaptureSmokeTest`，测试只捕获自己创建的窗口，不派发输入。

### 2026-10-09 等待日志与崩溃修复

用户提供的日志先出现 `INTERACTION_CHILD_PENDING` 的三次聚合错误，十秒后
JVM 在 `MacTimer._resume` 返回路径发生 SIGBUS。前者是等待控制信号穿过
Spring AI 流式聚合边界造成的误报；已在工具 Advisor 内接收等待，在外层
聚合正常结束后交回宿主，原保留调用和子任务恢复关系继续有效。

崩溃排查发现并修复以下确定的 Java／FFM 实现缺陷：

- macOS 每次回调的 upcall target 与 Auto Arena 形成强引用循环。改为固定
  4 个进程级共享 stub，通过 block 捕获的 token 定位 Java 状态；原生最后
  一次 dispose 才释放状态与名额。超时后的晚到回调仍有效，最多同时保留
  64 个未释放 block，超过限额在新操作派发前拒绝。
- Windows `GraphicsCaptureItem.Closed` 委托也存在同类循环。改为 4 个共享
  静态 stub 和共享 vtable／GUID，按 COM self 地址查找状态；最后一次
  `Release` 移除活动引用，实例 Auto Arena 可正常回收。事件仍持引用时，
  owner 释放不能使回调失效；执行中的回调保留实例直到返回。
- 包含 Java 等待的系统操作可能使虚拟线程切换 OS 载体，破坏 autorelease
  pool 和 Windows COM apartment 的线程归属。系统 API 与 session 方法统一
  进入 4 个守护平台线程执行，普通队列容量为 64，不在调用者线程回退执行。
  未开始的操作可以取消；已经开始的操作等待唯一真实结果，保留调用者的
  中断标记。关闭和焦点恢复可靠排队，不能因为队列满或调用者中断丢失清理。

macOS pool 同时在入口拒绝虚拟线程，并核验创建与释放使用同一平台线程。
线程约束依据 [Apple 的 autorelease pool 说明](https://developer.apple.com/library/archive/documentation/Cocoa/Conceptual/Multithreading/ThreadSafetySummary/ThreadSafetySummary.html)
及 [JDK 25 虚拟线程说明](https://docs.oracle.com/en/java/javase/25/core/virtual-threads.html)。

本轮统一定向回归 259 项，258 项通过，1 项 Windows 实机捕获因当前平台
跳过；Windows 委托补丁落盘后补测 44 项全部通过，其中新增 1 项。两轮共
260 个不同用例，259 项通过，1 项跳过。仍排除上节已在迁移前快照复现
失败的两个测试类。命令为：

```bash
mvn -o -Djavaclaw.native.public.tests=true \
  '-Dtest=com.javaclaw.desktop.**.*Test,!DesktopCapabilityContextTest,!DesktopObservationConditionBindingTest,RequiredDesktopOpenDiscoveryTest,SpringAiInteractionContinuationIntegrationTest,SpringAiInteractionStreamWaitTest,SpringAiInteractionWaitLifecycleTest,InteractionDelegateCoordinatorWaitTest' test
mvn -o -Djavaclaw.native.public.tests=true \
  '-Dtest=WinRtClosedHandlerTest,WindowsBindingsTest,WindowsUiaTest,ThreadBoundDesktopApiTest,NativePublicApiSmokeTest,NativeDesktopSystemPermissionServiceTest' test
mvn -o -DskipTests package
```

最终主 JAR 打包成功，包含新的等待边界、平台线程执行器与共享回调实现；
未包含自建 `.dylib`／`.dll` 或旧 jextract 绑定。`git diff --check` 通过。

新增验证覆盖流式和同步等待的真实宿主恢复链、同批后续工具停止、真正
异常仍传播，9 项平台线程／取消／饱和清理测试，256 次 macOS block 创建
与释放、晚到回调和 64 个活动回调限额，以及 32 个 Windows 委托共享
stub／vtable 和最后一次 COM 引用释放。macOS 系统测试实际调用原生
block 和只读元数据接口；Windows 委托测试通过 FFM 调用模拟 COM 虚表，
不代表已验证 Windows 实机捕获或输入。未申请权限或派发真实输入。

上述缺陷成立，但当时的崩溃报告不足以证明它们完整解释首次 SIGBUS。故障
位置涉及 JIT 取指／执行权限，CodeCache 当时未耗尽；尚未找到匹配的官方
已确认修复，因此没有以无依据升级 JavaFX 代替修复。需要在授权的真实
GUI 环境持续截图、等待及恢复后复测进程稳定性，自动回归不能代替该验收。

### 2026-10-09 18:17 自身 AX 查询导致的 SIGBUS

后续 `hs_err_pid78069.log` 提供了更明确的触发链：后台平台线程
`JavaClaw-desktop-ffm-1` 在 `MacWindows.list → MacAccessibility.decorate →
windowCandidates → AXUIElementCopyAttributeValue` 中读取本进程窗口，AppKit
同步调用本进程 Glass 的 `View.getAccessible / MacAccessible._initIDs`，
最终在 `StubRoutines::call_stub` 入口取指崩溃。

报告当前 worker 的 JavaThread 为 `0x720d14000`，但 call_stub 的 JavaThread
参数寄存器 x7／x19 是 `0x71f39c800`，线程表中该地址属于 JavaFX Application
Thread。这与实际 JavaFX 25+29 的 [GlassViewDelegate.getAccessible](https://github.com/openjdk/jfx/blob/25%2B29/modules/javafx.graphics/src/main/native-glass/mac/GlassViewDelegate.m#L1299)
使用 [GET_MAIN_JENV](https://github.com/openjdk/jfx/blob/25%2B29/modules/javafx.graphics/src/main/native-glass/mac/GlassMacros.h#L252)
一致：宏假设调用来自 Cocoa 主线程，并使用缓存的主线程 `JNIEnv`。
[JNI 规范](https://docs.oracle.com/en/java/javase/25/docs/specs/jni/design.html#jni-interface-functions-and-pointers)
禁止跨线程使用该指针。后台线程进入此路径违反了该约束；这也为此前
JavaFX Timer 的执行权限异常提供了因果解释，但旧报告单独不能确认同一链。

这是 Java／FFM 迁移遗漏的自身进程排除规则：迁移前实现已在窗口列表及
打开会话时分别拒绝 `getpid()`。本轮恢复并加强该规则：

- 窗口元数据发现立即排除自身 PID，打开和重验目标前再次拒绝自身 PID。
- AX 观察、动作、候选树和焦点操作均拒绝自身及不可核验的 owner；底层
  属性读取在 `AXUIElementGetPid` 验证通过后才调用真正的属性接口。
- 前台原属于 JavaClaw 时，不读取自身 `AXFocusedWindow`，仍保留原应用
  焦点恢复。外部应用的 FFM 捕获和操作路径继续有效。

没有新增 JNI 桥、自建库、其他语言源码或依赖。项目桌面系统接口仍由
Java／JDK FFM 实现；JavaFX 和 JDK 内部的 JNI 属于依赖自身实现，FFM
替换项目接口不会改写这些依赖。扫描项目未发现 Java `native` 声明或
`System.load / System.loadLibrary`，JAR 不包含旧生成绑定与自建原生库。

本轮运行上节统一回归命令，263 项中 262 项通过，1 项 Windows 实机测试
因平台跳过。新增验证包括自身及非法 PID 政策、在 native API 缺席时证明
自身观察／输入／焦点／绑定均提前阻止，以及真实 FFM 创建自身 AX 对象后
验证 owner 与底层属性拒绝。测试未请求权限或派发输入；当前环境没有授权
GUI，尚未复跑用户的完整界面操作，实机稳定性仍需启动新版后确认。

随后 `mvn -o -DskipTests package` 成功，主 JAR 已包含 `MacTargetPolicy` 与
底层 AX owner 守卫；`git diff --check` 通过。

### 2026-10-09 迁移前历史验证

以下记录来自已移除的自建原生库版本，保留以说明此前覆盖与限制；它们
不能作为当前 Java／FFM 平台实现的验收结果，也不是当前发行的构建前提。

桌面会话、工具协议、策略配置、原生桥与回执的定向回归 172 项通过。
其中并发关闭覆盖 Agent 和人工派发两种路径；未知结果在人工租约释放和
会话重开后仍受保护。JavaFX/AWT 预览与人工输入 UI 18 项通过，包括
真实 JWindow 焦点与系统指针位置检查、旧代次／旧 epoch 拒绝、透明像素
渲染，以及人工面板在提交、派发和结果回传阶段关闭的保护。

macOS 14+ universal arm64/x86_64 原生库以 `-Wall -Wextra -Werror` 构建通过，
构建后的动态导出／ABI 契约检查通过；同帧几何 synthetic sample、AX snapshot
检查通过。Java→FFM→新原生库的只读 smoke 测试单独启用并通过，未请求
权限、打开目标或派发真实输入。

完整 Maven 测试仍受仓库现有失败及沙箱 socket／子进程限制影响。
对同一 HEAD 的隔离完整测试进行了对照：本次初次全量新增的三个失败用例
均为动态代理未实现新策略接口，修正后已定向通过；其余失败用例均在 HEAD
基线复现。源码卫生四类失败的违规文件列表也与 HEAD 一致，未修改阈值。

当前工具进程没有 Screen Recording／Accessibility 授权，因此真实 macOS
窗口捕获及 AX 输入验收尚未完成；本机没有 Windows SDK/MSVC，Windows
原生构建、UIA 控件操作与多屏实机验收尚未完成。两平台当前捕获均只声明
可靠矩形几何，不能把 synthetic alpha 渲染测试解读为已验证任意原生窗口轮廓。

当前 Java／FFM 实现须重新完成各目标系统的只读捕获、真实控件输入、
权限变化与焦点恢复验收；迁移前的原生编译／导出检查不再运行。
