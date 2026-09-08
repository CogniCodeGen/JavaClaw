# V3 第二轮八项审查修复验证

日期：2026-09-08。状态：八项修复已完成，全部模块门禁已通过。
本记录只描述第二轮审查后的修复，不替代[前一轮验收记录](chat-webview-memory-v3-review-fixes-validation.md)。

## 修复与维护测试

| 项目 | 修复后的契约 | 维护回归 |
| --- | --- | --- |
| R1 流状态回退 | 对账在 UI 提交时读取最新 SDK 流快照；旧 RPC 结果不能覆盖已显示的正文或流终态 | DesktopTurnStreamCoordinatorTest |
| R2 历史中间缺口 | 不连续尾页不直接拼入旧窗口；跟随模式保留新连续尾页以便前翻，暂停跟随保留阅读窗口和锚点 | TranscriptStateTest |
| R3 启动执行限制 | 学习配置仍拥有精确执行选择；普通默认启动及相同选择可用，不匹配的覆盖和更低 token/时间预算明确拒绝，较宽预算收窄到固定上限 | MemoryLearningResourceTest |
| R4 幂等批次决议 | 先兑现既有成功回执；替代 Job 已运行不影响原请求重放，新决议仍检查活动工作单元安全边界 | MemoryBatchRecoveryTest |
| R5 Windows 文件路径 | FileChange 的宿主 Path 在进入 Worker 前转换为协议分隔符；POSIX 文件名中的反斜杠仍拒绝，避免改变指向 | DocumentPreviewServiceTest |
| R6 Markdown 空格目标 | 聊天、文档及相对图片共用目标分类；只在 URI 分类/外部导航时编码空格，资源 RPC 保留原始 href 供服务端校验 | MarkdownLinkTargetTest、ChatSurfaceTest、DocumentPreviewPaneTest、DocumentPreviewLoaderTest |
| R7 旧客户端图谱 | 未协商图谱能力时，独自承担主从绑定的 Graph 转为原有单选列表；保留节点身份、字段、详情及命令 revision 绑定 | ViewCapabilityProjectionTest |
| R8 展开与对账 | 邻居查询与普通加载共享 pending/epoch；查询期间延后通知和定时对账，成功合并窗口后读取权威数据，失败/离页释放状态 | ViewGraphBrowsingPageTest、ViewSchemaRefreshTest |

继续使用 Desktop → SDK → App Server、Protocol v3、data-v6 和现有 14 个业务模块。
本轮没有新增依赖、RPC 或数据库迁移，没有修改 UI 的 CSS、FXML 或 Golden 参考图。
测试使用固定模型替身、临时数据库/工作区和可控 Future，不调用付费模型。

## 修复前后定向验证

- 流与历史：新回归捕获正文/流终态倒退和不可回填历史缺口；修复后两个维护测试类共 11 项通过。
- Memory：旧生产实现 11 项通过、3 项预期失败；修复后相关 Memory 测试共 65 项通过。
- Markdown：同一组 Desktop 测试在旧实现上 11/15 通过，四个空格目标场景失败；修复后 15/15 通过。
  包括真实 WebView DOM 点击、相对图片有界解码和资源关闭。服务端真实 H2 与隔离文件 Worker 三个场景通过。
- 兼容投影：真实 Memory Schema 在旧实现上因 Graph 无法提供旧式单选而失败；修复后三项回归通过。
  另以 HEAD 原始 ViewSchemaPolicy 校验实际降级结果，得到 `OLD_CLIENT_ACCEPTED`。
- 图谱：修复前受控邻居请求会因周期对账丢弃新增节点；维护回归覆盖定时准入、失效通知、失败及离页迟到响应。

以上临时定向运行用于确认缺陷与修复因果关系，完整构建结果单独记录，不用临时编译替代门禁。

## 完整门禁

```sh
mvn -s /private/tmp/javaclaw-webview-maven-settings.xml spotless:apply
mvn -s /private/tmp/javaclaw-webview-maven-settings.xml spotless:check checkstyle:check clean verify
mvn -s /private/tmp/javaclaw-webview-maven-settings.xml -pl javaclaw-desktop spotless:apply
mvn -s /private/tmp/javaclaw-webview-maven-settings.xml \
  -f /private/tmp/javaclaw-review-round2-fixes/verified-reactor-install/pom.xml initialize
mvn -s /private/tmp/javaclaw-webview-maven-settings.xml \
  -rf :javaclaw-desktop spotless:check checkstyle:check clean verify
```

完整门禁分两段完成。首次根 Reactor 中父 POM 和 12 个上游模块通过，Desktop 在新增刷新回归访问测试夹具
私有计数字段时编译失败；随后仅将该夹具字段调整为包内可见并重新格式化。核对本轮已通过 verify 的上游
JAR/POM 哈希后，将这些产物安装到 Maven 本地缓存，从 Desktop 续跑完整 `clean verify`，Desktop 与
Packaging 均成功。没有跳过测试或降低规则、覆盖率和 UI 对比阈值。

14 个业务模块合计发现 **2,603 项测试：2,578 项通过，25 项按现有条件跳过，0 失败、0 错误**。
Spotless、Checkstyle、依赖边界和 JaCoCo 覆盖率门禁均通过。主要受影响模块结果如下：

| 模块 | 测试总数 | 通过 | 跳过 |
| --- | ---: | ---: | ---: |
| Built-in Extensions | 216 | 216 | 0 |
| App Server | 1,047 | 1,041 | 6 |
| Desktop（含 Golden IT） | 380 | 380 | 0 |
| Packaging | 89 | 87 | 2 |

Desktop 的 379 项单元/验收测试及 1 项 Golden IT 全部通过；后者实际生成九主题、三密度、两个窗口规格的
**54 张截图，与本轮开始时已有参考图逐字节一致**。图谱展开/对账 5 项、普通刷新 7 项和 WebView 生命周期
2 项回归均通过。没有把前一轮的外部 UI 回放次数重复计作本轮结果。

对格式化后的 1,630 个生产文件再次核验，验证期间无内容变化；本轮前后 77 个受保护文件
（CSS、FXML、历史迁移、Golden 图和清单）哈希未变。此前用户已有的源码、文档和截图改动继续保留。
临时 settings 只指定 Maven Central，未修改个人全局配置，未提交工作树。

原始证据：[首次门禁日志](chat-webview-memory-v3/review-round2/verify-initial.txt)、
[续跑成功日志](chat-webview-memory-v3/review-round2/verify-resume.txt)、
[上游产物哈希](chat-webview-memory-v3/review-round2/verified-reactor-artifacts.json)、
[测试汇总、跳过原因及截图哈希](chat-webview-memory-v3/review-round2/results.json)、
[受保护文件哈希](chat-webview-memory-v3/review-round2/protected-files.json)。

## 平台与限制

- 本轮运行平台为 macOS arm64、JDK 25、JavaFX 26.0.2。Windows/Linux 未实机验证；Windows 的宿主 Path
  回归已纳入维护测试，仍须由对应 Runner 运行。
- 旧客户端验证覆盖真实 Schema 与旧校验契约，不等同于安装运行完整旧版发行包。
- 没有以此次修复声称 WebView 性能、模型记忆质量、输入法、跨 DPI 或安装签名已完成验收。
- 前一轮记录的 WebKit 多进程探针偶发退出原因仍未确认；本轮不会通过关闭探针或放宽断言掩盖它。
