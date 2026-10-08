# 统一交互执行子 Agent

当前状态：统一交互核心已实现，C23 编译通过，新浏览器任务宿主 2/2 通过；C22 只读重启 3/3 经 C23 显式继续后完成。C16 单子 Run、3 次切换通过宿主 5/5。原 16 个样本未证明节省，继续灰度，历史失败及答复准确性缺口保留。

新发布的 `system.default` 通过 `interaction.run` 能力启用交互委派。已有 Run 恢复其原来的 Agent、Profile 和执行计划，不迁移冻结配置。主端不构造浏览器或桌面工具对象；`WorkspaceToolObjects` 在创建运行时之前按编译后的身份分支。

## 任务及执行边界

主端调用 `interaction_delegate`，传目标、必要业务数据、限制和可选 AUTO / BROWSER / DESKTOP / HYBRID 模式。宿主从父任务冻结契约绑定完整、有序的交互条件，保留 ID、目标、证据要求和桌面观察策略；模型不必重复抄写条件 ID，也不能缩小宿主契约。当前完整委派要求交互条件连续；`browser → file.write → desktop` 之类穿插其他能力的契约在启动子任务前返回 `INTERACTION_CONTRACT_INTERLEAVED`，不会重排条件。

专用定义是 `system.interaction-executor`，Profile 是 `interaction-executor`。每个主会话通过宿主推导一个稳定的子 scope。浏览器继续使用 Playwright，桌面使用已有 DesktopSessionService。子端只装配被父端授权的后端及宿主的模式、等待、验收和澄清能力。通用子任务、记忆、检索及插件工具不进入该定义。决策模型沿用父任务冻结配置；视觉调用沿用既有 ModelTaskGateway。

C11 的主聊天列表隐藏专用交互子会话，避免重启时把结构化子任务误选为普通聊天。判断同时要求持久化父会话关系、无用户分支来源，以及首个已接受请求的准确交互来源、专用 Agent 和 Profile；不按 `child:` 前缀猜测。普通子任务、根会话和用户分支仍可进入列表。旧聊天索引也在恢复及 `startSession` 前按同一目录过滤；隐藏只影响主列表和默认选择，不删除消息/线程、不取消子任务，也不改变 Inspector 或历史权限。实际重启已覆盖这一有限列表路径，任意分支和所有历史分页尚未通过 GUI 验证。

宿主验证父子关联、稳定 scope、实际权限上限和显式验收契约。模式切换记录 `core.interaction.mode_selected`，保持同一子 Run、会话和来源身份。切回后端必须重新观察；历史页面引用、桌面 observationId 和摘要不能恢复过期输入基线。未知效果保留在同 scope 的源 Run 上；模式切换、修改或新轮次本身不能清除它。投递未知期间，或未核实业务输入已经跨后端选择后，可选择已有页面或打开只读桌面会话来观察，不能继续业务输入、重复派发或启动应用来绕过约束。

模式选择可附带最多 4,000 字符的业务检查点。宿主递归脱敏、拒绝原始截图，每步保留该草稿、有序未满足条件及最多 8 个待确认操作。检查点按实际父会话来源校验后跨轮次复用，并明确标为未核验数据；它不能恢复句柄、清除未知效果或成为验收证据。跨模式交接保留来源调用和证据引用，无法证明的对象关系保持 UNKNOWN。

跨模式业务保护与投递保护分别记录。`UNKNOWN / MAYBE_SENT` 属于 `DELIVERY_UNKNOWN`；原生输入或专用子端的页面输入即使返回 `ACCEPTED / SENT`，仍可能属于 `BUSINESS_UNVERIFIED`。普通同模式的观察、输入链保持原有行为；任务未完成、失败或取消时，结果披露每个后端最后一项未核实输入。纯同模式任务通过原始契约验收后，不因每次低层输入未单独证明业务效果而全部变成未知错误。

已投递但业务未核实的输入一旦发生跨后端选择，后续任一后端的副作用均被阻止，仍允许模式选择和只读观察。保护按准确父主 scope 和各自真实父来源核验后的稳定子 scope 重建，保留跨轮次、AMEND 和重启历史；模式切回不能解除。浏览器只读及导航不归入页面业务输入名单；`web_navigate` 仍是 `LEGACY` 非幂等操作，既有副作用门禁和未知投递保护照常适用。

C12 为已被重复效果保护阻止的导航增加只读 `NO_OP` 路径，没有把 `web_navigate` 改成全局 `ENSURE_STATE`。只允许专用交互子端的准确宿主实现，且先前同 URL 导航具有同一可读子 scope 的真实成功调用及 `ACCEPTED` 回执。当前 Run 必须先取得完整正文观察，其调用开始和回执均晚于最新合同、模式选择及本 Run 最新同 URL 已接受导航（包括先前 NO_OP）；捕获时间也不得早于原导航。该观察只用于绑定实际页面身份，不移植其业务结果。

准入只捕获当前已有、受控且未关闭的 Page/Context，不启动浏览器或等待忙碌的操作锁。执行在原工具时限内固定读取 URL 和文档状态，重新核验同一实际 Page、Context、document、generation 与准确规范 URL；身份变化、读取失败或取消均不转入普通导航、凭据恢复或登录回调。成功生成本次新 invocation/证据引用的 `ACCEPTED` 回执，并明确 `delivery=NOT_SENT`、`effect=NONE`、`reusedExisting=true`；失败为已知未派发。权限、审批、跨模式业务保护及 UNKNOWN 保护仍保留。该回执只确认当前 URL 状态，不能证明发生了重新加载；原顺序要求导航后观察时，仍须再取得新的正文观察。

C13 修复首次页面观察阶段的工具暴露：空白页或过期页面仍可选择已授权的 `web_navigate`，不再因已有 Page 或表面记录而只剩观察、列举和切换。另将宿主确认的新根聊天轮次边界传递到其准确关联的专用子任务：同一主 scope 中两个不同的真实 `chat-turn`，在父来源、稳定子 scope、权限及冻结合同均经核验后，可以不继承先前已知完成导航的重复保护。它不复用旧回执作为新证据，也不适用于同一父任务内的 AMEND、恢复或重复执行；UNKNOWN 投递和跨模式业务保护仍保留。不能凭 Run ID 不同或调用方自填的根来源触发这个边界。

业务保护支持两种独立证明。既有的 `core.interaction.business_effect_verified` 仍只核验原动作之前冻结、真实相邻的桌面 click → 特定 view。新增 `core.interaction.stage_verified` 仅适用于同一源 Run 唯一的原始 schema 3 合同：原观察谓词在输入前有明确 FALSE，在完整有序的已投递输入集合之后有明确 TRUE，且所有前序条件、原目标、真实受控对象身份、每次输入基线和前后证据均通过宿主重验。事件列出它覆盖的全部调用 ID，只解除该集合的业务待核实状态，不修改投递回执。UNKNOWN、MAYBE_SENT、未结算、重叠、异对象输入或修订后换用的谓词不能取得阶段证明。存储在同一事务中重读原始日志、核验和幂等写入；普通事件入口不能写入验证事件。

