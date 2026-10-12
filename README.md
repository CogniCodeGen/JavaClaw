<div align="center">

# JavaClaw

**基于 JavaFX、Spring Framework 与 Spring AI 2.0 的可扩展 Agent 桌面工作台**

![Java](https://img.shields.io/badge/Java-25-orange)
![JavaFX](https://img.shields.io/badge/JavaFX-25-blue)
![Spring](https://img.shields.io/badge/Spring_Framework-7.0.8-6DB33F)
![Spring AI](https://img.shields.io/badge/Spring_AI-2.0.0-6DB33F)
![Playwright](https://img.shields.io/badge/Playwright-1.52.0-2EAD33)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

JavaClaw 将对话、方案研讨、自动循环、长时托管任务和可视化工作流整合到一个桌面应用中。
智能体可以调用浏览器、代码工程、命令行、邮件、系统与桌面自动化等工具，并通过长期记忆、
知识库、技能、MCP、插件和定时任务持续扩展能力。

## 项目能力

| 能力 | 说明 |
|---|---|
| 五种工作模式 | 普通对话、规划研讨、循环推进、SDD 托管任务、状态图工作流 |
| 多智能体协作 | 内置与自定义专家、目标拆解、能力路由、执行监控、评估和主动澄清 |
| 工具执行 | 浏览器、代码、命令、文件、桌面、邮件、通知、OCR 和站点登录 |
| 记忆与知识 | 长期事实、情景、人格、纠错、记忆图谱、知识库和 RAG |
| 自动化 | 定时任务、可恢复工作流、循环任务和带验收标准的长时托管 |
| 扩展能力 | 技能沉淀与审阅、MCP Server 和 [进程隔离插件 v2](docs/plugin-isolation-v2.md) |
| 桌面体验 | FXML 界面、Markdown、附件、主题、字体、快捷键、托盘和单实例 |
| 安全与隔离 | 工具审核、能力授权、项目围栏、凭据加密、审计和工作区隔离 |

## 技术栈

| 类别 | 技术 | 版本 |
|---|---|---|
| 运行环境 | JDK | 25 |
| 桌面 UI | JavaFX | 25 |
| 对象装配、JDBC、事务 | Spring Framework | 7.0.8 |
| Agent 底座 | Spring AI + JavaClaw Agent Framework | 2.0.0 / 2.0 |
| 记忆与向量 | EclipseStore + JVector | 4.1.0 |
| 结构化数据 | H2 | 2.3.232 |
| 浏览器自动化 | Playwright Java | 1.52.0 |
| 文档与 Markdown | PDFBox + CommonMark | 3.0.4 / 0.30.0 |
| 构建工具 | Maven | 3.9+ |

## 快速开始

环境要求：JDK 25、Maven 3.9+。

```bash
git clone https://github.com/CogniCodeGen/JavaClaw.git
cd JavaClaw

mvn -DskipTests clean install
mvn -pl javaclaw-desktop javafx:run
```

仓库根是 Maven 聚合项目。宿主源码位于 `javaclaw-desktop/src/`，构建产物和测试报告位于
`javaclaw-desktop/target/`。完成上述 `install` 后，也可在 `javaclaw-desktop/` 中运行
`mvn javafx:run`，或从仓库根运行 `mvn -f javaclaw-desktop/pom.xml javafx:run`。

在 IDE 中运行时，主类必须选择 `com.javaclaw.app.Launcher`，并添加 JVM 参数：

```text
--add-modules jdk.incubator.vector,jdk.httpserver --enable-native-access=ALL-UNNAMED
```

首次启动会引导配置模型提供商、服务地址、模型和 API Key。默认运行数据保存在 `data/`，插件工件保存在 `plugins/`，测试数据
保存在 `javaclaw-desktop/target/`，不会写入真实数据目录。开发数据根仍是仓库根；从模块目录启动也不会另建一套主数据。

已有旧数据或旧插件时，请先阅读 [JavaClaw 4.0 升级说明](docs/upgrade-4.0.md)。新桌面目标会话的授权、平台支持与限制见 [后台桌面会话](docs/background-desktop-sessions.md)，应用根与数据格式规则见 [便携应用目录](docs/portable-layout.md)。

## 验证

```bash
mvn clean -Pui-test verify
```

该命令运行单元测试、JavaFX/FXML 测试、架构规则、源码卫生、规模和覆盖率门禁。
只验证宿主及其协议依赖可在仓库根运行 `mvn -pl javaclaw-desktop -am -Pui-test verify`。
宿主 Surefire 报告位于 `javaclaw-desktop/target/surefire-reports/`，覆盖率报告位于
`javaclaw-desktop/target/site/jacoco/`；根 `target/` 中保留的历史证据与便携镜像暂存不属于宿主构建输出。

外部插件独立依赖 `plugin-sdk-java`，通过 gRPC 协议运行于独立 Runner；Desktop 只依赖协议。
内置产品功能统一通过 `AgentClient` 提交 `RunRequest`，不得自建 `ChatClient`、工具循环或运行时。
插件开发、管理、发行打包和模块依赖见 [插件架构与操作说明](docs/plugin-isolation-v2.md)，已执行验证与待验收项见 [插件隔离验收记录](docs/plugin-isolation-v2-validation.md)。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

Copyright (c) 2026 CogniCodeGen
