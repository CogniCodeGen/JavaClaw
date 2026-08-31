# JavaClaw 4.0 完整升级验证记录

日期：2026-08-29（Asia/Shanghai）。环境：macOS 26.5.2 arm64、Oracle JDK 25、Maven 3.9.0；
版本：`4.0.0-SNAPSHOT`。原完整门禁及安装包构建在 05:41:46 通过；发行目录桌面测试在 05:42:37 通过；
只读 DMG 内 `.app` 的桌面测试在 05:44:57 通过。唯一开发入口收敛后，完整门禁和安装包在 08:24:57 再次通过。

结论：本轮旧能力恢复链路、原风格桌面、统一内核、提示词与本机测试发行物完成回归。
不是“三个平台已经正式发布”的声明；需要外部 Runner、签名和真实业务授权的事项见第 7 节。
功能、源码入口及场景对应关系见 [能力验收矩阵](upgrade-acceptance.md)。

## 1. 实际执行命令

本次完整构建从空 target 开始，不复用旧 JAR；开启原生、性能和真实桌面门禁：

```bash
mvn -B -ntp spotless:apply spotless:check checkstyle:check clean \
  -Djavaclaw.require.native.sandbox=true \
  -Djavaclaw.performance.gate=true \
  -Djavaclaw.desktop.smoke=true \
  -Djavaclaw.distribution -Djavaclaw.native.package verify
```

实际运行另设置 `javaclaw.smoke.screenshot` 输出临时 PNG。原始日志：
`/private/tmp/javaclaw-final-gate-aug29.log`。

浏览器资源来自已安装的精确版本缓存；空缓存须先显式准备，不在应用启动时自动下载：

```bash
mvn -pl javaclaw-browser-service -am -DskipTests -Djavaclaw.browser.install verify
```

Linux 还需原生依赖与 Xvfb，见 README 和 CI；该准备步骤的 `skipTests` 不用于正式验收。
完整门禁没有关闭格式规则或跳过本机安全用例。

随后再次执行 `mvn spotless:apply spotless:check checkstyle:check`（05:43:28 通过）。
两次全仓格式运行之间，按路径排序的 Java/POM/FXML/CSS 内容摘要一致：
入口调整后重新执行 Spotless，当前摘要为
`80120dac26fb1522c5e291d1b8071973ccfe582df7e81d039a39b9cef8577bd0`。
Checkstyle 为零违规，`git diff --check` 通过。

所有业务测试使用临时数据根和合成输入。OpenAI 官方 SDK 只连接测试内的 loopback Responses JSON 服务，
Anthropic/Google Adapter 只连接 loopback SSE 服务，
不连接付费模型；桌面测试清除继承的模型凭据/服务连接环境，不读取现有用户数据库。

## 2. 最终测试结果

统计来自完整构建的 Surefire XML。随后重复执行的包内桌面 smoke 不重复累加到总数：

| 模块 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| api | 6 | 0 | 0 | 0 |
| protocol | 12 | 0 | 0 | 0 |
| agent-runtime | 79 | 0 | 0 | 0 |
| app-server | 151 | 0 | 0 | 0 |
| native-hosts | 7 | 0 | 0 | 0 |
| browser-service | 1 | 0 | 0 | 0 |
| client | 29 | 0 | 0 | 1 |
| desktop | 13 | 0 | 0 | 0 |
| packaging | 25 | 0 | 0 | 1 |
| 合计 | 323 | 0 | 0 | 2 |

上表是本轮更新后默认 `mvn verify` 的实际结果。两个条件式跳过项为
`ProtocolPerformanceGateTest` 和 `DesktopLaunchSmokeTest`；它们需分别显式设置
`javaclaw.performance.gate` 和 `javaclaw.desktop.smoke`。本文开头记录的历史发行门禁已使用这两个开关通过；
本轮未重新构建签名或安装包。

关键新增与恢复场景：

- Loop 有限迭代、无进展停止、Workflow 八节点、SDD 产物审批/补做、Schedule SKIP、恢复预算和检查点。
- 副作用先持久化意图，确认结果可复用，未知结果不自动重发；模型预算包含嵌套调用与子 Thread。
- Memory/Persona/关系/固定条目/版本恢复、Knowledge generation 原子切换、Skill Bundle/脚本/学习提案。
- Prompt 分层、快照/哈希、AGENTS.md 层级解析与 USER 注入、优化草稿采用、Plan 采用、
  原生 opaque/摘要窗口压缩与失败原子性。