浏览器阶段仅支持原合同已经声明非空 `requiredTextFragments` 的主文档正文 literal AND 谓词，同一实际 Page/Context 的完整正文读取在同一文档内绑定 URL、正文和宿主页面身份；原 subject 若并存也一起做 AND，明确记录 TRUE/FALSE。仅有历史泛化 subject 的条件不自动获得完整 BODY 范围；局部元素、iframe、截断摘要、正文不可读或疑似秘密均不产生完整 FALSE。输入前身份在实际派发处记录，导航换文档后必须有对应新观察，不能借 Tab 索引、相同 URL 或历史页面摘要证明身份。桌面阶段沿用现有观察调用中的受控视觉证据，不新增辅助模型调用；每个原条件明确为 TRUE/FALSE/UNKNOWN，FALSE 要有完整画面的主内容反证，TRUE 仍要求既有正向证据，两者均遵守原置信与截图位置校验。桌面判断是视觉判断，不能称为确定性的宿主文本证明。前后实际窗口、会话、代次相同且内容修订必须增加；未判断、无反证或画面未变化不能清除。

具备上述完整证据的原生 click/type/key/scroll 有序阶段及受支持浏览器输入阶段，可以解除所覆盖输入的业务保护，随后继续跨后端任务；这些工具并非一律永久阻断。普通观察、模型检查点和用户 ANSWER 不能替代阶段证明。原合同没有可核验观察谓词、缺新版本完整证据、重启后对象身份无法连续证明、实际窗口改变或仍有未覆盖输入时，保护继续生效。任意 `web_eval_js` 可作用于未枚举的对象，`web_dialog_handle` 注册的处理器可在稍后触发，因此这两项不支持阶段解除；原生跨窗口或对话框切换也不能仅凭同应用或同逻辑 target 获得证明。无强证明的写后跨模式副作用仍返回 `CROSS_MODE_BUSINESS_UNVERIFIED`，结束未满足的子任务，不无限等待或反复重试。门禁允许读取后切换到桌面执行计算；这不等于动态数据关系已通过宿主验算，也不能声称支持任意写后跨模式任务。

浏览器业务输入名单也覆盖确切宿主实现的 `site_fill_password`、`site_login_now` 和 `web_eval_js`：前两项会填入或提交真实表单，任意页面脚本可能触发 DOM 或远端 API 副作用，不能从返回成功推断业务已完成。它们成功返回后同样保留 `SENT / BUSINESS_UNVERIFIED`，且切换模式后必须先取得新页面观察；登录态核验只证明认证状态，必须仍由原业务谓词决定是否能形成阶段证明。导航、Tab 和 Cookie 管理、账号选择、只读认证检查、会话保存或清除不因该名单扩展而成为页面输入。

规划和唯一一次合同修复都不得预猜尚待观察的页面内容、编号、操作数或最终数值。浏览器字面片段只保留用户实际给出的标题或锚点，完整的取值要求仍保留在描述中；下游桌面条件必须保留“对指定来源实际读取的值执行所要求运算并查看结果”的完整含义，不能降成泛化的“计算结果”。当前宿主没有新增动态数据绑定或数学关系验证器；模式检查点仍是未核验草稿，浏览器标题字面匹配与桌面出现一个数值不能独立证明输入来自指定页面、关联正确且运算正确。桌面语义视觉证据继续遵守既有规则，证据无法覆盖完整依赖时应保守保留未核实结果，或澄清真实缺失的人类选择，不能通过弱化合同获得完成状态。观察能够取得的数据未知本身不构成人类意图缺失。提示修复只减少规划阶段猜值，不能作为完整混合任务语义验收通过的证据。

主端直接工具组和可委派授权分别保存；后者只能由编译结果及已验证的父请求派生，调用方自填的授权不能扩大权限。稳定子会话不把首轮剩余预算和权限永久保存为默认上限；每轮仍受本次父任务、累计祖先用量，以及用户显式配置的子会话限制约束。子会话保存的模型默认值也不能覆盖本次父任务冻结的模型。

## 等待及用户控制

委派开始后，原工具步骤保持未结算，父 Run 转为 `WAITING_CHILD`。桌面事件等待使用 `WAITING_EVENT`。模型调用栈退出，宿主订阅子事件、原生帧/状态变化和冻结截止时间，不让父模型反复查询状态。原生 idle 帧的时间戳刷新不会单独唤醒等待：宿主比较已验证观察的窗口代次和内容修订。

完成时，宿主以一个原子事件批次结算保留的工具步骤、结果和恢复事件，再继续父模型。交付受当前 wait 身份、任务版本和 Run 状态约束。澄清/审批携带子事件序号，恢复订阅使用已持久化游标，旧挑战不会覆盖当前挑战。

界面提供明确的“回答 / 修改要求 / 取消子任务”按钮。普通输入和 Enter 不隐式发出控制命令。命令含 commandId 和 expectedRevision；旧版本被拒绝。修改先取消旧子 Run，等待物理动作结算，再创建同子会话的新版本，保留未知效果和父预算。取消传播到附属子 Run，迟到回执仍由原调用结算。

C15 的 ANSWER 绑定具体问题：API 增加 `childEventSequence`，界面从宿主当前问题事件取得该序号，实际回答前在子任务锁内重验父子、任务版本及尚未消费的问题；恢复去重同时核验命令 ID、问题序号、任务 ID 和版本。旧四参数构造器及缺字段 JSON 保留兼容入口，但缺少问题序号的 ANSWER 会被拒绝，不能默认回答最新问题；AMEND/CANCEL 不需要这个序号。重启暂停仍可保留未被消费的原问题；已接受但过期或缺字段的旧回答明确记为拒绝，只重新展示宿主确证的当前问题及回答入口，不把旧命令补成有效授权，不用空继续替代未回答的问题，也不重置预算。已投递的准确命令只补确认，不再次投递。普通公开继续和审批语义保持原样。原问题及准确回答的身份绑定已在 C18/C19 真实 GUI 中成立；完整恢复验收仍须按下文各版本结果区分。

重启把等待中的 Run 转为 PAUSED，直到用户明确继续才重连。恢复未完成修改时，从持久化命令和 commandId 找回已创建的替代 Run 或已冻结的新契约，不能交付旧版本结果，也不能重新播放旧输入。剩余预算和截止时间不重置。

重连恢复的是持久化等待及控制关系，不恢复原进程中的登录核验基线、未保存登录态或原生控制句柄。登录等待继续前，须重新取得可观察页面并建立新的宿主登录核验基线；当前权限也须重新核验，必要时重新申请控制授权。原问题和用户回复不能替代这些事实，`site_auth_check` 在基线缺失时明确失败，不重放原导航。已受控保存的凭据及会话仍按既有存储规则使用，不能据此声称原登录挑战已经恢复或验证成功。

