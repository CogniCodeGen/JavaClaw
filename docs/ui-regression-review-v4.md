# JavaClaw v4 UI 回归审查报告

状态：视觉基线已建立；交互完成需同时满足独立交互回归报告
审查日期：2026-08-30
历史视觉来源：`509f1970e3a569a6da76e0525312d2b10041675e`
设计规范：[JavaClaw UI 设计基线与回归审查规范](ui-design-regression-baseline.md)
交互审查：[JavaClaw v4 交互差异矩阵与回归审查](ui-interaction-regression-v4.md)

## 1. 审查结论

本次 v4 UI 优化通过设计语言回归审查。候选界面继续使用 `509f197` 的九套主题、十份历史 CSS、
`-jc-*` 设计令牌、低饱和表面、细边框、柔和圆角和克制状态色；没有建立新的色板、字体或 Web 管理后台式外观。

信息架构、密度和响应式布局属于本次经过审阅的有意扩展：宽屏恢复三栏，标准和最小宽度使用抽屉；
12 个管理页从同一超宽表单壳改为统一骨架下的页面规格；管理入口收拢、处理进度、固定操作栏和严重级别反馈
均已恢复或补齐。视觉 Golden 只证明主题与布局稳定，不证明草稿、审批、重复提交、键盘或持久结果正确。

后续交互恢复以向后兼容方式新增四组类型化 RPC/SDK：执行摘要、原子重试分支、知识源批量统计和 Cron 预览。
这些扩展没有修改现有 DTO 构造、数据库 Schema、数据库权威边界或安全策略；旧客户端仍可继续使用原方法。

macOS/JDK 25 已生成 45 张固定夹具 Golden，覆盖九主题、主窗口三尺寸、设置浮层、12 个管理页推荐/最小尺寸，
以及加载、成功、校验错误和危险确认。基准位于
[`javaclaw-packaging/src/test/resources/visual/macos/`](../javaclaw-packaging/src/test/resources/visual/macos/)。

## 2. 原问题与优化证据

| 原问题 | v4 处理 | 审查证据 |
|---|---|---|
| 主窗口缺少处理进度与可达管理入口，宽屏留白失衡 | 宽屏使用会话栏—对话—进度三栏；标准/窄屏转为抽屉；侧栏通过单一“设置”入口向上展开完整管理浮层 | `main.fxml` 结构契约、`DesktopResponsiveContractTest`、真实菜单打开 smoke、`main.png` / `standard.png` / `min.png` |
| 会话、功能与设置导航退化为通用大卡片列表 | 会话标题强制压缩为单行，恢复状态点、短时间、分组计数和仅行内选中态；主侧栏使用历史全宽单行入口及 ContextMenu 浮层；设置恢复 210 px 分组侧栏、层级线与紧凑单行导航 | `DesktopPresentationMapperTest`、`DesktopVisualContractTest`、真实多行标题与菜单 smoke、`main.png`、`12-settings-0.png` |
| 进度与费用信息失真风险 | 从 `ThreadSnapshot` 与公开事件投影阶段、工具、文件、MCP、子任务和 Token；未知费用显示“—” | `DesktopProgressProjectorTest`；Golden 的右侧进度栏 |
| 管理页统一挤入超宽表单，操作位置漂移 | 引入 `ManagementPageSpec`；正文限宽、字段分级、左栏固定、长表单滚动、操作栏固定 | `ManagementPageSpecTest`；01–12 推荐/最小截图；smoke 的固定页脚边界断言 |
| 通用列表误用 `skill-list` | 通用对象列表统一为 `management-list`，页面专属类不再跨页面复用 | 页面 CSS 路由契约与 12 页真实窗口 smoke |
| 页面 CSS 全局加载、选择器串扰 | 全局仅加载 `chat.css`、`controls.css`、`desktop.css`；领域 CSS 仅装配到对应页面子树 | `DesktopTheme.baseStylesheets()` 与 `ManagementPageSpec.stylesheets()` 契约 |
| 只读正文使用巨大灰色 TextArea | 知识正文改为 Markdown 信息卡；项目约定改为分组摘要卡 | `03.png`、`10.png` |
| 空状态与反馈不可区分 | 空列表同时提供说明和下一步；反馈分为进行中、成功、校验错误、服务端失败 | `state-loading.png`、`state-success.png`、`state-validation-error.png` |
| 危险确认退化为普通主按钮 | 页面危险操作与确认按钮统一使用高对比陶土 danger 语义，确认文案保留具体动作 | `DesktopDialogGatewayTest`、`state-danger-confirmation.png` |
| Honey 主按钮白字和必要 hint 对比不足 | v4 适配层使用 `on-brand` 深色前景；必要说明与输入提示使用 body/muted；焦点边界加深 | `theme-honey.png`、`DesktopVisualContractTest` |
| 最小截图未等待缩放生效 | 每个目标窗口先精确校验 Stage 尺寸，再等待至少两个 JavaFX pulse；不符即失败 | `DesktopLaunchProbe`；所有 `*-min.png` 均为独立尺寸 |

## 3. 有意的信息架构扩展

### 3.1 主窗口

