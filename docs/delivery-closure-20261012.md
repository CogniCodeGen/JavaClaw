# 2026-10-12 交付收尾记录

布局变更说明：本记录中的 `data-v5/`、`plugins-v2/`、工件哈希和测试结果保留为当时的交付快照。当前默认目录已统一为 `data/` 和 `plugins/`，见 [便携应用目录](portable-layout.md)；本记录不作为目录调整后源码或新工件的验证结论，当前结果见 [本轮清理验证](cleanup-validation-20261012.md)。

## 结论与边界

本轮完成了本机可复现的交付修复、完整质量门禁、四插件本地验证、当前通用发行包和 macOS 开发镜像重建。**没有执行 Git 暂存、提交、推送或远端发布。**

这不是正式发行批准：真实 Deliverance 权重、Feishu 账号业务、Developer ID / 公证、Windows / Linux 实机及完整人工 GUI 验收仍缺外部条件，不能用模拟测试或 macOS 构建代替。旧实机清单的通过结论也不自动继承到当前 4.0 工件。

- 环境：macOS arm64、Oracle JDK 25、Maven 3.9.0。
- 分支：`dev_v0.3.5`；HEAD 保持 `d8cb7c35`，交付对应未提交工作树，不对应远端旧提交。
- 构建输入：2282 个源码、资源、测试、POM、脚本及 workflow 文件；不包含用户数据、构建输出和文档。
- 构建输入指纹：`50c44a345c630f152b868cab4f7b8f5cec91f4fa1bea9a438ee2f33000719fa4`。
- 算法：按路径排序，对每项的 `path + NUL + 文件 SHA-256 + LF` 拼接结果再计算 SHA-256。逐文件记录见交付目录的 `evidence/final-build-inputs-manifest.json`。
- 持久交付目录：`target/delivery-20261012-110350/`。
- 完整过程证据：`/tmp/javaclaw-delivery-20261012-110350/`；包括失败基线及各轮专项，临时目录不保证跨系统清理保留。

没有执行根聚合 `clean`，没有覆盖旧 `target/portable/JavaClaw-v2/`、历史实机证据或旧数据。仅清理 Desktop 自身构建输出，并在新目录生成交付物。

## 已完成的修复

### 契约、效果与恢复

- 桌面正证夹具遵守结构化条件决策、同帧身份、几何和内容绑定；缺失、FALSE、不完整、矛盾、重复及外来条件继续拒绝。
- 原生应用目录前置和冻结工具目录指纹按当前授权重验；不从展示名称或旧拒绝文案推断权限，不因新目录追认旧派发。
- 修复已拒绝的非法 harness JSON 在恢复时被误当成功决策重建的问题；拒绝反馈按原模型、调用及因果步骤重放，不补造完成申请。
- `CONTINUE` 保持非终态；`CLAIM_DONE` 仍只是一项申请，由宿主独立验收。
- UNKNOWN / MAYBE_SENT 效果保护不变。点击后的宿主自动观察计入同一工具预算，不核销未知效果、不授权重放；测试同步核对完整调用顺序。
- 必需上下文与完整原任务装不进预算时，在调用模型前拒绝，不截断任务或身份。正向和预算不足负向场景分开验证。
- 新证据进度插入当前工具目录之前，保证当前目录仍是尾部的唯一目录；持久化提示与真实送达提示保持一致。

### API、存储与界面

- 产品必要契约收敛到 framework API/SPI，包括交互控制、任务契约校验和宿主任务证据快照；不扩大架构豁免。
- `taskEvidence` 要求终态且线程可读，读取前后核对版本、序列及状态；补充删除和并发版本变化测试。
- SDD 通过真实 AgentEngine + 隔离 H2 获取终态证据快照，并重读实际项目文件；覆盖文件变化、证据缺失、父线程删除及批准计划修订的拒绝路径。
- 审核模式保存结果贯通配置、应用端口和界面；失败明确显示当前已生效但未持久化，连续保存串行，迟到结果不污染新工作区或已销毁界面。
- UI 跨线程更新复用 FxDispatcher，执行计时使用托管设施；所有生产 JSON 解析复用装配层的共享 ObjectMapper。
- 按知识写入、效果证明、工具目录重放、上下文投影、桌面观察、窗口追踪、启动栅栏、引擎恢复/等待/事件等职责拆分超限类。
- 唯一 AgentEngine 与状态 CAS 写入边界保留，协作者不新建运行时。源码规模超限文件由本轮开始的 15 个归零，未调整 350 / 800 行限制。
- 原生预览测试原先在完整 FX 测试后无法建立初始焦点；测试使用标准 AWT 前台请求建立自身窗口的前置条件，保留原焦点与鼠标位置断言，没有跳过测试或延长超时。

