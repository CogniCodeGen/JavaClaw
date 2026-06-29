<div align="center">

# JavaClaw

**基于 JavaFX + AgentScope 的多智能体桌面应用**

把「对话 · 规划 · 长时托管执行」三类场景统一在同一桌面，可调用浏览器、邮件、系统操作、命令行、知识库（RAG）、MCP 等工具，具备可向量检索的长期记忆，并支持智能体在使用中自我沉淀技能。

![Java](https://img.shields.io/badge/Java-25-orange)
![JavaFX](https://img.shields.io/badge/JavaFX-25-blue)
![AgentScope](https://img.shields.io/badge/AgentScope-1.0.11-green)
![EclipseStore](https://img.shields.io/badge/EclipseStore-4.1-purple)
![Playwright](https://img.shields.io/badge/Playwright-1.52.0-2EAD33)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

---

## 📖 简介

JavaClaw 是一个开箱即用的桌面端多智能体系统。它面向真实任务场景，把即时对话、多专家方案讨论、长时可验收的托管执行整合到统一界面，配合丰富的工具生态与可自我进化的技能体系，让智能体「越用越顺手」。

## ✨ 功能特性

### 💬 智能对话与编排
- **三种工作模式**
  - **普通模式** —— 日常对话与工具调用，自动拆解目标、监控执行、按需调整计划
  - **规划模式** —— 多专家协同讨论，多轮协作产出可执行方案
  - **托管任务（SDD）** —— 长时复杂任务按「提案 → 规格 → 设计 → 拆解 → 实现 → 验收」六阶段自动推进，可验收、绝不假完成
- **多媒体输入** —— 支持图片、文档、PDF 附件，图片自动转文字描述
- **流式输出** —— 实时气泡渲染，思考过程与回复分区展示
- **多会话管理** —— 会话侧边栏、历史持久化、随时切换

### 🧠 多模型支持
- 统一适配 **OpenAI 兼容 API、阿里云 DashScope、Anthropic、Google Gemini、本地 Ollama**
- 一处配置随时切换，支持思考模式（部分模型）
- **Token 用量追踪** —— 按会话 / 日期统计 token 与成本，含缓存命中率

### 🛠️ 工具生态
| 能力 | 说明 |
|------|------|
| 🌐 浏览器自动化 | 内置 Playwright：导航 / 截图标注 / 页面交互 / Cookie / JS 执行 / PDF 导出，支持站点凭据自动登录 |
| 📚 知识库 RAG | 全局 / 工作区两级知识库，基于 EclipseStore + JVector 向量检索增强问答 |
| 📧 邮件 | 通过 Jakarta Mail 收发邮件（SMTP / IMAP） |
| 🔔 多渠道通知 | 钉钉 / 企业微信 / 飞书 / 邮件 / 自定义 Webhook |
| 🖥️ 系统操作 | 鼠标 / 键盘 / 文件 / 截图 |
| ⏰ 定时任务 | 间隔 / 每日 / Cron 三种触发 |
| ☕ JShell 执行 | 在隔离环境中运行 Java 片段或技能脚本 |
| 🔌 MCP 集成 | 连接多个 Model Context Protocol Server，统一发现与调用工具 |

### 🌱 技能与自学习
- **技能体系** —— 技能即智能体的程序性记忆，支持条件激活、三级渐进加载与版本管理
- **自学习闭环** —— 智能体在使用中沉淀新技能，经轮后 / 任务后蒸馏并由用户审阅后落地
- **技能中心** —— 可视化管理技能、查看使用统计、回滚版本、审阅待定提案

### 🧬 长期记忆
- **统一记忆基座** —— 基于 **EclipseStore 对象图 + JVector 向量索引**，统一承载长期事实、对话情景、人格、会话检查点
- **语义检索注入** —— 每轮按当前问题做向量 Top-K 召回，只注入最相关的记忆（取代整文件灌入）
- **自动沉淀去重** —— 轮后从对话蒸馏可长期记住的事实，向量去重后增量入库，用户手改的记忆受保护不被覆盖
- **记忆中心** —— 可视化查看 / 编辑 / 删除事实、编辑人格、审阅变更日志（append-only 审计轨）

### 🎨 体验与安全
- **工作区隔离** —— 配置、数据、日志、浏览器状态、知识库、记忆均按工作区隔离
- **安全可控** —— 工具风险三级模型（通知 / 确认 / 二次确认），高风险操作执行前请求确认
- **主题换肤** —— 9 套内置主题，运行时实时切换
- **诊断工具** —— 执行轨迹可视化与导出（JSON / CSV）

## 🧩 技术栈

| 类别 | 技术选型 | 版本 |
|------|----------|------|
| 运行环境 | JDK | 25 |
| 桌面 UI | JavaFX | 25 |
| | RichTextFX | 0.11.7 |
| 智能体框架 | AgentScope Java | 1.0.11 |
| 记忆 / 向量存储 | EclipseStore + JVector | 4.1.0 |
| 模型 SDK | Anthropic SDK | 2.14.0 |
| | Google GenAI SDK | 1.28.0 |
| 浏览器自动化 | Playwright Java | 1.52.0 |
| 邮件 | Jakarta Mail | 2.0.3 |
| 文档处理 | Apache PDFBox | 3.0.4 |
| | CommonMark | 0.24.0 |
| 日志 | Logback | 1.4.14 |
| 构建工具 | Maven | 3.9+ |

## 🚀 快速开始

### 环境要求
- **JDK 25**（必需）
  - ⚠️ 记忆 / 知识库的 JVector 向量索引依赖孵化中的向量 API，运行需 VM 参数 `--add-modules jdk.incubator.vector`
  - ⚠️ 托管任务的 JShell 功能依赖完整 JDK（`jdk.jshell` 模块），JRE 不可用
- **Maven 3.9+**

### 编译与运行
```bash
# 拉取代码
git clone https://github.com/CogniCodeGen/JavaClaw.git
cd JavaClaw

# 编译
mvn clean compile

# 运行
mvn javafx:run

# 打包
mvn clean package
```

> ⚠️ 在 IDE 中运行时，主类必须选择 **`com.javaclaw.app.Launcher`**。直接运行 `JavaClawApp` 会触发 `Error: JavaFX runtime components are missing`（Launcher 用于绕过 JavaFX 非模块化启动限制）。
>
> ⚠️ IDE 运行还需补 VM 参数 **`--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED`**（JVector 向量索引依赖；`mvn javafx:run` 已在插件配置中内置，无需手动添加）。

### 首次配置
首次启动会进入引导向导：选择模型提供商模板 → 填写 `baseUrl` / `model` / API Key → 完成。配置持久化在当前工作区的 `javaclaw-agent.properties`，之后可在「设置」中随时修改。

## 📚 文档
- 设计文档：[`docs/agent-design.md`](docs/agent-design.md)
- 功能文档：[`docs/功能文档.md`](docs/功能文档.md)

## 🤝 贡献
欢迎提交 Issue 与 Pull Request。提交代码前请确保 `mvn clean compile` 通过。

> 项目约定**中文优先**：代码注释、提示词、UI 文本、日志、commit message 均使用中文。

## 📄 许可证
本项目基于 [MIT License](LICENSE) 开源。

欢迎打赏赞助！

<img width="200" height="300" alt="20260626-162729" src="https://github.com/user-attachments/assets/5396fc2f-dab9-42c6-ae5d-d48e4e179540" />


Copyright (c) 2026 CogniCodeGen
