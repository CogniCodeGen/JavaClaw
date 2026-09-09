# 聊天交互卡顿分析

日期：2026-09-08。范围：当前 macOS arm64 工作树；使用本地 JavaFX/WebKit 测试，不调用模型、不读取用户聊天。

本文保留当日诊断与滚动修复记录。随后完成的主窗口和渲染队列优化见
[2026-09-09 修复与验证](chat-interaction-lag-fix.md)。

## 已复现并修复的滚动问题

原聊天脚本在每个滚动动画帧重新挂载消息窗口、测量节点高度，并调用 `scrollBy` 校正锚点。
即使消息完全未变、校正量为零，也执行这套流程，打断 WebKit 的原生滚动动画。

使用两个同尺寸真实 WebView，投递相同的 24 次 JavaFX ScrollEvent，每次 12 像素、间隔 16ms。
待两侧停止移动 500ms 后记录结果，起点避开页面边界和虚拟窗口边界：

| 场景 | 原生基准位移 | 修复前聊天位移 | 修复后聊天位移 | 修复前消息重复挂载 | 修复后重复挂载 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 32 条消息 | 288px | 96px | 288px | 448 次 | 0 次 |
| 500 条消息，最多挂载 128 条 | 288px | 77px | 288px | 2301 次 | 0 次 |

修复前还分别发生 14、18 次 `scrollBy`；修复后为零。这是相同事件输入的位移与节点操作对照，
不代表所有设备的帧率提升倍数。

当前修改：普通滚动在缓冲区内只更新阅读状态；越过缓冲边界才调整消息窗口。
新回复到达时保留未变化消息的 DOM，只替换变化的消息；锚点没有位移时不调用 `scrollBy`。
保留系统像素位移、原生惯性和默认横向滚动，不增加全局滚轮倍率。

相关代码：[chat.js](../../javaclaw-desktop/src/main/resources/web/chat.js)。
可复现测试：[WebChatScrollResponsivenessTest](../../javaclaw-desktop/src/test/java/com/javaclaw/desktop/web/WebChatScrollResponsivenessTest.java)。
原始结果由测试写入 `javaclaw-desktop/target/acceptance/scroll-responsiveness/`。

## 整体操作还有哪些重复工作

以下是代码路径确认的开销，尚未取得它们在完整主壳上的实际耗时占比；本轮没有改写这些业务刷新链。

1. **正文更新触发整壳刷新。** 流式更新已经按 50ms 合并，最多约每秒 20 次 UI 提交。
   但 `DesktopStore.update` 对原状态也通知监听者；`DesktopShellController.render` 无条件重设工作区、
   对话和审批列表，还更新输入请求面板及 Tooltip。正文变化会产生无关的列表通知、选择维护和布局工作。
   来源：[DesktopStore](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/DesktopStore.java)、
   [DesktopShellController](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/shell/DesktopShellController.java)、
   [DesktopTurnStreamCoordinator](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/DesktopTurnStreamCoordinator.java)。

2. **隐藏的原生备用列表仍在 FX 线程完整投影。** `ShellWebSurfaces.renderNative` 每次重新建立消息行，
   新建 `CanonicalJson` 和 `TranscriptPresenter`，再处理原生列表；WebView 正常显示时也执行。
   `CanonicalJson` 构造器会创建 Jackson mapper 和模块。备用列表最终未发生变化，也已经付出了投影成本。
   输入框每次文本变化还会通过 `renderActions` 对备用转录列表执行 `scrollTo`。
   来源：[ShellWebSurfaces](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/shell/ShellWebSurfaces.java)、
   [CanonicalJson](../../javaclaw-protocol/src/main/java/com/javaclaw/protocol/CanonicalJson.java)。

3. **消息快照仍需完整构造、编码和解析。** `ChatSurface` 在后台为整个消息窗口构造投影和 JSON；
   Markdown 缓存不能消除完整快照的构造成本。宿主随后通过 FX 线程同步执行脚本，解析完整对象。
   成功投影只校验对话身份，没有检查请求版本，同一对话的过期成功结果仍可能排队并落地。
   来源：[ChatSurface](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/view/ChatSurface.java)、
   [WebSurfaceHost](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/web/WebSurfaceHost.java)。

4. **部分配置动作有重复读取，表现为等待。** 同作用域配置绑定已经幂等，不会每次流更新都请求模型目录。
   但保存后的成功回调和配置失效通知会先预览、再刷新整组目录与配置、再预览；首次连接也可能顺序刷新两轮。
   请求在后台执行，不能将其描述为 FX 线程同步等待网络。
   来源：[ChatConfigurationPresenter](../../javaclaw-desktop/src/main/java/com/javaclaw/desktop/settings/ChatConfigurationPresenter.java)。

## 展示层回放结果

在本机 JDK 25、JavaFX 26.0.2 下，通过 `scripts/replay-desktop-webview.py` 重放 10,000 条历史夹具，
缓存 500 条、最多挂载 128 条消息，以 50ms 间隔提交 120 次回复更新，并开启 JFR 采样：

| 指标 | 本次结果 |
| --- | ---: |
| 接收更新到渲染快照 P95 | 69.64ms |
| FX 队列往返 P95 | 11.38ms |
| 滚动命令到渲染快照 P95 | 60.48ms |

这说明当前夹具下的展示链能够持续处理更新，不能推导完整主窗口的输入延迟。
夹具频繁获取快照，且包含 JFR 开销；不将它的 CPU、内存或启动时间当成正常使用时的性能基准。
JFR 确实采到 `WebSurfaceHost.applySnapshot → WebEngine.executeScript`、WebKit 渲染及后台 JSON 编码，
同时渲染线程有大量 `nReadPixelsInt` 快照读回样本。采样支持这些路径实际执行，
不足以归因完整应用的耗时占比，也不能将夹具快照造成的负担算作日常交互瓶颈。

## 下一步处理顺序与验证边界

优先按子状态变化更新主壳，缓存原生备用投影及 JSON 解析器，把输入按钮更新与消息滚动分开。
随后过滤过期展示结果、复用未变化消息投影，再合并配置成功回执和失效通知，保留真正外部修改的补刷新。

已有的流式单槽、宿主 50ms 提交、相同 payload 去重和等待 ack 应继续保留。
没有证据表明输入监听同步访问模型或磁盘，也不能把现象描述成每个 token 都无限追加 UI 任务。

现有 `WebSurfaceReplayBenchmark` 直接从 `ChatSurface.show` 开始，绕过 Store、整壳和备用列表。
它可以检查展示层响应，不能证明整个聊天界面已经消除卡顿。
完整主壳后续应分别重放空闲输入、100/500 条历史、20Hz 流式更新，记录输入延迟、FX 队列延迟、
各列表变更次数及投影耗时。Windows/Linux 和真实鼠标、触控板的全部平台组合尚未验证。

## 本轮交付检查

- 已执行 `mvn spotless:apply` 并检查修改；全仓库 `mvn spotless:check checkstyle:check` 通过。
- `mvn install -rf :javaclaw-desktop` 通过：Desktop 490 项测试、1 项 JavaFX Golden 集成测试、
  Packaging 89 项测试（2 项平台条件跳过），覆盖率与打包检查通过。
- `git diff --check` 通过；构建资源中的 `chat.js` 与源码一致。
- 本轮重跑 Desktop 与 Packaging 的完整验证阶段，未重跑全仓库服务端测试；未调用付费模型。