## 完整质量门禁

先归档旧报告，仅清理 Desktop，再执行不忽略失败的完整验证：

```bash
mvn -o -pl javaclaw-desktop clean
mvn -o -pl javaclaw-desktop -am -Pui-test verify
mvn -o -Pui-test \
  -Djavaclaw.distribution.directory=/tmp/javaclaw-delivery-20261012-110350/final-distribution \
  verify
```

| 执行范围 | Tests | Failures | Errors | Skipped | 结论 |
| --- | ---: | ---: | ---: | ---: | --- |
| 本轮初始完整基线 | 2545 | 93 | 19 | 10 | 失败，保留历史证据 |
| Desktop 清洁输出完整 verify | 2599 | 0 | 0 | 10 | 通过 |
| 最终聚合 verify：协议、SDK、Runner、Desktop、发行 | 2617 | 0 | 0 | 10 | 通过 |
| 显式启用 macOS FFM / 公共原生 API 冒烟 | 9 | 0 | 0 | 0 | 通过，独立专项 |

聚合项目共六个 Maven 构建项（父项目加五个模块）。其中 SDK 3 项、Runner 15 项、Desktop 2599 项；协议和发行模块没有自己的测试，不把零项写成测试通过。

完整门禁的 10 项跳过有明确原因：8 项 `MacSystemFfmSmokeTest` 和 1 项 `NativePublicApiSmokeTest` 默认要求 `javaclaw.native.public.tests=true`，随后已显式启用并全部通过；另 1 项 `WindowsCaptureSmokeTest` 仅适用于 Windows，在 macOS 上不能执行。没有因为缺少 `-Pui-test` 跳过 FX 测试。

原生专项命令：

```bash
mvn -o -pl javaclaw-desktop -am -Pui-test \
  -Djavaclaw.native.public.tests=true \
  -Dtest=MacSystemFfmSmokeTest,NativePublicApiSmokeTest test
```

该专项覆盖真实系统 FFM、Objective-C completion block 生命周期、迟到回调、容量上限和图像解码等；不请求权限、不执行原生输入，也不等于完整电脑操作业务验收。

### 最终聚合覆盖率

来自归档的 `evidence/reactor/jacoco/jacoco.xml`，不是混合专项推算值：

| 范围 | Line | 原阈值 | Branch | 原阈值 |
| --- | ---: | ---: | ---: | ---: |
| 整体 | 70.12% | 55% | 49.89% | 45% |
| application + platform.execution | 89.14% | 80% | 70.88% | 70% |
| Controller | 80.73% | 70% | 60.11% | 60% |

三组 JaCoCo 检查、完整架构、FXML、源码卫生、源码规模和功能面门禁均通过。核心与 Controller 分支覆盖率余量仍有限，后续修改仍需完整重跑，不能据此降低阈值。

可复核证据：

- `evidence/final-desktop-verify-retry.log` 与 `evidence/full-desktop/`。
- `evidence/final-reactor-verify.log` 与 `evidence/reactor/summary.json`。
- `evidence/reactor/coverage-summary.json`、`jacoco/`、`jacoco.exec`。
- `evidence/final-native-ffm-smoke.log` 与 `evidence/native-ffm/`。

过程中的失败记录保留在临时证据根；最终命令未使用 `maven.test.failure.ignore`，也没有排除失败类。

## 插件与当前工件

四个样例不是 reactor 成员，已分别离线执行独立 `verify`：

| 样例 | 本地结果 | 不能替代的验证 |
| --- | --- | --- |
| Hello | 5 项通过 | 真实宿主 GUI 安装、授权和卸载 |
| Feishu | 5 项通过 | 真实账号鉴权、接收及发送业务 |
| echo-model | 2 项单元 + 2 项真实 Runner 子进程 IT 通过 | 真实生成模型推理质量 |
| Deliverance | 32 项通过 | 兼容真实权重推理及真实权重 Runner E2E |