## 结果、历史和验收

`InteractionResultProjector` 从宿主原始回执重建结果，包括按条件 ID 选中的证据、满足/未满足条件、简短摘要、错误码及未知效果。描述文字相同的条件不会共用身份。父任务仍用原始后代回执验收，委派回执和摘要不构成业务证明。较大正文通过 `run:<childRunId>:output` 返回，由主端 `interaction_read_result` 有界分页读取；该入口只允许当前主会话所属的已结束交互子任务。

C23 为已完成验收的纯交互合同增加主端收尾投影：当前可靠原合同的全部条件必须通过既有有序证据校验，且实际结束子 Run 的父来源、scope、权限、任务版本、完整原合同和宿主交付调用链仍须准确对应。此时仅保留 harness 及原已授权的 `interaction_read_result`，不提供重复委派、工具目录或底层后端；未满足条件、非可靠合同、穿插文件条件或交付身份缺失不进入该捷径。宿主提供已验证子任务的准确结果定位符，小结果也可读取；最新同定位符的完整读取交换保留供最终答复。结果正文仍不构成新观察或验收证据，最终完成必须由主模型明确提交并经宿主验收。没有剩余工具预算或读取授权时，只保留 harness，不新增权限、预算或编造正文。

C12 在主端接受 AMEND 后立即隔离旧版本交互验收证据。宿主重验 accepted → revised → waiting/applied 的持久化控制链、原保留委派步骤、当前子 Run 的任务版本/父来源/scope/权限上限，以及父子完整冻结条件的逐项等序对应。链缺失、修改尚未完成或身份不符时，交互验收保持未证明；只有当前版本已结束子 Run 在其当前冻结合同之后产生的物理回执可参与浏览器/桌面条件验收。未修改的文件或命令条件仍可使用其原证据；原始历史、旧回执、未知效果继承和用量统计保持完整。

已有任务结果的缓存也受验收边界约束：结果之后出现新合同、合同修订或已接受 AMEND 时，后续验收须重新收集当前版本证据并计算，不能直接返回旧完成结果。既有恢复后的重算规则仍保留。这不回写历史已经记录的 outcome，也不把旧回执改名成新版本证据。

`InteractionJournal` 写入独立的 thread interaction 事件；`InteractionHistoryClient` 按持久化委派关系查询当前主会话及有权限的后代。浏览器展示 context/page 稳定身份、opener、导航和关闭；桌面展示应用、进程实例、逻辑目标及实际捕获窗口。动作与观察对象只按准确的 Run/调用关联。直接创建、预注册弹窗等待匹配、操作期间观察到和关系未知分别保留。多个候选不自动选择最后出现的对象。

历史接口和 Inspector 提供有界只读视图：一次最多返回最近 500 项，每个 invocation 最多关联 8 个观察对象，较长历史或对象关联可能不完整。当前没有查询更早历史的统一分页接口，因此不能把该界面称为全量操作树。完整的已记录 Run/thread 事件仍保留在原始持久化日志中，不因视图截断而删除；输入保护和验收仍读取原始证据，不依赖这个有界显示列表。

C16 已通过真实 Inspector 界面检查有限历史分支：[桌面截图](../data/interaction-validation/2026-10-08/c16-history-desktop.png) 展开应用、进程实例和实际窗口，并显示旧 C13 的三条 checkpoint，覆盖同会话跨轮次聚合；[浏览器截图](../data/interaction-validation/2026-10-08/c16-history-browser.png) 展开本轮 runtime、context、page，显示导航与观察记录，并明确标注“观察关联的动作（不表示因果）”。这只证明这些有界分支实际可见，不证明全量历史、多弹窗选择或原生父窗关系，也不能把历史记录当成当前画面或新验收证据。

桌面实际窗口来自现有 ABI 6，其公共接口尚未传递可核验的父窗或 owner 关系，原生对象关系保留 UNKNOWN。此次不修改 ABI，也不触发启动编译；仍从项目 `data/native/<platform>` 加载预编译库。桌面历史不是完整的操作系统窗口事件流。浏览器事件在 Playwright 消息泵执行时交付，空闲期间不能承诺立即收到回调。

`NO_FRAME` / `TARGET_CHANGED` 允许一次重新发现、打开和观察。新的 handle、时间戳和 observationId 不会重置失败计数；仍失败时明确停止，保留未满足条件及未知效果。

## 验证状态

没有新增或运行代码测试用例。已通过 IDEA 启动及 AppleScript 模拟输入开展诊断和正式配对采集。早期三轮诊断（两轮浏览器、一轮桌面）不计入正式样本；其中桌面独立观察确有 `12×34 = 408`，但宿主输入用量 257,136 超过冻结上限 250,000，最终 PAUSED，未通过宿主验收。C3 的捕获身份与阶段证明源代码已编译通过，编译结果不等于这些解除路径已经通过 GUI 验证。C12 的导航 NO_OP、当前版本证据隔离及结果缓存修复已应用，统一编译与差异检查通过；[C12 构建清单](../data/interaction-validation/2026-10-08/source-manifest-C12.json) 记录 1,723 个文件，原生库未变。

C12 的实际补充运行 `eb7fd7ad-2c48-447b-880b-5e64591933f4` 复现了冷启动空白页工具目录失败。首次列举为零个 Tab；后续首次观察分支移除了导航工具，两版子任务都反复观察 `about:blank`、列举及切换，没有真实导航派发、有效 IANA 正文验收或桌面切换。验证操作者在确认循环后通过界面取消，父任务为 `CANCELLED / UNVERIFIED`、0/6 条件，不是自然预算失败。该失败保留为补充样本；它没有走到 NO_OP，也没有覆盖 AMEND 新版本验收成功路径，不能据此宣布这些修复通过。

C13 的工具暴露及新根聊天轮次边界修复已应用，统一编译 72704 与差异检查通过；[C13 构建清单](../data/interaction-validation/2026-10-08/source-manifest-C13.json) 记录 1,723 个文件，原生库未变。实际补充运行 `67b03c50-eef7-458c-9e5d-0f892c370e6a` 已覆盖同 Run 和 AMEND 新版本的导航 NO_OP：宿主生成成功的 `ACCEPTED / NOT_SENT / NONE` 回执，之后取得新的正文观察。当前版本子任务 `4689bd04-eac3-44a8-834e-4fff844dbbe7` 完成最后一次桌面模式选择后，因消息字符 24,946 超过冻结上限 24,000 而停止，最后桌面观察没有执行，结果为 `PARTIAL`、5/6。父任务自然 `COMPLETED / BLOCKED`、5/6，五项验收引用全部来自当前版本，覆盖了 AMEND 旧证据隔离的正向路径；完整四阶段仍未通过。

