# 网站会话设置页验证

日期：2026-09-16。环境为 macOS arm64、JDK 25、Maven 3.9.0。保留工作树已有未提交修改，未提交 Git。
测试使用固定 SDK 数据和本地替身，不调用付费模型或真实网站。

## 实现边界

- 网站列表在上方，显示名称、网址和启用状态，提供新建、刷新和分页，表格高度最多 240 逻辑像素。
- 详情跟随唯一网站选择，包含基本信息、账号与登录、高级配置；三个 TitledPane 互斥，折叠不重建表单。
- 新建模式独立于所选网站；成功创建后通过权威目录跨页定位新网站。删除当前网站后清空详情。
- 共享 HTTP 凭据的创建、轮换与永久清除使用独立对话框，与网站绑定、账号用户名密码明确区分。
- Desktop 通过 SDK 使用已有 ViewSchema 表单、校验、动作和 revision；平台布局接口不新增 wire 节点、
  migration 或权限能力。`login.view` 的可选 `siteId` 保持旧无参数行为。
- 切换前确认所有分区草稿，保存期间锁定操作目标。普通提交失败保留草稿，秘密提交后立即清空；
  页面关闭和 Workspace 变化清理临时状态，并拒绝迟到回执。

## 定向验证

相关范围包括 Builtin 11 项及 Desktop 55 项测试，全部在最终门禁中通过，0 失败、0 错误、0 跳过。
独立截图检查通过，并断言账号展开时其他分区收起、基本区实际高度小于 80 逻辑像素。

| 场景 | 断言范围 |
|---|---|
| 网站选择 | 统一目标、账号按需读取、取消草稿切换后恢复选中行与命令身份 |
| 新建和删除 | 创建后跨页定位、删除当前网站后清空详情 |
| 草稿与提交 | 保存失败保留文本、pending 期间禁止切换、多表单草稿不会互相覆盖、Card 不能冒充表单来源 |
| 账号与秘密 | 账号元数据和秘密独立提交、提交即清空、折叠须确认、取消保留密码 |
| 作用域 | 网站或 Workspace 变化、页面关闭后迟到结果不重建旧状态 |
| 关闭与重开 | 关闭时退订、旧句柄通知丢弃、重开重查目录与数据、同一 Schema 保留选择及展开分区、已发送命令不重放 |
| 登录 | 可选 siteId 过滤、无参数兼容、局部失败和重试、危险确认与版本绑定 |
| 默认渲染 | 未配置平台布局的页面保持既有行为，隐藏节点仍参与资源清理 |

## 实际界面证据

使用构建生成的真实网站管理 ViewSchema、生产控件和 CSS，数据为固定的示例网站。
每个状态均在 880×620 和 1040×720 的真实 JavaFX 窗口捕获；截图辅助器等待正常 pulse，避免复用 Scene 时捕获上一帧。
`SiteSettingsWindowEvidence` 直接打开生产 `ManagementCenterWindow`，保留导航、工作区栏和滚动容器，
并检查内容不超过实际视口、没有可见水平滚动条。长表单沿用管理页滚动方式，列表保持高度上限。

| 状态 | 880×620 | 1040×720 |
|---|---|---|
| 未选择网站 | [截图](site-settings-layout/site-window-empty-selection-880.png) | [截图](site-settings-layout/site-window-empty-selection-1040.png) |
| 基本信息 | [截图](site-settings-layout/site-window-basic-880.png) | [截图](site-settings-layout/site-window-basic-1040.png) |
| 账号与登录 | [截图](site-settings-layout/site-window-accounts-880.png) | [截图](site-settings-layout/site-window-accounts-1040.png) |
| 滚动后的账号配置 | [截图](site-settings-layout/site-window-account-details-880.png) | [截图](site-settings-layout/site-window-account-details-1040.png) |
| 空网站目录 | [截图](site-settings-layout/site-window-empty-list-880.png) | [截图](site-settings-layout/site-window-empty-list-1040.png) |
| 数据读取失败 | [截图](site-settings-layout/site-window-load-error-880.png) | [截图](site-settings-layout/site-window-load-error-1040.png) |

图片摘要见 [sha256.tsv](site-settings-layout/sha256.tsv)，同时保留 10 张独立内容区回归截图。
这组截图不代表真实网站登录成功，所有网站和账号数据均来自固定 SDK 夹具。

本次构建导出的 [网站管理 ViewSchema](site-settings-layout/site-management.json) 与图片一并保留。
复现时使用 Desktop Surefire XML 的 `java.class.path`，运行测试类路径中的
`com.javaclaw.desktop.settings.SiteSettingsWindowEvidence`，并设置
`-Djavaclaw.browser.evidence=<输出目录>` 和 `-Djavaclaw.site.evidence.schema=<上述 JSON 路径>`。
该入口要求本机 JavaFX 窗口环境，使用内存偏好，不连接 App Server。

## 整仓库门禁

`mvn spotless:apply`、实际 diff 检查、`git diff --check`、`mvn spotless:check checkstyle:check` 和完整
`mvn clean verify` 均已通过。最终完整构建在 2026-09-16 11:20:07 +08:00 完成，耗时 22 分 22 秒，
15 个 Reactor project 全部成功；日志位于 `/tmp/javaclaw-site-settings-verify.log`。

汇总最终 clean 生成的 Surefire/Failsafe XML：**3372 项测试记录，3346 项通过，26 项条件跳过，0 失败、0 错误**。
Desktop 包含 752 项单元测试与 1 项管理中心 Golden 集成测试，全部通过。跳过项分布为 Native Hosts 17 项、
App Server 6 项、Packaging 3 项，不计为通过。覆盖率、依赖与架构检查、Golden、发行打包门禁均通过，未降低规则。
模块计数与跳过明细见 [机器可读记录](site-settings-layout-validation.json)。

首轮完整构建发现截图用例在未启用截图时读取了尚未布局的高度；补充显式布局后，最终完整构建通过。
最终构建前后核对 Desktop 与 Built-in Extensions 的 723 个 Java 源文件摘要，没有源码变化；没有改写 Golden 基准。

## 真实限制

- 本轮界面实测平台为 macOS arm64；macOS x64、Linux arm64/x64 和 Windows x64 未执行。
- 登录会话使用按需查询、局部缓存及显式刷新，不声明持续实时推送。
- 没有通过真实网站、付费模型或真实账号密码进行验收。
- 整仓库统计包括用户已有未提交修改，不能将全部测试数量归因于本次页面变更。
