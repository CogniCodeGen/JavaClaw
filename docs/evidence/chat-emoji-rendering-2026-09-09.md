# 聊天彩色表情渲染修复

2026-09-09，macOS arm64，JDK 25、JavaFX 26.0.2。

## 原因与修复

用户截图中，中文后的笑脸等彩色字符被绘制成灰色横竖条。相同 Unicode 在本机 WebView 对照中复现，
字形分段后普通 emoji 恢复，但肤色、国旗、键帽和 ZWJ 家庭仍不能正确组合。系统 Java2D TextLayout
能够对完整字素进行正确绘制。JavaFX 源码中的彩色 glyph 分段和普通/彩色纹理路径是相关原因；
仅追加 CSS 字体或设置 text-rendering 无法解决本机全部测试样本。

聊天和文档在 macOS 上对完整 emoji 字素生成系统字体透明 PNG，并作为局部背景绘制。原始字符仍位于
DOM 的文字节点中，消息、Markdown 输入、链接目标和服务端数据均不改写；所有处理在本机进行。
位图只覆盖绘制，透明原文节点保留选择与复制语义，选区由外层提供可见反馈。

原生字体初始化、排版和 PNG 编码在后台完成，编码显式使用内存流，避免 ImageIO 默认创建临时磁盘缓存。
每页同时只处理一个批次，每批最多32个字素；每簇最多64个
UTF-16 单元，原生画布最多512×192像素，按64px字体生成后使用 em 随正文缩放。Java 和页面缓存各自限制为
128项、2MiB保守字符串字节预算；这不是含 DOM 背景和 WebKit 纹理在内的整页内存上限。
仅接近视口的未缓存字形发起请求，没有空闲轮询；失败回退到原文字体。
回执只对同一 WebView 代次有效，可在同一页面的消息版本之间复用，不能触发业务动作。

流式文本只重新分段上一片段末簇与新增字符，普通连续文本合并为一次 DOM 写入。截断保留稳定前缀节点；
跨已解析 Markdown 与新尾部的字素续接会提前重新解析，避免将代理对、肤色或 ZWJ 拆入不同容器。
异步字形尺寸改变会重新测量相关消息，并保留当前阅读锚点。

## 验证

专项测试覆盖中文混排、笑脸、书本、庆祝、勾选、警告、家庭、肤色、国旗、键帽，以及逐 UTF-16 单元输入、
消息截断、跨消息反向选区、代码横向滚动、Markdown 和源码预览。像素测试检查字形区域真实存在彩色像素，
不以 DOM 字符正确代替绘制验收。缓存测试核验淘汰与实际字节上限。

执行 `mvn -pl javaclaw-desktop spotless:apply`，检查实际 diff；最终
`mvn -pl javaclaw-desktop spotless:check checkstyle:check verify` 通过，包含596项测试及1项独立
JavaFX Golden集成测试，零失败、零错误、零跳过，依赖与覆盖率门禁通过。新增原生字形测试4项、
WebView测试5项，以及Markdown流式字素边界回归。JavaScript语法检查与 `git diff --check` 通过。
另以 `com.javaclaw.desktop` 命名模块启动，重验聊天浅色/深色、逐UTF-16输入、文档Markdown和代码绘制，
输出 `namedModule=true` 并通过。真实WebView的Cmd+C复制保持完整Unicode，测试结束恢复原剪贴板各格式。
本轮只重新执行受影响Desktop模块的完整验证，未把此前全仓库结果计入本轮通过数量。

原生截图：[浅色聊天](chat-emoji-rendering-2026-09-09/chat-light.png)、
[深色聊天](chat-emoji-rendering-2026-09-09/chat-dark.png)、
[Markdown文档](chat-emoji-rendering-2026-09-09/document-markdown.png)、
[代码预览](chat-emoji-rendering-2026-09-09/document-code.png)。
[验证汇总](chat-emoji-rendering-2026-09-09/validation.json)。

使用现有真实主Scene回放夹具，分别执行 `scripts/replay-chat-performance.py` 的 `short-idle` 与
`history-500-60hz` 场景；按生产模块方式运行，采样时不截图、不并行运行Maven。
两组均完整保留30次中文输入，未出现超过100ms的活动Pulse间隔。

| 场景 | 输入入队至布局P95 | 最大Pulse间隔 | 活动CPU（单核100%） |
| --- | ---: | ---: | ---: |
| [2条消息，回复结束](chat-emoji-rendering-2026-09-09/short-idle.json) | 9.42ms | 12.02ms | 27.5% |
| [500条消息，60Hz更新](chat-emoji-rendering-2026-09-09/history-500-60hz.json) | 18.95ms | 33.82ms | 98.5% |

此前同机已优化版本对应输入P95为9.30ms和18.95ms；本次响应仍满足50ms/100ms预算。
压力场景活动CPU从此前75.2%升至98.5%，单轮回放存在波动，不能据此宣称CPU改善或证明完全没有性能回归。
这些普通正文回放只检查通用展示路径，不能替代表情密集负载或真实用户会话的长期测量。
字形专项测试另检查实际彩色像素、选区、复制和流式组合，不把DOM字符串正确当成显示正确。

## 范围

- 兼容处理只在 macOS 启用；Windows/Linux 保持原有字体绘制，本轮未做其原生像素验收。
- 超长字素、单页大量不同表情、缺少系统支持的字形保留字体回退，不删除原文。
  页面单次处理与刷新最多4096个表情节点；超过预算的节点可能继续使用系统字体。
- 这是聊天/文档的字符显示修复，不是“点击发送到消息出现”的延迟修复，也不代表后者已验收。
- 未调用模型、未访问用户聊天数据库，未引入网络字体、远程图片或额外依赖。
