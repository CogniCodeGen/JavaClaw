# JavaClaw 6 UI 设计系统

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
提供，不把不可达 selector 当作视觉基线继续加载。6.0 新增页面不得再增加领域专属 CSS；内置与第三方扩展共享平台
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

`ViewRenderSession` 的可选 `ViewRenderLayout` 仅由 Desktop 平台使用，按节点 ID 组合已经生成的控件；默认页面
继续使用平铺布局。网站会话页固定使用管理视图，上方为有高度上限的网站列表，下方为互斥的 `TitledPane` 分区。
折叠不重建表单，页面关闭时统一清理隐藏节点的订阅和临时输入。此接口不扩展 ViewSchema wire 节点，也不绕过
字段校验、动作绑定、SDK 或权限检查。网站、分页、账号与分区切换共享草稿确认和保存锁；表单来源在本地保留，
防止一处提交后的刷新覆盖其他表单草稿。

当前生产导航共有 29 个入口，全部接入强类型 SDK 页面或 ViewSchema v2 权威数据源，不使用“即将支持”占位页。
`extension/event` 只作为失效信号；页面收到匹配资源的 revision 后合并重复刷新并重新查询，不能把通知内容当作
领域状态。存在 dirty 草稿时，后台刷新只能进入 revision conflict 状态，不能覆盖用户输入。

## 交互与可访问性

- destructive action 使用 danger 样式并要求明确确认；审批和输入等待不与普通通知混在一起。
- 流式 Item 按 sequence 稳定排序；同一 Item 的 delta 原位更新，终态后不接受迟到 delta。
- 键盘焦点顺序遵循视觉顺序；图标按钮必须有可读文本或 accessible text。
- 文本和状态色不得成为唯一信息载体；状态同时使用文字、图标或形状。
- 长列表使用 JavaFX 虚拟化控件；大段 Markdown、代码和 Artifact 按需渲染。

## 模型配置到聊天

点击“新建对话”直接创建空对话并聚焦输入框，不弹出标题输入框。空对话暂用“新对话”，首条用户消息保存时
从正文提取简短标题并持久化，不额外调用模型；已有自定义标题、子任务标题与后续消息均不触发覆盖。
标题刷新不等待回复完成，也不阻塞发送确认；切换对话后的迟到结果不改变当前页面。

主聊天优先给正文留出空间。右侧待处理区默认收起，顶部入口保留待处理数量；新请求可自动展开但不移动输入焦点，
用户关闭后同一批请求的普通刷新不重复展开。阅读文档时用数量提示新请求，不切走文档；自动展开的面板在事项处理
完成后收起，输入请求读取失败仍须提供可见、可重试的反馈。窗口宽度小于 1200 逻辑像素时两侧区域互斥，恢复宽度
或关闭右侧后恢复用户原有导航偏好。

输入框按两行起步，随正文增长至 240 逻辑像素后内部滚动。空配置反馈行不参与布局；焦点边框、会话选中态与
连接、等待、执行、失败状态均复用现有 token。Enter 发送，Shift+Enter 换行，Ctrl/Cmd+Enter 仍可发送；组合输入尚未提交时
不能触发发送。Escape 优先关闭当前弹层，其后关闭右侧区域并恢复触发焦点，不代表停止任务或拒绝审批。

聊天正文使用有界行增量，最多保留 500 行逻辑缓存与 128 行 DOM 窗口。增量基于实际提交到 DOM 的版本计算，
不是后台最近一次计算版本；动作和精确引用映射绑定该提交版本，显示确认只控制背压。切换作用域、WebKit 恢复
或基线失配时发送完整展示快照，不能重发业务操作。消息节点与普通流式文本尾部保持原位，字体、密度及宽度变化
使实际高度缓存失效；代码高亮按可见范围分帧执行，保留选区及横向阅读位置。