该运行的[真实 GUI 指标导出](../data/interaction-validation/2026-10-08/interaction-metrics-67b03c50-eef7-458c-9e5d-0f892c370e6a.json) SHA-256 为 `3759007e8ab4b9a9eed47f37995968f2f073bf0e7cc0814da0aa2c043aaf0e59`。读取时共 30 次 provider 尝试、240,501 个已记录 token、1 次用量未知及 3 项媒体输入；包含原父子执行链和一项可信维护调用，不能把已知 token 当成完整总用量。一次委派产生原版及 AMEND 版两个子 Run，两个版本合计 7 次模式选择、5 次切换。原版切换 1 次；当前版本包含先 D 后 B 的前奏，共切换 4 次（D→B→D→B→D），其中四阶段主轨迹 B→D→B→D 切换 3 次，不能混用这三个统计范围。应用已于 17:50:28 停止，C13 的 `BLOCKED` 5/6 失败记录保留。

C14 的两项修复已应用，统一编译 19339 通过；[C14 构建清单](../data/interaction-validation/2026-10-08/source-manifest-C14.json) 记录 1,723 个文件，原生库未变。真实补充运行 `9a67da20-2bcb-4287-8920-781ed31c2e5c` 在首次导航前失败：先前成功的只读 NO_OP 回执虽为 `ACCEPTED / NOT_SENT / NONE`，未被“已知完成导航”分类覆盖，新根聊天仍继承了它的重复效果保护。子任务 `c9001411-89b4-4af5-8d19-3a4a83820256` 为 `FAILED / UNVERIFIED`、0/4，错误为 `EFFECT_OBSERVATION_REQUIRED`；父任务自然 `COMPLETED / BLOCKED`、0/4。没有新导航派发、正文观察或桌面操作。模式选择虽返回 `modelViewChanged=false`，其 LongNode/IntNode 比较问题使模式回显路径尚未验证；原生上下文压缩也未覆盖，不能用这次早期停止证明压缩有效。

该轮[真实 GUI 指标导出](../data/interaction-validation/2026-10-08/interaction-metrics-9a67da20-2bcb-4287-8920-781ed31c2e5c.json) SHA-256 为 `9675bfea74d857ba4578ddc9011894119fdc543ce5bbe4986f706d43e4808cd3`；读取时含可信维护共 8 次 provider 尝试、42,286 个已记录 token，用量未知为 0。一次委派、一个子 Run、一次 BROWSER 选择、零切换，父等待期间没有模型轮询。应用于 18:09:30.823 停止；C14 失败保留，不把较短失败耗时或较少用量当成改善。

C15 的三项补丁已应用，统一编译 30514 通过；[C15 构建清单](../data/interaction-validation/2026-10-08/source-manifest-C15.json) 记录 1,725 个文件，源码指纹 `fbf22a1140b29dbe6f017d0b506fae7c02de7e590a0c798e538ffbcd4c6013bb`，原生库未变。有准确宿主调用与只读标记的成功 NO_OP 纳入已知完成导航分类，仅服务于已核验的新根聊天轮次继承边界；AMEND、重复执行、UNKNOWN 和业务保护仍照常适用，不把旧回执当新验收证据。模式回显按准确数值身份比较持久化节点，不因 LongNode/IntNode 的表示差异误判；ANSWER 采用上述具体问题绑定。实际补充运行 `0df34a74-3f56-4d33-a77d-13ce02538c16` 仍失败：子任务 `f16d8139-f6d7-46b2-a9ba-0ade9ef430ca` 在没有本轮桌面打开或观察回执时，自述桌面阶段已完成并编造会话、观察标识及捕获时间，随后调用事件等待；宿主因缺少真实观察基线而拒绝，子任务 `FAILED / PARTIAL`、3/5，父任务自然 `COMPLETED / BLOCKED`、3/5，没有把这些自述当成桌面验收。编造标识在此前模型上下文中没有对应的真实回执来源，不能认定其复制了某份真实历史证明；后续 checkpoint 复传的是未验证的模型草稿。当前 cursor 已明确没有可用会话并要求 `targets -> open -> observe`，后续实际工具目录也提供了 open，本轮不能归因为宿主漏出该工具。真实桌面调用只有 targets，没有 open、observe 或输入，完整四阶段与 ANSWER 路径均未覆盖。新根轮次真实导航成功，五次模式选择的回显均为 `modelViewChanged=true`，只构成导航继承及数值回显的正向证据，不构成桌面或完整跨模式成功。

该轮[真实 GUI 指标导出](../data/interaction-validation/2026-10-08/interaction-metrics-0df34a74-3f56-4d33-a77d-13ce02538c16.json) SHA-256 为 `25e6f312b33c741bb64a2cd0cd3e9934e7f27cfc0c6e57f34e037ccf223310bb`。读取时共 18 次 provider 尝试、128,823 个已记录 token（121,081 输入、7,742 输出），其中执行链 17 次、可信维护 1 次；用量未知为 0，费用未知为 18，媒体输入为 0。一次委派、一个子 Run、五次模式选择、三次切换；失败记录保留，不能把未执行桌面观察造成的较低用量当作节省。

C16 的两项补丁已应用，统一编译 67207 通过；[C16 构建清单](../data/interaction-validation/2026-10-08/source-manifest-C16.json) 记录 1,726 个文件，源码指纹 `8d4496b15b967c582834ff14f9ed37b7239a0b6397f592c1a301602e4d25b45f`、清单指纹 `3280528696344e286fe61dc2eec6a047bf205316443412c88aa760673b2c5d53`。专用提示明确当前宿主状态和真实回执优先；等待工具仅在本 Run 当前模式及合同之后有真实桌面观察基线时展示。跨模式切换前缺少当前模式的真实观察会返回有界失败反馈并保持原模式；待核实的未知投递仍允许切换到必要观察后端，原副作用门禁不变。无基线的等待请求明确记为未创建订阅、`NOT_SENT / NONE`，不以泛型异常伪造可能投递。

真实模拟输入 GUI 补充运行 `c34c189a-e583-43fd-b91c-273f98b3d4c7` 已自然完成：同一子 Run `542448ca-5b80-4d5b-8a00-9c4d56f5cf46` 按 BROWSER→DESKTOP→BROWSER→DESKTOP 三次切换，父子均为 `COMPLETED / VERIFIED_COMPLETE`、5/5 条件及 5 个有序引用，可记为本轮 `HOST_PASS`。最终引用对应子回执 27（导航）、38（首次浏览器正文）、90（首次桌面）、114（再次浏览器正文）、172（再次桌面）；两次浏览器观察绑定同一 runtime/context/page/document，但具有不同观察 ID 和捕获时间，两次桌面观察绑定同一会话与实际窗口，也使用新的观察 ID 和捕获时间。桌面通过真实 targets、`open(control=false)` 及两次 observe 完成，没有执行页面或桌面输入。最后一次桌面没有 stage marker，仍由既有同帧 schema-2 条件证明及完整原始观察路线验收，不能声称所有观察都产生了 stage marker。

