# JavaClaw 5 UI 设计系统

Desktop 沿用提交 `509f197` 的布局比例、CSS token 与交互语言。该提交只作为视觉基线；运行时状态、数据读取和
业务调用全部来自 Java SDK，不复用其中的服务或持久化实现。

## 设计原则

- 主窗口保持左侧导航、中央 Transcript、底部输入区和按需右侧交互区的层次。
- 颜色、圆角、边框、阴影与文字层级使用 `design-tokens-controls.css` 中的 `-jc-*` token，不在 Java 代码硬编码颜色。
- hover、selected、focused、disabled、success、warning、danger 都必须有可辨识状态。
- Controller 只转发 UI 事件；连接、导航、Thread、Transcript 和 Interaction 状态由不可变 state record 表达。
- Desktop 不推测服务端状态，也不为缺失事件生成 fallback；断线时明确显示连接状态并由 SDK 重连。
- 连接失败使用平台错误卡，不直接显示 `Connection refused` 等传输层文本；卡片只提供重新连接、打开脱敏诊断和
  由 launcher 能力决定是否可用的启动服务动作。IDEA 直接运行时必须解释启动按钮不可用的原因。

## 资源分层

| 资源 | 职责 |
|---|---|
| `design-tokens-controls.css` | 全局 token 与 TextField、Choice/ComboBox、CheckBox 等原生控件基线 |
| `navigation.css`、`chat-surface.css`、`interaction-overlays.css` | 当前主壳实际使用的 509f197 会话视觉 |
| `settings-extensions.css`、`design-system-components.css` | 设置页通用字段、卡片、开关行与反馈组件 |
| `themes-shell.css`、`management-center.css` | 九主题壳和设置中心布局，不包含领域专属规则 |
| `desktop.css` | SDK 壳、Transcript、导航、审批区与 ViewSchema 通用控件 |
| `main.fxml` | Desktop 壳的静态节点结构，不承载业务对象 |
| `PlatformComponentFactory` | 动态页面共享的页面、卡片、动作、反馈与虚拟化列表行组件 |
| `ManagementPageShell` | 单实例设置中心的导航与内容壳 |
| `FormSection` / `ListDetailPane` | 强类型管理页的表单及主从结构 |
| `AsyncActionBar` / `RevisionConflictPane` | 异步反馈、dirty 与 revision 冲突处理 |
| `SecretStatusField` / `DangerZone` / `ExecutionTimeline` | Secret 元数据、危险确认与执行过程展示 |

`DesktopStylesheets.BASELINE_RESOURCES` 按固定级联顺序保留当前界面仍会命中的 `509f197` 视觉规则。审计已删除没有
任何生产根节点的旧 Knowledge、MCP、Task、Attachment、Markdown/Loop 和 Conversation 领域分片；历史外观由 Git
提供，不把不可达 selector 当作视觉基线继续加载。5.0 新增页面不得再增加领域专属 CSS；内置与第三方扩展共享平台
renderer 和同一组通用视觉语义，只有平台控件确实出现新的跨扩展语义时才增加 token。

## 组件复用边界

- FXML 静态壳使用设计系统已有的 `sidebar-*`、`chat-*`、`jc-btn-*` 和 `modal-*` 语义，不写 inline style。
- Java 代码动态创建页面时必须通过 `PlatformComponentFactory` 组合页面、卡片、按钮、反馈和列表行；不得散落复制
  padding、圆角、颜色或字体层级。
- 新页面优先组合标准 JavaFX 控件与平台组件。只有两个以上页面存在相同交互语义且生命周期明确时，才新增自定义组件。
- 组件只持有展示状态和 JavaFX 事件，不访问 SDK、Repository 或 App Server；页面 Presenter 负责把用户意图转成 SDK 调用。
- 按钮只能选择 Primary、Soft、Ghost、Danger 四种强调级别以及 Normal、Compact 两种尺寸。危险动作不得复用主按钮样式。
- 加载、空数据和失败使用统一 Feedback 组件，说明必须简短、可行动，不直接显示堆栈或底层传输细节。
- `ComboBox` 选项由独立 `PopupWindow` 承载；弹层必须使用不透明 `-jc-surface-card`，不得继承普通虚拟列表的
  透明表面，否则会让宿主页面控件穿透显示。该规则统一放在 `interaction-overlays.css`，页面不得各自覆盖。
- `CheckBox` 的 box、check、文字、focus、hover 与 disabled 状态由全局控件基线统一提供；带说明的布尔设置复用
  `.row-toggle`、`.rt-main` 与 `.rt-sub`，页面不得以局部 CSS 重新实现。

## ViewSchema

扩展 UI 只接受 `schemaVersion=2` 的受限 `ViewSchema`。允许的节点为 Form、List、Table、Card、Progress、
Timeline、Markdown、Code、Artifact 和平台安全 Graph。Renderer 必须执行节点数量、文本长度、字段名、命令名、
引用关系和 URL 策略校验。

