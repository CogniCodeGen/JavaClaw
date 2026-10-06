# JavaClaw 实机功能验证记录

## 环境与方法

- 日期：2026-10-04至2026-10-06；平台：macOS 26.5.2 arm64；JDK 25；Maven 3.9.0。
- 启动方式：在 IntelliJ IDEA 点击现有 `Launcher` 运行配置，从项目源码启动。
- 数据环境：原应用数据，当前工作区为“默认工作区”；使用 `E2E_` 专用会话、末轮分支及虚构临时资料。`E2E_20261004_隔离` 临时工作区已通过 GUI 新建、切换、核对隔离并删除，仅操作本轮数据。
- 功能清单：[146 项 GUI 操作](e2e-functional-checklist.md)，含 D02 定义管理回归 F15-09、后台自动调度/历史显示 F14-07 及新窄窗口底部操作 F14-08。源码和日志仅用于梳理及定位，不作为功能通过证据。
- 已获得用户授权，使用 macOS 原生 AppleScript 辅助功能与 CGEvent 模拟电脑输入作为 JavaFX 窗口控制的备用方式；所有启动和重启仍在 IDEA 运行源码 `Launcher`，未使用打包启动器。
- 未执行 JUnit、TestFX、代码测试脚本、模拟模型或 Maven test/verify。初轮修复编译执行 `mvn -DskipTests compile`；嵌入续验通过 IDEA Build 实际编译（可能含 test 源码，但没有执行测试）。编译不作为功能通过证据。

## 准备记录

| 操作 | 实际结果 | 证据 |
|---|---|---|
| 确认启动前状态 | IDEA 的旧 Launcher 控制台显示进程已结束，系统应用列表没有 Java 进程 | 本次电脑操作记录 |
| 检查数据格式 | 当前标记为格式 4 | `data/.javaclaw-format` |
| 备份运行数据 | 已将关闭状态的 data 与 plugins 备份，约 47 MiB | `target/e2e-20261004/backup/pre-validation-data.tgz` |
| B12 修复加载前备份 | 托盘正常退出后备份关闭状态的当前 data；仅记录文件位置，不展示凭据 | `target/e2e-20261004/backup/before-b12-restart-data.tgz` |
| IDEA 源码启动 | 点击 Launcher 运行按钮，应用进程已出现；控制台显示主窗口已加载 | 本次 IDEA 界面及控制台截图，12:38:54–12:38:57 |
| 绑定 JavaClaw 窗口 | 电脑操作工具识别运行中的 Launcher，但按 `com.oracle.java.java`、Launcher 和实际 java 路径绑定均返回 Invalid app | 本次电脑操作工具原始结果 |

## 当前验证状态

截至2026-10-06清理后最终正常退出/IDEA源码重启实机完成，146项为 **90项通过、36项修复后通过、0项失败、20项阻塞**。本轮嵌入续验共改变11行状态：F09-02、F10-07为通过；F02-03、F09-03、F09-04、F09-05、F09-06、F09-07、F10-04、F17-06、F23-04为修复后通过。F10-03补充B64取消编辑回归，原修复后通过不重复计数。实际清单见 [完整after清单](e2e-functional-checklist.md)。

F10-04的↩恢复、重开、精准单删/批删原管理GUI验收已完成；原第三次纠正run668d…因普通memory_recall未打可信上下文而PAUSED，旧失败保留。B66精确身份分类修复源码加载后，新session83a6…/run3d95…原准确提示真实COMPLETED62.36秒/22961tokens，真实recall为trustedContextRead=true且SUCCEEDED240ms，合法纯回答plan及实际软状态/恢复重开均确认，本行改修复后通过。新run曾有中间orchestration失败后恢复，不写全部步骤无失败或沿用旧6工具计数。

Default原用户模型及用户新embedding配置保持，索引512/50已实际恢复。已登记本轮8独立知识对象全部逐项清理，两区0文档0片段；B66回归新两事实/sole纠错实际0/0；TEMP本轮4会话精准删除后自动01:34零消息空会话，随TEMP工作区精确删除，菜单仅Default；Default本轮新5会话删除后实际84保护会话。原旧76会话/12事实的历史永久删除审批范围未扩大，人类18:17会话保护。

Default新增3事实已按全文唯一ADD日志＋原12基线全文不存在限定归属、逐条精确确认清理15→14→13→12；原12全部正文保留。原12已经自动回填为正式，P04于01:34 MERGE但正文相同，状态和元数据确有变化，不能称原数据状态完全未变。

B67已确认01:57失败属于重启初始化，原晋升对象字段未显式store导致正式map/旧pending元数据矛盾，旧退出阶段猜测撤回。v3实际应用/回读9e4bc59…、编译1.094秒一warning、首启动恢复原12元数据并显示Default84/Personal12均通过。真正第二次样本持续真实服务OFF到Pending1，服务恢复后重开窗口自动晋升，退出0→02:15 IDEA源码重启仍formal13/保护保持，置顶/取消和唯一marker精确删除后原12，误建空会话直接删除后Default84。该真实晋升及未来重启链已经完成，不再写第二次回归待做。

最终Default RAG已实际正常已连接，原qwen3-embedding-0.6b-dwq/1024/COSINE/endpoint1234/512与50、全scope0doc0chunk0enabled。 清理后最后正常托盘退出02:23:36实际资源清理完成、进程退出0；IDEA源码启动02:23:54主窗84，菜单仅Default，Memory Facts12（原3habit+9explicit）无样本，原正文保留。最终Runlog只纳最小去敏启动完成事件，完整28583B raw日志不交付。P04普通MERGE更新时间02:23、正文相同，不能称所有metadata不变。 F23-04已按原路径修复后通过；原76/12历史永久删除范围及其删除后重启仍阻塞。IDEA原粘贴偏好已保存恢复并重开确认；报告保存、读回与证据包落地结果见 [交付记录](../target/e2e-20261005/embedding-followup/reports/delivery-receipt.md)。 [完整链路与边界](../target/e2e-20261005/embedding-followup/reports/javaclaw-embedding-followup-evidence-increment-20261005.md)留存依据，下方旧批次为当时时态。

### 当前20项阻塞的具体原因（截至2026-10-06最终清理后源码重启）

“阻塞”包括真实依赖缺失和验收剩余步骤未完成；已经可操作但尚未执行的步骤，不再写成缺嵌入模型。原实际聊天/设置故障已按各自原路径修复后回归通过，不混入这20项。

| 范围 | 数量 | 明确未满足条件 |
|---|---:|---|
| F23-05、F23-06 | 2 | 旧76会话/Personal12事实永久删除被历史自动审批1493要求的具体不可逆范围确认阻止；未删除就不能确认“清理后”重启。已有正常退出/源码重启和运行资源停用证据仍有效 |
| F01-05 | 1 | 原数据已完成设置，首次启动向导未出现；未重置原工作区以制造该入口 |
| F07-01 | 1 | 审核模式、说明和原模式重启恢复已有证据，保存失败反馈没有安全的正常GUI故障触发入口，未破坏数据库/权限制造错误 |
| F11-06 | 1 | 没有本轮真实学习提案，空页不等于预览/采纳/拒绝链路通过 |
| F12-05 | 1 | 现策略禁本地stdio且拒本机/回环HTTP，缺可用且策略允许的MCP端点，工具发现/真实进程生命周期未完成 |
| F13-02至F13-06 | 5 | 已安装列表0、在线市场未接入且没有本轮可信可安装包；详情、安装权限、配置、卸载缺对象 |
| F18-01至F18-07 | 7 | Deliverance服务插件未批准/注册且没有READY档案，管理、资产、加载、档案路由、本地API与别名/密钥入口不能进入；已有外部真实embedding服务不替代该产品模块 |
| F19-02 | 1 | 临时假站点的编辑/密码显隐/持久化/删除已完成，但没有真实登录session，Reset禁用，登录会话重置未执行 |
| F22-05 | 1 | 系统维护扫描0安全候选，没有仅含本轮数据且可删的标记目录，未用未知旧目录验证清理 |

本轮自建临时文档/事实等按用户原计划可在精确归属范围内处理；旧76/12的历史审批限制不自动扩展至新数据，也不借新数据授权删除旧范围。

以下1819之前的收尾状态是用户后来要求嵌入续验前的历史记录：

当前没有该外部窗口操作阻塞：用户06:29继续后，root确认FollowUpUI进程不存在，1278实际桌面没有账户弹窗。此前Apple账户确认造成的遮挡、未触碰账户按钮及首次整屏截图自动审批拒绝均为历史记录；不将其记作JavaClaw缺陷。最终临时资源清理及删除后重启当前仅受76会话/12事实当时具体human确认阻塞；清理逐项归属及GUI完整身份核验见下文1810收尾结论，不按83与原7的差值选择删除。

历史时点截至1819，清单四种状态为 **89项通过、27项修复后通过、0项失败、30项阻塞**，共146项，无待验证项。30项阻塞中28项仍为既有外部依赖/可执行条件缺失；新增2项仅是F23-05/06临时数据永久删除当时具体human确认未到及其后续清理后最后重启。D28已最新IDEA源码加载并真实回归：1785选68、1786–1797完整ID全文滚动集合与owned68精确相等，另8项完整身份逐个确认，1810所有确认取消/正常模式/83保留。完整标识显示/滚动/取消原路径修复后通过，不代表实际删除。76会话及Personal12事实已提供精确可审范围，没有点任何OK或永久删除；1814–16再次完整显示12同文本3+9、P04Markdown更新17:31，1819关闭记忆/83保留且无管理选择。原7会话及workspace人工Fact1 KEEP仅原归属保护边界，1817/18只读Personal+session下拉不切scope，本轮未重新GUI验证人工1数量；初始Personal事实数unknown不推0。F21-05完整输入链已1747 GUI579和同Runstrict9/9共同通过，clear/完整123+456/ENTER各FG SENT1无重复，1750真Stop/1751仅终态后环境294复原；正常源码重启后完整报告和无Preview复活已有实证。B56异常分类、B55/D25及B45 own30等未触发具体分支仍按原记录未覆盖，不把whole通过扩成所有异常分支通过。

本文顶部当前续验结论及暂存after清单是最新候选结论；以下按编号保存的执行批次、当时“待验证/失败/加载待”等文字均为历史时点。未执行删除不写会话7或Personal0，最后两项因具体确认阻塞，不推为通过。

截至1114时点，清单为 **83项通过、18项修复后通过、1项失败、26项阻塞、18项待验证**，共146项。B29搜索结合1071–1090全部14分类导航，F17-01修复后通过；1094仅两原系统定时任务启用、四本轮已删任务未复活，F14-06修复后通过。B33筛选范围修复及B35确认正文高度/owner经1109–1113原GUI回归：只选择两个精确本轮会话，取消保留66、真正确认仅删除这两项至64，原7保护会话未触及，F03-06修复后通过。1114新六步只读桌面请求真实运行中，D10/D11已02:22:04编译并1107/1108由IDEA源码加载，尚未取得完整链终态，F21-02仍失败；局部观察、收据、编译或加载不能代替整体完成。1101–1104恢复验证未发生写入，两次审批超时后真正取消，F07-06仍待验证。前台接管/输入、D08重启关联耐久、其余本轮临时清理及最终持久化仍待；真实embedding连接继续缺地址/模型/密钥。 本批补至1128：F12-02表单必填/格式与OFF保存实际通过；F12-06仅URL复制、日志仍待；1114桌面轮已预算暂停，D10/D11子路径生效但B36遮蔽新链，整项F21-02仍失败。汇总数量按下一批证据统一更新，不把本段1114历史数量当1128新数量。

电脑操作工具绑定 Java 进程曾返回 Invalid app，重新初始化后仍不能绑定。用户已授权原生 AppleScript/CGEvent 备用输入，后续实测已通过该方式开展，不再等待这项授权。源码和日志用于定位问题；通过状态来自实际界面及用户输入结果。

### 已接收的实机证据

证据目录为 `target/e2e-20261004/evidence/`。下表只描述实际覆盖的步骤；误截到 Codex 的 `06-copy-and-quote.png` 不作为验证证据。

| 功能 / 证据 | 实际结果 | 清单结论 |
|---|---|---|
| 启动界面：01-desktop-after-java-focus.png | 完整聊天主界面实际出现 | F01-01 通过；窗口缩放、单实例二次启动等未因此通过 |
| 新会话及 Markdown：02-chat-start、03-chat-response 的 AX / PNG | 原区创建 E2E_基础对话；真实 Qwen3.8-flash 回复，显示标题、列表、表格、Java 代码块 | F03-01、F05-01 通过 |
| 输入及上下文：04-multiturn、05-multiturn-result 的 AX / PNG | 中文真实发送；空格按 Enter 未新增空消息；Shift+Enter 两行发送；第二轮正确回复口令“青橙6729” | F04-01、F04-02、F04-03 通过 |
| 复制和引用：07-copy-and-quote.png | 代码块复制精确得到 `int n = 7;`；引用回复完整填入 Markdown，Esc 清除输入 | F05-02、F05-03 部分完成，其他复制、更多菜单和重生成待验证 |
| 模型设置：09-settings-general 的 AX / PNG | 已发现 500 个模型；模型列表接口可达，2216ms；界面明确标注此按钮尚未验证聊天调用，真实聊天已另行成功 | F17-03 部分完成，不把模型列表可达当作完整聊天连接测试 |
| 通用设置：11-general 的 AX / PNG | 屏幕与系统音频录制、辅助功能、发送输入事件均显示已授权；本轮未修改设置 | F21-01 部分完成，缺失权限及继续授权路径待验证 |
| 托盘：15-tray-closed.png、16-tray-click.png、17-tray-restored.png | 主窗口隐藏；菜单包含显示主窗口/新建任务/打开设置/退出；窗口实际恢复 | F01-07、F23-02 部分完成；未切换常驻开关，未验证后台任务持续运行 |
| 正常退出及重启：18-normal-exit.png、19-restarted 的 AX / PNG | Java 应用消失；IDEA 控制台正常退出代码 0；从 IDEA Launcher 重启后会话/消息保留，但第二轮助手回复多一条重复消息 | 保留 B03 原失败证据；后续原路径修复回归见 86/95/97；F23-04 全部模块持久化仍待验证 |
| 完成状态回归：21-status-result 的 AX / PNG | 新一轮真实聊天结束后显示“本轮处理已完成”，执行活动显示绿色“上下文资料已返回” | B01/B02 本次完成路径 GUI 回归完成；其他终态与恢复路径仍待测 |
| 执行规划展开：22-status-history 的 AX / PNG | 已目视检查截图：执行规划显示历史“模型正在推理…”和“收起记录”，确实展开成功；执行活动仍显示“展开记录” | F03-05 部分完成；未覆盖会话右键轮次/步骤入口，也未把执行活动算作已展开 |
| 真实生成与取消：23-stream、24-cancelled 的 AX / PNG | 长回复请求处于生成中；空输入 Esc 后显示“本轮处理已取消”，发送按钮恢复 | 结合先前 Esc 清输入，F04-05 通过；F04-04 只部分完成，未观察增量正文，也未点击停止按钮 |
| 图片附件：32-image-attached、33-two-attachments、35-image-sent、36-image-result 的 AX / PNG | 图片和 TXT 实际添加；TXT 移除后仅图片发送；真实视觉回复符合图片内容 | F05-04 通过；未把已移除的 TXT 算作发送或文档理解通过 |
| 图片查看器：38-image-viewer、39-image-zoom-in、40-image-zoom-out、41-image-fit、42-tray-menu-before-restart 的截图 | 已目视核对：已发图片双击打开，放大/缩小、适应窗口保持比例，42 时查看器已关闭返回聊天 | F05-05 通过 |
| 修复源码重启与新会话：44-restart-fixed、46-persist-first-result、48-personal-menu 的 AX / PNG | 经 IDEA 新源码重启后，干净 E2E_重启回归会话两轮各回复“收到”，共 4 消息 | B03 的新会话基线；完整重复重启回归见 86/95/97 |
| 记忆总览及自动事实：49-memory-center、50-memory-facts 的 AX / PNG | 总览/事实显示本轮虚构 E2E_小林偏好；13:30 自动蒸馏实际完成，原维护 Run 生命周期错误不再出现 | B04 本次真实聊天→自动蒸馏链路修复后通过；手工事实验证另见 77–79 |
| 事实编辑原失败：53-memory-input、54-memory-edit-saved、55-memory-edit-restored 的 AX / PNG | 真正键盘修改后保存新内容，再恢复原临时事实；未配置嵌入却报告已嵌入成功 | 保留 B05 原失败证据；52 的 SetValue 未生效，不作为输入成功证据；修复回归见 88–90 |
| 记忆分区：56-memory-graph、57-memory-episodes、58-memory-entities、59-memory-persona、69-memory-corrections、70-memory-log、75-memory-knowledge 的 AX / PNG | 各分区实际加载，空知识页显示导入说明；图谱 1 节点 0 边，日志可见本轮修改 | F10-01 仅页面加载通过；未将空页访问当成知识导入、情景业务或实体关联通过 |
| 人格：60-persona-preference、62-persona-saved、63-persona-reopened、65-persona-restored、68-persona-exported 的 AX / PNG | 新增虚构偏好/禁忌并改耐心语气，保存重开保持；随后删除两项并恢复简洁语气保存；身份原文未改；GUI 导出到 fixtures/E2E_persona.md | F10-06 通过；原人格配置已恢复，导出文件只在本轮临时目录 |
| 日志过滤：71-memory-log-add、72-memory-log-edit、73-memory-log-delete、74-memory-log-merge 的 AX（71/72 另有 PNG） | 实际切换新增、编辑、删除、合并过滤；有记录时显示相应记录，无匹配时显示空态 | F10-07 过滤已完成；真实回填因嵌入未配置而阻塞 |
| 手工事实及搜索：77-memory-add、78-memory-added、79-memory-search 的 AX / PNG | 空字段保存禁用；新增虚构 E2E_手工事实后共 2 条，分类明确偏好；搜索仅显示该临时事实 | F10-02 通过 |
| 单条事实删除：80-memory-delete-confirm、81-memory-delete-rejected、82-memory-deleted、83-memory-delete-correction 的 AX（80/82/83 另有 PNG） | 拒绝确认后保持 2 条；同意只删除 E2E_手工事实，剩原本轮蒸馏事实 1 条 | F10-04 已完成单条物理删除；批量删除未测；↩ 恢复检索因缺少非 Pending 的 superseded/contested 事实而阻塞，不是删除撤销缺陷 |
| B03 重启回归：84-restart-tray-menu.png、85/96-post-exit-process.txt、86-persist-restart-four、95-persist-six-before-restart、97-persist-restart-six 的 AX / PNG | 正常退出并从 IDEA 重启后 4 条仍为 4 条；第三轮后 6 条，再次正常退出重启仍为 6 条；三次合法同文“收到”均保留 | B03 修复后通过；F23-01/04 其他资源持久化仍待验证，不自动清理旧重复历史 |
| 固定及 B05 回归：76-memory-pinned.ax.txt、77-memory-add、88-embedding-status-fixed、89-embedding-save-feedback-fixed、90-memory-edit-cancelled 的 AX / PNG | 实际星标并取消；修复后显示嵌入未配置且无回填按钮；编辑保存仍 Pending，提示“事实已更新，等待嵌入”；恢复原临时内容并取消未保存编辑 | F10-03 当前未配置降级路径修复后通过；真实向量重嵌入仍需要可用模型 |
| 图谱：91-memory-graph-before、92-graph-selected 的 AX / PNG、93-graph-zoom-pan.png、94-graph-reset.png | 选择节点显示本轮事实及“无直接关联”；实际缩放、拖动和重置恢复位置 | F10-05 通过；不将 0 边图算作关联推断或重建通过 |
| 审批准备：98-approval-first 的 AX / PNG | 新专用会话请求只在 target/e2e-20261004/project 创建 E2E_approval.txt；截至该证据只已提交请求 | F07-02 部分，未将请求提交当成审批/拒绝或文件创建完成 |
| 过期审批：99-approval-state、100-approval-rejected 的 TXT / PNG | 审批超过 60 秒，后台任务已取消，但弹窗仍显示同意/拒绝；点击拒绝仅关闭过期窗口 | 保留 B06 原失败；修复回归见 137–139；F07-02 有效人工拒绝仍待验证，不能以界面“授权未通过”证明人工拒绝 |
| 有效批准及写入：101-approval-repeat、102-approval-wait、103-approval-approved、105-approval-continued、106-approval-final 的 TXT / PNG | 新审批等待 43.6 秒时及时同意；sys_file_write 写入本轮绝对路径文件 17 B，目标已验证；相对读取失败，绝对读取成功返回 E2E_APPROVAL_6729 | F07-03 有效批准后的本轮写入/读取通过；不将相对读取失败记成成功 |
| 重复执行保护及 B07 原失败：103–108 对应审批 TXT / PNG | E2E_approval.txt 写入/读取标记成功，但模型 DONE 时宿主仍判两项完成条件 UNVERIFIED，修复轮再次请求写入；108 提示“先前操作已经提交；已暂停重复执行”；未新增旧 original task snapshot is duplicated 报错 | 保留 B07 原失败；实际防护已观察，未通过恢复入口继续、未将暂停当整轮完成，F07-06 仍待验证；B07 回归见 186 |
| 临时工作区：109-workspace-create.png、110-workspace-isolated、114-workspace-options.png、115-workspace-return 的 TXT / PNG | 创建临时区后仅 1 个空会话，默认 qwen/qwen3.5-9b；回原区仍 11 会话、真实 qwen3.8-flash，旧消息未混入临时区 | F02-01/02/03 部分；创建取消、带标记资源及知识文本隔离仍未覆盖 |
| 当前区删除保护：111-workspace-delete-confirm.png、112-workspace-delete-block.png、113-workspace-delete-warning 的 TXT / PNG | 当前临时区确认删除后显示需先切换的警告，临时区未删除；原工作区保持 | F02-05 通过；临时区删除取消路径 F02-04 仍未完整覆盖 |
| 非当前区删除缺陷：116-workspace-delete-target 的 TXT / PNG | 切回原区后 − 的删除目标只能为默认工作区，无可选非当前临时区；关闭确认，未删除原区 | 保留 D01 原失败；F02-06 修复回归见 140–146 |
| 会话搜索：117-session-search、118-session-no-result 的 TXT / PNG | 本轮标记过滤正确，无匹配词显示“没有匹配的会话” | F03-02 部分；清空搜索恢复全列表尚无本批证据 |
| 归档与恢复：119-session-context-menu、120-session-archived、121-session-archive-menu.png、122-session-restored 的 TXT / PNG | 临时会话归档后带已归档标记，并自动新建 14:11 空对话、原区总数变 12；恢复后原 6 条消息保留，输入可用 | F03-03 通过；自动新建空对话列入本轮待清理数据 |
| 分支隔离：123-session-forked、124-session-branch-input、125-session-branch-result、126-session-parent-isolated 的 TXT / PNG | 末轮分支保留 6 条；新增 BRANCH_6729 并收到“分支收到”后 8 条；父会话仍 6 条，没有分支标记 | F03-04 通过 |
| 执行检查器：127-session-parent-menu.png、128-session-executions 的 TXT / PNG、129-execution-expanded.png、130-execution-result-detail.png、131-tray-before-fix-restart.png | 显示三轮 COMPLETED；展开步骤；选择轮次结果显示完整 JSON；关闭检查器后返回主界面 | F03-05 通过；已目视核对关键截图 |
| 新修复加载准备：131-tray-before-fix-restart.png、132-normal-exit.txt | 使用托盘正常退出，进程核对为空；IDEA 控制台退出代码 0 | 退出完成；随后修复源码加载见 133，GUI 回归见 137–146 |
| 修复源码重启：133-fixed-launch 的 TXT / PNG | 从 IDEA 加载新源码，末轮分支仍 8 条消息及专用标记 | 分支重启持久化已覆盖；F23 全部资源仍待验证 |
| 首次审批回归：134-approval-timeout-start、135-approval-timeout-wait、136-approval-timeout-closed 的 TXT / PNG | 首次请求 E2E_timeout.txt 由用户手动点击同意，用户明确回复“我点过同意”；文件正常写入并完成核验 | 仅为正常有效审批，不作为无人操作超时证据；无人操作超时只由 137–139 证明 |
| B06 超时原路径回归：137-approval-timeout2-start、138-approval-timeout2-wait、139-approval-timeout2-later 的 TXT / PNG | 第二次请求 E2E_timeout2.txt 无人操作，60 秒超时后弹窗自动关闭，任务取消且未创建文件，发送按钮恢复 | B06 修复后通过；F07-02 的有效人工拒绝步骤仍待验证 |
| 工作区取消及隔离：140-workspace-delete-picker、141-workspace-confirm-cancel、142-workspace-memory-isolated 的 TXT / PNG | 目标选择只列非当前 E2E_ 临时区；取消选择器及永久删除确认均保留数据；再次切临时区仍为空会话、0 事实/0 情景，与原区 1 事实隔离 | F02-04 通过；F02-03 临时文本/知识的双向隔离仍未覆盖 |
| D01 删除回归：143-workspace-delete-final-confirm、144-workspace-deleted 的 TXT / PNG、145-workspace-single-option.png、146-workspace-last-protected 的 TXT / PNG | 原区选择临时区确认删除；原区保持 14 会话及原 qwen3.8-flash；下拉只剩默认区；再尝试删除最后区显示保护 | F02-06 修复后通过，临时区已 GUI 删除；不将该操作算作所有模块清理完成 |
| 知识库状态与空文本：147-knowledge-center、149-knowledge-text-dialog、150-knowledge-empty-validation、152-knowledge-index-settings、156-knowledge-global-scope 的 TXT / PNG | 全部/工作区/全局范围文档均 0，RAG 原值关闭；空文本报“文本内容不能为空”；索引显示嵌入未配置、维度 1024、COSINE、分块 512/重叠 50 | F09-01 页面/状态查看通过；未改索引或点击清空全部 |
| 知识库导入及查询：151-knowledge-text-import-feedback、153-knowledge-file-picker、154-knowledge-go-path、155-knowledge-file-import-feedback、157-knowledge-search-page、158-knowledge-query-result 的 TXT / PNG | 虚构粘贴文本及 E2E_knowledge.md 均反馈 0 成功/1 失败、文档仍 0；查询为空，0 文档参与检索；定位依赖为 embedding UNCONFIGURED | F09-02–07 记录嵌入依赖阻塞；TXT/PDF/目录解析、详情、成功检索/RAG/删除未验证，失败不能补记成功 |
| 嵌入配置与无效保存：159-embedding-settings、160-embedding-connection-feedback、161-embedding-invalid-settings 的 TXT / PNG | OpenAI、API/密钥空、模型默认 text-embedding-3-small、维度 1024、返回 5/阈值 0.3；测试及临时开启 RAG 后保存都明确 API 地址不能为空 | F17-06 部分；维度没有改为 0，参数边界和真实连接未测 |
| 未保存设置：162-settings-dirty-navigation、163-settings-unsaved-prompt、164-settings-close-rejected、165-settings-discard-restored 的 TXT / PNG | 跨分区保留脏标记；关闭提示 1 分区未保存；拒绝后继续保留设置窗口；同意丢弃后重开，RAG 原值关闭恢复 | F17-07 丢弃路径已覆盖；成功保存及持久化仍待验证 |
| 设置搜索：166-settings-search、167-settings-search-empty、168-agent-new 的 TXT / PNG | 中文“嵌入”过滤相关导航，无匹配词无导航，清空后恢复核心配置列表 | F17-01 部分；全部分类及英文关键词未覆盖 |
| 智能体字段校验及保存：168-agent-new、169-agent-invalid-tool 的 TXT / PNG、170-agent-corrected.png、171-agent-saved.png | 添加自定义项；1bad 被具体语法校验拒绝；修正 e2e_expert，保存 E2E_专家、迭代 3、虚构描述且启用，列表更新 | F08-02 基础字段保存已完成，系统提示词仍默认未编辑，因此整项待验证；F08-03 空字段/重复工具名未覆盖；AI 补全未做 |
| 智能体启停及重启保持：172-agent-disabled-reselected.png、175-tray-b07-restart.png、176-b07-source-restarted 的 TXT / PNG、182-agent-restart-state.png、183-agent-fields-persisted.png、184-agent-enabled-delete-confirm.png | 停用保存，选择内置再返回仍停用；托盘正常退出，IDEA 退出代码 0；从 IDEA 新源码重启后名称、工具名、迭代 3、描述与停用状态保持；再次启用保存 | F08-04 通过；F23 全部资源持久化仍待验证，不把字段保存当实际专家调用通过 |
| 智能体删除：173-agent-delete-confirm.png、174-agent-delete-cancel-return.png、182-agent-restart-state.png、183-agent-fields-persisted.png、184-agent-enabled-delete-confirm.png、185-agent-deleted.png | 首次确认拒绝并关闭设置，重启后本轮项仍在；再次启用保存并确认删除，185 自定义项为空，内置 JavaClaw Assistant 保留且启用 | F08-06 通过；本轮 E2E_专家已 GUI 清理 |
| B07 审批回归：177-fileproof-start.png、178-fileproof-progress.png、179-fileproof-approval.png、180-fileproof-approved.png、181-fileproof-timely-approved.png | E2E_文件证据回归请求只写/读本轮临时文件；179 显示正确绝对路径和 E2E_FILEPROOF_6729；180 辅助功能点击没有生效、弹窗仍打开；181 真实点击同意后执行继续 | 180 文件名含 approved 仍不能作为批准证据；批准以 181 和后续实际结果为准，F07-03 补充通过证据 |
| B07 完成条件回归：186-fileproof-result 的 TXT / PNG | 106.3 秒完成；sys_file_write 写入 E2E_fileproof.txt、18 B 并核验文件内容后置条件；sys_file_read 同绝对路径读取精确内容并独立观察；界面两项完成条件已核验，无重复暂停 | B07 原路径修复后通过；不将这次完整写入/读取当作实际恢复入口通过，F07-06 仍待验证 |
| 工作流打开与新建：187-workflow-center 的 TXT / PNG、188-workflow-created.png、189-workflow-definition-menu.png | 系统编排只读；新建本轮“新工作流”，默认开始/输出/结束，选中并自动保存；当时定义右键无重命名/删除菜单 | F15-01 通过；D02 原缺口保留，后续改名/保护回归见 230–239，实际删除仍待；定义 ID wf-90a4737f-e32a-49bf-a6b5-52d0846c788e |
| 工作流节点及配置：190-workflow-add-menu.png、191-workflow-human-added 的 TXT / PNG、192-workflow-human-inspector 的 TXT / PNG、193-workflow-invalid-json.png、195-workflow-correct-output.png | 人工输入添加并接链，节点拖动/适配；非法 JSON 具体错误且原图名称未变；合法人工 prompt/responseKey 和输出模板应用、校验通过；当时反馈因 B08 几乎不可读 | 后续 F15-02 添加/缩放见 241/247/262/263 通过，B08 浅色可读回归见 232/239/259/261；当时恢复安全SAFE未更改，后续421/422真正应用/重选保持后F15-03通过。194 错选 END 是操作失误，195 恢复 END/{}，不是缺陷 |
| 工作流人工运行及恢复：196-workflow-run-input.png、197-workflow-history-waiting 的 TXT / PNG、198-workflow-history-refreshed.png、199-workflow-empty-recovery.png、200-workflow-recovered.png、201-workflow-recovery-trace 的 TXT / PNG | 输入 E2E_初始6729；历史首次空，刷新后 #475b8f6a 待输入；空恢复报必须填写输入；E2E_恢复6729 恢复同一次运行，5 步结束，轨迹 COMPLETED，输出保留两输入 | F15-06 运行/恢复步骤通过，B08 浅色回归见 232/239/259/261；纯回显历史未核验，不声称可信任务验收；F07-06 带已完成副作用恢复仍未测 |
| 工作流取消：202-workflow-second-wait.png、203-workflow-cancelled.png | 新运行 #60291256 待输入，取消后同 ID 已取消；先前 #475b8f6a 5 步结果保留 | F15-06 取消步骤通过；未将先前运行“历史未核验”当作已核验 |
| 工作流发布：204-workflow-published.png | 本轮图发布成功，列表已发布，可在聊天模式运行；自定义项继续可编辑 | 本批发布完成；后续保持/复制见 230/234/310，F15-07 通过；错误出口见 264–268 |
| 工作流聊天：205-chat-mode-menu.png、206-chat-workflow-mode.png、207-workflow-chat-interrupted.png、208-workflow-chat-result 的 TXT / PNG | 新专用会话选择刚发布图；首个输入产生人工提示，第二输入恢复后输出精确 E2E_结果：E2E_聊天初始6729 / E2E_聊天恢复6729，人工/输出/结束完成 | F15-08 运行恢复/输出通过；界面明确任务结果未验证、缺少可核验完成证据，纯回显没有宿主任务验收证据，不把运行 COMPLETED 写成可信任务已验收 |
| 技能创建及字段保存：209-skill-center.png、210-skill-new-editor.png、211-skill-filled-bottom.png、212-skill-v1-saved.png | 初始列表 0；新建自动持久化，输入 E2E_文本整理、描述、类别 E2E_办公、标签 E2E/文本并停用；保存后名称/停用同步列表 | 本批只保存；后续 286 已实际重启核对全部字段及正文保持，F11-01 通过；初始技能目录仍为自动目录名 |
| 技能正文版本回滚：212-skill-v1-saved.png、213-skill-versions.png、214-skill-rollback.png、215-skill-rollback-result.png | V1 E2E_V1_6729 三个要点保存为 1.0.1，V2 两个要点保存为 1.0.2；选择历史 1.0.1 并同意回滚后，正文恢复 V1、提示已回滚到 v1.0.1 | F11-02 只验收版本列表和正文回滚通过；不据此推断重启持久化、回滚拒绝或技能删除通过 |
| 技能脚本编辑：216-skill-script-editor.png、217-skill-script-controls.png、218-skill-new-script-dialog.png、219-skill-script-created.png、220-skill-script-saved.png | 展开查看参数/输出；新建 E2E_notes.jsh，仅一行注释保存、目录有 scripts/ | 本批只编辑保存；后续 290–293 实际保持/拒绝/删除完成，F11-04 通过；没有检查/运行测试或执行代码 |
| 技能包：221-skill-package-new.png、222-skill-package-fields.png、223-skill-package-saved.png | E2E_文本包填写描述、技能引用及附加 E2E_6729 指令，禁用后保存 | 本批只保存；后续 294–298 字段/停用重启保持、启停保存/删除已完成，F11-05 通过 |
| 技能提案：224-skill-proposals.png | 待审提案页正常加载，显示暂无待审提案 | F11-06 阻塞：缺少本轮真实生成提案，不能以空页当预览/采纳/拒绝通过 |
| 技能目录导入：225-skill-import-choice.png、226-skill-import-directory-picker.png、227-skill-import-result.png | 选择目录导入，经系统选择器选本轮 fixtures/E2E_import_skill（SKILL.md、enabled:false），提示安装成功至 data/skills/E2E_import_skill，列表 E2E_导入文本且停用 | 此时合法目录导入已覆盖；后续1152原生取消及1170无效Zip明确反馈补齐验收，F11-03现通过。合法Zip仍未实测 |
| 新修复源码加载：228-restart-tray-menu.png、229-source-restart.png、230-workflow-management-loaded.png | 托盘正常退出、IDEA 退出代码 0，随后 IDEA Launcher 源码重启；工作流管理按钮已加载，系统项重命名/删除禁用，原本轮发布项仍在 | B08/D02 修复已实际加载；原工作流发布保持部分持久化已覆盖，所有资源 F23 仍待 |
| 工作流改名及复制：231-workflow-rename.png、232-workflow-rename-empty.png、233-workflow-renamed.png、234-workflow-cloned.png、235-workflow-delete-confirm.png | 空名具体拒绝且原名称保持；合法改为 E2E_人工输入流程；复制形成 E2E_人工输入流程 副本、草稿自动保存，节点/配置继承且编辑控件可用；取消删除保留副本 | 本批改名/空名/取消删除完成；后续 310 重启保持、313/314 终结删除/重开完成，F15-07 通过、F15-09 修复后通过；恢复失败另记 B10 |
| 工作流待输入删除保护：236-workflow-clone-test.png、237-workflow-delete-running-blocked.png、238-workflow-delete-waiting-confirm.png、239-workflow-delete-block-feedback.png，完整 AX 见 259-workflow-true-result.txt | 副本运行 #db1093c5 到 WAITING_INPUT；两次删除均明确“工作流仍有未终结运行，请先取消后再删除”，副本与等待运行保留 | 正常保护，不能算删除成功或新缺陷；保留运行随后在 310/311 重启恢复时复现 B10，终结后定义删除见 312–314 |
| 工作流编辑历史及校验：240-workflow-node-menu.png、241-workflow-isolated-validation.png、242-workflow-undo.png、243-workflow-redo.png、244-workflow-node-deleted.png、245-workflow-delete-undone.png、246-workflow-delete-redone-valid.png，具体校验 AX 见 259 | 孤立 transform 明确缺成功出口、从 START 不可达；撤销添加消失、重做恢复；删除后撤销恢复、再重做删除，完整图校验通过 | F15-05 通过；已查看实际节点增删截图，不以按钮可见作通过 |
| 工作流节点/出口及分支配置：247-workflow-condition-output-added.png 至 257-workflow-branch-validation.png | 添加 condition 和第二 output；输出普通出口接 end，human 普通出口替换为 condition；条件弹窗取消后 Esc 无残边。TRUE：human.response/EQUAL/JSON 字符串 E2E_通过6729、优先级 0；DEFAULT：勾默认、优先级 1；完整图校验通过 | F15-02 添加类型完成；本批普通/条件/默认/取消完成，后续 264–268 真实错误出口补齐，F15-04 通过 |
| 工作流 TRUE 运行：258-workflow-condition-waiting.png、259-workflow-true-result.txt / .png | #73c237b8 待人工输入后恢复 E2E_通过6729，同 ID 经过 human→condition→TRUE output→end，6 步 COMPLETED，实际输出 E2E_TRUE：E2E_通过6729 | 条件命中运行/恢复/输出通过，补入 F15-06；不声称具有宿主任务验收证据 |
| 工作流 DEFAULT 运行：260-workflow-default-waiting.png、261-workflow-default-result.txt / .png | #8cd7b4cc 待输入后恢复 E2E_其他6729，同 ID 经 condition→DEFAULT output→end，6 步 COMPLETED，实际输出 E2E_DEFAULT：E2E_其他6729；TXT 已生成且已读，截图已核对 | 默认分支真实运行/恢复/输出通过，补入 F15-06；没有把未生成文件或推测结果记通过 |
| 工作流画布及浅色可读性：262-workflow-zoom-out.png、263-workflow-zoom-restored.png，232/239/259/261 工作流截图 | 画布 100% 缩至 87% 再恢复 100%，原图可读；适配操作已执行。浅色空名错误与恢复轨迹呈深色文字、米白底可读 | F15-02 通过；B08 本批浅色原路径通过，深色后续 309/311 回归；当时恢复安全值未改；后续421/422完整覆盖，F15-03通过 |
| 工作流错误出口：264-workflow-tool-added.png、265-workflow-error-connected.png、266-workflow-error-validation.png、267-workflow-error-waiting.png、268-workflow-error-result.txt / .png | 添加 E2E_缺失工具，human 普通边连到该工具，其错误边为红色虚线通向输出；校验通过。#042a9cfc 人工等待后恢复 E2E_错误恢复6729，工具 E2E_no_such_tool_6729 失败，沿错误边输出 E2E_ERROR_OR_DEFAULT：工具执行失败: E2E_no_such_tool_6729 / E2E_错误恢复6729，同 ID 6 步 COMPLETED | F15-04 普通/条件/默认/取消/错误出口全部本条范围通过；补入 F15-06，未称宿主可信验收通过 |
| 主界面主题：269-theme-menu.png、270-theme-midnight.png、272-theme-carbon.png 至 279-theme-emerald-restored.png | 原主题 Emerald；实际切 Midnight、Carbon、Sapphire、Ocean、Plum、Terracotta、Honey、Graphite，各主界面配色即时变化，279 恢复原 Emerald | F20-01 主界面切换/恢复已覆盖；全部弹窗/禁用态/代码区未逐项覆盖；工作流未跟随见 B09，不标整项通过 |
| 工作流主题原失败：271-workflow-dark-readable.png | 午夜主界面下工作流中心重开仍是 Emerald 米白底/绿色，轨迹可读但属于浅色，文件名不能代表实测深色 | 保留 B09 原失败；原路径修复后回归见 308/309/316，不用 271 证明深色可读 |
| 技能重开与输入时序：280-skill-after-restart.png 至 286-skill-original-reselect.png | 280/281 初次原生输入尚未真正选中编辑器；后续 283 可开导入项，286 重选原技能，名称、描述、类别 E2E_办公、标签 E2E/文本、停用及 V1 三要点保持；徽标实际为 1.0.2 | F11-01 通过；先前技能输入疑点已排除为原生输入焦点/时序，不列应用缺陷。B10 编号用于后续真实工作流恢复问题；不将 281 文件名当字段加载证据 |
| 技能目录与滚动：285-skill-directory-finder.png、287-skill-directory-opened.png、288-skill-script-reopen-header.png、289-skill-scroll-working.png | Finder 实际显示 E2E_import_skill 的 SKILL.md，返回技能中心；正确原生滚动后可达脚本区 | 本批目录/返回完成，技能删除后续见 303–306，F11-07 通过；滚动时序不报应用 Bug |
| 技能脚本保持及清理：290-skill-script-persisted.png、291-skill-script-delete-confirm.png、292-skill-script-delete-rejected.png、293-skill-script-deleted.png | IDEA 重启后 E2E_notes.jsh 名称及同一注释正文保持；先拒绝删除仍 1 个脚本/原文，再同意后明确已删除 E2E_notes.jsh、暂无脚本 | F11-04 编辑/保存/保持/拒绝及删除通过；没有检查、运行测试或执行脚本 |
| 技能包保持/启停/删除：294-skill-package-persisted.png、295-skill-package-restored-fields.png、296-skill-package-enabled.png、297-skill-package-disabled.png、298-skill-package-delete-confirm.png | 重启后 E2E_文本包、虚构描述、技能引用、附加 E2E_6729 指令和停用保持；启用保存、停用保存；删除后已删除提示、字段清空 | F11-05 通过；298 实际无确认弹窗、已直接删除，文件误名不作为删除拒绝/取消证据；只删本轮包 |
| 技能启停与清理：299–302 技能选择/保存，303-skill-delete-dialog.png、304-skill-delete-refused.png、305-skill-deleted.png、306-skills-cleaned.png | 原技能真正鼠标选中后 301 启用保存、302 停用保存，列表状态同步；303 删除提示整个目录不可撤销，304 拒绝保留原技能，305 同意只删该项，再删本轮导入项后 306 列表恢复 0 | F11-07 目录及技能实际删除通过；启停补入 F11-01，原有技能初始为 0，没有删除用户已有技能 |
| 新修复源码重启及主题：307-tray-pre-restart.png、308-restart-state.txt / .png、309-workflow-dark-after-fix.txt / .png、315-theme-menu-current.png、316-workflow-carbon-after-fix.png、317-theme-emerald-restored.png | 托盘正常退出，IDEA 源码重启含 B09；工作流中心 Midnight 画布/面板为深色，315 菜单勾选午夜，316 切 Carbon 后工作流也为碳黑蓝色，317 恢复 Emerald | B09 原路径修复后通过；B08 深色轨迹另见 311。F20-01 全部九主题的弹窗/禁用态/代码区仍待 |
| 待输入副本重启及恢复失败：310-workflow-clone-wait-restart.txt / .png、311-workflow-restarted-completed-dark.txt / .png | 原改名/发布项及副本草稿均保留，副本图/布局/已保存状态保持；同 db1093c5-1d75-47a7-8ade-af9c082a4e18 仍待输入。点击合法恢复后实际 run.finished status FAILED、output null，明确“工作流协调轮次已结束，不能重新执行” | F15-07 定义保持通过；真实恢复缺陷 B10 使 F15-06/F23-04 失败。311 文件名含 completed，但实际不是 COMPLETED。午夜真实错误轨迹可读，B08 深色回归完成 |
| 终结副本删除：312-workflow-terminal-delete-dialog.png、313-workflow-terminal-deleted.png、314-workflow-deleted-reopen.png | 副本因 B10 已 FAILED；确认仅删 E2E_人工输入流程 副本，313 列表移除且提示工作流已删除；314 重开中心仍无副本，系统两项与原发布项保留 | F15-09 D02 核心改名/保持/取消/未终结保护/终结删除/重开修复后通过；当时迟到保存未专测；后续423–425拖动后立即删除/重开无复活路径通过 |
| 定时草稿与空提示：318-schedule-entry.png、319-schedule-new.txt / .png、320-schedule-empty-validation.png、321-schedule-interval-saved-paused.png | 初始 2 内置任务；新草稿暂停、0/0、无运行历史，保存空提示明确“任务提示词不能为空”；填 E2E_间隔任务6729、2 分钟及只回复专用文本提示后保存，仍暂停且未执行 | F14-01 通过；F14-03 空提示校验部分，创建不等于真实触发 |
| 暂停任务手动运行：322-schedule-run-once-confirm.png、323-schedule-running-paused.png、324-schedule-history-start.txt / .png、325-schedule-interval-result.txt / .png | 确认明确只运行一次且不重新启用，先拒绝再同意；实际运行完成、回复 E2E_SCHEDULE_INTERVAL_6729，历史 2026-10-04 16:26:45、“答复已交付”、10.0s 与备注对应，累计/失败 1/0，任务仍暂停 | F14-05 通过；Token/备注列可见但无具体 Token 数值，不编造值、不泛化自动触发。默认持续执行、通知关闭/不通知及无人值守高风险授权说明可见，F14-04 选项未展开仍待 |
| 一次性保存/启停与非法时间：326-schedule-once-invalid.png、327-schedule-invalid-enable-feedback.png、328-schedule-once-saved-paused.png、329-schedule-invalid-toggle-feedback.txt / .png、330-schedule-once-enabled.png、331-schedule-once-paused.png | 326/327 只保存暂停的未完整草稿，不能当启用失败；329 实际启用非法时间拒绝并说明 yyyy-MM-dd HH:mm，仍暂停。合法 2026-10-04 23:55 启用后下一次时间对应，立即暂停后下一次为空 | F14-02/03/06 部分完成；不能把允许暂存草稿报 Bug，未等待实际一次性自动触发 |
| 每日及 Cron：332-schedule-daily-input.png、333-schedule-daily-saved.png、334-schedule-cron-input.png、335-schedule-cron-invalid.png、336-schedule-cron-saved.png、337-schedule-four-reopen.png | 每日 23:54 暂停保存；非法 Cron 在启用状态保存被拒，明确需要 Quartz 6 段；手工合法 0 53 23 * * ? 标有效并暂停保存。重开列表四本轮任务均暂停，原 2 内置启用未改 | 四触发字段/保存/重开部分覆盖；Cron 预设、非法日期/每日时间/非正间隔、未保存提醒、删除及自动触发仍待。四暂停任务尚未清理 |
| B10 新实机基线：338-workflow-b10-new-rename.png、339-workflow-b10-rename-dialog.png、340-workflow-b10-run-dialog.png、341-workflow-b10-wait-start.txt / .png | 16:28 修复只编译、16:34 经 IDEA 源码加载；从本轮图建新草稿 E2E_B10_等待恢复6729（wf-dbbee54b-791b-4448-9468-11deb8faaad3），输入 E2E_B10_初始6729，16:38 同 5bf39622-f82c-487a-a539-334bd6686ad4 在人工节点 WAITING_INPUT、无错误 | 仅新待输入基线；必须跨 30 分钟并正常退出/IDEA 重启恢复原 ID，17:10 前不记 B10 修复通过；原 311 失败仍保留 |
| MCP 空态及 stdio 保存：342-mcp-entry.png、343-mcp-new-dialog.txt / .png、344-mcp-required-validation.png、345-mcp-stdio-env-input.png 至 349-mcp-stdio-saved-disabled.png | 初始配置 0；空表单尝试添加未新增。E2E_stdio6729 输入本轮虚构 missing 命令，逐行 --e2e/6729 组合预览正确；添加普通环境变量并手动隐藏为圆点；取消启用后保存为禁用，1 个配置/0 工具 | F12-02/03 字段及保存部分，逐项必填/非法 URL、行删除/取消编辑未覆盖；本轮没有执行虚构命令，不将配置保存算连接通过 |
| MCP HTTP 保存：350-mcp-http-editor.png、351-mcp-http-header-row.png、352-mcp-http-test.png、353-mcp-two-disabled.png | HTTP 切换显示 URL/Header 字段，填 http://127.0.0.1:1/mcp 及普通 X-E2E 行，取消启用保存；列表总 2、均 disabled、0 工具 | F12-02 对应字段切换/保存部分；真实连接在严格隔离策略处被拒绝，见下行 |
| MCP 策略阻塞及状态恢复：348-mcp-stdio-test-error.png、352-mcp-http-test.png、354-mcp-start-blocked.txt / .png、355-mcp-no-match.png | stdio 测试明确“严格项目文件隔离已启用：本地 stdio MCP 已被系统禁用”；HTTP 明确“严格项目隔离已拒绝本机或回环 MCP 端点”。354 启动失败自动勾启用但实际运行 0/1、0 工具，355 已取消勾选恢复 disabled；无匹配搜索显示 0/2 及清空搜索入口 | F12-05 连接/发现/停止/重启阻塞，严格隔离保持开启；不写 missing 命令已启动、不写 loopback 已发生网络连接。F12-01 搜索部分、全部状态筛选未覆盖 |
| MCP 时间模板：356-mcp-template-list.png、357-mcp-template-time.png、358-mcp-template-editor.png、359-mcp-template-saved-disabled.png | 查看模板，选时间后编辑器自动填 uvx/mcp-server-time；改名 E2E_time_template6729 并禁用保存，列表 3、均 disabled。清空搜索后列表恢复 | F12-04 模板审阅/编辑/保存部分，未执行 uvx 或下载 MCP，不把模板工具说明算工具发现 |
| MCP JSON：360-mcp-json-dialog.png、361-mcp-json-invalid.png、362-mcp-json-preview.png、363-mcp-json-imported.png | 非法 JSON 具体报 Unexpected character、需要双引号字段名，列表仍 3；合法对象预览 2 服务器，导入后提示 2 项、0 启动，列表总 5、均 disabled | 本批对象内两条配置导入及无效 JSON 拒绝已验证；后续 437–440 补独立单条名称校验/实际导入，F12-04 现通过 |
| MCP 重开及敏感键失败：364-mcp-edit-restored-fields.png、365-mcp-edit-env-fields.png、366-mcp-token-unmasked-new-key.png | stdio 编辑重开命令/参数/普通 E2E_LABEL 虚构值/停用保持；改普通键为 E2E_API_TOKEN 后值仍显示明文，新增空键值行可见 | 保留 B11 原失败；修复编译/IDEA 411 加载后，427–435 动态敏感键及保存重开实际回归，F12-03 现修复后通过。本轮只用虚构值；F12-06 后续 441–445 补单项删除/复制路径，完整配置复制仍待；其余五项后续 446–454 已清理，运行日志受策略阻塞 |
| MCP 取消及筛选：367–371 的 TXT / PNG | 367 删除空行但 AX 取消未生效；368 实际鼠标取消重开，原 E2E_LABEL/虚构值/无空行保持。369 运行中 0、370 需要处理 0，371 已停止 5，保留 stdio 搜索为 1/5；清空恢复依据为 359 | F12-01 通过；F12-03 取消已覆盖，B11 此时原失败保留，后续 427–435 已回归；不记 367 取消成功或 371 已清空搜索 |
| 插件四页：372–376/378 的 TXT / PNG | 已安装/Agent/服务均 0，市场明确搜索/一键安装/更新尚未接入，实际刷新与重新检测；375 动画叠字不结论 Bug | F13-01 页面空态通过，不能记市场或插件生命周期成功；不开发未接入市场 |
| 插件选择器/搜索：377/379–381 的 TXT / PNG | Agent JAR 及从文件安装系统选择器实际打开并取消；搜索 E2E_无插件6729 空，安装数仍 0 | F13-02–06 缺本轮可信包/详情/权限对象而阻塞；取消已覆盖，不算安装/卸载通过 |
| 设置快捷键/连接：382–384 的 TXT / PNG | ⌘, 实际打开设置，刷新发现 500 模型，列表接口可达 2279ms，明确尚未验证聊天调用 | F17-01/F20-03 仅快捷键部分；F17-03 模型名手输/完整失败重试仍待，不以接口可达替代聊天 |
| Deliverance 入口：385–388 的 TXT / PNG | 未保存切到 Deliverance，无 READY 档案；管理入口实际转插件中心并提示先批准/注册 Deliverance 服务，当前服务 0；测试明确本地档案不能为空 | F18-01–07 具体入口依赖阻塞；没有进入模型/档案/运行/API/别名密钥管理，未下载模型 |
| Provider 切换/丢弃：389–393/398 的 PNG | OpenAI 推荐地址/gpt-4o-mini、临时密钥空，模型列表 HTTP 401；关闭提醒 1 分区未保存并丢弃。398 实际 DashScope/qwen3.8-flash/密钥遮罩恢复；393 仍旧 OpenAI/等待确认，截早 | F17-02 通过，以 398 为恢复证据；F17-03/07 部分，391 连接按钮仍测试中不算最终聊天成功；不展示真实密钥 |
| 高级字段/边界：394–398 的 TXT / PNG | 原 HTTP/1.1、超时 30/120/30、迭代 10/8/5、重复 8/相似 0.8/评估 3.5/重试 2；超时 0/编排 101 保存均拒绝，HTTP/2 切换后恢复 HTTP/1.1/原字段，未保存 | F17-04 部分，Token/全部边界及恢复默认影响未覆盖 |
| 分级模型：399–402 的 TXT / PNG | NORMAL/LIGHT 独立配置 OFF、字段禁用及继承说明可见；清除确认明确回落，拒绝后保留 | F17-05 查看/取消通过，不记实际独立路由调用成功 |
| 思考预算：403-thinking-budget-invalid.png | 1023 保存被具体错误拒绝，当前无效草稿尚在，原 4096 待恢复 | F17-04/07 仍待，不声称全部设置恢复 |
| 思考预算恢复：404-thinking-budget-upper-invalid.png、405-thinking-off-budget-disabled.png、406-model-boundary-discard.png | 65537 保存也被拒绝；OFF 时原 4096 字段禁用，随后恢复 ON/4096。406 确认关闭存在 1 分区未保存，已丢弃 | F17-04/07 部分，原值已恢复；Token/默认影响及成功保存持久化仍待 |
| 正常退出/修复加载：407–411 的 TXT / PNG | 408 原 5bf39622 仍待输入；17:11 托盘正常退出，IDEA 正常退出代码 0；17:12 从 IDEA Launcher 源码 Run 重启，含 B11 修复 | B11 此时已加载，后续 427–435 已完成敏感键原路径 GUI 回归；不是二次启动单实例验证 |
| B10 跨时长重启恢复：412-b10-restarted-wait.txt / .png、413-b10-restart-resumed.txt / .png、414-b10-recovery-trace.txt / .png、415-b10-completed-output.png | 16:38 待输入的同 5bf39622-f82c-487a-a539-334bd6686ad4，正常退出/17:12重启后仍2步待输入；恢复 E2E_B10_恢复6729，完整轨迹人工3→工具4→错误出口DEFAULT5→结束6、run.finished COMPLETED/error:null，没有重跑开始节点 | B10 原路径修复后通过、F15-06修复后通过；F23-04 全量持久化仍待验证，不将回显/错误出口完成算可信任务验收 |
| 恢复安全实际保存：416–419/421–423 的 TXT / PNG | 查看 SAFE/CONFIRM_RETRY；418/419 AX“应用”未生效，仅组合框变化。421 真正应用 CONFIRM_RETRY 后已保存，422 选择开始再返回人工节点仍保持；随后恢复 SAFE 应用 | F15-03 完整字段/非法JSON/可读反馈通过；不把失败 AX 点击记应用成功 |
| 立即删除与重开：420-workflow-autosave-delete-confirm.txt / .png、423–425 的 TXT / PNG | 420 取消删除保留。恢复 SAFE 应用并拖动开始节点后立即点击删除，423确认、424明确已删除E2E_B10_等待恢复6729，425重开无复活，原发布项与内置两项保留 | D02 本次迟到自动保存删除路径回归通过；F15-09仍修复后通过，B10草稿已清，仅原本轮发布流程及历史待清 |
| MCP 重启及敏感键回归：426–430 的 TXT / PNG | ⌘M 打开重启后的 MCP：五配置均停用、0 工具。普通键改 API_TOKEN 立即遮罩，眼睛显虚构值；改 API_SECRET 再遮罩，保存重开仍遮罩 | B11 环境变量原路径修复后通过；F12-03 修复后通过，真实运行未发生 |
| HTTP Header 回归：431–436 的 PNG | 普通 X-E2E=6729 重开保持；新增虚构 Bearer 后改 authorization 自动遮罩，显隐、保存重开遮罩均正常；普通值仍正确，删除新增行更新恢复原 Header | B11 Header 回归通过；没有使用真实凭据 |
| 单条 JSON：437–440 的 TXT / PNG | 无 name 明确要求 JSON 或表单提供名称，列表未改变；补 E2E_single_import6729 后预览 1、实导入 1/0 启动，总 6、均停用 | 结合模板/对象导入/无效 JSON，F12-04 通过 |
| MCP 单项删除及复制：441–445 的 TXT / PNG | 搜索匹配；删除确认拒绝后保留总 6，同意仅删该项回 5。复制真实 data/javaclaw.mv.db 存储路径后粘贴至搜索 | F12-06 部分完成；完整配置复制仍待，剩五项后续 446–454 已清理，真实运行日志受严格隔离阻塞 |
| MCP 五项清理：446–454 的 TXT / PNG | 逐项确认删除已停用的本轮 HTTP、导入 HTTP、stdio、导入 stdio、时间模板；454 全部/运行中/需要处理/已停止均 0、工具 0，恢复初始 | MCP 清理部分完成，完整配置复制及真实运行日志未通过；空列表无清空搜索按钮不作 Bug |
| 托管需求表单：455–457 的 TXT / PNG | 审核菜单后选择手动审核；托管初始 0，空需求明确“请填写任务需求描述”。真实填写三行虚构文档需求/约束、E2E_托管文本6729、本轮 project 目录、120K、none，查看能力菜单并选择自定义 system（root 操作记录） | F16-01/02 部分，目录选择取消及非法预算未测 |
| 托管暂停/继续失败：458-managed-paused、459-managed-continued 的 TXT / PNG | 创建约 2 秒后暂停，任务已出现列表，共 1、提案/0%/Token 0；暂停后底部 Failed to obtain JDBC Connection。继续显示运行中，错误仍在且概览仍显示暂停，未产生可核验进展 | 新严重 B12，F16-05 失败；F16-03/04 部分待验证，托管闭环未通过 |
| B12 后正常退出：460-b12-normal-exit-menu.png、当前数据备份 | 托盘实际退出，root 随后观察 IDEA 已可 Run；关闭状态 data 已备份 | 只计退出及备份，此时修复加载/回归未发生；后续461/462及465–471补加载和部分暂停/恢复回归 |
| B12 首次修复加载及旧运行：461–464 的 TXT / PNG | IDEA从原数据库启动，原会话/旧托管PAUSED正常加载无JDBC；同意workflow_recovery后旧任务提案FAILED，root定位旧子Run300秒到期 | 此批只覆盖数据加载，旧截止时间不记新模型/JSON错误；B12原故障通过由后续468–477补证 |
| B12 最终加载与新输入：465–467 的 TXT / PNG | 最新异常/清理顺序 guard 17:49:08仅编译成功、正常exit0并IDEA源码启动。466正文输入未生效，467重填后AX核对完整三行虚构文本需求/约束、新标题E2E_B12暂停回归6729、专用project、120K/system/none | 只认467完整输入，目录取消/预算边界未覆盖 |
| B12 新暂停/恢复：468–471 的 TXT / PNG | 创建约2秒暂停无JDBC；关重开/继续进入待人工子轮次4aaf99a0，再继续审批workflow_recovery实际同意回运行中。469待人工以随后PNG为证，AX是前一运行帧 | 后续473/476/477补跨页读/取消后，B12原故障修复后通过；完整托管阶段另受B13/B14影响，F16-05仍失败 |
| 托管恢复停滞：472-b12-resume-live-log、475-managed-stalled-reopen 的 TXT / PNG | 实时日志仅两条提案，关重开仍提案/0%/Token0、RUNNING，无阶段完成或真实产物。root源码诊断中间JSON任务误用最终文件契约（B13）及恢复驱动重复settle/终态通知缺失（B14） | F16-03/04部分；B13/B14已18:08仅编译并经483 IDEA源码加载，新任务回归待，不把运行中当完成 |
| B12取消及跨页数据库读取：473-mcp-clean-restart-zero、476-b12-cancelled、477-schedule-restart-persist 的 TXT / PNG | MCP重启后0配置/0工具可读；新托管实际取消至CANCELLED、运行中0，随后定时列表6项可读，均无JDBC错误 | 与468新暂停/469关重开共同完成B12原故障路径回归；F16-05仍因B13/B14失败，未宣称全面线程安全 |
| 定时重启与非法日期/时间：477–481 的 TXT / PNG | 四临时OFF、两内置ON；478一次性日期/23:55/提示词/策略保持。479 AX开关未生效；480真实点击非法日期启用拒绝并回滚，481 25:99同样拒绝回滚原值，提示yyyy-MM-dd HH:mm | 持久化及边界部分；不把479无效输入算校验。每日非法时间/非正间隔、删除仍待 |
| 后台自动一次性运行：482–488 的 TXT / PNG | 一次性保存18:10并启用；483 IDEA源码启动，484主窗关闭到托盘，485菜单/486恢复。18:10:00自动执行、18:10:10答复已交付E2E_SCHEDULE_ONCE_6729，10.0s、累计/失败1/0、自动OFF；四临时OFF/两内置ON | 自动执行功能实测成功。488长备注撑宽历史列、行表头错位/备注裁切为B15；F14-07原失败保留；554–557显示/完整备注回归后修复后通过，原F14-05手动运行通过保留 |
| Cron预设：490-cron-preset-daily、491-cron-preset-weekly、492-cron-preset-hourly、493-cron-preset-quarter 的 TXT / PNG | 逐一实际点击每日09:00、周一18:00、每小时、每15分钟，字段分别0 0 9 * * ?、0 0 18 ? * MON、0 0 * * * ?、0 0/15 * * * ?且有效 | F14-02触发字段/预设通过；四次为未保存草稿，不宣称已保存或恢复原0 53 23 * * ? |
| Cron原值恢复：494-cron-restored 的 TXT / PNG | 预设试用后真正手动恢复0 53 23 * * ?并保存；字段/列表摘要一致、已保存、disabled | 清理状态补证，四任务仍未删除；不把恢复保存当脏关闭/丢弃验证 |
| 新托管安全输入/创建：495–498 的 TXT / PNG | E2E_B13B14闭环6729、专用project、120K/system/none及精确三行虚构文件需求完整，限write/read、单验收/实现项、禁命令/脚本/构建/代码测试。创建约2秒暂停成功且无JDBC | 新完整输入及创建/暂停部分；目录取消/全流程尚待 |
| B16负预算：499-sdd-budget-dialog、500-sdd-budget-invalid、501-sdd-continue-first 的 TXT / PNG | 对话框120000及“填0表示不限制”；root真实输入-1保存后界面预算不限，没有拒绝；随后改回120000/120K再继续 | 保留B16原失败；542加载后545–549输入路径修复通过，F16-06改部分待验证，0/实际耗尽续跑/重跑未覆盖 |
| 提案批准及规格暂停：502-sdd-repeat-resume、503-sdd-proposal-approved-paused 的 TXT / PNG | 502实际完整提案评审保留三行文件/唯一验收/单实现项/全部限制；尝试Pause/Continue遇modal没有操作到。实际同意一次后503真实Pause，提案✓、阶段规格/PAUSED、3.6K及H2 OpenSpec1514ffbd保存 | B13结构化提案成功推进为部分回归；不把评审文本或OpenSpec等同文件实现/验收完成 |
| 规格继续及再次评审：504–506 的 TXT / PNG | 504真实Continue，505再次提案评审相似新内容、是否重跑已完成阶段仍由架构判断；再同意一次后506较晚PNG规格/10.4K/待人工、子轮次e907c242已暂停，TXT仍先前RUNNING帧 | 以较晚PNG记录状态；B14完整终止/无重复步骤回归仍待，不计第二次评审为已确认新Bug或持续正常运行 |
| 快捷键/输入/侧栏/帮助：507–510 的 TXT / PNG | ⌘N新本轮18:28空会话，⌘K聚焦输入草稿E2E_快捷键草稿6729；Esc清草稿，⌘\折叠再恢复，⌘/打开帮助/关闭，再⌘Shift/打开同帮助/关闭 | F20-04通过；F20-03结合382/426四键动作均有证据，但连续触发不重复开窗/保留草稿仍待 |
| 空临时会话清除：511-shortcut-clear-fixture 的 TXT / PNG | ⌘L移除本轮18:28空会话并新建18:30空会话，列表仍30、消息0/ctx0、其他可见项保持 | 511历史空fixture单独不足验收；1223后续仅本轮2消息会话CmdL清空/移除/新0消息及其它侧栏保持补齐，F20-05现通过；新替换会话已1224发送恢复请求 |
| Token悬浮：512-token-hover-zero.png | 今日353K（输入332K/输出21K）、月2.0M（输入1.8M/输出225K）、估算月成本¥0.00、本次运行时会话0、点击可重置说明可读 | 当时只覆盖悬浮；520/521补证后F04-06通过；非真实账单证据，统计口径见下方边界 |
| 模式菜单：513-mode-options 的 TXT / PNG | 菜单对话/研讨/循环/工作流/命令五项完整可见 | F06-01部分，逐项切换/输入保持未由展开菜单证明 |
| 导航与首次向导范围：48个人菜单及分散管理中心证据 | 菜单九入口均已打开/关闭返回，后续507–513主聊天输入可用；本轮已有配置启动没有首次向导 | F01-04基础导航通过，不泛化全部业务；F01-05未出现按条件阻塞，不重置数据 |
| 研讨四档与真实答复：514–519 的 TXT / PNG | 515AUTO、516STANDARD、517DEEP、518QUICK与实际标签一致；QUICK纯文字虚构讨论，519优缺点与组合结论已交付/完成13.5s，3,001tok、输入2539/输出462 | F06-02通过，四档仅选择、一档实际运行；519文件名含hover但PNG无tooltip，不当非零点击重置证据 |
| 非零Token重置：520-token-hover-nonzero.png、521-token-reset-fixture 的 TXT / PNG | 真悬浮会话9.1K/今日362K/月2.0M/估算¥0.00；实际点摘要归0/ctx0，今日362K及原研讨用户/助手2条消息保持 | F04-06通过，限定工作区runtime口径；估算成本不作账单证据 |
| 循环模板：522–526 的 TXT / PNG | 三模板选中填max10 judgeon、interval5m max20 judgeon、interval30s max20 judgeon及说明后Esc清草稿 | F06-03通过；模板绝未发送，构建/代码测试未执行 |
| 实际两轮纯文本循环：527/528 的 TXT / PNG | max2 judgeoff/明确最多两轮；两轮宣传语改进/推荐真实完成29.1s/7,493tok，第2轮Harness和显式准则通过、处理完成 | F06-04通过，不泛化文件/工具验收 |
| 长循环真实停止：529–531 的 TXT / PNG | 529/530第1轮推理、新正文空；531真正点击停止后62.4s已取消/4,962tok，已有1–40段正文保留、后续第2轮已停止、发送恢复 | F06-05停止部分通过，普通对话恢复/无重复排队待；运行中正文增量未实测，F04-04仍部分 |
| 模式切换/草稿保持：532-mode-workflow-draft-retained、533-mode-dialog-draft-retained的TXT / PNG | 同31会话/4消息，E2E_MODE_DRAFT_6729切工作流后保持、出现原发布流程选择器；切回对话同草稿保持、对应控件隐藏 | F06-01补充部分，未逐模式带草稿核对全项 |
| 澄清与停止后聊天：534–538的TXT / PNG | 534实际前缀连原draft；535仍旧正文；536点击新消息后普通回复询问温暖/幽默等待，537回复温暖，538最终“青橙灯下，共读生暖”、10.4s/3,354tok、处理完成及发送恢复，无文件/外部操作、无旧循环重复排队 | F07-04、F06-05通过；没有独立选项按钮，不虚构点击选项 |
| 旧托管受阻/取消未执行：539-sdd-context-empty-paused、540-sdd-old-cancelled的TXT / PNG | 两帧均仍1514ffbd规格、子轮次e907c242受阻、0运行/1待人工；精确取消按钮不存在，动作未发生 | 540文件名错误，不记CANCELLED；旧三托管仍待清理，F16闭环未通过 |
| 修复编译与正常退出：541-tray-before-fixes-restart.png及root记录 | 18:46:05统一mvn -DskipTests compile成功、6.873秒；541实际托盘显示退出入口，随后root正常退出 | B13补充/D03/B15/B16待IDEA加载及GUI回归，编译和托盘菜单不算功能通过 |
| 新源码与预算回归：542-source-restart-fixes、545–549的TXT / PNG | IDEA启动加载B16；-1、1.5、Long溢出保存均拒绝且弹窗保持，取消重开原120000；合法120001保存重开后真实保持，再恢复120000 | B16原输入路径修复后通过；F16-06仍有0/耗尽续跑/重跑未覆盖，改部分待验证 |
| 新托管创建/真实失败：543/544/550–552的TXT / PNG | 安全完整需求创建E2E_D03恢复闭环6729，约2秒Pause、550Continue；552明确提案结构化JSON无效、FAILED/0%/2.5K/1m31s、尚无OpenSpec，共4任务 | F16-03按创建与状态观察范围通过；无提案审批/文件写入，空catalog误停消失只部分回归，B13格式prompt已19:26:19统一compile、待新GUI；D03/闭环仍待 |
| 历史布局及完整备注：553–557的TXT / PNG | 重启两内置ON/四本轮OFF，四列正常/实际拖窄100逻辑像素仍对齐并省略；555短悬停无tooltip不算通过，557真正2秒悬停完整答复已交付/专用回复备注 | B15及F14-07原路径修复后通过，保留488失败证据 |
| 再次手动运行确认：558/559的TXT / PNG及root动作 | 558虽误名interval，实际一次性确认被物理拒绝、未执行；559改选间隔只见确认弹窗 | 不增加新运行次数或将待批准当执行；后续真实多条历史另待证据 |
| 第二次间隔手动运行及新footer缺陷：560/561的PNG（560另有较早TXT） | 终态PNG两行19:12:55/15.5s、16:26:45/10s均交付专用答复且对齐，仍OFF/2/0；561旧行完整备注。窄860逻辑像素底部长状态把关闭/保存挤成… | F14-05/B15既有通过保持；新B17/F14-08失败，560较早TXT不能证明第二次完成 |
| 零间隔误启用及恢复：562–565的TXT / PNG及root操作/log | 真正输入0并ON/Save后564静默变1分钟且启用；565立即OFF/恢复2分钟。19:14:31启用至19:15:07关闭无额外自动开始，最近仍手动19:12:40→19:12:55 | B18/F14-03失败，四文件修复/双review后19:26:19仅编译待GUI；564误名rejected不表示拒绝 |
| 设置实际入口：566–570的TXT / PNG | 566/567⌘,未开；568/569个人菜单实际打开。空tooltip窗口曾被helper误选，已过滤非空标题 | 仅认菜单入口，F20-03连续快捷/草稿保持未因此通过，不扩列产品Bug |
| 假站点表单与保存类加载失败：571–577的TXT / PNG | 初始0，填写专用显示名/invalid域名/登录URL/用户名/假密码及备注，默认遮罩；576/577实际类加载失败SiteCredentialValues、仍0 | F19-01部分待重启重试；失败编译期间旧应用产物半更新，不记产品Bug或持久化成功 |
| 编译修正与正常退出：578/579的PNG及root记录 | 19:24:50 SDK Handler泛型导致compile失败，修正后19:26:19成功/5.768秒；578托盘入口后正常退出，root观察IDEA exit0，579PNG实际为Codex桌面 | D04/B17/B18/B13格式prompt只编译完成，待新IDEA源码启动/GUI；不将579误当IDEA控制台截图 |
| D04首次加载/正文部分及新失败：580–586 PNG | 19:27:58 IDEA源码启动；581/582上下文阶段、583约50s无body；584真实回复中1m43正文22–30可见，585/586 2m6 FAILED/No value present、正文清除、1用户消息、输入发送恢复 | 有运行中正文显示部分证据，但整轮最终失败、新版本停止未测，F04-04/D04仍失败；rawdelta138及SDK merge根因为root只读定位，不作为完整通过 |
| B18严格间隔原路径：587–593 TXT / PNG | 重启2分钟OFF/历史2/0；0保存/ON均拒绝且字段0保留，负数/小数/Int溢出/空白保存拒绝，恢复2分钟OFF保存 | B18原路径修复后通过；与已有日期/Cron/空提示和每日校验合并F14-03修复后通过 |
| 每日时间/策略及通知选项：594–598 PNG | 暂停25:99可暂存，真正ON则HH:mm拒绝并回OFF，恢复23:54；两策略只查看、七通知渠道临时ON展开后恢复OFF/不通知Save，未发送外部通知 | F14-03其余类型补全、F14-04通过；不把暂停草稿暂存或只看策略当自动目标执行通过 |
| 未保存关闭未形成证据：599-schedule-unsaved-close-confirm.png | 实际主聊天，无确认；root粘贴后没有观察到字段变更 | 文件名不算确认，F14-06提醒仍待重试，无新Bug |


| 假站点校验与保存：600–604 PNG、603 TXT | 必填空表确定禁用；javascript URL被明确http/https错误拒绝、列表仍0；合法假站点成功0→1，默认密码遮罩，重开字段保持，未登录/无会话时重置禁用 | F19-01通过；577旧半更新类加载现场不计产品Bug，未连接外部服务 |
| 假密码显隐与编辑：605/606 PNG及root恢复动作 | 眼睛按钮仅显示本轮假密码后立即复遮；备注E2E_站点编辑恢复6729真实保存，列表一致/已更新站点 | F19-02部分，重置取消/删除未做，1个本轮假站点待清理；不作真实凭据或外部登录证据 |
| SDK兼容修复加载：607/608 PNG及root IDEA AX/compile记录 | 正常托盘退出、IDEA实际exit0；应用关闭状态19:45:16 compile成功/5.668秒，608IDEA源码Run加载SDK工具碎片兼容修复 | 仅编译和加载，D04完整长回复/最终提交/停止及B17原窄footer仍待GUI，未运行测试 |


| SDK修复后的正文交付缺陷：609–611 PNG、623 AX及root trace | 新真实运行69.1s/5,697tok、输入2554/输出3143，完成且无No value present，却只显示95字已完成摘要，没有请求的60段正文；trace同一Generation content4234字/userMessage95字，仅1primary、无repair | SDK异常原路径消失只是局部回归；D04正文交付仍失败，prompt两文件修复双review后编译中，不标完整流式通过 |
| 邮件预设与边界：613–620 TXT / PNG | 原QQ/465/993/SSL与空账号/码/发件人；五预设及三加密项实际查看，163/Gmail/Outlook正确填字段、自定义保留值；NONE仅草稿后恢复QQ/SSL。端口0保存明确拒绝、0仍保留，再恢复465/原配置保存 | F19-03表单通过；没有真实凭据，无SMTP/IMAP网络/收发通过证据，未点击测试收发、未发送邮件 |
| 邮件脏配置关闭：621–623 TXT / PNG及root退出观察 | 原配置保存后填本轮假邮箱，关闭明确1分区未保存；拒绝保持窗口和假草稿，再关闭同意丢弃回主窗；其后正常托盘退出、IDEA exit0 | F17-07关闭提醒/拒绝/丢弃已覆盖；原空邮箱成功保存后的重开/重启复核待，假地址不当真实收件人 |


| 新协议加载及规划误停：624/625 PNG、root trace | 19:58:09compile成功/5.68秒、IDEA源码启动；含纯聊天示例C:\path请求18.4s在主模型前暂停。LIGHT false/RESOLVED/criteria[]却reasonCodes为NO_ACTION_REQUIRED，被strict判INVALID_PLAN，NORMAL15秒repair超时 | 新B19，根因为规划无动作字符串与严格契约不符，非路径artifact误判；三字符串提示最小修复双review待compile，不降低strict |
| 正文增量到完整交付：626–634 PNG及root trace | 新会话首primary自由content3642/no toolCall→missing-decision repair；630 2m53回复中段27–35可见，631 3m7交付至60/✓/2条、5506in9289out；首段青橙及第二段引号/🌊、滚动底部60保持，无SDK异常 | D04正文交付原路径部分回归成功，完整项另等待停止后续聊及无迟到回填；632/633稳定用户末两行覆盖助手header为B20 |
| 全文复制：635–637 PNG、636 TXT | 更多菜单复制全文/保存文件/删除，实际复制全文粘输入框；证据原文1–60中文、🌊及末>✓均保持，Esc清草稿 | F05-02复制完整/特殊字符部分与F05-03菜单关闭已覆盖，保存文件/重新生成/不同复制形式尚未实操 |
| 停止时间边界：638–644/647–650 PNG、642 TXT | 80段641生成中13–23，642按钮动作时已自然完成2m7/80，不算取消。随后200段648 3m5仍无正文，649真正停止后3m51已取消/send恢复、旧60+80保持且200不交付；650短消息已发送 | 真停止及原文保持有证据，短消息终态和无迟到回填待后续，F04-04仍未整项通过；不把642 misnamed cancelled文件当停止成功 |
| 邮件原值持久与通知配置：639–646 TXT / PNG | 物理⌘,实际设置打开；640重启后QQ/465/993/SSL、账号/码/发件人全空、无dirty。645/646五通知全部OFF、字段空/禁用、模板${message}及SMTP复用提示，测试禁用，无变更无发送 | F17-07保存/拒绝/丢弃/原值重启保持通过，F19-04配置展示通过；真实邮件或通知收发仍依赖阻塞 |


| 取消后续聊及重新生成：651–653 PNG | 651短答8.3s/2611in138out、7消息、旧60/80保持、取消200无回填；652点Regenerate后653 7.7s/2660in133out，追加相同用户及新助手轮、9消息，旧回复保留 | 结合630增量/631全文及649真取消，F04-04核心原路径修复后通过；F05-03通过，重新生成为追加新轮，未声称替换旧回复 |
| 复制两种形式与焦点缺陷：654–658 PNG、657/658 TXT | 菜单Copy/SelectAll/CopyMarkdown；655选择后输入仍聚焦，⌘C未复制、656仍旧60段不算通过。657真正原文复制取得短文+空行/>✓；658 SelectAll→菜单Copy取得纯文本+✓并粘回、Esc | F05-02菜单复制文本/Markdown及特殊字符通过；键盘焦点独立B21，两调用处requestFocus修复双review待compile/加载/GUI |
| GEPA及技能进化：659–671 TXT / PNG | 659首展开无效、660再点实际导航。GEPA原两ON/3/3.5/2，0间隔/0阈值/11轮次各保存拒绝并恢复保存，671原值保持；技能原提案5/0.6/两辅助ON，关闭/AUTO仅草稿未保存未运行，0最少/2成功率拒绝，670恢复原值保存 | F19-05/06具体操作通过；错误实际为格式不正确，不夸大明确范围提示或全数值端点；无自动落盘 |
| 退出后仅编译：672 PNG及root IDEA观察 | 托盘退出后IDEA Run12可用且Stop不存在，进程已停止；本次无exitcode文本，B19/B20/B21仅-DskipTests compile进行中 | 退出事实已证，不编造exit0，不把待编译的修复当GUI通过 |
| 新修复源码启动：673 PNG及root编译观察 | 20:27:52仅-DskipTests compile成功/5.961秒，随后在IDEA Launcher Run加载B19/B20/B21；原9条长回复/短答仍在 | 编译不是GUI通过；原路径回归另由674–683证明 |
| B20长消息布局：674–677 PNG | 宽窗口完整用户正文不压header；675仅缩窄瞬时一帧不作缺陷；676稳定窄窗口完整换行无重叠，677恢复宽窗口正常 | B20修复后通过；此时F05-06折叠未覆盖，改待验证。后续1142–1147折叠及1202–1205真实长代码wrap完整/表格/段落滚动补齐，F05-06现修复后通过，无水平bar是wrap设计 |
| B21键盘复制：678–681 PNG、681-b21-cmd-copy.txt | 恢复短答菜单、全选真正聚焦正文；预置不同剪贴板哨兵后⌘C复制短纯文本及✓，GUI粘回并Esc清除 | B21原键盘焦点路径修复后通过；保留655/656失败，F05-02菜单复制既有通过继续有效 |
| B19纯聊天规划：682/683 PNG及root trace | E2E_引用路径6729含引号/🌊及示例C:\E2E\海边，9.6s正常终态2646in/123out，无FILE契约/暂停/业务调用；实际模型单段、省标签/引号 | B19规划修复后通过；不宣称逐字两行回显通过，基础聊天既有验收不扩张 |
| 托管真实重跑与显示问题：684–687 TXT / PNG | 4项任务中选552已失败项，686↻真实RUNNING却详情仍旧terminal JSON错误/等待人工卡，且耗时1h38m为创建年龄；687新FAILED/6K、空OpenSpec | B22旧结果未清/F16-04失败；B23耗时语义/F16-03失败。创建/阶段/Token已观察；修复源码未加载GUI。严格JSON新失败不是B22或parser Bug |
| D05格式修复候选：687 TXT / PNG及root trace | 本轮1054字符合法外层harness、内层proposal JSON缺末}，canonical无污染，严格parser正确拒绝并显示FAILED，无批准/文件 | 单独记录有界阶段格式修复能力候选，GitHub研究及实现准备中；不得自动补字符串、放宽guard或计完整SDD通过 |
| 定时原值复查：688/689 TXT / PNG | 总6/两内置ON/四本轮OFF；间隔2分钟、持续策略、通知OFF/不通知、无人值守OFF，历史2/0/末19:12:55及安全回复提示保持 | 仅实际重开与字段持久化；尚未重新RunOnce在窄窗口检查长footer，B17/F14-08仍失败待回归 |
| B17窄长底部原路径：690–695 PNG / AX | 安全RunOnce确认后实际同意，RUNNING→20:48:31完成3/0仍暂停；860及最小780宽三按钮完整、状态省略；694完整完成tooltip，695真正窄窗口保存显示已保存 | B17/F14-08修复后通过；699重开宽窗口，未将编译或先前688访问当回归 |
| B24真实未保存关闭：696–699 PNG、699 AX | 696原生焦点green名称框真实⌘A/V输入E2E_间隔未保存6729，左列表原名；697点击关闭即主界面无询问；699重开原E2E_间隔任务6729，修改静默丢弃 | F14-06失败、新B24定位修复中；599原输入未证仍不作为Bug，四定时删除待 |
| 两诊断命令：700/701 AX / PNG | /诊断打开，红色关闭后/diagnostics重开、实际查询；无模型调用，原2消息/45会话保持 | F22-01通过；只验证入口，不将事件0或导出入口当业务闭环 |
| 诊断查询与选项：701–703 AX / PNG | 24小时全部事件0，703全部时间仍0；702四时间选项、703五事件选项实际可见 | F22-02失败/D06确认，结合源码证明canonical事件断连；未验证有结果的智能体/事件/关键字命中或失败重试 |
| 旧诊断组合筛选：704 PNG | 全部时间/model_call，填不存在智能体E2E_no_such_agent6729及关键字E2E_no_such_event6729查询0 | 仍旧JSONL断连源，不能证明过滤正确，F22-02/D06失败保持 |
| 诊断导出原生取消及保存：705–717 PNG、717 AX | 705选择器/706实际Cancel无文件；707–715 helper焦点/修饰键问题不列产品Bug，715选本轮目录；717真正Save反馈完整实际路径与119KB，名称拼接来自未有效⌘A | F22-03取消及GUI路径/大小部分已覆盖；内容trace/脱敏metadata仍待，不提前整项通过 |
| 导出包条目元数据：root unzip-list观察 | 仅javaclaw.log 1228117B、task.log 13816B、javaclaw-agent.redacted.properties 1604B、system-info.txt 356B，共4项，没有agent-trace；最终仅1个zip | D06事件导出断连再证；未读raw logs，不宣称无秘密，取消无产物由root真实操作/文件元数据核对 |
| 待修复加载前正常退出：718 PNG及root IDEA观察 | 通过托盘退出，IDEA实际exit0；B22/B23/D05/B24/D06双reviewready，21:14仅-DskipTests compile进行中 | 退出完成，尚未源码启动/GUI回归；编译准备和静态review不计通过 |
| 修复源码加载：719 PNG及root IDEA观察 | 21:15:34仅-DskipTests compile成功/6.484秒/1483源码；IDEA源码Run加载B22/B23/D05/B24/D06 | 不执行代码测试，编译/加载不单独计功能通过；B24/D06未GUI回归 |
| 托管重跑与详情：720/721 AX/PNG、722/723 PNG | 四本轮任务仍在；同7fe已结束任务↻后RUNNING，旧terminal JSON错误卡和等待人工状态消失，创建年龄“自创建2h18m/至今（含等待）”准确标义；新提案评审完整保留单文件/三行/sys_file_write/read/无命令脚本测试约束 | B22/B23原路径修复后通过，F16-03修复后通过；F16-04完整验收/实现清单仍待验证；合法提案不能证明D05 repair实际触发 |
| 托管批准及暂停：724 AX/PNG | 仅Allow一次后Pause实际为SPEC/提案✓/Token9.9K/预算120K，正文保留；“已暂停”卡对应当前控制状态 | 有效提案推进及暂停部分回归，不代表最终产物或完整托管完成 |
| 预算控制：725/726 PNG、727/728 AX/PNG | 已用9894（8077输入+1817输出）；0保存重开确为0；1保存Continue真实NEEDS_HUMAN并明确9894/1耗尽；提升120000后续跑再Pause保留SPEC/提案✓，无重复提案Gate/Token增加 | 结合545–549非法校验、721终结重跑，F16-06通过；控制通过不等同最终SDD完成 |
| 已批准后正常退出恢复：729 PNG、731 AX/PNG、732 PNG及root IDEA观察 | 正常托盘退出/IDEA实际exit0，随后IDEA源码Run启动；731重新选同7fe为PAUSED SPEC/9894/120000/提案✓，受限正文保持；732Continue为RUNNING SPEC/提案✓且无再提案Gate | D03已批准后恢复至规格原路径修复后通过，完整计划/实现/终止尚待，F16-05仍失败、F23全量持久化待验证；730只激活未选不作依据 |
| 恢复后计划评审：733 PNG、734 AX/PNG | Gate实际1个artifact_exists验收场景/criterionPredicate=E2E_MANAGED.md及1个action，完整三行/限定本轮目录/sys_file_write/read/无命令脚本构建测试限制；根代理物理同意一次 | 已恢复推进至计划审批，最终实现/验收仍待；proposal/spec/plan实际trace首次合法repair=false，不计D05触发 |
| 受限工具审批：735–737 AX/PNG | 735拟切实时日志时工具modal已出现；736再次核对sys_file_write为本轮绝对路径E2E_MANAGED.md和精确三行原文；737根代理原生点击同意、modal消失，GUI仍RUNNING/阶段规格/23K/120K | 不将735命名当日志tab完成，不将737批准当写入/读回或最终验收；F16-05保持未完成失败 |
| 托管实施与投影原失败：738/739/744 AX/PNG | 738真正实时日志已复用提案/计划确认第1轮1项并到实施，但顶部仍规格/实现清单—；744验收页仍未生成，后台SpecStore已有1criterion/1plan | 新B25投影滞后，F16-03/F16-04失败；最小Controller修复独立reviewready尚未compile/IDEA/GUI，不把日志操作进展当全项通过 |
| 重复write与人工处理：740–743 AX/PNG及root运行trace | 正确本轮20261004目标写/read成功，但freeze误为20260104，宿主严格UNVERIFIED后重复write；root两次拒绝后743 NEEDS_HUMAN/0运行/2待人工 | B26可信workDir+确定性目标grounding修复ready，旧journal不改；741被modal挡住，不是成功Pause，完整实现验收仍失败 |
| 未保存关闭取消/放弃：747–754 AX/PNG | 原间隔2分钟/OFF/3/0；真实修改后748出现三选择，749Cancel保留草稿dirty；750选放弃Close后753真正重开原列表名、754原详情保持 | B24取消/放弃原路径通过；751仅main/过快重开未成功，不作为数据证明 |
| 未保存关闭保存：755–759 AX/PNG | 改E2E_B24保存回归6729后Close，756 root确认默认保存并继续真关闭；757/758重开新名/OFF/3/0保持，759恢复原名实际Save/AX已保存 | B24保存原路径修复后通过；F14-06含四任务删除未覆盖，整项待验证；748/750/755“账号：”另记B27待修复加载 |
| canonical诊断查询：760–764 AX/PNG | /诊断打开；761异步placeholder排除，762稳定2000条真实core.run/task/model/tool；system.default+E2E_MANAGED.md+tool_result组合764稳定12条，含正确本轮write/read成功33字符及错误目录读取 | D06查询命中原路径已回归，不将上限2000当全库总事件数 |
| 诊断过滤与空态：765–771 AX/PNG | 765未稳定空态排除，766稳定不存在关键字0、767不存在智能体0；768四时间选项，769最近15分钟ALL清空其他筛选8，770最近1小时model_call125，771全部时间error29 | F22-02修复后通过，真实无匹配/事件/时间/智能体/关键字与清空筛选有实机结果 |
| canonical诊断导出：772–774 PNG/AX及root zip元数据 | 原生Save Where本轮target、正确basename输入后真Save；稳定GUI导出E2E_diagnostics_D06.zip（12369KB），按钮完整可读；5项含agent-trace.jsonl 31937655B+logs/redacted配置1604B/system-info | 事件导出产物已回归，trace/脱敏配置只读审阅进行中，F22-03待验证；不宣称rawlogs无秘密，不将717操作拼接名重现为产品Bug |
| B25/B26/B27源码加载：775/776 PNG及root IDEA观察 | 775真实托盘退出/IDEA exit0，22:00:43仅-skipTests compile成功/6.387秒/1483源码，776 IDEA源码Run加载 | 无代码测试；后续功能结论仅实际GUI |
| 托管重开浏览：779–781 AX/PNG | 同7fe重开/重新选后顶部实现、清单0/1、验收场景1；验收页实际Given/When/Then及artifact_exists待核，清单1完整action对应真实持久文档 | F16-04四页浏览通过；旧controller重开也会requestDetail更新，不能据此证明B25运行中动态跨阶段修复，F16-03仍失败待不关窗口原路径；待核内容可见不等于最终核验 |
| 严格错误目标拦截与修复超时：782–785 AX/PNG及root trace | Continue直接IMPLEMENT无proposal/planGate；LIGHT错误60104目标被UNGROUNDED_FILE_TARGET拒绝，没有toolstarted；NORMAL15秒repair超时后待人工，785子轮次f4需核结果 | B26guard部分回归，完整SDD F16-05仍失败；783新表单打开时运行已0，不算运行中保页；D07受控时间预算修复ready未加载 |
| 三选择标签/非法草稿回归：786–798 AX/PNG | 重启六项/原字段保持；788真实“选项：”；789 Cancel保留非法0草稿dirty；793–798实际放弃/稳定关闭/真重开原2分钟OFF3/0无dirty | B27原标签修复后通过，B24非法草稿丢弃补充通过；790–792过快旧draft不是放弃proof或产品Bug |
| 临时调度删除：799–812 AX/PNG | 801拒绝删除保持6；803间隔删到5、806每日到4、809一次性到3、812Cron到2，仅原系统命令清理/习惯回顾ON | 四本轮任务已GUI清理；删除后最终重启核对/不再触发尚待，F14-06/全量F23暂待验证 |
| 诊断及保护会话元数据：readonly-diagnostics-and-session-metadata.json | 包5项/6876事件/138runs/JSON错误0；已识别凭据pattern0、2payload隐藏；配置53项/4mask含2API+2预算保守匹配；138rollout join全匹配/0scope mismatch，47非维护+30维护；原备份临时只读DB副本SELECT核7原会话ID/title | trace/config已核已知脱敏范围，rawlogs仅大小不认证无秘密，F22-03待验证；scope来自join非ZIP行字段，纠正旧43/34推断；无生产DB/API/测试/凭据正文读取 |
| 下一次编译退出准备：813 PNG | 本批只有托盘菜单打开，尚无随后实际退出/IDEA退出码/D07 compile或源码重启结果 | 不推断动作前准备为已完成操作 |
| 当前ZIP日志安全只读审阅：metadata.logSafetyInspection | 原ZIP javaclaw.log9385行/task.log104行在内存扫描，10类credential模式两份均0、未分类候选0；无原文/凭据值输出，无候选需实际值比较，未访问活配置/解密 | 结合已有trace/config审阅，F22-03修复后通过：当前样本未观察具体泄露，不能认证任意无标签秘密/未来日志永远安全；不虚称literal comparison已执行 |
| D07源码加载及预期恢复审批：814–818 AX/PNG | root提供22:27:02仅compile，814 IDEA源码Run加载；817Continue预期workflow_recovery Gate，818根代理真实同意后sys_file_write正确本轮61004路径/33字符精确三行审批 | 只记录真实恢复和正确请求，D07修复分支是否触发及最终验收仍待 |
| 同一托管详情运行：819 AX/PNG | 根代理真实同意write，modal消失，同一详情保持打开为RUNNING实施0/1 | B25实现→验收/终态动态原路径未完成，B26完整grounding和D07结果未确认；不推断审批为写/read或完成 |
| B25同窗动态原路径：819–821 TXT/PNG | 同一详情保持打开，实施0/1→未经关闭/重开动态100%/编排结束/清单1/1/46.2K，821验收场景1真实✓ | B25动态修复后通过、F16-03修复后通过；不同于779–781仅重开requestDetail浏览，整体UNVERIFIED另属D08 |
| B26/D07真实坏目标修复分支：root 8ce5cbf4/01d96bf8 trace | LIGHT7.316秒错60104被UNGROUNDED_FILE_TARGET拒绝；唯一NORMAL repair15.981秒在新60秒内可靠RESOLVED/0reasons；冻结2criteria目标61004/subject33实际2换行 | B26错误目标拒绝→正确冻结/后置条件及D07原15秒超时分支修复后通过，不放宽guard/预算/Gate，不改旧journal |
| 唯一受限write/read及child验收：root实际trace | 22:33:03唯一write VERIFIED，22:33:14唯一read OBSERVED，22:33:48 child VERIFIED_COMPLETE/2satisfied/2refs/0unmet | 实现child已有宿主可信证据，无重复写入；该child结果不能凭推断替代overall验收 |
| 整体证据聚合缺口：820/821 TXT/PNG及root源码定位 | GUI编排结束·未验证，场景✓却整体待确认“未取得关联可信收据”；SddOutcome.completed固定UNVERIFIED缺child receipt→overall bridge | 新D08功能缺口；架构agent先GitHub研究后修，未实现/GUI，F16-05保留失败；保留已完成记录/旧journal |
| 受限产物GUI内容：826 PNG/root文件元数据 | Finder QuickLook真实E2E_MANAGED.md精确三行，87字节；822–825导航/动画过渡不作为完成终态 | 补实际文件内容证据，不把内容正确当D08 overall已核验 |
| 窗口最小化/还原：828–835 PNG | 828标题栏最小化，832托盘触发恢复动画、833稳定同会话3消息保持；834标题栏双击最大化、835还原约1200逻辑像素宽 | F01-02通过，菜单/动画准备不当稳定恢复证据 |
| 窄窗口及侧栏：836–838 PNG | 实际拖窄约790逻辑像素（渲染1030px/1.303），侧栏自动隐藏，入口点开展开后恢复原1200宽 | F01-03折叠/展开/还原通过；侧栏展开时部分操作省略/正文换行密，不泛化全部窄布局/字体 |
| 搜索/同区重选：839–842 PNG | 无匹配后清空恢复49列表；下拉仅默认区，再选当前项仍3消息/49会话且无switchDialog | F03-02/F02-01通过；后续51为不同实际时点，不推断来源 |
| 创建取消及新临时区：843–845 PNG | 新建Cancel未新增，重新确认E2E_隔离补充6729后仅1空会话/默认qwen3.5-9b/智能审核 | F02-02通过，不将默认模型调用可用性一并判通过 |
| 双向会话/设置隔离：847–852 PNG | 无工具标记发送保存1user+暂停反馈（未配置模型调用失败），返回临时区2消息保持；原区搜标记无匹配且原qwen3.8-flash/手动审核不变，临时反搜原区E2E_引用路径无匹配 | 会话/模型审核双向隔离通过；知识embedding未配置不导入，F02-03含知识整体阻塞，不把失败反馈当模型成功 |
| 第二临时区删除：853–855 PNG | selector排除当前默认，仅临时区可选；真确认删除后仅默认区/51会话保留 | 本轮隔离2消息随临时区清理，原7受保护资源未删；B28多行header另记 |
| 模型手工名/接口重试/默认取消：856–862 TXT/PNG及root实际操作 | Cmd,打开设置，手填E2E_manual_model_6729再恢复qwen3.8-flash；测试稳定列表可达1728ms，明确未验证聊天；恢复默认impactConfirm真实Reject，1dirty关闭由root同意丢弃 | F17-03结合旧发现/错误反馈通过，F17-04恢复默认取消仅部分，Token范围未做；不把862退出菜单截图单独当设置丢弃证据 |
| 新修复编译前正常退出：865 PNG/root CUA IDEA观察 | 托盘AX真正退出，IDEA实际exit0；compile-d08-b28-2312进行中 | 只记退出/编译进度，未有BUILD SUCCESS或IDEA加载，D08/B28不提前通过 |
| D08/B28编译及源码加载：866 PNG/root IDEA及compile-d08-b28-2312.log | 23:13:04仅mvn -DskipTests compile成功/6.415秒/1484源码，866由IDEA运行源码加载D08/B28 | 无代码测试；编译与加载不作为D08整体可信验收通过 |
| B28长标题原路径：867–869 PNG | 原多行自动SDD标题单行显示，867悬停完整原文；868约790逻辑像素窄幅全部右侧按钮可读，869恢复宽度 | B28原失败路径修复后通过，短/空标题尚未测，不泛化全部字体布局 |
| 托管新建目录/预算：870–875 PNG | 目录选择器实际取消回表单；五项预算120K/80K/200K/不限/自定义；-1创建拒绝非负整数，仍四任务 | F16-01目录取消补齐；F16-02新建非法预算补齐，不产生第五任务 |
| 托管通知/能力及创建：876–879 TXT/PNG | 恢复120000，七通知选项最终none；三种能力模式，自定义system,command仅草稿，实际879仅system创建E2E_D08可信验收6729，共五项 | F16-01/-02表单交互通过；没有command执行、通知发送或代码测试 |
| D08新受限任务进展：880–883 TXT/PNG | 881提案实际同意一次，882受限规格/计划生成；883计划Gate显示唯一criterion和唯一action/正确日期20261004及真实三行限制，仍待同意 | 尚无新文件write/read、child或overall可信终态，不将合法计划审批界面计D08通过，F16-05保留失败 |
| D08类型转换原失败：884/885 PNG | 计划实际同意后实现0/1、19.5K、待人工，ArrayNode不能转换CharSequence，尚无工具调用 | D08继续失败；不把计划批准或进入实现阶段当新产物/overall通过 |
| D08类型转换源码加载：899 PNG/root IDEA及compile-d08-json-2342.log | 显式JsonNode/toString最小修复，23:42:26仅compile成功/6.287秒/1484源码，23:45 IDEA源码启动 | 等原任务继续/可信终态GUI，不以编译替代通过 |
| 权限就绪及常驻OFF：890/893/895/896 PNG/root日志 | 原电脑访问ON、三权限Granted；常驻由ON改OFF保存，主窗red close正常退出，日志23:41:02资源关闭 | F21当前无缺权限依赖，可继续真实桌面功能；F01-07/F23-02原ON恢复和常驻全路径待 |
| 重复源码启动：901/902/905–907 PNG | 第二/再次Launcher源码进程exit0；905手动activate才看到原56会话/同2消息；907最小化后未自动恢复原窗可见 | 单实例进程退出部分覆盖；自动恢复窗口仍待F01-06/F23-03，不将手动activate提升通过 |
| 常驻恢复/真实快捷键：910–914 PNG | 910原Tray ON实际保存；911 CmdM实际开MCP，912个人菜单再进入托管；913五项、914原D08待人工/19.5K/cast错误 | CmdM=MCP，不能从911文件名推断进入SDD；ON保存后关闭恢复尾项待 |
| D08原路径恢复：915/918/919 PNG | Continue直接实现不重提案/计划审批；正确PROOF.md write Gate，root实际同意一次 | 不把审批当完成；后921/922终态才作依据 |
| D08可信整体终态：921/922/924 PNG及root Core/H2元数据 | 同一窗口动态100%/1/1/47.9K、overall已核验完成；child29580f1d/parentae8aa5a1 VERIFIED_COMPLETE/2refs/0unmet，00:02:12 binding持久/item DONE/overall COMPLETED | D08关联原缺口修复后通过，F16-05控制恢复至终态通过；write1/read2不符合严格read一次，重启耐久未覆盖，不概括所有业务约束通过 |
| 英文设置搜索原失败：925–927 PNG | model只剩MCP，中文模型匹配3导航，清空恢复 | 新B29缺英文别名，F17-01失败；最小别名修复待编译/GUI |
| 字体及密度：929–933 PNG | 原Native/monoSystem/Cozy；实际FollowSystem、Compact、Roomy，再Native/Cozy恢复，仅本机2界面/1等宽可用 | 此时只有表单中文/布局；1211–1217后续Compact/Roomy主文代码与Native/SystemMono/Cozy恢复均可读无遮挡，F20-02现通过；不臆造未安装字体 |
| 智能体补完整交互：934–942 PNG | 内置字段/系统prompt/能力灰且不可编辑；新虚构专家prompt编辑保存保持，空name拒绝、重复system_default拒绝、合法唯一ID禁用保存 | F08-01/-02/-03通过，新增专家待最终删除/持久化复核 |
| AI补全进行中：943 PNG | 真实点击本轮虚构描述的AI补全，模型busy | 尚无终态/审阅保存，不记F08-05通过 |
| AI补全完成/审阅保存：944–946 PNG | 完整prompt生成且提示保存，实际滚末尾审阅仅虚构摘要/禁工具外部操作命令脚本构建测试，946真实Save已保存 | F08-05通过，没有执行脚本或代码测试；新禁用专家待清 |
| 全模式草稿与短标题：947–952 PNG | CmdN空会话57/0消息，CmdK输入E2E_模式草稿补充6729，研讨AUTO/循环模板/工作流已发布选择/命令/回对话控件匹配，全程同草稿未发送 | F06-01通过，B28空/短标题右按钮回归；不把草稿当保存消息 |
| 连续快捷键复用：953/954 TXT及953–955 PNG | 连续Cmd,单个设置、连续CmdM单个MCP，关闭后同草稿/0消息保持 | F20-03快捷键对应/窗口复用/草稿保护通过；CmdM一直是MCP |
| 维护真实零候选：956–958 PNG | 搜索进入维护，说明只读扫描带标记junit-*，实际扫描0目录/清理按钮禁用，没有删除 | F22-04零候选分支通过；F22-05缺本轮合法可删候选阻塞，没有运行/制造代码测试 |
| 桌面安全请求运行中：959 PNG | E2E_桌面6729真实只读probe+applications，限Calculator、权限ON不修改 | 尚无终态/回传/真实窗口一致性，F21待验证，不以请求文本当工具成功 |
| 人工拒绝与托盘闭环：967–983 PNG/IDEA AX | 969在30.1秒真正拒绝正确本轮E2E_reject6729.txt审批，970授权拒绝终态/发送恢复，975目录5文件无拒绝文件；910恢复常驻ON后978关闭到托盘、979–981恢复同58会话/6消息，983 IDEA exit0 | F07-02、F01-07、F23-02通过；961–964无人超时和965过期点击不算人工拒绝，旧135用户Allow仅算正常审批；137–139独立无人超时有效 |
| D09/B29编译和源码加载：984 PNG及compile-d09-b29-0030.log | 983正常退出后2026-10-05 00:30:58 BUILD SUCCESS/6.885秒/1485源码，仅mvn -DskipTests compile；984从IDEA Launcher源码Run加载 | 没有代码测试；编译和加载不作为功能通过证据 |
| B29搜索/路由/清空原路径回归：986–991 PNG | root实际审阅：model匹配模型配置/分级模型/MCP三项、Enter路由模型；appearance仅界面风格、Enter路由风格；中文“模型”命中模型配置/分级模型/嵌入模型三项；Clear恢复所有分组 | B29原搜索/Enter/Clear故障修复后通过，925原失败保留；F17-01尚缺全分类逐页导航，本批只路由两类，暂保留失败，不虚报全部分类覆盖 |
| D09受限发现可信终态：985/992/993 PNG、993-d09-host-metadata.json | 新专用会话仅probe+applications且限Calculator，不launch/不读其他窗口；真实com.apple.calculator唯一匹配，count=1/total=1/truncated=false/hasMore=false，28.6秒/9672in/859out；两项criteria核验，run564600de-24d3-4b7d-8849-35dea416d2d0 VERIFIED_COMPLETE/2refs/0unmet，probe/apps各1次SUCCEEDED+OBSERVED | 959/960原发现失败保留，D09发现子路径修复后通过；不代替窗口内容返回，F21-02整项待验证；元数据审阅未输出原始日志/密钥 |
| 真实只读桌面预览：994–1000 PNG | launch Calculator/open control=false后996真实预览，997最大化、998恢复正常、999compact/minimized、1000展开还原 | F21-03通过；只读observe本身c6匹配/GEPA0.95不代替整体任务成功，接管及输入未覆盖 |
| 观察契约未完成及真实取消：1001–1003 PNG/root只读分析 | Core缺未经请求的probe/targets证据，targets未调用；snapshot被要求证明非空“Calculator window”但该操作不产内容条件证明。1001 StopPreview仅关旧session，model repair又开1002新session；1002主任务实际取消3m23/158431in/4323out，1003再次StopPreview | 整轮未VERIFIED_COMPLETE，F21-02保持待验证；架构修复分析进行中，不能以observe局部成功/高评分或1001关预览改判完成 |
| 单实例解除最小化及置前边界：1004–1008 PNG/root IDEA观察 | 1004黄钮最小化原主窗，1005同Launcher第二Run；1006 IDEA仍前景，第二console SHOWN/exit0，1007仍误选第二console。1008及root真正主console显示00:44:34 SHOW：showing true→true、iconified true→false、focused false→false | 只证实解除最小化，没有自动置前通过；F01-06/F23-03仍待。B31标准macOS requestForeground(false)最小调用仅静态review通过待加载回归 |
| 模型载入与合法思考预算：1009–1014 PNG | 1009初始空载入不是配置丢失；1010高级页仍原HTTP1.1/30,120,30/10,8,5/8,0.8/3.5,2；1011基本页qwen3.8-flash/key遮罩/发现500/思考ON4096。1012预算1024、1013预算65536实际Save均绿“已保存并生效”；1014恢复4096Save同绿 | F17-04结合394–406非法/开关/HTTP及859–861默认影响说明通过；当前实际唯一Token控件为思考预算，不捏造独立maxTokens输入或缺失Bug |
| 嵌入数值边界及无效首次输入：1015–1020 PNG | 原OFF/OpenAI/API和key空/text-embedding-3-small/1024/5/0.3。1016旧坐标未聚焦维度，实际仍1024/空API拒绝；1017实际维度0红“维度格式不正确”；恢复1024后1018检索0红“检索数量格式不正确”；恢复5后1019分数1.01红“分数格式不正确”。1020恢复0.3仅看四Provider选项、未切换 | 数值非法校验已实测；1016误名zero不作维度0证据，查看Provider不算切换。真实连接缺地址/模型/密钥，F17-06整体待，不能以参数校验替代成功连接 |
| 嵌入OFF保存仍被拒绝：1021/1022及1025/1026 PNG | 1021恢复OFF及全部原字段Save仍红“嵌入API地址不能为空”；1022仅通用分组展开。1025 Close提示1dirty分区，1026实际同意丢弃嵌入草稿回主窗 | 新B32无条件连接必填校验；单文件静态修复/review/diff通过，未编译加载GUI回归。1021文件名saved与实际失败不同，不宣称成功保存 |
| 已有权限实际检查：1023/1024 PNG | 原Emerald/常驻ON/AppAccessON/三Granted，1024真正点“检查并继续授权”后仍三Granted、权限就绪，无系统设置/权限变更 | F21-01当前已有权限分支通过；没有真实缺权限，不冒充缺失权限授权流程 |
| B30–B32回归前正常退出：1027/1028 PNG/root IDEA观察 | 1027实际托盘退出入口，1028实际Exit后root CUA观察主源码进程exit0 | 正常退出已完成；compile-b30-b31-b32-0103.log仅-DskipTests compile截至此批尚未确认完成，三修复未IDEA加载/GUI回归，不计功能通过 |
| B30/B31/B32编译/源码加载与自动置前：1029–1032 PNG/root IDEA观察 | 01:03:30+08:00仅compile BUILD SUCCESS/6.819秒/1485源码；1029 IDEA Run、1030稳定原62会话/同3消息。1031黄钮真实最小化后只activate IDEA再Run；1032未手动JavaClaw activate，稳定实际Launcher自动前景/完整原窗恢复，IDEA第二console01:05:19.709 SHOWN/exit0 | B31原自动置前路径修复后通过，F01-06/F23-03修复后通过；B30/B32尚未GUI回归，不因同次编译/加载提升 |
| B32关闭未配置保存原路径回归：1033–1039 PNG | 1034原OFF空API表单；1035无dirty Save无反馈，排除保存proof。1036 OFF probe仍空API错误；1037 ON Save同错误且dirty/未成功启用；1038真实OFF dirty Save绿“已保存并生效，下一轮对话重建智能体服务”、dirty清除；1039真实Close无未保存dialog回main，截图已视觉核实 | B32原关闭未配置保存故障修复后通过，ON/probe严格保留；F17-06数值及保存子路径已覆盖，成功健康连接缺真实地址/模型/密钥，整体改阻塞 |

## 本轮缺陷及修复状态

| 编号 | 问题 / 处理 | 当前状态与证据 |
|---|---|---|
| B01 | 处理完成后执行规划仍停留“模型正在推理…” | 修复已编译；21-status-result 显示“本轮处理已完成”，本次完成路径 GUI 回归通过；历史推理记录仍可在展开后查看 |
| B02 | AgentConversationRunner 丢弃 ToolExecutionStatus，成功的上下文工具被显示为红色 UNKNOWN | 修复已编译；21-status-result 显示绿色“上下文资料已返回”，本次成功工具路径 GUI 回归通过 |
| B03 | 正常退出并经 IDEA 源码重启后，第二轮助手回复被重复插入一条 | 以 application/chat/TaskResultDisplay 共享展示、chat 薄委托和 Transcript.completed 展示统一进行三处最小修复，不自动清理历史；编译并经 IDEA 加载后，新干净会话 4 条→正常退出重启 86 仍 4 条→第三轮 95 共 6 条→再次正常退出重启 97 仍 6 条，三次合法同文“收到”均保留；原路径修复后通过。F23-04现已据后续各模块保存/正常退出/IDEA重启证据通过，最终清理F23-05/06仍待 |
| B04 | 本轮正常 GUI 聊天触发自动蒸馏日志错误：`cannot create a child Run after its parent ended` | MaintenanceModelTaskGateway 改为独立 root 维护 Run；13:30 蒸馏完成并在 49/50 产生本轮虚构事实，原生命周期错误消失；本次链路修复后通过 |
| B05 | 未配置嵌入模型，记忆总览误报“嵌入服务已恢复”，保存事实误报已嵌入成功 | 49/54 GUI 复现；修复明确嵌入五态和待嵌入反馈，已编译并经 IDEA 启动；88 明确未配置且无回填按钮，89 保存仍 Pending 并提示“事实已更新，等待嵌入”，90 恢复原临时内容且取消编辑未保存；原路径修复后通过。真实嵌入服务仍未配置 |
| B06 | 审批超过 60 秒，后台任务取消，但过期确认弹窗仍能显示同意/拒绝 | 99/100 原失败保留；审批生命周期 5 文件修复，经编译及 IDEA 133 加载后，137–139 第二次审批无人操作，60 秒后自动关闭/取消且无文件，原路径修复后通过。135/136 首次由用户手动同意，只算有效审批；人工拒绝仍未验证 |
| B07 | 同一临时文件已成功写入/读取标记，模型 DONE，但宿主判两项内容完成条件 UNVERIFIED，进入修复重复写入并在 108 暂停 | 103–108 原失败保留。根因是文件效果回执 subject 为空，无法匹配 TaskCriterionV3.requiredSubject 的内容标记；新增 FileContentProof 只保存 SHA256 与长度，不持久化正文，HostEffectReceiptAdapter 在写入后置条件与有界读取时重新观察真实文件生成证明，TaskResultEvaluator 的 file.write/read 仅以宿主证明严格匹配内容，保持大小写/内部空格及目标、能力、状态校验。14:53 编译成功并经 IDEA 176 加载；181 有效批准后，186 两项写入/读取完成条件均核验、106.3 秒正常完成且无重复暂停，原路径修复后通过。旧 original task snapshot is duplicated 本链路未重现，不是本缺陷根因 |
| B08 | 工作流验证错误及轨迹区域浅蓝文字在米白底上几乎不可读 | 原失败 193/195/201 保留；CSS token 修复经 IDEA 229 加载，232/239/259/261 浅色错误与轨迹可读；308 含 B09 修复的 IDEA 重启后，309/311 深色画布与真实失败轨迹亦可读，浅色原路径及深色回归完成。421/422 恢复安全真正应用及重选保持已覆盖，F15-03 现通过；未把 418/419 无效 AX 应用算成功 |
| B09 | 主界面切换午夜主题后，工作流中心仍为翡翠浅色，未继承全局主题 | 270/271 原失败保留；workflow-view.fxml 内层 HBox 的 root 样式覆盖主题变量，一行修复编译并经 IDEA 308 重启后，309/311 Midnight 工作流真实为深色、316 Carbon 同步，317 恢复 Emerald，原路径修复后通过。271 不是深色证据；F20-01按九主界面加代表性浅/深覆盖，1207–1222深色代码/禁用卡/确认框/子窗继承及Emerald恢复补齐，现修复后通过，未要求全部组合 |
| B10 | 人工待输入工作流跨长时间并经 IDEA 重启后，同运行仍显示待输入，但恢复失败“工作流协调轮次已结束” | 310/311原同db1093c5 FAILED/output null保留；根因为人工等待超30分钟消耗执行deadline。4个framework文件16:28只编译、16:34 IDEA加载。新原ID5bf39622-f82c-487a-a539-334bd6686ad4于16:38 WAIT，41017:11正常退出/IDEA exit0、41117:12源码重启，412原ID仍2步待输入。413合法恢复，414完整AX同ID人工3→工具4→错误出口DEFAULT5→结束6、COMPLETED/error:null，415 GUI终态可见、未重跑开始节点；跨时长原路径修复后通过，F15-06修复后通过、当时F23-04范围尚待；现已据后续完整持久化覆盖通过，清理/清理后重启另归F23-05/06。423–425该终结草稿已GUI删且重开不复活 |
| B11 | MCP 环境变量普通键改为敏感键后，值没有自动遮罩，继续明文可见 | 366 真正键盘将 E2E_LABEL 改为 E2E_API_TOKEN 后虚构值仍明文，原失败保留；345–348 手动显隐不代替动态识别。两个 UI 文件 16:57 只编译、IDEA 411 加载后，427 改 API_TOKEN 立即遮罩，428 眼睛显示虚构值，429 改 API_SECRET 再遮罩，430 保存重开仍遮罩；433 Header 改 authorization 自动遮罩，434 眼睛显虚构 Bearer，435 保存重开敏感行遮罩且普通 X-E2E=6729 正确，436 删除新增敏感行恢复原 Header。原路径及保存回归通过，F12-03 修复后通过。367 AX 取消失败不记成功；368 实际鼠标取消已覆盖，只用虚构值 |
| B12 | 托管任务创建后立即暂停，共享数据库通道关闭，列表刷新及继续运行均报 JDBC 连接失败 | 458/459原失败及root17:40 ClosedByInterruptException定位保留。三文件修复：H2DataSource使用async、保持原data/javaclaw.mv.db/embedded/keepalive；SddTaskRunner.close在nested finally清中断后清理再恢复；FrameworkSddAgents在InterruptedException取消持久化后恢复中断；异常抑制/两条catch清理顺序已独立静态复核。17:49:08仅编译并IDEA465加载后，468新任务约2秒暂停无JDBC、469关重开、470/471审批恢复，473 MCP0查询、476实际取消CANCELLED/运行中0、477定时6项查询均无JDBC；原数据库故障路径修复后通过。472/475停滞另归B13/B14，当时F16-05仍失败；后续D08可信聚合及恢复完成已使F16-05修复后通过，1137–1139重启终态保持，1322–1332本轮五托管已清理。463/464旧子Run300秒到期不记新模型/JSON错误。[H2连接模式](https://h2database.com/html/features.html#connection_modes)、[文件系统](https://h2database.com/html/advanced.html#file_system)及[官方issue227](https://github.com/h2database/h2database/issues/227)支持中断风险与async方案依据；async为实验性，本报告只认本轮GUI覆盖，不宣称全面线程安全 |
| B13 | 托管结构化提案/规格受阻：中间JSON误用最终文件验收契约，空目录上下文又被提升为空hostselection | 472/475停滞原失败保留；最初契约/空工具catalog意图修复18:08仅编译并483 IDEA加载，新502/503真实提案✓/H2 OpenSpec1514ffbd推进为部分回归，505再评审、506/539/540规格待人工受阻。最终补充空hostselection及journal恢复，18:46:05统一compile成功，542 IDEA已加载，新543/544暂停、550继续后551/552明确提案JSON无效FAILED；本轮未再因空catalog误停，模型harness.userMessage为自然语言摘要，无提案审批/写入。格式prompt已补并于19:26:19统一compile成功，后580/608/624已有IDEA源码加载；完整新SDD GUI阶段及原路径仍待，不记全通过 |
| B14 | 托管恢复后重复结算已FAILED图步骤，终态投影异常阻止listener通知，UI仍RUNNING | root定位恢复复用同graph visit:2步骤、再次settle被拒，终态投影throw后未释放等待latch；472/475真实停滞保留。GraphAgentTurn/NodeExecutionContext/GraphEngine三文件给每次driver独立attemptId，并以terminal finally保障listener通知，保留工具幂等；18:08仅编译、483 IDEA加载，新498暂停/501继续后503真实提案✓并暂停规格；504继续后505重复提案评审已定位D03，506较晚PNG为待人工/子轮次e907c242暂停。542后新544暂停/550继续，552真实JSON失败终态已更新至FAILED，终态通知部分回归；原已完成步骤恢复及完整终止仍待。参考GitHub LangGraph的[任务标识](https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/pregel/_algo.py)、[循环执行](https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/pregel/_loop.py)及[MIT许可](https://github.com/langchain-ai/langgraph/blob/main/LICENSE)，具体修复按本项目框架完成 |
| B15 | 定时运行历史的长备注撑大列宽，数据行与表头错位且备注被裁切 | 488原失败保留；布局修复/双agent复核后18:46:05只编译、542 IDEA加载。554四列对齐，556真实窄100逻辑像素仍对齐及省略；555短悬停未见tooltip，不作成功，557实际2秒悬停完整“答复已交付；回复：E2E_SCHEDULE_ONCE_6729”。原布局/完整备注可读路径修复后通过，F14-07修复后通过，原自动后台一次性成功保持 |
| B16 | 修改托管Token预算时负数-1被静默保存为“不限” | 499/500负数-1误为不限、501恢复120000原失败保留；542加载修复后545负数/546小数/547Long溢出明确拒绝，548取消重开120000，549合法120001保持再恢复120000，原非法输入修复后通过。719源码加载后725实际已用9894，726 0保存重开确为不限，727预算1真实耗尽/NEEDS_HUMAN、Token不增加，728提升120000继续后Pause保留SPEC/提案✓；731重启预算/Token保持、732继续，结合721终结重跑，F16-06预算及重跑控制通过，最终SDD完成另依F16-05。 |
| B17 | 窄定时窗口完成状态过长，footer关闭/保存按钮被挤压成省略号 | 560窄860原失败保留；footer两文件修复/双review、19:26:19仅compile、580 IDEA源码加载。690 RunOnce确认/691原生同意后RUNNING，692 20:48:31完成3/0仍暂停，860宽按钮完整；693最小780宽三按钮可达、状态省略，694完整tooltip“[20:48:31]运行完成:答复已交付;回复:E2E_SCHEDULE_INTERVAL_6729”，695实际保存已保存，699恢复宽重开，原路径修复后通过/F14-08修复后通过。B24未保存关闭独立，不抵消footer回归 |
| B18 | 间隔0在开启保存时被静默改1并启用，非法输入反而触发排程 | root真实0+ON+Save，564界面1分钟启用/3ON/2/0；565立即恢复OFF/2分钟保存，log19:14:31至19:15:07未见自动新开始。四schedule文件严格正整数UI/usecase/tool修复；非interval不解析隐藏field、存量fallback保持，双review/19:26:19仅编译成功，580已IDEA加载，587原2分钟OFF/2/0保持；588零值Save及ON均拒绝、589–592负数/小数/Int溢出/空白均明确拒绝且OFF，593恢复2分钟保存。596每日非法ON拒绝、合法23:54恢复；原路径修复后通过、F14-03修复后通过。564旧文件名rejected仍为失败证据 |
| B19 | 纯聊天无动作规划使用不存在的reasonCodes，严格契约判无效后repair超时，主模型未执行 | 624/625 18.4s暂停原失败保留；root trace LIGHT requiresAction=false/RESOLVED/criteria=[]却reasonCodes=NO_ACTION_REQUIRED无效，strict INVALID_PLAN后NORMAL15s修复超时，不是pathartifact误判。仅规划三字符串提示最小修复/双review，20:27:52只编译成功、673 IDEA加载。682含引用路径纯文本新请求、683 9.6s正常完成2646in/123out，无FILE契约/暂停/业务调用，原规划路径修复后通过；模型实际单段、省标签/引号，不将逐字两行回显计通过，strict校验保持 |
| B20 | 长用户消息自动高度不足，末尾正文稳定覆盖后续助手header | 632/633稳定两行重叠原失败保留；单BubbleTextAreaSupport按真实wrap自动高度和width重新layout最小修复/双review，20:27:52仅编译、673 IDEA源码加载。674宽窗口用户长消息完整不压header，675为缩窄瞬时一帧不作新Bug，676稳定窄窗口完整换行无重叠，677恢复宽窗口正常，原路径修复后通过。后续1142–1147折叠及1202–1205长代码约300字符wrap6行/END6729完整、表格和01–25段落滚至结束补齐，F05-06现修复后通过；无水平bar按实际wrap设计验收 |
| B21 | 回复SelectAll只改变选区，没有将焦点交给正文，键盘复制仍作用于输入框 | 655蓝选却输入green焦点、⌘C未复制、656仍旧60段clipboard原失败保留；两调用处selectAll前requestFocus最小修复/双review，20:27:52只编译、673 IDEA加载。679短答菜单、680全选真正聚焦，681预置不同剪贴板哨兵后⌘C取得短纯文本“取消后可发送6729🌊”及✓，实际GUI粘回并Esc清除，排除旧clipboard假阳性，原键盘路径修复后通过。菜单Copy/Markdown的657/658成功独立保留 |
| B22 | 托管已失败任务重跑进入RUNNING，详情仍显示上一轮terminal错误卡及等待人工状态 | 686实际重跑RUNNING却保留上一轮terminal JSON错误卡/等待人工状态，原失败保留；SddTaskManager.launch在有效预算/workdir及epoch后清result/taskResult，RUNNING详情防御隐藏旧terminal卡、历史日志保留。21:15:34仅编译后719 IDEA源码加载，721真实再↻同7fe为RUNNING且旧错误/待人工卡消失，尚无规格空态准确；724/731暂停卡对应当前PAUSED、732继续RUNNING无旧卡，原显示路径修复后通过。687内层JSON缺末}另属D05，非B22/parser Bug；F16-04验收场景/实现清单完整内容仍待验证。 |
| B23 | 托管任务自创建年龄误标为耗时，含等待且并非本次执行时长 | 686/687自创建年龄1h38m误标耗时，原失败保留；最小修复诚实显示自创建/至今（含等待）/至最后更新（含等待），年龄算法不改、不宣称实际执行计时器。21:15:34仅编译后719 IDEA加载；721真实RUNNING自创建2h18m/至今含等待，724/731暂停为至最后更新含等待，732继续为至今含等待，原标签路径修复后通过。F16-03验收明确自创建年龄与阶段/进度/Token，不以创建年龄当执行耗时。 |
| B24 | 定时任务名称真实未保存修改，关闭无询问直接静默丢弃 | 696–699静默丢弃原失败保留，599未成功输入排除；719 IDEA加载字段快照dirty/刷新世代修复后747原值，748真实修改Close出现三选择、749取消保留草稿dirty；750放弃后753真实重开/754原名原详情，755保存并继续、756真关闭、757/758新名/OFF/3/0保持，759恢复原名实际保存。保存/放弃/取消原路径修复后通过；751过快重开未成功不作数据依据。当时F14-06含删除仍待；后续四本轮任务812已逐项GUI删除、1094重启只余两原内置ON，F14-06现修复后通过；三选择标签另属B27。 |
| B25 | 托管详情投影未跟随真实阶段及已生成规格/计划 | 738运行中实施日志却顶部规格/清单—、744无criterion原失败保留；776加载后779–781重开正确只证明requestDetail浏览，曾误泛化为动态回归已纠正。814加载后819同一详情RUNNING实施0/1，820未经关闭/重开真实动态100%/编排结束/清单1/1/Token46.2K，821验收场景1✓。运行中动态原路径修复后通过/F16-03修复后通过，F16-04四页浏览通过；总体UNVERIFIED是D08聚合缺口，不能因动态显示更新计整体已核验。 |
| B26 | 模型规划冻结的文件目标目录误写，真实正确文件写/read成功仍UNVERIFIED并重复写 | 原正确61004文件write/read成功但模型freeze错60104导致UNVERIFIED/重复write，740/742拒绝后743待人工；741modal挡Pause排除。已读官方[LangChain filesystem middleware](https://github.com/langchain-ai/langchain/blob/master/libs/partners/anthropic/langchain_anthropic/middleware/anthropic_tools.py)/[MIT](https://github.com/langchain-ai/langchain/blob/master/LICENSE)，采用可信workDir+确定性目标grounding、旧journal不改。776源码加载后首f4 badtarget被严格拒绝但旧15秒repair超时；814加载D07后实际8ce5cbf4/01d96bf8 LIGHT7.316秒错60104被UNGROUNDED_FILE_TARGET拒绝、唯一NORMAL repair15.981秒可靠RESOLVED/0reasons。冻结2criteria目标正确61004、subject33字符实际2换行，唯一write VERIFIED/read OBSERVED，22:33:48 child VERIFIED_COMPLETE/2satisfied/2refs/0unmet，坏目标→正确冻结→真实后置条件原路径修复后通过，不放宽guard或从旧journal改判。总体聚合仍D08/F16-05失败。 |
| B27 | 公共三选择dialog把选择说明误标为账号 | 748/750/755公共三选择dialog误标账号原失败保留，root仅FXML改选项；22:00:43仅compile后776 IDEA加载，788真未保存/非法间隔草稿dialog显示“选项：”，793/794选择仍正常，原标签路径修复后通过。B24三关闭行为独立保持，四本轮调度812已清理。 |
| B28 | 多行自动会话标题撑高header并挤压顶部操作按钮 | 850/853多行自动标题撑高header、挤压顶部操作原失败保留。两UI文件最小修复独立review后23:13:04仅compile成功/6.415秒/1484源码，866 IDEA源码加载；867原多行自动SDD标题显示单行且tooltip完整原文，868约790逻辑像素窄幅全部右侧按钮可读，869恢复原宽。原长标题失败路径修复后通过；短/空标题边界仍待，不泛化字体或全部窄布局。 947新本轮空会话短标题“新的对话”、0消息/57列表，顶部右按钮完整；948–952五模式切换短标题/0消息/同草稿保持，原先未测空/短标题相关边界已真实回归。仍不泛化所有字体或任意窄幅布局。 |
| B29 | 设置搜索英文model遗漏模型/分级模型导航，只剩MCP | 925原失败保留。root先读CherryStudio types.ts/searchEngine.ts/aggregate.ts及AGPL3，再在SettingsCategory三类最小aliases实现；00:30:58仅compile、984 IDEA源码加载后986 model恢复模型/分级模型/MCP三匹配且987 Enter路由模型，988 appearance仅界面风格且989 Enter路由风格。990中文模型三匹配及991 Clear恢复所有导航分组已由root实际审阅，搜索/Enter/清空原路径修复后通过；F17-01完整分类导航尚缺，仅这两类已实际路由，整项暂保留失败。 |
| B30 | 桌面snapshot内容条件不可产证据，真实snapshot空metadata不能满足DESKTOP_LINKED_FRAME | 994整轮未完成/1002取消原失败保留。TaskContractCompiler拒绝snapshot非空subject走既有bounded repair；DesktopSessionTools/DesktopToolPayloads仅在PNG成功捕获且owner/actual frame前后一致时生成captureUUID及typed identity/frame，HostEffectReceiptAdapter桥空subject OBSERVED元数据。四文件静态交叉review后01:03:30仅compile/1029 IDEA加载。1040新六步Calculator请求、1041实际只读预览数字294；本轮run0444fbf5-ba07-45ab-909f-bcc7fe57320b的真实snapshot receipts109/347已具PNG成功保存、独立captureUUID、同session/target/app/provider/process/窗口代次/修订/时间，observe实际数字与预览一致，snapshot空subject条件已匹配。review384为5/6满足，唯一未满足按apps/launch之后的targets；首次模型先targets/open/observe/snapshot，后修复apps/launch却未重新targets。禁止借用旧顺序收据或放宽Evaluator。1044整轮后续B34暂停失败，B30证据桥子路径有真实回归但整条F21-02仍失败，未宣称六步链完成。 |
| B31 | 原主窗解除最小化后仍未自动成为前景 | 1004–1008真实重复同Launcher，新进程SHOWN/exit0；root实际主console showing true/iconified true/focused false→true/false/false，只证明解除最小化，IDEA仍前景。JavaClawApp在已有AWT路径增加macOS且支持Desktop.Action.APP_REQUEST_FOREGROUND时的Desktop.requestForeground(false)，没有新增线程。root已查[JDK25 requestForeground](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/Desktop.html#requestForeground(boolean))及OpenJDK [CDesktopPeer](https://github.com/openjdk/jdk/blob/master/src/java.desktop/macosx/classes/sun/lwawt/macosx/CDesktopPeer.java)、[ApplicationDelegate](https://github.com/openjdk/jdk/blob/master/src/java.desktop/macosx/native/libawt_lwawt/awt/ApplicationDelegate.m)与GPL2+Classpath来源，未复制源代码。独立静态review/diff检查通过，01:03:30仅compile成功/6.819秒/1485源码、1029 IDEA源码加载后1030稳定原62会话/同3消息。1031真正黄钮最小化主窗后只activate IDEA并再次Run；1032未手动activate JavaClaw，稳定实际Launcher前景且完整主窗/原62会话/同3消息恢复，IDEA第二console01:05:19.709 SHOWN/exit0。自动置前原路径修复后通过，F01-06/F23-03修复后通过；没有观察到第二数据库初始化或第二独立应用窗，不扩大为任意数据库并发保证。 |
| B32 | 嵌入已关闭且原连接未配置时，普通保存仍强制API地址必填 | 1021原OFF/OpenAI/空API空key/text-embedding-3-small/1024/5/0.3保存被“嵌入API地址不能为空”拒绝、1025/1026丢弃草稿原失败保留。仅ModelSettingsUseCase.validateEmbedding(value, probing)最小修复，OFF普通Save跳过连接必填，ON/probe及Provider/数值仍严格；独立review后01:03:30仅compile成功、1029 IDEA源码加载。1035无dirty Save无反馈排除；1036 OFF真实probe仍空API失败，1037 ON Save同空API拒绝并dirty/未成功启用；1038真正切OFF并Save绿“已保存并生效，下一轮对话重建智能体服务”、无dirty，1039实际Close无未保存dialog回主窗，截图已只读视觉核实。原OFF未配置保存故障修复后通过；F17-06真实健康连接仍缺embedding地址/模型/密钥，整体改阻塞，不能以保存成功代替真实连接。 |
| B33 | 会话搜索只显示一项时，全选却包含全工作区隐藏会话 | 1047仅一个本轮结果、1048全选却选63，原失败保留；未删除，1049清选退出。SidebarSessionListController将全选限定filteredSessions、搜索变化清选、空结果禁用，并冻结确认与实际删除相同快照。1054 IDEA加载后1055单项1、1056空态禁用、1057换筛选清零、1058双项2实际回归；1059/1060正文高度和owner另记B35。B35加载后1109仅两精确本轮流式会话、两短ID可读，1111实际取消保持66及两项，1112/1113确认仅删除这两项，66→64且无匹配并退出管理。结合筛选及两级删除原路径，F03-06修复后通过；原7保护会话未触及，不使用全工作区或日期批删。 |
| B34 | 已成功harness decision恢复时重解析脱敏provider参数，冲突成错误工具暂停 | 1044实际黄卡原因persisted invalid harness decision step conflicts with provider call、诊断36a483bc-5685-3491-9399-c3c53a673649，整轮终态失败5m7s。root对仅本轮run0444fbf5的安全事件定位：最后provider当步提供framework_tool_catalog和harness_submit_decision，实际仅请求后者；375 control start、376 decision_submitted、377 COMPLETED/SUCCEEDED/accepted=true均同run/model/invocation。provider rawArguments已credentialRedacted且仅为敏感内容已隐藏占位，ModelStepJournal.recoverDecision重解析该占位，失败后试图把同一已成功control重记为harness.decision_invalid并冲突，UI误称未提供工具。补丁只允许从同作用域、唯一可信规范化decision_submitted及完成ack恢复，并核对脱敏后的control input；缺绑定/ack/内容仍fail closed。源码补丁待加载/GUI回归，不解密占位、不借其他run、不放宽工具白名单、冻结契约/证据顺序，也不改旧journal。F21-02新失败保留。  后续1054已IDEA源码加载，1067本轮completed CLAIM_DONE恢复成功，但该provider rawargs未脱敏，不冒充旧占位分支直接复现；1114新链仍运行中。源码加载不代替旧脱敏占位原路径通过，整条F21-02仍失败。 |
| B35 | 批量删除确认正文应用CSS后高度过矮，且未绑定主Stage而被主窗遮挡 | 1059约36px正文只露末尾短ID、1060主窗遮挡原框，1065 AXRaise恢复、1066取消未删，原失败保留。按应用CSS后实际正文高度重算、完整正文滚动及所属屏幕边界限制，批量删除框绑定主Stage。02:22:04仅compile成功，1107/1108 IDEA源码加载；1109确认5行正文全部可读且fa30ce80/6ebb5ff8两短ID完整，1110点击主背景框仍前台，1111真取消保留66，1112重开同两项后1113真确认仅删除两项至64。高度/owner及取消/确认原路径修复后通过，F03-06修复后通过。 |
| B36 | 桌面linked-frame投影选取旧首次链，误剔除按完整契约顺序修复后的合法新收据 | 1114/1124安全宿主事件显示apps40/launch50/targets155后已有open302/observe317/snapshot342且同owner/frame绑定，最终却三项DESKTOP_POST_ACTION_PROOF。定位V2投影先选旧60/75/100，V3按全序选新302/317/342，再用旧linkedEvidence membership剔除新链。TaskResultEvaluator最小修复把linked核验约束到V3当前criterion→evidenceRef候选链；完整desktopContract和全部真实events保留，严格cursor、目标/session/app/frame/subject和输入失效屏障不变。UNKNOWN click仍保留原强前置proof，末尾还对最终绑定链重走完整V2与强transition/selected invocation核验；公开V2/verifiedActionEvidence默认null绑定兼容，无新API/SPI。独立静态review及diff check通过，经两次仅compile后02:41:52 IDEA源码加载；1134–1141新run3050a82e实际43<53<217<241<256，新open/observe合法收据已被接受，旧70/85不再遮蔽，相关新链选择真实改善。修复后未生成新snapshot，最终321 PARTIAL只缺step6/MISSING_TRUSTED_RECEIPT，整六步仍未通过；未运行代码测试或改旧journal，不据源码将F21-02通过。 |
| B37 | MCP严格隔离拒绝后清理移除client，FAILED错误丢失为STOPPED，日志入口隐藏 | 1129真实Start仅泛“服务器启动失败”、STOPPED卡片且日志隐藏，原失败保留。McpClientManager移除重复提前拒绝分支，统一走McpClient.start既有严格TransportPolicy；策略仍在HTTP网络/stdio进程启动前执行，拒绝不得联网或起进程。McpClient.stop不覆盖FAILED，保留原错误供现有卡片/日志；显式Stop仍移除client，Retry创建新client。SecurityException仅warn bounded/redacted startupError摘要且不附throwable，避免URI/header凭据经cause输出，其余异常路径不变。两文件已独立静态审查/diff check；03:13:15仅compile成功及1173 IDEA源码加载最终日志补充后，1178真实Start显示需要处理1/已停止0/工具0及明确“严格项目隔离已拒绝本机或回环 MCP 端点”，日志入口可用；1179日志窗口同明确启动错误/stderr0，1180真正Retry重复同明确失败，1181取消Enable恢复已禁用/已停止1/需要处理0/工具0且错误卡消失。明确拒绝、错误/日志保留、Retry及停用恢复原路径修复后通过；本轮dummy1261/1262已精确删除至0，1265 IDEA源码重启后1266中心卡片/需要处理/运行/停止均0，无临时复活；F12-06包含复制、日志、取消删除、确认删除及重启的完整原路径修复后通过。证据1129-mcp-start-policy-feedback.png、1177–1181 PNG。 |
| B38 | 技术Completed忽略TaskResult.PARTIAL，右侧误显示本轮处理已完成；展开历史提示仍像正在推理 | 1141实际正文“六步尚未完成”，宿主321 PARTIAL只缺snapshot，但右侧本轮处理已完成；1142终态展开旧hint仍见模型正在推理。ChatTurnController透传已有宿主TaskResult到ThinkingPanelController，PARTIAL标未完成/待继续，BLOCKED/UNVERIFIED诚实显示，DELIVERED/null正常回复保持原完成。ThinkingContentRenderer终态header保持，展开内容前置运行期间历史说明；只有仍未终态的Agent/Stage停为待处理、Tool置UNKNOWN，已有真实settled成功/receipt不改。三UI文件独立静态审查与diff check通过，经03:13:15仅compile及1173 IDEA源码加载。1174新轮因MCP上下文刷新在1182被取消，没有PARTIAL终态结果，不能作B38部分完成原路径回归；该路径仍待，不把正常回复改FAILED、不据源码将功能通过。 1201真实普通Markdown请求，1202 24.7秒/2763输入865输出DELIVERED，主界面正常完成显示，B38正常纯文本分支已实机回归；真正PARTIAL投影及其历史说明原路径仍待，不能用正常回复代替。 1245新C/D原Run实际PARTIAL，界面诚实显示未完成/待继续，部分完成投影原路径修复后通过；1202普通文本DELIVERED及1258取消后普通中文6.3秒DELIVERED均正常完成，不误标失败。该UI投影通过不表示原四步自动任务完成；原任务缺读D为B40，仍按PARTIAL记录。证据1245-recovery-readback-result.png、1258-secure-normal-chat-recovered.png。 |
| B39 | 本地安全Header取消被普通ERROR/未适配MCP投影成UNKNOWN，自动再发后重复保护暂停 | 1183新安全输入会话68；1184仅本轮offline dummy/X-E2E-Probe获root批准，1185本地SecretDialog，1186虚构值全遮罩，1187真实拒绝后消失并继续模型，但1188最终UNKNOWN黄色暂停/右侧失败2m27且仅用户消息1条，原Bug保留。McpManageTools仅null/empty输入分支在copy/save/reconnect前发布7字段typed取消数据（schema/kind/name/header及saved/reconnected/retryAllowed=false），不含secret，处理取消返回SUCCEEDED。HostEffectReceiptAdapter仅exact宿主类、exact secure tool、SUCCESS、严格字段数量/类型及真实参数name/header绑定产FAILED/NOT_SENT/effectNONE；其它MCP成功/失败/未知仍UNKNOWN。SUCCEEDED仅表示取消请求已处理，effect receipt rank0不能满足WRITE；真实save异常不伪装取消，旧UNKNOWN/通用guard/one-shot审批不变。两文件已独立只读审查/diff check，无源码测试；1195正常退出/03:27:41 exit0后03:28:15仅compile成功6.616秒，1196 IDEA AX12实际加载B39/70会话。1197新secure71及1199重发分别1198 15.4秒/1200 17.3秒工具前unreliable，CAPABILITY_NOT_FOUND/MODEL_UNRELIABLE/EMPTY_CRITERIA/PLAN_REPAIR_EXHAUSTED，criteria[]/applicable=true/reliable=false，没有再次打开安全弹窗，不能计B39原取消回归通过。后续D15单独补取消交互cap，仍待加载/GUI，F07-05失败保持。证据1183–1188及1195–1200 PNG。 后续D15于03:51 IDEA自动Make实际加载；1251有效重试、1252审批、1253Allow出现本地输入、1254仅虚构值全遮罩、1255实际Reject、1256真实VERIFIED_COMPLETE且明确取消/saved=false/未重连/未重试，无UNKNOWN。1257/1258同会话普通对话正常，1260编辑器Headers0/OFF；root限定扫描16text日志/121jsonl rollouts的虚构值matches0。B39+D15取消与继续交互原路径修复后通过/F07-05修复后通过。1249/1250旧首审批TimeoutDenied、未输入，排除取消证据；取消成功只证明交互取消，不证明MCP Header已设置或任何WRITE。证据1251–1260 PNG。 |
| B40 | 合法CONTINUE缺剩余只读条件却直接completed，未触发既有宿主验收修复 | 1234新C/D恢复原Run288b2288-f357-4135-9a2c-79f567343df1的可信harness seq210为CONTINUE、缺c4_read_D，当步真实provider已有sys_file_read；Gateway原仅CLAIM_DONE进入review，CONTINUE直接fallthrough completed，1245实机原任务PARTIAL。1246/1248人工补读为新Run VERIFIED_COMPLETE，不作原自动闭环通过。最小仅SpringAiReasoningGateway：CONTINUE在终态output guard/GEPA前复用private reviewTaskCompletion(requireTerminalDecision=true)，严格冻结evaluateV3后套既有真实model decision gate；收据全齐仍需独立CLAIM_DONE。保留可靠契约、原进展规则、最多2repair、剩余工具/时间/输入预算、同Run journal及enterTaskRepair WRITE/UNKNOWN屏障；全部证据齐时只提示最终control决策、不再业务工具，无法继续明确PAUSED；无适用契约走已有单次protocol repair/pause，不无限继续。CLAIM_DONE默认分支、NEEDS_INPUT/BLOCKED及正常纯回复不改，无新API/SPI、不开放工具、不修改历史证据或预算。作者、独立及root静态审查/diff check通过；1264托盘正常退出04:08:50.171、IDEA exit0，04:09:37 compile-b40-0409.log BUILD_SUCCESS/6.880秒/1485源码，1265于04:10 IDEA源码启动已加载。1267–1273 F审批在退出前TimeoutDenied，1274–1277 G未实际获批且外部系统弹窗遮挡后TimeoutDenied，两轮排除有效恢复回归，不计Bug或失败，新真实四步闭环仍待。证据1245-recovery-readback-result.png、1248-recovery-remaining-read-latest.png、1264-b40-normal-exit.png、dual-file-recovery-final-1248-summary.json。 定位辅助引用target/e2e-20261004/secure-cancel-and-interrupted-recovery-1251-1277-summary.json，真实GUI截图仍为验收依据。 1280–1287 I/J因J在实际退出菜单前已超时，继续排除有效回归；1291–1303新独立K/L轮真正待L审批退出/重启/Continue原L/Allow后L5B已验证，当前读回处理中，最终尚未取得，B40不提前通过。1298未启动排除，实际源码启动证据为后续IDEA Run及1299。 1304新独立K/L同Run真实自动恢复闭环完成，F07-06修复后通过；但本轮CONTINUE decision0/core.task.repair_requested0，protocolrepair1=MODEL_DECISION_MISSING，B40具体CONTINUE分支未直接触发，原路径不记PASS。后续1306完整六步桌面请求仍活跃，分支覆盖结论待实际事件，不把普通协议修复冒充B40。引用target/e2e-20261004/dual-file-kl-independent-recovery-final-1291-1304-summary.json及1304-kl-final-status.png。 1310该六步桌面轮真实BLOCKED且CONTINUE0/taskrepair0，具体CONTINUE分支仍未直接回归，不能将D16缺schema归因B40。 最新1418–1419自然两轮只读scope3991e63e-a983-4790-ae33-0f5211b6bc96/Runada8c2d8-551e-4dd5-9090-221bde3d6cfa首次实际合法CONTINUE146→review148，B40分支已直接触发；早L94在K前，K42为UNKNOWN，125repair后可信K136 OBSERVED，但review148仍缺后续L，150 NO_PROGRESS/151 PAUSED为新B44。1419实机44.1秒/19863in1391out TASK_UNVERIFIED，整个任务不是PASS。B40分支进入严格复核与B44后续进展问题分别记录；引用two-round-read-1418-1419-continue-safe-diagnosis.json，1419真实截图已补为evidence/1419-two-read-current.png。 1443/1444同原Run ada8恢复已实机核验完成：resumed153后合法CONTINUE787→review789选ref2/0unmet但MODEL_COMPLETION_NOT_CLAIMED→repair2(790)→CLAIM_DONE815→823/825 VERIFIED_COMPLETE→completed826。B40继续进入验收及B44可信fileRead进展原Pause/Continue分支实际通过。K/L正文正确、4消息/绿色完成及同Run保持；有很多重复只读，不能声称仅两calls/每轮一次或未注册次数条件也已满足，保留性能和模型最终表述局限。文件恢复通过不升级F21桌面原六步。 已实际读取并引用[two-round-read-1437-1444-final-safe-diagnosis.json](../target/e2e-20261004/two-round-read-1437-1444-final-safe-diagnosis.json)：最终严格K136/L174、同Run恢复20次业务sys_file_read（K10/L10）、全Run22次（各11），22started均有精确同invocation receipt，无write。真实多次重复只读为性能/最终表述局限；provider元数据不证明完成后的重复由host read-pin强制，不能据旧repair上下文仍可见就另判算法bug。原恢复GUI通过依据仍为1437–1444真实操作，诊断仅补证。 |
| B41 | 上传图片及文件附件未纳入历史快照，重启后用户消息丢失附件 | 32/35/36/38原本轮图片添加、缩略图与viewer已实测；1364/1365重选session127a71b4-2239-4d74-878d-b62465c220a4的13:16会话，3消息稳定仅剩13:24附件请求文字，无附件卡或viewer，F23-01失败。addUserMessage保留File附件，但旧MessageSnapshot仅序列化imagePaths，恢复attachments为空；既有图片journal投影又被recoverTail已有文本匹配保留的空元数据遮蔽。最小五文件：MessageSnapshot新增独立attachmentPaths并保留旧7参数构造器，JdbcChatHistoryStore按13列/13参数读写独立JSON，JavaClawSchema幂等新增nullable列；Coordinator双向映射上传文件，恢复展示在任何stat/read前检查ProjectAccessPolicy，不可用仅文件名反馈且保留原元数据；ChatThreadTranscript只从同scope真实core.image/core.file文件URI投影，旧图片补齐须唯一非空USER文本、已有单调同内容匹配及含日期同一分钟，跨分钟/重复/空输入拒绝猜测，保留消息顺序/时间/adoption/usage/delivery并去重。图片与文档路径分开，不将文档伪作imagePaths，不新增公共端点或离线DB写；旧无可信文件引用不编造。作者、独立静态审查及diff check通过，尚待IDEA编译加载、原旧图及新图片/文件重启GUI回归。F05此前重启前验收保持，修复源码不作为功能通过。证据1364-image-owned-session-after-reboot.png、1365-image-persistence-no-preview.png。 1369正常IDEA exit0后compile-b41-d16-0737.log BUILD SUCCESS6.968秒，1370于07:40实际IDEA源码加载；1373原同session13:24图片恢复，同分钟旧journal补齐原路径生效。1372瞬时旧画面排除，1374点击内联图未弹viewer，不算viewer回归。1389新本轮图片/TXT两卡实际发送仍运行，新附件完整重启持久化仍待，F23-01保持失败；编译和部分恢复不代替全项通过。 1390新附件回复正确；1397正常退出/IDEA07:52:58.898 exit0，1398于07:54源码重启同新会话2消息图/文档标签保留，1400双击新图真正viewer。1401TXT仅标签无预览，不虚构docviewer；1402旧session原3消息第二次重启图保留、1403双击原viewer实际通过。原图补齐、新图片/文件持久化及图片查看闭环修复后通过；F23-01整项修复后通过，1399单击无viewer排除。 |
| B42 | 新建空会话标题及footer继承上一会话Token累计 | 1415实际New创建08:09新62/0消息，header ctx291.9k/200k、footer会话292K继承上轮，1416稳定empty welcome/right0仍相同，非延迟加载。只读定位ChatStatusController标题/摘要均读workspace TokenTracker.getSessionTokens；ChatSessionCoordinator.newSession仅换ChatSession并刷新标题；共享RunUsageObserver虽有scope但当前累计未区分聊天。最窄设计拟限包内UI状态/turn/session协调：当前会话既有TurnMetrics初始化显示，真实已隔离Usage回调按streamingSession归属；新空会话为0，切换即刷新，当前显示reset不改背景及今日/月汇总，workspace绑定清投影。不改Framework、历史元数据或真实费用账，不将未知旧指标伪作workspace累计。尚未授权源码实施/GUI回归，旧521手动reset证据继续限定原统计口径；本新缺陷单列待修，不虚构通过。证据1415-b40-two-round-read-session.png、1416-b40-empty-session-stable-context.png。 root随后授权实施三文件包内UI方案，作者、独立与root静态通过；历史TurnMetrics一次seed，捕获workspace+streamingSession归属真实Usage增量，generation原隔离保留，finish不二次累加token，reset仅当前显示投影，日/月真实账不变。bind/reload清缓存，删除forget，新建/切换标题和footer即时同步。失败无正文缺历史metrics时重启显示0，手动重置仅本次显示缓存；不借workspace累计或读取Framework journal/业务DB。源码就绪未编译加载/GUI回归，仍待原路径。 08:22:15.839正常退出/exit0，08:22:41仅compile成功6.567秒，1425 IDEA08:23源码加载；1426New63/0ctx0/footer0，1432当前9.5K/0:56，1433局部reset至0且正文2条保持，1434日2.5M/月4.9M全保持，1435另一owned旧scope21.3K/21K保留。1430虽名background，实际前run在19秒前已终态，不作后台隔离。1437原暂停继续后1438真globalstream活跃18.9秒、11298in197out时切已存在0消息空scope，header/footer0；须其终态后持续0才能完整通过。初步GUI paths通过但完整后台结束回归待，不按编译提升。 最终1439–1441后台用量持续上升、所选空scope0，1442真正全局处理完成6m13/123349in6950out/Send恢复且空scope0，1443切回原scope4消息ctx151.6K/footer152K=旧21.3K+新增约130.3K。1438至1443真实后台归属完整回归通过，结合新建/真实非0/局部reset日月不变/其它scope保持，F04-06从通过改修复后通过。 最终安全摘要two-round-read-1437-1444-final-safe-diagnosis.json已实际读取，补核后台上升/所选空0及切原scope归属；不改日/月真实账，原GUI证据仍为通过依据。 |
| B43 | 静态工具目录说明被凭据脱敏误识别，后续redacted prompt无法安全重放 | 1414同scope仅final继续45.4秒/20425输入2185输出暂停，限定诊断发现工具目录静态site_login_now说明含“密码：工具内部…”及site_fill_password含“密码由站点管理器内部读取”，触发现有LABELED_SECRET/COMPARATIVE_SECRET，使目录complete68标credentialRedacted、MODELstart78持久prompt整段隐藏，后续99replay门禁正确拒绝。最窄仅BrowserSiteTools两条静态description独立改写，移除标签/比较句式，不改真实参数、调用权限、redactor、replaygate或旧opaque prompt；作者/独立静态审查与diff check通过，未IDEA加载或GUI回归，不提升F21-02。诊断desktop-d16-activation-1412-1414-redacted-continuation-safe-diagnosis.json。 1425 IDEA08:23已源码加载，但1428–1431纯file-read轮没有目录/schema违规回归事件，不因该运行未触发原路径而记通过；旧opaque prompt仍不改。 1437–1444继续时新生成工具目录未再次误脱敏，部分新目录路径生效；原完整六步/目录恢复回归仍待，旧opaque prompt不重写。 |
| B44 | 有序验收修复忽略新的可信fileRead回执，提前判NO_PROGRESS | 1418–1419自然两轮K/L只读任务，早L94在K前、K42 code_read为UNKNOWN。repair125后真实宿主sys_file_read K136 OBSERVED/5字符SHA，合法CONTINUE146进入review148只满足K/仍缺后续L；TaskRepairProgress无条件排除fileRead新可信回执导致NO_PROGRESS150/PAUSED151，1419实机TASK_UNVERIFIED。root按现有严格契约/receipt最窄修已写，gepa最终独立静态审查通过、尚未IDEA加载或原路径回归；不接受UNKNOWN、放松顺序/审批、扩大预算或无限修复。B40实际CONTINUE分支已覆盖，但该整轮不通过。诊断two-round-read-1418-1419-continue-safe-diagnosis.json，1417/1418实际GUI发送与1419-two-read-current.png实机终态。root后续收口仅按当前严格选中的可信fileRead，与最新pre-repair同Run/framework.springai/schema3 review实际选中refs的同target/digest/字符数比较；无该review时保守按全部历史，保留2repair与合法CLAIM_DONE终态要求。二次独立静态审查通过，加载/GUI仍待，不提前通过。 1425已IDEA源码加载，1428新read轮CONTINUE/repair0没有直接触发该进展分支；1436/1437原1419暂停scope明确继续实际08:29:45，1438仍运行，原恢复终态等待，不计PASS。 1443/1444同原Run ada8恢复已实机核验完成：resumed153后合法CONTINUE787→review789选ref2/0unmet但MODEL_COMPLETION_NOT_CLAIMED→repair2(790)→CLAIM_DONE815→823/825 VERIFIED_COMPLETE→completed826。B40继续进入验收及B44可信fileRead进展原Pause/Continue分支实际通过。K/L正文正确、4消息/绿色完成及同Run保持；有很多重复只读，不能声称仅两calls/每轮一次或未注册次数条件也已满足，保留性能和模型最终表述局限。文件恢复通过不升级F21桌面原六步。 已实际读取并引用[two-round-read-1437-1444-final-safe-diagnosis.json](../target/e2e-20261004/two-round-read-1437-1444-final-safe-diagnosis.json)：最终严格K136/L174、同Run恢复20次业务sys_file_read（K10/L10）、全Run22次（各11），22started均有精确同invocation receipt，无write。真实多次重复只读为性能/最终表述局限；provider元数据不证明完成后的重复由host read-pin强制，不能据旧repair上下文仍可见就另判算法bug。原恢复GUI通过依据仍为1437–1444真实操作，诊断仅补证。 |
| B45 | GEPA独立请求截止时间被当作整轮模型失败，中断已严格完成的只读交付 | 1428新K/L只读宿主K59/L85各OBSERVED/真实5字符，合法CLAIM_DONE113、strict119 VERIFIED_COMPLETE；GEPA116于08:25:06.012开始、117于08:25:36.019被本地30.007秒deadline终结，run.failed12008:25:36.103。1431实际56.9秒/9533tok FAIL“inline model task timed out: gepa.evaluate”，严格任务证据完整不等于run通过。D10原仅处理ModelTaskOutputException，不处理本地timeout；不能宽泛catch TimeoutException，因为审计/provider/owner截止等也可能同型。root批准六文件最窄typed来源方案：仅core只读本地deadline marker，gateway在request自身截止短于真实owner期限且owner活跃、未取消/中断/混合suppressed、有效RUNNING/run deadline守卫时提升typed中性ModelTaskTimeoutException，policy仅该型V2 unavailable/no score；audit/provider普通timeout、owner截止及缺状态继续失败，原预算/取消/lease/晚到账不改。六文件作者/root/独立最终静态审查通过、diff check干净：CancellableTaskStages、SpringAiModelTaskGateway、AdaptiveGepaEvaluationPolicy、BuiltinExtensionCatalog及两必要SPI ReadOnlyTaskTimeoutException/ModelTaskTimeoutException。已保守限定ROOT owner：parentRunId非空保持旧失败，不猜祖先deadline或managed等待扩时；真实schema1/framework.core创建deadline还受冻结budget上限约束。仅typed own deadline令V2 unavailable/evaluation_timeout，无score/needsRevision，不改原model-task失败journal或严格终态。尚未编译IDEA加载或原GUI回归，不提升F21-02；参考[自身截止来源边界](../target/e2e-20261004/github-references/gepa-own-deadline-boundary-reference.md)。安全JSONtwo-round-read-1428-1431-final-safe-diagnosis.json与1431实机截图。 1445正常托盘退出后root IDEA AX08:38:17.251 resourceclosed/exit0，compile-b45-0838.log08:38:56 BUILD SUCCESS6.796秒/1487源码，仅skipTests compile。实际IDEA重启加载与GEPA own-timeout原GUI回归未取得，不将编译或前1444成功用于B45验收，1431原FAIL保持。 1446于08:40 IDEA实际源码加载；1448于08:41:23新评分任务，1449实机42.5秒/13402in1195out绿色完成。限定安全摘要核K47/L103各1read、CLAIM145→GEPA147–151正常2.318秒、schema1/model score0.85/needsRevision=false→153/155 VERIFIED_COMPLETE→completed156。此轮正常GEPA开启完成路径通过，未触发own-timeout/evaluation_timeout/V2 unavailable，因此B45超时原路径仍待，1431历史FAIL保留；refine_v2(62)JSON失败非GEPA，现有fallback未阻断完成。引用[two-round-gepa-1448-1449-final-safe-diagnosis.json](../target/e2e-20261004/two-round-gepa-1448-1449-final-safe-diagnosis.json)，不把辅助读取也计成零工具。 |
| B46 | 前台窗口选择与准备检查资格不一致，控制后续观察受阻 | 1452–1459真实Calculator控制轮最终数字仍294、目标579未完成，256.7秒/194313tokens/受阻待处理；1459实际Stop使预览消失。精确限定诊断确认真实provider87提供click、click94 STALE_FRAME/NOT_SENT，随后15次mandatory observe均因native-4拒绝准备前台。frontmostWindowIdFor只选>=24x24，targetWindowReady.firstApp旧逻辑只需>=1x1，实际target2098/frontApp2879/setMainAttempted1/raiseAttempted0；2879尺寸/类型未知，不声称装饰窗口。root授权sole native desktop_bridge_mac.mm最窄Bug修复，共享private尺寸资格helper仅用于前台候选与firstApp；pointer topAtPoint仍先扫描所有>=1px遮挡，保留同PID小覆盖层、精确ID/PID/bounds/AXfocused、TTL/帧/未知副作用屏障和进程守卫。作者、root及独立静态审查通过/diff check干净，未构建加载或原输入GUI回归，F21-05保持失败。GEPA正常2.277秒非B45超时；无D16缺input证据，不采信模型“从未提供click”。来源[限定安全摘要](../target/e2e-20261004/desktop-control-1452-1459-final-safe-diagnosis.json)、[1458输入未完成](../target/e2e-20261004/evidence/1458-desktop-control-observation-failure.png)、[1459实际停止](../target/e2e-20261004/evidence/1459-desktop-control-stop-failed-result.png)。 1472正常退出、IDEA08:59:04.810资源关闭/exit0；08:59:39 Maven SUCCESS0.611秒/NothingToCompile不等于native构建，但另root实际xcrun双架构/Werror成功，native-build-b46-0859.log输出target/native/macos/libjavaclaw_desktop.dylib。1473 IDEA09:00新Java源码启动已使用target/native加载；1474/1475原control fixture约09:01:28实际发送仍运行，原GUI回归未取得终态，F21-05失败保持。构建与启动仅证明修复加载，不计功能通过。 1482真GUI8m32秒/201136输入9011输出/累计254742250000预算失败，safe JSON核7次fresh观察已成功、原first-window nativeprepare旧故障未再出现，B46原分支可记修复后通过；整控制链仍4次foreground pointer被未知layer20挡住NOT_SENT、type/key0/目标579未完成，F21-05失败保持。1481 Codex前景不作终态，CUA getApp约7336秒阻塞后11:14恢复属外部工具；1484 root人工将0恢复294只记环境恢复。源[本轮最终安全摘要](../target/e2e-20261004/desktop-control-b46-1475-1480-final-safe-diagnosis.json)、[1482真实失败](../target/e2e-20261004/evidence/1482-desktop-control-b46-stopped-result.png)。 |
| B47 | Pointer阻挡诊断缺少安全身份信息，未知layer20不能被认作透明窗口 | B46加载后成功foreground观察136/176/216/256/296/387等，旧first-window nativeprepare错误不再；4次前台click201/241/281/321均Another window in front/NOT_SENT、阻挡layer20且非Calculator/JavaClaw PID，现有journal无bundle/rect，不能声称ScreenCaptureKit边框或忽略覆盖窗。实施前实际读官方[Hammerspoon系统级命中源码](https://github.com/Hammerspoon/hammerspoon/blob/master/extensions/axuielement/libaxuielement.m)、[窗口句柄比较](https://github.com/Hammerspoon/hammerspoon/blob/master/extensions/window/libwindow.m)及[MIT](https://github.com/Hammerspoon/hammerspoon/blob/master/LICENSE)，记录hammerspoon-systemwide-hit-test-reference.md；systemwide timeout会改默认且此时未证明安全有界，root授权metadata-only。sole native仅同topAtPoint entry失败detail增加96ASCII公共bundle、有限%.9g rect、sharingState0..2或-1、真实空/缺title布尔、systemAxHitCollected=0；不输出标题/路径，不新AX/globaltimeout/线程/C ABI，所有准入过滤/false/dispatch原样。未知title type保守false，独立最终静态通过、未构建加载/GUI，不记输入修复或降低guard。 1497/1498 IDEA11:28实际源码启动已加载B47，1500新第68会话于11:29:49.832发送单次AC前台请求，1502实际前台接管。1504/1505实机终态受阻2m59秒/58688输入3225输出，实际AC click105/145两次均FAILED/NOT_SENT/dispatchAttempted=false，Calculator保持294；1505 Stop预览消失。诊断实际blockerBundle=com.apple.dock、layer20、rect0,0,1512,982、sharingState1、titleEmpty0、systemAxHitCollected0，不证明透明/真实接收者、不作为放行条件。诊断功能原路径已生效，输入修复尚未实施、整F21-05仍失败；[安全诊断](../target/e2e-20261004/desktop-pointer-b47-1500-safe-diagnosis.json)已补final178completed，与root实际受阻/Stop截图一致；模型实际两次尝试，不冒认仅一次或no-retry通过。 |
| B48 | CG阻挡诊断缺少实际鼠标接收窗口，不能仅凭Dock签名放行 | 在实际读取Apple NSWindow物理mouse-down规则、GLFW官方exact windowNumber命中实现及完整zlib许可后，root只授权native诊断。单文件/tmp新基线增量已独立审：非主线程向现有main queue异步查询below=0，shared状态不持session/栈引用、等待300ms及前后expired/deadline守卫，主线程/无效坐标/异常/超时保守未采集；迟到结果不发布，主队列API自身不能中断的限制保留。仅原pointer失败reason加receiverWindowId/receiverCollected，所有原准入/false/dispatch不改，无缓存/新权限/ABI。1521 IDEA11:46实际加载后，1523新owned Run c4211cd3…；背景AC91 SENT使GUI0，1525实际前台接管，前台347 FAILED/NOT_SENT/no dispatch却采得receiverCollected1/receiver2098==target2098，同公共Dock/window11/layer20/full1512x982/share1/titleEmpty0。1528真实预算255960/250000停止、Send/2消息/Preview0，诊断生效但整F21-05失败，不能认透明或把诊断单独作准入。[Apple/GLFW来源及边界](../target/e2e-20261004/github-references/apple-pointer-receiver-design.md)、[最终安全摘要](../target/e2e-20261004/desktop-pointer-b48-1523-safe-diagnosis.json)。B49精准签名加fresh unrestricted exact receiver的最小方案已root授权，尚待源码独立审与实机回归。 |
| B49 | 已证实际mouse receiver为目标但精确Dock CG覆盖仍误拒前台点击 | root授权唯一native增量并独立对/tmp新基线审查：同次fresh unrestricted below0接收结果附真实primaryBounds，只有found/current exact bounds、firstSameApp目标、typed layer20/share1/真实非空NSString title、公共bundle exact com.apple.dock、阻挡rect完全等同该query primary、point在primary内、collected非0 receiver==目标及当前foreground PID匹配全成立才准入。未知/异常/timeout/主线程省略/不同target保持原NOT_SENT；reason为空也重新query，无cache/skipWindow/新权限/ABI，原initial/stillReady各次fresh、TTL/像素/process/TYPE焦点/dispatch与已送UNKNOWN保持。源独立静态通过后root真实构建加载1533/1534；1540 Calculator294→0/root没有人工点，真实106/107 FOREGROUND_SYNTHETIC SENT/ACCEPTED/SUCCEEDED/dispatchtrue、effect仍UNKNOWN，新121/122同session/target/gen1观察数字0(.92)。原清除误拒子路径修复后通过；1544完整579流程已预算暂停249749/250000；实际六次FG SENT为clear×5/数字1×1、type/key0，strict仅targets满足，F21-05整项失败保持，query后瞬时变化race及必须新观察核效限制诚实保留。[来源与边界](../target/e2e-20261004/github-references/apple-pointer-receiver-design.md)、[安全摘要](../target/e2e-20261004/desktop-control-b49-1536-safe-diagnosis.json)。 |
| B50 | 声明动作前的可信历史view被后续同窗动作当成终态陈旧view否决 | canonical原before77真实294、同窗clear104已SENT且引用该帧；旧laterDesktopAction对所有view一律否决前置view，是独立源码原因，实际249/254另因targets晚于open只选apps+targets，不把它写成单一历史拒绝。最小TaskResultEvaluator私有历史IDs只用于可靠受支持V3中同声明targetType/target的view→action→最终view链；只历史view跳过后动作失效，完整events/全序/绑定/帧/subject/此前freshness不变。最终view仍拒未来真实动作，publicV2及UNKNOWN/verifiedActionEvidence默认空历史资格。作者、独立及root静态通过并经IDEA加载；1606–1609原before61在真实action86之后仍由严格review174选中，历史view豁免原分支修复后通过。该轮WAITING_INPUT仍缺独立c5 subject，整体F21-05不通过。详见[1605安全摘要](../target/e2e-20261004/desktop-canonical-d22-1605-safe-diagnosis.json)。 |
| B51 | 普通只读应用目录查询被误投影为必须启动应用的恢复阶段 | 1575原请求没有launch，实际apps42→launch→open62→before77→clear104→晚targets146，顺序受扰。OnDemandApplicationRecovery对所有settled目录设置needsIdentity=catalogEligible；root授权仅applications分支加failure!=null，failure仍仅同Run真实typed FAILED/NOT_SENT且identity reason匹配的launch生成。普通查询不隐含launch，失败launch原分页/查询/目录失败回原launch及core真实aliases保留；显式launch原策略/选择仍保持，未增加身份preflight、改冻结target或重放输入。单行增量及独立静态通过并经IDEA加载；1587后普通apps30进入正常optional planner/catalog，provider52无旧SELECT_APPLICATION，launch56为模型自选而非host隐式恢复，原pure-query子路径修复后通过。1605 apps→targets且无launch补核，整体F21-05仍未闭环。[已读来源与边界](../target/e2e-20261004/github-references/hammerspoon-application-identity-preflight-design.md)。 |
| B52 | 已完成控制ACK恢复后仍把opaque历史harness参数提交provider | 1579实机4m13秒/105935输入3420输出失败；252历史harness_submit_decision参数为9字符redactor占位、非JSON，241为可信CLAIM_DONE，255对应provider400 function.arguments格式拒绝。不是provider新生成错误或外部故障推断。root授权优先ModelStepJournal最小恢复：仅COMPLETED控制且原recoverCompletedDecision全部绑定验证通过，非法JSON参数从已脱敏typed决策重建四字段，保持callId/type/name；合法JSON、业务/待执行/不完整或歧义记录不改，不恢复raw秘密、不重执行/自动CLAIM。单ModelStepJournal源修及独立静态审通过：仅fresh非冻结replay消息中非法JSON exact harness，唯一原同Run完成MODEL单独精确调用/时序/原visibility，再完成控制与原全部事件/ACK/typed绑定验证；四字段只取leaf-redacted input，保留消息text/media/metadata。冻结replay、redacted-input gate、合法JSON与其它调用保持；缺完整绑定failclosed。1584批编译及后续IDEA源码加载已完成；1594/1609 fresh历史控制参数均合法且原参数本已合法，仅覆盖正常路径，opaque重建原分支仍未直接回归。[最终诊断](../target/e2e-20261004/desktop-canonical-history-1575-safe-diagnosis.json)。 |
| B53 | 规划要求非空桌面input主体，但真实click/type/key/scroll receipt固定不提供主体 | 1609真实AC派发、前后观察已选，旧c5 requiredSubject=Clear All button不能由空subject满足。仅TrustedCapabilityRegistry四input能力说明主体必须为空、细节留description并独立observe；TaskContractCompiler对非空主体记录UNSUPPORTED_RECEIPT_SUBJECT、保留原值，走既有有界规划修复。旧合同、adapter、Evaluator、权限/预算不变，不伪造或清空旧c5。作者/独立/root静态通过，13:55:34编译、13:56 IDEA源码加载；1629原type/key新可靠合同click/type/key主体均空；1634实际strict选中159空主体click，正常有效规划/点击验收子路径已回归。非空主体拒绝/修复分支未触发，整轮BLOCKED仍缺c5–c9，不提升F21-05。详见[1629安全摘要](../target/e2e-20261004/desktop-type-key-d21-b53-1629-safe-diagnosis.json)。 |
| B54 | 可选视觉候选Schema允许缺内层confidence，严格消费者拒绝后缺条件proof | 新81第二轮剩14.68/18.05秒超时，首OCR安全回退已测。D26缩小第二任务后，新82实际153→154成功6.210秒、159真实候选0；289→290成功3.793秒、295真实候选1，双层数字置信度/原首OCR/六帧绑定均保留，B54/D26成功修正子路径通过。整链因原完整输入未执行及后续条件缺失仍PARTIAL6/9/NO_PROGRESS，不把此子路径升级whole。见[原来源](../target/e2e-20261004/github-references/pydantic-vision-condition-repair-reference.md)、[新82最终安全摘要](../target/e2e-20261004/desktop-type-key-d26-1725-safe-diagnosis.json)。 |
| B55 | INPUT前置参数验证拒绝无派发，却被Cursor当UNKNOWN pending | 新79 type121多targetId前置reject，无派发/receipt却入pending。仅Cursor56行可信sameRun/唯一step/invocation/tool/input/output及StepId.tool绑定排除该未派发拒绝，不按flag清真实UNKNOWN，D24/evaluator/adapter保持。root/peer静态通过并源码加载；新81参数拒绝0、type/key0，因B54缺c5证明最终1715 NO_PROGRESS/PARTIAL4/9，具体前置拒绝排除分支未覆盖，whole仍失败。 |
| B56 | AX动作能力不匹配被误归为画面过期 | 新82三次原完整type选择AXScrollArea actions0，ManagedSession请求WRITE位未具备，在platform.perform前拒绝，却返回STALE_FRAME/STALE_OBSERVATION泛报过期，真实NOT_SENT非TTL。root已授权gepa仅ManagedSession/DesktopSessionTools按现FAILED/INVALID_TARGET/ModeNONE/dispatchfalse/NextOBSERVE准确分类并保留reobserve反馈；其它FAILED/DENIED/真正stale不变，不加publicAPI/native/权限或proof。最终两/tmp增量及原receipt/Prerequisite/Cursor策略已root/独立静态审通过冻结，16:38编译成功、1737 IDEA源码实际加载；1747新83完整GUI成功且220/222 strict9/9核验，F21-05修复后通过；实际能力拒绝本轮0，B56异常分类分支仍未覆盖。见[D27实际来源](../target/e2e-20261004/github-references/d27-desktop-input-target-and-payload-reference.md)、[新82安全摘要](../target/e2e-20261004/desktop-type-key-d26-1725-safe-diagnosis.json)。 |
| D01 | 删除入口绑定当前工作区，保护要求先切换，但切换后无法选择非当前临时区删除 | 113/116 原失败保留；已查阅 GitHub [VS Code](https://github.com/microsoft/vscode) 工作区目标选择及 MIT 许可，以 SidebarController + FXML 增加独立非当前区选择。编译并经 IDEA 133 加载；140/141 两级取消、142 数据保持、143/144 删除仅临时区、145 默认区保留、146 最后区保护均实际回归，修复后通过 |
| D02 | 工作流定义列表没有重命名或删除入口，新建只能用自动名称，临时定义不能从 GUI 清理 | 189 原缺口保留；参考 [Node-RED](https://github.com/node-red/node-red) 后经 IDEA 229 加载。230 系统按钮禁用、232 空名拒绝、233 正常改名、235 取消删除、239 未终结删除保护实际回归；IDEA 308 重启后 310 名称/发布/副本图及待输入 ID 保持。311 因 B10 进入 FAILED 后 312 确认、313 删除、314 重开无副本，原发布项/内置项保留；F15-09 核心路径修复后通过。420取消保留后，恢复SAFE应用并拖动开始节点立即删除，423确认、424真删B10终结草稿、425重开无复活，本次迟到自动保存删除路径回归通过 |
| D03 | 托管阶段缺少已准备输出/已审批状态的恢复检查点，继续时重复已完成提案及审批 | 503已批准提案✓/SPEC暂停后505重现提案Gate，原失败保留；既有SpecStore preparation.json/hash审批checkpoint修复，参考GitHub LangGraph的[中断与执行循环机制](https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/pregel/_loop.py)。新719源码加载后722/723真实提案评审仅批准一次，724 Pause为SPEC/Proposal✓；727预算耗尽、728恢复120000续跑再Pause不重审批；729正常退出/root IDEA exit0并IDEA源码重启后731同7fe检查点/正文/9894/120000持久，732Continue为RUNNING SPEC/Proposal✓，没有再次提案Gate。已批准后暂停/重启恢复至规格的原路径修复后通过；计划Gate、实现及完整终止仍待，不能将F16-05或SDD闭环判通过。旧1514仍待人工，540未取消；730只首点击激活不作为已选任务依据。 733恢复后进一步出现规格+计划Gate，734仅物理同意一次、736/737受限工具写入批准，仍无重复提案Gate；最终实现/验收待，不提升整条托管闭环。 |
| D04 | 主对话/研讨/循环原缺正文delta；首修后SDK异常、又仅交付摘要 | 原23/529/530无运行中正文、531终态40段；585 No value present正文清除、611仅95字摘要保留。参考GitHub [Cline](https://github.com/cline/cline)、[LibreChat](https://github.com/danny-avila/LibreChat)与SpringAI主源实现OpenAI兼容rawdelta，只提取可信harness.userMessage、guard/脱敏及终态替换/取消保护；SDK工具碎片适配19:45:16compile/608加载，新协议提示19:58:09compile/624加载。630真实运行中27–35段、631完整60及✓，632–636引号/🌊/中文/1–60保持；641另一80段增量、642自然完成不算停止。649新200段真取消/发送恢复、651随后短答完成7消息、653手动重生成追加合法新轮9消息，旧60/80保持、取消200无迟到回填。核心原路径修复后通过、F04-04修复后通过。Stop发生在该轮尚无可见body阶段，正文已显后的停止/循环多段流式/SDK新业务工具尚未覆盖；未知provider/guard/customAdvisor整文fallback、SDD排除边界保持 |
| D05 | 托管阶段JSON格式失败缺少有界重试修复 | 687合法外层harness、内层proposal JSON1054字符缺末}，canonical无污染且严格parser正确拒绝，非parser Bug/B22。GitHub主源研究后五source一次严格repair、持久marker、同RunId deadline/Token去重双review完成；第二次失败仍严格终止，不补字符串/审批/放宽guard。21:15:34仅compile后719 IDEA源码加载，722/723真实合法提案并推进SPEC；是否实际触发bounded repair待arch实际trace确认，合法结果本身不能推断修复原路径通过，完整阶段亦未完成。 root本轮实际trace已确认proposal/spec/plan均首次合法、repair=false，未触发bounded repair；正常合法阶段成功仅覆盖常规路径，格式故障原路径GUI仍待。 |
| D06 | 诊断查询/导出事件与真实Framework运行记录断连 | 701/703旧源查询0、717旧包4项无trace原失败保留；现ArchivePort接scoped canonical RunStore投影，保留过滤/2000行/安全摘要，无Framework API/SQL或UI JDBC改变，参考官方LangGraph/Cline来源与许可。719源码加载后762稳定2000条、764组合12、766/767无匹配0、769时间8/770 model125/771 error29，F22-02原路径修复后通过。774真实导出12369KB/5项含trace；只读JSON核6876事件/138runs/JSON错误0、2payload隐藏、已识别credential模式0、配置53项/4masked含2真实API字段+2预算保守误匹配；后续原ZIP日志9385/104行内存扫描10类模式全0/未分类候选0，仅计数无敏感原文输出。当前样本未观察具体泄露，F22-03修复后通过；无候选需真实值比对，未访问活配置/解密，不虚称literal comparison执行或任意日志永远无秘密。scope仅由既有rollout join138/0mismatch解释来源，47非维护+30维护纠正旧43/34，不作附加失败。 |
| D07 | 契约初次编译和修复共用15秒限制，坏文件目标被正确拒绝后修复超时 | 首f4 LIGHT错误目标被B26严格拒绝，无toolstarted，但NORMAL15秒repair超时/785待人工。已读LangGraph [runner](https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/pregel/_runner.py)、[runtime](https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/pregel/main.py)、[MIT](https://github.com/langchain-ai/langgraph/blob/main/LICENSE)采用有界attempt/owner剩余预算；first15/repair60/shared75与owner.remaining取更小值，不放宽Token/取消/Gate/冻结校验。22:27:02仅compile、814 IDEA加载，817恢复Gate/818写入请求/819批准。实际8ce5cbf4 LIGHT7.316秒被拒，唯一NORMAL修复15.981秒（旧15秒会超时、新60秒成功）可靠RESOLVED/0reasons，正确criteria→write/read→22:33:48 VERIFIED_COMPLETE，原超时修复分支修复后通过；总体D08缺桥未验证，不能泛化整条SDD闭环。 |
| D08 | 已核验实现child收据没有聚合到总体SDD结论，编排完成仍固定UNVERIFIED | 原820/821可信child但overall固定UNVERIFIED缺关联桥、885新任务工具前ArrayNode转换失败均保留。八文件桥及显式JsonNode/toString修复已编译/899 IDEA加载后，914原D08待人工、915Continue恢复实现不重提案/计划审批，918正确本轮PROOF.md写Gate/919实际批准；921同窗动态100%/1/1/47.9K，922任务结果已核验完成、924criterion更新。root实际child29580f1d/parentae8aa5a1 Core VERIFIED_COMPLETE/2refs/0unmet，00:02:12 H2绑定/item DONE/overall COMPLETED，child到overall关联原缺口修复后通过/F16-05控制回归通过。实际write1/read2，需求严格只read一次未满足，不能宣称全部次数约束通过；关联/完成结论重启耐久未测，旧未核验journal不私改。 1137–1139 root实际在02:41:52 IDEA源码重启后重开同D08：100%/1/1/47.9K及总体“已核验完成”、实现项✓保持，关联终态GUI重启耐久已覆盖；任务仍保留未清理，严格read一次的既有限制不变。 |
| D09 | 原受限桌面发现请求失败；窗口观察链缺可信snapshot关联及后续恢复失败 | 原959/960失败保留。先实际阅读GitHub官方LangChain tool_selection.py及mcp-adapters tools.py/MIT，再六文件修复并独立静态review，native discovery三类cap严格宿主证明；00:30:58仅compile/984 IDEA加载后985原受限请求、992唯一Calculator/count1/total1/完整页、993两criteria已核验，安全host run564600de-24d3-4b7d-8849-35dea416d2d0 VERIFIED_COMPLETE/2refs/0unmet，probe/applications各仅1次SUCCEEDED+OBSERVED，发现原子路径修复后通过。994 launch/control=false/observe/snapshot有996真实预览，但模型加入未经请求probe/省略targets，snapshot非空内容条件及空metadata属B30，整轮未完成，1002取消/1003停修复新预览。B30四文件修复后1040新六步契约准确、1041真实数字294与observe一致，snapshot真实PNG及同owner/frame typed receipt已匹配；review384为5/6、仅按apps/launch之后的targets缺失，不借旧顺序收据。1044后续B34 harness decision脱敏重解析冲突终态失败，1045实际停止本轮空闲预览。发现及snapshot子路径通过不代替完整链，F21-02现失败，F21-03窗口控件通过，F21-04/-05未做。宿主证据、权限和收据顺序条件未放宽。 |
| D10 | 辅助GEPA结构化评估失败中断主任务，缺独立降级反馈 | 原1067/1068第二次summary678→591仍超500、一次修复后异常中断主修复；第一轮558→430成功，两者均非D05内层JSON格式故障分支。先读GitHub [Pydantic Evals执行异常处理](https://github.com/pydantic/pydantic-ai/blob/main/pydantic_evals/pydantic_evals/evaluators/_run_evaluator.py)及[独立失败汇总](https://github.com/pydantic/pydantic-ai/blob/main/pydantic_evals/pydantic_evals/dataset.py)/MIT，独立实现provider-neutral ModelTaskOutputException和GEPA窄捕获、mode=unavailable/reason=structured_output_invalid；不制造score/pass，500字符与一次修复不放宽，审计异常/中断继续传播。02:22:04仅compile成功、1107/1108 IDEA源码加载；1114新run5e7f07b2实际274 gepa.evaluate summary>500重试仍非法，275 assessment mode=unavailable/reason=structured_output_invalid，随后276主任务review、277 repair和299–342真实后续只读工具继续；D10辅助失败不再中断主任务的原分支真实回归。最终预算暂停及B36属于整链其它问题，F21-02仍失败，不能用辅助降级成功判整体完成。安全证据desktop-full-chain-receipts-1124.json。 |
| D11 | 有序任务前置步骤迟补后，修复提示只列当前缺项，未指导重获后续只读收据 | 1067/1068真实apps40→launch50→open60→observe75→snapshot142→targets214，迟补targets后没有重获后续只读证据，338 PARTIAL/339 FAILED且3项未满足。严格TaskResultEvaluator顺序校验正确，保留不变。先实际阅读官方GitHub [LangGraph _algo.py](https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/pregel/_algo.py)的apply_writes/prepare_next_tasks/依赖版本触发片段及[MIT许可证](https://github.com/langchain-ai/langgraph/blob/main/LICENSE)，采用依赖状态变更后重新调度受影响节点的原则，独立实现、不复制代码。SpringAiReasoningGateway反馈携完整冻结criteria JSON、编号能力链，要求迟补前置后按冻结顺序重获已授权只读发现/观察/截图或明确无控制会话收据，使用真实目标与会话标识；禁止重放写入/发送/删除/输入等副作用，无法满足则报告缺口请求人工处理。TaskRepairContext仅更新尾截断注释，逻辑未变；无新公共API/SPI，不改权限/证据/旧journal。静态复核及两文件diff check通过；02:22:04仅compile、1107/1108 IDEA源码加载，1114新run5e7f07b2的277 repair实际含完整冻结JSON、有序能力链和禁止重放副作用规则；迟补targets155后确实重新open302→observe317→snapshot342，组成40<50<155<302<317<342有序六收据链，指导后续只读重查的原路径实际生效。最终B36旧linkedEvidence遮蔽新链且266535/250000输入预算停止，整项F21-02仍失败，不放松严格验收。安全证据desktop-full-chain-receipts-1124.json。 |
| D12 | 删除来源会话时，未提升习惯观察及待处理证据键缺少对应清理 | 先实际阅读官方[Mem0 scoped delete_all](https://github.com/mem0ai/mem0/blob/main/mem0/memory/main.py#L1730-L1776)、[派生引用删除](https://github.com/mem0ai/mem0/blob/main/mem0/memory/main.py#L1921-L1946)及[Apache 2.0](https://github.com/mem0ai/mem0/blob/main/LICENSE)，独立采用明确身份范围删除和派生引用清理原则。ThreadMemoryCleanup仅现有当前用户/工作区/精确正在删除thread的两组件来源键，空thread及多colon歧义fail closed；copy/prune pendingHabitObservations/pendingHabitEvidenceKeys并持久化changed及revision递增，拒绝在途旧review覆盖。保留手工/其它会话事实、已提升事实的原evidenceDeleted语义，不新增历史扫描或修改墓碑/lease/cursor。源码及来源已静态复核，经03:13:15仅compile及1173 IDEA源码加载，尚待本轮精确来源会话删除的真实GUI回归；旧已删会话是否确有raw遗留尚未证实，不提前判阻塞，也不把加载当清理通过。参考记录target/e2e-20261004/github-references/mem0-memory-deletion-reference.md。 |
| D13 | completion repair所需只读工具被辅助上下文规划失败后的READY投影移除，缺恢复入口 | 1134新六步run3050a82e修复后targets217/open241/observe256合法，snapshot132早于targets无顺序证明；274曾提供snapshot但未调用，291 repair_select_sources_v2根类型array/object schema mismatch，295 fallback仅observe/open且CatalogMode.NONE，1141真实正文无所需工具/目录，最终PARTIAL。先实际阅读官方[LangGraph动态工具绑定与缺项校验](https://github.com/langchain-ai/langgraph/blob/main/libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py)及[MIT](https://github.com/langchain-ai/langgraph/blob/main/LICENSE)，独立采用每次调用所需接口完整、绑定属于授权子集原则。四Framework文件以本Run可信持久completion repair与incoming精确对应、实际registry/完整严格TaskResult/冻结唯一criterion确定必要接口；真实HOST且已授权只读工具恰好一个时持续直供，描述或接口歧义仅恢复原受控目录。replay/零预算/mandatory恢复观察和输入栅栏优先，保留activation承诺、风险、审批和schema/count预算，无法满足则暂停，不开放全工具、不增公共API/SPI。独立静态审查通过，经03:13:15仅compile及1173 IDEA源码加载。1174新桌面轮1175/1176已真实运行/control=false预览294，但运行时MCP启停造成上下文刷新，1182取消1m55/只有用户消息/无完成结果，不能计D13恢复或完整六步链通过，需独立重跑。来源记录langgraph-task-repair-tools-reference.md，诊断desktop-full-chain-receipts-1141.json。 1310合法BLOCKED轮没有可信task-repair事件，因此该已有门禁没有触发；普通可选规划丢snapshot的剩余范围单列D16，不放松D13可信repair来源。 |
| D14 | 原聊天待审批运行缺少显式恢复入口，正常关闭适配器又先取消原Run | 先实际读取官方Cline [持久任务恢复协调器](https://github.com/cline/cline/blob/main/apps/vscode/src/sdk/sdk-task-control-coordinator.ts)、[绑定toolCallId的审批交互](https://github.com/cline/cline/blob/main/apps/vscode/src/sdk/sdk-interaction-coordinator.ts)及[Apache2.0](https://github.com/cline/cline/blob/main/LICENSE)，独立采用同任务持久状态恢复、“继续”与“批准”分离。AgentConversationRunner仅交互显式CONTINUE经现有内部ResumeCommand(input.continue)，Engine真实pendingApproval且PAUSED/WAITING_APPROVAL才持久重发同challenge并返回原Run，不生成grant/launch；无pending归一旧input，deadline/作用域/fingerprint保持。应用shutdown仅释放交互WAITING_APPROVAL观察器交给kernel原checkpoint，精确Active ownership/closed谓词避免退出UI被当Deny；正常用户Stop/Deny及COMPLETED journal回放仍保留。三文件独立静态审查/diff check通过，尚待编译加载及同Run真实审批恢复GUI，不按源码将F07-06通过。引用target/e2e-20261004/github-references/cline-approval-recovery-reference.md。 03:51 IDEA Run自动Make已首次实际加载D14/D15；03:55:54仅Nothing to compile/0.680秒，不是首次手动加载。1224旧Run852a3256在03:50:53.063旧shutdown已CANCELLED，1231/1232新Run重新审批A、1233rootReject均不作修复恢复证据。新1234–1243 C/D任务在同Run288b2288-f357-4135-9a2c-79f567343df1于03:56重启后，明确Continue仅0.7秒重显原D同challenge/fingerprint，批准后C/D各写1，同Run checkpoint与不重复已完成WRITE原操作链实际通过。1245原四步却因CONTINUE未repair缺读D而PARTIAL/B40；1246/1248手动新Runc71a82a5-8dc3-46f5-b311-088e90dda15f补只读为VERIFIED_COMPLETE/CLAIM_DONE且无新write，不能充当原自动四步通过。F07-06仍待B40新闭环。安全摘要dual-file-recovery-original-1224-1231-summary.json、dual-file-recovery-final-1248-summary.json及1224–1248 PNG。 1291–1304新独立K/L实际恢复自动闭环已完成：同原Run待L审批正常退出为KERNEL_SHUTDOWN暂停，重启/Continue只重发原L同fingerprint/argsSHA，经human approve后才写L；K/L各write1/read1，1304实际85.7秒/完成/3消息及4criteria同Run全部VERIFIED_COMPLETE。F07-06现修复后通过，不再以B40待直接分支覆盖阻止该恢复验收；target/e2e-20261004/dual-file-kl-independent-recovery-final-1291-1304-summary.json仅定位辅助、1291–1304截图为主证据。 |
| D15 | 只打开并取消本地安全Header输入缺可信完成capability，工具前严格契约无法解析 | B39已1196源码加载，但1197–1200均工具前unreliable，run a8d30832的criteria[]/applicable=true/reliable=false及CAPABILITY_NOT_FOUND/MODEL_UNRELIABLE/EMPTY_CRITERIA/PLAN_REPAIR_EXHAUSTED；未再次打开弹窗。先实际读官方[LangGraph interrupt实现](https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/types.py)及[MIT](https://github.com/langchain-ai/langgraph/blob/main/LICENSE)，独立采用持久交互结果与外部写入证明分离。四Framework文件仅注册mcp.secure_input.cancel（RESOURCE/OBSERVED，target=name，exact Header subject）；exact host/tool/SUCCESS/typed7字段及saved/reconnected/retryAllowed=false全部严格，name超512 fail closed不截断，唯一合法取消给OBSERVED/input_cancelled/NOT_SENT/effectNONE。Compiler拒绝空/非法subject，Evaluator只该cap subject使用equals；真实保存和其它MCP仍UNKNOWN，不注册MCP.WRITE或新增publicAPI/SPI，不改审批/预算/重复保护。RunControl只同Run+invocation更新，旧source-qualified UNKNOWN不被覆盖，COMPLETED仅replay旧结果不经新adapter升级。独立静态审查/diff check通过，尚待编译加载及真实取消GUI，F07-05仍失败，不降格普通聊天绕过验收。引用langgraph-secure-interaction-reference.md。 03:51 IDEA自动Make首次实际加载后，1251–1256真实工具审批及本地虚构值遮罩→Reject→VERIFIED_COMPLETE，只证明input_cancelled/saved=false/无Header改写/无重连/无retry，未出现UNKNOWN；1257/1258同会话正常中文回复，1260重开Headers0/OFF。可信取消交互能力及B39取消恢复原路径修复后通过，F07-05修复后通过；不外推真实保存、连接或WRITE。1249/1250工具前审批超时无输入，排除。证据1251–1260 PNG与root限定虚构值日志扫描。 |
| D16 | 普通可选上下文收窄及planner失败fallback移除冻结契约尚需的已授权只读schema | 1306–1310真实Calculator六步只读run28a93a60-6fbd-4bbb-8a98-de629bd8b494，严格新链107<117<296<320<335仅前五步；snapshot167早于targets296，之后新snapshot0。provider353仅observe/open/control，377合法BLOCKED、390宿主BLOCKED及391终结；1310实机200.2秒/受阻待处理/Send恢复，CONTINUE0/taskrepair0。先实际读官方[LangGraph ToolNode注册与分派](https://github.com/langchain-ai/langgraph/blob/main/libs/prebuilt/langgraph/prebuilt/tool_node.py)、[宿主工具绑定核验](https://github.com/langchain-ai/langgraph/blob/main/libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py)及[MIT](https://github.com/langchain-ai/langgraph/blob/main/LICENSE)，独立采用authoritative注册工具与provider可见schema一致原则。仅ComputerUseContextSelection、OnDemandContextSession、ToolCatalogSession内部：同Run最新可靠/适用冻结契约及严格V3首个未满足条件映射唯一真实HOST只读callback，支持/subject/source/host contract/schema/current policy全部校验；普通可选选择和失败fallback在fetch/fixed读取前合并并预留原预算，歧义沿原受控目录。mandatory/replay/RECONCILE/UNKNOWN/input mask与真实live-session preflight优先，不丢原activation/planned、无法容纳暂停、不补WRITE/NETWORK、不改审批/严格终态或新增API/SPI。作者及独立静态审查、三文件diff check通过，尚待源码加载和新真实六步GUI回归，F21-02仍失败。来源langgraph-required-read-tools-reference.md；诊断desktop-full-chain-1306-terminal-safe-diagnosis.json；截图1307–1310为实际界面主证据。 1333/1334正常托盘退出，IDEA07:02:57.566资源清理完成/exit0；compile-d16-0703.log于07:03:24 BUILD SUCCESS/7.047秒，无测试。root已触发IDEA源码Run，实际boot与新六步GUI仍待，不以编译提升F21-02。 1335约07:04实际IDEA boot已加载；1338–1343本轮真实目录循环与预算暂停：正常只读pin生效，但首缺open不属READ_ONLY，已有当前durable activation/provider callback仍被completionRepair REQUIRED目录/hint忽略。9次目录/7次成功activation，open296终于补成，observe309预算失败；实机1343为3m24秒/251830输入对250000限制，CONTINUE0，非B40。已实际读的LangGraph宿主注册与exact dispatch原则可沿用；最窄补修只核正确接口当前合法activation与实际projection，已可用才解除冗余目录要求，不直接注入非只读工具、不授新权限、不放松预算/严格收据。两内部文件增量已作者及独立只读静态审查/diff check通过：direct pin仍只READ_ONLY，discovery名不入planned；只有当前filtered activation及实际projected callback对象与authorized map一致、source/host契约/schema/current policy都合法才解除冗余目录提示，否则REQUIRED或暂停。原activation生命周期/预算/审批/UNKNOWN/strict未改，尚待加载与新真实GUI，F21-02仍失败。诊断desktop-d16-six-step-1338-1343-final-safe-diagnosis.json，截图1335–1344。 1370实际IDEA加载补修；1406–1410本轮provider175真实snapshot只读pin→receipt182成功，strict六链40/57/109/135/150/182、0unmet。模型CLAIM_DONE两次分别添decisionEvidenceNote/decisionNote，违反additionalProperties而协议正确拒绝，1410 FAIL5m45；exact activation completion-repair分支与B40 CONTINUE均未直接触发，不写其分支已回归。仅read-pin子路径实际生效，整项F21-02仍失败，1413同scope final继续尚活跃。引用desktop-d16-activation-1406-1410-final-safe-diagnosis.json与1406–1413实际截图。 |
| D17 | 终态schema违规反馈过于笼统，模型无法定位被拒的额外字段 | 1410两次CLAIM_DONE分别添加decisionEvidenceNote/decisionNote，现有严格schema正确拒绝但泛反馈未指出实际字段。实现前实际阅读官方[LangGraph ToolNode验证位置过滤](https://github.com/langchain-ai/langgraph/blob/main/libs/prebuilt/langgraph/prebuilt/tool_node.py)、[Pydantic AI ToolManager](https://github.com/pydantic/pydantic-ai/blob/main/pydantic_ai_slim/pydantic_ai/tool_manager.py)、[RetryPromptPart](https://github.com/pydantic/pydantic-ai/blob/main/pydantic_ai_slim/pydantic_ai/messages.py)及各MIT许可，独立采用具体参数位置、现有重试边界原则。仅ModelStepJournal私有反馈：exact sole durable rawArguments按原ModelDecisionV1 schema重新验证，最多8个问题/1800字符问题说明，只安全ASCII额外属性名≤48、已知字段path/固定期望和安全code，敏感形态替换，不回显raw values、原validator message或任意exception text；constructor仅固定宿主白名单。保留JSON/可信criteria/evidence拒绝、恢复所有权、现有schema与retry/budget，不strip额外字段或autoCLAIM。作者/独立静态通过，尚待加载与原GUI回归，不提升F21-02。引用[GitHub-first记录](../target/e2e-20261004/github-references/structured-control-validation-feedback-reference.md)。 1425 IDEA08:23已源码加载，但1428–1431纯file-read轮没有目录/schema违规回归事件，不因该运行未触发原路径而记通过；旧opaque prompt仍不改。 1437–1444恢复最终完成，但没有直接schema违规纠错事件，不以no-schema-error冒称D17详细反馈分支通过。 1461新真实六步轮root限定核反馈162明确/decision/decision_note [additionalProperties]→198合法四字段CLAIM_DONE，原禁止额外字段反馈纠错分支已直接覆盖。严格210仍PARTIAL/repair1正补六步，纠错分支生效不等于整轮通过，也不改schema/预算/次数；终态待实际GUI。 已实际读取[1461–1469最终安全摘要](../target/e2e-20261004/desktop-full-chain-1461-1469-budget-final-safe-diagnosis.json)补核反馈162及合法198。最终修复后六回执齐，但repair210后没有新合法CLAIM，427 UNVERIFIED/428预算PAUSED；具体纠错分支可记生效，整轮不可升PASS。 |
| D18 | 六个可信完成条件已齐后，旧repair缺项指令仍可见、没有实时正向状态提示 | 1469严格40/50/102/227/242/253六有序HOST回执齐、253后required读hint0，但repair210后无新合法CLAIM_DONE、259590/250000预算失败，不能判强制pin Bug。实施前实际读官方[LangChain TodoListMiddleware](https://github.com/langchain-ai/langchain/blob/master/libs/langchain_v1/langchain/agents/middleware/todo.py)最新清单更新/最终答复分离及[MIT许可](https://github.com/langchain-ai/langchain/blob/master/LICENSE)。root授权后仅TaskRepairContext/OnDemandContextSession两个内部文件实现：fresh provider尾部独立宿主进度槽，仅exact同Run/scope/RUNNING、最后可信可解码可靠适用V3及原strict重算全条件VERIFIED时给完整有界安全IDs/同Run hostrefs，预算不fit或上下文不足省略；新步骤去旧进度、replay原样，保留cursor/UNKNOWN CONTROL/manifest/callbacks。提示不等于run完成或答复交付、不自动CLAIM、不禁合法新research；不改strict/schema/工具权限/预算/2repair/stopconditions。两份/tmp基线保留，gepa及root独立静态审通过；1508/1509 IDEA实际加载后，1515–1517原完整六步GUI真实完成，fresh provider288 typed尾部1680字符/完整6IDs及6refs→合法CLAIM_DONE305→strict316/318 VERIFIED_COMPLETE/0unmet→319completed，原路径修复后通过。open3/observe3不声称只有六次调用；GEPA315 unavailable/structured_output_invalid继续严格验收，无score或own-timeout通过。[实际来源、实现及审查记录](../target/e2e-20261004/github-references/langchain-task-progress-feedback-reference.md)。 |
| D19（候选） | compact后缺少当前严格PARTIAL进度提示 | 仅只读研究，未实施。实际读官方LangChain TodoListMiddleware完整状态替换/partial指引/同步异步hook及MIT；hook追加固定指引，不自动注入最新todo且模型todo不作宿主证明。最窄候选复用D18私有fresh槽，严格结果描述须唯一映射冻结IDs、aggregate host refs完整有界，剩余ID取补集，歧义/预算不fit省略；replay/UNKNOWN/权限/合法最终决策不改。本B49 strict只targets met，不能称clear通过。[来源、设计和限制](../target/e2e-20261004/github-references/langchain-partial-progress-reference.md)。 |
| D20 | 非空但不完整的可选工具集也移除已授权能力发现入口 | 1547–1551原type/Enter任务十个provider从未提供type/key/catalog而真实受阻。实际GitHub-first读取官方LangChain工具选择器保留当前合法接口的subset原则、LangGraph注册/绑定门禁及完整MIT后，root授权仅OnDemandContextSession去掉NONE→OPTIONAL必须toolNames/activation均空的条件；原policy/remaining≥2/slots/schema/inputmask/replay全部保持，不直接注入输入或执行。独立静态通过、12:27:36编译成功6.264秒/1487源码，1557实际源码加载。1559回归provider229真实READY/plannerUnavailable=false下key+observe旁目录出现，目录实际list2/activate3（两成功、一错误名称拒绝），D20入口子路径修复后通过。整轮实际579但strict英文别名未建立/预算失败，F21-05不升通过。[来源与实施回归](../target/e2e-20261004/github-references/langchain-controlled-tool-discovery-reference.md)、[最终安全摘要](../target/e2e-20261004/desktop-type-key-d20-1559-safe-diagnosis.json)。 |
| D24 | 首缺INPUT进入可选多动作投影，模型可重选已完成清除 | 原新78真实两次清除SENT，type/key可用但反复其它动作。GitHub-first LangChain真实工具subset/MIT后，root授权三个内部文件，限定可靠同Run/scope最新V3严格首缺4INPUT/空subject、READY/GRANTED/inputAllowed/pending EMPTY、当前Baseline与帧started前D21 COMPLETE canonical ID一致；fit/final唯一当前HOST callback+harness/NONE，隐藏active不消费，原D22/replay/权限/UNKNOWN/预算保持。静态冻结/14:59编译及IDEA加载后，新79实际73仅click、118仅type，hint/帧/EMPTY正确，唯一输入投影子路径修复后通过。121参数多targetId被原门禁拒绝，后续pending非空退出合理但普通投影又清除，完整任务1672 PARTIAL6/9协议暂停，动作次数及wholeF21仍不通过。详见[来源](../target/e2e-20261004/github-references/langchain-required-input-tools-reference.md)、[独立增量](../target/e2e-20261004/d24-three-source-files-incremental.patch)、[新79安全诊断](../target/e2e-20261004/desktop-type-key-d24-1668-safe-diagnosis.json)。 |
| D25 | 首次视觉结构化输出INVALID_JSON缺少一次有界同图重生成 | 旧80两首视觉非法JSON；GitHub-first Pydantic AI/完整MIT后，仅VisionPreprocessor实现typed INVALID_JSON一次同图完整重生成/控制优先/原期限/严格Parser/no第三B54。root/peer冻结、15:48:56编译、1705 IDEA加载；新81实际INVALID_JSON0/语法修正调用0，1715因postclear条件修正超时而NO_PROGRESS，不能按无语法错误计D25原分支通过。SDK retry3未改，两显式Framework任务调用不代表物理请求≤2。见[来源与边界](../target/e2e-20261004/github-references/pydantic-vision-invalid-json-repair-reference.md)、[独立增量](../target/e2e-20261004/d25-vision-structured-json-repair-source.patch)、[新81安全摘要](../target/e2e-20261004/desktop-type-key-d25-1709-safe-diagnosis.json)。 |
| D26 | confidence修正任务重生成未消费的完整观察字段，短剩余期限内两次超时 | GitHub-first实际读Browser Use专用JudgementResult/完整judge调用/全judge.py/MIT，独立实现两内部文件focused conditionEvidence/eligible IDs/max12/双数字confidence及专用短prompt，首OCR/总45或90剩余/预算与D25 FULL保持。静态审通过、16:09编译及1722 IDEA加载后，新82两次focused成功6.210/3.793秒，159/295真实proof保持原OCR/六绑定，具体修正子路径修复后通过；257严格review未选专用159而选其它可信零proof，不伪称整轮依赖此证据完成。1731whole PARTIAL6/9仍失败，不能保证所有调用延迟。见[来源](../target/e2e-20261004/github-references/d26-focused-candidate-repair-reference.md)、[限定最终安全摘要](../target/e2e-20261004/desktop-type-key-d26-1725-safe-diagnosis.json)。 |
| D27 | 输入目标能力拒绝泛报过期，模型随后改用试字符 | 新82三次完整7字符type因AXScrollArea actions0被原WRITE门禁拒绝/NOT_SENT，后模型改1字符+无elementId前台SENT，原表达式proof仍缺。已实际读Browser Use完整input/目标frame与几何处理/原text传递/值差异反馈及MIT。候选仅说明动作位、完整文本语义和现INVALID_TARGET准确分类/固定安全提示；不采用盲focus fallback/自动重试，不新增expectedPayload API或放宽门禁。提示不能机械保证模型不改参数；root已授权gepa仅ManagedSession/DesktopSessionTools实施，与B56准确分类一起，最终两/tmp增量已root/独立静态审通过冻结，16:38编译成功、1737 IDEA源码加载；1747新83GUI完整结果579，TYPE原7字符123+456及ENTER各一次真实SENT、strict9/9完成，完整文本正常子路径与整轮实际回归通过；工具说明仍非机械payloadguard，B56拒绝异常分支0未覆盖。见[实际来源与边界](../target/e2e-20261004/github-references/d27-desktop-input-target-and-payload-reference.md)。 |
| D28 | 批量删除确认明细短标识碰撞，无法审阅具体派生会话范围 | 1777–79实际短ID碰撞复现、1780Cancel83保持。GitHub-first采用LibreChat/Cline同target及原ID数组确认原则（非声称其展示完整UUID），仅SidebarSessionListController完整原ID显示、原选择/删除API不变；源审和17:28:12编译后1783最新IDEA源码加载。1785原68项、1786–1797完整ID顶到底可读/滚动、逐ID集合审计精确相等；另8项完整身份确认，1810均取消/正常模式/83保留，显示与取消原路径修复后通过。未点OK、未永久删会话或事实，不升级F23清理。见[GitHub来源](../target/e2e-20261004/github-references/d28-deletion-identity-reference.md)、[真实68身份审计](../target/e2e-20261004/cleanup-gui-confirmation-68-identity-audit-d28-1797.json)。 |

## 编译与验证边界

1195实际托盘正常退出，IDEA AX确认03:27:41 exit0；root仅执行`mvn -DskipTests compile`，compile-b39-0328.log于03:28:15 BUILD SUCCESS（6.616秒）。1196 IDEA AX12实际03:29源码Run加载B39，主70会话；1197–1200取消回归被工具前可信capability缺失阻断，没有打开弹窗，不能按这次编译/加载计B39通过。D14/D15最小源码及GitHub-first来源已独立静态通过，截至1222尚待下一次编译/IDEA加载/GUI。

1172实际正常托盘退出，IDEA AX确认主源码进程exit0（03:12:40）；root仅执行`mvn -DskipTests compile`，`target/e2e-20261004/compile-d12-d13-b37-b38-0313.log`于03:13:15 BUILD SUCCESS（7.759秒、1485源码），没有代码测试。IDEA AX12实际Run Launcher源码，1173实机主界面66会话，B37final/D12/D13/B38均已加载。1178–1181已完成B37拒绝卡/日志/Retry/停用原路径回归；D12删除、D13缺工具恢复、B38 PARTIAL显示尚未取得相应GUI验收，1174桌面轮被上下文刷新取消，不能以编译/加载或预览代替完成。

1105/1106实际正常托盘退出，root观察IDEA源码主进程exit0；2026-10-05 02:22:04+08:00仅`mvn -DskipTests compile` BUILD SUCCESS（6.866秒、1485源码，`target/e2e-20261004/compile-b35-d10-d11-0222.log`），未运行代码测试。1107由IDEA Launcher源码Run加载B35/D10/D11，1108完整主界面及原66会话保持。1109–1113已有B33/B35删除原路径GUI回归；D10/D11仅加载，1114新桌面链运行中，不能按编译或加载计通过。

1027/1028已实际托盘正常退出，root CUA观察IDEA主源码进程exit0；`target/e2e-20261004/compile-b30-b31-b32-0103.log`仅执行`mvn -DskipTests compile`，2026-10-05 01:03:30+08:00 BUILD SUCCESS（6.819秒、1485源码），没有代码测试。1029从IDEA同Launcher源码Run加载B30/B31/B32，1030原62会话/同3消息保持。B31经1031/1032原最小化→IDEA二次Run→自动置前原路径实际回归通过；B32后续1036–1039已完成OFF普通保存及ON/probe严格原路径回归；1040–1044已实测B30真实snapshot/owner/frame收据子路径，但整轮缺按序targets并在B34处失败。B33/B34新源码补丁尚未加载或GUI回归；静态review、编译和加载不代替功能验收。

2026-10-05 00:30:58，983正常托盘退出/IDEA exit0后仅`mvn -DskipTests compile`成功（6.885秒、1485源码，compile-d09-b29-0030.log），984经IDEA Launcher源码Run加载D09/B29。986–989英文搜索/路由和985/992/993受限发现已获真实GUI结果；996–1000仅桌面预览窗口操作通过。994观察整轮契约未完成，在1002真正取消、1003停止新预览。静态review、编译、局部observe匹配和GEPA评分均不代替整轮Core验收；本批未执行代码测试。

root 先前两次 `mvn -DskipTests compile` 成功，第二次 13:24:28；B05 后续编译/IDEA 回归，B06/D01 14:13 编译后 IDEA 133 原路径通过；B07 14:53 编译 1476 源码、IDEA 176 后 186 核验通过。B08/D02 经 IDEA 229 加载，B09 编译并 IDEA 308 后 309/316 主题回归，311 深色可读、313/314 D02 删除/重开回归。B10 的 4 个 framework 文件 16:28 仅编译、16:34 IDEA 已加载，341 16:38 新原 ID 处待输入；410–415实际跨30分钟正常退出/IDEA重启后，同原ID恢复COMPLETED/error:null，原路径通过。B11 两个 UI 文件 16:57 已只编译成功，411已于17:12 IDEA源码加载，427–435 环境变量/Header 敏感键自动遮罩及保存重开实际回归通过。编译不是功能通过证据，没有代码测试；技能脚本只编辑注释/清理，没有检查/运行测试，MCP GUI 测试连接为用户功能操作，严格隔离阻止真实执行。B04 蒸馏、B03 重启、B05 反馈已实际回归；技能输入时序疑点已排除，B10 专用于真实工作流问题。 B12最终三文件及清理顺序已独立静态复核，17:49:08仅编译/IDEA465加载，468–477新暂停/关重开/恢复审批/取消及MCP、定时数据库读原故障路径无JDBC，修复后通过。B13/B14三文件各自修复18:08仅编译并IDEA483加载，新502/503实际提案生成/审批/入规格为部分回归，505再评审/506待人工，完整终止待。B13最终补充与D03审批checkpoint、B15布局和B16负预算修复已18:46:05统一compile成功/6.873秒；542已IDEA源码加载；B16输入545–549及B15布局/tooltip554–557实际回归通过。新SDD552为严格JSON失败且终态可见，B13格式prompt补充、D03有效审批后恢复和完整阶段仍待；D04及B17/B18/B13格式prompt于19:24:50先编译失败、修正SDK Handler泛型后19:26:19compile成功/5.768秒；578正常退出后58019:27:58 IDEA已加载；B18间隔及每日校验587–597原路径通过，D04真实正文584部分后585失败，私有SDK碎片适配经review、应用607正常退出后19:45:16compile成功/5.668秒，608IDEA源码Run已加载，609–611虽处理完成且SDK异常不再，却只交付95字摘要，D04正文交付仍失败；prompt两文件修复双review后19:58:09compile成功/5.68秒，624IDEA源码已加载。630/631正文增量与60段终态部分已回归、649实际停止，651恢复短消息完成、653重新生成完成且无取消正文回填，D04核心原路径已修复通过。B19规划提示/B20布局/B21焦点双review后，672退出、20:27:52仅compile成功/5.961秒、673 IDEA源码加载；674/676/677布局、679–681带哨兵键盘复制及682/683纯聊天规划已实际原路径回归通过。B22旧terminal卡/B23创建年龄误标耗时源码双review尚未加载GUI；687严格JSON拒绝另为D05候选、非解析器Bug。B17已在690–695第三次手动运行后真实窄860/780、完整tooltip/点击保存完成原路径回归；696–699新增B24未保存关闭静默丢弃待修复。编译未当通过，旧进程在失败编译期间577站点保存类加载失败仅记现场；600–606新进程假站点校验/保存/重开/编辑实际通过。H2官方说明embedded中断风险及async实验性边界，不能由本轮GUI回归推广为全面线程安全。

718正常托盘退出/root实际IDEA exit0后，21:15:34仅`mvn -DskipTests compile`成功/6.484秒/1483源码，719从IDEA源码Run加载B22/B23/D05/B24/D06。721/724/731/732旧结果卡清除和诚实年龄标签已GUI原路径回归；725–728零预算保存、实际耗尽、提升续跑及729退出/731同任务SPEC批准检查点持久恢复、732无重复提案Gate已有实机证据。D05实际bounded repair是否触发仍待trace，B24未保存提醒/D06查询和安全trace导出尚未GUI回归，合法提案或编译均不代替其原路径通过。717旧包4项无canonical trace且raw logs未读，不能宣称日志无秘密；SDD最终计划/实现/完成仍待。
当前嵌入未配置，RAG原关闭，知识检索/回填等仍受依赖阻塞；↩非删除撤销、Pending事实不具恢复检索条件，临时区知识双向隔离未覆盖。工作流TRUE/DEFAULT/错误出口及编辑管理已操作，B10跨时长正常重启同ID恢复、迟到自动保存删除、B08/B09回归通过。技能持久化/清理通过，真实提案缺失。定时手动运行与后台一次性自动运行各有证据，四种触发字段及Cron预设通过；长备注历史B15及完整tooltip已554–557原路径回归，策略/通知选项及非法调度边界已595–598覆盖；未保存关闭在696–699确认B24失败待修复，删除及真实渠道发送未覆盖。MCP动态敏感键及保存重开通过，GUI删除后473重启0；完整配置复制待，strict隔离阻止连接/发现/真实日志。插件缺可信包，本地推理缺批准/注册Deliverance。B12新暂停/关重开/取消及跨页数据库读取原故障回归通过；B13新提案成功推进已部分回归，但505重复评审属D03、506/539/540待人工受阻，B14完整终止及阶段不重复仍待；B16负预算输入路径545–549通过；719加载后726零预算保存、727真实耗尽、728提升续跑及731预算持久保持已验证，F16-06通过。B22旧结果显示与B23年龄标签经721/724/731/732原路径修复后通过；D03批准后重启恢复至SPEC通过，完整阶段/验收及D05实际格式repair触发仍未通过。桌面控制等完整链路尚无通过证据。

正文原失败版本链路另做只读源码核对，不计为GUI通过：`SpringAiReasoningGateway` 637–641 使用同步 `.call().chatResponse()`，用户可见正文来自 Harness 提交的 `decision.userMessage`；`AgentConversationRunner` 218–222 在 `core.run.completed` 才发完整 Reply，`FrameworkLoopRunner` 146–160 等本轮 COMPLETED 才发完整 Reply。UI 的 `ChatStreamRenderer.appendReply` 87–94 只追加字符串缓冲，`ChatTurnOutcomeHandler` 在完成/待输入/取消终态才调用 `showFinalReply` 显示正文。因此 529/530 的运行中空正文、531 取消后出现已有正文，与当时整轮提交路径一致，不能归为已观察流式增量。`ManagedInferenceChatModel.stream` 虽有 delta 转换，但当时 reasoning 路径未使用。若补充正文逐段显示，需保持 Harness 工具与最终验收边界，避免将模型原始协议/JSON直接展示；本子任务只定位、未改源代码；D04缺失已确认，另行实现/独立review、19:26:19compile后580新IDEA加载，584已见真实回复中正文；585终态SDK merge失败使正文清除，私有SDK适配经review后19:45:16仅编译成功、608IDEA源码Run加载，609–611已完成却仅95字摘要，正文交付仍失败，prompt两文件修复19:58:09compile、624IDEA加载，630/631正文增量与完整60段及649真正停止已实测；651短回复及653重新生成均完成、旧正文保持、取消200无迟到回填，F04-04核心原路径修复后通过；正文已显后的停止/循环流式/新业务工具额外路径仍未覆盖。

Token口径按root安排的源码只读核对：每个工作区一个运行时TokenTracker，累计SDD/路由等usage，界面“会话”计数不按聊天线程。⌘N不重置；⌘L、摘要点击重置及重启会归零。顶部ctx使用同一累计，不代表新线程实际prompt占用。507新空线程仍显示28K/ctx27.9k，511清除后0、512悬浮可读与此口径相符；520非零9.1K悬浮、521真实摘要点击归0并保持今日362K及2条消息，重置已实操；估算成本不当实际账单，也不扩列新Bug。

D06只读诊断来源与采用理由：本项目`TraceRecorder`默认启用但没有生产调用方；`DiagnosticsUseCase`只把查询传给`TraceExporterDiagnosticsArchive`，最后`TraceExporter.grep`在旧workspace JSONL不存在时返回空。Framework的`StepEvents`/`AgentEngine`持久事件由`JdbcRunStore.insertEventAndOutbox`写`agent_run_events`，并非同一查询源；未读取数据库payload或秘密正文。修复应保持现应用端口，通过基础设施适配同一canonical事件存储并投影，不在UI直接JDBC，也不新增另一路执行日志。

已实读GitHub官方来源：LangGraph运行时在[`pregel/_loop.py`](https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/pregel/_loop.py)绑定同checkpointer的put_writes并put保存；[`pregel/main.py`](https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/pregel/main.py)的get_state_history按配置、filter/before/limit取checkpointer历史并投影StateSnapshot；[`SqliteSaver`](https://github.com/langchain-ai/langgraph/blob/main/libs/checkpoint-sqlite/langgraph/checkpoint/sqlite/__init__.py)的put/list写读同checkpoints/writes，按thread/namespace限制、筛选并限制条数。已核[`MIT许可`](https://github.com/langchain-ai/langgraph/blob/main/LICENSE)。采用其运行记录与查询共用持久源的结构原则，适配本项目RunStore，未引入LangGraph依赖/复制代码。补充对照Cline [`getTaskHistory.ts`](https://github.com/cline/cline/blob/main/src/core/controller/task/getTaskHistory.ts)从stateManager持久taskHistory按工作区/搜索/排序再转换响应DTO，已核[`Apache 2.0许可`](https://github.com/cline/cline/blob/main/LICENSE)；采用服务侧过滤/投影原则，GUI保持通过应用端口读取。以上仅研究/方案依据，不计修复或功能通过。

## 历史问题线索

旧控制台在 12:11 出现 `original task snapshot is duplicated`。本次只读检查发现，当前 `OriginalTaskSnapshot.restore` 已按唯一来源的 index 恢复，不将同文普通历史提升为原任务；当前编译产物也包含这项修正。此次真实审批写入、读取和重复执行防护过程中没有新增该报错，不把它列为 B07 文件内容回执缺失的根因；实际恢复入口尚未完成，不能以没有报错替代完整恢复回归。

## 清理与恢复

当前补至1360：1355/1356本轮补充智能体精确确认删除，自定义0、内置Assistant绿色保留。1359 Personal事实实际7条，逐条来源与删除尚待；不把旧6当当前数量、不按数量判断全部为可删，原workspace人工1保留。1360人格上部恢复值已核但完整禁忌及图片重启复查尚待，最终记忆/会话/产物清理未完成。

当前补至1352：1348已在重启后核对本轮假站点完整编辑字段及密码遮罩，1349拒绝删除保留，1351确认后1352列表0。此前本轮工作流/5托管/4定时/MCP清理事实可复用；其它会话、事实、智能体、产物与最终重启核对仍待。1335实际源码启动后会话58、1336新59只为当时数量，不等同可删除选择集。

当前截至1334：1320已精确删除本轮发布工作流、仅2内置保留；1322–1332已逐项删除5个本轮托管任务至共0/运行0/待人工0。托管Delete实际直接删除，无确认弹窗。其它本轮会话、事实、假站点、文件及最终重启无复活核对仍待；以下旧时点数量只为历史记录，不作为当前清理选择集。

截至1049，本轮桌面六步任务1044已失败暂停，1045实际Stop空闲只读预览，1049主窗无预览且可发送。1048筛选仅一项却全选63的B33已复现；没有点击删除，1049实际取消全选及退出管理，63会话总数保持，原7保护会话未受删除。B33修复及安全GUI回归完成前，不使用全选清理。本轮五托管/假站点/发布流程/事实/会话/产物及最终重启复核仍未完成。用户再次明确135审批曾亲自同意，只计正常Allow；137–139独立无人触碰的60秒自动关闭仍为有效超时回归。

截至1039，嵌入1038实际OFF/原空连接及1024/5/0.3绿色保存并清除dirty，1039真实关闭设置无未保存弹窗回主聊天；ON Save失败未启用，OFF probe仍严格空API错误。B32原关闭配置保存已回归，原RAG OFF保持；真实embedding连接缺依赖。本轮临时资源及最终重启持久化仍未全部完成。

截至1032，1029正常IDEA源码重启及1030/1032原62会话/同3消息和取消历史保持；B31最小化后的同Launcher二次启动自动置前已真实通过。B30/B32已同次compile并源码加载，仍待GUI回归；本轮资源清理及最终重启核仍未完成。

截至1028，1014思考预算已真实恢复原ON/4096并绿色保存；1010高级配置保持原值，1023主题Emerald/常驻ON/电脑访问ON/三Granted保持。1017–1021嵌入数值草稿均恢复原1024/5/0.3、OFF/OpenAI及空连接字段，1021保存失败，1026已真实丢弃1dirty嵌入分区；没有切Provider或变更权限。1028已正常退出，三修复仍待加载回归。四本轮调度此前已GUI清理仅2原系统ON；五托管、假站点、原发布流程、事实、本轮会话/产物及最终重启持久化仍未全部完成，原7保护会话保持。

已创建 E2E_基础对话、E2E_停止生成、E2E_重启回归、E2E_审批验证、E2E_审批超时回归、E2E_文件证据回归、E2E_聊天初始6729 及末轮分支；归档操作自动新建的 14:11 空对话也属于本轮临时数据。临时 E2E_20261004_隔离 区及其 14:06 初始空会话已经 GUI 删除，默认区仍保留。审批实际写入 `project/E2E_approval.txt`，用户同意后另写入 `project/E2E_timeout.txt`；无人操作超时的 `project/E2E_timeout2.txt` 未创建；186 新写入 `project/E2E_fileproof.txt`（以上 project 均位于本轮 target 目录）。知识文本和 Markdown 导入失败，没有成功文档需要删除。新自定义 E2E_专家在 171 保存、182/183 重启保持后已于 185 GUI 删除，内置智能体保留。发送本轮临时图片，并蒸馏出虚构 E2E_小林事实。手工新增 E2E_手工事实已 GUI 删除；剩余蒸馏事实原临时内容及未固定状态恢复。人格临时偏好/禁忌已移除，语气“简洁直接”、身份原文保持；`target/e2e-20261004/fixtures/E2E_persona.md` 为本轮导出。

本轮原工作流于233改名E2E_人工输入流程，ID `wf-90a4737f-e32a-49bf-a6b5-52d0846c788e`，308/411重启保持，条件/错误图及运行历史待清。已有#475b8f6a COMPLETED、#60291256取消、#73c237b8 TRUE/#8cd7b4cc DEFAULT/#042a9cfc错误出口COMPLETED及聊天运行。旧副本 `wf-16fcac9a-d23a-4a63-92de-d3496e133519` 原db1093c5恢复FAILED保留证据，313GUI删/314重开无复活。B10修复后新 `wf-dbbee54b-791b-4448-9468-11deb8faaad3`，原5bf39622于16:38待输入，41017:11正常退出、41117:12源码重启，413–415同ID恢复6步COMPLETED/error:null。恢复安全真实应用/重选保持后恢复SAFE，拖动开始节点立即删除，423确认、424GUI删该B10草稿、425重开无复活。本轮仅原发布流程及历史待清，内置两编排未改。

技能中心原列表为 0；E2E_文本整理（data/skills/新技能）回滚 V1，286 重启字段/停用/V1 保持、徽标实际 1.0.2；导入 E2E_导入文本（data/skills/E2E_import_skill）重开字段/停用保持，287 Finder 定位成功。290 注释脚本保持后拒绝/同意删除，293 已删；包重启字段保持、启停保存后 298 直接 GUI 删除，实际无确认。301/302 原技能启停保存后，303/304 拒绝删除保留、305 同意删除，306 再删导入项，技能恢复初始 0。提案为空缺真实本轮数据；没有执行脚本或代码测试。

定时任务初始2个内置启用；本轮间隔2分钟、一次性、每日23:54、Cron 0 53 23 * * ?四项已保存。间隔手动运行一次完成；477 IDEA重启四项OFF/两内置ON，478一次性原日期/提示词/策略保持。482一次性改为2026-10-04 18:10并启用；483 IDEA源码启动、484主窗口隐藏至托盘期间18:10:00自动触发，18:10:10交付E2E_SCHEDULE_ONCE_6729，累计/失败1/0、10秒并自动OFF；487/488四临时OFF/两内置ON，完成通知及高风险无人值守未启用。488历史B15已554–557原路径回归；553源码重启四本轮OFF/两内置ON与一次性历史保持。490–493四个Cron预设为未保存草稿，494已真正手动恢复原0 53 23 * * ?并保存disabled；560间隔第二次手动完成为2/0且仍OFF；564零间隔误启用B18后565已恢复OFF/2分钟，启停间无额外自动开始。四项尚未删除，不宣称自动间隔/每日/Cron全部执行通过；580已加载B17，窄长footer原路径待GUI；B18间隔非法类型588–592及每日596已通过、恢复原2分钟OFF/23:54，598通知恢复OFF/不通知。

MCP 初始配置为 0；新建 E2E_stdio6729、E2E_http6729、E2E_time_template6729，合法对象另导入本轮 stdio/HTTP 两项，总 5，均 disabled。354 启动尝试被策略拒绝并自动勾启用，355 恢复禁用；426 IDEA 重启后仍五项停用、0 工具。严格隔离保持开启，没有执行 missing 命令或 time 模板 uvx。366 原失败敏感键/空行未保存，368 实际鼠标取消恢复；修复后 430 保存 E2E_API_SECRET 与原虚构值，重开遮罩。435 新增虚构 authorization 保存重开后 436 已单行删除恢复原 X-E2E=6729。440 另导入 E2E_single_import6729、总 6，443 拒绝删除保留，444 同意仅删该项回 5；445 复制真实存储路径并粘贴。446–454 剩五项逐项确认删除，454 全部 0/工具 0，恢复初始；473 IDEA重启后仍全部0/工具0，读取无JDBC，清理保持已验证。插件始终 0，未安装/批准/卸载；未下载模型或启动 Deliverance/API 服务。

原聊天模型、电脑权限和已有工作区配置未持久修改；Provider 的 Deliverance/OpenAI 为未保存草稿，392 关闭丢弃后恢复以 398 实际 DashScope/qwen3.8-flash/密钥遮罩为证，393 截早不作恢复证据。非法超时 0/编排迭代 101 均拒绝；临时 HTTP/2 恢复 HTTP/1.1/原字段，未保存。399/400 NORMAL/LIGHT 仍 OFF，清除配置在 402 拒绝，继承保持。403/404思考预算1023/65537保存均拒绝，405恢复原ON/4096，406关闭脏配置确认后已丢弃；成功保存及全设置持久化仍待。RAG 165 丢弃恢复关闭/维度 1024，317 恢复 Emerald，严格隔离保持开启。本轮会话/分支/空对话、附件/蒸馏事实/文件、原工作流/历史、四暂停定时任务及四个本轮托管任务（旧文本464 FAILED、新B12回归476 CANCELLED、E2E_B13B14闭环6729于506/539/540待人工且取消未执行、新E2E_D03恢复闭环6729于552 FAILED）仍待清，五MCP已清理且473重启仍0；四暂停定时任务未删、494 Cron原表达式已保存恢复disabled，新托管500负预算误为不限已501恢复120000。临时区/初始会话、专家、脚本、包、两个技能、旧终结副本及B10回归草稿已GUI删除；全量清理未完成。 507新建本轮18:28空会话已511快捷键移除并替换为18:30空会话，列表仍30；该18:30会话随后产生E2E_研讨6729真实答复，已非空且仍待清理，未删除其他会话。 527新增本轮max2纯文本循环会话（自动标题@loop max=2 judge=off，需求含E2E_循环6729）；528完成两轮，529续发E2E_循环停止6729、531已取消，随后534–538真实澄清/温暖回答使该会话8条消息，原列表31，全部本轮循环/澄清消息亦待清。

正常退出与 IDEA 源码重启已完成；分支 133、智能体 182/183、发布工作流 230、技能/V1 286、脚本 290、包 294/295、改名/副本图 310 保持。B08/B09 及 D02 核心管理已回归；B10原失败保留，410–415已跨30分钟正常退出/IDEA重启同原ID恢复通过，423–425终结草稿已删且重开无复活；426 MCP 五项停用及字段重启保持；B11 16:57只编译后41117:12已IDEA加载，427–435 敏感键/显隐/保存重开实际通过。460 B12后正常退出备份关闭data，461/462原数据/旧PAUSED无JDBC；17:49:08最新仅编译/IDEA465加载后，468–477新暂停/关重开/审批/取消及MCP、定时读原故障通过。473 MCP清理重启仍0，477四定时OFF/478一次性字段保持；483于18:09加载B13/B14，新502/503实际提案✓/入规格为部分回归，505再评审/506待人工，未完成托管闭环。后台一次性488已真实自动完成，B15显示/B16负预算及B13最终补充/D03于18:46:05统一compile后542 IDEA加载，B15/B16原路径已GUI回归，B13/D03新552严格JSON失败未完成；539/540旧任务仍待人工未取消，541托盘正常退出；494 Cron恢复保存。19:26:19最新统一compile成功后578正常托盘退出/root观察IDEA exit0，579图实际Codex；580新IDEA加载后，站点600–606实际校验/保存/编辑通过，当前1个E2E_站点6729未删除。607再正常托盘退出/root实际IDEA AX exit0，19:45:16应用关闭时compile成功，608源码Run加载SDK兼容修复；609–611新流式处理完成但只交付摘要，正文交付修复仍待；站点重启复核亦待。623后又正常托盘退出/root观察IDEA exit0，下一轮邮箱原空值持久化复核尚待。本轮任何真实邮件/通知未发送；全部清理后仍需正常退出/重启核对所有已删除项及原设置。备份、样例、导出和证据保留在忽略的target，不纳入版本提交。

邮件613原QQ/465/993/SSL、账号/授权码/发件人空。切换预设和加密均为配置操作；620端口0拒绝后621已恢复465及原配置保存。假地址E2E_mail6729@example.invalid仅用于未保存提醒，622拒绝后623同意丢弃，没有真实邮箱授权码，没有网络收发、邮件或通知发送。下一轮源码重启需确认QQ/原空账号保持。609新增19:46流式会话仅摘要终态2条消息，与19:29失败会话均待清理；623主列表实际42，不能把此前31当当前数量。

624新E2E_正文交付回归6729纯聊天会话规划前暂停；626新E2E_正文完成回归6729（20:00）已保存60段、80段，649取消200段没有交付，650短回复终态待。最近列表实际44，以上与两旧流式会话都待清理。640源码重启后原QQ/465/993/SSL及空账号/码/发件人无dirty，621假邮箱已丢弃且未持久化。645/646五通知均OFF、原空配置保持，未执行真实网络发送。假站点1、四暂停定时任务、四旧托管任务、原发布流程及本轮事实/文件/会话仍待清理。

651取消后短答7消息、653实际重新生成追加同用户+新助手后9消息，为本轮合法操作历史，不能按重启重复缺陷自动清除。200段取消没有回填；复制草稿均Esc清除，菜单复制路径成功但⌘C焦点原失败B21待回归。GEPA671恢复目标分解/自适应ON及3/3.5/2；技能进化670恢复提案/5/0.6/nudge及bundles ON保存，AUTO仅未保存草稿、未运行。672已正常退出且进程停止，本次未显示退出码；源码下一轮加载及全量资源/清理仍待。

673通过IDEA源码加载B19/B20/B21后，原E2E_正文完成回归6729九条消息仍保持；682另新增本轮E2E_引用路径6729两消息，列表实际45，未清理。684–687四个托管项仍在，E2E_D03恢复闭环6729真实重跑后FAILED/6K，无可批准提案/OpenSpec或文件；其余旧状态不据此改写。688/689定时总6/两原内置ON/四本轮OFF、间隔2分钟和历史2/0保持，四临时定时/假站点1/原发布流程/事实及文件/所有本轮会话仍待GUI清理；B17窄长footer尚未回归。最新用户再次明确135曾亲自点同意，只计正常Allow；137–139独立无人操作60秒超时原路径结论保持。

690–695间隔任务第三次安全手动运行完成20:48:31、3/0且继续OFF；860/780窄footer及完整状态tooltip/实际保存回归通过。696名称E2E_间隔未保存6729仅真实未保存草稿，697关闭没有确认即丢弃，699重开原E2E_间隔任务6729，不新增持久任务名称；B24待修复。四本轮定时仍OFF未删，两原内置ON保留，全量清理未完成。

700/701两条诊断命令未新增聊天消息或模型调用，原本轮引用路径会话仍2消息、总45会话。703全部时间仍0事件已确认D06，但没有为验证向原始日志或数据库手工注入事件；诊断导出/配置遮罩及真实事件包需后续GUI证据，本轮临时清理未完成。

717本轮实际导出文件为`/Users/fengs/Documents/openProject/JavaClaw/target/e2e-20261004/javaclaw-diagnosE2E_diagnostics_before_D06.ziptics-2026-10-04.zip`，GUI/AX为119KB；文件名由操作helper⌘A未生效插入中间形成，非产品命名Bug。只核zip条目大小，未读raw logs正文，原生取消未产生另一zip。该文件作为本轮证据保留。718托盘正常退出/root IDEA exit0，下一轮SOURCE加载B22/B23/D05/B24/D06及真实回归尚待；本轮业务临时数据清理仍未完成。

719–732最新本轮四托管任务仍保留；7fe E2E_D03恢复闭环6729已从旧FAILED重跑，724批准后SPEC暂停、727预算1耗尽、728恢复120000，729正常退出/root IDEA exit0后源码重启，731已批准正文/9894/120000持久，732当前RUNNING SPEC/Proposal✓。这批没有最终实现写入或读回证据，不推断E2E_MANAGED.md已创建；旧1514待人工及旧FAILED/B12 CANCELLED未删除。四暂停定时、假站点1、原发布流程、事实/文件和本轮会话仍待GUI清理；B24/D06源码已加载但业务回归未做。135用户手动同意仍仅计有效Allow，137–139独立无人触碰超时证据结论保持。

733–737只新增本轮托管计划/工具审批实际操作，未清理已有临时项；同7fe最新GUI RUNNING/规格/Token23K/预算120K，最终产物及完整验收尚未确认。本轮proposal/spec/plan trace repair=false，没有为验证伪造坏JSON或触发修复；735日志tab操作被工具modal先出现打断，未记通过。135旧timeout由用户手动同意的事实继续保留，仅137–139独立无人操作超时作为B06回归。

738–759最新托管仍未完成：正确本轮E2E_MANAGED.md写/read实际成功，但B26冻结目标目录错误，root拒绝重复write后743为NEEDS_HUMAN/0运行/2待人工，741Pause没有操作成功。旧journal和错误冻结记录未改，B25/B26/B27最小源码修复待统一编译、IDEA源码加载及新实机回归；D05本轮未触发repair。747–759本轮间隔保存/放弃/取消已回归，759原名称E2E_间隔任务6729恢复且真实Save，仍OFF/2分钟/3/0；四暂停定时未删、四托管及假站点/原发布流程/事实/本轮会话文件仍待清理，不把B24修复当清理完成。

760–774 D06 canonical诊断查询与事件导出产物已有真实GUI回归，F22-02修复后通过，F22-03 trace/脱敏内容只读审阅仍在进行，不能概括rawlogs没有秘密。新证据ZIP /Users/fengs/Documents/openProject/JavaClaw/target/e2e-20261004/E2E_diagnostics_D06.zip 保留；774路径和导出按钮完整可读，未重现旧操作helper拼接文件名。B25/B26/B27均独立reviewready未compile/IDEA加载，托管仍NEEDS_HUMAN/冻结目标错/投影滞后、完整实现验收失败；四暂停定时及其他临时资源尚未清理。

775正常托盘退出/root IDEA exit0后22:00:43仅compile成功/6.387秒/1483源码、776 IDEA源码加载B25/B26/B27；779–781重开详情浏览及788标签原路径回归通过（B25运行中动态跨阶段尚未回归），782实现续跑无重复Gate。B26新错误目标被guard拒绝没有任何工具启动，修复阶段15秒timeout导致785NEEDS_HUMAN；D07预算方案独立reviewready、813仅退出菜单准备，尚无本批实际退出/新编译/加载，不提前通过。四本轮定时经803/806/809/812逐项GUI删除，仅2原系统ON，最终重启清理持久化核对待；四托管、假站点、原发布流程、事实/文件/本轮会话仍待清理。原7受保护会话精确ID/title以临时只读备份DB副本SELECT核验并在JSON keep名单保留，不按标题猜测删除。诊断快照47非维护scope/30维护scope来自138rollout metadata join而非ZIP字段，旧43/34推断已纠正；trace/config已核已知脱敏，任意rawlogs无秘密未认证。

B25范围纠正：779/780/781经过重新打开/选任务，证明requestDetail读取已生成文档和顶部当前状态正确；旧未修controller重开也会如此，不能作为原运行中阶段动态卡住的修复证据。F16-04概览/验收/清单/日志真实浏览通过，F16-03因B25动态未回归保持失败；下一newchild实施→验收/终态须不关窗口观察。F22-03不把ZIP缺scope字段当新增失败条件，scope join只解释只读元数据；rawlogs的已识别pattern/真实配置key仅内存匹配统计待根代理只读审阅，最终仅输出计数且不展示日志/凭据正文。

774原ZIP日志只读安全审阅已完成：9385行javaclaw.log和104行task.log的10类已知credential模式全部0，无未分类候选、不输出日志正文或敏感值；没有候选需要值比较，metadata明确未访问活配置密钥/解密，不能宣称真实key literal comparison已执行。F22-03在当前导出样本未观察泄露的范围修复后通过，不无限保留“未检查”，也不保证任意日志/无标签秘密永远安全。814 IDEA源码加载D07（22:27:02只编译），817–819实际恢复Gate/正确写入审批/同窗RUNNING实施0/1记录完成；B25动态跨阶段、B26完整验收与D07实际repair分支尚待，临时清理和最终重启核仍未完成。

820/821最新同7fe未关闭窗口动态完成编排100%/清单1/1/46.2K、场景1✓，B25原动态卡住已正确回归；B26错误目标→严格拒绝→唯一NORMAL15.981秒修复→正确61004冻结及唯一可信write/read→child VERIFIED_COMPLETE已有实际trace，D07新60秒分支成功。整体仍UNVERIFIED新增D08缺child receipt聚合桥，架构agent先GitHub研究再修，F16-05继续失败；不私改旧journal/场景或overall结论。四本轮调度已GUI清理仅2原系统ON但最终重启核未做；完成托管项和其他临时站点/工作流/事实/会话/文件仍保留待清理，全量持久化尚未完成。

822–865补实机受限产物/窗口/搜索/工作区/模型表单。826真实QuickLook仅三行/87B；833稳定最小化恢复、834/835最大化还原；836约790逻辑像素窄幅（渲染约1030px/1.303）、837展开侧栏、838恢复1200，部分窄按钮省略仅作布局限制不泛化字体。第二临时E2E_隔离补充6729含实际2消息/未配置模型暂停反馈、双向标记/原模型审核隔离已核并855真删除，原默认51会话保持，7原保护资源不删。知识缺嵌入整项阻塞；857模型手填及858列表probe1728ms不算chat。860默认警告拒绝/861dirty丢弃未应用配置默认，Token细项待。865托盘AX真实退出/root IDEA exit0，D08+B28统一编译进行中，没有功能回归通过；四暂停调度早已清理，其他托管/站点/发布workflow/事实/会话文件及最终重启复查仍待。

866–883最新：23:13:04仅compile成功/6.415秒/1484源码后IDEA866加载D08/B28，长自动标题单行/完整tooltip/窄790右按钮/还原867–869原路径回归。新建目录取消和预算/能力/通知完整选项补齐，负数拒绝未产生第五任务；随后仅system/120K/通知none真正创建E2E_D08可信验收6729，879列表五项。878 system,command仅草稿，未执行command；881提案批准、883计划Gate尚待同意，新E2E_MANAGED_PROOF.md尚无写入证据。D08整体未核仍失败，旧完成项/旧journal不改。四调度早已清理仅2系统ON，五托管/假站点/发布流程/事实/本轮会话文件及全部持久化最终复核待；原7精确保护会话保持。用户再次确认135曾手动Allow，排除无人到期证据；137–139独立未触碰60秒自动关闭仍有效。

884–907最新：新D08计划批准后工具前ArrayNode类型转换报错、第五任务待人工，无E2E_MANAGED_PROOF.md写入证据；23:42:26仅compile成功/6.287秒/1484源码，899 23:45 IDEA源码加载修复，原任务继续及可信终态待。893常驻OFF保存，895主窗关闭正常退出、23:41:02资源关闭；原常驻ON尚需恢复。890电脑访问ON/三权限Granted，真实桌面链路待操作、无缺权限阻塞。901/902/907重复源码新进程exit0，905手动activate后原默认56会话/2消息保持，但907主窗未自动可见；自动唤醒、临时清理和全量持久化均未通过。135人工同意、137–139独立无人超时依据保持。

## 907时点待验证项的最短剩余GUI路径

以下39项是907历史时点的待验证入口和剩余验收点；后续结果在相应行与最新汇总注明，静态FXML梳理没有提升任何状态。可在同一批安全操作中复用对应截图，取消/删除仅本轮精确ID，缺外部依赖须实记，不强行改原配置。

| 编号 | 最短剩余操作 | 尚缺结果或阻塞条件 |
|---|---|---|
| F01-06 | 将原主窗最小化后从IDEA重复运行同Launcher；等原窗自动显示，禁止手动activate充当结果 | 新进程exit0已覆盖，补自动唤醒/原实例一致；当前尚未自动显示 |
| F01-07 | 通用设置恢复常驻ON保存→关闭主窗→托盘恢复→托盘退出→IDEA重启核ON | OFF正常退出已覆盖；补ON恢复与完整常驻保存路径 |
| F03-06 | 管理中只勾两个本轮精确ID会话→删除拒绝→重选确认→核数量 | 原7保护ID排除；同一次批量操作补取消/确认 |
| F05-06 | 已有长回复展开/折叠思考与长Markdown，滚至长行再恢复 | 907历史剩余项；1142–1147折叠及1202–1205长代码wrap/表格/滚动已补齐，F05-06现修复后通过 |
| F06-01 | 输入同一E2E草稿，依次对话→研讨→循环→工作流→对话 | 已有各模式访问；补同一草稿全程及控件保持 |
| F07-01 | 模式栏审核菜单逐项看说明，切换后恢复原智能审核 | 907历史剩余项；1392/1394两模式Tooltip、1395原智能审核及1398重启保持已补，剩保存失败缺安全GUI触发条件，现阻塞；全自动仅菜单可见未启用 |
| F07-02 | 新专用会话请求仅写本轮E2E_reject文件，出现工具Gate点击拒绝 | 仅拒绝本次未执行写入；GUI目录核文件不存在及输入恢复 |
| F07-05 | 真实安全任务自然触发密钥请求时仅用假值查遮罩并取消 | 907历史剩余项；1183–1188已真实触发并全遮罩/拒绝，最终UNKNOWN暂停为B39，修复后原GUI回归仍待 |
| F07-06 | 从本轮D08待人工实际继续，按其恢复Gate继续至终态并核记录/副作用 | 已有工作流/B10/SDD检查点证据；当前D08完整恢复依赖原路径修复回归 |
| F08-01 | 设置智能体选一个内置项查看所有字段/按钮限制，再返回 | 内置详情/限制尚未完整记录，不修改原内置 |
| F08-02 | 新E2E专家填合法ID/描述/虚构提示词保存→重开 | 此前名称/ID/描述已测，补显式提示词保存与恢复后删本轮项 |
| F08-03 | 同新专家依次空必填、非法ID、已存在ID尝试保存，恢复合法 | 非法ID已有；补空必填/重复ID具体反馈且不半保存 |
| F08-05 | 同专家虚构描述点AI补全→审阅结果→保存重开 | 真实模型可用；补忙碌/终态/结果保存，失败不虚报补全成功 |
| F11-03 | 导入选择器取消一次；选不含SKILL.md的本轮目录观察错误 | 907历史剩余项；合法目录227、原生取消1152、无效Zip明确反馈1170现已齐，整项通过；合法Zip未实测 |
| F12-02 | 新增OFF临时MCP，stdio空命令/HTTP非法URL保存各一次，恢复合法后删 | 对应字段展示已有；补必填及格式反馈，strict ON不启动 |
| F12-06 | OFF临时MCP复制配置粘搜索/草稿核对，编辑重开再删；查看日志入口 | 存储路径复制已有；真实连接/工具日志受strict隔离阻塞，只记录其实际反馈 |
| F14-06 | 重启后打开调度列表核只原2系统ON，无4已删除本轮任务 | 未保存三选/删除已完成；补最终重启无复活/无继续触发 |
| F16-07 | 1322–1332已逐项核对并删除全部5个本轮托管任务 | 实际直接删除无确认；共0/运行0/待人工0，通过。1324文件名confirm不代表有弹窗；最终重启无复活核对仍属收尾，不扩为全量清理完成。 |
| F17-01 | 设置分组全部展开逐页访问，搜索中文和英文关键词再清空 | 已有中文/无匹配；补英文及清空导航。空依赖页只验证导航 |
| F17-04 | 核已有思考预算/HTTP/超时/迭代非法反馈与恢复证据，必要时补其余可见边界 | 真实FXML Token控件仅思考预算，无maxTokens额外输入；403–406已测其边界，不造新控件要求 |
| F17-06 | 嵌入表单逐项参数非法输入→保存拒绝→恢复原值/丢弃 | 原API空/UNCONFIGURED已测；成功健康连接需真实embedding地址/模型/密钥，当前缺失 |
| F19-02 | 1348重启后完整字段MASKED保持；1349Reject/1350保留、1351Allow/1352列表0已实测 | 编辑及删除子路径通过；无真实登录session，Reset禁用不能替代会话重置取消，整项现阻塞。 |
| F20-01 | 切代表性Midnight看现有代码块/禁用控件/普通确认弹窗，再恢复Emerald | 907历史剩余项；1207–1222代表性Midnight代码/禁用卡/确认/子窗与Emerald恢复已补齐，F20-01现修复后通过 |
| F20-02 | 记录原字体/等宽/密度；逐选实际可用字体及3密度，看中文/代码后恢复 | 907历史剩余项；1211–1217补Compact/Roomy主文代码及Native/SystemMono/Cozy恢复，F20-02现通过；密度控制字号，无独立字号字段 |
| F20-03 | 留E2E草稿，CmdM连续两次查同窗口，关闭回草稿；Cmd,同样，再CmdK/N | 四快捷键已执行；补重复不开第二窗及未发草稿保护 |
| F20-05 | 选仅含本轮临时消息精确ID会话CmdL清除，核它移除和其他会话保持 | 907历史剩余项；1223本轮2消息会话实际CmdL→新0消息/原临时session移除/其它侧栏保持，现通过，原7未选 |
| F21-01 | 通用页检查当前ON/三Granted及检查按钮实际可用状态 | 890权限就绪非阻塞；按钮若已授权而禁用记录实际，不撤销权限制造缺失 |
| F21-02 | E2E会话请求只看本轮无敏感窗口，核授权反馈及回传 | 当前权限全Granted；缺的是实际安全桌面请求结果，不是权限 |
| F21-03 | 真实桌面会话预览出现后最小化/最大化/还原，对照本轮窗口变化 | 需先获得真实桌面session/预览；未出现不以JavaClaw主窗替代 |
| F21-04 | 同临时桌面session点击前台接管后停止 | 仅本轮session；观察状态与停止释放，不误控其他应用 |
| F21-05 | 同获准临时窗口安全点击/输入E2E标记，核实际窗口及日志 | 需真实可用桌面adapter/session；不保存或外发，失效时记录具体错误 |
| F22-04 | 系统维护→测试数据清理→只读扫描，核路径/大小或0候选 | 这是用户扫描功能，不运行测试。不得删除data/及未知旧junit目录 |
| F22-05 | 仅全部候选都为本轮可删临时目录时先取消再确认 | 暂无已确认本轮合法候选；无候选/夹未知历史目录时清理具体依赖阻塞，不制造测试执行 |
| F23-01 | 按本轮台账逐页重开实际保存资源，汇总已有持久化图+缺项补图 | 依赖受阻模块单列；凭据仅遮罩/元数据，不重造已清理资源 |
| F23-02 | 恢复原常驻ON保存→关闭主窗→托盘恢复 | 可与F01-07同次操作；原ON目前被本轮OFF改值，需恢复 |
| F23-03 | 与F01-06同次重复IDEA启动，从最小化原窗观察自动恢复 | 二进程exit0已有；手动activate不能充当自动恢复 |
| F23-04 | 恢复/清理前一次正常退出并IDEA源码重启，按台账核保存/取消及删除状态 | 同批资源核对可复用；D08可信整体终态/新产物尚待 |
| F23-05 | 完成持久化核对后GUI清理本轮五托管、假site、流程、事实、精确ID会话等并恢复原设置 | 四调度/MCP/技能/专家/临时区已清；原7保护ID必须排除，停止桌面会话 |
| F23-06 | 清理后正常退出→IDEA源码重启，核原区、原设置、2系统调度及临时资源无复活 | 必须最后进行；当前清理未完成，不能提前通过 |

910–943最新：原Tray ON真实恢复保存。D08同任务恢复到整体已核验完成，child/Core/H2关联一致，原聚合缺口已回归；但write1/read2不符合严格读一次，重启绑定/完成结论仍待，不能泛化所有需求约束通过。新的E2E_MANAGED_PROOF.md/第五任务仍留待清，旧四任务不改journal；新E2E_专家补充6729禁用/虚构提示词已保存，AI补全busy待结果及删除。字体恢复Native/monoSystem/Cozy，没有其它可用字体不虚报切换。B29英文搜索原路径失败，aliases实现尚待编译/源码GUI；原7保护会话保持，最终清理/持久化未完成。907时点39条剩余路径是历史快照；当前35待验证，F08-01/-02/-03转通过、F17-01转失败，F16-05原失败转修复后通过。

944–959最新：AI完整prompt审阅末尾并946真实保存，新禁用专家待清；模式切换同草稿和连续设置/MCP快捷键单窗口复用实际通过，947新空会话短标题补B28边界。维护只读扫描0目录/清理禁用，F22-04条件分支通过、F22-05无本轮合法候选阻塞，没运行或制造测试、没删目录。959真实安全桌面请求运行中，权限ON不变，尚未得到结果或可停止桌面session，不提前声称桌面链路/释放通过。当前30待验证；B29英文别名原路径仍失败待编译/GUI，其他本轮五托管/假site/流程/事实/会话及产物未全清，原7保护ID保持。


### 967–983：人工拒绝与托盘闭环（2026-10-05 00:24–00:30）

用户确认旧135弹窗曾由本人同意，仍排除超时结论。961/962、963/964无人操作自动超时；965点击时弹窗已消失，不能算人工拒绝。967再生成，968真正弹出正确目标文件审批，969点击拒绝（30.1秒，早于60秒期限），970取消并恢复发送。975 Finder完整目录只列5个本轮文件，无E2E_reject6729.txt；976 Finder QuickLook核对D08新E2E_MANAGED_PROOF.md真实87B、三行虚构事实。

910恢复原常驻ON后，978关闭主窗口隐藏到托盘；979/980实际显示主窗口，981稳定恢复原58会话、6消息及取消终态。982菜单退出/983后IDEA实际AX观察主源码进程exit0。D09、B29与单实例诊断已三方静态复核通过，仅待编译/IDEA源码加载与原路径GUI回归，不提前记通过。

### 984–1003：D09受限发现、B29搜索及真实预览（2026-10-05）

983托盘退出/IDEA exit0后，00:30:58仅compile成功（6.885秒、1485源码），984从IDEA源码Run加载D09/B29。986–989真实英文model/appearance匹配并Enter正确路由；990/991中文/清空截图待根代理审阅，原925失败保留，F17-01暂不改判。985原范围新请求仅probe+applications/Calculator，992唯一真实com.apple.calculator/catalog完整页，993GUI两条件已核验；安全host元数据为VERIFIED_COMPLETE/2refs/0unmet，probe/apps各1次SUCCEEDED+OBSERVED。这只通过发现子路径，不代替F21-02窗口内容返回及整条观察任务。

994真实Calculator launch/open control=false/observe数字区/snapshot产生996实际预览；997最大化、998正常、999compact/minimized、1000还原窗口验证，F21-03通过。Core仍缺未经请求的probe/targets，targets未调用，snapshot“Calculator window”非空内容条件与其可产证据不符；observe自身c6匹配/GEPA0.95不能代替任务完成。1001停止旧预览后model repair又开新session，1002主任务真正取消（3m23/158431in/4323out），1003再停止新预览。F21-02保持待验证，F21-04前台接管及F21-05控制输入未做，架构修复分析进行中。

1003时点146项为81通过/13修复后通过/1失败/25阻塞/26待验证。此轮运行取消和预览关闭已有真实证据，未清理本轮会话、五托管、假站点、发布流程、事实/产物等全量临时资源；原7保护会话保持，最终清理和重启持久化仍未完成。旧135用户亲自同意仍仅计正常Allow，137–139独立无人超时结论有效。

### 1004–1028：单实例边界、模型参数及权限检查（2026-10-05）

1004实际黄钮最小化原主窗、1005同Launcher二次源码Run；1006第二console SHOWN/exit0时IDEA仍前景，1007仍第二console不作主证据。1008及root真正主console00:44:34 SHOW前后showing保持true、iconified true→false、focused保持false，只通过解除最小化，自动置前仍待；B31标准Desktop.requestForeground(false)守卫调用仅静态review通过，F01-06/F23-03不提升。

root已实际审阅986–991，B29英文model/appearance匹配、两类Enter路由、中文模型三匹配及Clear恢复所有分组原路径修复后通过；F17-01还缺全部分类逐页访问，暂保留失败。1009初载入空不是丢配置；1010/1011原高级/基本配置完整。1012唯一Token控件思考预算1024Save、1013预算65536Save、1014恢复4096Save均绿“已保存并生效”，结合既有非法边界、思考开关、HTTP/超时/迭代及默认影响说明，F17-04通过。

1016实际未输入维度0，只空API保存拒绝，不作数值证明；1017维度0、1018检索0、1019分数1.01均真实具体红色拒绝，1020恢复0.3仅查看Provider。1021原OFF且空连接恢复保存仍强制API必填，新增B32；1025/1026实际丢弃嵌入草稿。B32仅单文件OFF普通Save跳过连接必填，ON/probe及Provider/数值仍严格，静态review/diff通过待加载回归，真实embedding连接缺依赖，F17-06整体待。

1023原Emerald/常驻ON/电脑访问ON/三Granted；1024实际检查后仍三Granted、系统权限就绪，F21-01在当前已有权限分支通过，缺失权限路径没有真实条件而未冒充。B30 snapshot拒绝非空subject走既有bounded repair，并只桥真实PNG及前后同owner/frame的typed metadata；四文件静态交叉review通过，旧Evaluator顺序/有效frame/previous observe等严格条件保留，模型追加probe/省略targets不由宿主放宽。1027/1028正常托盘退出/主IDEA exit0后统一compile截至此批未确认完成，B30/B31/B32均未GUI回归。

截至1028，146项为83通过/13修复后通过/1失败/25阻塞/24待验证。四调度早已清理，其他本轮临时资源与最终持久化仍未完成，原7保护资源保持；当前不可视为E2E收尾完成。

### 1029–1032：修复加载与单实例自动置前（2026-10-05 01:03–01:05）

1028正常退出后，compile-b30-b31-b32-0103.log实际01:03:30+08:00 BUILD SUCCESS/6.819秒/1485源码，仅-DskipTests compile。1029 IDEA同Launcher源码Run加载三修复，1030主窗稳定原62会话/同3消息及旧取消后的最新历史保持。

1031实际黄钮最小化原主窗后只activate IDEA，再从IDEA二次Run；1032没有手动activate JavaClaw，稳定实际Launcher已经自动成为前景、原62会话/同3消息完整主窗恢复。root CUA只读IDEA第二console01:05:19.709协议SHOWN/exit0，未观察第二数据库初始化或第二独立应用窗。B31自动置前原故障路径修复后通过，F01-06/F23-03修复后通过；B30/B32仍待原GUI回归。

截至1032，146项为83通过/15修复后通过/1失败/25阻塞/22待验证。清理及最终重启持久化尚未完成，原7保护资源保持，不把本批单实例通过当成整体E2E收尾。

### 1033–1039：B32关闭未配置保存原路径回归（2026-10-05）

1033打开设置、1034原OFF/空API嵌入表单保持。1035无dirty点击Save没有反馈，排除为保存证据。1036实际OFF测试嵌入仍明确“嵌入测试失败：嵌入API地址不能为空”，probe严格保留；1037实际切ON并Save同空API错误、dirty且未成功启用。1038真正切OFF再Save，绿“已保存并生效，下一轮对话重建智能体服务”、dirty清除，1039真实Close无未保存弹窗回主聊天（截图已只读视觉核实）。B32关闭未配置保存原故障修复后通过。

F17-06数值边界及关闭保存子路径已完成，真实健康连接仍没有embedding地址/模型/密钥，当前不能执行，整体从待验证改阻塞。截图命名1035含save-result不代替实际dirty保存，1037错误不等同成功启用，连接拒绝不代替成功连接。

截至1039，146项为83通过/15修复后通过/1失败/26阻塞/21待验证。B30真实snapshot/观察整轮仍待GUI回归，全量临时清理及最终重启持久化仍未完成。

### 1040–1049：真实桌面证据子路径与新增恢复/筛选失败（2026-10-05）

1040从原模型新建专用E2E_截图回归6729，只操作无敏感Calculator，明确六步apps→launch→targets→open(control=false)→observe数字显示区→snapshot，不输入、不读其他应用。1041真实预览显示32.66666667×9及294，实际observe返回一致。root只读本轮安全收据确认实际PNG保存及owner/frame关联：109/347 snapshot的session为2a0908dc-25f8-4d11-8f55-82c57676441d、target为21041375-5003-3028-9751-44ae8db8195e、applicationId=com.apple.calculator、provider=macos/process33977、窗口代次1/修订2，具不同captureUUID及实际capture时间；该轮snapshot空subject验收已匹配，B30真实证据桥子路径已有回归。发现Calculator英文别名仍绑定实际中文计算器及精确applicationId，不判身份缺陷。

首次执行模型先targets/open/observe/snapshot，后修复执行apps/launch/open/observe/snapshot却省略重新targets。review384为5/6满足、唯一未满足按apps/launch之后的targets；早于这两步的旧targets不能借用，不改Evaluator顺序条件。1044整轮终态失败5m7s/171757in/6304out，黄色暂停原因persisted invalid harness decision step conflicts with provider call。定位B34为同run/model/invocation已成功harness decision及规范化decision_submitted，被ModelStepJournal从敏感内容已隐藏的provider rawArguments占位重新解析，错误转记invalid control而冲突。源码补丁待加载/GUI回归，F21-02从待验证改失败；不以已匹配snapshot/observe或GEPA判六步链完成。1045实际Stop本轮空闲只读预览，后续截图没有桌面窗口；本轮未执行前台接管或输入。

1046进入会话管理，1047精确搜索E2E_截图回归6729只显示一个本轮会话，1048实际全选却显示删除选中（63），确认B33搜索隐藏项亦被选中。没有点击删除。1049实际取消全选并完成管理，截图确认非管理态、同一搜索结果、原63会话总数保持；原7保护会话未删除。F03-06从待验证改失败，源码修复实施中；须先完成单项/多项/空结果/改变筛选GUI回归，再只对本轮精确会话进行取消/确认删除。1041、1044、1048、1049截图已只读视觉核实。

用户最新明确135旧审批由本人同意，仍只计正常Allow，不能做超时回归；137–139独立无人操作60秒自动关闭依据保持。截至1049，146项为83通过/15修复后通过/3失败/26阻塞/19待验证。B33/B34未加载/回归，不提前通过；全量临时资源清理和最终重启持久化仍未完成。

1052正常退出后于01:29:09编译B33/B34成功（1485源文件，6.846s；仅skipTests compile），1053由IDEA Run源码启动，1054原63会话保持。1055单一可见本轮会话全选显示1；1056无结果时全选/删除禁用；1057改精确两个本轮流式会话旧选择清零，1058全选仅2。此时确认框正文仅约36px，1059滚动仅末尾短ID可读，另记B35。1060试拖后无owner确认框被主窗口遮挡、1061–1063主灰阻塞；1065实际AXRaise原确认框、1066实际取消，未删任何会话。B33核心筛选路径已修复，确认删除尚未执行，因此F03-06保持失败。B35源码按应用CSS后真实正文高度重算、全正文滚动及所属屏幕边界限制；批量删除框绑定主Stage，源码静态复核通过，未加载不计通过。

1067新E2E_全链回归6729将总数增至64，真实运行a8979e4e-f148-41d9-92d2-c735fecb72fa仍失败。已限定只读安全元数据保存在target/e2e-20261004/desktop-full-chain-receipts-1068.json：apps40、launch50、open60、observe75、snapshot142先于targets214，补前置targets后未重获后续证据，338 PARTIAL/339 FAILED且3未满足。1068真正桌面预览显示294，不是设置导航证据；1069Stop关闭预览，主失败4m15/121836in8444out；1070可见实际图片路径及IDs。模型“按序六步”的文字不改变实际回执次序，F21-02保持失败。B34已成功恢复本轮completed CLAIM_DONE，但该provider rawargs没有脱敏占位，旧占位分支仍未直接复现。

新功能缺陷D10为辅助GEPA评估在一次结构化输出修复仍超限时中断主任务。真实第二次summary678→591字符仍大于500，第一轮558→430成功；均不是D05包装JSON修复分支。先阅读GitHub [Pydantic Evals评估异常处理](https://github.com/pydantic/pydantic-ai/blob/main/pydantic_evals/pydantic_evals/evaluators/_run_evaluator.py)67–105和[独立失败汇总](https://github.com/pydantic/pydantic-ai/blob/main/pydantic_evals/pydantic_evals/dataset.py)1153–1188，采用辅助评估失败独立记录、保留任务输出的原则，MIT许可证已读并保存，独立实现。ModelTaskOutputException既有异常迁至provider-neutral framework SPI；GEPA仅窄捕获有界结构化输出失败，明确mode=unavailable/reason=structured_output_invalid，不造score或pass；审计异常和线程中断继续传播。500字符与一次重试不放宽，原失败模型步骤保留。两个既有测试仅适配import，无新增或运行代码测试。root静态复核通过，待编译和真实GUI回归。D11有序修复提示仍由协作agent先研究GitHub实现，尚未加载。

1071–1090实际打开设置14入口，模型/分级/嵌入/智能体/GEPA/技能演化/MCP/站点/样式/字体/通用/维护/邮件/通知均有对应标题及真实内容。原模型qwen3.8-flash/密钥遮罩/think4096、嵌入OFF1024/5/.3、GEPAON3/3.5/max2、MCP0/0、假站点未登录、Emerald/native/SystemMono/cozy、托盘与AppAccessON、QQ465/993 SSL账号空、通知关闭均保持；本批未编辑。1050空载入帧和1068预览不计导航，1088仅展开通信。B29搜索原路径986–991结合全页导航，F17-01修复后通过。1091/1092临时智能体OFF及AI生成提示词重启真实保留；1094定时任务仅两原项均启用，4本轮任务未复活，F14-06修复后通过。

1095记忆窗口初载空白不作数据证据，1096稳定个人习惯总览为6事实，1097事实页逐条删除入口真实可见。本轮资源清理尚未执行。1098发起新的恢复验证，1099模型在写文件前提前澄清，不能计副作用已完成；1100通过输入回复推进，当前尚未完成恢复验证。用户再次确认135本人同意，仍排除无人超时结论，137–139独立证据保持。

## 1101–1114 实机回归补充

1098–1104本轮恢复验证没有已完成副作用：1101 code_read反馈目标不存在，1102及1103写入审批均60秒超时，没有写入；1104 root实际取消该轮。旧同号approval命令因操作审批超时未执行，不能算点击同意/拒绝证据。F07-06仍待验证，不用无人超时冒充有效人工拒绝，也不据此判断完成副作用不会重放。证据1101-recovery-approval-state.png、1102-recovery-file-approval.png、1103-recovery-retry-running.png、1104-recovery-after-approval-timeouts.png。

1105/1106真实正常托盘退出/IDEA exit0，02:22:04 compile及1107源码Run、1108主界面确认66会话。1109精确搜索E2E_流式回归6729并仅选两项，确认框5行正文及fa30ce80/6ebb5ff8短ID均完整可读；1110点击主背景后框仍前景。1111真正取消，66与两匹配项保留；1112重开同两项确认框，1113真正同意，仅删除这两项，66→64、无匹配且退出管理。B33筛选范围、B35正文高度/owner及删除取消/确认原路径实际通过，F03-06修复后通过；原7保护会话未触及。证据1105–1113 PNG均经root视觉核实。

1114新E2E_全链回归6729真实六步只读请求创建后总65，当前运行中；D10/D11已编译并IDEA源码加载，尚无完整终态和按序收据，F21-02仍失败。不得把请求提交、预览、局部snapshot匹配或编译当整链通过；其余本轮临时资源及最终重启持久化尚未完成。证据1114-desktop-ordered-chain-after-fixes-request.png。

截至1114，146项为83通过/18修复后通过/1失败/26阻塞/18待验证。清单146行均为6列，无重复编号；当前三项更新为F17-01、F14-06、F03-06修复后通过，其余状态不变。135用户本人同意仅计正常Allow，137–139独立无人60秒超时证据保持。

## 1115–1128 MCP 与桌面修复分支补充

1115文件名含MCP但实际仍主窗；1116才是真正MCP中心全部0/运行0/工具0。1117新增窗口初载空白不计表单反馈；1118稳定stdio有命令、逐行参数、环境变量和启用字段。命名E2E_MCP补充6729并关闭启用后，空命令在1119/1120操作后于1121滚动区下方显示明确必填错误；1122 HTTP空URL报必填，1123 ftp://127.0.0.1:9/mcp报必须有效http/https。1124改http://127.0.0.1:9/mcp后Test明确严格项目隔离拒绝本机/回环，不作为连接成功。1125保存busy帧不计稳定保存；1126卡片已禁用/已停止1/运行0/工具0及绿色“配置已保存，服务器保持停止”证明OFF保存成功。结合原343–353逐行参数预览，F12-02实际通过。

1126卡片复制针对HTTP URL；1127粘入主聊天草稿的精确内容是http://127.0.0.1:9/mcp，没有发送，也没有复制完整配置JSON。当前卡片无完整配置复制入口，按实际命令/URL复制验收，不能沿用“完整配置复制已测”的错误描述。1128仅打开删除E2E_MCP补充6729确认，全部仍1/已停止1，尚未据此计删除完成。F12-06日志仍待GUI，保持待验证；存储路径和旧临时MCP删除/重启0的先前证据保持。

1114本轮02:25创建session cc175ba1-d41d-4fc1-90d8-1ea4fd92f72f/run 5e7f07b2-c90c-4657-98d0-1fa9df8c50e6经只读安全诊断确认：apps40→launch50→open60→observe75→snapshot100后迟补targets155；274 GEPA结构化summary重试仍超过500，275真实mode=unavailable/reason=structured_output_invalid后主任务继续review276/repair277及后续工具。277含D11新完整冻结条件/有序能力链/禁重放副作用指引，随后重新open302→observe317→snapshot342，完整顺序40<50<155<302<317<342真实存在，非模型描述推断。D10辅助失败降级及D11后续只读重查子路径已真实生效。

最终430 MODEL_INPUT_TOKENS预算266535/250000停止，431 PARTIAL三项DESKTOP_POST_ACTION_PROOF，432 PAUSED；1127实际GUI5m2s/失败、显示相同预算警告并恢复发送。B36为V2旧60/75/100 linkedEvidence遮蔽V3完整顺序新302/317/342；原顺序校验正确，修复按同一候选链运行既有严格linked核验并保留全部真实动作事件。修复静态通过、仍待加载/GUI回归，F21-02整体失败不变。安全JSON为target/e2e-20261004/desktop-full-chain-receipts-1124.json，未读原7私有消息、未输出raw desktop targets/windows/provider结果或凭据。

本批只将F12-02转通过；F12-06仍待、F21-02仍失败，其余状态未变。最终汇总统计待下一批实际证据统一更新。135由用户本人点击同意，继续仅计正常Allow，不能作无人超时；137–139独立无人超时依据保持。

1134–1146最新（root实际GUI，诊断只用于定位）：02:41:52 IDEA源码加载B36后，新第66会话1134提交限定Calculator六步只读请求，1135预览294；1141本轮3m46/124778in/5364out且正文明确未完成，1142展开历史、1143收起与长区滚动、1144observe长记录展开、1145/1146继续滚动仅证明实际浏览，长Markdown/代码横向等仍待。宿主321 PARTIAL选定43/53/217/241/256，新open/observe不再被旧链遮蔽，唯一缺项是targets后新snapshot真实未生成，非预算停止。D13必要工具暴露与B38终态投影已最小实现且独立静态通过，均未编译/GUI回归，本轮F21-02仍失败。1137–1139同D08任务重启后100%/1/1/47.9K、任务验收已核验完成、实现项✓保持，完成关联GUI耐久已补；暂不清理。此批没有修改146项状态或更新最终汇总；安全JSON不含raw provider/targets/window结果或原7私有消息。


## 1147–1170 长记录及技能导入补充

1142展开思考历史、1143收起并滚动长区，1144展开observe长记录全文，1145/1146逐段滚动；1147点击已展开正文后真正折叠，界面恢复摘要和“展开全文”，输入区及其它记录未遮挡。F05-06思考/工具长记录展开、滚动、折叠已实测；代码长行横向及其它长Markdown边界仍待，整项继续待验证。证据1147-thinking-long-record-collapsed.png及1142–1146对应实际截图。

1148技能列表0打开导入，1149选目录，1151真正原生DirectoryPicker，1152实际Cancel后列表仍0。1153选择Zip太晚，1154发生Choice60sTimeout，该次不计成功选择；1155及时重开选Zip，1156真正原生FilePicker。后续多次AS/CG路径输入未生效仅是电脑输入未完成，不计JavaClaw缺陷。1166先原生clearX再输入无underscore ASCII的本轮E2Ebad6729.zip，1167路径匹配，1169原生选择76bytes文件，1170真正Open后Toast“zip中未找到SKILL.md”，列表0无新增。与227合法SKILL.md目录成功导入合并，F11-03按“目录或Zip”的既定入口及取消/无效包验收通过；未宣称合法Zip导入成功。证据1148/1149/1151/1152/1153–1156/1166/1167/1169/1170 PNG，1170-skill-invalid-zip-feedback.png。

MCP1129真实Start只有泛服务器启动失败/STOPPED卡片且日志隐藏，B37两文件修复和SecurityException安全摘要已静态审查，最终补充尚未加载及GUI回归；F12-06仍待验证。D12精确来源prune已静态就绪，仍需新源码加载后删除本轮精确来源会话并确认；旧raw是否存在未证实，不提前记阻塞。D13/B38亦待源码加载及真实原路径回归，不据静态结论提升功能状态。

截至1170，146项为85通过/18修复后通过/1失败/26阻塞/16待验证；本批只将F11-03转通过，F12-02已在上批转通过。清单146行均6列、编号不重复。135由用户本人同意只计正常Allow，不能作无人超时；137–139独立无人60秒超时证据保持。此批未编辑源码、操作GUI、运行测试、编译或写数据库。


## 1172–1182 源码加载及 MCP 明确失败回归

1172托盘正常退出，IDEA AX确认03:12:40 exit0；03:13:15仅编译BUILD SUCCESS（7.759秒、1485源码，compile-d12-d13-b37-b38-0313.log）。IDEA AX12实际启动现有Launcher源码配置，1173主66会话保持，B37final/D12/D13/B38已加载。编译和源码启动仅证明可加载，未作为功能通过证据。

1177本轮唯一MCP仍停用；1178实际Start后需要处理1/已停止0/工具0，错误卡明确“严格项目隔离已拒绝本机或回环 MCP 端点”，查看日志入口可用。1179打开真实日志窗口，启动错误保留相同明确原因、stderr0；1180关闭后真正Retry仍明确失败。1181取消Enable后恢复已禁用/已停止1/需要处理0/工具0且错误卡消失。B37原泛失败/STOPPED丢错误的路径修复后通过；本轮离线dummy仍1OFF留作后续安全输入Cancel验证，本轮删除及最终重启待补，F12-06仍待验证。证据1177-mcp-new-source-restored-off.png、1178-mcp-b37-failed-detail.png、1179-mcp-failure-log-open.png、1180-mcp-b37-retry-explicit-failure.png、1181-mcp-b37-disable-stopped.png。

1174于03:14新建E2E_全链只读请求至67会话；1175/1176真实运行、Calculator只读预览294/control=false。root在该轮活跃时操作MCP启停，工具上下文刷新取消了桌面运行，1182实机1m55/已取消/仅一条用户消息，没有完成回复或完整结果。该轮因回归环境操作被中断，不计D13或六步链通过，也不据此新增Appbug；D13/B38仍需独立新轮回归，F21-02既有失败保持。证据1174-full-chain-new-source-request.png、1175-desktop-chain-progress.png、1176-management-menu-running.png、1182-full-chain-final-state.png。

此批146项状态不变：85通过/18修复后通过/1失败/26阻塞/16待验证。D12精确来源删除仍待真实GUI，旧raw存在未证实，不能预判阻塞。135用户本人同意继续只计正常Allow，137–139无人超时证据保持；此文档补充未改源码、操作GUI、运行测试、编译或写数据库。


## 1183–1191 安全输入取消及恢复前置阻塞

1183新真实E2E安全输入请求至68会话，仅本轮offline dummy/X-E2E-Probe参数；1184实际工具审批由root同意，1185出现本地SecretDialog，1186只填虚构E2E_FAKE_VALUE_6729且全点状遮罩。1187真正拒绝后弹窗消失、模型继续，1188最终黄色UNKNOWN暂停/右侧失败，2m27且仅一条用户消息。遮罩子路径已实测，但取消恢复交互没有闭环，F07-05不能计通过；原Bug为B39，不把普通审批同意当密钥保存。证据1183-secure-offline-header-request.png、1184-secure-header-tool-approval.png、1185-secure-local-masked-dialog.png、1186-secure-fake-value-masked.png、1187-secure-input-rejected-resumes.png、1188-secure-cancel-final.png。

B39两文件取消typed no-write补丁已独立只读审查：exact宿主取消分支尚未copy/save/reconnect，仅7字段无secret数据；严格exact tool、字段类型数量及参数绑定才建立FAILED/NOT_SENT/effectNONE。工具调用SUCCEEDED表示本地取消已处理，与未发生WRITE的FAILED effect区分；不放宽WRITE证明、不吞真实UNKNOWN或save失败、不改风险/审批/重复屏障。源码就绪尚未编译加载或原GUI回归，不能据静态审查通过功能。

1189新恢复请求至69会话，1190模型思考，1191实际32秒UNRELIABLE_CONTRACT在工具执行前暂停。root仅本轮session10c8ff91/run6731cee4只读诊断显示originalTask contract repair返回无效JSON含未转义中文引号，INVALID_PLAN/CONTRACT_UNRELIABLE/EMPTY_CRITERIA/PLANNING_REPAIR_FAILED，write0且没有已完成副作用。该轮未进入副作用恢复，不能作为恢复失败或恢复通过；root将简化真实请求再试，F07-06继续待验证。1191截图名write-approval不能冒充已经出现工具审批。证据1189-restart-once-request.png、1190-restart-once-first-state.png、1191-restart-once-write-approval.png。

截至1191，F07-05按真实取消未闭环从待验证改失败；146项为85通过/18修复后通过/2失败/26阻塞/15待验证，F07-06仍待。本批仅文档与只读源码审查，没有源码编辑、GUI、代码测试、编译或数据库写入。


## 1195–1222 取消契约前置与长 Markdown / 外观回归

1195正常托盘退出，IDEA AX确认03:27:41 exit0；03:28:15仅compile-b39-0328.log编译成功6.616秒，1196 IDEA AX12于03:29实际源码Run加载B39，主70会话。1197新安全输入请求至71会话，1198 15.4秒工具前unreliable；1199同prompt重新发送，1200 17.3秒仍未操作。root只读本轮session6aa04427-9c8f-4001-92e1-dcd8b4106495/run a8d30832-72c1-452b-a02e-9669baa37c87的安全契约摘要：criteria[]、applicable=true/reliable=false及CAPABILITY_NOT_FOUND/MODEL_UNRELIABLE/EMPTY_CRITERIA/PLAN_REPAIR_EXHAUSTED。没有再次打开SecretDialog，不能计B39取消回归通过；F07-05原失败保持。证据1195–1200 PNG，不把文件名approval冒充真实审批。

1201向真实模型请求长Markdown，1202约24.7秒/2763输入865输出正常DELIVERED且主界面完成显示，B38普通文本完成分支已实机回归；真正PARTIAL仍待。1202–1205约300字符代码自动wrap为6行，END6729完整；3列3行表格可读，段落01–25连续滚动到“正文结束6729”，长区未遮输入。没有水平bar是当前自动换行设计，内容完整可读不记缺陷。结合1142–1147思考/observe全文展开、滚动、折叠及B20稳定窄窗修复，F05-06修复后通过。证据1201-markdown-long-line-request.png、1202-markdown-response-state.png、1203-markdown-code-left-edge.png、1204-markdown-table-and-start-paragraphs.png、1205-markdown-middle-paragraphs.png。

1207 Midnight主聊天中文和代码可读，1208–1210设置子窗继承深色；1211/1212 FollowSystem/Compact及1214/1215 FollowSystem/Roomy设置与主文代码无遮挡，1216/1217 Native/SystemMono/Cozy恢复后仍可读。1218 MCP停用卡与1219删除确认深色清楚，1220真正取消删除保留1OFF；1221菜单及1222恢复原Emerald主题与原字体/密度。结合先前九主界面/浅深工作流修复证据，F20-01修复后通过；各实际可用字体/密度和主内容回归使F20-02通过。不要求九主题与所有控件组合，不臆造未安装字体。F12-06仅补取消保留，真正Delete与最终boot仍待。证据1207–1222对应PNG。

D14先读Cline官方持久任务恢复/审批源码及Apache2.0，现有三文件仅恢复同Run的真实pending审批入口，“继续”不视为批准。D15先读LangGraph实际interrupt及MIT，四Framework文件仅补mcp.secure_input.cancel可信交互能力；OBSERVED/input_cancelled只证明取消或未提供有效值且effectNONE/NOT_SENT，不代表Header已设置，不注册MCP.WRITE、不放松审批、不覆盖旧UNKNOWN或升级历史COMPLETED收据。两项均独立静态就绪，尚待下一次IDEA源码加载和原GUI回归，不能按源码将F07-05/-06通过。来源记录cline-approval-recovery-reference.md、langgraph-secure-interaction-reference.md。

截至1222，146项为86通过/20修复后通过/2失败/26阻塞/12待验证；本批只提升F05-06与F20-01为修复后通过、F20-02为通过。135用户本人同意仍只计正常Allow，137–139独立无人60秒超时证据保持。此批仅文档和引用记录更新，没有源码编辑、GUI操作、测试、编译或数据库写入。


1223补足F20-05：当前仅本轮E2E代码显示6729会话（原2消息）按CmdL，实机立即清空并进入“新的对话”/0消息，总数72保持，其它侧栏E2E标题未变化。没有确认弹窗，文件名含dialog不能算存在；背景日志精确删除本轮session90849c69-cb4e-41d2-8c38-50c67600941d。自动替换的新空会话已1224发送双文件恢复请求，不留未知blank。结合旧511空fixture，现有带临时已发消息的清除及隔离验收已补，F20-05通过。1224仍是1196加载B39的运行版本，D14/D15未加载，目前只证明真实请求提交，不作审批恢复或副作用完成证据。证据1223-clear-shortcut-dialog.png（实际无dialog）、1224-recovery-twofiles-original-request.png。

截至1224，146项为87通过/20修复后通过/2失败/26阻塞/11待验证；新增F20-05通过，其它截至1222结论保持。清单状态只来自root实机证据，本代理仅更新文档/引用，未改源码或操作GUI/测试/编译/数据库。

## 补充批次 1224–1264：审批恢复、安全输入取消及 CONTINUE 缺陷

1224旧双文件A/B任务：A真实write started/completed/VERIFIED各1，B只待审批未执行；03:50:53.063旧adapter SHUTDOWN终结原Run852a3256-b58f-487b-a4e3-d6ab5f52fa36为CANCELLED。03:51 IDEA Run自动Make实际加载D14/D15（root核对4个class marker及mtime），1231“继续”因旧Run已终结正常另起fe76ec31-2f9d-4282-8e4e-06433bc3dcf2；1232再次审批A，1233root真正Reject，新Run未写A/B。该旧运行对照不能计修复恢复通过。03:55:54编译Nothing to compile/0.680秒，不能称其首次手动加载D14/D15。证据1224–1233 PNG及dual-file-recovery-original-1224-1231-summary.json。

1234新E2E_恢复修复回归6729的C/D任务于新运行时创建，原Run288b2288-f357-4135-9a2c-79f567343df1：C已实际批准写入，D只待审批；1239/1240正常退出，1241于03:56 IDEA重启。1242同会话明确Continue在0.7秒重显原D同challenge/fingerprint，1243Allow只执行D，C/D跨本轮各write1。D14同Run恢复、保留原checkpoint及不重写已完成副作用的原操作链通过。1245宿主原任务PARTIAL且B38界面诚实未完成/待继续，真正部分完成投影已回归；原合法CONTINUE缺c4_read_D却未task review/repair为B40，原自动四步不能算通过。1246明确只readD后，1248另Run c71a82a5-8dc3-46f5-b311-088e90dda15f真实VERIFIED_COMPLETE/CLAIM_DONE，D两次只读均5字符、D6729证明一致，无新write；不是普通DELIVERED，不升级原四步。F07-06仍待B40新真实自动闭环。证据1234–1248 PNG及dual-file-recovery-final-1248-summary.json。

1249/1250首安全输入回归审批因root观察较慢而TimeoutDenied，没有打开输入或填值，排除取消证据。有效1251重试→1252正确本轮MCP/X-E2E-Probe工具审批→1253Allow打开本地Header安全输入→1254仅虚构E2E_FAKE_VALUE_6729全DOTS遮罩→1255真正Reject→1256真实VERIFIED_COMPLETE、明确saved=false/未改Header/未重连/未retry，无UNKNOWN。1257随后同会话普通中文，1258真实6.3秒回复“取消后仍可继续对话6729”，B38普通DELIVERED维持正常。root限定只读扫描data/logs的16个text与data/rollouts的121个jsonl，虚构值matches0；此扫描只支持本虚构值在实际范围未泄露，不泛化所有凭据。1260重开本轮MCP编辑器Headers0/Enable OFF，取消确未保存。B39+D15原取消、遮罩与继续对话路径修复后通过，F07-05修复后通过；交互取消证据不代表任何Header设置或WRITE成功。证据1251–1260 PNG。

1261仅本轮E2E_MCP补充6729名称明确删除确认，1262真正删除后全部/运行/已停止均0；该精确临时MCP删除已完成，F12-06尚待最终IDEA重启0，不提前通过。B40源码仅Gateway内部CONTINUE分支及private overload，原D13/流式/taskRepairFeedback等历史差异不计本次；作者、独立及root静态审查通过，保留原有严格证据、审批、预算、最多2repair、完整journal及WRITE/UNKNOWN屏障。1263托盘菜单、1264正常退出04:08:50.171且IDEA exit0；目前root只报告compile-b40-0409.log编译进程exit0，BUILD SUCCESS细节和后续IDEA加载/GUI仍待补，不把编译当功能通过证据。

截至1264，146项为87通过/21修复后通过/1失败/26阻塞/11待验证；本批只将F07-05失败改修复后通过，F07-06与F12-06仍待，F21-02仍失败。135用户本人同意只计正常Allow，137–139独立无人60秒超时证据保持。本次仅更新两份文档，未编辑源码、操作GUI、运行代码测试、编译或写数据库。

## 补充批次 1265–1277：MCP 清理持久化与恢复回归排除

compile-b40-0409.log于2026-10-05 04:09:37+08显示BUILD_SUCCESS、6.880秒、1485源码；1265于04:10从IDEA源码启动已加载B40，不填写未精确观察的启动秒数。1266 CmdM重开MCP中心，卡片0/全部0/需要处理0/运行0/已停止0，本轮删除后重启无复活。结合既有B37明确错误/真实日志/Retry、复制与删除取消保留及1261/1262确认删除，F12-06修复后通过。编译成功仅证明可加载，不充当B40功能验收。证据1265-b40-idea-source-boot.png、1266-mcp-cleanup-zero-after-boot.png。

1267–1273 E/F轮（Run97422c7d-9a79-44c7-b0e5-d4bb5e775793）E实际write1/VERIFIED，F在04:13:28.564待审批，60.186秒后04:14:28.750 TimeoutDenied/Fwrite0；1271退出菜单截图时已经取消且无审批。1272正常退出04:14:39.835/exit0，1273源码重启仅复现已取消，不构成D14待审批恢复或B40执行修复失败。1274–1277 G/H轮（Run888caf19-00db-4be7-91ba-efbfeb922f7d）1275出现G审批，1276root原生点击Allow位置时被无关Apple新设备账户系统弹窗先覆盖，未批准G；1277在60.077秒TimeoutDenied，G/Hwrite0。root当时未触碰账户按钮并请求用户自行处理系统弹窗；该等待已于用户06:29继续、1278桌面无账户窗/FollowUpUI进程不存在时解除；这两轮分别因操作时限和外部遮挡排除，不记Appbug、失败或通过。F07-06/B40保留待有效新闭环。证据1267–1277 PNG；1276文件名approved不能代替实际批准。

安全诊断参考[secure-cancel-and-interrupted-recovery-1251-1277-summary.json](../target/e2e-20261004/secure-cancel-and-interrupted-recovery-1251-1277-summary.json)仅含本轮作用域、时间、计数与typed取消元数据，辅助定位而非GUI验收：1251取消工具1次、7字段/saved=false/reconnected=false/retryAllowed=false、receipt42为OBSERVED/input_cancelled/NOT_SENT/effectNONE，无后续业务再调用。1258宿主结果DELIVERED、业务工具调用0，已有宿主fixed/memory.persona上下文读取1单列，不称绝对0工具。截图1251–1260仍为取消回归主证据。

截至1277，146项为87通过/22修复后通过/1失败/26阻塞/10待验证；本批只将F12-06待验证改修复后通过。最终临时资源清理及整体退出/持久化收尾仍待，原7保护会话未处理；135人工同意与137–139无人超时证据继续分别保留。本次仅更新两份文档，未改源码、操作GUI、运行测试、编译或写数据库。

## 补充批次 1278–1306：解除系统遮挡、K/L 恢复闭环及新桌面请求

用户06:29继续后，root确认FollowUpUI进程不存在，1278实际桌面无账户弹窗；此前系统遮挡等待仅是历史，未执行账户操作。1280新会话77的send.applescript clipboard record转换失败，尚未发送；1281改原生Unicode输入，1282实际发送I/J，1283正确I审批/1284真实Allow，1285 J待审批。托盘位置移至1032,2，旧1000位置及AS点击均未打开菜单，1287在新1044位置真正菜单出现时运行已97.7秒取消；安全摘要显示J审批60.081秒TimeoutDenied，I实际write1/VERIFIED5字符、J批准0/write0。该操作时限轮排除D14/B40失败或通过。证据1278-returned-ui.png及1280–1287 PNG；[dual-file-ij-timeout-excluded-1280-1287-summary.json](../target/e2e-20261004/dual-file-ij-timeout-excluded-1280-1287-summary.json)只辅助定位。

1288原意新建没有生效，但同会话真实发送新的K/L请求；1289当步model参数仍为旧I/I6729，1290 root在25.498秒内真正Reject，当前Run文件工具0。安全只读诊断确认当前可靠冻结契约与唯一originalTask均正确K/L，审批内容对应当前model原始工具参数，排除Coordinator复用旧challenge或宿主改写参数；历史上下文影响只是可能性，未证明因果。该轮没有执行或完成新写入，不提升恢复结论。证据1288–1290 PNG及[kl-request-old-i-model-args-1288-1290-diagnosis.json](../target/e2e-20261004/kl-request-old-i-model-args-1288-1290-diagnosis.json)，不展示原私有会话或raw provider输出。

1291真正独立新会话78/0消息，1292提交K/L请求，1293正确K审批/1294真正Allow；1295 L待审批，1296实际打开托盘菜单同时仍pending，1297正常退出，IDEA06:36:44.358 exit0。1298初次CUA Run未触发、无Java，不当启动；随后activate IDEA并实际Run12/Stop272，1299主78/原K/L/1消息。1300明确Continue立即重开L审批，1301确认原路径与L6729正确，1302真实Allow；1303界面L5B已验证且read处理中。尚未取得最终回复、完整四步收据与终态，F07-06/B40保留待验证。证据1291–1303 PNG；截至1303时146项计数不变，最终临时数据清理仍待。本次仅更新两份文档，未编辑源码、操作GUI、运行测试、编译或写数据库。

1304实机终态85.7秒、右侧处理完成/本轮处理已完成，原请求/Continue/助手3消息、主78会话，正文K/L各5B各写一次且readback K6729/L6729一致。[dual-file-kl-independent-recovery-final-1291-1304-summary.json](../target/e2e-20261004/dual-file-kl-independent-recovery-final-1291-1304-summary.json)补核同独立session34b11bda-cd54-4a44-972a-7540dd5be2ad/原Runa8cafc9e-7bca-4305-a225-f6db188e436c：shutdown64为KERNEL_SHUTDOWN暂停，65重显原L同fingerprint/完整参数SHA，66真实批准后68才L写入，K/L各write1/read1；四真实同Run收据均5字符及SHA匹配，冻结4criteria/4refs/0unmet，214/215 VERIFIED_COMPLETE/CLAIM_DONE。F07-06待审批退出→IDEA重启→同Run恢复→不重复已完成K写入→自动两写两读原GUI闭环修复后通过，没有人工新Run补读。该轮CONTINUE decision0/taskrepair0，protocolrepair1仅MODEL_DECISION_MISSING，B40具体CONTINUE分支未直接回归，不能将全链恢复通过写成B40原路径PASS。证据1304-kl-final-status.png及1291–1303 PNG。

1305新独立会话79/0消息，1306发送原完整六步仅Calculator只读请求，当前运行活跃，root不操作任何manager直至终态。尚未取得该轮完整结果，F21-02已有失败保持；B40分支是否触发亦须实际事件确认。证据1305-fresh-desktop-chain-session.png、1306-desktop-six-step-request.png。

截至1306，146项为87通过/23修复后通过/1失败/26阻塞/9待验证；本批仅提升F07-06为修复后通过。清单状态与B40分支覆盖分别记录，最终临时资源清理与整体退出/持久化收尾仍待。本代理仅更新两份文档和读取本轮安全摘要，未改源码、操作GUI、运行测试、编译或写数据库。

## 补充批次 1307–1320：六步桌面终态与工作流临时定义清理

1307–1310新独立Calculator六步只读轮已有真实终态：1310实机200.2秒/受阻待处理，Send恢复，正文明确仍缺截图。[desktop-full-chain-1306-terminal-safe-diagnosis.json](../target/e2e-20261004/desktop-full-chain-1306-terminal-safe-diagnosis.json)辅助核对同Run28a93a60-6fbd-4bbb-8a98-de629bd8b494：严格新链107<117<296<320<335仅满足apps、launch、targets、新open(control=false)与新observe；旧snapshot167早于targets296，之后没有新snapshot，不能放松顺序借用旧图。最终provider353仅observe/open/control，合法BLOCKED377→宿主BLOCKED390→completed391且唯一缺desktop.snapshot。CONTINUE0/taskrepair0，B40具体分支未直接触发；D13可信task-repair门禁在此没有来源事件。普通辅助规划丢失原已授权必要schema为D16，三内部文件经GitHub-first独立适配及静态审查就绪，尚待加载与真实GUI原路径回归，F21-02仍失败。证据1307-desktop-chain-progress.png、1308-desktop-chain-ordered-repair.png、1309-desktop-chain-main-progress.png、1310-desktop-chain-terminal-check.png。

1313–1315最新重启后，本轮E2E_人工输入流程published、节点及2条legacy运行历史保持。1316删除确认、1317实际Cancel仍保留3定义；1319按正确本轮名称确认删除，1320实际只余2系统内置且删除禁用。F15-07定义/历史持久化与F15-09本轮精确删除相关回归补充完成，状态维持已有通过/修复后通过，不把该定义清理泛化全部数据清理。证据1313-workflow-persisted-list.png、1314-published-workflow-persisted-detail.png、1315-published-workflow-persisted-history.png、1316-owned-published-workflow-delete-confirm.png、1317-workflow-delete-cancel-retained.png、1319-workflow-owned-delete-confirm.png、1320-workflow-owned-deleted.png。

截至1320，146项状态仍为87通过/23修复后通过/1失败/26阻塞/9待验证。本批只补已有项目的终态及清理证据，D16源码审查不计功能通过。最终会话、托管任务等本轮临时资源清理及整体持久化/退出收尾仍待；未操作原7保护会话。此代理只更新两份文档和引用记录，未操作GUI、编辑源码、运行测试、编译或写数据库。

## 补充批次 1322–1334：五个本轮托管任务删除与 D16 编译

1322只列5个本轮托管任务，root逐项先选中并核对实际状态，然后点击Delete：E2E_B13B14闭环6729待人工、D08可信验收completed、B12已取消、托管文本failed、D03编排未核验。每项直接消失，没有确认弹窗；1324文件名含confirm不作为弹窗证据。1324待人工删除后共4/待人工0，1326共3、1328共2、1330共1，1332实际共0/运行0/待人工0。F16-07通过，只覆盖这5个本轮任务的界面删除与无残留运行状态。证据1322-managed-owned-cleanup-list.png、1323-managed-owned-needs-human.png、1324-managed-needs-human-delete-confirm.png、1325-managed-completed-owned-selected.png、1326-managed-completed-owned-deleted.png、1327-managed-cancelled-owned-selected.png、1328-managed-cancelled-owned-deleted.png、1329-managed-failed-owned-selected.png、1330-managed-failed-owned-deleted.png、1331-managed-unverified-owned-selected.png、1332-managed-cleanup-zero.png。

1333托盘菜单、1334正常退出，root观察IDEA07:02:57.566资源清理完成/exit0。compile-d16-0703.log于07:03:24 BUILD SUCCESS、7.047秒，仅编译且没有代码测试；root已在IDEA触发源码Run，实际boot及新桌面六步仍待。编译不作为D16或F21-02通过证据，原桌面失败与B40具体分支未直接覆盖保持。证据1333-d16-normal-exit-menu.png、1334-d16-normal-exit.png及root编译结果。

截至1334，146项为88通过/23修复后通过/1失败/26阻塞/8待验证，本批只提升F16-07。最终其它临时资源清理及整体重启/持久化收尾仍待；本代理仅更新两份文档，未操作GUI、编辑源码、运行测试、编译或写数据库。

## 补充批次 1335–1352：D16 实际回归与站点持久化、删除

1335约07:04 IDEA实际源码boot已加载D16，托管删除级联后主会话79→58；1336新独立59/0消息，1337输入、1338于07:05实际提交原六步请求。1339–1342运行与Calculator只读预览294不作为通过；1343终态3m24秒因累计输入安全预算251830/250000暂停，Send恢复，1344 root实际Stop compact预览。[desktop-d16-six-step-1338-1343-final-safe-diagnosis.json](../target/e2e-20261004/desktop-d16-six-step-1338-1343-final-safe-diagnosis.json)辅助定位同Run ed73a4c6-dffe-47b0-a987-c2bb153af0b5：D16正常targets只读schema保留已生效，但171 CLAIM_DONE→182严格PARTIAL缺新open/observe/snapshot→183 repair1；目录9次、成功activation7次，provider201/217/233/249/265/273/289虽已有相应真实callback仍反复受REQUIRED目录提示。open296终于补成，observe309被预算阻止，最终严格40<50<126<296只4项，旧snapshot不得借用。CONTINUE0，不归因B40分支。最窄补修仅复用当前合法已激活接口解除冗余目录要求，不直接注入非只读工具、放权或加预算，两内部文件增量已独立只读静态审查就绪，尚待加载与新GUI回归，F21-02仍失败。证据1335–1344对应PNG。

1348 root实际在重启后的本轮假站点编辑器核完整URL/用户名/备注保持及密码MASKED，补齐F23-01该类字段持久化。1349正确本轮站点删除确认真正Reject，1350仍保留1项；1351同目标Allow后1352实际列表0，站点清理完成。Edit/重启字段保持/删除取消及确认子路径通过；.invalid站点无真实已登录session、Reset仍禁用，不能将该按钮状态算会话重置取消，F19-02整项改为缺登录依赖阻塞。F23-01仍待重启后已发图片打开及人格恢复值核对，F23-05仍待其他临时资源与最终收尾。证据1348-site-full-fields-after-reboot.png、1349-site-owned-delete-confirm.png、1350-site-delete-rejected-retained.png、1351-site-owned-delete-reconfirm.png、1352-site-owned-deleted-zero.png。

截至1352，146项为88通过/23修复后通过/1失败/27阻塞/7待验证，本批只将F19-02待验证改依赖阻塞。1353尚未取得智能体页观察，不记录其结果。此代理仅更新两份文档并只读本轮安全摘要，未操作GUI、编辑源码、运行测试、编译或写数据库。

## 补充批次 1353–1360：补充智能体清理与人格恢复上部核对

1353/1354 root实际重启后选中本轮E2E_专家补充6729，OFF及name/tool/最大迭代1/description保持；1355准确本轮目标删除确认，1356真正Allow后自定义0、内置Assistant绿色启用保留。补充智能体清理完成，复用此前AI生成prompt1091/1092重启持久化证据，不将仅当前基础字段核对扩为新prompt编辑。证据1353-agent-owned-cleanup-list.png、1354-agent-owned-disabled-selected.png、1355-agent-owned-delete-confirm.png、1356-agent-owned-deleted-builtin-retained.png。

1359 Personal事实实际7条，不沿用旧6；具体来源仍待本轮会话删除后root逐项核对、最后经GUI删除，不清原workspace人工1。1360重启后人格实际#JavaClawAgent、简洁直接selected、偏好列表空，禁忌目前只见空input；root正在向下补核完整禁忌，1361工具尚未返回，不能提前通过。F23-01仍需完整人格下部与已发送图片的重启打开结果。证据1357–1360 PNG。本代理只从已有安全scope metadata及32–41本轮附件截图定位原图片会话127a71b4-2239-4d74-878d-b62465c220a4/13:16/当时E2E_停止生成入口，提供给root实际操作；定位本身不作图片持久化通过。

截至1360，146项状态不变：88通过/23修复后通过/1失败/27阻塞/7待验证。D16目录补修静态就绪仍待加载及真实回归，本轮无新状态提升。此代理只更新两份文档与读取既有安全元数据/本轮截图文字，不操作GUI、编辑源码、运行测试、编译或写数据库。

## 补充批次 1362–1367：完整人格恢复、附件持久化失败与原审核模式恢复

1362 root实际核完人格整页：#JavaClawAgent/简洁直接保持，偏好与禁忌列表均空，原值重启持久化补齐。1363搜索E2E_停止生成唯一匹配，1364选精确13:16的本轮3消息会话，1365稳定复查仍只有13:24附件请求文字，图片/附件卡/缩略图及viewer入口全无；全部3消息可见，IDEA也确认加载同session的3消息，排除滚动遗漏或等待加载。对照32/35/36/38原图片界面证据，B41确认，F23-01整项失败；F05原重启前图片操作验收保持。证据[1362人格完整恢复](../target/e2e-20261004/evidence/1362-persona-empty-restored-lower.png)、[1364重启后原图片会话](../target/e2e-20261004/evidence/1364-image-owned-session-after-reboot.png)、[1365稳定无预览](../target/e2e-20261004/evidence/1365-image-persistence-no-preview.png)。

B41五文件最小兼容修复已通过独立静态审查：独立保存上传attachmentPaths，不混用inline imagePaths；旧构造器/null兼容、13列参数及幂等列迁移一致。恢复附件展示先按项目路径策略检查，再读取，缺失/不可读仅显示文件名且不改存储引用；旧图片仅依据同scope真实journal及唯一非空USER文本、原单调匹配、完整日期同一分钟补齐，重复输入或跨分钟保守不补。没有可信旧文档引用则不编造。仍须IDEA加载后重新打开旧图，以及实际新图片/文件发送后正常重启验证；D16目录补修也仅静态就绪。源码审查不提升任何GUI状态。

1366实际审核菜单显示三模式，1367恢复原智能审核，当前无运行；重启确认与未覆盖保存失败反馈仍待，F07-01保持待验证。Personal事实7条的逐项归属及其余临时资源清理仍待，原workspace人工1保留。证据[1366审核模式菜单](../target/e2e-20261004/evidence/1366-review-mode-menu.png)、[1367原智能审核恢复](../target/e2e-20261004/evidence/1367-review-original-smart-restored.png)。

截至1367，146项为88通过/23修复后通过/2失败/27阻塞/6待验证，本批仅F23-01待验证改失败。两失败为F21-02与F23-01；新修复均待实际加载及GUI回归。本代理只读审查源码并更新两份文档，没有编辑源码、操作GUI、运行测试、编译或写数据库。

## 补充批次 1368–1389：B41加载、旧图恢复与新两附件实际发送

1368托盘菜单、1369正常退出，root观察IDEA07:35:41.537 exit0；compile-b41-d16-0737.log BUILD SUCCESS6.968秒，未运行测试。1370于07:40从IDEA源码boot已加载B41及D16目录补修。1371搜索原本轮图片会话，1372加载瞬时旧画面不作失败；1373稳定同session127a71b4-2239-4d74-878d-b62465c220a4的13:24图片已恢复，B41可信同分钟旧journal补齐路径生效。1374点击内联图没有新viewer，文件名含viewer不表示打开成功。证据[1370 IDEA源码启动](../target/e2e-20261004/evidence/1370-b41-idea-source-start.png)、[1373旧图稳定恢复](../target/e2e-20261004/evidence/1373-b41-original-image-stable.png)、[1374内联图点击结果](../target/e2e-20261004/evidence/1374-b41-old-image-viewer.png)。

1375新本轮会话60/0消息，1376–1387经原生系统选择器实际选fixture E2E_image.png和E2E_note.txt，1388两附件卡可见，1389于07:46真实发送且模型运行中。两附件实际终态、已发图片/文件展示及完整正常退出重启仍待，F23-01保持失败至完整附件路径回归；不将旧图部分恢复、源码或编译当整项通过。证据[1388两附件输入](../target/e2e-20261004/evidence/1388-b41-two-attachments-user-input.png)、[1389实际发送](../target/e2e-20261004/evidence/1389-b41-two-attachments-send.png)及1376–1387 PNG。

原智能审核在1370/1373/1389重启后保持，补齐恢复保存子路径。只读源码确认ChatModePresenter.renderReviewMode以按钮Tooltip显示名称和说明，可在无运行时逐模式选择后hover实际验证；保存失败没有正常GUI故障入口，AgentConfigToolReviewSettings先改内存再异步单键保存，SqlPropertyStore保存异常仅日志/false且未回传UI，不能据源码声称失败反馈通过，也不破坏DB或权限制造故障。F07-01仍待真实Tooltip及该子条件说明；此为静态定位，未虚构界面失败。

截至1389，146项仍为88通过/23修复后通过/2失败/27阻塞/6待验证。新附件运行中不提升状态，D16补修仍待真实六步回归。本代理仅更新两份文档和只读原实现，没有操作GUI、编辑源码、运行测试、编译或写数据库。

## 补充批次 1390–1403：图片及文档持久化完整回归

1390真实模型10.9秒、4274输入/227输出，回复正确E2E_ATTACHMENT_6729及蓝紫图片描述。1396正常托盘退出、1397 root观察IDEA07:52:58.898资源清理完成/exit0，1398于07:54从IDEA源码重启，同新会话2消息、图片及E2E_note.txt文档标签完整保持。1399单击图片没有viewer，不计；1400双击真正打开E2E_image.png。1401关闭viewer后点击TXT仅保留标签，没有文档预览，不声称docviewer。1402原session127a71b4-2239-4d74-878d-b62465c220a4的3消息在第二次重启后图仍保留，1403双击原图viewer真正通过。B41可信旧图补齐、新上传图片/文件保存及正常重启展示闭环已实际回归，结合1348站点、1360/1362完整人格及其他各类已保存数据的既有重启证据，F23-01修复后通过。证据[1390附件回复](../target/e2e-20261004/evidence/1390-b41-attachment-answer.png)、[1398新附件重启保留](../target/e2e-20261004/evidence/1398-b41-new-attachments-after-restart.png)、[1400新图viewer](../target/e2e-20261004/evidence/1400-b41-double-click-image-preview.png)、[1401文档标签](../target/e2e-20261004/evidence/1401-b41-document-tag-after-restart.png)、[1402旧图再次重启](../target/e2e-20261004/evidence/1402-b41-legacy-image-second-restart.png)、[1403旧图viewer](../target/e2e-20261004/evidence/1403-b41-legacy-image-viewer.png)。

1391菜单关闭后未显示Tooltip，排除；1392智能审核实际Tooltip“按风险等级智能判断是否需要确认”，1393三模式菜单，1394手动审核实际Tooltip“所有操作都需要用户确认”，1395恢复原智能审核，1398重启保持。全自动仅菜单可见，未启用或降低审批。名称、两实际模式说明及选择/恢复已实测；保存失败没有正常GUI可安全触发方式，不修改DB/权限制造异常，异步保存错误仅日志的实现分析不能代替界面故障证据。因此F07-01剩余失败反馈子条件记阻塞，不虚构反馈通过或实际失败。证据1392-smart-review-description.png、1393-review-mode-menu.png、1394-manual-review-description.png、1395-original-smart-restored.png。

截至1403，146项为88通过/24修复后通过/1失败/28阻塞/5待验证，本批F23-01失败改修复后通过、F07-01待验证改阻塞。唯一失败F21-02；待验证F21-04、F21-05、F23-04、F23-05、F23-06，实际全量清理/最终退出仍待。此代理只更新两份文档，没有操作GUI、编辑源码、运行测试、编译或写数据库。

## 补充批次 1404–1413：六条有序回执完成，但终态协议失败

1404新独立会话61/0消息，1405输入原六步fixture，1406于07:57真实发送；1407Calculator294只读预览，1408最小化预览时Codex自身浮前，1409root恢复Java前景。1410实机FAIL、5m45、178406输入/7948输出，完整反馈为PROTOCOL_ERROR/MODEL_DECISION_MISSING及schema无效。1411点compact预览Close真正Stop，预览消失且主反馈完整。证据[1406实际发送](../target/e2e-20261004/evidence/1406-d16-supplement-six-step-send.png)、[1410终态](../target/e2e-20261004/evidence/1410-d16-six-step-terminal-observation.png)、[1411预览停止及反馈](../target/e2e-20261004/evidence/1411-d16-terminal-feedback-preview-stop.png)。

[限定安全诊断](../target/e2e-20261004/desktop-d16-activation-1406-1410-final-safe-diagnosis.json)辅助核同session d62c40bc-d466-441d-8296-102e7d9c21c8/Runb9d25b7b-36f3-412f-8072-f71ff4538407：严格选中40apps→57launch→109targets→135新open→150新observe→182新snapshot，六criteria/0unmet；D16真实snapshot schema保留并在182取得回执。模型seq273的CLAIM_DONE增加decisionEvidenceNote，seq425增加decisionNote，均违反终态schema禁止额外字段；276/428判无效，278一次protocol repair仍未得到有效决策，430协议违规/432暂停，最终UNVERIFIED/MODEL_COMPLETION_NOT_CLAIMED。宿主拒绝符合契约，不放宽schema或根据六回执自行强救完成；六工具链证据不等于运行验收通过。exact activation repair与B40 CONTINUE分支未触发，不能误称已覆盖。

1412同scope用户仅要求汇总已有结果，不重复业务调用；1413于08:07真实发送继续，当前运行终态尚未取得。F21-02保持失败，其他状态不变；最终资源清理及退出也不能在活跃时提前通过。证据[1412最终化输入](../target/e2e-20261004/evidence/1412-d16-same-run-finalization-input.png)、[1413真实继续](../target/e2e-20261004/evidence/1413-d16-same-scope-continue-send.png)。

截至1413，146项仍为88通过/24修复后通过/1失败/28阻塞/5待验证，本批无状态提升。本代理只更新两份文档，没有操作GUI、编辑源码、运行测试、编译或写数据库。

## 补充批次 1414–1416：继续门禁暂停与空会话计量scope错误

1414同scope仅final继续实际45.4秒、20425输入/2185输出后再次暂停，错误指出持久provider prompt已redacted，需要reconcile后才可replay。此安全门禁不绕过，未取得有效终态，F21-02保持失败；继续暂停与1410额外字段协议拒绝分别记录。证据[1414继续结果](../target/e2e-20261004/evidence/1414-d16-recovery-current-message.png)。

1415实际New创建08:09新62/0消息，但header ctx291.9k/200k与footer会话292K继承上轮；1416稳定空welcome/right0、无操作仍同数，确认非加载瞬时。B42只读根因是当前聊天UI使用workspace累计TokenTracker，尚未建立选中聊天scope的显示投影。拟最窄包内UI修复按真实streamingSession归属计量，新空会话0，背景用量不加当前新会话，日/月真实累计不清；源码尚未修改，需root确定设计后再实施并原路径回归。原F04-06的521手动reset/日月保持已通过，限定旧运行时统计范围，不能据此称新空聊天scope正确。证据[1415新建空会话](../target/e2e-20261004/evidence/1415-b40-two-round-read-session.png)、[1416稳定继承旧累计](../target/e2e-20261004/evidence/1416-b40-empty-session-stable-context.png)。

截至1416，146项仍为88通过/24修复后通过/1失败/28阻塞/5待验证；新增B42待修记录，没有把源码设计当GUI通过。本代理只读诊断并更新两份文档，未编辑源码、操作GUI、运行测试、编译或写数据库。

## 补充批次 1417–1422：CONTINUE实际复核、B44待修与本轮文件清理

1417输入自然两轮K/L只读请求，1418实际发送；1419 root实机终态44.1秒、19863输入1391输出、TASK_UNVERIFIED。[限定安全JSON](../target/e2e-20261004/two-round-read-1418-1419-continue-safe-diagnosis.json)辅助核scope3991e63e-a983-4790-ae33-0f5211b6bc96/Runada8c2d8-551e-4dd5-9090-221bde3d6cfa：早L94在K前，code_read K42 UNKNOWN；repair125后宿主sys_file_read K136真实OBSERVED、5字符SHA。合法CONTINUE146→review148，严格选择K/仍缺其后L，NO_PROGRESS150/PAUSED151源于新B44无条件排除fileRead进展。B40具体CONTINUE分支现在已实际覆盖，但整轮没有验收通过；B44最窄修已写/最终独立静态通过/未加载，F21-02保持失败。root已将1419真实终态截图补入正确证据目录，诊断不能代替界面观察。证据[1418真正发送](../target/e2e-20261004/evidence/1418-b40-two-round-real-send.png)、[1419实际失败终态](../target/e2e-20261004/evidence/1419-two-read-current.png)。B44后续收口仅把当前严格选中的可信fileRead，与最新pre-repair同Run/framework.springai/schema3 review实际选中refs中的同target/digest/字符数比较；无该review则保守比较全部历史，2repair上限和仅合法CLAIM_DONE完成不改，二次独立静态审查通过，真实回归仍待。

B42三UI文件、D17详细安全schema反馈与B43两静态说明修复已作者/独立/root相关静态审查就绪，未编译加载或GUI回归，不提升状态。[1414安全诊断](../target/e2e-20261004/desktop-d16-activation-1412-1414-redacted-continuation-safe-diagnosis.json)说明原静态说明误触凭据脱敏后安全replay门禁拒绝，旧opaque prompt不改写；D17[GitHub主参考与许可记录](../target/e2e-20261004/github-references/structured-control-validation-feedback-reference.md)已先读原实现再独立适配，具体问题反馈仍保持严格schema及重试预算。

1420截图实际仍Java主窗，不因文件名计空目录。root原生Finder精确选择project内5个本轮临时文件→CmdBackspace，1421 actual Finder project空0；另根目录精确A/C/D/E/I五个本轮文件→CmdBackspace，1422实际根仅K/L保留。共10个本轮文件移入废纸篓，未清废纸篓；K/L回归fixture、fixtures/backup/screens/源码全部保留，未删已有用户数据。会话/事实及最终整体清理退出仍待，F23-05不提前通过。证据[1421 project空目录](../target/e2e-20261004/evidence/1421-five-owned-files-trash-empty-project-visible.png)、[1422根K/L保留](../target/e2e-20261004/evidence/1422-owned-resume-files-trash-kl-retained.png)。

截至1422，146项仍为88通过/24修复后通过/1失败/28阻塞/5待验证，本批没有状态提升。本次文档更新未操作GUI、编译、代码测试或数据库；B42此前授权源码实现与静态审查单独记录，均待真正GUI回归。

## 补充批次 1423–1438：会话计量初步回归与GEPA本地超时

1423托盘退出菜单、1424仅退出进行中画面；随后root通过IDEA AX确认08:22:15.839资源清理完成/exit0。compile-b42-b44-d17-0823.log实际08:22:41 BUILD SUCCESS6.567秒/1485源码，仅skipTests compile；1425 IDEA于08:23源码boot已加载B42/B44/D17/B43，原scope21.3K。1426New63/0消息ctx0/footer0，B42新会话归零已实测。证据[1425源码加载](../target/e2e-20261004/evidence/1425-b42-b44-d17-idea-source-boot.png)、[1426新空会话0](../target/e2e-20261004/evidence/1426-b42-new-empty-zero-context.png)。

1427/1428于08:24:39发送新K/L两轮read，1429正文K/L完整但仍running、9533usage，不作完成。1431切回实际56.9秒/9533tok FAIL，提示inline model task timed out: gepa.evaluate。[限定安全诊断](../target/e2e-20261004/two-round-read-1428-1431-final-safe-diagnosis.json)核真实K59→L85各5字符typed OBSERVED、合法CLAIM_DONE113/ref2、strict119 VERIFIED_COMPLETE，但GEPA116→117本地30.007秒timeout、run.failed120。B45只针对可证明的请求自身本地截止，ROOT-only typed中性unavailable方案已完成六文件最终静态审查，不吞audit/provider/owner异常；编译加载及原GUI路径仍待，child owner维持旧失败且不猜managed扩时；不能据strict119将run升PASS。该轮CONTINUE/taskrepair/schemaError均0，未直接覆盖B44/D17。证据[1431真正失败终态](../target/e2e-20261004/evidence/1431-b42-switch-back-real-usage.png)。

1432Tooltip今日2.5M（in2.3M/out182K）、月4.9M（in4.5M/out461K）、当前9.5K/0:56；1433点击摘要后header ctx0/footer0，正文2消息保持；1434Tooltip今日/月全部保持、当前0/0:00；1435另一owned旧scope21.3K/21K保持，局部reset未清其它会话显示。1430New空0发生在前run terminal19.026秒后，虽然截图名background，不能作为活跃后台隔离证据。证据[1432重置前](../target/e2e-20261004/evidence/1432-b42-current-usage-before-reset.png)、[1433局部归零](../target/e2e-20261004/evidence/1433-b42-reset-current-only-zero.png)、[1434日/月保持](../target/e2e-20261004/evidence/1434-b42-reset-daily-monthly-unchanged.png)、[1435其它scope保持](../target/e2e-20261004/evidence/1435-b42-other-session-usage-preserved.png)。

1436/1437在原1419 scope真正发送单词“继续”于08:29:45，1438于08:30:03切到已有空会话a03e8b21-3c06-4f78-b88e-66263f9438d3、0消息/创建08:25，header/footer0且globalstream仍active、工具状态18.9秒/11298输入197输出。这是本批真正后台隔离场景，原run终态后空会话持续0仍待；B44原pause恢复也仍运行，不提前通过。证据[1437原暂停继续](../target/e2e-20261004/evidence/1437-b44-original-paused-continue-send.png)、[1438活跃后台空scope0](../target/e2e-20261004/evidence/1438-b42-background-running-empty-scope-zero.png)。

截至1438，146项仍为88通过/24修复后通过/1失败/28阻塞/5待验证，本批无提前状态提升。仅本次文档更新；本代理未操作GUI、编译、代码测试或数据库。

## 补充批次 1439–1445：后台计量完整回归与原文件恢复完成

1439空scope ctx0/footer0，后台1m31、35621输入/882输出、日2.6M；1440空0/后台2m23、50888输入1820输出；1441空0/4m24、78250输入3335输出、日2.7M；1442真正全局处理完成6m13、123349输入6950输出、Send恢复且所选空scope仍0。1443切原1419 scope，4消息、ctx151.6k/footer152K，原21.3K加新增约130.3K归属正确。结合新建0、1432–1435局部reset日/月不变和其它scope保持，B42实际新空→后台增量→终态空0→切回归属完整原路径通过，F04-06改修复后通过。证据[1439后台空0](../target/e2e-20261004/evidence/1439-b42-background-usage-stays-zero.png)、[1442后台终态空0](../target/e2e-20261004/evidence/1442-b42-background-latest-status.png)、[1443切回原scope](../target/e2e-20261004/evidence/1443-b44-original-resume-final-result.png)。

1444实机正文K6729/L6729一致、绿色任务已完成/完成条件已核验，同原Run ada8恢复保持。root限定安全核resumed153→合法CONTINUE787→review789严格ref2/0unmet，但因仍CONTINUE为MODEL_COMPLETION_NOT_CLAIMED→repair2(790)→合法CLAIM_DONE815→823/825 VERIFIED_COMPLETE→completed826。B40与B44原暂停/Continue/严格验收及可信读取进展分支实际通过。该轮有很多重复只读，不能将模型最终描述冒认实际仅两calls/每轮一次；宿主冻结2criterion回执完成与额外次数条件分别记录，性能及模型表述局限保留。D17没有直接违规纠错，不将no-schema-invalid当分支通过；B43新catalog未误脱敏只补部分继续路径，原桌面六步仍待，F21-02继续失败。证据[1444同原恢复完成](../target/e2e-20261004/evidence/1444-b44-original-resume-complete-two-files.png)，最终已实际读取[同Run恢复安全摘要](../target/e2e-20261004/two-round-read-1437-1444-final-safe-diagnosis.json)：恢复K/L各10次、全Run各11次，20/22次实际只读，不能称仅2次。

1445真实退出菜单后root点退出，IDEA AX08:38:17.251 resourceclosed/exit0。compile-b45-0838.log实际08:38:56 BUILD SUCCESS6.796秒/1487源码，仅skipTests compile；实际IDEA加载尚待，B45原own-timeout GUI回归未取得，1431原FAIL事实保留，编译不作为功能通过。

截至1445，146项重新统计为87通过/25修复后通过/1失败/28阻塞/5待验证，本批只将F04-06通过改修复后通过。原桌面失败和其它阻塞/待验证保持，本轮全量清理与最终退出仍待。本代理只更新两份文档，未操作GUI、编辑源码、编译、运行测试或写数据库。

## 补充批次 1446–1449：最新源码启动与GEPA正常路径

已实际读取[1437–1444最终安全摘要](../target/e2e-20261004/two-round-read-1437-1444-final-safe-diagnosis.json)，同ada8原Run153恢复、合法CONTINUE787→review789未宣告完成/严格2refs与0unmet→repair790 attempt2→CLAIM_DONE815→823/825 VERIFIED_COMPLETE→826 completed，严格选择K136/L174。恢复实际20次sys_file_read（K10/L10），全Run22次（各11），started和同invocation receipt均匹配，无write。B40/B44原分支及B42真实后台归属通过维持，不能声称整个过程仅两读、每轮一次或额外次数条件满足；重复只读性能/模型最终表述局限保留，provider证据并未证明宿主read-pin强制导致完成后的重复。

1446于08:40 IDEA实际启动已加载最新B45；1447输入、1448于08:41:23真实发送新K/L两读评分任务，1449实机42.5秒、13402输入1195输出、绿色完成核验与Send恢复。[1448–1449最终安全摘要](../target/e2e-20261004/two-round-gepa-1448-1449-final-safe-diagnosis.json)已实际读取：新Run9f185724-b4ba-4530-95bb-19d9757e74b7，K47/L103各1次真实业务read、各5字符，CLAIM_DONE145→正常GEPA147–151仅2.318秒→assessment152 schema1/model score0.85/needsRevision=false→153/155 VERIFIED_COMPLETE→completed156。无业务write，辅助context调用单列，不冒称所有工具绝对2次。正常GEPA ON交付路径通过，但未触发own-timeout/V2 unavailable，B45超时原路径不升级，1431历史失败仍保留。证据[1446实际源码启动](../target/e2e-20261004/evidence/1446-b45-idea-source-boot.png)、[1448新评分实际发送](../target/e2e-20261004/evidence/1448-b45-original-two-read-send.png)、[1449绿色完成](../target/e2e-20261004/evidence/1449-b45-two-read-current.png)。

1449阶段JSON坏值警告来自context.on_demand.refine_v2失败62，非GEPA；现有fallback允许任务及正常评分完成。控制层76有一次MODEL_DECISION_MISSING协议修复、相关UNKNOWN_CRITERION_ID，并非D17 forbidden-extra-property纠错分支。该轮CONTINUE/taskrepair0，不替代1437–1444的B40/B44实际分支证据。

截至1449，146项仍为87通过/25修复后通过/1失败/28阻塞/5待验证，本批没有新增状态提升。原桌面六步、控制链、清理及最终重启仍由root实机推进；本代理仅维护文档并读取两份限定安全摘要，没有操作GUI、编辑源码、编译、运行代码测试或写数据库。

## 补充批次 1450–1467：桌面接管与输入失败、六步继续运行

1450/1451输入、1452实际发送本轮Calculator安全控制请求，1455预览实际294且接管/停止入口可见；1456 root真正点击前台接管，1457实际Calculator与预览均294。1458真实终态256.7秒/194313tokens、受阻待处理/Send恢复，目标输入579没有完成。1459真正点击Stop，预览消失、主窗恢复、Calculator仍294。F21-04前台接管/该预览停止通过，F21-05安全输入失败；局部接管通过不能升级整条控制任务。证据[1455预览](../target/e2e-20261004/evidence/1455-desktop-control-preparation.png)、[1456接管](../target/e2e-20261004/evidence/1456-desktop-control-front-takeover.png)、[1457实际数字](../target/e2e-20261004/evidence/1457-desktop-control-latest-preview.png)、[1458终态](../target/e2e-20261004/evidence/1458-desktop-control-observation-failure.png)、[1459实际Stop](../target/e2e-20261004/evidence/1459-desktop-control-stop-failed-result.png)。

已实际读取[本轮限定安全摘要](../target/e2e-20261004/desktop-control-1452-1459-final-safe-diagnosis.json)：owned session8c2f3e79-35f2-4077-92f5-ee41216b7a09/Run91220217-c848-4879-806a-aa20106f7a83，click94真实STALE_FRAME/NOT_SENT，provider87有click；其后15次observe因native前台窗口检查失败，严格仅targets44满足、BLOCKED290→completed291。模型正文声称从未提供click与真实工具元数据不符，不据正文判D16漏input。GEPA正常2.277秒，没有B45 own-timeout。B46只统一本来存在的24x24前台候选资格，pointer所有1px以上遮挡仍检查，其他授权/目标/帧/进程条件未放松；独立静态通过、原路径GUI待，不猜2879窗口尺寸或种类。

1460新独立六步输入、1461实际发送后运行中；root限定事件核D17反馈162明确/decision/decision_note [additionalProperties]，随后198模型提交合法四字段CLAIM_DONE，具体违规反馈纠错分支已覆盖；210严格PARTIAL/repair1正补六步，尚无新终态，F21-02保持失败。1467 root观察Personal仍7条，3条habit为本轮测试派生、4条explicit测试prompt，待下半部/逐项核对后实际删除；此时未删除，不提前计最终清理通过。证据[1461新请求](../target/e2e-20261004/evidence/1461-desktop-chain-fresh-sent.png)、[1467清理前事实](../target/e2e-20261004/evidence/1467-personal-facts-before-cleanup.png)。

截至1467，146项实际重计为88通过/25修复后通过/2失败/28阻塞/3待验证。两失败F21-02与F21-05，三待验证F23-04、F23-05、F23-06。本批仅维护两份文档、读取限定安全摘要及独立静态审查，没有操作GUI、编辑源码、编译、运行代码测试或写数据库。

## 补充批次 1468–1475：六步预算终态、B46构建加载

1469实机4m59秒、202165输入7072输出，黄色累计模型输入259590/250000预算停止，主界面失败；1470 root真正Stop compact预览后消失。已实际读取[1461–1469最终限定摘要](../target/e2e-20261004/desktop-full-chain-1461-1469-budget-final-safe-diagnosis.json)：same Run a5bdcca7-146b-42be-ac06-2fa7815efb66严格40apps→50launch→102targets→227新open→242新observe→253新snapshot六条有序可信回执、缺0，但repair210之后没有新的合法CLAIM_DONE，427 UNVERIFIED/MODEL_COMPLETION_NOT_CLAIMED、428预算PAUSED，F21-02失败保留。D17反馈162具体/decision/decision_note additionalProperties→198合法四字段CLAIM真实覆盖纠错分支；没有CONTINUE/B45 own-timeout。正常snapshot pin246确供接口，253之后required读hint0，旧repair可见/可选callback存在不能证明宿主完成后强制重复读，保留实际预算失败但不虚构新强制pin Bug。证据[1469预算终态](../target/e2e-20261004/evidence/1469-desktop-chain-terminal.png)、[1470实际Stop](../target/e2e-20261004/evidence/1470-desktop-chain-preview-stopped.png)。

1468 root已滚完Personal全部7条内容：3habit为读写/E2E测试派生；4explicit为Markdown only、ask_user_clarification only、温暖/幽默二选一、E2E_小林徒步简短回复的测试prompt。此时均未删除，下批必须实际按本轮归属删除并保护原workspace人工事实，F23-05保持待验证。证据[1468全部事实下半部](../target/e2e-20261004/evidence/1468-personal-owned-facts-lower.png)。

1471托盘菜单、1472真正正常退出，root观察IDEA08:59:04.810 resourcesclosed/exit0。[Maven日志](../target/e2e-20261004/compile-b46-0859.log)实际08:59:39 BUILD SUCCESS0.611秒、NothingToCompile；B46为native修复，另root实际xcrun双架构/Werror构建成功，[native输出日志](../target/e2e-20261004/native-build-b46-0859.log)记录target/native/macos/libjavaclaw_desktop.dylib。1473 IDEA09:00真实新Java源码启动、native路径使用target/native。1474/1475新第67会话按原control fixture约09:01:28实际发送，仍运行；F21-05等待原GUI终态，不将源码、构建或启动当通过。证据[1473新源码启动](../target/e2e-20261004/evidence/1473-idea-native-fix-launch.png)、[1474原fixture输入](../target/e2e-20261004/evidence/1474-desktop-control-fixed-input.png)、[1475实际发送](../target/e2e-20261004/evidence/1475-desktop-control-fixed-sent.png)。

截至1475，146项状态仍为88通过/25修复后通过/2失败/28阻塞/3待验证。F21-02/F21-05失败，F23-04/05/06待验证；本批仅更新两份文档及读取限定安全摘要/已有构建日志，未操作GUI、修改源码、编译、运行代码测试或写数据库。

## 补充批次 1476–1484：B46观察修复与Pointer受阻终态

1476工具实际清空使Calculator和预览为0，root未点击Calculator控件；1477 root前台接管、1478–1480 Calculator持续0，观察时预览临时隐藏，目标579未取得。CUA Calculator getApp意外阻塞约7336秒，实际11:14恢复、Codex在前景；1481不作终态截图，不把外部操作工具阻塞计JavaClaw业务耗时或Bug。1482 root稳定实际JavaClaw失败终态8m32秒/201136输入9011输出、黄色累计254742/250000，2消息；实际Stop使预览消失。1483/1484 root手工点击恢复初始294是环境恢复，不是产品输入通过。证据[1480实际0及失败输入](../target/e2e-20261004/evidence/1480-desktop-control-fixed-progress.png)、[1482实际终态/停止](../target/e2e-20261004/evidence/1482-desktop-control-b46-stopped-result.png)、[1484人工恢复原值](../target/e2e-20261004/evidence/1484-calculator-original294-restored.png)。

已实际读取更新后的[1475–1482安全摘要](../target/e2e-20261004/desktop-control-b46-1475-1480-final-safe-diagnosis.json)：同Run282b9759-322a-45f4-aa1b-88a17a493978，7次fresh observe成功、原first-app窗口prepare失败未再出现，B46旧Bug原观察分支修复后通过。背景clear106已SENT且GUI为0，前台click201/241/281/321因foreign CG layer20 blocker均NOT_SENT，另161 stale亦未派发，type/key0；394预算PAUSED/strict仅discover_targets44满足、6缺，整F21-05失败保留。不要采信模型“未提供click”，不要据layer20猜透明共享边框或忽略未知覆盖窗。B47仅补失败元数据诊断，静态通过但尚未构建加载，不改输入准入；Github实际来源与MIT记录见[hammerspoon-systemwide-hit-test-reference.md](../target/e2e-20261004/github-references/hammerspoon-systemwide-hit-test-reference.md)。

截至1484，146项仍为88通过/25修复后通过/2失败/28阻塞/3待验证。B46单分支修复生效不提升整个F21-05，D18候选实时进展提示正在只读源码/GitHub研究，没有实施或GUI通过。本批仅更新文档/读取限定安全摘要/独立静态审B47，未操作GUI、编辑源码、构建、运行测试或写数据库。

## D18源码就绪记录（尚未新增实机结论）

root授权后，D18两个内部Java文件已实施并通过gepa独立静态审查，来源及边界见[实际实现记录](../target/e2e-20261004/github-references/langchain-task-progress-feedback-reference.md)。仅每个fresh provider的最新可信全条件进度投影，不自动完成、不修改历史prompt或replay，不增加工具/权限/repair次数/预算。尚未编译加载或取得原六步GUI结果，146项状态仍为88通过/25修复后通过/2失败/28阻塞/3待验证；原1469预算失败及F21-02失败保留。

## 补充批次1491–1507：事实删除未执行与B47真实诊断

1491/1492实际选择七条本轮Personal事实并核对删除确认，执行即时不可逆删除的电脑动作被自动审批拒绝；root已向用户发出明确授权请求，当前等待，未删除七条事实，不写facts0、不触碰原workspace人工1。确认框显示不代表删除已执行。证据[1491删除确认](../target/e2e-20261004/evidence/1491-personal-facts-delete-confirmation.png)、[1492确认详情](../target/e2e-20261004/evidence/1492-personal-facts-delete-details.png)。

1497/1498 root在IDEA11:28实际启动已构建B47 native，1500独立第68会话于11:29:49.832发送单次Calculator前台AC/观察请求，1502明确授予前台接管。1504真实终态受阻2m59秒/58688输入3225输出；实际两次前台AC click105/145均FAILED/NOT_SENT、无dispatch，Calculator保持294；1505 root实际Stop预览消失。已读取[本轮安全诊断](../target/e2e-20261004/desktop-pointer-b47-1500-safe-diagnosis.json)：同Run f7cde354-0d59-4506-9afc-65ea0de003cd，click105原失败元数据实际公开bundle com.apple.dock、layer20、rect0,0,1512,982、share1、titleEmpty0；AX未采集，不能认透明边框、真实鼠标接收者或忽略Dock。B47诊断已在实机原故障生效；原输入准入不变，F21-05失败保持，未来native方案尚只读研究、未授权实施。JSON现已补final178completed，和root已观察受阻/Stop截图相互印证；请求单次但模型实际两次click，不能将no-retry条件冒认通过。证据[1497 IDEA运行](../target/e2e-20261004/evidence/1497-b47-idea-source-run-click.png)、[1498加载](../target/e2e-20261004/evidence/1498-b47-native-loaded-application.png)、[1500实际请求](../target/e2e-20261004/evidence/1500-b47-foreground-single-action-sent.png)、[1502前台接管](../target/e2e-20261004/evidence/1502-b47-foreground-takeover-granted.png)、[1504受阻终态](../target/e2e-20261004/evidence/1504-b47-single-input-blocked-result.png)、[1505实际Stop/294保持](../target/e2e-20261004/evidence/1505-b47-preview-stopped-294-unchanged.png)。

1506托盘退出菜单、1507正常退出，root观察IDEA11:34:22.453 exit0；compile-d18-1135.log正在进行，尚无本批成功/新加载/GUI结论。D18两文件root已读基线增量认可及gepa独立静态通过，不作为功能通过。146状态保持88通过/25修复后通过/2失败/28阻塞/3待验证，事实与最终清理仍待。证据[1506退出入口](../target/e2e-20261004/evidence/1506-d18-normal-exit-menu.png)、[1507退出0](../target/e2e-20261004/evidence/1507-d18-normal-exit-zero.png)。

## 补充批次1508–1517：D18原完整六步只读闭环完成

1507正常退出后，[compile-d18-1135.log](../target/e2e-20261004/compile-d18-1135.log)实际11:35:03 BUILD SUCCESS6.212秒/1487源码、仅skipTests compile。1508/1509 root实际IDEA11:36源码启动，1510新第69空会话准备原完整六步fixture，1511约11:37:46真实发送；1512/1513只读Preview294/运行中保持。1514正确六步正文已出现但Stop及GEPA仍进行，不当终态；1515真实4m0秒/120463输入4212输出、2消息、Send恢复、本轮完成及绿色六条件，1516完整六criteria和六refs，1517聊天内联真实截图清楚294，与Preview294一致。F21-02完整原路径修复后通过，此前预算/协议失败事实保留。证据[1508源码Run](../target/e2e-20261004/evidence/1508-d18-idea-source-run-click.png)、[1509源码boot](../target/e2e-20261004/evidence/1509-d18-idea-source-application-boot.png)、[1511原请求Send](../target/e2e-20261004/evidence/1511-d18-six-step-readonly-sent.png)、[1515真实终态](../target/e2e-20261004/evidence/1515-d18-snapshot-reply-lower.png)、[1516六条件](../target/e2e-20261004/evidence/1516-d18-six-criteria-completed.png)、[1517实际内联截图](../target/e2e-20261004/evidence/1517-d18-real-snapshot-inline.png)。

已只读定位这一轮owned scope6c018db1-16ce-44f3-9892-4733d2dbee9e/Run01f10d4f-b385-4538-a012-3614e0cf11a2，原请求marker精确匹配、冻结V3可靠适用；[最终安全摘要](../target/e2e-20261004/desktop-d18-six-step-1511-safe-diagnosis.json)已有strict316/318 VERIFIED_COMPLETE及319completed，6选定有序HOST回执42apps→52launch→104targets→149新open→164新observe→261snapshot、0unmet。早open62/observe77没有借作targets之后完成；额外open219/observe234在同桌面session内，实际open3/observe3/snapshot1，不虚构只有6calls。页面桌面session f3704c67-9929-484d-85cc-e09d8d16ab17与观察ba86780e-f57a-4aba-adec-ebc69a94abcf、截图basename desktop-session-d2637664-858e-401f-836d-3438341a2a9c.png来自本轮，仅用于root实际显示核对，不导出其他目标/窗口/私有正文。

D18在fresh provider288真正出现唯一typed宿主尾部进度slot，revision287、required=true、1680字符；完整六criterion IDs及同Run6refs与最终strictreview逐值一致，不替代原CONTROL/manifest、没有taskrepair_requested、未自动生成决策。其后305模型实际提交符合原schema的四字段独立CLAIM_DONE，最终严格门禁通过。D18实际分支修复后通过，仍保持replay/权限/UNKNOWN/预算/repair次数等约束。GEPA308–309及311–312有界尝试分别3.517及2.793秒，315 schema2 unavailable/reason structured_output_invalid后继续严格验收；没有分数、没有own30秒超时，不记正常评分成功或B45 timeout分支通过。GUI真实Token投影与raw usage事件合计分别记录，不拿未去重的事件求和冒充UI或预算台账。

同时B47先前安全摘要已补终态：click105/145两次均FAILED/NOT_SENT/no dispatch，178completed为受阻任务结束；1504/1505实际数字294未变/Stop消失相符。单次请求发生两次模型尝试，不能声称仅一次或no-retry通过；F21-05仍失败。七条事实授权仍pending、未删，最终清理及退出待。146项静态重计为88通过/26修复后通过/1失败/28阻塞/3待验证，本批仅将F21-02从失败升为修复后通过；剩余失败F21-05，待验证F23-04/05/06。未操作GUI、编译、运行代码测试或写数据库。

## 补充批次1521–1528：B48接收窗口诊断生效，控制任务仍失败

1521 root实际IDEA11:46源码启动加载B48，1522新owned会话9b65f846-6cf8-4853-b408-c9e11a85ab93，1523真实发送受限Calculator前台AC任务，Run c4211cd3-32a3-4b3b-a22d-4a766eedd404。1524背景AC使实际Calculator/Preview为0，root没有操作Calculator；1525 root实际授予前台接管。1526/1527属于进行中，不当终态。1528实机11:57显示预算255960/250000停止、Send恢复/2消息、Preview0及最近点击失败；整F21-05失败保持。证据[1521 IDEA运行](../target/e2e-20261004/evidence/1521-b48-idea-source-run-click.png)、[1523实际请求](../target/e2e-20261004/evidence/1523-b48-native-receiver-diagnosis-sent.png)、[1524实际0](../target/e2e-20261004/evidence/1524-b48-control-preview-ready.png)、[1525前台接管](../target/e2e-20261004/evidence/1525-b48-control-foreground-takeover.png)、[1528真实终态](../target/e2e-20261004/evidence/1528-b48-current-user-visible-state.png)。

已实际读取[最终安全摘要](../target/e2e-20261004/desktop-pointer-b48-1523-safe-diagnosis.json)，全Run实际两个click：91背景BACKGROUND_SEMANTIC SENT/UNKNOWN，347前台FOREGROUND_SYNTHETIC FAILED/PLATFORM_FAILURE/NOT_SENT/dispatchAttempted=false。347同一次失败诊断实际得到receiverCollected1/window2098==target2098，原CG阻挡为公开com.apple.dock/PID823/window11/layer20/rect0,0,1512,982/share1/titleEmpty0；方法below0未跳过窗口、当前guard仍拒绝，故只有诊断命中，没有前台AC成功。356 PAUSED/BUDGET_EXHAUSTED对应GUI预算停止；严格仅targets满足，不能以背景AC变0或receiver匹配升级整控制闭环。本轮GEPA未进入；vision的45秒超时属于另一任务，不算B45 own-GEPA30秒分支。

root授权gepa继续B49精准现有Dock签名、fresh unrestricted actualmouse receiver exacttarget与原firstSameApp/PID/bounds等全部守卫的native最小修复；本记录只待其源码独立审、构建加载和原实际路径回归，不提前PASS。B48只读诊断来源/许可/线程及坐标限制见[Apple与GLFW参考记录](../target/e2e-20261004/github-references/apple-pointer-receiver-design.md)。七条本轮Personal事实删除即时用户确认仍pending、未执行，不能记facts0或清理通过。146状态仍88通过/26修复后通过/1失败/28阻塞/3待验证。

## 补充批次1529–1541：B49前台清除子路径生效，整体任务未终态

1529 root实际Stop使B48 Preview消失，1530人工恢复Calculator294只属环境复原，不作产品PASS。1531托盘菜单、1532正常退出，root观察IDEA11:59:29.880资源清理完成/exit0。root构建native-build-b49-1202.log exit0，文件名不作为实际构建时间；1533 IDEA12:01真实源码Run、1534加载后70会话。1535新第71会话准备原full control fixture，1536于12:02:49.734真实发送；1538 root于12:03前台接管，无人工Calculator输入。1540约12:05实际Calculator294→0，root未手工点Calculator，对应本轮真实click106/receipt107 FOREGROUND_SYNTHETIC ACCEPTED/SUCCEEDED/SENT/dispatchAttempted=true、effect仍UNKNOWN；新observe121/receipt122同session/target/gen1新obs数字0(.92)，原Dock误拒清除子路径修复后通过。1541仍active3m23秒/104061输入2256输出/Calc0，完整123+456=579未取得，不提升F21-05。证据[1529预览停止](../target/e2e-20261004/evidence/1529-b48-preview-stopped.png)、[1530环境复原](../target/e2e-20261004/evidence/1530-calculator-original-294-restored.png)、[1532正常退出](../target/e2e-20261004/evidence/1532-b49-normal-application-exit.png)、[1533源码运行](../target/e2e-20261004/evidence/1533-b49-idea-source-launch.png)、[1536原任务Send](../target/e2e-20261004/evidence/1536-b49-original-full-control-sent.png)、[1538前台接管](../target/e2e-20261004/evidence/1538-b49-foreground-takeover.png)、[1540实际清除](../target/e2e-20261004/evidence/1540-b49-fresh-action-progress.png)、[1541仍活跃](../target/e2e-20261004/evidence/1541-b49-live-calculator-state.png)。

已读取限定[本轮安全摘要](../target/e2e-20261004/desktop-control-b49-1536-safe-diagnosis.json)，owned scope1ac13c66-335f-4f1c-a15d-9a762f12e7ba/Run38df05d0-b6fc-458b-9ce4-a4348a6a6f82。这次前台SENT与观察变化是原输入误拒分支实测，不能据callback成功或派发将effect UNKNOWN直接升级verified，更不能冒认整任务完成；完整终态仍由root实际GUI确认。B49只改精确已观察签名与当次真实receiver证明组合，原窗口/PID/像素/TTL/stillReady及未知副作用约束保留，没有全局忽略Dock/layer20。

会话清理准备见[reviewable候选JSON](../target/e2e-20261004/cleanup-session-candidates-1532.json)：原7精确IDs永不删除；本轮已知归属候选与两owned无E2E标题blank分开，a03e8b21…已用于1448，按普通本轮scope而非当前空白处理；未知保持。只读Sidebar源码确认popup仅显示title+前8UUID、完整ID在callback；8位当前候选无碰撞，仍须实际GUI逐条匹配，不能从总数或0消息推归属。已有安全摘要与thread/started元数据是历史来源，不是当前Sidebar存在清单；安装H2 CLI仅SELECT三元数据列、ACCESS_MODE_DATA=r/IFEXISTS试取当前镜像因Java占用DATABASE_LOCKED而未取rows，未写/绕锁/读消息。root下次正常退出后可补当时镜像，最终GUI确认仍必需。七条事实删除即时授权pending、未执行，146计数88/26/1/28/3不变。

## 补充批次1542–1551：控制任务终态与工具发现死端

1542/1543仍活跃，root实际观察Calculator从0到1；1544最终249749/250000预算暂停、Send恢复/2消息，数字回到0。限定[B49最终摘要](../target/e2e-20261004/desktop-control-b49-1536-safe-diagnosis.json)记录六次真实FG SENT（清除×5、数字1×1）、type/key0，412严格PARTIAL仅criterion-0 targets满足，413暂停。单次原误拒清除通过保持，完整123+456=579失败保持。原fixture及最近输入/观察在最新provider中保留，不能把compact猜作全部任务或回执丢失。证据[1543实际数字1](../target/e2e-20261004/evidence/1543-b49-live-calculation-progress.png)、[1544真实终态](../target/e2e-20261004/evidence/1544-b49-current-near-terminal.png)。

只读[精准严格身份诊断](../target/e2e-20261004/desktop-control-b49-strict-target-diagnosis-1544.json)确认初始targets42→open52→observe82→click107顺序成立。冻结英文Calculator与真实中文计算器/applicationId com.apple.calculator之间缺本Run已完整读取且早于回执的applications别名目录，既有matcher按严格身份拒绝其余条件；不硬编码映射、不依SENT或GUI0补认完成。已有能力目录先行或真实canonical ID可严谨建立身份链，但本次未用替代合同/evaluator模拟补认。D19目前只能投影targets met，未实施；历史前置observe与终态laterDesktopAction新鲜性边界另待实际canonical路径核验。

1546/1547 root在新第72会话明确发送type+Enter真实请求，1548 Preview0、1549实际前台接管；1550/1551终态受阻4m9秒/71056输入3760输出、Send/2消息/Calculator0。正文说明当前click/observe工具集缺type/key及catalog，限定[本轮安全摘要](../target/e2e-20261004/desktop-type-key-1547-1551-safe-diagnosis.json)证实十个实际primary provider全程没有这三个callback，三者实际调用均0；实际click94背景、149前台分别SENT。197合法BLOCKED、GEPA正常score0.4、207严格BLOCKED、208结束，不能把run结束或评分当任务通过。混合target Calculator 计算器、apps0仍是独立身份障碍。证据[1547显式请求](../target/e2e-20261004/evidence/1547-real-type-key-request-sent.png)、[1550界面结果](../target/e2e-20261004/evidence/1550-type-key-real-effect-progress.png)、[1551受阻详情](../target/e2e-20261004/evidence/1551-type-key-terminal-details.png)。

D20先实际读取官方LangChain tool selector保留真实恢复接口及LangGraph registry/绑定一致性源码、完整MIT后，root授权一文件最小改动。只把现有NONE目录模式恢复为OPTIONAL，去掉必须所选/activation均空的限制；目录仍受现有policy/预算/slots/schema门禁，不直接恢复输入或自动执行。gepa独立源码审通过，IDEA原路径回归待；F21-05保持失败。七条事实未删除、最终清理/重启待，146状态88/26/1/28/3不变。

## 补充批次1556–1557：D20编译与源码加载

root执行[compile-d20-1227.log](../target/e2e-20261004/compile-d20-1227.log)，12:27:36 BUILD SUCCESS6.264秒/1487源码，仅skipTests compile；native B49注释维护构建成功，没有native准入语义修改。1556实际IDEA12:28源码Run，1557 root实机12:31确认JavaClaw加载72会话。编译和加载不作为D20功能通过；后续新第73会话原type/key fixture回归结果仍待，F21-05失败与146统计88/26/1/28/3保持。证据[1556 IDEA源码运行](../target/e2e-20261004/evidence/1556-d20-idea-source-run.png)及[1557实际加载72会话](../target/e2e-20261004/1557-d20-idea-loaded.png)。截图已真实保存于target/e2e-20261004根目录。

1547–1551安全摘要另明确元数据限制：原请求toolCalls120/input250000/timeout1800、host.tools启用、selectedTools8，末provider190为2业务工具及harness、combined schema2761；owned事件未持久化完整execution-plan policy/authorized map/schema ceiling或provider时实时remainingToolCalls。因此不能仅凭缺少type/key/catalog就断言它们当时已授权且必然fit。D20只恢复选择机会，实际callback仍由当前runtime所有原门禁判断。D19/B50仅只读候选，七条本轮Personal事实action-time确认仍未得到新human回复，旧“继续”不作确认，没有执行删除或清理通过。

## 补充批次1558–1575：D20入口真实回归与整轮严格失败

1557已在target/e2e-20261004根目录真实保存。1558新第73会话，1559于12:32:16发送原type/key fixture，1560–1562观察/前台接管；1565约12:37实际Calculator表达式123+456/result579，root没有输入Calculator，1567超时恢复后仍579。限定[最终安全摘要](../target/e2e-20261004/desktop-type-key-d20-1559-safe-diagnosis.json)同Run570a10c6-558d-48d2-a104-0524690cd03e/session45c3b0a9-de67-41a5-bcf1-8356eaa2a849确认type194/receipt196与key234/receipt236各一次FOREGROUND_SYNTHETIC ACCEPTED/SENT，参数为123+456/Enter；恢复后receipt375真实OBSERVED，verify-result主体精确匹配冻结条件、confidence0.94、session/target/obs/generation/revision/capture六帧字段完整，结果579。click99背景与154前台各一次clear，不称只清一次，也不把SENT/effectUNKNOWN自动认验收。证据[1559原请求](../target/e2e-20261004/1559-d20-original-type-key-sent.png)、[1562接管](../target/e2e-20261004/1562-d20-front-takeover.png)、[1565实际579](../target/e2e-20261004/evidence/1565-d20-current-outcome.png)、[1567恢复后579](../target/e2e-20261004/evidence/1567-d20-after-vision-timeout.png)。

D20原受控入口分支已实触发：provider229真实fresh CONTROL READY/plannerUnavailable=false，非空key+observe旁仍提供framework_tool_catalog和harness、schema3119；后目录实际调用5次（list2/activate3，2成功、1请求错误的其它名称拒绝）。这与仅fallback提供type/key不同，D20正常OPTIONAL入口子路径修复后通过，不从模型正文推断接口存在。辅助planner的失败与mandatory observe路径保留；目录不提供native应用别名证据，不能替代desktop_session_applications。

1570实机12:42终态失败9m43秒/205259输入9766输出、Send恢复/2消息/Calculator579。412预算actual254563/limit250000，413严格PARTIAL仅targets-list满足、8unmet，414PAUSED；apps0、冻结target Calculator与实际计算器/com.apple.calculator没有本Run可信别名，因此实际效果及最终结果证明仍未满足整冻结合同。GEPA正常score0.9不是B45 own30秒分支。F21-05失败保持，源码/目录分支/派发/可见579均不替代整轮验收。证据[1570真实终态](../target/e2e-20261004/evidence/1570-d20-current-safe-state.png)。

1571 AXRaise未枚举preview不证明释放；1572 root人工恢复294只属环境复原。1573激活Java并新建74会话时旧preview仍出现，1574 root真正Stop后才消失；[1574停止证据](../target/e2e-20261004/evidence/1574-d20-preview-stopped.png)是本轮释放依据。1575约12:45实际发送准确canonical appID与显式本Run只读catalog前置的短fixture，仅隔离复现B50候选，仍运行，未改源码。[1575实际发送](../target/e2e-20261004/evidence/1575-canonical-history-request-sent.png)。自然身份preflight仅只读[设计记录](../target/e2e-20261004/github-references/hammerspoon-application-identity-preflight-design.md)，D19/B50也未实施。七条事实无新即时确认、未删；146项88/26/1/28/3保持。


## 补充批次1576–1579：canonical身份已证，顺序与历史控制参数仍失败

限定[最终安全摘要](../target/e2e-20261004/desktop-canonical-history-1575-safe-diagnosis.json)同Run2ecb2412-5303-4bed-8852-ec6a746083ae/session7ed719d8-211f-424f-861b-c9db97ca8504，冻结六条件使用准确com.apple.calculator，本Run应用查询完整唯一Calculator目录与English alias已在42真实OBSERVED，不能再归因为无应用目录。1576实机Calculator0，1577 root前台接管，1578仍处理中；before77精确冻结subject与六帧绑定、confidence0.95、真实294，clear104背景SENT引用同帧且GUI0。实际调用还包含用户未要求的launch，open62早于targets146；249及254严格PARTIAL仅apps+晚targets，不据可见0认整链。证据[1576实际0](../target/e2e-20261004/evidence/1576-canonical-history-control-preview.png)、[1577接管](../target/e2e-20261004/evidence/1577-canonical-history-front-takeover.png)、[1578处理中](../target/e2e-20261004/evidence/1578-canonical-history-current-review.png)。

1579真正终态4m13秒/105935输入3420输出、Send恢复/2消息/Calculator0，消息12:49、截图12:51。252最后provider历史中harness_submit_decision参数是9字符redactor占位而非JSON，255对应400 function.arguments must JSON；此前241合法CLAIM_DONE和249/254实际PARTIAL均保留。[1579真实终态](../target/e2e-20261004/evidence/1579-canonical-history-evidence-state.png)。B50历史view门禁、B51纯目录误入launch、B52控制参数恢复分别记录，避免归为同一根因。B50/B51/B52源码均已独立静态审，尚未加载/原GUI回归，F21-05仍失败。D19及广身份preflight未实施，七条事实未删；146项88/26/1/28/3不变。


## 补充批次1580–1584：正常退出、合批编译与临时文件回收

1580旧canonical本轮预览真正Stop后消失，随后人工恢复Calculator294仅属环境复原，非产品输入验收。1581托盘菜单→1582实际正常退出，root CUA在IDEA AX确认12:57:21.238资源关闭、exit0；1584约13:04源码控制台截图保留该退出结论。B50/B51/B52作者与独立静态审通过，[合批编译日志](../target/e2e-20261004/compile-b50-b51-b52-1303.log)实际13:03:41 BUILD SUCCESS、6.141秒、1487源码，仅skipTests compile。1584已点击IDEA源码Run，但尚未观察新主窗加载，不把编译/点击启动算修复GUI通过。证据[1580停止与环境恢复](../target/e2e-20261004/evidence/1580-canonical-preview-stopped-restored294.png)、[1581退出菜单](../target/e2e-20261004/evidence/1581-b50-b51-b52-tray-exit-menu.png)、[1582退出过程](../target/e2e-20261004/evidence/1582-before-b50-b51-b52-normal-exit.png)、[1584 IDEA源码启动](../target/e2e-20261004/evidence/1584-b50-b51-b52-idea-source-launch.png)。

原生Finder新本轮窗口真实进入项目，精确选择最后两个owned文件：K（UI元素270）CmdBackspace后tree中消失、14items；L（UI元素271）同样移废纸篓后消失、13items。[1583限定目录截图](../target/e2e-20261004/evidence/1583-owned-k-l-trash-cleanup.png)与root逐次操作确认12本轮临时文件均已回收到可恢复废纸篓，未EmptyTrash，未删backup/screens/diagnostics/source。七条Personal事实无新human即时确认，未删除；owned会话等待最终GUI批次，F23-05/06仍待验证。新同canonical第75会话回归尚待加载与实际发送，F21-05保持失败，146项88/26/1/28/3不变。


## 补充批次1585–1602：源码回归仍有顺序缺项，正常退出持久化核对完成

1585 actual IDEA源码boot13:04，主窗74会话；1586新第75空会话、1586a发送前实际13:09，1587约13:10发送完全相同canonical短fixture。1588只读Preview294，1589 root仅前台接管后Calculator0，没有人工输入Calculator。1592仍active6m10/0，1594实际终态失败、Send恢复；限定[本轮安全摘要](../target/e2e-20261004/desktop-canonical-b50-b51-b52-1587-safe-diagnosis.json)为session db46e954-d76b-4ceb-8c7e-b9340b5c7ffd/Run d66863a1-848a-49e2-a240-3407804c98af。证据[1585加载](../target/e2e-20261004/evidence/1585-b50-b51-b52-loaded-ui.png)、[1587同fixture](../target/e2e-20261004/evidence/1587-b50-b51-b52-canonical-history-sent.png)、[1589接管](../target/e2e-20261004/evidence/1589-canonical-repair-front-takeover.png)、[1594终态](../target/e2e-20261004/evidence/1594-canonical-repair-current-terminal-check.png)。

B51原host误路由子路径已改善：普通apps30之后走optional planner34、真实catalog41/42、模型refine49选launch，provider52实际同时提供launch/targets/catalog/harness；CONTROL没有旧SELECT_APPLICATION阶段，且已经包含需要targets及遵循冻结顺序的hint。launch56由模型自己选择，不是普通应用查询自动触发host恢复。targets159仍晚于初始open/before，实际clear两次。247真实PARTIAL选33/159/185/200、缺open/before，248repair1；349最终PARTIAL选33/159/281/296、缺clear/final，350预算reservation248214/250000暂停。源码修复子路径及可见0不能提升整体F21-05。

B50具体历史view豁免未直接覆盖：新before296后没有实际action，仅普通当前view；旧before84先被晚targets严格顺序排除。B52 fresh provider250中的历史harness JSON四字段合法，原236也为合法JSON（len1980），因此本轮没有复现原opaque重建分支，不能声称其修复原路径通过。新请求没有provider400是正常路径观察，不替代原分支。D22拟在已有strict唯一可信HOST READ阶段将fresh provider实际允许集合收窄至该READ+harness，保留native/UNKNOWN恢复优先，并按可信MODEL原input.toolNames只消费真正曝光的activation；四内部文件作者/独立静态审通过，未编译或操作GUI，未改变本批GUI结论。D19/广身份preflight未实施。

1595旧Preview再次可见，1596 root真正Stop后消失，人工恢复Calculator294只为环境复原；1597托盘菜单→1598正常退出，IDEA实际13:21:30.862资源清理完成/exit0。[1596实际停止](../target/e2e-20261004/evidence/1596-canonical-preview-stopped-original294-restored.png)、[1598正常退出](../target/e2e-20261004/evidence/1598-before-d22-normal-exit.png)。F23-04按已实际核对的覆盖表通过：D08验收/预算1137–1139、发布流程节点及历史1313–1315、站点完整masked字段1348、人格完整页1360/1362、旧/新图片与文档标签及真正viewer1398–1403、MCP删后重启0（1266）、两原调度ON/四临时不复活1094、原设置1071–1090，配合多次正常退出/IDEA源码重开；本项没有独立退出/重开Bug，不合并其它模块修复为本项新Bug。

1602 root通过真实IDEA Launcher配置菜单把Allow multiple instances从ON取消、保存后重新打开确认OFF，Launcher主配置保持。1599/1600即时入口未打开不计成功，1601仅初始ON观察；无需源码/配置文件改写。[1602真实重开核对](../target/e2e-20261004/evidence/1602-idea-allow-multiple-restored-off-reopened.png)。IDEA全屏恢复仍待。12临时文件已移可恢复废纸篓，未清Trash；七条Personal事实仍无新human即时确认、未执行删除，原人工1保留；本轮会话及两owned无前缀blank最后GUI核验/清理尚待。F23-05/06不能提前通过，146项现89/26/1/28/2。


## 补充批次1603–1605：D22源码加载与原请求重新验证

root与独立协作agent实际审阅四个/tmp增量，没有阻断；[compile-d22-1333.log](../target/e2e-20261004/compile-d22-1333.log)实际13:32:44 BUILD SUCCESS、6.193秒、1487源码，仅-DskipTests compile。1603 root通过IDEA CtrlR启动源码，约13:33实际主窗75会话；旧失败会话只见一条用户消息，不据此宣称旧助手error正文已持久化。1604新第76空会话，1605约13:34完全相同canonical fixture真正Send。证据[1603加载](../target/e2e-20261004/evidence/1603-d22-idea-loaded-state.png)、[1604新会话](../target/e2e-20261004/evidence/1604-d22-new-regression-session.png)、[1605同原请求发送](../target/e2e-20261004/evidence/1605-d22-original-canonical-request-sent.png)。D22/B50完整GUI结果等待root真实终态，编译/加载/发送均不作为功能通过。

D22采用[实际读取的LangGraph primary与完整MIT记录](../target/e2e-20261004/github-references/langgraph-required-read-tools-reference.md)，只参考宿主权威注册集合与provider真实绑定一致性；旧chat_agent_executor明确deprecated，没有迁入其API或循环。独立实现在OnDemandContextSession、ComputerUseContextSelection、ToolCatalogSession、PersistedProviderTools四个现有内部文件：可靠适用冻结合同经同Run/同scope/当前最新可信V3严格求值，唯一已授权HOST READ首缺期间，fresh实际允许集合只有该READ+harness，目录NONE。未知/歧义、非READ保持原路径；native lifecycle/session/RECONCILE/UNKNOWN恢复、policy/source/schema/预算与原冻结replay保持。

隐藏的durable activation不被READ阶段暗中消费：live与恢复统一根据可信schema1/framework.core MODEL原started.input.toolNames和completed绑定，仅消费实际曝光交集；onDemand还核全部真实定义指纹，classic不新增指纹字段要求。缺坏legacy目录保守暂停，非法事件不推进游标或提前移除绑定；模型返回调用名不是消费authority。不新增公开端点、grant、action或自动CLAIM，不调整严格次序/预算/repair上限。B51上轮普通目录不再进入host强制launch子路径保持实测结论，整项F21-05失败保持。七条事实仍无新即时确认、未删除，最终清理/重启待；146项89/26/1/28/2不变。


## 补充批次1606–1609：次序已正确，历史视图修复命中，逻辑点击主体仍未闭环

1606实机只读Preview294，1607 root仅前台接管、Calculator实际0，没有人工Calculator输入；1608仍active3m14，1609约13:43真实Send恢复/2消息/ctx约93.7K、Calculator0而Preview仍开。限定[新76安全摘要](../target/e2e-20261004/desktop-canonical-d22-1605-safe-diagnosis.json)为session32b260a1…/Run b04a24ae-2f75-4353-a186-6ea30fe5080a。core233合法NEEDS_INPUT(c5)、235WAITING_INPUT；这是本轮GUI已结束、等待人类输入，不是kernel FAILED或完成核验。证据[1606初始294](../target/e2e-20261004/evidence/1606-d22-canonical-preview-before-takeover.png)、[1607接管后0](../target/e2e-20261004/evidence/1607-d22-canonical-front-takeover.png)、[1608运行中](../target/e2e-20261004/evidence/1608-d22-canonical-current-result.png)、[1609本轮结束](../target/e2e-20261004/evidence/1609-d22-canonical-terminal-observation.png)。

D22实际provider19仅apps+harness、29仅targets+harness，没有launch；严格174选26apps→36targets→46open→61before294→116final0。真实click86为ACCEPTED/SENT、对应completed85SUCCEEDED；第二尝试192 FAILED/NOT_SENT/STALE_FRAME，所以两次尝试仅一次派发，不称重复效果。B50原before61位于真实click86之前，在其后已有action情况下仍被严格选中，历史view豁免原分支修复后通过。整体仍只有c5未满足：冻结requiredSubject为Clear All button，而两个真实input receipt subject均空。正文说缺ACCEPTED不能替代真实宿主回执；可见0不证明空subject满足非空逻辑按钮条件，不能删除旧c5、放宽matcher或伪造按钮subject救本轮。

B53两文件最小修复已作者及独立静态通过：Registry明确click/type/key/scroll input receipt requiredSubject为空，动作细节留description并以独立observe验证结果；Compiler对这四能力的非空subject记录UNSUPPORTED_RECEIPT_SUBJECT、保留原值，走原有界规划修复/不可靠拒绝。adapter、旧合同、Evaluator、schema、权限及预算不改；本段1609时尚未编译加载，后续13:56已IDEA加载、1629正常规划路径观察见下批。B52本轮177 fresh history四字段合法，但原163本已合法，opaque重建具体分支未覆盖。F21-05整体失败保持。

D21另按root明确授权实现四内部文件，作者与journal独立静态两轮通过、source冻结；本段1609时尚未编译/GUI，后续编译及13:56源码加载见下批。它处理自然名称在D22 READ前缺同Run OS别名的独立闭环，不用于清除本轮canonical c5缺项。复用[实际Hammerspoon primary/MIT及架构记录](../target/e2e-20261004/github-references/hammerspoon-application-identity-preflight-design.md)：core原parser提供immutable同Run/时点之前完整唯一alias准备状态，必要TaskResultEvaluator只读跨包bridge避免另写弱parser；原matches/canonical/evaluate不改。fresh唯一规范化自然目标仅APPS+harness，真实raw query/连续offset/catalogId、有界refs决定推进；empty/ambiguous/truncated/failed/bad binding/未完成重复或跳页/目录epoch变化/预算不足明确暂停。完整相同可信页可去重但不增加完成事实，查询参数提示并不保证模型一定按提示调用；接受身份证明仍由真实完整exact alias和原canonicalIdentity共同核验。已派发SENT/UNKNOWN输入却此前缺身份时直接暂停，不补目录后重放；原replay/native/UNKNOWN/activation生命周期与D22保留。146项89/26/1/28/2不变。


## 补充批次1610–1626：预览停止、批量删除取消、D21/B53编译

1610 root真正Stop使Preview消失，1612人工恢复Calculator294仅为环境复原，非产品输入验收。1613搜索E2E_、1614进入管理、1615全选实际61项，数量来自本轮GUI，不是旧总数差值69或其它推算。1616仅打开不可恢复删除popup；1617/1618 AX无法读取正文，1619–1623 root实际滚屏核范围但尚未到最终底部，不能声称61项全已匹配安全来源/短ID。1624实际Cancel，未删任何本批会话；七条Personal事实仍没有新human即时确认，也未删除。证据[1610真正停止](../target/e2e-20261004/evidence/1610-d22-canonical-preview-stopped.png)、[1612环境恢复](../target/e2e-20261004/evidence/1612-d22-preview-stopped-original294-restored.png)、[1615实际选中61](../target/e2e-20261004/evidence/1615-owned-session-filter-selected.png)、[1616仅打开popup](../target/e2e-20261004/evidence/1616-owned-session-delete-scope-dialog.png)、[1623长标题范围滚屏](../target/e2e-20261004/evidence/1623-owned-session-delete-scope-long-titles.png)、[1624取消保留](../target/e2e-20261004/evidence/1624-owned-session-delete-cancelled.png)。原7精确保护IDs不能删除，两owned无E2E前缀blank须另行GUI核验；选择数量或部分阅读都不替代逐项归属和最终确认，F23-05/06仍待。

root实际读D21四/tmp增量与B53两/tmp增量，静态通过。1625真实托盘菜单→1626实际退出，CUA IDEA确认13:55:01.284资源清理/exit0；[compile-d21-b53-1355.log](../target/e2e-20261004/compile-d21-b53-1355.log)在文档核对时已实际完成13:55:34 BUILD SUCCESS、6.103秒、1487源码，仅-DskipTests compile，未运行代码测试。证据[1625退出菜单](../target/e2e-20261004/evidence/1625-before-d21-b53-tray-exit-menu.png)、[1626正常退出](../target/e2e-20261004/evidence/1626-d21-b53-normal-exit.png)。本段1626时源码加载及原type/key GUI结果尚待；后续13:56已加载、1629原请求发送见下批，不将编译替代通过。整体F21-05仍失败；146项89/26/1/28/2不变。


## 补充批次1627–1632：D21/B53已源码加载，原type/key请求运行中

root实际通过IDEA CtrlR于13:56启动当前源码；1627主窗76会话，旧76的两条消息/WAITING状态仍在，不据此改判旧c5。1628新第77空会话、消息0/ctx0；1629于13:57:55.187完全相同原type/key fixture真实Send，session49a34c09-aed9-425c-aede-d8554416b766、Run4e485219-846f-45fa-ad19-1b8f21f7f477。1630只读Preview294；1631 root仅点击前台接管，Calculator仍294/前台模式，没有人为Calculator输入。1632实机14:00仍运行2m33/44608输入1854输出、Calculator294，Preview隐藏不等于Stop释放。证据[1627源码加载](../target/e2e-20261004/evidence/1627-d21-b53-idea-source-loaded.png)、[1628新空会话](../target/e2e-20261004/evidence/1628-d21-b53-new-regression-session.png)、[1629原请求发送](../target/e2e-20261004/evidence/1629-d21-b53-original-type-key-request-sent.png)、[1630初始Preview](../target/e2e-20261004/evidence/1630-d21-b53-type-key-first-state.png)、[1631前台接管](../target/e2e-20261004/evidence/1631-d21-b53-type-key-front-takeover.png)、[1632仍在运行](../target/e2e-20261004/evidence/1632-d21-b53-type-key-current-result.png)。

已读取限定[1629安全摘要](../target/e2e-20261004/desktop-type-key-d21-b53-1629-safe-diagnosis.json)截至seq85：冻结9条V3 reliable/applicable/RESOLVED，应用target保持Calculator，click/type/key主体均空；provider19仅applications+harness，23精确query Calculator、26真实OBSERVED；29仅targets+harness→36，39仅open+harness→46，无launch。D21自然名称同Run真实目录前置已执行，严格alias是否由实际review选中及整个输入验收待终态；B53正常有效规划可见，但UNSUPPORTED_RECEIPT_SUBJECT拒绝/有界修复分支没有触发。该摘要尚无真实review或合法decision，不把参数、目录成功或源码加载算完整任务通过。原F21-05失败保持，B50历史view/B51pure-query限定分支通过、B52 opaque分支未覆盖的边界保持。

146项重计仍为89通过/26修复后通过/1失败/28阻塞/2待验证；仅失败F21-05，待验证F23-05/06。七条Personal事实没有新即时确认、未删除；61选中会话此前取消未清理，原7保护、两owned无前缀blank需最终GUI独立核验。本批只更新文档和读取已保存安全摘要，未操作GUI、编辑源码、运行编译/代码测试或写业务数据库。


## 补充批次1634–1638：身份与点击已验收，清零逻辑证明仍缺

1634实机约14:03本轮受阻待处理，4m33/102552输入5671输出、Send恢复/2消息、Calculator实际0，root只点击前台接管，没有操作Calculator。1635激活Java主窗后Preview仍存在；1636真正Stop使其消失，并滚屏观察完整受阻正文。1638 root人工恢复原294仅属环境复原，不能作为产品输入通过。证据[1634实机受阻](../target/e2e-20261004/evidence/1634-d21-b53-type-key-live-state.png)、[1635Preview仍在](../target/e2e-20261004/evidence/1635-d21-b53-terminal-preview-visible.png)、[1636真正停止及完整正文](../target/e2e-20261004/evidence/1636-d21-b53-terminal-details-preview-stopped.png)、[1638环境恢复](../target/e2e-20261004/evidence/1638-d21-preview-stopped-original294-restored.png)。

最终[1629安全摘要](../target/e2e-20261004/desktop-type-key-d21-b53-1629-safe-diagnosis.json)确认同Run4e485219…235 COMPLETED，taskResult却是BLOCKED/MODEL_BLOCKED，不把run结束当任务通过。strict选择36targets→46open→134before294→159click，c1–c4满足；c5–c9仍缺。134前置proof置信度.92/全部六帧字段绑定；159仅一次FOREGROUND_SYNTHETIC ACCEPTED/SENT，subject为空可以匹配新冻结click，D21 Calculator真实OS alias与B53正确有效规划/点击验收子路径有真实依据。173后置观察显示0但conditionEvidence=[]、174 receipt无c5逻辑proof，不能将屏幕字符串0直接升级为验收证据。152 provider真实曾提供type/key，之后c5首缺READ阶段162/178/202仅observe+harness，实际type0/key0；正文声称2026或工具从未提供不作根因，真实前置内容294且无2026。115/195两次合法CONTINUE→117/197 PARTIAL→118/198既有两次repair→224 BLOCKED；本轮没有预算停止或provider400，B52 opaque重建原分支仍未覆盖。

专项安全定位已排除D23条件漏传：166observe→167视觉任务→168真实输入带c3/c5=0/c7=123+456/c9=579→169模型输出两项c3/c5→173均被丢弃。两候选exact ID/subject、main-content、outerconfidence .93、content原文0在visibleText、role content、bbox(0,100,460,150)在真实460×816帧内，唯一缺内层content.confidence。该分类来自静态Parser读取，不冒充运行事件reasonCode。168真实outputSchema及当前DesktopObservationSchema都未将condition自身或内层confidence列入required，而Parser要求显式数字；不以外层.93补值，保持严格拒绝正确。本批1634–1638定位时B54仍是只读Schema/消费契约对齐提案；后续root已授权四vision文件实现并静态冻结，状态见下段，不把源码修复回写成此轮GUI通过。D23新条件通道不实施。现视觉ModelTaskRequest maxRetries=0，不宣称已有自动schema定向修复；若要求新修复反馈需独立确定有界范围。先实际阅读[Browser Use条件与图像共同判断主实现及MIT记录](../target/e2e-20261004/github-references/browser-use-observation-condition-reference.md)，只参考明确条件与观察共同判断，不替代JavaClaw帧绑定proof。

146项仍89通过/26修复后通过/1失败/28阻塞/2待验证；F21-05失败、F23-05/06待。七条Personal事实无新即时确认未删，本轮61会话此前取消未删。实机结果均由root观察，本批仅更新文档、读取已保存安全摘要/源码及GitHub主源，未操作GUI、编译、运行代码测试或写业务数据库。


## 补充批次1641–1644：删除范围底部核对后取消

1641实际E2E_筛选全选62项；1642只打开不可恢复删除popup并核顶部，1643真正滚至底部，底部均为本轮E2E_默认/条件/删除保护/取消/初始、审批超时/重启分支/基础聊天等条目，不把同名或4c085e40派生短ID混同为一个会话。0cb035ab-9961-4082-aa34-b613b23303e2当前实际标题是E2E_聊天初始6729，已被62项筛选覆盖，不能另算无前缀blank。1644真正Cancel，没有删除，主列表77保持；选择数量、顶部与底部核对不等于已完成全部归属与删除。原7精确保护ID不动，剩余无前缀owned项a3584f1b需单独GUI核验；七条Personal事实无新即时确认未删。证据[1641选中62](../target/e2e-20261004/evidence/1641-owned-session-current-filter-selected.png)、[1642顶部](../target/e2e-20261004/evidence/1642-owned-62-session-scope-confirmation-top.png)、[1643真正底部](../target/e2e-20261004/evidence/1643-owned-62-session-scope-confirmation-bottom.png)、[1644取消保留](../target/e2e-20261004/evidence/1644-owned-session-scope-cancelled-no-deletion.png)。F23-05/06仍待，146项89/26/1/28/2不变。


## B54修复准备：四vision文件静态冻结，原GUI回归待

实施前已实际阅读[Pydantic AI具体验证反馈与完整MIT](../target/e2e-20261004/github-references/pydantic-vision-condition-repair-reference.md)，root随后授权最窄四文件，作者/root/journal peer已实际逐一审查[相对/tmp基线的增量](../target/e2e-20261004/b54-four-vision-files-incremental.patch)，178新增/9删除，源码冻结。保留首宽schema/合法OCR，仅其它原严格字段已合格、unique exact HOST候选的confidence字段absent触发一次同图修正；不修低值/错subject/重复/空候选、不填dummyconfidence。第二schema/解析只eligible IDs，候选用首visibleText走原Parser再合并，首summary/targets/activeView/已accepted不改。PNG仅编码一次、全部原host条件仍传、反馈只固定路径；首次前monotonic原45/90总期限、第二前重算剩余，不增加45秒；既有Framework journal/vision预算/cachefalse/maxRetries0不变。先传播因果/抑制链权威控制异常再checkowner，避免Budget/TurnPaused被后续期限耗尽覆盖。独立修正失败/无proof保留首OCR，Stop/中断/Run预算必须传播。

本段源码冻结时尚未编译、IDEA加载或GUI回归；后续14:28编译/14:29加载见1645–1649批次，不将1634原BLOCKED改判。F21-05仍失败，146项仍89通过/26修复后通过/1失败/28阻塞/2待验证。D23新的条件通道不实施；B54只对已有host条件视觉候选补足有界真实修正路径，没有新增capture/frame/input/grant/公共API/SPI，Evaluator/adapter/冻结合同保持。


## 补充批次1645–1649：B54编译加载，原输入路径运行中

1645真实托盘菜单、1646正常退出，root通过IDEA确认14:28:20.542资源清理/exit0。[compile-b54-1429.log](../target/e2e-20261004/compile-b54-1429.log)实际14:28:50 BUILD SUCCESS、6.097秒、1487源码，仅-DskipTests compile。1647 root通过IDEA CtrlR源码启动，14:29 JavaClaw加载原77会话/当前旧2消息；1648真正新建第78空会话、0消息/ctx0；1649于14:30真实发送完全未改的原type/key fixture，仍active。证据[1645退出菜单](../target/e2e-20261004/evidence/1645-b54-before-source-rebuild-tray-menu.png)、[1646正常退出](../target/e2e-20261004/evidence/1646-b54-before-source-rebuild-normal-exit.png)、[1647源码加载](../target/e2e-20261004/evidence/1647-b54-idea-source-loaded.png)、[1648新空会话](../target/e2e-20261004/evidence/1648-b54-new-original-path-regression-session.png)、[1649原请求实际Send](../target/e2e-20261004/evidence/1649-b54-original-type-key-request-sent.png)。

编译/源码加载仅确认四vision修复已运行，不能替代missing-confidence定向修正或完整输入链的GUI结果。原1634任务BLOCKED历史保留，F21-05仍失败，本段截至1649时仍等待真实终态及限定owned安全诊断；不将后续修正触发回写为1649时已验证。146项89通过/26修复后通过/1失败/28阻塞/2待验证保持；七Personal事实仍未获新即时确认、未删，62项会话前次Cancel保留，最终清理/重启待。

## 补充批次1650–1663：真实579、视觉修正回退及重复清除

1650初始Preview294，1651 root只点击Takeover；1652仍294/1m48，1653实际0，1654–1656仍运行4m16/5m15/6m15。1658实际123+456、运行8m48，root未操作Calculator。1659终态实际10m32/190203输入6308输出、Send恢复/2消息、123+456=579；正文预算253874/250000。证据[1650初始](../target/e2e-20261004/evidence/1650-b54-live-preview-before-takeover.png)、[1651仅接管](../target/e2e-20261004/evidence/1651-b54-actual-foreground-takeover.png)、[1653实际清零](../target/e2e-20261004/evidence/1653-b54-foreground-live-result-progress.png)、[1658运行表达式](../target/e2e-20261004/evidence/1658-b54-original-chain-status.png)、[1659实际终态](../target/e2e-20261004/evidence/1659-b54-type-key-final-display.png)。运行中截图不作终态，1655文件名中的terminal也不改变当时仍运行事实。

[限定新78安全摘要](../target/e2e-20261004/desktop-type-key-b54-1649-safe-diagnosis.json)确认Run89c97ee5-3d79-4f39-9467-4c833c772d91、session05660d01-367b-4c57-b5fc-2bdb74e9653b：470宿主PAUSED/BUDGET_EXHAUSTED，并非kernelFAILED；UI显示失败。TaskResult PARTIAL8满足/1缺最终c9，选中44targets→54open→69before294→151clear→185zero→391type→406typed→424RETURN。type/key各一次FOREGROUND SENT，表达式proof406六帧字段绑定；没有harness/core.task.review，不编造合法CLAIM。clear共三尝试：111 NOT_SENT、151清除SENT、210全部清除SENT，两实际派发已违反用户只清一次/此后不清要求；strict描述中的once不证明调用次数。

B54两次真实secondpurpose224/438均使用原同一图像：前者eligible c3/c5、226TimerTimeout；后者c9内confidence缺失、440CancellationException。首summary/visibleText保留，缺proof仍拒绝。185有效零proof来自正常完整置信度输出，不是修正成功；B54安全失败回退已覆盖，成功增加proof分支待。203及271/301/331真实提供过type/key，模型曾选择重复click/observe；三次辅助planner错误仅query136/131/131超原128，fallback仍提供输入，不增加limit或宣称工具缺失。

1660激活Java仅隐藏Preview，1661激活Calculator后Preview又显示579；1662真正Stop才消失，1663 root终态后手工2/9/4恢复294，仅环境复原。证据[1660隐藏非释放](../target/e2e-20261004/evidence/1660-b54-preview-after-failed-task.png)、[1661仍存在](../target/e2e-20261004/evidence/1661-b54-native-calculator-after-terminal.png)、[1662真正停止](../target/e2e-20261004/evidence/1662-b54-preview-true-stop.png)、[1663人工恢复](../target/e2e-20261004/evidence/1663-b54-calculator-original-294-restored.png)。F21-05仍失败，146项89/26/1/28/2不变。D24按[实际LangChain primary/MIT记录](../target/e2e-20261004/github-references/langchain-required-input-tools-reference.md)进行最窄INPUT子集设计与门禁收口，未把源码或设计计通过；七Personal事实及会话清理均未执行。

## D24源码冻结与1664–1665收尾元数据

[原新78四个限定CONTROL元数据](../target/e2e-20261004/d24-owned78-control-provider-guard-1665.json)实际203/271/331为READY/GRANTED/inputAllowed且pending、observedPending均空；384才各有同一条真实pending。静态可能性或effectUNKNOWN不等于本轮pending实况，因此按root批准保留EMPTY门禁，对384保守退出，不引入不同kind豁免或清UNKNOWN。

D24三内部springai文件已保存独立/tmp基线，作者、root和journal peer逐实际增量静态审通过后冻结。[135新增/7删除增量](../target/e2e-20261004/d24-three-source-files-incremental.patch)、[源码hash与边界摘要](../target/e2e-20261004/d24-source-freeze-summary.json)及[实际GitHub primary/完整MIT与独立适配](../target/e2e-20261004/github-references/langchain-required-input-tools-reference.md)均单列。currentContract、原strict first missing、唯一真实接口/空subject、当前owner baseline/canonical身份核验后，fit与final都单INPUT+harness，不自动dispatch/CLAIM/grant。目录必须在帧started之前完整证明身份；literal appID没有目录时也保守不pin。原隐藏activation由D22实际曝光toolNames决定消费，未新增consumer/core/API/SPI。源码就绪不改F21-05失败，编译/IDEA加载/原GUI仍待。

1664真实托盘菜单→1665正常退出，root IDEA确认14:47:39资源清理/exit0，应用关闭便于下一源码加载。证据[1664菜单](../target/e2e-20261004/evidence/1664-d24-before-source-rebuild-tray-menu.png)、[1665正常退出](../target/e2e-20261004/evidence/1665-d24-before-source-rebuild-normal-exit.png)。[1665精确当前清理元数据来源](../target/e2e-20261004/cleanup-nonprefix-current-1665-provenance.json)仅辅助GUI归属：当前78、contains E2E_匹配63（59前缀及4内部包含的派生标题），另a3584f1b本轮blank及七个精确loop/schedule/workflow派生项均有本轮来源，原七保护IDs全在。不是时间/总数差值证明，不替代最终popup逐项ID核验与即时删除确认；连接已关闭、无DB写/会话删除。七Personal事实仍无新即时确认、未删；F23-05/06待，146状态89/26/1/28/2不变。

## 补充批次1666–1668：D24源码加载及原79输入链启动

root执行[compile-d24-1500.log](../target/e2e-20261004/compile-d24-1500.log)，实际14:59:18 BUILD SUCCESS、6.029秒、1487源码，仅-DskipTests compile。1666通过IDEA CtrlR从源码于14:59加载JavaClaw，旧78会话仅用户1消息/ctx0/等待状态，预算系统反馈未持久化，不能声称旧2消息恢复。1667真正新建第79空会话、0消息/ctx0；1668于15:01:33.533附近完全相同原fixture真实Send，当前active，仍待root实机终态及gepa限定新79安全诊断。证据[1666源码加载](../target/e2e-20261004/evidence/1666-d24-idea-source-loaded.png)、[1667新空会话](../target/e2e-20261004/evidence/1667-d24-new-original-path-regression-session.png)、[1668原请求发送](../target/e2e-20261004/evidence/1668-d24-original-type-key-request-sent.png)。

源码加载确认D24已进入真实运行，但不能计唯一下一INPUT门禁、无重复清除、最终条件proof或整个F21-05通过。原1659重复清除/缺c9失败保持，B54成功修正proof、B52 opaque、B53拒绝修复及B45 own30秒限定分支仍未覆盖。146状态89通过/26修复后通过/1失败/28阻塞/2待验证不变；最终删除即时确认未到，七Personal事实与会话没有执行清理。

## 补充批次1669–1674：D24两次唯一输入投影及协议暂停

1669初始Preview294，无输入；1670 root只点击Takeover，Calculator实际0，没有人为Calculator输入。1671仍运行；1672本轮结束、ctx约134.7K、Send恢复/2消息，Calculator表达式123+456。1673仅打开协议暂停后的继续入口，1674仍是继续前截图，尚无实际恢复结论。证据[1669初始](../target/e2e-20261004/evidence/1669-d24-preview-before-takeover.png)、[1670仅接管](../target/e2e-20261004/evidence/1670-d24-actual-foreground-takeover.png)、[1671运行中](../target/e2e-20261004/evidence/1671-d24-original-chain-live-progress.png)、[1672实机结束状态](../target/e2e-20261004/evidence/1672-d24-original-chain-status.png)、[1673继续入口](../target/e2e-20261004/evidence/1673-d24-protocol-pause-resume-entry.png)、[1674继续前](../target/e2e-20261004/evidence/1674-before-paused-run-continue.png)。Preview仍在，没有Stop或294人工恢复证据。

[限定新79最终安全摘要](../target/e2e-20261004/desktop-type-key-d24-1668-safe-diagnosis.json)确认sessionc362e652-b768-4152-ac0e-4c4bdb96c2a1、Run403bcbdb-0a3a-44fd-96c6-ebd3f59eb15c：278 PAUSED/PROTOCOL_ERROR，TaskResult PARTIAL6满足/3缺，缺表达式逻辑proof、Enter及最终579。73真实仅desktop_session_click+harness，118仅desktop_session_type+harness，两次都是READY/GRANTED/inputAllowed、pending空，required input hint与当前真实帧正确；D24单接口投影子路径已真实修复后通过，不等于自动执行或整项通过。118之后type121带额外targetId，被122 INVALID_TOOL_ARGUMENTS前置拒绝、executed=false，没有dispatch；后续137强制观察，pending非空时167/255退出D24、回到普通投影，不能清UNKNOWN为强行pin。后续精确绑定更正：该pending实际来自121前置参数拒绝、无派发/receipt却被Cursor当未决input，不是observe；B55定位见下一批。80背景与174前台clear各一次真实SENT，已违反原只清一次；259→262实际type一次7字符123+456，key0。

两次harness尝试129/271均使用合法CONTINUE枚举，但129引用UNKNOWN_EVIDENCE_REFERENCE被拒，271新增decisionNote使完整参数Schema非法；276真实D17反馈/decision/decisionNote [additionalProperties]后278协议暂停。没有被接受的harness decision或core.task.review，不能当B40 review已触发、合法CLAIM或预算停止。B54 94同图confidence修正→95Timeout，失败回退保持原OCR，成功补proof仍未覆盖。F21-05仍失败，146状态89/26/1/28/2不变；root即将同Run继续，结果待新实机证据，七Personal事实及会话尚未清理。

## 补充批次1675–1681：同Run继续及前置拒绝pending定位

1675第一次fallback沙箱无效、未输入，文件名entered不能作输入成功证据；1676真正GUI输入“继续”，1677于15:13:39.756发送。安全摘要确认279同Run403bcbdb-0a3a-44fd-96c6-ebd3f59eb15c resumed，没有另起Run；283仅observe+harness、287真实observe。1678观察47.5秒、Calculator仍123+456；1679界面出现c7表达式proof、typed/content置信度均.95及bbox；1680ctx172.6K/2m26仍运行。1681Preview仍123+456、最近按键显示画面已过期；尚不根据界面推断dispatchSuccess、Enter效果或whole完成。证据[1676真正输入](../target/e2e-20261004/evidence/1676-paused-run-continue-entered.png)、[1677发送](../target/e2e-20261004/evidence/1677-paused-run-continue-sent.png)、[1678观察进展](../target/e2e-20261004/evidence/1678-paused-run-resume-progress.png)、[1679条件卡](../target/e2e-20261004/evidence/1679-paused-run-resume-status.png)、[1680仍运行](../target/e2e-20261004/evidence/1680-paused-run-resume-progress.png)、[1681过期反馈](../target/e2e-20261004/evidence/1681-paused-run-resume-progress.png)。

B55精确源码及限定owned诊断纠正此前pending归属：121 type参数前置reject，其122 COMPLETED TOOL/FAILED带validationRejected、duration0、123 framework.springai arguments_rejected；在原gateway schema门禁处拒绝，早于reserveEffect/core.tool.started，未调用实际输入且无receipt。Cursor原frameAction缺receipt→!matched→pending，所以该条不是observe的UNKNOWN，也不是已派发type。root授权gepa仅Cursor最小可信拒绝证明排除，须sameRun/唯一stableStepId/invocation/tool/input/output/拒绝event完整绑定并无派发事件矛盾；单flag不能证明未执行，真实UNKNOWN仍保留。源码已准备、独立审查及加载/GUI待，不改D24EMPTY门禁救旧状态，F21-05仍失败。

并行仅源码复核[画面期限与安全重新观察路径](../target/e2e-20261004/desktop-observation-freshness-source-review-1681.md)：实时流latest帧1.5秒、pending捕获提交120秒、视觉结束commit后输入60秒是不同期限；KEY另检查contentRevision。不能把视觉45/90直接当60秒已耗或STALE_OBSERVATION唯一根因，不扩大TTL、跳像素/UNKNOWN门禁或自动重放输入。当前runtime只报live session IDs，未报权威提交观察有效期，是否主LLM耗时/帧修订须精确固定拒绝分支定位，尚未新增相关源码。146状态89/26/1/28/2不变；本批无Stop/人工恢复，七Personal事实及会话未清理。

## 补充批次1682–1687：同Run恢复仍缺Enter、真实环境复原

1682 root实机恢复轮失败3m8、4消息、ctx193.9K、累积249338/250000预算、Send恢复，Calculator仍123+456。[同Run最终安全摘要](../target/e2e-20261004/desktop-type-key-d24-1668-safe-diagnosis.json)确认279恢复→401 PAUSED/BUDGET_EXHAUSTED、TaskResult PARTIAL7/9；300表达式123+456逻辑proof六帧字段全绑定/双层.95被最终选中，仍缺Enter与579。两key尝试346→349、393→396均STALE_FRAME/NOT_SENT/dispatchAttempted=false，不能说按键已执行。继续期间无重复clear/type，整个Run仍为两次clear SENT/type一次/keySENT0，最初只清一次要求依然违反。恢复未出现新的被接受harness、协议或task.review事件，不把partly满足当整体通过。证据[1682恢复轮结束](../target/e2e-20261004/evidence/1682-paused-run-resume-progress.png)。

[固定host反馈与安全时间专项](../target/e2e-20261004/desktop-type-key-owned79-key-staleness-1682.json)将泛STALE_OBSERVATION细分：349固定“观察目标区域已变化”对应KEY whole-frame contentRevision不等，清除committed/pending observation，非60秒年龄门禁；396固定“观察 ID 不存在或已使用”仅能定位当前committed为null、请求ID空或不匹配三选一，不能猜具体项。两个观察completed至key.started实际33.727/32.513秒，服务committedAt没有导出，不把capturedAt当提交年龄。此前[TTL源码复核](../target/e2e-20261004/desktop-observation-freshness-source-review-1681.md)只是期限与安全策略说明，本轮不能归因TTL耗尽，也不扩大TTL/略过revision/清UNKNOWN或自动输入。

1683真正Stop使Preview消失；1684激活Calculator后Preview未复活。1685已经纠正文件名为after-stop-partial-operand-294，画面实际123+294而非恢复原294；1686按C后仍123+/AC状态，不能称完成；1687真正AC+2/9/4后实际294/无Preview，才是环境清理完成，均在任务已结束后人工进行、不作产品通过。证据[1683真正Stop](../target/e2e-20261004/evidence/1683-paused-run-preview-stopped.png)、[1684不复活](../target/e2e-20261004/evidence/1684-after-stop-calculator-before-restore.png)、[1685仅当前操作数294](../target/e2e-20261004/evidence/1685-after-stop-partial-operand-294.png)、[1686仍表达式](../target/e2e-20261004/evidence/1686-after-stop-clear-current-operand.png)、[1687真实294](../target/e2e-20261004/evidence/1687-after-stop-calculator-restored-294.png)。B55作者正在收口唯一StepId.tool绑定、尚未源码加载/原GUI回归，F21-05仍失败；146状态89/26/1/28/2不变，七Personal事实及会话清理未执行。

## 补充批次1688–1693：B55编译加载及新80原请求

1688实际托盘退出菜单→1689正常退出，root确认IDEA15:24:00.992资源清理完成/exit0。B55仅ComputerUseSessionCursor可信前置参数拒绝helper及StepId.tool精确绑定，root/peer实际静态审通过。root执行[compile-b55-1524.log](../target/e2e-20261004/compile-b55-1524.log)，实际15:24:30 BUILD SUCCESS、6.204秒/1487源码，仅skipTests compile。1690通过IDEA源码于15:25启动，旧79当前仅3消息/ctx0/等待状态，不能把此前GUI4消息或yellow预算系统反馈当已持久化通过。证据[1688菜单](../target/e2e-20261004/evidence/1688-tray-exit-menu-before-b55-compile.png)、[1689正常退出](../target/e2e-20261004/evidence/1689-normal-exit-before-b55-compile.png)、[1690源码加载](../target/e2e-20261004/evidence/1690-idea-source-b55-restart.png)。

1691于15:25:41.786真正新建第80空会话、0消息/ctx0；1692原fixture未改实际输入，1693于15:26:49.717真实Send、思考5.6秒仍运行。证据[1691新空会话](../target/e2e-20261004/evidence/1691-b55-new-session-80-blank.png)、[1692原请求输入](../target/e2e-20261004/evidence/1692-b55-original-request-entered.png)、[1693发送](../target/e2e-20261004/evidence/1693-b55-original-request-sent.png)。B55源码已加载，但尚不能声称实际前置reject不再留错误pending、无重复clear、key派发或最终579验收；原F21-05失败和未覆盖分支均保留，等待真实终态及唯一owned reader安全摘要。146状态89/26/1/28/2不变，最终事实/会话清理未执行。

## 补充批次1694–1698：工具前契约暂停、同Run继续澄清及原请求重发

1694实机42.1秒、2消息/ctx0，在业务操作前暂停，Calculator294且没有Preview；文件名含preview不表示出现预览。[新80限定安全摘要](../target/e2e-20261004/desktop-type-key-b55-1693-safe-diagnosis.json)确认session52775bda-5997-4fc4-8d6b-a15ff2b0e7ac、Run224a0ef0-0272-4f8c-8be4-f3167c18d8a6：首计划4→5 ReadOnlyTaskTimeoutException；一次计划repair8→9完成，但12不可靠契约的c5/c9 desktop.observe要求VERIFIED超过HOST上限OBSERVED，原因EVIDENCE_CEILING_EXCEEDED/PLAN_REPAIR_EXHAUSTED。15 PAUSED/TASK_UNVERIFIED，无业务工具和输入调用；不能把它计B55参数拒绝修复或D24输入回归失败。证据[1694工具前暂停](../target/e2e-20261004/evidence/1694-b55-desktop-control-preview.png)。

1695实际输入继续，1696于15:31:28.852发送；16恢复同原Run，不是另起Run。22 contract_revised为NEEDS_HUMAN、AMBIGUOUS_CONTINUATION/UNRESOLVED_INPUTS、criteria0，23 WAITING_INPUT，业务仍0；实机2.7秒/4消息/ctx0，回复询问继续哪项任务。只读现TaskHarnessLifecycle.clarificationRequest确认原请求仍保留在humanHistory，但最新core.text是继续并移除了explicit-original属性；Compiler在模型规划不可靠时回退该当前输入，因此本次revised.originalRequest是继续。原RunRequest及既有journal未被重写，不能写成原目标丢失或宿主必然新建任务。已实际读取Cline官方[task-control coordinator](https://github.com/cline/cline/blob/main/apps/vscode/src/sdk/sdk-task-control-coordinator.ts)同taskId重载持久历史与状态、[完整Apache2.0](https://github.com/cline/cline/blob/main/LICENSE)；此阶段只是恢复语义参考，未实施新的继续策略，后续按root指令停止扩展歧义研究。证据[1695输入](../target/e2e-20261004/evidence/1695-b55-plan-pause-continue-entered.png)、[1696同Run澄清](../target/e2e-20261004/evidence/1696-b55-plan-pause-continue-sent.png)。

1697按实际澄清重新输入原fixture全文，1698于15:33:11.635发送，5消息/思考7.7秒。一次clear、原type/key要求未改；仍运行，不能提前提升whole F21-05或B55分支。证据[1697原全文](../target/e2e-20261004/evidence/1697-b55-original-task-clarification-entered.png)、[1698发送](../target/e2e-20261004/evidence/1698-b55-original-task-clarification-sent.png)。另D25只完成[官方Pydantic AI/MIT与有界语法修正设计](../target/e2e-20261004/github-references/pydantic-vision-invalid-json-repair-reference.md)，不把研究或既有条件候选修正当实际JSON修复通过。146项89/26/1/28/2保持，七Personal事实及会话没有最终删除。

## 补充批次1699–1702：原请求重发后重复观察停止与D25语法修正缺口

1699原全文重发后的桌面处理仍进行，1700 root仅前台接管，没有Calculator输入。1701实际6消息/ctx36.6K、Calculator294、Preview仍开，不能当释放；1702真正Stop后预览消失，并跳到最新失败消息：2m45/35875输入731输出，黄色反馈检测到重复工具调用循环。Calculator始终294，无需人工还原，也没有将人工操作算产品结果。证据[1699运行](../target/e2e-20261004/evidence/1699-b55-after-clarification-desktop-progress.png)、[1700接管](../target/e2e-20261004/evidence/1700-b55-desktop-foreground-takeover.png)、[1701预览仍在](../target/e2e-20261004/evidence/1701-b55-observation-validation-terminal.png)、[1702真正Stop及终态](../target/e2e-20261004/evidence/1702-b55-observation-repeat-budget-terminal.png)。

唯一owned reader结算的[新80安全摘要](../target/e2e-20261004/desktop-type-key-b55-1693-safe-diagnosis.json)及root限定核验确认：34最新可靠契约共十条件；137宿主PAUSED/BUDGET_EXHAUSTED的具体门禁为REPEATED_TOOL_CALLS4/3，非Token250000耗尽。严格只有c1/c2满足，三次观察、全部input0，第四调用候选被前置循环门禁停止。B55原schema参数拒绝排除分支没有被调用，不把本轮失败当该补丁失效或通过。c10以desktop.observe/OBSERVED要求“最终操作结果和sessionId及实时预览保持状态”，包含最终文字报告和另一预览状态，Calculator画面没有Framework sessionId且视觉提示隐藏会话值；本轮c3已先失败，c10尚未实际评估，故仅记录规划语义疑点，不断言它是实际停止原因，不改冻结条件或预先实施Compiler修复。

92与126两次首视觉完整输出为INVALID_JSON，summary字符串引号未转义；安全定位分别line1/col103、code20840及col56、code67，不复制原输出。109是B54缺innerconfidence定向修正超时，属于不同失败，不能混为语法修正。现VisionPreprocessor无首INVALID_JSON一次有界重生成；GitHub-first已实际读Pydantic AI ToolManager严格验证/重试上限、RetryPromptPart反馈、新请求节点及完整MIT，[来源与最小独立适配边界](../target/e2e-20261004/github-references/pydantic-vision-invalid-json-repair-reference.md)已保存。root随后授权gepa仅VisionPreprocessor实现D25，同图/原conditions/总45或90秒剩余/一次第二完整输出/严格Parser及Framework预算，若走语法修正不再B54第三轮；源码尚未ready和加载，不能提前PASS。F21-05仍失败，146状态89/26/1/28/2保持，七Personal事实及会话最终删除未执行。

## 补充批次1703–1709：D25源码冻结、IDEA加载及新81原请求

1703实际托盘菜单→1704正常退出，root确认IDEA15:48:42.692资源清理完成/exit0。D25仅VisionPreprocessor，root/peer逐实际增量静态通过，最终源码SHA256 `50fd74e083b6856edd48d1fa804d266230062cfb94b48d445bd4998e77c9ea8b`、patch SHA256 `fa6da3f962f3a19efde45291cacfd8f52fdb53984f5320224ed2035fa039b4f2`；最后仅comment明确一次观察至多两次显式Framework structured model-task调用，不宣称物理provider请求≤2，旧SDK maxRetries3保持。见[单文件独立增量](../target/e2e-20261004/d25-vision-structured-json-repair-source.patch)、[静态冻结摘要](../target/e2e-20261004/d25-vision-structured-json-repair-source-review.json)、[GitHub-first/MIT与独立实现](../target/e2e-20261004/github-references/pydantic-vision-invalid-json-repair-reference.md)。源码摘要的compiled=false等是作者冻结时状态，后续root实际编译另列，不将静态记录误作未加载或GUI通过。

root执行[compile-d25-1549.log](../target/e2e-20261004/compile-d25-1549.log)，实际15:48:56 BUILD SUCCESS、6.141秒/1487源码，仅-DskipTests compile；1705 IDEA Ctrl+R于15:49源码boot，D25+B55+B54+D24实际加载。旧80当时5条持久消息/ctx0/等待，上一轮黄色重复循环失败未持久化，不能声称旧6消息或黄色终态持久化通过。证据[1703退出菜单](../target/e2e-20261004/evidence/1703-tray-exit-menu-before-d25-compile.png)、[1704正常退出](../target/e2e-20261004/evidence/1704-normal-exit-before-d25-compile.png)、[1705实际加载](../target/e2e-20261004/evidence/1705-idea-source-d25-restart.png)。

1706于15:53仍旧80/80会话；1707于15:54真正新建第81空会话、0消息/81会话，1708原E2E_desktop_type_key_request完全未改并经真实键盘输入，1709于15:54真实Send、1消息/思考0.1秒。Calculator原294在后方，root没有输入任何Calc键。证据[1706新建前](../target/e2e-20261004/evidence/1706-d25-before-new-session.png)、[1707真正新81空页](../target/e2e-20261004/evidence/1707-d25-new81-empty.png)、[1708原请求](../target/e2e-20261004/evidence/1708-d25-new81-original-request.png)、[1709发送](../target/e2e-20261004/evidence/1709-d25-new81-send.png)。仅确认真实加载和发送，D25语法重生成、B55可信前置拒绝排除、B54成功proof及whole均待owned限定诊断和root终态；不提前PASS。146项89/26/1/28/2维持，七Personal事实和本轮会话最终清理未执行。

## 补充批次1710–1718：清除后证明仍缺、两次有界修正超时及真正释放

1710真实预览294/仍运行，1711 root只点前台接管后预览0，1713/1714实际Calculator0、运行中且Stop仍在，root未输入Calculator键。1715实际本轮结束3m31、63897输入3309输出、2消息/Send恢复、ctx约67.2K，任务未核验；隐藏预览不等于Stop。证据[1710预览294](../target/e2e-20261004/evidence/1710-d25-new81-current.png)、[1711仅接管](../target/e2e-20261004/evidence/1711-d25-new81-foreground-takeover.png)、[1712运行记录](../target/e2e-20261004/evidence/1712-d25-new81-input-progress.png)、[1713实际0仍运行](../target/e2e-20261004/evidence/1713-d25-new81-observation-progress.png)、[1714仍运行](../target/e2e-20261004/evidence/1714-d25-new81-current-result.png)、[1715真实结束](../target/e2e-20261004/evidence/1715-d25-new81-reply-result.png)。

[限定新81最终安全摘要](../target/e2e-20261004/desktop-type-key-d25-1709-safe-diagnosis.json)确认sessioncc4f6c89-e5fa-421e-a7f8-2c566da55e59、Rund47b9618-b99e-4bb8-9134-b845e6b52b41：125/169两合法CONTINUE、128一次task repair，174 PAUSED/TASK_UNVERIFIED、NO_PROGRESS、PARTIAL4/9。严格c1targets/c2open/c3before/c4clear满足，首缺c5清除后的逻辑0及后续五项。唯一clear80为BACKGROUND_SEMANTIC/SENT/ACCEPTED，type/key0，不把接管后的前台预览模式当该点击前台派发，也不以GUI0代替c5逻辑proof。此轮D25首INVALID_JSON及语法重生成均0、B55参数前置拒绝0，不能将未触发原路径记通过。

首次63视觉32.243秒得到有效c3；清除后89首视觉30.258秒、138首26.835秒均合法JSON，c3/c5 exact主体、main-content、label0/原文/像素框及外层.92合格，但content.confidence缺失，原Parser正确拒绝。94第二任务剩14.679587667秒、实际14.656秒超时；143剩18.047786125秒、实际18.028秒超时，两pair44.950/44.894秒，原monotonic45秒保持，首合法OCR仍保留。不能写成INVALID_JSON、B54成功修正或放大预算。

D26仅研究已确认第二任务重生成未消费的全观察字段：confidenceRepair schema仍required summary/visibleText/targets(max24)，activeView为可选property；输入保留全观察instructions与全部conditions，而Parser.repairedConditions只消费conditionEvidence并与首OCR绑定。GitHub-first实际读Browser Use独立小型评估类型、完整调用与judge.py204行及MIT19行，[focused候选设计及严格边界](../target/e2e-20261004/github-references/d26-focused-candidate-repair-reference.md)已发root。最小两内部文件建议不改D25完整输出路径、阈值/原OCR/同图/总期限/预算；源码尚未实施，不能预判性能或whole通过。

1716激活Java后Preview仍显示0及前台观察过期，16:02未释放；1717真正Stop后预览消失，1718随后才激活Calculator并人工2/9/4恢复原294，16:03 Preview不复活。均为运行结束后的环境清理，不作产品PASS。证据[1716仍存在](../target/e2e-20261004/evidence/1716-d25-new81-before-desktop-stop.png)、[1717真正Stop](../target/e2e-20261004/evidence/1717-d25-new81-real-desktop-stop.png)、[1718随后人工复原](../target/e2e-20261004/evidence/1718-d25-after-stop-restored-calculator-294.png)。146项89/26/1/28/2保持，仅F21-05失败，七Personal事实及本轮会话最终删除未执行。

## 补充批次1719–1721：D26两内部文件获授权及正常退出

root实际读取D26官方GitHub/MIT与最小focused设计后，授权gepa为唯一源码作者，仅DesktopObservationSchema/VisionPreprocessor；独立/tmp/javaclaw-d26-*基线于16:04授权前保存。只让B54第二任务输出conditionEvidence并改专用短prompt，D25首语法修正仍完整输出/schema、首OCR原强验证/同图/45或90秒总剩余/owner预算及全部门禁保持。源码尚未ready、编译、加载或GUI回归，不计任何功能通过。

1719实际托盘菜单→1720点击正常退出；1721实机JavaClaw无窗，root IDEA确认16:04:07.780资源清理完成/exit0，Calculator294保持。证据[1719菜单](../target/e2e-20261004/evidence/1719-d26-before-normal-exit.png)、[1720退出操作](../target/e2e-20261004/evidence/1720-d26-normal-exit.png)、[1721退出完成](../target/e2e-20261004/evidence/1721-d26-exit-complete.png)。81头信息归属只读核对在应用关闭期间进行，永久会话删除与七Personal事实删除均未发生；原七保护，不凭退出升级F23-05/06。146状态89/26/1/28/2维持。

## D26最终源码冻结与独立终审

root已实际读两/tmp独立增量，本文作者随后实际对照最终两个源文件及B54/D25完整调用链只读终审，通过且无阻断；最后短prompt明确不能确认时返回conditionEvidence为空数组的JSON对象，不能返回根数组。Schema仅新增focusedConfidenceRepair，根只包含并required conditionEvidence，从既有完整强候选属性deepCopy保留max12/knownIDs/全部required/双数字confidence/additionalProperties=false，空数组合法；旧create/confidenceRepair及D25完整语法重生成无增量。Preprocessor只替换B54第二轮专用输入/schema并去掉该私有方法未使用的input参数，唯一callsite同步；未改公开契约、首OCR/Parser/同图/总期限/owner账本/失败fallback/UNKNOWN或native。

最终实际SHA256核对与作者冻结记录一致：Schema `eba8e6f2547755a333f14b66a251d858f4005335fe7d192dced1512853e8f975`，Preprocessor `bcf29558cc17415048ca6ce1b78b5860133171bec4f457ce92fe2041382d995a`，patch `c908abb6eb7bfe4715eba39069efe52769c59b7b0e7f819898873e3e30460544`。见[两文件独立增量](../target/e2e-20261004/d26-focused-condition-repair-source.patch)、[作者冻结摘要](../target/e2e-20261004/d26-focused-condition-repair-source-review.json)、[实际GitHub主源与独立适配](../target/e2e-20261004/github-references/d26-focused-candidate-repair-reference.md)。静态diff check干净，本文作者未修改源码、编译、运行代码测试、操作GUI、读Run journal或写DB。源码静态ready不证明14–18秒必定完成，更不提升whole；编译/IDEA加载及实际focused修正仍待root，146状态保持89/26/1/28/2。

## 补充批次1722–1725：D26源码加载及新82原请求

root执行[compile-d26-1609.log](../target/e2e-20261004/compile-d26-1609.log)，实际16:09:21 BUILD SUCCESS、5.869秒、1487源码，仅-DskipTests compile。1722通过IDEA CtrlR于16:09源码启动，D26及此前修复实际加载；旧81完整2消息、ctx67.2K及TASK_UNVERIFIED回复保留，右侧等待0，没有Preview、Calculator仍294。1723于16:10真正新建第82空会话、0消息/82会话；1724未改原fixture真实输入；1725截图显示16:14实际Send后1消息/思考0.1秒。截图墙钟跨分钟，只记已观察时间，不补造发送精确毫秒。证据[1722源码加载](../target/e2e-20261004/evidence/1722-d26-idea-source-restart.png)、[1723新82空页](../target/e2e-20261004/evidence/1723-d26-new82-empty.png)、[1724原请求](../target/e2e-20261004/evidence/1724-d26-new82-original-request.png)、[1725发送](../target/e2e-20261004/evidence/1725-d26-new82-send.png)。

D26实际focused修正分支及完整输入链尚未取得终态，上一1715失败保留，F21-05仍失败；编译和源码加载不计功能通过。146项仍89通过/26修复后通过/1失败/28阻塞/2待验证。七Personal事实没有新即时确认、未删，永久会话删除未发生。

[截至1721组合头信息manifest](../target/e2e-20261004/session-header-manifest-1721-composed-readonly.json)及[74本轮owned逐项来源](../target/e2e-20261004/cleanup-owned-74-proof-1721.json)是已保存证据组合，标明composed-not-freshDBsnapshot、未包含之后新会话、finalCleanupClaimed=false；只供最终GUI审阅，不是新数据库读取或删除清单执行。原七精确IDs保护；4c085e40及schedule等first8存在碰撞，最终popup须核完整UUID，不能仅凭同标题、短ID或81/82总数差值批准删除。

## 补充批次1726–1731：focused修正成功，完整输入仍失败

1726于16:15 Preview实际0、最近click已受理；1727 root于16:16仅点击Takeover，Preview0且最近TYPE显示画面已过期。1728真实native0、工具complete3m41/ctx80.8K；1729 native1、执行5m26/ctx125.7K，文件名terminal-check不代表当时已结束；1730 native1、执行7m58/ctx163.5K。1731于16:25实机终态，TASK_UNVERIFIED回复、2消息/ctx187.5K、Send恢复，Calculator与Preview仍1，未出现原123+456。root运行中未输入Calculator。证据[1726当前0](../target/e2e-20261004/evidence/1726-d26-new82-current.png)、[1727仅接管](../target/e2e-20261004/evidence/1727-d26-new82-foreground-takeover.png)、[1728运行0](../target/e2e-20261004/evidence/1728-d26-new82-input-current.png)、[1729仍执行1](../target/e2e-20261004/evidence/1729-d26-new82-terminal-check.png)、[1730仍执行](../target/e2e-20261004/evidence/1730-d26-new82-observe-current.png)、[1731真实终态](../target/e2e-20261004/evidence/1731-d26-new82-result-check.png)。未获可靠最终in/out数值，不从ctx推算；本批尚无真实Stop或294恢复证据。

[限定新82最终安全摘要](../target/e2e-20261004/desktop-type-key-d26-1725-safe-diagnosis.json)确认sessioncaebf7ae-2dfc-4f05-a5f5-6aab56bed421、Run2c3fa646-d03d-4e6f-bd50-056d6f8ea803：D26第一次153→154 focused任务真实成功6.210秒，只返回conditionEvidence；159保留候选0、双层.95、首OCR锚定和六帧绑定。第二次289→290成功3.793秒、292语义完成，295真实候选1/双层.92、首OCR和六绑定同样完整。两个修正子路径实际修复后通过，原总45/90剩余、置信度和首OCR验证未放宽；257严格review未选择专用159，而选后续其它可信零proof，不能伪称whole依靠159完成。

三次type95/136/167都是用户要求的原7字符，但选择AXScrollArea actions0，没有WRITE位，原ManagedSession在platform.perform前拒绝/NOT_SENT/dispatchfalse；“画面已过期”展示来自能力拒绝被归STALE_FRAME，不是TTL。200由模型改为1字符并省略elementId，203真实前台SENT/ACCEPTED，平台未截断原文本。全Runclear SENT1/type SENT1错误文本1/type NOT_SENT3/key0。冻结type action能力可被受理收据满足，但独立原表达式proof不满足，不能把ACCEPTED当原完整输入完成。

314新增decision_details字段被319严格拒绝，335仅合法四字段CONTINUE→338接受。340最终strict PARTIAL6/9，342 PARTIAL/NO_PROGRESS，343 PAUSED/TASK_UNVERIFIED；满足get_targets/open_session/observe_initial/click_clear/verify_zero/type_expression，缺observe_after_type/press_enter/verify_result。没有预算250K导致的本轮停止；D25语法修正和B55参数拒绝分支均0。F21-05仍失败，146状态89/26/1/28/2不变。

D27先完成[Browser Use真实输入路径/完整MIT与最小设计](../target/e2e-20261004/github-references/d27-desktop-input-target-and-payload-reference.md)：当前观察动作位资格、完整原text语义与实际值差异准确反馈可独立适配；不采用其盲页焦点fallback或自动输入重试。候选仅现工具说明/目标能力错误分类/固定安全提示，不新增expectedPayload公共schema、不硬编码fixture、放宽WRITE/freshness/UNKNOWN或造proof。说明本身不能机械保证模型参数不改，尚未授权源码实施或GUI验证。七Personal事实及永久会话清理未发生。

## 补充批次1732–1736：真实停止、环境复原及B56/D27获授权

1732真正点击Stop1451439使Preview消失，主界面明确本轮失败8m36、179636输入7867输出、2消息/ctx187.5K/Send。1733随后才激活Calculator，经C及2/9/4真实恢复原294，Preview不复活；运行期间没有人为Calculator输入，终态后操作仅环境清理，不升级F21-05。证据[1732真正Stop及完整主界面结果](../target/e2e-20261004/evidence/1732-d26-new82-real-stop.png)、[1733随后原294复原](../target/e2e-20261004/evidence/1733-d26-after-stop-restored-calculator-294.png)。此前1731未提供最终in/out的边界保持，本次1732才补足实际读数。

1734实际托盘菜单→1735点击退出→1736正常资源清理完成，root确认IDEA16:33:42.328 exit0。证据[1734菜单](../target/e2e-20261004/evidence/1734-b56-before-normal-exit.png)、[1735退出操作](../target/e2e-20261004/evidence/1735-b56-normal-exit.png)、[1736资源完成](../target/e2e-20261004/evidence/1736-b56-exit-complete.png)。

root在实际GitHub-first D27研究后授权gepa为唯一源码作者，仅ManagedSession/DesktopSessionTools，独立/tmp/javaclaw-b56-*基线先保存：B56把原动作位不支持的拒绝分类为现FAILED/INVALID_TARGET/ModeNONE/NOT_SENT/dispatchfalse/NextOBSERVE，Tools保留待重新选择合法目标的reobserve反馈，原其它FAILED/DENIED/真正STALE路径保持；D27仅type说明/参数明确AX WRITE=2、PRESS=1、只读容器不适用、已有受限前台visual/坐标路径及保持完整文本、不用试字符，不把操作按钮当TYPE目标。无合法目标应反馈受限，不扩大权限/自动执行。源码就绪及独立实际增量审尚待，未编译或GUI回归；不加公共API、event reason、native/evaluator/expectedPayload字段，提示不等于机械payload约束。146状态89/26/1/28/2保持，事实/会话永久清理仍未发生。

## B56/D27最终源码冻结与独立策略终审

root及本文作者已分别实际读取两/tmp基线增量，并复核原HostEffectReceiptAdapter、DesktopToolPayloads、OnDemandDesktopPrerequisites和ComputerUseSessionCursor。ManagedSession缺动作位分支在platform.perform之前返回明确FAILED/INVALID_TARGET/dispatchfalse/NextOBSERVE，delivery仍NOT_SENT；可信ActionProof被现adapter映射为FAILED+NOT_SENT，不能满足WRITE或吞真实UNKNOWN。Tools仅FAILED+INVALID_TARGET+OBSERVE改为待观察文案/信号，其余FAILED/DENIED/真正STALE不动。原typed admission仍FAILED，与REOBSERVE展示不是同一层；Prerequisite依可信receipt nextStep=OBSERVE要求新观察，不要求成功或admission=REOBSERVE；Cursor精确matched+NOT_SENT不会误留UNKNOWN pending，不触发自动切前台。

D27增量仅type工具/参数注解，未修改实际text、注册schema字段、权限/审批、native/frame/像素/TTL、输入调度或验收，完整文本指导仍不等于机械payload绑定。已实际核对最终SHA256：ManagedSession `f831ec2ef2c5fc278fb00ded329f101f9deffd64f5863930692b5bb043de002b`，DesktopSessionTools `d240a9ac300ba7bc72fbe0d476e208512c81e5430c41e313f6da7516f66ea181`，patch `08ed22735d82713925a84517e1157b4be48b7da034197c177b76c4a5920a82f1`。见[两文件独立增量](../target/e2e-20261004/b56-d27-desktop-input-capability-source.patch)、[作者冻结记录](../target/e2e-20261004/b56-d27-desktop-input-capability-source-review.json)、[GitHub-first主源及独立适配](../target/e2e-20261004/github-references/d27-desktop-input-target-and-payload-reference.md)。差异检查干净，独立源审通过不计功能通过，编译/IDEA加载及原路径GUI仍待；146状态89/26/1/28/2保持。


## 补充批次1737–1740：B56/D27源码加载与新83原请求

[compile-b56-d27-1638.log](../target/e2e-20261004/compile-b56-d27-1638.log)实际16:38:00 BUILD SUCCESS、6.099秒、1487源码，仅-DskipTests compile。1737于16:39通过IDEA CtrlR从源码真实启动，B56/D27实际加载；首次CUA因用户切换应用未执行，不计启动，fresh AX后第二次才真正执行。1738于16:40新建第83空会话、83会话/0消息；1739a于16:45再次确认空页，1739原fixture全文实际输入，1740于16:45 Send、思考0.1秒/1消息，仍运行。证据[1737实际源码启动](../target/e2e-20261004/evidence/1737-b56-d27-idea-source-boot.png)、[1738新83空页](../target/e2e-20261004/evidence/1738-b56-d27-new83-empty.png)、[1739a空页复核](../target/e2e-20261004/evidence/1739a-b56-d27-new83-current.png)、[1739原请求](../target/e2e-20261004/evidence/1739-b56-d27-new83-original-request.png)、[1740发送](../target/e2e-20261004/evidence/1740-b56-d27-new83-send.png)。

编译、源码启动和发送只证明修复已加载并进入原路径，尚无本轮终态；不把目标能力反馈、完整文本输入或579验收记为通过。原新82失败与1732真正Stop、1733人工294复原、1736正常退出均保留，F21-05仍失败；146状态89通过/26修复后通过/1失败/28阻塞/2待验证保持，七Personal事实及会话永久清理未执行。


## 补充批次1741–1743：新83仅接管，仍在真实观察

1741于16:46 Calculator实时Preview为294，尚无操作；1742 root实际只点击前台接管1379439，预览进入自动前台操作中仍294。1743于16:47 native Calculator前台仍294，Preview暂隐藏不表示Stop；主UI显示工具已完成1m37、ctx25.1K、24681输入430输出，但Stop仍可用且模型继续运行。root没有输入Calculator。证据[1741实时预览](../target/e2e-20261004/evidence/1741-b56-d27-new83-preview.png)、[1742仅前台接管](../target/e2e-20261004/evidence/1742-b56-d27-new83-takeover.png)、[1743仍在运行](../target/e2e-20261004/evidence/1743-b56-d27-new83-observe.png)。工具条局部完成不能代替整轮终态，预览暂隐藏不计释放。

仅已保存的[新83安全摘要](../target/e2e-20261004/desktop-type-key-b56-d27-1740-safe-diagnosis.json)核对本轮chat6823df50-3e85-450b-a3e2-3190bd1c7fae/Runecbd3aa2-4fa7-403e-a6a2-2a1c4fae90db，创建16:40:12.685、Send16:45:29.678、原fixture精确唯一匹配。该首快照截止seq78仍在初始观察：可信目录26→targets44→open54，尚无当前strict review或kernel终态、click/type/key SENT均0；B56能力拒绝、D25语法修正及D26focused修正分支此快照均未触发。此为截至78的安全定位，不外推之后事件，也不读取原rollout。F21-05仍失败，B56/D27实际原路径效果待终态；146状态89/26/1/28/2及事实、会话清理状态不变。


## 补充批次1744–1751：原完整输入成功界面及真实停止

1744于16:48 native已为123+456、工具2m40、ctx53.7K、51907输入1794输出；1745仍Stop、3m23，但native表达式123+456及结果579实际出现。1746于16:49仍回复中4m26，正文给出的desktop sessionId为8e0f16a3-dc24-4e4b-8a11-b44421792d18；1747于16:50实际处理完成4m29、2消息/ctx92.7K、89239输入3480输出、Send恢复，完整成功报告与native579一致。root全程只前台接管，未输入Calculator。证据[1744原表达式](../target/e2e-20261004/evidence/1744-b56-d27-new83-after-clear.png)、[1745实际579仍运行](../target/e2e-20261004/evidence/1745-b56-d27-new83-result.png)、[1746回复中真实sessionId](../target/e2e-20261004/evidence/1746-b56-d27-new83-final-observation.png)、[1747真实处理完成](../target/e2e-20261004/evidence/1747-b56-d27-new83-terminal.png)。

1748激活Java时Preview没有自行抬前，但root只读SystemEvents确知JavaClaw·计算器窗口仍存在，不能当自动释放。1749 Raise该已知窗口后真实Preview579/自动前台/最近按键已受理，结束后显示过期属于此时状态，不倒推实际输入被拒。1750真正点击Preview Stop1451439，Preview消失；1751仅Stop后人工AC+2/9/4恢复native294，无Preview。证据[1748未抬前](../target/e2e-20261004/evidence/1748-b56-d27-new83-preview-terminal.png)、[1749已知预览Raise](../target/e2e-20261004/evidence/1749-b56-d27-new83-preview-raised.png)、[1750真正Stop](../target/e2e-20261004/evidence/1750-b56-d27-new83-true-stop.png)、[1751随后原294复原](../target/e2e-20261004/evidence/1751-b56-d27-new83-restore-294.png)。停止及人工复原不替代运行验收。

[精确新83安全摘要](../target/e2e-20261004/desktop-type-key-b56-d27-1740-safe-diagnosis.json)第二快照截止161核clear92→SENT95、TYPE118→SENT121完整7字符123+456/空elementId、KEY149→SENT152 ENTER各一次FOREGROUND/ACCEPTED/dispatchtrue，零proof110及表达式proof141六帧绑定。D26 focused135→136成功9.993秒，候选c7双层.96，进入141；B56能力拒绝/B55输入schema拒绝/D25语法修正分支均0。随后final1747最终精确核对212四字段CLAIM_DONE、220/222 VERIFIED_COMPLETE9/9/0unmet及223 COMPLETED16:49:59.108；九refs均同Run真实唯一且有序，最后167为579双.95/六绑定。原完整输入、真实579、保留Preview至1749及1750实际Stop共同完成本轮闭环，F21-05修复后通过；B56失败分类未触发仍不称已回归。

[截至1740组合83头信息](../target/e2e-20261004/session-header-manifest-1740-composed-readonly.json)、[76逐项owned来源](../target/e2e-20261004/cleanup-owned-76-proof-1740.json)、[GUI确认准备](../target/e2e-20261004/cleanup-gui-confirmation-preparation-1740.json)均为composed-not-freshDBsnapshot，不执行或授权删除，原七保护/owned76/unknown0、已删除0；当前仍须最终GUI逐项完整UUID核对，同标题及first8碰撞不可代替身份证明。七Personal事实仍未获新即时确认、未删，F23-05/06待。


## 新83最终严格验收：F21-05修复后通过

本文仅重读已保存的[最终安全摘要final1747](../target/e2e-20261004/desktop-type-key-b56-d27-1740-safe-diagnosis.json)，未读取rollout：同Runecbd3aa2-4fa7-403e-a6a2-2a1c4fae90db在212提交合法原四字段CLAIM_DONE，220及222均VERIFIED_COMPLETE、九项满足/零缺项，223于16:49:59.108正常完成。c1–c9的host receipt序为44 targets→54 open→84初始数字/窗口→95唯一clear→110零proof→121唯一完整123+456输入→141表达式proof→152唯一ENTER→167最终579。全部九项证据同Run且唯一匹配，输入均FOREGROUND_SYNTHETIC/ACCEPTED/SENT/dispatchtrue，NOT_SENT0；原一次clear要求实际满足，没有其它实际输入或重复派发。ACCEPTED本身仍effect UNKNOWN，逻辑0、表达式及579各由其后的真实观察证明，不借受理状态冒认效果。

最终167 c9双层.95、六帧字段全部匹配；135→136 focused修正9.993秒输出仅conditionEvidence、双层.96，首OCR锚定后进入141且最终strict确实选中c7。合法CLAIM及strict证据结合1747实际成功、1749仍存Preview579和1750真正Stop，按原路径判F21-05修复后通过。正常GEPA mode=model、needsRevision=false，不是B45独立30秒超时分支；B56能力拒绝/B55参数前置拒绝/D25 INVALID_JSON修正本轮均0，保持这些异常分支未覆盖。D27完整文本正常路径已通过，但说明不是机械payload约束，不外推所有请求可靠性。

最终清单146项：89通过、27修复后通过、0失败、28阻塞、2待验证。F23-05/06剩余清理及最终重启尚未完成，七Personal事实无新即时确认未删，76owned会话来源只是最终GUI核验准备，原七保护。1751人工恢复294仅终态后清理，旧失败全部留作历史证据。


## 补充批次1752–1768：原设置与资源收尾核对，Personal范围为12

1752误用未展开菜单的旧坐标，实际仅切到已owned7ed719d8历史E2E会话；其中旧400是历史失败，不是新Bug，也未打开设置。1753真正展开profile；1754首次auto-review deadline未执行，允许一次retry后才实际打开设置loading；1755稳定loaded为DashScope/qwen3.8flash/API key MASK、思考ON4096原值。1756 Embedding OFF/空API/textembedding3small/1024/5/.3；1757外观导航、1758 Emerald选中、1759 Native字体/SystemMono/适中密度，均仅查看，无任何save。证据[1752实际历史会话](../target/e2e-20261004/evidence/1752-final-settings-model.png)、[1753真实菜单](../target/e2e-20261004/evidence/1753-final-management-entry.png)、[1754设置loading](../target/e2e-20261004/evidence/1754-final-settings-model-restored.png)、[1755原模型稳定值](../target/e2e-20261004/evidence/1755-final-model-config-confirmed.png)、[1756嵌入配置](../target/e2e-20261004/evidence/1756-final-embedding-config.png)、[1757外观导航](../target/e2e-20261004/evidence/1757-final-appearance-navigation.png)、[1758原主题](../target/e2e-20261004/evidence/1758-final-theme-density.png)、[1759原字体/密度](../target/e2e-20261004/evidence/1759-final-font-restored.png)。菜单未展开、loading及自动审批未执行均不作为设置操作成功。

1760 Schedule仅原系统“命令会话清理/习惯回顾”两项enabled、无本轮临时任务；1761托管0running/0待人工/0all；1762 MCP0/0tools；1763工作流仅原SDD托管编排和循环编排两个builtin。证据[1760原调度](../target/e2e-20261004/evidence/1760-final-schedules.png)、[1761托管已清零](../target/e2e-20261004/evidence/1761-final-managed-projects-empty.png)、[1762 MCP清零](../target/e2e-20261004/evidence/1762-final-mcp-empty.png)、[1763仅系统工作流](../target/e2e-20261004/evidence/1763-final-workflow-builtins.png)。这是本次源码重启后的真实复核，但不是会话/事实清理完成后的最后重启，不提升F23-06。

1764 memory仍loading不据此判空；1765实际Personal当前12事实，情景/实体/文档0。1766–1768逐项UI显示旧7条及新5条Calculator用户纠错，均本轮后续fixture衍生；当前删除范围已变，不能沿用旧7事实审批或称事实已清零。root已请cleanup agent制作12项精确source proof，尚待落地，不从计数差值或相似内容猜身份。永久delete没有执行，缺action-time specific human reply仍成立；原workspace人工Fact1保留。证据[1764 loading概览](../target/e2e-20261004/evidence/1764-final-memory-overview.png)、[1765实际范围选择](../target/e2e-20261004/evidence/1765-final-memory-scope-selector.png)、[1766 Personal顶部](../target/e2e-20261004/evidence/1766-final-personal-facts-top.png)、[1767中段](../target/e2e-20261004/evidence/1767-final-personal-facts-middle.png)、[1768末段](../target/e2e-20261004/evidence/1768-final-personal-facts-bottom.png)。

83头信息/76owned会话尚未最后GUI精确选择和永久删除，Personal12尚未删除，也尚未最终正常退出并从IDEA源码重启；F23-05/06当前待验证，交付前按实际完成或具体阻塞改为通过/阻塞，不将“待”作为最终结论。146状态保持89通过/27修复后通过/0失败/28阻塞/2待验证；F21整链已实际通过，B56异常分支0不把整链退回失败，也不冒称该异常分支回归通过。


## 补充批次1769–1776：正常重启无预览复活，删除仍未执行

1769托盘菜单→1770点击退出，IDEA AX实际17:06:22.251资源清理完成/exit0；1771正常退出后JavaClaw main和Preview均无窗，Calculator294仍在，截图Codex遮console不能据此补造控制台文字。1772实际Ctrl+Super+F恢复原全屏并完整截图console资源清理/exit0。随后fresh AX CtrlR真实Launcher源码boot17:08:49.758；1773于17:09主界面83会话、最新owned83完整2消息/ctx92.7K、右waiting0/Send，无Preview或自动复活。1774真实滚动，正确desktop sessionId及原0→123+456→579完整报告保持。证据[1769正常退出菜单](../target/e2e-20261004/evidence/1769-final-normal-exit-menu.png)、[1770退出动作](../target/e2e-20261004/evidence/1770-final-normal-exit.png)、[1771无运行窗](../target/e2e-20261004/evidence/1771-final-exit-zero.png)、[1772原全屏及完整console](../target/e2e-20261004/evidence/1772-final-idea-fullscreen-restored.png)、[1773实际源码重启](../target/e2e-20261004/evidence/1773-final-idea-source-reboot.png)、[1774正确完整报告持久化](../target/e2e-20261004/evidence/1774-final-success-session-persisted.png)。

1775打开会话管理，1776 E2E_搜索仍0selected，永久删除没有执行。证据[1775管理](../target/e2e-20261004/evidence/1775-final-session-management.png)、[1776仅搜索未选择](../target/e2e-20261004/evidence/1776-final-session-e2e-filter.png)。本次重启证明已清理运行资源持久化及预览不复活；76owned会话和12Personal事实尚未获当时明确human确认并删除，F23-06要求清理后的最后重启，因此仍未完成，不据本次重启提升两项。

[Personal12逐项组合归属及限制](../target/e2e-20261004/cleanup-personal-12-proof-1768.json)已落地：3habit+9explicit，共12；旧7原文持续保留、新5完整文字及显示日期与本轮1500/1629保存prompt对应。初始Personal事实数为unknown、没有确切允许baseline，不推初始0来证明归属；显示更新时间只作对应线索，不等于已核创建时间或数据库lineage。fact IDs和直接sourceSessionId不可见，证明明确不伪造，最终须按Personal范围/分类/完整文本及日期逐行GUI核对，不能只凭12计数或使用清空。原workspace人工Fact1 KEEP且与Personal项独立保护；新未知或变化行须另核。此文件是保存截图/owned fixture组合而非新DB快照，无GUI删除、人类当时确认或最终清理声明。

146状态仍89通过/27修复后通过/0失败/28阻塞/2待验证。F21正常完整输入回归和重启报告保持均有真实证据；B56异常分类及B55/D25未触发分支仍未覆盖，不从重启改其结论。F23-05/06交付前须根据最终明确阻塞或实际闭环转为四种最终状态之一。


## 清理审阅缺口D28：短标识不能区分派生会话

本文仅读当前SidebarSessionListController确认分支及过滤/选择逻辑：selectedDetails使用shortId，而该helper只返回前8位；当前组合manifest记录9个前缀4c085e40的派生标识及2个前缀schedule碰撞。完整业务selectedIds仍取真实id，不能据此声称已删除错对象；问题是用户确认正文不能唯一审阅exact76范围，阻断安全清理闭环。现F03-06两精确会话的原取消/真删除回归结论不被倒推成未测，本次是全批派生标识审阅缺口。

现有过滤按title contains，SelectAll遍历全部filteredSessions、包括滚屏之外；修改search会清所有selection，不支持跨search累积。批量模式行/checkbox只切换checked状态，不打开会话正文；清理必须使用当前准确选择及完整标识明细，不能用总数、同标题、前8位或多个搜索的历史选择拼接批准。父代理已委派gepa实际GitHub primary/Cline或LibreChat或Cherry相关实现及许可证研究，暂无源码更改；候选仅1文件补完整审阅标识，不新增业务删除接口或自动执行，原selection/owner/不可恢复确认与human action-time要求保持。F23-05/06仍待，76会话及12事实未删除。来源准备见[组合manifest及碰撞](../target/e2e-20261004/session-header-manifest-1740-composed-readonly.json)、[GUI确认准备限制](../target/e2e-20261004/cleanup-gui-confirmation-preparation-1740.json)。


## 补充批次1777–1780：短标识碰撞实机复现及取消

1777真实E2E_过滤SelectAll68、总83；1778只打开确认，实际条目仍前8位UUID；1779滚至底部，多个4c085e40及schedule前缀相同，无法唯一审阅具体派生范围。1780真正Cancel、取消全选、退出Manage，83会话保留，永久删除未发生。证据[1777实际68项选择](../target/e2e-20261004/evidence/1777-final-filtered-temporary-selection.png)、[1778确认短标识](../target/e2e-20261004/evidence/1778-d28-short-id-confirmation.png)、[1779底部碰撞](../target/e2e-20261004/evidence/1779-d28-short-id-collisions.png)、[1780真实取消保留](../target/e2e-20261004/evidence/1780-d28-review-cancelled.png)。68只是本次过滤选择，不能冒认全部76owned已核，更不能只凭数字执行永久删除。

作者/root已实际阅读[GitHub-first完整出处与许可](../target/e2e-20261004/github-references/d28-deletion-identity-reference.md)：LibreChat完整delete dialog/keyboard冻结同target、MIT；Cline明确原ID数组modal确认、Apache2。实际参考UI只显示标题/数量，没有伪称其已提供本轮完整UUID列表；只采用确认目标与真实删除目标一致的原则，独立补JavaClaw已有显示，未复制外部实现。root授权仅SidebarSessionListController一个文件，selectedDetails用现normalizedTitle+Objects.toString原完整id、null静态“ID 缺失”，删仅此调用的private shortId。

本文作者实际对/tmp/javaclaw-d28-SidebarSessionListController.java逐项diff及现onBatchDelete/过滤/selection/UIHelper阅读，独立静态审通过：同selectedSessions生成原完整selectedIds不变，搜索清选/全选范围/owner/OK callback/CANCEL全保持；原wrapText/fitWidth/全文换行高度/纵向scroll/max320支持长明细，无通用布局或新公共删除API变更。实际sourceSHA `bab9ed682d5f2671e32eeeb4d272086c56057968f8834e322fc3d7df1d159895`，baselineSHA `5aaaae98e7999167496d847eb2a5112a1872186262948d2275acb3928998e4ca`，patchSHA `380ec37511e7a2c3709a17e453d4d72d15527df61b10cdca8c2f6cf384b57a6c`，与[一文件增量](../target/e2e-20261004/d28-deletion-full-identity-source.patch)及[作者ready记录](../target/e2e-20261004/d28-deletion-full-identity-source-review.json)一致。diff check干净，但真实长复合ID排版/原68确认路径仍需IDEA源码加载后GUI复核；静态通过不计功能通过，尚无编译或新GUI结论。

F23-05/06仍待，永久会话删除及Personal12删除没有执行，原七会话及workspace人工1保护。146状态89/27/0/28/2保持；待原列表完整标识GUI回归及action-time human明确确认，不扩其它实现或删除权限。


## 补充批次1781–1782：D28正常退出及编译，GUI回归待

1781真正托盘菜单、1782实际退出，IDEA AX确认17:27:20.608资源清理完成/exit0。证据[1781退出菜单](../target/e2e-20261004/evidence/1781-d28-precompile-exit-menu.png)、[1782正常退出](../target/e2e-20261004/evidence/1782-d28-precompile-normal-exit.png)。D28已root和独立实际增量审通过，父代理执行[compile-d28-1728.log](../target/e2e-20261004/compile-d28-1728.log)，实际2026-10-05 17:28:12 BUILD SUCCESS、6.470秒、1487源码，仅-DskipTests compile。

当前父代理从IDEA源码加载，尚无已观察主窗或原68项完整ID显示/滚动/取消回归终态；编译不计GUI通过。永久会话及Personal12事实删除未执行、缺当时明确human确认仍保留，F23-05/06最终清理不通过推定。146项89通过/27修复后通过/0失败/28阻塞/2待验证保持。


## 补充批次1783–1810：D28完整身份显示及取消原路径通过

1783通过IDEA最新源码加载D28；1784 E2E_过滤零选择、1785真正选68。1786确认顶部完整ID，1787–1797连续scroll01–11至最后858f118e-35f1-4973-ac49-26395b03156a底部，完整复合ID换行/滚动可读。独立同行已逐保存截图审计，[68项GUI完整身份审计](../target/e2e-20261004/cleanup-gui-confirmation-68-identity-audit-d28-1797.json)记录68个distinct complete IDs、68 exact owned matches、unknown/protected/missing/outside/uncertain均0，实际集合与对应owned68精确相等；不是数量相等推身份，部分边缘截断不单独提升为完整证据。审计只读保存截图和此前组合来源，未读DB、Run或private正文、未OCR/代码测试/操作GUI。证据[1783源码加载](../target/e2e-20261004/1783-d28-idea-source-boot.png)、[1784零选择](../target/e2e-20261004/1784-d28-filtered-zero-selection.png)、[1785选68](../target/e2e-20261004/1785-d28-filtered-selected-68.png)、[1786完整顶部](../target/e2e-20261004/1786-d28-full-id-confirmation-top.png)、[1797真正底部](../target/e2e-20261004/1797-d28-full-id-confirmation-scroll-11.png)；中间scroll01–10逐页文件及完整ID证据均列在审计pageTranscripts，不虚构遗漏页。该批新截图实际位于target/e2e-20261004根，引用真实位置。

筛选外8个本轮owned由root另行真实搜索/零选中/确认完整ID：1799 worker、1801 system-loop、1803 loop root、1805三个workflow镜像、1807 schedule、1809初始blank；相邻1798/1800/1802/1804/1806/1808搜索均零选中后才逐组确认，不能跨搜索累积选择。证据[1799 worker](../target/e2e-20261004/1799-d28-owned-worker-full-id-confirmation.png)、[1801 system-loop](../target/e2e-20261004/1801-d28-owned-system-loop-full-id-confirmation.png)、[1803 loop root](../target/e2e-20261004/1803-d28-owned-loop-root-full-id-confirmation.png)、[1805三个workflow](../target/e2e-20261004/1805-d28-owned-workflow-three-full-id-confirmation.png)、[1807 schedule](../target/e2e-20261004/1807-d28-owned-schedule-full-id-confirmation.png)、[1809初始blank](../target/e2e-20261004/1809-d28-owned-initial-blank-full-id-confirmation.png)。上述8项以root实际完整身份复核为主，不把68审计范围偷扩至后来改变的selection。

1810所有确认均取消、退出为normal模式、83会话完整保留；未点击任何OK永久删除、没有事实删除。证据[1810所有取消保留83](../target/e2e-20261004/1810-d28-all-confirmations-cancelled-83-normal-mode.png)。D28原路径展示完整ID、连续滚动、可审阅碰撞派生身份及取消全部实际通过；业务删除执行未发生，不claim actual deletion。F03-06已有修复后通过行关联此显示修复说明，不新增验收行或重复增“修复后通过”数量。

## 当前收尾阻塞：仅具体永久删除确认及删除后重启

当前83会话中原7精确保护，76本轮owned已由组合逐项来源及68+另8实际完整ID确认交叉核对；Personal12逐文本/显示日期核对且原workspace人工Fact1排除，[12事实范围限制](../target/e2e-20261004/cleanup-personal-12-proof-1768.json)不推初始Personal0、不伪造不可见fact ID/lineage。自动审批曾对不可逆即时删除要求当时具体确认，当前仍缺human针对这些76会话和12事实的明确action-time reply；此前笼统继续/授权不能被当成本轮具体不可逆范围确认。root将提交具体可审范围询问，未获确认不执行永久删除。

因此F23-05标阻塞（只剩确认后本轮临时会话/事实清理），F23-06标阻塞（依赖前项完成后的正常退出、IDEA源码重启核清理持久化）。其它运行资源已清理、设置原值及正常退出/报告保留/无Preview复活已有实机证据；不重造或重复测试已验证资源。最终四状态统计146：89通过、27修复后通过、0失败、30阻塞，其中28既有依赖阻塞及2临时human确认阻塞。没有待验证行；后续若用户明确确认并实际完成清理与最后重启，按新GUI事实更新这两项，不先假设结果。


## 补充批次1811–1819：12事实当前复核及最终96行范围表

IDEA AX补足本轮D28实际启动：17:31:56.943工作区默认4c085e40，17:31:57.334启动，17:32:00.432主窗口1200×700及启动完成，与[1783源码boot截图](../target/e2e-20261004/1783-d28-idea-source-boot.png)对应，不从此前编译时间或截图名推造启动秒数。D28展示/滚动/取消回归结论保持。

1811菜单，1812记忆loading不能据此判空；1813真实Personal当前12概览（已改名，旧workspacekeep文件不存在、不引用），1814顶部/1815中段/1816底部逐项同12完整文本，3habit+9explicit现存；P04 Markdown的显示更新时间变为17:31，是当前更新时间，不重写成新建时间或新的事实。证据[1811菜单](../target/e2e-20261004/1811-d28-profile-menu-before-memory.png)、[1812 loading](../target/e2e-20261004/1812-d28-memory-current-scope-entry.png)、[1813 Personal12概览](../target/e2e-20261004/1813-d28-memory-personal-current-12-overview.png)、[1814顶部](../target/e2e-20261004/1814-d28-personal-current-facts-top.png)、[1815中段](../target/e2e-20261004/1815-d28-personal-current-facts-middle.png)、[1816底部](../target/e2e-20261004/1816-d28-personal-current-facts-bottom.png)。

1817/1818只读取scope dropdown，实际列表仅Personal+session，没有选择其它范围；不因1818文件名含workspace而称重新验证原workspace人工1。1819 Esc关闭记忆，83会话、无管理选择、无删除。原workspace人工Fact1 KEEP只是先前归属保护边界/本轮未操作，不能写成本次GUI重核1；原7会话同样保护。证据[1817下拉只读](../target/e2e-20261004/1817-d28-memory-scope-selector.png)、[1818未切范围](../target/e2e-20261004/1818-d28-memory-workspace-scope-option.png)、[1819全部关闭未删除](../target/e2e-20261004/1819-d28-cleanup-preparation-done-no-deletion.png)。

[最新最终审阅范围CSV](../target/e2e-20261004/cleanup-final-review-scope-d28-1816.csv)已落地，本文只读核96数据行（不含表头）：76会话DELETE候选+7原会话KEEP+12Personal事实DELETE候选+1原workspace人工事实KEEP；总88行标DELETE_CANDIDATE_REVIEW_ONLY、8行KEEP，不是执行清单授权或删除结果。它列当前完整session IDs/GUI证据、事实完整文本/显示日期及来源对应，事实数据库IDs/直接lineage缺失不伪造；不得仅按96或88数字跳过实际范围审阅。新的[Personal12当前证明](../target/e2e-20261004/Personal12-proof-d28-1816.json)已落地，asOf1816、12同文本/无新增或缺失，只P04更新时间由16:39变17:31；既有[1768证明及限制](../target/e2e-20261004/cleanup-personal-12-proof-1768.json)保留历史asOf及原hash，不静默重写旧日期。当前证明明确12个fact IDs及直接sourceSessionIds均unknown，不能造身份或把日期对应当数据库lineage。

仍缺specific human reply：永久删除已逐完整ID核76本轮会话及其现有消息/Run轨迹/threadmemory级联清理，以及12本轮个人测试事实；不扩展到原7、原workspace人工1、未知scope或其它用户数据。此前1493自动审批拒绝不可逆即时fact删除、未执行，所以先前“继续/泛授权”不等于此次76+12的当时具体确认。当前F23-05/06仅该确认与删除后最后正常退出/IDEA重启阻塞，没有新永久删除、事实0或清理完成声明。146四状态89通过/27修复后通过/0失败/30阻塞保持。


[完整76 GUI身份组合证明](../target/e2e-20261004/cleanup-gui-confirmation-76-identity-proof-d28-1810.json)已将68和另8逐取消确认的实际完整ID合并，只做review准备：76独立完整GUI IDs与此前逐项owned证明集合精确相等、duplicate/unknown/protected/未见/uncertain均0，原7 KEEP且与owned无交集；这是多次已取消选择的审计合并，不代表当前selection跨搜索累积或最终删除授权。[当前Personal12证明](../target/e2e-20261004/Personal12-proof-d28-1816.json)和[96行当前范围CSV](../target/e2e-20261004/cleanup-final-review-scope-d28-1816.csv)亦完成只读artifact回查：76候选、7KEEP、12候选、1KEEP结构一致；113个不同文件引用均存在，语义性parent确认标记不误当文件，历史1768hash不改。所有证明finalCleanupClaimed/deletionExecuted均false，不扩scope，不新DB/Run读取，不运行代码测试。


## 2026-10-05至2026-10-06 嵌入续验当前结论

真实配置为OpenAI API、`http://127.0.0.1:1234/V1`、`qwen3-embedding-0.6b-dwq`、1024维、TopK5、阈值0.3。Default真实探测1024维/309ms，知识导入、重建、语义检索及聊天均使用真实模型；不运行测试用例、模拟服务或直接业务接口代替GUI。各源码修复由IDEA精确应用、编译、正常退出后Launcher源码启动加载，编译不作为功能通过证据。

| 功能 | 最终已观察结果 | 当前结论 |
|---|---|---|
| F17-06嵌入设置 | 同窗localhost保存→1024维/472ms绿色，再恢复127→174ms绿色；另一模型草稿4097保留，拒绝关闭/明确丢弃重开4096，重开原地址178ms及真实双区知识范围正常 | 修复后通过 |
| F09-02文本、F09-03文件/目录 | WORKSPACE/GLOBAL/TEMP文本真实成功；项目外拒绝有安全原因，项目内TXT成功；PDF、Markdown和既有目录入口一次导入两个文件均成功。独有PDF84%、MD83%、目录TXT86%/MD85%语义命中 | 文本通过，文件/目录修复后通过 |
| F09-04/05知识详情与检索 | 搜索有匹配/无匹配/清空恢复，片段、范围开关、正文及窄抽屉换行滚动实际可用；重复同TXT两轮稳定5片段/default3文档7片段，失败保留旧版；真实重建7片段和语义来源/分数 | 修复后通过 |
| F02-03工作区隔离 | TEMP工作区知识及会话不混入Default，GLOBAL可共享；实际往返切换与检索正常。按原条款不另增加记忆前置 | 修复后通过 |
| F09-06知识聊天 | 合法规划及真实普通knowledge_search、Completed28.7秒/8021tokens；GLOBAL-only21.4秒只返回GLOBAL；allOFF31.5秒明确无参与文档/无资料正文；仅TEXT重启用19.4秒准确返回独有事实 | 修复后通过；未外推可点击citation |
| F09-07索引/删除 | End→Home不重点击256→0保存并保焦点；Tab不夺焦；128块最大重叠64、1024/25625%、缩128夹64；准确512/50关闭重开保持。本轮PDF拒绝保留6/10，再同意删除5/8，正常查询全5来源DIR_MD/WS_ONLY/GLOBAL/DIR_MD/MD无PDF，剩余MD79%可检索 | 修复后通过；五条其他文档语义命中，不是零命中 |
| F10-03编辑、F10-07手动回填 | MEM29及新MEM30两轮取消再编辑正确；真实provider收到MEM30全文返回1024维。C2持续真实服务不可用Pending1，同Memory窗恢复健康后手动立即回填Toast1、全正式，日志同C2 PR…；辅助4已精准物理批删 | 编辑原修复后通过保持；手动回填通过 |
| F10-04恢复/删除 | 原合格MEM30↩恢复/日志/重开，MEM31单删拒绝2/同意1及四辅助精准批删完成；B66新原提示真实83a6…/3d95…COMPLETED62.36秒22961tokens，合法planfalse/RESOLVED，普通recall contexttrue/SUCCEEDED240ms含独有MEM31；新A/B软状态、再↩恢复及重开两formal实际保持 | 修复后通过；旧run668d…PAUSED及新run中间orchestration失败恢复保留，非全部步骤无失败 |
| F23-04持久化与退出 | B67真实Pending1→重开自动晋升→退出0→02:15源码重启formal13/保护保持→pin/unpin→样本精确删回原12；最终清理后02:23退出0、IDEA源码重启Main84/仅Default/Personal12无样本 | 修复后通过；旧76/12历史删除及其删除后重启另列F23-05/06阻塞 |

第三次失败session `304c4f77-7887-4697-bb6f-64c752b2a333` / run `668d4fed-cb46-4f42-8006-85c8b71f1ba5` 原PAUSED保留：[真实结果](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-temp-original-result-selected.png)。六个实际工具只有普通memory_recall的trustedContextRead/catalog均false，not-applicable分类误判；B66精确修复后新run3d95…已真实COMPLETED，但不据此改变旧PAUSED。具体六工具、源码SHA和回读见 [本轮增量](../target/e2e-20261005/embedding-followup/reports/javaclaw-embedding-followup-evidence-increment-20261005.md)。

原失败诊断的纠正导出已由root实际GUI核实同源：[原Run结果](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-temp-original-run-result-ui.txt)、[原pure-answer规划](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-temp-original-plan-output-ui.txt)、[有效应用Step截图](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-temp-original-first-model-step-valid.png)、[六工具来源审计](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b66-original-run-ui-provenance-20261006.json)。旧错误复制、错前景ChatGPT图、无效MemoryExpert与512/52冒称恢复图排除；详见增量记录。

B66新原链路真实证据：[COMPLETED根与IDs](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-b66-execution-root.png)、[真实答复](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-b66-original-correction-final.png)、[合法plan导出](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b66-plan-output-ui.txt)、[普通recall输入contexttrue](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b66-memory-recall-input-ui.txt)、[实际输出](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b66-memory-recall-output-ui.txt)、[Memory软状态](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-b66-memory-soft-state.png)、[再次恢复](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-b66-memory-restored.png)、[重开两formal](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-b66-memory-restored-reopen.png)、[来源及边界](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b66-success-run-ui-provenance-20261006.json)。原失败合法plan和旧memory标记仍留存；新工具总数未声称，后半段截图名不当完整最底证明。

B67完整有效样本证据：[真实Pending1](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-real-owned-pending1.png)、[重开自动回填](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-refill-ready-after-reopen.png)、[正式13](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-owned-promoted-formal.png)、[正常退出0](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-promotion-normal-exit0.png)、[正确源码重启formal](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-promoted-after-source-restart-valid.png)、[置顶](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-formal-pin-after-restart.png)、[取消置顶](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-formal-unpin-after-restart.png)、[仅marker删除](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-owned-final-delete-confirm.png)、[原12保留](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-original12-after-owned-delete.png)、[误建空会话直接删除后的84](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-owned-empty-session-delete-confirm.png)、[健康及原51250](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-final-default-index-health512-50.png)。首样本直接formal已单独判无效、未计Pending晋升；未带valid的重启截图实际Main85不当formal证据，文件名不代替画面。

### 问题、修复与验证边界

| 问题 | 最小实现与原路径回归 | 修复源码记录 |
|---|---|---|
| B57项目外导入只显示失败 | 沿用ProjectAccessPolicy，将既有安全原因交给UseCase逐文件反馈；项目外明确拒绝、项目内成功，不放宽路径权限 | [补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-b57-knowledge-import-permission-feedback.patch) |
| B58片段及检索正文裁切 | 现有JavaFX卡片宽度/换行调整，原预览及窄结果回归可读 | [补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-cell-width.patch) |
| B59同文档重复追加片段 | 真实向量先暂存、按现有同docName范围替换；两次导入稳定5块，真实失败保旧文。未证明磁盘事务或任意故障补偿 | [补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b59-staged-replace.patch) |
| B60正常切区GLOBAL运行时冲突 | Kernel先关闭旧runtime再建目标；双向切区数据和真实检索保持，不误称原列表0数据丢失 | [补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b60-kernel-lifecycle.patch) |
| B61可信只读工具误判及取材规划缺失 | Evaluator只豁免准确可信KnowledgeExpert只读谓词；Compiler明确已有知识取材规划材料，运行期门禁继续有效；合法plan、普通tool及Completed原两段通过，内联取材不冒充helper回归 | [验收补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b61-knowledge-answer-read.patch)、[规划补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b61-answer-planning.patch) |
| B62设置保存后同窗探测失效 | SettingsView在实际关闭后合并runtime刷新；两轮同窗真探测、保留其它分区草稿、拒绝/丢弃重开及双区知识回归通过。没有回调计数证明 | [补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b62-settings-lifecycle.patch) |
| B63普通知识工具忽略参与检索开关 | ordinary search按当前enabledDocs，null/空集合含义精确分离，管理列表不改；GLOBAL-only、allOFF明确拒绝、REENABLE正文真实回归通过 | [补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-knowledge-chat-scope.patch) |
| B64取消编辑保留草稿 | 进入编辑时从当前FactItem重置一行，不持久化取消草稿；两轮MEM29/MEM30取消原路径通过 | [补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b64-fact-editor-cancel.patch) |
| B65键盘未保存、超界及保存失焦 | Controller/FXML按实际调整键保存、动态min(256,chunk/2)限值；最终保存期间数值事件过滤保留焦点并允许Tab，不requestFocus；源码加载后原及边界回归完整通过 | [参数补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b65-chunk-settings.patch)、[最终焦点增量](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b65-focus.patch) |
| B66内置只读memory_recall误当业务工具 | Gateway仅精确内置recall身份、幂等/LEGACY及{tool.read}标为可信上下文，保留普通异步执行/审批/权限/receipt及自动纠错写入口；01:06 IDEA911ms一vector警告构建及源码加载，新原提示COMPLETED62.36秒、普通recall contexttrue/SUCCEEDED240ms、实际纠错/↩/重开原路径修复后通过 | [补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b66-memory-recall-context.patch)、[源码回读](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-DefaultToolInvocationGateway-b66-applied.java)、[静态边界](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b66-static-review.txt)、[独立审阅](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b66-independent-review.txt)、[编译](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-b66-build.png) |
| B67重启晋升实体字段未持久化 | v3晋升显式store实体+open真实map/index仅存储元数据恢复；编译、首恢复12正常启动、真实Pending1→重开自动晋升→退出0→02:15源码重启formal13/保护保持→pin/unpin→唯一样本精确删回12，误建0msg会话删回84；最终RAG健康/原51250通过。清理后最后02:23正常退出0/IDEA源码重启Default84/onlyDefault/Personal12已实机确认 | [补丁](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-b67-promotion-persistence.patch)、[applied](../target/e2e-20261005/embedding-followup/source-changes/javaclaw-embedding-MemoryStore-b67-applied.java)、[恢复12诊断](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-first-start-diagnostic-excerpt.txt)、[真实Pending1](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-real-owned-pending1.png)、[重启formal](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-promoted-after-source-restart-valid.png)、[样本清理原12](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b67-original12-after-owned-delete.png)；F23-04修复后通过 |

实现沿用JavaClaw4.0既有UI→application用例/ports→infrastructure持久化及统一Framework执行，未通过数据库造状态或改门禁/timeout。静态审查、源码应用回读、IDEA实际编译与每条GUI复现/回归时点均在 [完整增量记录](../target/e2e-20261005/embedding-followup/reports/javaclaw-embedding-followup-evidence-increment-20261005.md)，不把候选/编译当通过。

### GitHub参考与证据

B57参考Cherry Studio实际导入错误反馈，AGPL-3.0仅独立适配理念：[来源记录](../target/e2e-20261005/embedding-followup/github-references/cherry-knowledge-import-error-feedback-reference-20261005.md)。B59参考LlamaIndex/LangChain实际同身份更新，MIT：[来源记录](../target/e2e-20261005/embedding-followup/github-references/b59-knowledge-reimport-dedup-reference-20261005.md)。B61规划材料参考LangChain retrieval-agent-template主项目实际graph/prompts，MIT：[来源记录](../target/e2e-20261005/embedding-followup/github-references/javaclaw-embedding-b61-planning-github-reference-20261005.md)。B63参考OpenWebUI实际attached retrieval范围与空集合处理，其许可含品牌条件，未按无条件MIT复制：[来源记录](../target/e2e-20261005/embedding-followup/github-references/javaclaw-embedding-knowledge-scope-github-reference-20261005.md)。各实现独立适配现有架构；现有目录入口可正常使用，不作为缺陷扩建。

关键证据：[普通知识工具输出](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b61-recheck-knowledge-output-ui.txt)、[GLOBAL-only真实工具](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b63-global-tool-output-ui.txt)、[allOFF真实拒绝](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-b63-none-tool-output-ui.txt)、[重开512/50](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-b65-restore-actual-reopen512-50.png)、[删除PDF后正常检索](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-owned-pdf-deletion-retrieval-healthy.png)、[全5来源底部](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-owned-pdf-deletion-retrieval-healthy-bottom.png)、[同窗手动回填](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-manual-refill-completed.png)、[MEM30恢复](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-temp-memory-soft-restored.png)、[纠错记录](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-temp-memory-correction-record.png)、[恢复日志](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-temp-memory-restore-log.png)。所有其它逐项截图与来源见增量报告及包manifest，截图根代理实际查看，报告代理仅核存在/链接。 记忆完成证据：[恢复后重开](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-temp-memory-restored-reopen.png)、[MEM31精确删除范围](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-temp-memory-owned-b-delete-dialog.png)、[拒绝保留两条](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-temp-memory-b-delete-rejected-retained.png)、[同意删除只余MEM30](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-temp-memory-b-deleted-a-retained.png)。

### 当前数据与收尾

本轮新增8独立知识对象已全部逐项精确删除，Default/TEMP最终各0docs0chunks；新回归事实及sole纠错均精准清理。TEMP4专用会话完整ID核对后删除，自动01:34空会话随E2E_EMB_WS_6729_20261005精确删除；Default新5会话及B67误生空会话精准删除后实际84，菜单仅Default。人类18:17会话及旧76/12历史删除审批范围保护边界保持。Default额外3以全文ADD唯一且原12基线不存在准确归属，逐条15→14→13→12；B67唯一晋升样本也已删回12。原12全部正文保留；自动回填已正式、P04随正常MERGE更新时间变化，不能称状态/元数据完全未变。

索引准确512/50与Default用户原模型、用户新embedding基线保持。LM Studio已Discard unsaved恢复原Thinking ON并仅Eject临时9b，原embedding READY/server Running/脱敏ON保持，[Thinking恢复](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-lm-thinking-discard-restored.png)、[只原embedding就绪](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-lm-original-only-restored.png)。Default RAG真实正常已连接、qwen3-embedding-0.6b-dwq1024/COSINE/endpoint1234、51250、全scope0doc0chunk0enabled已经确认。

清理后最后正常托盘退出02:23:36实际资源清理完成、进程退出0；IDEA源码启动02:23:54主窗84，菜单仅Default，Memory Facts12（原3habit+9explicit）无样本，原正文保留。最终Runlog只纳最小去敏启动完成事件，完整28583B raw日志不交付。P04普通MERGE更新时间02:23、正文相同，不能称所有metadata不变。 [清理后正常退出0](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-final-cleanup-normal-exit0.png), [IDEA源码重启Main84](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-final-cleanup-source-restart-main84.png), [仅Default/84](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-final-cleanup-only-default84.png), [Personal12重启顶部](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-final-cleanup-restart-personal12-top.png), [最后启动最小诊断](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-final-cleanup-startup-diagnostic-excerpt.txt)。F23-04升级修复后通过，主表90/36/0/20；旧76/12具体历史永久删除确认及其删除后的重启仍是F23-05/06两项阻塞，不能把本轮新增对象清理等同旧范围清理。

最新清理范围证据：[新事实0](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-b66-owned-facts-zero.png), [新纠错0](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-b66-owned-corrections-zero.png), [TEMP知识0](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-cleanup-temp-kb-zero.png), [Default知识0](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-cleanup-default-kb-zero.png), [TEMP4会话精确范围](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-cleanup-temp-four-sessions-confirm.png), [Default5精确范围](../target/e2e-20261005/embedding-followup/evidence/javaclaw-e2e-embedding-cleanup-default-five-confirm.png), [原12保留顶部](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-cleanup-default-original12-retained-top.png), [原12保留底部](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-cleanup-default-original12-retained-bottom.png)。最后重启真正底部与健康原参数也已root实看：[最后重启原12真正底部](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-final-cleanup-restart-personal12-bottom-valid.png)（原P05–P12正文/保护、Facts12）和[最后重启RAG与原512/50](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-final-cleanup-restart-index512-50.png)（RAG正常/已连接/1024COSINE/原模型地址/51250/全scope0）；早期不带valid的错名bottom实际顶部排除。

IDEA原粘贴偏好已保存恢复并重开确认。报告保存、读回与证据包落地结果见 [交付记录](../target/e2e-20261005/embedding-followup/reports/delivery-receipt.md)。两主文档final-fresh-before已由root GUI重导出且本代理逐字比较与原before完全一致，可安全按原基线保存。基线指纹、路径和项目交接步骤见[交接记录](../target/e2e-20261005/embedding-followup/reports/javaclaw-embedding-report-handoff-preparation-20261006.md)。全部raw日志排除，仅纳最小去敏诊断片段；静态review历史“待回归”表述不改为实际通过依据。

IDEA原粘贴偏好“缩进每一行”已实际选回并OK保存，重开仍为原值且Apply灰，root亲自查看，证据：[原粘贴偏好实际选回](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-idea-paste-pref-original-selected.png)、[保存后重开仍原值](../target/e2e-20261005/embedding-followup/evidence/javaclaw-embedding-idea-paste-pref-original-restored.png)。 本文为已冻结的实机结论；报告保存、读回与证据包落地结果见 [交付记录](../target/e2e-20261005/embedding-followup/reports/delivery-receipt.md)。