宿主目录的等待基线限制已在正常路径实际覆盖：首次观察前没有 wait，首次观察后提供 wait，再次切回桌面后先隐藏 wait，取得新观察后才重新提供。父 `WAITING_CHILD` 第 20 至 25 事件间 262,163ms 没有父 MODEL/MODEL_TASK 调用。验证操作者通过独立 Calculator AX 确认开始及结束显示均为 0，期间没有输入；浏览器阶段由绑定受控 Page 的完整正文宿主证据及独立日志审查核验，没有另行人工逐阶段查看该 Playwright Page，因此不能标作全阶段独立 `VIEW_PASS`。无新鲜观察时拒绝切换、无效 wait 的失败反馈、ANSWER 旧问题拒绝及重启恢复等负向或恢复路径，本轮均未执行，仍保留待验证状态。

该轮[真实 GUI 指标导出](../data/interaction-validation/2026-10-08/interaction-metrics-c34c189a-e583-43fd-b91c-273f98b3d4c7.json) SHA-256 为 `46e7ee411b71197147c860af317d53a98abe24cc13e2a5b48064f501c69790ef`。读取时含可信维护共 30 次 provider 尝试、212,433 个已记录 token（194,855 输入、17,578 输出），执行链 29 次、维护 1 次；用量未知为 0，费用未知为 30，媒体输入为 2。父自然业务耗时 345,255ms，子任务 262,051ms；导出仍标 provisional，不能把零金额视作免费或由这一补充样本声明节省。ANSWER 成功结论仍未取得。各构建指纹、已采集成功与失败样本、尚未执行的补充项均以 [interaction-validation-template.md](interaction-validation-template.md) 为准；C13/C14/C15 失败和原正式 16 个样本均不替换。

同一 C16 构建随后进行了三次澄清诊断，均保留真实 GUI 导出，未替换正式 16 个样本或上述正常四阶段成功记录：

| 补充父任务及原始指标 | 实际结果 | 已记录用量 |
| --- | --- | --- |
| [a43a3dac-607b-47a0-96c3-3c6f6a414b94](../data/interaction-validation/2026-10-08/interaction-metrics-a43a3dac-607b-47a0-96c3-3c6f6a414b94.json) | 规划缺少 requiredEvidence，唯一修复又未返回 JSON；形成不可靠空合同，导出为 PAUSED / UNVERIFIED，尚未委派。空合同 0 个未满足项不代表完成。 | 2 次 provider，22,262 token |
| [af69562c-bb9b-43ca-baac-b0ecc619c979](../data/interaction-validation/2026-10-08/interaction-metrics-af69562c-bb9b-43ca-baac-b0ecc619c979.json) | 首次计算器观察真实成立；ask_user_clarification 审批约 60 秒未获通过，子任务 CANCELLED / PARTIAL 1/2，父 COMPLETED / BLOCKED 1/2，没有形成实际问题或回答。验证操作者没有点击拒绝，不能将通用 DENY 映射解释为真人拒绝。 | 10 次 provider，55,341 token |
| [4f79eecd-fa7b-4c14-8f1a-86e3d7d75c7a](../data/interaction-validation/2026-10-08/interaction-metrics-4f79eecd-fa7b-4c14-8f1a-86e3d7d75c7a.json) | 原冻结只读 open 条件走通用目录路径；模型反复目录激活和 targets，虽多次实际提供 open，仍无 open、observe 或澄清。子任务输入用量 233,003 超过剩余额度 231,758，FAILED / UNVERIFIED 0/3；父自然 COMPLETED / BLOCKED 0/3。 | 35 次 provider，259,482 token |

这三次均未覆盖 ANSWER 或重启问题恢复；第三次仅在准备修改时已经失败，没有提交 AMEND，也没有 accepted/revised 修改链。原失败、全部物理调用成本及导出快照保持不变；三个导出用量均无缺失，但费用仍未知。应用于 19:05:21.379 停止后才应用 C17 补丁。

C17 已应用窄范围的宿主只读 open 路径及一行审批中性文案，统一编译 22096 通过；[C17 冻结清单](../data/interaction-validation/2026-10-08/source-manifest-C17.json) 记录 1,727 个文件，源码指纹 `be4c4e01f531047eb91cc252ab8978fa2b34f740e098c4a32fcc10f3d2cc1071`、清单指纹 `d505715556e5ef6468eaa3d8dd660fe4c3c98a94872c6c843b43e37e858967f3`。相对 C16 只有四个 Java 文件变化，原生库未改。真实 GUI 任务已生成问题，但重启失败，具体记录见下文；真实 ANSWER 仍未覆盖。新路径仅针对 DESKTOP-only 专用子任务、明确 readOnly=true/control=false、完整合同只包含 desktop.open/desktop.observe 且首个未满足条件是准确原生应用 ID 的 open；按本轮真实发现候选提供 required targets/open 接口，避免通用目录激活循环。没有候选时明确停止，多个候选不自动选择；正常打开仍由模型显式调用 control=false，再取得真实观察。该投影不自动操作、不改合同、不授控制权限，也不放宽未知投递、恢复、预算或工具上限。审批未获通过的描述改为中性“tool approval was not granted”，不再在没有真人拒绝证据时写成 user denied。没有新增或运行代码测试用例。

C17 补充父任务 `d30363bb-5764-4e84-afc2-b55e5b2375f9` 已实际走通只读 targets→open→observe，并在子任务 `4b74a312-4f42-4380-abe7-1623a8c0ab24` 第 110 事件进入 `WAITING_INPUT`。验证操作者停止 IDEA 以测试重启时，会话适配器提前调用取消：父第 29 事件为 `CANCELLED / SHUTDOWN`，详情为 conversation adapter cancellation、userInitiated=false；子第 112 事件为 `CANCELLED / PARENT_CANCELLED`。问题 110 因终态取消未恢复，没有提交 ANSWER。这是实际重启失败，旧终态不能复活。父导出验收为 `UNVERIFIED` 0/3、0 引用，子随后为 `PARTIAL` 2/3，不能将子进展回填为父验收。[真实 GUI 指标导出](../data/interaction-validation/2026-10-08/interaction-metrics-d30363bb-5764-4e84-afc2-b55e5b2375f9.json) 记录 12 次 provider、99,552 个已知 token（90,042 输入、9,510 输出），用量未知为 0、费用未知为 12；父等待第 25 至 29 事件间 189,876ms 没有 MODEL/MODEL_TASK 轮询。

