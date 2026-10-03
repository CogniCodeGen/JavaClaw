# 后台桌面会话

JavaClaw 使用目标绑定的桌面会话观察和操作其他应用。首版平台是 Windows 11 x64 与 macOS 14+ arm64/x64。Linux 返回明确的不可用原因。悬浮窗只订阅会话帧与状态，Agent 只调用标准服务 API；两者都不接触平台句柄。

## 分层与装配

- `com.javaclaw.desktop.api.DesktopSessionService`：发现目标、打开会话、按需截图、异步操作、前台接管、帧、状态与操作事件订阅，以及关闭作用域或工作区。`actions(owner, sessionId)` 受同一会话归属约束，事件仅含动作种类、开始／结束、结果、窗口代次与时间戳；不含输入文本、键名、坐标或原生句柄。
- `com.javaclaw.desktop.spi.DesktopPlatformProvider`：平台能力探测、目标发现、创建原生会话。Spring 根配置注册 Windows 和 macOS Provider，服务按探测结果选择可用实现。
- `com.javaclaw.desktop.nativebridge`：从 `ApplicationHome/runtime/native/<platform>/` 中固定的绝对路径加载库，通过版本化 `desktop_bridge.h` 与 jextract 25 生成的 FFM 绑定调用。源码开发可从同一应用根内的 `target/native/<platform>/` 加载。
- `com.javaclaw.desktop.agent.DesktopSessionTools`：新 Agent 工具；旧 `desktop_probe`、`desktop_capture`、`desktop_click` 等前台 Computer Use 工具及 CLI 适配器已删除。`sys_*` 截图和键鼠工具保留原有行为，不受新开关放宽。
- `desktop_session_observe`：从所属会话读取最新实时帧，直接在内存中交给运行作用域内的视觉模型；返回原帧尺寸、窗口代次、内容修订、`observationId`、有界辅助功能元素及视觉目标。不接受任意图片路径。视觉识别失败时不会提交本次观察，也不会解除结果未知的输入门禁。`desktop_session_snapshot` 仍可把图片保存到应用内目录供用户查看。
- `com.javaclaw.ui.javafx.desktop.DesktopPreviewWindow`：置顶预览窗，显示当前应用／弹窗标题、最近操作及结果、暂停原因、停止与前台接管入口。静态结构采用 FXML，颜色复用应用主题令牌；窗口支持普通、最大化和迷你悬浮三态。原始帧在 FX 线程外按视口尺寸及屏幕像素密度缩放，只保留最新帧并限制刷新频率；调整尺寸时即使没有新帧也会重新缩放，暂停或关闭时清除缓存。多个窗口错位排布，前台输入前暂时隐藏预览，避免挡住目标。

后续平台只需实现 Provider SPI 并注册 Bean；公开 API、Agent 编排和 JavaFX 预览不需要平台分支。

## 悬浮预览操作

标题栏的减号切换为保留实时缩略图的迷你悬浮窗，再次点击还原；方框按钮在当前屏幕可用区域最大化，再次点击恢复普通尺寸。双击标题栏或画面也可切换最大化，Escape 恢复普通窗口，系统快捷修饰键加 M 切换迷你状态。拖动标题栏移动窗口，普通状态下拖动右下角调整尺寸。迷你状态保留会话状态和关闭入口；关闭按钮与“停止”都会结束该桌面会话。

## 会话规则

1. “通用设置 → 电脑应用访问”默认关闭。开启时会检查并请求系统权限；未就绪则保持关闭。macOS 请求屏幕录制并提示开启辅助功能权限；Windows 检查 WGC 与输入能力，该平台没有统一的应用授权弹窗。保存开关后，智能体可直接使用 `desktop_session_*`，包括前台接管；手动审核模式也不再逐工具弹窗。会话服务仍在每次观察和输入前检查开关及系统权限。`sys_*` 维持原有审核。关闭开关会阻止新操作并释放现有会话；运行中撤销系统权限会暂停会话并清除最新帧。
2. 目标发现返回不含 HWND/原生指针的 ID。ID 包含进程创建实例；原生打开时再次核对，若进程在发现后重启或 PID/窗口 ID 被复用，则要求重新发现。无法读取进程实例的窗口不参与发现。会话仍绑定工作区、会话作用域和运行来源。
3. 每次动作须带本会话最近一次已提交的 `observationId` 和窗口代次。辅助功能元素及视觉目标均有观察专属的目标 ID；服务核对窗口、目标区域与周边像素、元素能力及坐标边界，允许目标区域外的动画变化，并把最新修订号交给原生层复核。令牌只供一次动作使用。旧持久化调用缺少观察 ID 时只要求重新观察，不会重放输入。截图中的控件文字不作为可信指令或权限来源。
4. 后台优先调用 UI Automation／Accessibility 语义操作。Windows 的定向 `PostMessage` 仅用于可识别的经典 Button；macOS 不宣称任意后台 `CGEventPostToPid` 可用。`UNSUPPORTED` 仅用于确定没有派发输入的情形。获得会话控制授权且权限齐备时，服务自动准备前台模式并要求重新观察，无需模型选择接管工具。
5. 前台动作使用已开启的设置授权。Windows `SendInput` 与 macOS CoreGraphics 发事件前均核对目标窗口实际获得输入且目标坐标没有被其他窗口遮挡。按位置聚焦输入框、滚动区域；动作结束后仅在用户未主动切走时恢复先前焦点。输入接口成功不等于业务效果成功，后者须由新的画面观察确认。
6. 输入是否完整派发无法确定时，返回 `UNKNOWN / MAYBE_SENT`，暂时停止该目标后续输入。原生调用结束并经过稳定时间后，同一实际目标的新画面须成功完成视觉解释与观察提交；完整宿主调用与收据再建立后续输入基线。框架 `RunControl`、持久 `JdbcRunStore`、`ManagedSession` 与 Cursor 均检查新基线，允许基于该新 `observationId` 决定后续动作。原业务效果仍为未知，`SATISFIED` 仍须严格验收；旧帧、无效或外来观察、未配对收据及展示文字不能解除保护。重新打开仅恢复句柄，Agent 应先观察当前状态，不得盲目重复原逻辑动作。双击部分派发和输入接口异常同样按未知处理。后台文本语义写入可能替换控件的全部当前值。
7. 采集方只保留最新帧，帧订阅者每人最多积压一帧；操作事件订阅者有固定上限的队列。最小化、不可见、失帧或权限撤销会清除实时画面并标记暂停，迟到的旧帧不能在恢复时重新显示。工作区切换、会话删除或应用退出会关闭原生会话并取消悬浮窗订阅。

