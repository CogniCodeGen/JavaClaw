# 从浏览器添加网站：验证记录

日期：2026-09-20。环境为 macOS 26.5.2 arm64、JDK 25、Maven 3.9.0。
保留工作树已有未提交修改，不提交 Git。本功能测试使用固定数据、本地 H2/Vault、协议子进程或 Playwright 替身，不调用付费模型或真实网站。

## 功能与架构

网站列表提供“添加地址”入口，输入 HTTPS 地址后申请独立的 Workspace 登记窗口。
用户在窗口中登录，返回设置页确认网站名称和本次登录表单候选，点击“完成添加”。
成功后网站、默认账号、登录态、选中的凭据和幂等回执在同一事务提交，列表跨页定位新网站。
不选择密码候选时只保存登录态；取消、过期和失败不会创建占位网站。

调用沿用 `Desktop → SDK → Site Extension → App Server → Browser Worker`。
秘密只通过 Worker 私有帧进入 Vault，普通 DTO 与界面只接收候选 ID、脱敏地址和标题。
临时浏览器不读取个人浏览器配置，不借用聊天 Thread，不扩大普通工具授权，不新增数据库 migration。

登记最长 15 分钟，每个 Workspace 同时一个窗口。追加 HTTPS 来源需要用户明确授权；
原有私网授权仍独立检查活动状态、精确来源、完整 DNS 集合及版本。
来源授权成功但回执丢失时，按查询到的代次和来源恢复操作；保存结果未知时只查询，不自动重复提交。
Worker 完成或取消后保持到宿主回收进程树，不以子进程自行退出充当原生清理证据。
无法确认清理的会话记为 `FAILED`，不会伪称正常取消或过期。

## 验证范围

| 层 | 覆盖的场景 |
|---|---|
| 契约与扩展 | HTTPS 与精确来源校验、原始路径转义保留、设置页作用域、命令身份、查询与写入分离 |
| Worker | 当前地址/标题一致快照、跨导航候选、同来源选择、旧页面拒绝、私有帧清零、状态过滤、未授权重定向、宿主回收 |
| 服务端 | Workspace 隔离、启动去重、过期、跨重启拒绝恢复窗口、原子保存与回滚、重复来源、回执恢复、撤销与清理失败 |
| 网络 | 逐次租约检查、旧代次失效、待授权提示、私网撤销/过期与有效授权并存、DNS 集合精确匹配 |
| SDK 与界面 | 类型化调用、草稿取消、成功选择、关闭与迟到回执、候选退休、轮询保留输入焦点、未知授权恢复、未知保存不重放 |

## 实际界面证据

使用真实 JavaFX 生产控件及 CSS，数据来自固定 SDK 夹具。
管理窗口沿用已有 `SiteSettingsWindowEvidence` 和网站 ViewSchema 夹具，验证 880×620、1040×720。
登记窗口使用 `SiteRegistrationWindowEvidence`，验证常规 640×620 和窄窗 480×400；长内容可滚动，关闭按钮始终可达。

| 状态 | 截图 |
|---|---|
| 已选网站与新增入口 | [880×620](site-registration/settings/site-window-basic-880.png)、[1040×720](site-registration/settings/site-window-basic-1040.png) |
| 空目录与新增入口 | [880×620](site-registration/settings/site-window-empty-list-880.png)、[1040×720](site-registration/settings/site-window-empty-list-1040.png) |
| 输入地址 | [常规窗口](site-registration/registration-empty-640.png)、[窄窗口](site-registration/registration-empty-480.png) |
| 登记中 | [常规窗口](site-registration/registration-active-640.png)、[窄窗口](site-registration/registration-active-480.png) |
| 窄窗中的操作与失败状态 | [操作区](site-registration/registration-active-controls-narrow.png)、[失败状态](site-registration/registration-failed-status-narrow.png)、[底部来源区](site-registration/registration-failed-origins-narrow.png) |

截图辅助器使用 Desktop Surefire XML 中的 `java.class.path`，设置
`-Djavaclaw.browser.evidence=<输出目录>`。管理窗口还需设置
`-Djavaclaw.site.evidence.schema=docs/evidence/site-settings-layout/site-management.json`。
它们不连接 App Server、真实浏览器或模型，截图不能证明真实网站登录成功。
共保留 21 张本功能界面截图，摘要见 [sha256.tsv](site-registration/sha256.tsv)。

## 整仓库门禁

`mvn spotless:apply`、实际 diff 检查、`git diff --check`、`mvn spotless:check checkstyle:check` 和完整
`mvn clean verify` 均通过。最终完整构建在 2026-09-20 14:52:58 +08:00 完成，耗时 22 分 14 秒，
15 个 Reactor project 全部成功；日志位于 `/tmp/javaclaw-registration-verify-final.log`。
构建仅增加离线模式及截图输出路径，没有测试过滤、跳过标志或门槛调整。

最终 clean 生成的 Surefire/Failsafe 报告共 **3471 项测试记录：3445 项通过，26 项条件跳过，0 失败、0 错误**。
网站登记相关的 93 项测试全部通过；Desktop 的 774 项单元测试和 1 项 Golden 集成测试全部通过。
跳过项为 Native Hosts 17 项、App Server 6 项、Packaging 3 项，不计为通过。
依赖、架构、覆盖率、Golden 和打包检查均通过；App Server 最终行覆盖率 91.52%、分支覆盖率 80.24%。

构建前后核对了 2732 个源码及资源文件，内容均未变化。原有 Provider、Vault 和相关网关修改未被本轮改写；
未改写历史 migration、官方 Schema 或 Golden 基准。整仓库统计包含原有工作树修改，不能全部归因于本功能。
模块计数、登记测试清单、覆盖率及跳过明细见[机器可读记录](site-registration-validation.json)。

首轮完整构建已执行的功能测试没有失败，但 App Server 分支覆盖率为 79.98%，低于既有 80% 门槛，构建按规则停止。
随后新增两个登记边界测试类，验证身份与租约篡改、持久状态冲突、秘密载荷不一致和回执恢复；
没有修改生产逻辑或降低规则。定向测试新增覆盖 31 个分支，重跑完整构建作为最终门禁。

## 真实限制

- 可见浏览器沿用现有原生发布门禁：当前三个平台的常驻进程树证明均为 `UNVERIFIED`；本机 macOS 的私有 bootstrap namespace 也未通过先前探针。因此目前生产入口会拒绝真实浏览器启动，本轮没有绕过门禁。
- 本轮执行平台仅为 macOS arm64。Windows、Linux、macOS x64 未执行，不宣称跨平台原生验收通过。
- 自动捕获仅识别本次窗口可配对的用户名和 password 输入；验证码、新密码字段、无法配对的分步登录不作为密码候选，SSO 来源的密码不会保存成另一来源的网站密码。
- 登录态包含 Cookie、localStorage、IndexedDB，不包含 sessionStorage；保存完成不代表能判断外部网站登录成功。
- 浏览器必须在后续真实原生验收完成后才能发布可用能力；相关现状见[原生探针记录](browser-interactive-native-2026-09-14.md)。