C18 已应用仅修改 AgentConversationRunner 的窄补丁（SHA-256 `4323b6af4a75a09e16521f5a68e57a938cd7452297f01053b36385b5d8929fa8`），统一编译 64417 通过；[C18 冻结清单](../data/interaction-validation/2026-10-08/source-manifest-C18.json) 记录 1,727 个文件，源码指纹 `55fb220bf95040432557162f8b68a5b804fe987251f39c27b93a2d16a4ed5ddb`、清单指纹 `3af56722e250bc97e8827a3cdd2d085a4fdfc1b4ae3fceff191b0bb479e74193`，原生库未变。交互会话在准确 SHUTDOWN 原因下只释放 UI 观察订阅，由内核既有停机路径持久化可恢复暂停；非交互任务和用户显式取消、替换任务、RUNTIME_REBUILD 等其他原因保持原取消行为。适配器不写业务终态，不改预算、原 deadline 或效果保护；C17 已终止任务仍保持原记录。C18 真实恢复运行的阶段结果与失败记录见下文。没有新增或运行代码测试用例。

C18 父任务 `ab97a10f-b43e-4f3d-9fe7-c014593001b3` 的停机暂停、显式重新连接和准确问题 ANSWER 身份绑定实际成立：子任务 `9cb5a18e-843a-4a8c-b80f-523661222e3a` 保留原问题 103，重启后回答仍绑定原 task、revision 及问题序号，没有自动回答或重建 Run。但 ANSWER 后的业务恢复失败：此前澄清工具的 Spring 包装异常被记为 FAILED step 及 UNKNOWN/MAYBE_SENT 回执，子第 105 事件恢复后立即因该未决步骤停止，第 107 事件为 `FAILED / PARTIAL` 2/3，第二次观察没有执行，回答后没有新子 MODEL 或工具调用。父自然 `COMPLETED / BLOCKED` 2/3，不能据控制身份成功宣称完整恢复通过。两段活动等待分别为 114,265ms 与 42,754ms，父模型调用均为 0；停机到重连间隔另计。[真实 GUI 指标导出](../data/interaction-validation/2026-10-08/interaction-metrics-ab97a10f-b43e-4f3d-9fe7-c014593001b3.json) SHA-256 为 `b6399f78479250e09d9f6d54a03da7747a19fd789439c15df8e9ec172d4a6a1e`，含执行链 14 次、可信维护 1 次，共 15 次 provider、96,500 个已知 token（87,325 输入、9,175 输出），用量未知为 0、费用未知为 15；原失败和正式 16 个样本均保留。

C19 已应用准确宿主澄清异常分类补丁，统一编译 86165 通过；[C19 冻结清单](../data/interaction-validation/2026-10-08/source-manifest-C19.json) 记录 1,727 个文件，源码指纹 `2fd0f338f24ef89fca7968bb7789450846670f042a48eeb22582d8b1efa0c93a`、清单指纹 `62c30f2be694464a78603b3c0b6ebaf0b8ac83cb404b709789facd1dab7689d6`，原生库未变。仅对未取消的准确宿主 ask_user_clarification、最多八层已知透明包装、与本次已验证入参完全一致的 typed 问题信号，复用既有 completed/PENDING 等待步骤及 OBSERVED/NOT_SENT 回执；其他工具异常和一般 UNKNOWN 保护不变，不升级旧 C18 的失败步骤。

C19 父任务 `a2f5981a-0504-4f8d-a63c-a3a523f25463` 及子任务 `2391af5b-d207-4ee2-aee0-88fde27c70b6` 已完成真实 GUI 诊断。新澄清步骤正常结算并进入问题 100 的 WAITING_INPUT；重启后提交准确绑定问题 100 的 ANSWER，重新 targets→open 取得真实新 session，并由第二次观察回执 162 读到 0，没有再次被旧步骤 UNKNOWN 阻断。但验收链仍绑定停机前的会话：只选中原 open 54 和首观察 71，拒绝新会话观察 162，随后修复所需消息 24,535 字符超过原上限 24,000。子任务最终 `FAILED / PARTIAL` 2/3，父自然 `COMPLETED / BLOCKED` 2/3；完整恢复仍失败，不能用原生对象相同或已出现第二次观察代替最终验收。[真实 GUI 指标导出](../data/interaction-validation/2026-10-08/interaction-metrics-a2f5981a-0504-4f8d-a63c-a3a523f25463.json) SHA-256 为 `b4a89caaeaa11aefbad0a9fedd544cc99ab6f30e29ea4ab9f857c80dfd931b5f`，记录执行链 20 次、可信维护 1 次，共 21 次 provider、156,357 个已知 token（137,781 输入、18,576 输出），用量未知为 0、费用未知为 21、媒体输入为 3。两段活动等待分别为 124,842ms 和 179,438ms，父 MODEL/MODEL_TASK 均为 0。该原始失败和正式 16 个样本均保留。

C20 一行补丁已与 C21 一并应用：仅将 trustedClarificationInput 的 expectedReason 判断从 reason.isEmpty() 改为 reason.isBlank()，准确对齐 ClarifyTools 已有 Unicode 空白 fallback；reason/question 同时 empty 的拒绝条件保持原样。这是静态语义对齐，不代表额外执行过 Unicode GUI 样本，也不升级既有 UNKNOWN 记录；没有独立 C20 运行。

C21 的严格只读会话恢复补丁已应用，统一编译 27850 通过；[C21 冻结清单](../data/interaction-validation/2026-10-08/source-manifest-C21.json) 记录 1,728 个文件，源码指纹 `03d1cb61e62fa4d9e371960aa9cea25c0458f1096dede6f77b4dbd5008c2df69`、清单指纹 `0041c940059fdef2a944b84f5108d417449f21ec0bf5d8799bcea8ff429d6537`，原生库未变。仅在 V3 的完整只读 open→observe 合同、准确原任务/版本、停机前真实问题及对应 ANSWER 均获宿主验证时，允许用本轮真实 targets→`open(control=false)`→新观察重新关联验收。前后必须具有同一原生进程实例出生标识、实际窗口、目标及代次，原合同 SHA、逐条件谓词和完整同帧 TRUE 证明仍须成立；完整原 Run 记录不能含业务输入尝试或未知物理效果。成功的纯宿主目录/模式控制须有完整、准确的宿主调用链，不能借其一般 UNKNOWN 回执排除任何物理未知。该路径只以实际新 open 对当前候选进行临时关联校验，不重写旧回执、原会话、证据引用或 UNKNOWN 账本，不授予输入权限，不修改普通 V2/动作链、预算或上下文上限。C19 历史结果不会被回填为成功；编译或静态独审均不等于恢复 PASS。没有新增或运行代码测试用例。

