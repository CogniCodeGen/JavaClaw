# JavaClaw 仓库目录说明

本文说明仓库根目录、九个 Maven 模块和模块内标准目录的归属。目录数量不等同于业务模块数量：
源码边界、构建配置、测试夹具、可再生输出和本机运行数据具有不同的生命周期。

## 1. 目录分类

| 目录 | 分类 | 用途 | Git 策略 |
|---|---|---|---|
| `.git/` | 版本控制 | Git 对象、索引和分支历史 | Git 自身管理 |
| `.github/` | 工程配置 | GitHub Actions CI 工作流 | 提交 |
| `.claude/` | 本机配置 | 当前开发者的 Claude 设置 | 忽略 |
| `.idea/` | 本机配置 | IntelliJ 工作区、运行状态和数据源设置 | 忽略 |
| `.javaclaw/` | 敏感运行数据 | v4 H2、凭据主密钥、日志和缓存 | 严格忽略 |
| `config/` | 工程配置 | Checkstyle 规则和 Spotless XML 格式化参数 | 提交 |
| `docs/` | 文档 | 架构 ADR、升级、开发规范和 UI 回归资料 | 提交 |
| `skills/` | 项目协作资料 | 开发与审查工作流使用的 Skill | 提交 |
| `javaclaw-*/` | 产品源码 | 九个 Maven 功能领域模块 | 提交 |
| `target/`、`*/target/` | 可再生输出 | 编译、测试、发行包和视觉测试结果 | 忽略 |

`config/` 不是 JavaClaw 业务配置。它只被根 `pom.xml` 的 Spotless 和 Checkstyle 插件读取，
不保存 Provider、MCP、数据库连接或用户凭据。业务运行配置统一位于 `.javaclaw/config-v4/`，
并且不得加入版本控制。

旧版可能在仓库根生成 `data/`、`logs/` 和 `plugins/`。v4 不读取或迁移这些目录；确认应用退出后，
应先归档到仓库外再清理。当前 v4 的 `.javaclaw/` 不属于旧数据，不能一并删除。

## 2. Maven 模块

Reactor 已由 23 个技术模块收敛为九个功能领域模块。依赖方向由根 POM 和架构测试共同约束：

| 模块 | 职责 | JavaClaw 模块依赖 |
|---|---|---|
| `javaclaw-api` | 核心领域对象与 Sandbox 公共 API | 无 |
| `javaclaw-protocol` | JSON-RPC 消息、Schema 和编解码 | 无 |
| `javaclaw-agent-runtime` | Agent 内核、上下文、工具、知识与自动化 | `api` |
| `javaclaw-app-server` | RPC、H2、Provider、MCP、Plugin、安全与装配 | `api`、`protocol`、`agent-runtime` |
| `javaclaw-native-hosts` | FFM、Sandbox Launcher 与 Windows Transport Host | `api`、`protocol` |
| `javaclaw-browser-service` | 隔离的浏览器渲染服务 | `protocol` |
| `javaclaw-client` | Typed SDK、Protocol Mapper 与 CLI | `protocol` |
| `javaclaw-desktop` | JavaFX Desktop UI | `client` |
| `javaclaw-packaging` | Launcher、发行组装、签名和平台门禁 | 其余产品模块 |

这些模块对应公共契约、独立进程、安全边界或产品入口，不应仅为减少目录数量继续合并。
详细决策见 [ADR-0011](architecture/adr/0011-domain-module-consolidation.md)。

## 3. 模块内目录

每个模块沿用 Maven 标准结构：

- `pom.xml`：模块依赖、插件和构建元数据。
- `src/main/java/`：正式 Java 源码。
- `src/main/resources/`：运行时 CSS、FXML、Prompt、Schema 或数据库 Migration。
- `src/test/java/`：单元、架构、场景和集成测试。
- `src/test/resources/`：固定测试夹具与视觉 Golden；测试运行不得在这里写入临时截图。
- `target/`：全部可再生的编译和测试输出，可用 `mvn clean` 清除。

测试当前截图、候选 Golden 和差异图统一生成到 `javaclaw-packaging/target/visual/`。所有
`src/test/resources/` 目录默认忽略新增文件；确需新增或更新固定夹具时，必须显式强制添加并单独审查。

## 4. 根文件

- `pom.xml`：九模块 Reactor、依赖版本和工程门禁。
- `README.md`：构建、运行、配置和文档入口。
- `AGENTS.md`：仓库开发与交付约定。
- `.editorconfig`、`.gitattributes`：编码、换行和 Git 文件行为。
- `.gitignore`：构建输出、本机配置与敏感运行数据的排除策略。
- `run.sh`、`run.cmd`：macOS/Linux 和 Windows 的统一启动脚本。
- `LICENSE`：MIT License。
- `CLAUDE.md`：开发者本机文件，默认忽略。

## 5. 清理与安全规则

1. 清理前停止 Desktop 和 App Server，避免复制活动 H2 或未完成日志。
2. 仅用 `mvn clean` 清除构建输出，不手工删除源码或固定测试夹具。
3. 旧 `data/`、`logs/`、`plugins/` 先在仓库外归档并核对 SHA-256，再从工作区移除。
4. `.javaclaw/`、IDE 数据源、环境文件、日志、数据库和凭据文件不得提交。
5. 提交前检查暂存区和忽略状态；测试生成图片不得出现在 `git status` 中。
