# 模型服务配置

## 新建或编辑

模型服务在设置页内完成连接、模型选择和保存。聊天模型菜单的“添加”定位到同一个设置编辑器；不会另建一套配置流程。
已有服务先显示摘要和模型目录，点击“编辑”后原位修改。后台刷新不重建正在编辑的草稿。

信息结构参考 [Cherry Studio 的服务商与模型管理](https://github.com/CherryHQ/cherry-studio-docs/blob/main/pre-basic/settings/providers.md)
和 [Open WebUI 的兼容服务配置](https://docs.openwebui.com/getting-started/quick-start/connect-a-provider/starting-with-openai-compatible/)。
这里只参考分层操作及目录失败后的手动入口，地址规则和保存边界仍采用 JavaClaw 的契约。

1. 点击“添加服务”，选择常用服务商或“自定义服务”。预设只填写非敏感连接字段，不创建服务、不请求网络，也不声明模型能力。
2. 确认名称、接口协议、API 地址和鉴权方式，填写 API Key。协议始终可见；超时、重试及协议专属参数位于“高级设置”，只显示相关参数。默认请求超时为 60 秒、最大重试次数为 0。
3. 展开“模型管理”，点击“获取模型”读取目录，或直接“手动添加”完整模型 ID。读取目录不是保存的前置条件，不会创建服务或保存密钥。
4. 搜索名称或完整 ID，使用“全部模型／已选模型”筛选；复选框决定保存哪些模型，选中行显示当前模型属性。指定用途、图片支持声明和向量维度；未知用途标记为待完善，程序不会按模型名称推测。多选时可展开“批量用途”。手动输入必须点击“添加”或按 Enter 确认。
5. 点击固定底栏的“保存配置”，一次提交连接、凭据、模型和启停状态。新建默认勾选“保存后启用”；编辑保留当前启停状态，取消勾选可保存为停用。

至少选择一个模型且完成属性后才能保存。保存成功留在当前服务，显示“已保存，包含 N 个模型”，重新读取权威数据并清除草稿标记。
保存不改变当前对话或项目默认模型，也不升级已保存的精确模型引用。纯向量服务可以保存；聊天模型菜单只显示声明了聊天用途的模型。

“放弃修改”清理当前草稿并回到服务摘要。服务切换、添加服务、页面导航和窗口关闭均受草稿保护；先保存或显式放弃后才能离开。
提交中或结果尚未确认时优先阻止离开，不能用“放弃修改”假定撤销已经发送的请求。

## 服务商预设与地址

预设定义位于 Desktop，只提供以下初值；选定后仍可修改名称、协议和完整 API 根地址。

| 服务商 | 默认协议 | API 根地址及鉴权 |
|---|---|---|
| 阿里云百炼 | OpenAI 兼容 | 必须显式选择地域；北京为 `https://dashscope.aliyuncs.com/compatible-mode/v1`，新加坡为 `https://dashscope-intl.aliyuncs.com/compatible-mode/v1`；API Key |
| DeepSeek | OpenAI 兼容 | `https://api.deepseek.com`；API Key |
| 硅基流动 | OpenAI 兼容 | `https://api.siliconflow.cn/v1`；API Key |
| OpenAI | OpenAI 兼容 | `https://api.openai.com/v1`；API Key，可明确改选 Responses |
| Anthropic | Anthropic Messages | `https://api.anthropic.com`；API Key |
| Gemini | Google Gen AI | `https://generativelanguage.googleapis.com`；API Key，API Version 初值为 `v1beta` |
| OpenRouter | OpenAI 兼容 | `https://openrouter.ai/api/v1`；API Key |
| Ollama | OpenAI 兼容 | `http://localhost:11434/v1`；无鉴权 |
| 自定义服务 | OpenAI 兼容 | 手动填写完整根地址；默认 API Key |

百炼的其他地域及业务空间选择“填写控制台完整 Base URL”，复制控制台给出的完整地址；程序不猜测业务空间域名或拼接路径。
地域与密钥必须匹配。地址旁可展开“查看实际请求地址”，预览模型目录、对话、向量或 Responses 的最终路径；预览不发送请求。

地址校验沿用项目领域约束：必须是 API 根地址，不能含用户信息、查询参数、片段或 `/models`、`/chat/completions` 等资源路径。
API Key 地址使用 HTTPS；显式本机回环地址可用 HTTP。Anthropic SDK 会追加 `/v1`，因此其预设根地址不含 `/v1`；预览如实反映重复版本段，不静默改写配置。
Gemini 的版本单独填写在 API Version 中，根地址不包含版本。不同协议不会沿用其他产品的特殊路径拼接约定。

## 密钥与鉴权

API Key 密文和主密钥统一存放在本地数据库，不访问系统钥匙串。仅支持新建数据库及其后续重启；
旧库不升级、不导入，需使用全新的数据目录重新配置。详见[本地密钥库](local-vault.md)。

- 编辑时只显示“已配置”，不回显已有密钥；通过“替换密钥”展开新值输入。保留已有密钥时不要求重复输入。
- 更换地址、协议、鉴权方式或预设地域时清除临时输入，并使工作流内已准备的秘密及旧目录请求失效；已选模型和非敏感属性保留。
- 切换为无鉴权时，必须明确确认最终保存将清除原密钥。无鉴权仅适用于自定义 OpenAI 兼容地址。
- 准备连接时密钥从输入控件转交当前编辑工作流，控件立即清空；再次获取目录可以复用该编辑会话中仍有效的临时秘密。普通名称或请求参数修改不会把密钥发送到其他目的地。
- 保存提交、放弃、编辑器关闭或会话失效时按生命周期清理秘密；秘密不写入普通草稿、诊断或日志。重连只恢复非敏感草稿，已清除的密钥需重新输入。

## 目录读取和失败恢复

目录读取使用已有服务端网络边界，不发起模型推理。目录为空、接口不支持或读取失败时仍可手动添加。
搜索和刷新保留已选模型及属性，精确模型 ID 不按名称合并；更改连接后不接受旧请求的迟到结果。
“已获取目录”只证明目录请求完成，图片支持属于用户声明；显式联网验证结果另行展示，不能将三者混为“模型可用”。

| 状态 | 展示及恢复方式 |
|---|---|
| 字段未填完整 | 在对应字段显示中文说明，展开相关区域并定位首个错误 |
| 目录读取失败 | 提示检查连接、重试或手动添加，不丢弃选择 |
| 保存准备失败、尚未提交 | “配置未能提交，填写内容已保留”；没有已提交身份，不查询回执 |
| 临时密钥已清除 | 原页展开连接设置，聚焦密钥字段，补填后继续保存，保留模型选择 |
| App Server 断线 | 明确提示连接失效并提供“重新连接”，不误报需要升级 |
| 能力检查尚未完成或失败 | 分别提示检查中或重试连接，与明确不支持区分 |
| 服务端明确不支持统一配置 | 提示升级 App Server，不降级为分阶段写入 |
| revision 冲突 | 保留草稿，显式“重新读取”前确认放弃当前修改，不自动覆盖最新配置 |
| 已提交、结果未知 | 锁定草稿，只查询原保存回执，不再次密封或重发保存 |

底部反馈使用简短中文，显示区域最多三行；“诊断分类”按需展开，只显示稳定错误码或客户端失败分类。
异常原文、Java 类型、堆栈及秘密不进入正文或 Tooltip。目录刷新及迟到目录结果不覆盖已有保存失败反馈。
普通目录读取可取消，不作为阻止整个设置中心离开的在途写入；未保存草稿仍受通常的离页保护。

回执查询使用原幂等身份。尚未找到回执表示结果仍不确定，不能当作“没有保存”；重连也不能重建新的提交身份。

## 保存后的管理与使用

服务摘要中的所选模型旁提供容量、默认向量绑定、本地检查和显式联网验证，归档保留在服务级危险区域。
这些动作使用各自既有命令；联网验证可能产生模型费用，仍需显式确认，目录读取不触发该验证。

“使用此模型”是独立操作，打开后由用户确认目标工作区和对话，再点击使用；仅打开窗口不会自动应用。
界面说明该动作会影响目标对话及新对话的最近选择，不修改项目默认模型。服务配置保存不触发“使用此模型”。

## 实现与视口边界

调用仍经过 JavaFX 页面、工作流或 Presenter、SDK、Protocol v3 和 App Server Provider 服务。Desktop 不接触 Vault、数据库或模型 HTTP。

- `ProviderConfigurationEditor` 由模型设置页承载，保留非敏感表单和模型选择；`ProviderSetupWorkflow` 管理临时秘密、预览取消及原子提交身份。旧两步窗口保留兼容入口，不再作为设置和聊天新增的主流程。
- `ManagedSettingsPage.ownsViewport()` 默认返回 `false`；模型页返回 `true`，管理中心不再包装整页 ScrollPane。连接表单可独立滚动，模型目录使用独立虚拟化 ListView，底部动作位于固定槽位。
- 服务浏览区域小于 900 逻辑像素时将侧栏收为顶部搜索和选择器；模型区域小于 760 逻辑像素时详情移至列表下方。阈值针对页面可用区域，不是完整窗口宽度，不提高设置窗口的 880×620 最小尺寸。
- 高度有限时连接与模型分区可互斥展开，折叠只改变展示，不销毁草稿。短错误、长 ID 和键盘恢复仍需以真实完整设置窗口验证，不能以静态绘图代替。
- 窄窗详情位于目录下方的独立滚动视口，键盘聚焦字段时自动带入可见区；手动添加和批量用途在紧凑高度下暂时使用目录空间，完成后恢复原列表与选择。
- `provider/model/preview/start|read|cancel` 只读取未保存草稿的目录，不写数据库、Vault 或运行时注册表；原 `provider/model/discovery/*` 仍只读取已保存的精确 Provider 版本。
- `provider/configuration/save` 通过既有候选准备与 Vault 事务端口一次提交完整配置；`provider/configuration/result` 读取已有 `COMMAND_RESULT` 回执，不新增数据库表。

预览与保存各有独立密封用途。SDK 冻结最终请求的密文、幂等键和摘要，保留凭据反重放机制。
摘要使用包含 `expectedRevision` 和 `payload` 的规范化 Map，避免反射访问私有 record，不扩大 JPMS `opens`，并维持既有摘要与回执协议。
Anthropic 目录自定义传输保留 SDK `AnthropicBackend.prepareRequest` 步骤，补齐默认 `anthropic-version` 并保留显式覆盖；禁止重定向、取消、响应大小和请求预算仍在原 HTTP 边界执行。
服务端仍在提交后激活资源，候选失败时回滚并释放。Harness、Turn 冻结、权限审批、工具和 Sandbox 的机制不变。

## 回归与证据维护

回归入口包括 `ProviderPresetTest`、`ProviderSetupConnectionFormTest`、`ProviderConfigurationEditorTest`、
`ProviderSetupModelSelectionTest`、`ProviderSetupModelFormTest` 与 `ManagementCenterInteractionTest`。
`ProviderConfigurationEditorLayoutTest` 使用真实 `ManagementCenterWindow`、完整导航和内存 Gateway，覆盖 880×620、
1040×720、1440×900，载入 1,000 条模拟目录并检查选择、布局与失败恢复。
设置 `javaclaw.provider.evidence` 为输出目录可生成该测试的连接、模型、保存准备失败及返回模型区 PNG；输出应放在 `target/` 等构建目录中。
每张图的 `.metadata.txt` 同步记录本次实际 Window outputScale/renderScale、Scene 逻辑尺寸和截图像素尺寸，供核对原生 DPI；不据此推断其他缩放比例已经验证。

这些说明列出测试入口，不代表本次验证已经通过。交付记录须另外说明实际命令、结果、截图及平台；不得将 macOS 截图或逻辑尺寸覆盖等同于 Windows、Linux 或所有原生 DPI 的验收。
当前单页编辑器的执行结果与截图见[单页模型设置验收](evidence/provider-settings-single-page-2026-09-21/README.md)。
历史两步流程的证据保留在[原完整配置验收](evidence/provider-configuration-2026-09-20/README.md)和
[模型选择与保存反馈验收](evidence/provider-configuration-ux-2026-09-20/README.md)，不能代替本次单页编辑器的验证结果。