- 文件/补丁/文档隔离 Worker、JShell 不继承 Server classpath、真实图片输入、OCR 页数和预算限制。
- PTY 有界控制帧、阻塞 stdin、异常输出与进程树回收；实际 Chromium 导航、页面引用、上传下载、PDF。
- 站点安全填充/加密会话、准确私网端点、通信预授权撤销；MCP/OAuth/Plugin 的恶意输入与版本约束。
- 脏 Git 工作区快照、临时 index、真实三方冲突/备份、取消/导出/清理幂等；非 Git 单写者。
- Runtime 目录维护租约覆盖同路径 Thread/fork，活动物理执行未退出前不能清理目录。
- OpenAI Responses 与 Anthropic/Google SSE 契约、Provider 专属 options、使用量与资源关闭；
  修复 stdout 污染和 SDK 关闭死锁。
- 源码/依赖/进程边界、SDK 公共 API、JSON-RPC transcript、恢复订阅与 500 个慢订阅者的有界队列。
- IDE、终端脚本和安装包共享唯一 `JavaClawLauncher.main()`；JavaFX 生命周期类不再暴露误导入口。

这里的原生结果属于 macOS arm64。Windows/其他平台测试中的契约分支，不能代替那些平台实际的 ACL、
reparse point、ConPTY、Named Pipe、namespace/seccomp 验证。

## 3. 数据安全与兼容性

新增五个 H2MigrationBackupTest 场景实际验证：

1. 升级前由 H2 生成一致性 ZIP；恢复副本具有旧表结构、旧 migration 记录和完整测试用户数据。
2. 无法创建私有备份时，在任何新增 DDL 前拒绝升级。
3. 历史 checksum 改变或历史版本出现空洞时，拒绝执行新 migration。
4. 新数据库不生成无意义备份；无新 migration 的重启不重复备份。
5. V8→V9 在删除旧规则表前完成备份；恢复该备份仍可读取旧表与测试数据。

备份目录为 `migration-backups/`，POSIX 0700/0600，Windows 使用所有者独占 ACL；保存 SHA-256，
不自动删除或覆盖恢复。ZIP 仅包含数据库，附件 blob、工作树和 H2 外主密钥须另外保留。
H2 DDL 可能隐式提交，因此不把事务回滚冒充完整升级恢复。
旧格式/未标记非空根的拒绝测试同时核验内容、目录时间和 POSIX 权限未改变。

原始十份 CSS 与 `509f197` 逐文件比对相同。历史兼容摘要仍为：

| 文件 | SHA-256 |
|---|---|
| V001__v4_baseline.sql | b640cc1e96c77f84c1d65bf2fc1c805bb745ca53a56e2ea2b3e7d56fa29bf96b |
| V002__worktree_lifecycle.sql | d7806ce4db85479fed0771e678012592c4ea3b5cc52769fcf5a20ee19dcc9091 |
| chat.css | bf6a431e8ce2ab0f7e795a8dcb7784f82d80b5a095c040017e902eb61fdb68e3 |
| controls.css | 32f620aec3e529f32e7a1ffb80a47ac6830a572fba66aed0c827fefea713c62b |
| MCP 官方 schema-2026-07-28.json | ef70b61f99b6d2e5e3b46863822eab08dff6a45bedc7a08914e0e5b133f40203 |

V003–V009 是独立增量 migration；V009 回归覆盖升级前备份、旧规则表删除和旧摘要窗口回填。
JSON-RPC v1 同步更新 golden schema，旧纯文本 Item 和旧 Plan 表示仍受 fixture 测试约束。
没有恢复旧 Runtime、读取/迁移/删除 3.x 数据或提交工作树。

## 4. 性能

`ProtocolPerformanceGateTest` 使用真实 AgentLoopKernel、PromptCompiler、Profile、工具治理与 H2 Journal，
同一假模型延迟 120 ms、4 次预热、每场景 40 次采样，两条路径交替运行并核对编译输入哈希和 transcript。
原始结果：`javaclaw-client/target/performance-gate.json`。

| 场景 | Direct p50 / p95（ms） | SDK/App Server p50 / p95（ms） | p50 / p95 增量 |
|---|---:|---:|---:|
| 普通 Turn | 132.186 / 137.801 | 133.642 / 145.968 | 1.10% / 5.93% |
| 单工具 Turn | 139.215 / 147.351 | 141.041 / 149.802 | 1.31% / 1.66% |

四项都低于 10%。本地管道测试衡量协议路径额外开销，不等于跨进程 UDS/Named Pipe 的全场景延迟，
也不等于真实模型质量或推理加速。500 订阅者用例证明队列有界与不阻塞执行，不冒充长期峰值内存压测。
未进行 3.x 全产品性能对照或付费模型 Prompt A/B。