C21 第一次实际补充父任务 `016275b6-8c53-490a-9325-a1ed2e742c28`、子任务 `5a1ebe1e-c45a-489c-b66c-99847b1f093e` 未通过恢复验收。原 open 48 成立，但首观察 101 的原始视觉输出在 conditionEvidence 和 conditionResults 的 content 中都缺少 confidence，条件证明保守保持 UNKNOWN。原 TRUE 判断本身不完整，因此不满足既有“只补正向候选缺失置信度”的修复准入条件，没有新增修复调用或补默认值。重启后取得的新观察 184 有完整 TRUE 证明，但不能替代停机前缺失的首帧证明。准确绑定问题 130 的 ANSWER 已进入模型 190 上下文，模型仍再次请求澄清，第 193 事件等待审批，审批超时后第 195 事件为 `CANCELLED / TOOL_APPROVAL_DENIED`、userInitiated=false，子验收为 `PARTIAL` 1/3，只保留原 open 引用。验证操作者没有实际点击拒绝或提交取消，不能记作取消路径通过。该失败保留，C21 严格会话关联的正向 GUI 验证仍待完成。

C21 的三次独立诊断及其原始 GUI 导出均保留，不能用下一次重跑替换，且均不计入原正式 16 个样本：

| 父任务及原始指标 | 实际结果 | provider / 已知 token |
| --- | --- | --- |
| [016275b6-8c53-490a-9325-a1ed2e742c28](../data/interaction-validation/2026-10-08/interaction-metrics-016275b6-8c53-490a-9325-a1ed2e742c28.json) | 上述首帧证明不完整；重启/准确回答及新观察已执行，父最终 BLOCKED 1/3，未通过会话关联验收。 | 23 / 185,184（161,472 输入、23,712 输出） |
| [f91e9026-1ea8-4fb0-9ed0-62c9138056d4](../data/interaction-validation/2026-10-08/interaction-metrics-f91e9026-1ea8-4fb0-9ed0-62c9138056d4.json) | 首次规划为 INVALID_SECURE_INPUT_SUBJECT，唯一修复超时；PAUSED / UNVERIFIED / PLANNING_REPAIR_TIMEOUT，未委派或执行工具。空合同和零未满足项不代表完成，也不是实际敏感输入尝试。 | 2 / 8,993 已知；失败修复另有 1 次用量未知，不能记为零消耗 |
| [5670d075-dff2-4642-8903-623358328717](../data/interaction-validation/2026-10-08/interaction-metrics-5670d075-dff2-4642-8903-623358328717.json) | 子任务 fa8d0a22 实际 open 204、观察 221，但嵌套置信度缺失，条件 UNKNOWN；提前澄清的审批未获通过，父 BLOCKED 1/3。没有形成问题、ANSWER 或重启。 | 25 / 181,159（170,779 输入、10,380 输出） |

第三次的目录激活曾在 MODEL 70 和 109 真实提供 targets/open/observe，并非这些工具从未可达；C17 持续只读生命周期投影被旧 C17/C18 澄清 UNKNOWN/MAYBE_SENT 集合挡住，模型又反复选择目录。后来真实打开和观察并未使缺字段的条件证明成为有效证据。验证操作者拟通过的提前澄清审批被自动审核拒绝，操作未执行；随后既有审批等待超时，不能称为操作者点击拒绝或主动取消。三份导出费用均未知；首份和第三份用量已知且包括各一项可信维护，第二份修复缺失用量保持未知。第一份两段活动等待 240,665ms/184,666ms、第三份等待 239,859ms，父 MODEL/MODEL_TASK 均为 0；等待期间没有父轮询不代表完整恢复通过，也不能由失败样本宣称节省。

C22 已应用两处只读投影修复及两行视觉提示澄清，统一编译 30704 通过；[C22 冻结清单](../data/interaction-validation/2026-10-08/source-manifest-C22.json) 记录 1,728 个文件，源码指纹 `f3d6bf7ea5c2278518bc49419c3cfab0357d989688504bc8fefd96eea99f51c6`、清单指纹 `6cb3d1b734046f1c6c422d198d5a8adc5e001b4224778085fd3c72a7ad3c3895`。仅取消准确只读 open 生命周期两处对通用未决效果集合的投影拒绝，保留 hasPendingDesktopInput、当前 cursor 待决输入、原模式/合同/权限、readOnly=true/control=false 和实际派发端全部 UNKNOWN 保护；不清旧账本或开放输入。提示明确 TRUE 证明的 conditionEvidence 与 conditionResults 各自外层和 content 共四处数字置信度须独立提供，不确定仍 UNKNOWN 并省略整项正向证明，不复制或补默认值。schema、解析器、阈值、修复准入及调用上限均未改，旧帧不升级。

硬 schema 候选未合入：现有 ModelTaskGateway 对完整响应执行 schema 校验，若将可选证明中每个已出现目标的 confidence 改为硬 required，模型漏字段会令整帧 SCHEMA_MISMATCH，而不只是丢弃该项证明，可能丢失仍合法的 OCR。本次保留原有逐项降级行为；提示澄清也不保证模型一定提供完整证明。编译通过不改写三次 C21 失败或任何原始成本；没有新增或运行代码测试用例。

C22 实际父任务 `65175d14-9036-4112-823a-b3792676bf1d`、子任务 `54d37e32-130b-418b-bebc-253bdb46272d` 已覆盖严格只读重启恢复：首观察 45 有完整同帧 TRUE 证明；真实停机保留问题 61，重启后 ANSWER 仍准确绑定该问题和原任务版本。随后真实 targets→`open(control=false)`→观察 116 取得新的会话及观察身份，仍绑定同一实际进程实例、窗口、目标和代次，没有业务输入。子第 130/131 事件为 `VERIFIED_COMPLETE / COMPLETED`、3/3，原有序引用是 open 28、首观察 45 和恢复后观察 116；新 open 94 只用于严格校验第三项会话关联，没有重写旧引用。

父第 47 事件已收到这三项真实证据，却因辅助工具选择及其修复返回的 `toolIntent.query` 超过 128 字符，在第 48 事件 `PAUSED / UNVERIFIED / MODEL_COMPLETION_NOT_CLAIMED`，尚未形成最终完成决策。[原 C22 暂停导出](../data/interaction-validation/2026-10-08/interaction-metrics-65175d14-9036-4112-823a-b3792676bf1d.json) 保持不变，SHA-256 为 `2fa4588ea3fa427884fea45c440c64f1c2d252e1791ee04a9eea716620d49b4d`；15 次 provider、107,999 个已知 token（95,556 输入、12,443 输出），用量未知为 0、费用未知为 15、媒体输入为 3。两段活动等待 145,142ms / 145,630ms 的父 MODEL/MODEL_TASK 均为 0。该快照记为子恢复通过、父收尾未完成，不能改写成 C22 全链成功。