## macOS 控件定位与失败恢复

辅助功能目录按能力采集，不依赖具体应用名。树稀疏时会探测目标是否支持
`AXManualAccessibility`；每个进程实例只尝试开启一次，并等待树生成。
开启前的能力探测若遇到暂时的通信或权限错误，后续观察可在退避后限次
重新探测；确定不支持的结果及已尝试开启的结果会保留。
目录遍历有节点、时间和输出预算，优先保留可操作控件；观察结果包含遍历
计数、截断情况和原始 AX 错误，不包含输入框当前值。

后台单次左键点击优先使用观察中的 AX 元素令牌，执行前复核进程实例、
目标窗口、控件角色、动作能力、位置和目标区域像素。重新读取目录会使旧
元素令牌失效。目标区域之外的动画不必使控件失效；目标变化则要求重新观察。
其他手势和视觉定位使用各自的能力检查与输入路径；当前原生接口为 ABI v6。

前台准备分别确认目标窗口和应用焦点，鼠标事件派发前在实际点击点检查
遮挡。窗口中央被遮挡不直接代表目标控件被遮挡；真实点击点不属于目标
窗口时，失败诊断会指出遮挡窗口的进程、窗口 ID 和层级。后台路径失败
与前台准备失败的原因会一同保存，以便区分权限、AX 通信、能力和窗口问题。

启动成功或启动结果未知后，恢复流程先调用 `desktop_session_targets` 发现
窗口，再打开或复用会话并观察。跨 Run 继承未知输入且缺少当前句柄时，
同样按 `targets → open → observe` 建立新基线，不清空历史记录。观察原始数据、
收据与后续输入须匹配当前会话、目标、观察 ID 和窗口代次，采集时间须晚于旧输入。
重新启动应用不能解决点击遮挡；启动保护、输入基线及业务效果核销由运行框架分别处理。

## 应用目录与发行

便携应用根包含只读签名 `runtime/`、可写 `plugins/` 和 `data/`。数据、截图、日志、Playwright 资产及临时文件都在该根下；旧数据和插件经暂存、内容校验后复制迁移，原件保留。详情见 [便携应用目录](portable-layout.md)。

构建和签名方式见 [原生 ABI 说明](../src/main/native/README.md) 与 `scripts/assemble-portable-macos.sh`、`scripts/assemble-portable-windows.ps1`。生产版缺少原生库、ABI 不兼容或缺少平台权限时，服务会报告不可用，不会从 `plugins/`、工作目录或系统临时目录加载替代库。

## 验证

`DesktopSessionServiceTest` 使用模拟 Provider 验证授权、观察令牌、自动前台准备、未知输入后建立新基线、旧帧重试阻断与最新帧背压；`DesktopBridgeLayoutTest` 对齐 ABI v6 结构布局与系统界面标志。macOS 原生库可在本机编译。Windows 原生构建和下列实机行为需在 Windows 11 验证：遮挡窗口预览、弹窗切换、最小化、多屏缩放、权限变化、目标重启、前台输入与焦点恢复。macOS 的实时录屏及自绘界面的输入行为需在获屏幕录制与辅助功能权限后验证。

这些平台限制来自 [Windows WGC](https://learn.microsoft.com/en-us/windows/win32/api/windows.graphics.capture.interop/nf-windows-graphics-capture-interop-igraphicscaptureiteminterop-createforwindow)、[PostMessage](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-postmessagew)、[ScreenCaptureKit](https://developer.apple.com/videos/play/wwdc2022/10155/) 与 [CGEventPostToPid](https://developer.apple.com/documentation/coregraphics/cgevent/posttopid%28_%3A%29) 的接口边界。