新 macOS 镜像的内置 Java 与外置 Runner 又执行了 echo-model 的 2 项真实子进程 IT，全部通过；证据为 `evidence/final-bundled-echo.log` 和 `evidence/bundled-echo-failsafe/`。

实际发行隔离扫描通过：Desktop 159 个 JAR（主 JAR + 158 个依赖）、Runner 1 个 JAR、四插件 ZIP 共 4 个 JAR。通用 ZIP 内四插件与完整性清单逐项核对，Desktop 与当前模块 JAR 一致；macOS 镜像的全部宿主依赖、Runner 及插件也与最终发行输入逐字节一致。

### 交付位置与哈希

相对于 `target/delivery-20261012-110350/`：

| 工件 | SHA-256 |
| --- | --- |
| `portable/javaclaw-distribution-4.0.0-SNAPSHOT-portable.zip` | `71cc43eeb8e822d1ec76f6aaf037c15b8e8bfe0f9d48957829850686adf15c92` |
| `portable/JavaClaw-macos-arm64-development.zip` | `2393a3151369b119630234ca7019e272a4df6c61427447fa17ecbdc3c8243226` |
| `plugin-packages/hello.zip` | `0432cb06f503097c0d6de3f2c1a01cd8b47d5d9c8bb7a0f53763b644fdb1b85b` |
| `plugin-packages/feishu.zip` | `50766be6125003fa4f9c46661cc6401f4d2083ba7cebb55b2debcafda13a51aa` |
| `plugin-packages/echo-model.zip` | `e8eb8df47aae8af374d13ad923e4f15288c791dede85669fa47abbaa152d6bc1` |
| `plugin-packages/deliverance.zip` | `6e4b30fa7f13023c8ce42d1eebae28cff3264f016f7de2e68cc462ea0e52c3e9` |

独立 Desktop JAR：`7b3b4b6c20460a5f7e3a1786471038d76506298dbbcb561336aaef6b75f0e06d`。
Runner JAR：`0c23d4ec40c18abb0d4d3d55076a1346d6f9d7e159d2f8c7d574cfd1c59f06d2`。
完整大小和哈希清单见 `evidence/artifact-hashes.json`。

macOS 解包镜像位于 `macos/JavaClaw/`。归档 ZIP 在启动验收前生成，`data-v5/`、`plugins-v2/` 当时均为空；解包镜像目录随后留存本轮独立启动数据，不能将其初始化后的数据当作空白发行状态。

### 签名、启动与未覆盖范围

- 开发镜像实际使用 ad-hoc 签名，应用和包内 Java 的 strict 验签通过；没有 Developer ID，没有公证。
- 内置完整 JDK 模块已核对，含 `java.sql`、`java.desktop`、`java.naming`、`jdk.incubator.vector`、`jdk.httpserver`；Launcher classpath 不含 Runner 或插件实现。
- 从 `/tmp` 启动真实包内 Launcher，应用根仍定位到新镜像外层；新数据标记为格式 5。未跳过向导的启动停留在首次向导 `showAndWait`，没有输入账号或完成向导。
- 第二次启动仅使用 `javaclaw.ui.test=true` 跳过向导且不保存完成标记；日志确认唯一引擎启动诊断、工作区内核、聊天 FXML、主窗口显示及应用启动完成。这是启动冒烟，不是人工 GUI 全功能验收。
- 两次均由验收脚本在 20 秒后发送 SIGTERM，退出码 143。资源关闭日志完成，但终止期间出现 JavaFX 图形管线 NPE / UI 清理超时告警，原日志保留；**不能写成正常 GUI 退出、无告警或重启业务持久化验收通过**。正常退出与强制终止的原生收尾仍须分开实机复验。
- 退出代码核对确认 `shutdownForJvmTermination` 只给 UI 清理 250ms，随后通过 `closeBackendForProcessExit` 幂等释放后台资源；既有协调器测试覆盖 UI 队列不执行、失败、重复关闭和等待并发数据库关闭。这解释了后台清理不受 UI 超时阻塞的设计，不证明 JavaFX 原生异常的根因，也不消除该告警。本轮没有为获得无告警日志而改动退出实现或隐藏异常。
- 当前开发镜像的 jpackage 元数据沿用默认 `1.0`，实际应用 JAR 为 `4.0.0-SNAPSHOT`；本轮不将该默认元数据作为正式发行版本号证明。
- 未自动连接真实模型、Feishu、站点账号或 MCP；未触发远端 CI、发布、签名服务或公证。