WebView 宿主只在内容、主题、可见性、加载和确认超时发生变化时调度，空闲与隐藏页面不保留 50ms 轮询。
隐藏的备用列表和反馈卡退出布局，正在加载的 WebView 保留显示确认所需尺寸。主聊天性能回放使用
`scripts/replay-chat-performance.py`，真实 FXML、Presenter 与 SDK 连接内存 RPC，不调用模型或写用户偏好；
持续测量与截图分开，事件至布局延迟不得表述为操作系统光学可见延迟。

聊天输入区常驻模型、思考强度和“更多”，发送与停止由 Turn 状态互斥显示。模型菜单支持模型及服务名搜索，
提供添加、管理和恢复项目设置入口；Agent、权限与配置来源位于“更多”。模型名称和 Agent 锁定状态来自服务端
执行预览，普通目录刷新不推进对话中保存的精确 Provider revision。

新建、编辑和补全服务共用设置页内的单页编辑器；聊天“添加”也定位到同一编辑器。已有服务先显示摘要，点击
“编辑”后原位修改；新建直接进入草稿。常用服务商预设只填写名称、协议、地址与鉴权方式，不发请求、不创建服务，
不预填模型能力。百炼必须显式选择地域，业务空间及其他地域使用控制台完整 Base URL，不猜测路径。
接口协议始终可见；请求高级参数按协议显示。地址旁提供与实际 SDK 一致的最终请求地址预览，字段校验失败时定位输入。
编辑态隐藏重复页面标题及摘要工具栏；新建时清除服务列表中的旧选择，顶部选择器显示“正在添加新服务”，避免误认正在修改旧服务。

服务浏览区域不足 900 逻辑像素时由侧栏切换为顶部搜索和选择器，不提高设置窗口的 880×620 最小尺寸。
模型区域使用虚拟化列表；复选框决定保存对象，行选择决定当前属性编辑对象，取消额外的已选模型下拉框。
工具栏包含搜索、全部／已选筛选、获取模型和手动添加；详情包含用途、图片声明及向量维度，未知用途必须明确指定。
模型区域不足 760 逻辑像素时详情移至列表下方的独立滚动视口，保留至少 110 逻辑像素的目录高度；聚焦详情字段时自动滚动使其可见。
这些阈值针对扣除导航后的可用区域。搜索、刷新、补填密钥保留选择和属性。模型计数与批量入口位于“模型管理”分区标题，
启用开关位于“连接设置”分区标题，折叠内容时仍可读取摘要。手动输入必须确认添加；手动添加与批量用途互斥展开。
模型区高度不足 360 逻辑像素时，辅助编辑暂时使用目录所占空间；隐藏目录不销毁选择、详情或待确认输入，完成后恢复目录。
目录成功、用户能力声明与调用验证分别表达，不统一标成“可用”。

`ManagedSettingsPage.ownsViewport()` 默认 `false`，普通页面继续由管理中心包装滚动容器；模型页返回 `true`，
独立分配连接表单和虚拟化目录的视口，固定底栏承载“放弃修改”和“保存配置”。不允许把整个模型页再放入外层 ScrollPane。
高度有限时连接与模型分区互斥展开，折叠不重建草稿。键盘可通过空格勾选目录行、Enter 确认手动模型；错误恢复要展开目标区，
恢复焦点并滚动到字段，不能只对隐藏控件调用 requestFocus。

“保存配置”一次提交连接、凭据、模型及启停状态；新建默认启用，编辑保留原状态。保存成功留在当前服务，显示已保存模型数，
刷新权威数据并清除 dirty 状态。保存失败保留非敏感草稿；临时密钥清除后在原页补填，目录刷新及迟到目录不能覆盖保存错误。
反馈正文最多三行，按需展开的“诊断分类”只显示稳定错误码或脱敏分类，正文与 Tooltip 均不得展示异常原文、Java 类型或堆栈。
诊断分类放在连接滚动区内，不在模型区与固定底栏之间新增占位，避免失败时再次压缩目录。
能力检查区分检查中、可用、不支持、连接失效和检查失败；仅明确不支持时建议升级 App Server。

