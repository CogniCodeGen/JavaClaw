# 插件隔离 v2 验收记录

布局变更说明：本文保留目录调整前的验收快照，`data-v5/`、`plugins-v2/`、工件哈希与验证结果均对应当时的输入，不追溯改写。当前默认目录已统一为 `data/` 和 `plugins/`，见 [便携应用目录](portable-layout.md)；布局变更后的验证见 [本轮清理验证](cleanup-validation-20261012.md)。

首次记录：2026-10-10；最新补验：2026-10-12。验证环境：macOS、arm64、JDK 25。**最新完整聚合质量门禁已通过：2617 项，0 失败、0 错误、10 跳过，三组覆盖率达到原阈值。**前几节保留 2026-10-10 的失败基线、目录迁移及旧工件快照，不代表当前结果；最新结果见末节和 [交付收尾记录](delivery-closure-20261012.md)。真实账号、兼容权重、正式签名及跨平台实机仍未完成。

## 全量与基线

原始基线通过 `git archive HEAD` 保存，提交为 `d8cb7c35`。

| 执行范围 | Tests | Failures | Errors | Skipped | 结论 |
| --- | ---: | ---: | ---: | ---: | --- |
| 原始 HEAD，完整 `-Pui-test test` | 2581 | 97 | 19 | 10 | 失败 |
| 新版第二轮清洁全量 | 2508 | 96 | 19 | 10 | 失败 |
| 新版最终全量 | 2509 | 92 | 19 | 10 | 失败 |

原始 HEAD 的 Gateway 验证复现了 23 个失败、2 个错误，与新版对应结果一致。原始 HEAD 的三项架构检查失败也已实际复现。新版首次全量发现的目录别名等问题已修复，并进行了第二轮清洁全量。

最终全量日志为 `/tmp/javaclaw-v2-final-quality.log`。按 testcase 的 `classname + name` 与原始 HEAD 比较，新增失败集合 `NEW_FAILURES={}`，对照记录位于 `/private/tmp/javaclaw-v2-final-comparison.json`。该结论仅描述失败用例集合比较，完整测试仍有失败和错误。

最终执行的整体覆盖率门槛通过；核心分支覆盖率为 `0.66`，低于原有 `0.70` 门槛，导致 `verify` 失败，门槛未降低。该轮使用 `-Dmaven.test.failure.ignore=true`，用途是让测试失败后继续执行覆盖率门禁；此参数不能作为测试通过的依据。

复用原始 HEAD 的执行数据和编译类，单独执行 JaCoCo 报告及核心门禁，基线核心分支覆盖率为 `0.67`，同样低于 `0.70`，检查失败；日志为 `/tmp/javaclaw-v2-head-coverage.log`。

此后通用 JDK 模块声明追加的三项测试已纳入下述最终专项，未再次重复全部既有失败测试。

## 专项验证

最终专项使用 macOS 便携包内置 Java 运行，共 **137 项全部通过**，失败、错误、跳过均为 0，构建成功，日志 `/tmp/javaclaw-v2-bundled-final-focused.log`。包含插件后端、真实子进程、模块审批、JavaFX 页面、模型、架构隔离、无插件启动及生产工具桥。当前工具清单为 169 个静态工具项、157 个唯一工具，包含 Clarify 受控中断。

下列结果来自已完成的针对性验证，并非一次全量构建的汇总：

| 范围 | 已证实结果 |
| --- | --- |
| 最新插件后端 | 42/42：Manager 26、Store 5、Verifier 6、Manifest 5 |
| 宿主回调与调度隔离 | Host 8/8 |
| JavaFX 统一插件中心 | 11/11 |
| 插件中心 ViewModel | 1/1 |
| 模型端点 metadata 投影 | 2/2 |
| 通用模型 Gateway / Endpoint / Catalog | 7/7、4/4、2/2 |
| Run 模型快照冻结 | 已通过 |
| Run 恢复缺失插件隔离 | 已通过 |
| AgentEngine 初始化失败释放插件租约 | 2/2 |

通用模块验证覆盖 `java.sql` 未批准时不启动进程、批准后真实 Runner 可用，合法但不存在的模块在启动前失败，以及模块名参数注入和超长输入拒绝。

## SDK、Runner 与独立样例

| 模块或样例 | 已证实结果 |
| --- | --- |
| Java SDK | 3/3 |
| Java Runner | 15/15 |
| Deliverance | 32/32 |
| echo-model | 2/2 单元测试、2/2 真实 Runner 子进程集成测试，合计 4 项 |
| Hello | 无单元测试；独立 `verify` 成功 |
| Feishu | 无单元测试；独立 `verify` 成功 |

echo-model 是用于协议验证的确定性模型，已验证独立子进程中的模型管理、聊天、流式响应与嵌入契约。Deliverance 的真实模型权重推理和 Feishu 的真实账号连接尚未验证。

## 发行与平台