## 历史 20 项 GUI 阻塞映射到当前 4.0

以下只映射 2026-10-06 的 20 项未完成验收，不改写旧 146 项清单，也不把自动测试升级为人工 GUI 通过：

| 原编号 | 数量 | 当前已经补齐 | 仍需的实际条件 |
| --- | ---: | --- | --- |
| F01-05 | 1 | 新隔离镜像真实首次启动进入向导路径 | 人工完成向导、取消和重启交互验收；不能重置旧用户数据制造入口 |
| F07-01 | 1 | 审核模式保存失败反馈及迟到结果链的自动回归通过 | 安全、受控 GUI 故障注入及反馈目视验证，不破坏真实数据库 |
| F11-06 | 1 | 提案相关代码和 FX 测试纳入完整门禁 | 专用测试数据中的真实学习提案及预览/采纳/拒绝操作 |
| F12-05 | 1 | MCP 现行边界与相关测试保留 | 策略允许且获授权的真实 MCP 端点，实际发现和生命周期验收 |
| F13-02–F13-06 | 5 | 四个可安装开发 ZIP、完整性清单、Runner 与插件中心自动测试 | 当前 4.0 GUI 中实际安装、权限审批、配置、详情及卸载；不再写成完全缺少测试包 |
| F18-01–F18-07 | 7 | Deliverance 开发包及 32 项本地验证 | 兼容生成/嵌入权重、目录树哈希、维度、READY 档案和实际 UI/API 业务 |
| F19-02 | 1 | 站点身份、会话保存和重置相关自动测试 | 专用真实站点账号、真实登录会话及 GUI 重置；没有对真实账号操作 |
| F22-05 | 1 | 安全候选和清理相关自动门禁保留 | 仅含本轮数据且归属明确的候选目录、当前 GUI 人工确认与验收 |
| F23-05、F23-06 | 2 | 当前格式 5 新镜像与旧格式数据隔离 | 原 76 会话/12 事实不可逆删除的独立具体授权及删除后重启；本轮没有执行该删除 |

本轮未具备可控的完整图形操作通道及必要真实业务条件，因此没有宣称上述 20 项全部完成；此前已通过的历史 126 项也没有被重新计作本轮实机通过。

## 外部阻塞与后续验收输入

1. **真实权重**：使用 Deliverance 已有 `-Pdeliverance-it` 入口，需要兼容的 `generation-model` / `generation-sha256`、`embedding-model` / `embedding-sha256` / `embedding-dimensions`。哈希必须按测试定义的目录树算法产生；现有 GGUF 或未经证明兼容的 MLX/DWQ 不能冒充夹具。本轮未下载或转换大型权重。引擎 IT 与真实权重 Runner E2E 分开记账。
2. **Feishu / 真实模型 / 站点**：需专用账号、必要权限、网络和实际模型配置。凭据通过受控界面或批准的密钥入口提供，不写入命令、日志和报告；真实发送必须单独确认。
3. **正式签名和跨平台**：需 Developer ID、时间戳及公证条件、Windows 签名环境和 Windows/Linux 目标机。CI 已补四插件 staging、Windows 开发镜像验证与原生工件归档，但没有触发执行；远端旧 SHA 的绿灯不能证明本地未提交源码。
4. **完整 GUI 与退出**：需在当前镜像上执行正常退出/重启、插件生命周期、真实焦点和故障恢复验收；另复验 SIGTERM 图形管线收尾告警。不要用本轮测试通过或启动日志替代这些操作。
5. **旧数据永久删除**：本轮任务不提供该不可逆具体范围的授权，旧 `data/`、`plugins/`、真实会话和事实保持未操作。

当前可交付的是通过完整自动门禁的源码工作树和本机开发工件；正式业务和发行验收仍按上述条件独立完成。