- `≥1180 px`：会话侧栏、对话区、处理进度同时显示。
- `960–1179 px`：保留会话侧栏，处理进度由标题栏按钮打开为右侧抽屉。
- `<960 px`：会话栏和进度栏均为可访问抽屉，中央对话不被永久挤压。
- 侧栏底部只保留单一“设置”入口；点击后以向上浮层提供设置、知识、记忆、技能、自动化、定时任务、
  插件、MCP、Agent Studio、站点、项目约定和工作树恢复，避免功能入口长期占用会话列表高度。
- 标题栏只承载响应式切换、会话状态、会话操作、主题、帮助和设置。压缩、分支、归档和删除不混入功能导航。
- 助手正文限制可读行宽；用户、助手、计划、执行、交互和错误有独立结构语义。执行块默认显示紧凑摘要，
  完整内容通过明确按钮查看。
- 输入区保持 56–240 px 自适应高度；正文、附件、运行模式选择和发送/停止操作收进同一张输入卡片，
  不再让模式表单悬在输入框下方。窄屏仍保留完整键盘与可访问操作。

恢复的当前能力快捷键为：`⌘/Ctrl+N`、`⌘/Ctrl+,`、`⌘/Ctrl+\`、`⌘/Ctrl+K`、
`⌘/Ctrl+M`、`⌘/Ctrl+/`、`Enter`、`Shift+Enter` 和 `Esc`。未恢复已经不存在的旧“清空历史”语义。

### 3.2 管理页

12 个页面共用“标题与状态—对象列表—详情摘要—分组正文—固定操作栏”骨架，但保持页面专属尺寸、
左栏宽度和领域 CSS。首次打开采用推荐尺寸，用户调整后仅在当前进程内按页面记忆，不写入服务端。

Provider 凭据、MCP 认证、站点授权和危险操作分别成组；只读内容使用信息卡或 Markdown；短字段、
数字字段和多行字段使用不同宽度层级。空列表不会只留下大块空白，也不会将空状态冒充错误。
保存 Provider 对话模型时，同步内置 Profile 及仍沿用旧默认值的同 Provider Profile；自定义模型覆盖保持不变。
主窗口随后重新读取 Profile，因此输入卡片展示的模型与下一个 Turn 的真实执行模型一致。

## 4. 关键前后对照

历史图片用于观察视觉语言，不作为逐像素 Golden；新图来自隔离固定夹具。

| 页面 | 历史参照 | v4 固定夹具 | 审查结论 |
|---|---|---|---|
| 主窗口 | [01-main-chat.png](images/screenshots/01-main-chat.png) | [main.png](../javaclaw-packaging/src/test/resources/visual/macos/main.png) | 保留品牌、侧栏、消息与输入语言；合理增加安全进度投影和响应式控制 |
| 设置 | [02-settings.png](images/screenshots/02-settings.png) | [12-settings-0.png](../javaclaw-packaging/src/test/resources/visual/macos/12-settings-0.png) | 保留 210 px 分组 Master/Detail 导航；凭据与普通配置分组 |
| 知识 | [03-knowledge-center.png](images/screenshots/03-knowledge-center.png) | [03.png](../javaclaw-packaging/src/test/resources/visual/macos/03.png) | 来源列表、详情摘要和 Markdown 正文层级清晰 |
| 记忆 | [04-memory-center.png](images/screenshots/04-memory-center.png) | [02.png](../javaclaw-packaging/src/test/resources/visual/macos/02.png) | 沿用 212 px 领域导航与卡片语义 |
| 技能 | [05-skill-center.png](images/screenshots/05-skill-center.png) | [04.png](../javaclaw-packaging/src/test/resources/visual/macos/04.png) | 通用列表不再继承技能页专属选择器 |
| 托管任务/自动化 | [06-task-center.png](images/screenshots/06-task-center.png) | [05.png](../javaclaw-packaging/src/test/resources/visual/macos/05.png) | 预算、步骤和执行操作保持领域层次，固定动作可达 |
| MCP | [07-mcp-servers.png](images/screenshots/07-mcp-servers.png) | [08.png](../javaclaw-packaging/src/test/resources/visual/macos/08.png) | 采用源码契约的 960×680，而非旧图的异常 760×650 |
| 定时任务 | [08-schedule-center.png](images/screenshots/08-schedule-center.png) | [06.png](../javaclaw-packaging/src/test/resources/visual/macos/06.png) | 保存、立即运行、历史和删除保持不同语义 |
| 插件 | [09-plugin-center.png](images/screenshots/09-plugin-center.png) | [07.png](../javaclaw-packaging/src/test/resources/visual/macos/07.png) | 空状态提供下一步；卸载保持危险操作语义 |

## 5. 主题与可访问性审查

- 十份历史 CSS 逐文件 SHA-256 固定，新增修正只位于 `desktop.css`。
- 九套主题全部由真实窗口循环切换并截图；默认仍为 Emerald。
- 普通正文和必要说明以 4.5:1 为目标；图标、焦点边界和大字以 3:1 为目标。
- Honey 品牌金色使用深色 `on-brand` 前景；Midnight/Carbon 的必要提示使用 muted/body，不使用装饰 hint。
- 所有稳定主窗口控件保留 `fx:id` 和非空 `accessibleText`；管理页 smoke 会拒绝缺少可访问名称的可见按钮。
- 加载、成功、校验错误、服务端失败和危险确认同时使用文字、位置或结构提示，不只依赖颜色。

## 6. 进度投影与安全边界

处理进度只显示服务端已公开的状态、阶段和有界摘要：工具、文件、MCP、子任务、计划、产物、交互、错误
与 Token 使用。它不展示原始事件 JSON、隐藏推理过程、Secret 或不受信任的任意样式。事件详情有长度上限，
助手正文不会重复出现在进度栏。费用在 SDK 没有可信来源时显示“—”，不会用 `0` 暗示免费。

## 7. 固定截图矩阵

| 组 | 数量 | 内容 |
|---|---:|---|
| 主题 | 9 | Emerald、Midnight、Carbon、Sapphire、Ocean、Plum、Terracotta、Honey、Graphite |
| 主窗口 | 3 | 宽屏 1200×700、标准 1100×700、最小 600×500 的窗口目标 |
| 管理页 | 28 | 前 11 页各推荐/最小两张；设置的外观、Provider、诊断三个分区各推荐/最小两张 |
| 关键状态 | 4 | 加载、成功、校验错误、危险确认 |
| 合计 | 44 | 全部由真实 Desktop、真实 SDK/App Server 链和本地合成 Provider 生成 |

macOS 的 PNG 是 Scene snapshot，因此在按 Stage 设置窗口目标后，高度不包含 28 px 系统标题栏；测试同时
精确校验 Stage 目标和 Scene 像素尺寸。会话/消息头时间及 ProgressIndicator 动画帧使用相邻 `.png.mask` 明确遮罩。

## 8. 像素门禁

门禁要求候选与基准尺寸完全一致。比较前对图像做 1 px 邻域平滑，单通道差异 `≤16` 忽略；总变化面积
`>0.5%` 或最大连续变化区域 `>0.1%` 时失败并输出差异图。macOS/JDK 25 执行硬门禁；Windows/Linux
执行相同结构契约并上传截图，不因跨平台字体栅格差异做整屏像素硬失败。

动态区域遮罩会按 1 px 平滑半径向外扩展，避免被遮罩像素在卷积后污染相邻像素；门禁生成的 `*-diff.png`
明确排除在下一次候选图扫描之外，失败后的原目录重跑不会产生输入串扰。

固定夹具使用 reduce-motion 消除微动效帧差；每个窗口尺寸在截图前将 transcript 定位到首个 Item，并将焦点统一放在
无可见样式的根节点，避免窗口激活顺序随机突出 Button 或 ComboBox；尺寸矩阵同时隐藏与页面内容无关的瞬时 WindowToast。
加载、成功、校验错误等反馈仍由独立状态截图验证；ProgressIndicator、状态 Toast、运行时消息时间，以及本地
App Server 可能在“保存中”帧内重建的三个 Provider 值文本使用局部遮罩；字段结构、反馈和其余正文仍参与比较，
不放宽全局面积或连续区域阈值。

本地生成候选基准必须显式设置 `-Djavaclaw.visual.update=true`；候选图默认写入已被 Git 忽略的
`javaclaw-packaging/target/visual/baseline-candidate/macos/`，不会改写固定 Golden。只有审查确认后额外设置
`-Djavaclaw.visual.promote=true`，或通过 `-Djavaclaw.visual.baseline=...` 明确指定目标目录，才会更新固定基准；
检测到 `CI` 环境时更新仍会直接失败。正常审查使用 `-Djavaclaw.visual.gate=true`，不允许边比较边改基准。

## 9. 验证记录

已完成并通过：

- `mvn -o spotless:apply`
- `git diff --check`
- `mvn -o spotless:check checkstyle:check`
- UI 设计契约、响应式断点、页面规格、进度投影、快捷键、危险确认和视觉比较单元测试
- Desktop 与上游依赖测试；Desktop 共 44 项测试通过
- 真实 Desktop smoke：设置浮层、九主题、12 页面、Provider 校验/保存/凭据、SDK 持久化和 45 张截图
- macOS/JDK 25 固定基准生成，以及独立候选截图的只读像素门禁
- 完整 10 模块 `mvn -o verify`：376 项执行、零失败/错误；其中 2 项平台/显式门禁条件跳过，真实 Desktop smoke 已另行启用并通过

## 10. 真实限制

- 固定夹具覆盖 macOS/JDK 25；Windows/Linux 的字体回退、窗口装饰和原生控件仍以结构门禁与上传截图审查。
- hover、pressed 和完整键盘 focus 组合不为每个页面建立独立 Golden，仍由 CSS 契约、可访问名称和场景测试约束。
- 夹具使用隔离目录和本地合成服务，不调用付费模型；它不能替代真实用户超长文档、极端本地化和系统字体替换测试。
- 本次只优化 Desktop 表现层与内部投影，不恢复旧 Runtime、Spring Context 或客户端数据读取链。