| 项目 | 当前状态 |
| --- | --- |
| 四个样例开发 ZIP | 已生成；未签名 |
| 实际发行 classpath 隔离扫描 | 已通过：Desktop 159 个 JAR、Runner 1 个 JAR、四个插件 ZIP 共 4 个 JAR |
| 发行组装 | `mvn -DskipTests package` 成功，产生 `distribution/target/javaclaw-distribution-4.0.0-SNAPSHOT-portable.zip` |
| macOS jpackage | 开发 ad-hoc 签名及内置 Java 检查通过；包内 Java + Runner 的 echo-model 两项真实集成测试通过 |
| Windows / Linux | 已配置 CI；尚未实际运行 |

`scripts/verify-plugin-isolation.py` 扫描发行目录中的 Desktop、依赖 JAR、独立 Runner 及可选插件 ZIP，实际检查日志 `/tmp/javaclaw-v2-isolation-artifacts.log`。插件隔离专项 CI 仅选择对应架构方法，完整质量门禁仍保留全部架构检查。

实际打包验证发现并修复了原有显式模块列表仅保留 vector/httpserver 导致 Java SE 模块缺失的问题。三平台现在在 jlink 阶段保留完整 JDK 模块，插件 JVM 参数仍由声明和授权控制。开发包未使用 Developer ID、未公证，不代表正式发行签名已完成。

最终 macOS 开发便携目录为 `target/portable/JavaClaw-v2/`。已逐字节核对其中 Desktop、全部依赖与 Runner 和最终组装产物一致；随后使用该目录内 Java 与 Runner 重跑 echo-model 集成测试。日志分别为 `/tmp/javaclaw-v2-release-package.log`、`/tmp/javaclaw-v2-macos-delivery.log`、`/tmp/javaclaw-v2-macos-delivery-echo.log`。发行 ZIP 的 SHA-256 为 `abe4e56012360d47f89ec4c55f299e501341c8f63e82c05b69f42ac6d5d40c57`，四个随附插件包的内容哈希与发行清单一致。

当前实现提供独立 JVM 进程隔离，不提供操作系统沙箱。开发 ZIP 未签名，新安装插件默认停用；签包能力与实际发行包签名状态分别记录。

## Desktop 标准目录调整

已将根 `src/` 完整迁至 `javaclaw-desktop/src/`，源码、资源、测试合计 2119 个文件，无缺失或额外文件。迁移前后逐文件 SHA-256 比较，仅应用根目录识别及对应的三个测试文件发生预期修改。Desktop POM 不再覆盖源码、资源、构建目录或测试工作目录，采用 Maven 标准布局，编译和测试输出均位于 `javaclaw-desktop/target/`。

开发应用根仍为仓库根。从聚合根、Desktop 模块或其 JAR 启动使用同一套 `data-v5/`、`plugins-v2/`；测试隔离目录允许位于 Desktop 的 `target/`，同时拒绝符号链接及越界路径。历史数据和根 `target/` 中的既有验收证据保留。

| 调整后验证 | 结果 |
| --- | --- |
| 从 Desktop POM 独立执行完整 `-Pui-test verify` | 2516 项，93 个失败、19 个错误、10 个跳过；整体覆盖率门槛通过，核心分支覆盖率仍为 0.66，未达到 0.70 |
| 独立 Desktop 专项 `package` | 26/26，通过；包含应用根、数据根、FXML、功能目录、协议依赖和源码引用检查，以及原生预览测试 |
| 完整回归中的插件管理服务与统一插件中心 | 分别 26/26、11/11，通过 |
| 聚合 `mvn -DskipTests package` | 六个构建项目全部成功，Desktop 从新模块路径参与发行组装 |
| 仓库根及 Desktop 工作目录运行发行隔离扫描 | 均通过：Desktop 159 个 JAR、Runner 1 个 JAR、四个插件 ZIP 共 4 个 JAR |
| 模块 JAR、发行目录与发行 ZIP | Desktop 内容一致，四个随附插件哈希均与清单一致 |

完整回归相较目录迁移前的 92 个失败多出一项原生窗口焦点等待失败。相同失败已出现在目录迁移前的 `/tmp/javaclaw-v2-full-verify.log`，本次不改代码单独重跑两项原生预览测试均通过。其余失败和错误用例集合与上一轮一致，目录相关测试未出现失败；这不代表全量质量门禁已通过。

完整日志为 `/tmp/javaclaw-desktop-layout-full-verify.log`，原始报告与覆盖率快照保存在 `/private/tmp/javaclaw-desktop-layout-full-reports/`，逐用例比较为 `/private/tmp/javaclaw-desktop-layout-comparison.json`。专项和发行构建日志分别为 `/tmp/javaclaw-desktop-layout-focused-package.log`、`/tmp/javaclaw-desktop-layout-reactor-package.log`；隔离扫描日志为 `/tmp/javaclaw-desktop-layout-isolation-root.log` 和 `/tmp/javaclaw-desktop-layout-isolation-module.log`。