本地密钥库将主密钥与凭据密文存放在数据库，不访问系统钥匙串；仅支持新建数据库，旧库不升级或导入。
已有密钥只显示“已配置”，通过明确的替换入口输入新值；修改请求目的地立即清除临时输入及工作流内秘密。
切换无鉴权须明确确认最终保存时清除原密钥。临时秘密交由工作流的可清零缓冲区保管，保存、放弃、关闭或会话失效时清理，
重连只恢复非敏感草稿。服务切换、新建、页面导航和窗口关闭统一先检查 pending，再检查 dirty；提交中及结果未知时禁止离开。
目录读取可取消，不冒充在途写入；结果未知只查询原回执，不重建幂等身份、不再次提交，也不以关闭窗口表示撤销。

保存不切换当前对话或项目默认模型，不升级已有精确模型引用。“使用此模型”保留为独立操作，显示目标工作区和对话及其对
新对话最近选择的影响；打开使用窗口不会自动执行。目录查询与显式联网验证独立，验证仍需用户确认，不默认发送付费模型请求。
模型配置的具体操作、预设与失败恢复规则见[模型服务配置](provider-configuration.md)。

模型与思考选择保存到当前对话，并单独记入最近选择；新对话复制当时最近的选择，已有对话保留自身配置。
最近选择通过 `execution/recent/read` 和 `execution/recent/update` 持久化，不进入安装、项目和对话的执行
继承链；因此日常切换模型不会改变旧对话的继承结果。用户主动修改项目默认时，仍会影响跟随项目设置的对话。
Agent 固定配置继续优先。“跟随设置”与明确“关闭”思考分别表达继承和禁用；自定义服务的支持范围未知时，
界面不把协议可编码的档位描述成模型已验证支持。“恢复项目设置”仅清除模型和思考覆盖，保留权限、预算等限制。

`execution/preview` 只读取权威执行配置、精确引用与本地凭证状态，返回实际模型、思考、锁定状态及阻塞项；
不会读取 Prompt、创建 Turn 或调用模型。界面据此提供添加模型、选择工作区、调整设置或修复连接动作。
预览就绪只表示配置具备启动条件；发送仍由服务端独立校验。

Desktop 配置事件仅在 SDK 写操作成功后发布资源和作用域标识，页面重新读取权威数据。设置工作区选择
独立于聊天选择；打开、聚焦和服务重连会补读，隐藏页面在下次激活时更新。在途通知合并并补刷，响应按
作用域和请求代次隔离。同一工作区刷新保留列表选择、未保存草稿及其原 revision；发生外部修改时显示
“配置已更新，草稿已保留”。归档目标保留提示并暂停写入，不自动替换为其他工作区。

发送返回服务端确认的启动结果后，输入区只清除该次提交的草稿版本。失败保留输入，切换对话或发送期间
继续输入时，迟到结果不能清除新的内容。消息草稿按工作区和对话保存，首次配置模型创建对话时接续初始输入。

状态机验证覆盖 `ChatConfigurationPresenterTest`、`ExecutionSelectionAutomaticRefreshTest`、
`WorkspaceSettingsPresenterRefreshTest`、`DesktopModelApplicationTest`、`DesktopModelPreferencesTest`、
`DesktopSendResultTest` 与 `ComposerDraftsTest`；真实 JavaFX 交互的回归入口包括 `ChatConfigurationPanelTest`、
`ProviderConfigurationEditorTest`、`ManagementCenterInteractionTest` 和设置中心刷新测试。
`ProviderConfigurationEditorLayoutTest` 从完整设置窗口检查 880×620、1040×720 和 1440×900，使用 1,000 条模拟目录，
并通过 `javaclaw.provider.evidence` 输出真实 Scene 的连接、模型与失败恢复 PNG，同名元数据记录本次原生 outputScale/renderScale
以及逻辑和截图像素尺寸。测试入口不代表本次已通过验证；具体命令、截图及观察到的原生比例见
[单页模型设置验收](evidence/provider-settings-single-page-2026-09-21/README.md)，其他操作系统及未观察到的 DPI 比例仍须对应平台验收。

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
