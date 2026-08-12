<div align="center">

# JavaClaw

**基于 JavaFX、Spring Framework 与 AgentScope 的多智能体桌面工作台**

![Java](https://img.shields.io/badge/Java-25-orange)
![JavaFX](https://img.shields.io/badge/JavaFX-25-blue)
![Spring](https://img.shields.io/badge/Spring_Framework-7.0.8-6DB33F)
![AgentScope](https://img.shields.io/badge/AgentScope-1.0.12-green)
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
| 扩展能力 | 技能沉淀与审阅、MCP Server 和 Plugin API 3.0 |
| 桌面体验 | FXML 界面、Markdown、附件、主题、字体、快捷键、托盘和单实例 |
| 安全与隔离 | 工具审核、能力授权、项目围栏、凭据加密、审计和工作区隔离 |

## 技术栈

| 类别 | 技术 | 版本 |
|---|---|---|
| 运行环境 | JDK | 25 |
| 桌面 UI | JavaFX | 25 |
| 对象装配、JDBC、事务 | Spring Framework | 7.0.8 |
| 智能体框架 | AgentScope Java | 1.0.12 |
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

mvn clean compile
mvn javafx:run
```

在 IDE 中运行时，主类必须选择 `com.javaclaw.app.Launcher`，并添加 JVM 参数：

```text
--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED
```

首次启动会引导配置模型提供商、服务地址、模型和 API Key。默认运行数据保存在 `data/`，测试数据
保存在 `target/`，不会写入真实数据目录。

已有 2.x 安装、早期 3.0 数据或旧插件时，请先阅读 [JavaClaw 3.0 升级说明](docs/upgrade-3.0.md)。

## 验证

```bash
mvn clean -Pui-test verify
```

该命令运行单元测试、JavaFX/FXML 测试、架构规则、源码卫生、规模和覆盖率门禁。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

Copyright (c) 2026 CogniCodeGen