调整后通用发行 ZIP 的 SHA-256 为 `3637deb4bd0c361d242ac89e6b883003ee8c6bc78b491ddfb9c8271ff3afebf1`。前文 macOS 原生开发包的验证仍对应目录调整前的工件；本节记录新组装的通用发行 ZIP。

## 2026-10-12 完整门禁与当前交付

本节对应 `dev_v0.3.5` 未提交工作树，HEAD 仍为 `d8cb7c35`，没有暂存、提交或推送。环境为 macOS arm64、Oracle JDK 25、Maven 3.9.0。构建输入指纹为 `50c44a345c630f152b868cab4f7b8f5cec91f4fa1bea9a438ee2f33000719fa4`，覆盖 2282 个源码、资源、测试、POM、脚本及 workflow 文件，不包含文档、用户数据和生成输出。

没有降低覆盖率阈值、扩大架构豁免或排除失败测试。桌面证据、原生身份与工具目录夹具按现行契约修订；同时修复按需身份发现、非法决策恢复、目录尾部投影、审核模式保存失败反馈、产品 API 越界与超限类职责。SDD 证据集成测试使用真实引擎、隔离数据库及真实项目文件，覆盖版本、删除、文件变化和批准计划修订等拒绝路径。

### 完整验证结果

```bash
mvn -o -pl javaclaw-desktop clean
mvn -o -pl javaclaw-desktop -am -Pui-test verify
mvn -o -Pui-test \
  -Djavaclaw.distribution.directory=/tmp/javaclaw-delivery-20261012-110350/final-distribution \
  verify
```

| 范围 | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Desktop 清洁输出完整 verify | 2599 | 0 | 0 | 10 |
| 最终五模块聚合 verify（含父项目共六个构建项） | 2617 | 0 | 0 | 10 |
| 随后显式启用的原生 API / macOS FFM 专项 | 9 | 0 | 0 | 0 |

两次完整 verify 均为 `BUILD SUCCESS`，未设置 `maven.test.failure.ignore`。聚合结果包括 SDK 3 项、Runner 15 项和 Desktop 2599 项。完整门禁的 10 项跳过中，9 项默认 opt-in 原生冒烟已在随后专项实际通过，另 1 项 Windows 捕获测试无法在 macOS 执行。

| 最终聚合覆盖率 | Line | Branch | 原阈值 Line / Branch |
| --- | ---: | ---: | --- |
| 整体 | 70.12% | 49.89% | 55% / 45% |
| application + platform.execution | 89.14% | 70.88% | 80% / 70% |
| Controller | 80.73% | 60.11% | 70% / 60% |

全部架构、FXML、源码卫生、规模及功能面门禁通过。后续专项可能覆盖模块默认报告；完整证据以 `target/delivery-20261012-110350/evidence/full-desktop/` 和 `evidence/reactor/` 归档为准，不混用专项数据推算全量。

### 插件与发行结果

- Hello：新增配置、贡献及调用边界测试，独立 verify 5 项通过。
- Feishu：新增配置、工作区生命周期及消息边界测试，独立 verify 5 项通过；未连接真实账号或发送消息。
- echo-model：独立 verify 的 2 项单元 + 2 项真实 Runner IT 通过；新 macOS 内置 Java + Runner 再运行 2 项真实 IT 通过。
- Deliverance：32 项通过；没有兼容真实权重，未执行真实权重 IT / Runner E2E。
- 当前四插件 ZIP、通用发行 ZIP、macOS 开发 ZIP 已在独立目录生成；没有覆盖前文旧镜像或旧证据。
- 隔离扫描通过：Desktop 159 个 JAR、Runner 1 个 JAR、四插件共 4 个 JAR。包内主程序、158 个依赖、Runner、插件和完整性清单与最终发行输入逐字节核对。
- 通用 ZIP：`target/delivery-20261012-110350/portable/javaclaw-distribution-4.0.0-SNAPSHOT-portable.zip`，SHA-256 为 `71cc43eeb8e822d1ec76f6aaf037c15b8e8bfe0f9d48957829850686adf15c92`。
- macOS 开发 ZIP：同目录 `JavaClaw-macos-arm64-development.zip`，SHA-256 为 `2393a3151369b119630234ca7019e272a4df6c61427447fa17ecbdc3c8243226`。

macOS 应用及包内 Java 的 ad-hoc strict 验签通过，完整 JDK 模块已核对。实际 Launcher 从 `/tmp` 启动并定位到新镜像外层；首次向导未跳过，另一轮 UI 测试模式启动日志确认聊天主窗口显示。两次均以 SIGTERM 结束（143），终止期间出现 JavaFX 图形管线 NPE / UI 清理超时，不能宣称正常 GUI 退出无异常。

Developer ID / 公证、Windows / Linux 实机、Feishu 真实账号、Deliverance 兼容权重及完整人工 GUI 仍单独阻塞。原始失败基线继续保留；本节成功只证明当前源码的完整自动门禁和本机开发工件，不替代上述外部验收。命令、逐包哈希、原 20 项 GUI 阻塞映射及完整边界见 [交付收尾记录](delivery-closure-20261012.md)。
