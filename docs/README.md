# JavaClaw 文档

本目录描述 JavaClaw 3.0 的当前功能、架构和升级方式。架构文档只维护当前有效设计，版本变化
统一记录在升级指南中，不再保留已经失效的历史设计记录。

## 文档导航

| 文档 | 适合读者 | 内容 |
|---|---|---|
| [整体功能](features.md) | 用户、产品、测试 | 工作模式、智能体、工具、记忆、知识、自动化、扩展与安全能力 |
| [升级到 3.0](upgrade-3.0.md) | 用户、维护者、插件作者 | 升级步骤、数据边界、Plugin API 变化、架构对照和回滚方式 |
| [3.0 架构](architecture/README.md) | 开发者、评审者 | 分层、Spring 生命周期、请求链、工作区、持久化和扩展边界 |
| [FXML MVC](architecture/fxml-mvc.md) | JavaFX 开发者 | View、Controller、ViewModel、异步动作和页面销毁规范 |
| [执行与取消](architecture/execution-model.md) | 后台任务开发者 | 虚拟线程、负载池、配额、任务状态、取消与外部进程 |
| [数据与存储](architecture/data-storage.md) | 运维、持久化开发者 | `data/` 格式、H2、事务、工作区文件和备份边界 |
| [Plugin API 3.0](architecture/plugin-api-3.md) | 插件作者 | 描述符、能力授权、任务句柄、调度和卸载协议 |
| [质量门禁](architecture/quality-gates.md) | 贡献者、CI 维护者 | 测试、ArchUnit、FXML、源码卫生、规模和覆盖率要求 |

## 推荐阅读路径

- 第一次使用：整体功能 → 升级到 3.0 → 根目录 README 的快速开始。
- 修改业务：3.0 架构 → 对应 Application Service/UseCase → 质量门禁。
- 修改界面：3.0 架构 → FXML MVC → 质量门禁。
- 增加异步任务或外部调用：执行与取消 → 3.0 架构中的平台能力。
- 编写插件：升级到 3.0 → Plugin API 3.0。

## 维护原则

- 用户可见能力只在 [整体功能](features.md) 中完整说明，根 README 仅保留摘要。
- 架构文档描述当前代码，不记录已经废弃的静态单例、Java 布局或旧线程模型。
- 兼容性、迁移和破坏性变化写入 [升级到 3.0](upgrade-3.0.md)。
- 专项文档只解释稳定边界，不复制其他文档中的整段实现细节。
- 文件名、类名、命令和默认值必须能从当前源码或构建配置中验证。