## 5. 桌面与发行物验证

真实窗口分别使用开发 classpath、发行目录自己的 jlink/JAR、只读 DMG `.app` 内的 jlink/JAR 启动。
均经产品 Launcher → SDK → 独立 App Server，完成真实 Adapter 驱动的假服务流式对话、十二个管理页面、
Markdown 渲染、初始数据加载和正常关闭。没有主动关闭后的伪重连，也没有遗留自有 App Server。
截图位于 `/private/tmp/javaclaw-desktop-final*.png`、`javaclaw-packaged-desktop-final*.png` 和 `javaclaw-dmg-desktop-final*.png`。
目视核验原翡翠侧栏/标题/输入/卡片与领域表单；不宣称所有分辨率逐像素等价。

发行目录验证可重复执行（不带 distribution/clean 参数，避免先清掉要测的包）：

```bash
mvn -pl javaclaw-packaging -am \
  -Dtest=DesktopLaunchSmokeTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Djavaclaw.desktop.smoke=true \
  "-Djavaclaw.smoke.java=$PWD/javaclaw-packaging/target/distribution/runtime/bin/java" \
  "-Djavaclaw.smoke.classpath=$PWD/javaclaw-packaging/target/distribution/lib/*" test
```

Windows 将 Java 路径改为 `java.exe`；Linux 在 Xvfb 中执行。CI 已加入对应三平台的包内桌面门禁。

已生成的本机产物：

- `javaclaw-packaging/target/javaclaw-4.0.0-SNAPSHOT-macos-aarch64.zip`（约 576 MiB）。
- `javaclaw-packaging/target/native/JavaClaw-4.0.0.dmg`（约 582 MiB）与 `.pkg`（约 559 MiB）。
- `javaclaw-packaging/target/distribution/sbom.json`：CycloneDX 1.6，128 个组件。
- 同目录许可证清单、浏览器 bundle manifest，及 ZIP/安装包/SBOM/许可证 SHA-256 文件。

精确浏览器资源包括 Chromium/headless shell `136.0.7103.25`（Playwright revision 1169）、ffmpeg 1011。
184 个条目的大小、哈希、链接目标和可执行位已校验，资源总计 487,474,310 字节；不是启动时临时下载。
锁定版本的功能通过不等于这些依赖已完成漏洞审计。

ZIP/DMG/PKG/SBOM/许可证的 `shasum -a 256 -c` 全部通过；`unzip -tq` 无损坏，
ZIP 中启动器/Java/Chromium 可执行位与 Framework 符号链接保留。`hdiutil verify` 返回 VALID；
`pkgutil --payload-files` 确认 App、内部 JAR、Native module-path 和浏览器资源存在。
`pkgutil --check-signature` 返回 **no signature**，符合测试版，不是正式签名通过。
DMG 只读挂载在测试临时目录，验收后卸载；没有安装到 `/Applications`。

## 6. 健康检查与启动

清空继承环境后执行发行物 `bin/javaclaw-health`，真实子 App Server 的 initialize/capabilities 成功，
协议版本为 1，返回 modelCompaction、planAdoption、worktreeRecovery、sites、boundedToolAuthorization 等能力。
health 使用独立临时数据/配置/缓存，退出时清理；不连接既有用户服务。
构建工具和第三方库仍有 JDK native-access/日志 provider 提示，未将其当作业务失败，也未掩盖协议输出错误。

项目根目录启动已构建桌面：

```bash
./run.sh --no-build
```

重建使用 `./run.sh`。数据根必须是新的空 v4 根，不能指向 3.x 目录；模型与外部 MCP 需用户在设置中配置。

## 7. 明确未由本机验证代替的条件

1. Linux arm64/x64、Windows x64、macOS x64 原生安全、安装、启动和端到端结果；CI 已配置五类 Runner，但本轮未触发远程发布。
2. Apple 签名/公证、Windows Authenticode、Linux 包签名和全平台同版本 Release；没有使用或申请这些凭据。
3. 用户实际 MCP/OAuth 登录、邮件/通知送达，以及三家模型的付费质量 A/B；假服务不能证明真实业务送达或质量提高。
4. 固定规格长期压力、3.x 合成基线性能对照、浏览器及依赖漏洞审阅。

这些是外部验证/发布条件，不通过静默降级、关闭沙箱、伪造测试结果或修改已确认功能范围来消除。
本轮仍明确排除本地推理、宿主桌面操控、内置邮件、3.x 迁移、旧 MCP 回退和远程 RPC。