扩展不能注入 FXML、CSS、Java Controller、JavaScript 或本地 URL。Action 只能生成声明过的
`extension/command`，数据读取只能使用声明过的 `extension/query`。未知节点或超出限制的 schema 整体拒绝，
不能部分执行。

数据读取统一使用声明的 `ViewDataSource`、`ViewQueryRequest` 和 `ViewQueryResult`，由平台负责分页、选择、刷新、
请求 epoch 和取消。表单使用显式初值绑定、有限类型校验、动态选项和等值条件显示；写操作必须携带权威
`expectedRevision`，revision 冲突时保留草稿，并只允许用户显式重新加载或继续编辑。

扩展页面嵌入单实例管理中心的固定左导航、右内容和底部动作栏骨架。全部节点只映射到平台组件，不允许扩展
指定颜色、字体、间距或按钮强调级别。Graph 的节点与边只能来自声明数据，禁止脚本、表达式、自定义布局代码和事件处理器。

当前生产导航共有 29 个入口，全部接入强类型 SDK 页面或 ViewSchema v2 权威数据源，不使用“即将支持”占位页。
`extension/event` 只作为失效信号；页面收到匹配资源的 revision 后合并重复刷新并重新查询，不能把通知内容当作
领域状态。存在 dirty 草稿时，后台刷新只能进入 revision conflict 状态，不能覆盖用户输入。

## 交互与可访问性

- destructive action 使用 danger 样式并要求明确确认；审批和输入等待不与普通通知混在一起。
- 流式 Item 按 sequence 稳定排序；同一 Item 的 delta 原位更新，终态后不接受迟到 delta。
- 键盘焦点顺序遵循视觉顺序；图标按钮必须有可读文本或 accessible text。
- 文本和状态色不得成为唯一信息载体；状态同时使用文字、图标或形状。
- 长列表使用 JavaFX 虚拟化控件；大段 Markdown、代码和 Artifact 按需渲染。

## 视觉验收

设置与管理中心已有一组可复现的 JavaFX Golden：直接打开生产 `ManagementCenterWindow`、完整导航、
`AppearanceSettingsPage`、共享组件和完整 CSS 级联，不使用绘图 mock。当前生产目录有 29 个入口；该数量由测试从
真实导航读取并锁定，不能用手写示意列表冒充。Desktop 自动测试覆盖壳、Presenter、renderer、页面状态、
通知刷新和 Golden 生成。视觉矩阵覆盖九主题、紧凑/标准/舒展三密度以及
880×620 最小窗口和 1040×720 标准窗口，共 54 张图；渲染字号固定为 100%，页面内同时展示四个合法字号档位。
当前仓库只保存
`docs/images/screenshots/settings-center/macos-reference` 下的 macOS 参考图；清单记录每张图的尺寸、SHA-256 和字节数。
清单同时固定生产 Scene 标识和 29 个管理入口数量。这组证据不代表 Linux 或 Windows 已完成人工视觉验收。

常规 `verify` 会在 `javaclaw-desktop/target/ui-golden/<platform>` 生成当前平台的完整矩阵；macOS 会逐文件比较本次
PNG 与仓库参考图，同时校验文件名、数量、尺寸和清单摘要，但不会修改仓库文件。原生 CI Runner 分平台上传该目录
供人工审阅；由于字体栅格化和平台控件像素存在差异，不对不同操作系统做错误的逐像素等同断言。

管理中心的最小交互验收矩阵如下；像素 Golden 负责布局与主题，状态机测试负责不能靠静态截图证明的行为：

| 状态 | 证据 |
|---|---|
| loading / empty / error | `PlatformComponentFactoryTest` 与各 Presenter 失败、空目录测试 |
| dirty / 离页拦截 | `ManagementCenterWindowTest` 与各页面 Presenter 草稿测试 |
| revision conflict / 保留草稿 | Provider、Bundle、内置扩展和 ViewSchema 页面测试 |
| 断线 / 重试 / 打开诊断 | 真实 `main.fxml` 的 `DesktopShellControllerTest` |
| 九主题 / 三密度 / 最小与标准窗口 | 54 张生产设置中心 Golden |
| 下拉弹层 / 宿主页面遮挡 / 选项行边界 | `ComboBoxPopupStyleTest` 的九主题、四字号、三密度与四类真实 PopupWindow 测试 |

只有在 macOS 有可用图形会话时，才允许显式更新仓库参考图：

```bash
mvn -pl javaclaw-desktop -am -Djavaclaw.update.ui.golden=true verify
```

更新后必须检查 54 张图和 `manifest.txt` 的实际 diff，再提交资源。未设置
`javaclaw.update.ui.golden=true` 时测试会断言参考清单没有被改写。这 54 张只证明设置中心外观矩阵；主聊天、审批和
断线恢复等独立生产 Scene 仍需各自的目标平台参考集，不能由设置中心截图或静态 mock 替代。