C23 收尾补丁已应用并通过统一编译 40409；[C23 冻结清单](../data/interaction-validation/2026-10-08/source-manifest-C23.json) 记录 1,728 个文件，源码指纹 `874d9ebbd46f7df2088484b479890e1fafd71768e27f6dc537b126e1c75a641c`、清单指纹 `62b6cf2d91b7d9410da18541e4a06640d48e464af28f37245f0338eeb7c5b6fa`，原生库未变。验证操作者在 C23 明确继续同一父 Run 后，第 58/60 事件通过结果读取接口取得完整 1,089 字符正文，下一 MODEL 64 保留该读取交换；父第 94/95 事件自然 `VERIFIED_COMPLETE / COMPLETED`、3/3。子任务停在原第 131 事件，没有新委派或重跑，三项证据仍是原 28/45/116。这只证明跨构建继续及主端有界结果读取/收尾路径，不能当作新的全 C23 重启样本；后续独立 C23 任务记录见下表。

[C23 继续后的单独 GUI 导出](../data/interaction-validation/2026-10-08/interaction-metrics-65175d14-9036-4112-823a-b3792676bf1d-C23-resumed.json) SHA-256 为 `3ada9b8133c9fb17f051fe41a93553a6f878360a8dbdb0bb81a98cec91c13ad4`，累计含维护共 19 次 provider、120,595 个已知 token（106,258 输入、14,337 输出），用量未知为 0、费用未知为 19。该累计值保留原 C22 开销，1,471,769ms 的 elapsed 包括原暂停间隔，不能当作纯 C23 自然耗时或节省。最终主答复仍复制了子摘要中的错误，把第二条件写成新观察，并将应用名误写为 `com.apple.apple.calculator`；宿主实际选中的第二项是原首观察 45。验收成功不等于答复描述准确，这项缺口仍保留。原 C22 快照、三次 C21 失败及正式 16 个样本均不替换。

随后同一 C23 构建的新任务补充记录如下；失败、取消和规划不可靠均保留，不由下一轮成功替换，也不计入正式 16 个样本：

| 父任务及原始指标 | 实际结果 | provider / 已知 token |
| --- | --- | --- |
| [b09a6376-adb6-4f55-9ffc-7450e6a8f943](../data/interaction-validation/2026-10-08/interaction-metrics-b09a6376-adb6-4f55-9ffc-7450e6a8f943.json) | 旧主 scope 的子任务在派发前受 C18 历史未决效果保护，EFFECT_UNKNOWN、0/2；父自然 COMPLETED / BLOCKED 0/2。没有清除或升级旧 UNKNOWN。 | 7 / 28,649（24,585 输入、4,064 输出）；含维护 1 次，用量未知 0、费用未知 7 |
| [bac2d0bf-6d4e-4d76-8518-a0fb764eb44b](../data/interaction-validation/2026-10-08/interaction-metrics-bac2d0bf-6d4e-4d76-8518-a0fb764eb44b.json) | 新主 scope 本轮实际 example.com 多语正文不含原要求的 Example Domain / h1 / More information，合同未修改。验证操作者通过真实 GUI 取消子任务；父事件 25 USER_REQUEST、CANCELLED / UNVERIFIED 0/2，子事件 178 PARTIAL 1/2 后 179 PARENT_CANCELLED。这是实际显式取消，不是自然失败或审批超时。 | 17 / 110,608 已知（106,318 输入、4,290 输出）；在途子 MODEL 的 1 次用量未知、1 次未结算仍保留，费用未知 17 |
| [f5d1170d-4514-47de-b35f-c77e6b1c0cba](../data/interaction-validation/2026-10-08/interaction-metrics-f5d1170d-4514-47de-b35f-c77e6b1c0cba.json) | 首次规划只返回 `{`，唯一修复超时，事件 15 PAUSED；没有可靠可执行合同或子任务。空合同的零未满足条件不代表完成。 | 2 次辅助调用 / 7,679 已知（7,678 输入、1 输出）；修复用量未知 1，费用未知 2 |
| [5f750220-71e9-43e0-9668-3c70d43d56b3](../data/interaction-validation/2026-10-08/interaction-metrics-5f750220-71e9-43e0-9668-3c70d43d56b3.json)，子 `b37749a7-7dc3-4004-b43c-9d503349a375` | 新 IANA 任务保留可靠原 navigate→observe Example Domains 两项条件；父事件 69/70、子事件 78/79 均 VERIFIED_COMPLETE / COMPLETED 2/2。一次委派、一次结果读取及父收尾成立，可记纯浏览器 HOST_PASS；没有桌面操作或切换。 | 12 / 69,058（64,991 输入、4,067 输出）；含维护 1 次，用量未知 0、费用未知 12 |

四份原始导出均已实际保存并独立核对；未知用量不能按零计算，较早失败或操作者取消不能当成成功任务的耗时或 token 节省。IANA 任务选择与实际页面相符的独立新目标，不修改或补造前述 example.com 任务的合同及回执。

IANA 任务的结果读取事件 36 返回完整 995 字符，下一 MODEL 保留该交换。最终答复中的标题、文章文字和链接分别对应真实 title 56、正文 61、href 66；链接原值为相对路径 `/go/rfc6761`，解析为 `www.iana.org/go/rfc6761`，没有点击该链接。这里三项业务事实核对不同于原合同的两项验收条件，也不证明 RFC 目标页已打开。[该次纯 C23 导出](../data/interaction-validation/2026-10-08/interaction-metrics-5f750220-71e9-43e0-9668-3c70d43d56b3.json) SHA-256 为 `b81193bfc24776b9005e7ba60ea7a71b9633407a3f5c505396c08068a7daf69f`，执行链 11 次、可信维护 1 次，共 12 次 provider；用量完整，费用仍未知。这个独立纯浏览器成功不覆盖桌面写入恢复、任意窗口关系或全部答复准确性，也不能由单次补充样本声明总体收益。

指标可通过执行 Inspector 导出，统计父子及辅助模型、视觉与重试，缺失用量单列未知。已采集的配对结果必须按构建 cohort 分别解释，不能把不同构建的样本合并，或由少量结果声称稳定的 token 节省、成功率提升或耗时改善。验证基线仅通过本地启动参数固定历史已发布的默认 Agent 版本，不修改其定义或既有 Run。

C11 的 schema-2 指标把原父子执行链保留为 `executionFamily`，另列 `associatedMaintenance`；`aggregate` 和 `byPurpose` 包含读取时可见且可信关联的维护调用及其真实后代，每个 Run 只计一次。维护关联须由宿主一次性许可绑定完整创建请求，再由首次创建事件记录准确来源；调用方自填 origin 字段不能取得此资格。查询重验原 Run、可读派生 scope、用户/工作区、任务目的和创建标记。这只是用量归因，不建立权限、预算或控制的父子关系；主任务业务耗时、验收和控制统计继续使用原执行链。

旧 schema-1 导出和没有强创建标记的历史维护 Run 不回填或重写；可准确归属的旧维护开销在验证文档手工另列。即使 `terminalFamily=true`，新指标仍标 `provisional=true`：后续维护和迟到物理用量可能继续增加。零金额且缺报价来源仍是费用未知；读取时快照不能证明实际免费，也不保证未来全部辅助调用已计入。
