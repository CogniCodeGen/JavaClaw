<div align="center">

# JavaClaw

**基于 JavaFX + AgentScope 的多智能体桌面应用**

把「对话 · 研讨 · 自动循环 · 长时托管执行 · 可视化工作流」五类场景统一在同一桌面，可调用浏览器、邮件、系统与桌面操作、命令行、代码工程、知识库（RAG）、MCP 等工具，具备可向量检索的长期记忆，并支持智能体在使用中自我沉淀技能。

![Java](https://img.shields.io/badge/Java-25-orange)
![JavaFX](https://img.shields.io/badge/JavaFX-25-blue)
![AgentScope](https://img.shields.io/badge/AgentScope-1.0.12-green)
![EclipseStore](https://img.shields.io/badge/EclipseStore-4.1-purple)
![Playwright](https://img.shields.io/badge/Playwright-1.52.0-2EAD33)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

---

## 📖 简介

JavaClaw 是一个基于 JavaFX 的开箱即用桌面端多智能体系统。它面向真实任务场景，把即时对话、多专家方案讨论、长时可验收的托管执行整合到统一界面，配合丰富的工具生态与可自我进化的技能体系，让智能体「越用越顺手」。

## 🖼️ 界面截图

### 主界面概览

<img width="2382" height="1440" alt="JavaClaw 主界面概览" src="https://github.com/user-attachments/assets/23d6c59e-c889-4ae7-8a66-bccd10ac496e" />

主界面把会话列表、对话区、知识库选择、主题切换、Token 用量与托管任务入口放在同一工作台内。用户可以在「对话 / 研讨 / 托管任务」之间切换，也可以从侧边栏进入记忆中心、技能中心、MCP、插件、定时任务等扩展功能。

## 🔎 项目功能分析

JavaClaw 不只是聊天壳，而是一个「桌面智能体工作台」。从代码结构看，它的能力可以分为六层：

| 功能层 | 面向用户的能力 | 关键实现模块 |
|--------|----------------|--------------|
| 交互入口 | 多会话聊天、普通对话、研讨模式、循环模式、托管任务与工作流入口、附件输入、Markdown 气泡、图片查看 | `chat`、`mode`、`api.conversation`、`ui.javafx` |
| 智能体编排 | 主 ReAct 编排、多专家委派、目标拆解、执行监控、中段评估、计划演进、主动澄清 | `agent`、`agent.goal`、`agent.execution`、`agent.evaluation`、`agent.planning`、`agent.clarify` |
| 工具执行 | 浏览器、系统、桌面自动化、命令行、代码工程（读/检索/精确编辑/构建/git）、邮件、通知、媒体 OCR、站点凭据自动登录 | `browser`、`system`、`desktop`、`code`、`email`、`notification`、`media`、`site` |
| 记忆与知识 | 长期事实记忆、对话情景、人格、显式纠错、知识库 RAG、记忆图谱、审计日志 | `memory`、`memory.store`、`memory.retrieval`、`memory.correction`、`memory.graph`、`ui.javafx.memory`、`ui.javafx.knowledge` |
| 长时任务 | SDD 托管任务（提案→规格→设计→拆解→实现→验收）、循环模式自动迭代、状态图工作流，均支持暂停恢复与预算闸门 | `task.sdd`、`loop`、`workflow`、`ui.javafx.task`、`ui.javafx.workflow` |
| 扩展生态 | 技能自学习、MCP Server、插件系统、定时任务、工作区隔离、主题换肤 | `skill`、`mcp`、`plugin`、`schedule`、`config`、`ui.javafx.theme` |

按使用场景理解，项目主要覆盖这些工作流：

- **日常智能助理**：多会话对话、附件理解、联网浏览、文件处理、命令行与系统操作。
- **方案研讨助手**：规划模式下让多个专家围绕同一问题讨论，输出更稳妥的设计或执行方案。
- **目标自动推进**：循环模式反复迭代同一目标（改到测试通过、等 CI 出结果、盯某个状态变化），带客观核验与停止护栏。
- **工程任务托管**：把复杂需求转成 OpenSpec 风格的验收场景和任务清单，逐步实现、核验、补做。
- **流程固化复用**：把跑通的步骤画成状态图发布，之后一键重复执行，中断可从检查点续跑。
- **个人知识与记忆工作台**：沉淀长期事实、人格偏好、对话情景和知识文档，并通过向量召回注入上下文。
- **可扩展智能体平台**：通过技能、MCP、插件和定时任务持续扩展能力边界。

## ✨ 功能特性

### 💬 智能对话与编排
- **五种工作模式**
  - **普通模式** —— 日常对话与工具调用，自动拆解目标、监控执行、按需调整计划
  - **规划模式** —— 多专家协同讨论，多轮协作产出可执行方案
  - **循环模式** —— 反复推进同一目标直到达成：自动多轮迭代 + 上下文接力 + 收敛判定，可自驱或按间隔轮询（首行写 `@loop interval=5m max=20`）
  - **托管任务（SDD）** —— 长时复杂任务按「提案 → 规格 → 设计 → 拆解 → 实现 → 验收」六阶段自动推进，可验收、绝不假完成
  - **工作流模式** —— 在工作流中心可视化编排 Java 原生状态图（节点 / 条件分支 / 重试），逐节点检查点、可暂停续跑
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
| 🪟 桌面自动化 | 跨平台「操作任意软件」：截图 → 视觉模型定位 → 点击 / 输入，窗口枚举 / 激活 / 启动按 OS 自动适配（macOS / Windows / Linux，免原生依赖） |
| ⏰ 定时任务 | 间隔 / 每日 / Cron 三种触发 |
| 💻 代码工程 | 按行读取 / 正则检索 / glob 查找 / 唯一片段精确编辑 / 构建测试（自动探测 mvn·gradle·npm·go·cargo）/ git 状态与提交，项目根围栏保护 |
| ⎇ 工作流中心 | 可视化设计状态图（节点 / 条件分支 / 重试 / 撤销重做），校验后发布运行，逐节点检查点与运行历史续跑 |
| ☕ JShell 执行 | 在隔离环境中运行 Java 片段或技能脚本 |
| 🔌 MCP 集成 | 连接多个 Model Context Protocol Server，统一发现与调用工具 |
| 🧩 插件系统 | 加载 jar 插件扩展工具与技能，首次启用经用户授权其声明的能力，按工作区隔离启用态 |

### 🌱 技能与自学习
- **技能体系** —— 技能即智能体的程序性记忆，支持条件激活、三级渐进加载与版本管理
- **自学习闭环** —— 智能体在使用中沉淀新技能，经轮后 / 任务后蒸馏并由用户审阅后落地
- **技能中心** —— 可视化管理技能、查看使用统计、回滚版本、审阅待定提案

### 🧬 长期记忆
- **统一记忆基座** —— 基于 **EclipseStore 对象图 + JVector 向量索引**，统一承载长期事实、对话情景、实体、人格、会话检查点
- **语义检索注入** —— 每轮按当前问题做向量 Top-K 召回，只注入最相关的记忆（取代整文件灌入）
- **自动沉淀去重** —— 轮后从对话蒸馏可长期记住的事实，向量去重后增量入库，新事实否定旧事实时自动取代（软删除留审计），用户手改 / 置顶的记忆受保护不被覆盖
- **显式纠错** —— 说一句「不是 X，而是 Y」即刻生效：本轮同步撤销旧记忆并写入更正，后续回答若又冒出旧错误会被拦下，不会说出口也不会写回记忆
- **降级不丢** —— 嵌入服务不可用时记忆以纯文本暂存（记忆中心可见），服务恢复后自动或一键重嵌入回填，对话不中断
- **记忆图谱** —— 事实 · 情景 · 实体三类节点的关联网络，力导向可视化，支持类别筛选、聚焦深度、点击节点查看关联
- **记忆中心** —— 八个分区的可视化管理面板：
  - **总览** 记忆健康度仪表盘（累计召回 / 命中 / 蒸馏 / 去重 + 记忆构成与近期变更）
  - **事实** 按主题分组，支持行内编辑（重嵌入 + 保护位）、置顶、批量删除、手动新增
  - **记忆图谱** 三栏交互（类别筛选 + 聚焦深度滑块 + 节点检视器）
  - **情景 / 实体** 对话轮时间线、记忆图实体节点
  - **知识库** 导入文档分块管理（重建索引 / 删除）
  - **人格** 结构化编辑（身份 / 语气 / 偏好 / 禁忌）+ 系统提示词注入预览
  - **变更日志** append-only 审计轨

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
| 智能体框架 | AgentScope Java | 1.0.12 |
| 记忆 / 向量存储 | EclipseStore + JVector | 4.1.0 |
| 结构化持久化 | H2（单文件全局库） | 2.3.232 |
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
首次启动会进入引导向导：选择模型提供商模板 → 填写 `baseUrl` / `model` / API Key → 完成。配置持久化在全局 H2 数据库 `data/javaclaw.mv.db`（按工作区隔离，API Key 加密存储），之后可在「设置」中随时修改。

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
