# 升级到 JavaClaw 3.0

JavaClaw 3.0 是一次集中架构切换，不与 2.x 维持双架构。界面布局、CSS class、快捷键、托盘和
主要交互保持兼容，但运行环境、数据格式、插件接口、对象生命周期和并发模型均发生变化。

## 升级结论

| 项目 | 3.0 行为 |
|---|---|
| 项目版本 | `3.0.0-SNAPSHOT` |
| 运行环境 | JDK 25、JavaFX 25 |
| 对象装配 | Spring Framework 7.0.8，不使用 Spring Boot/WebMVC |
| 默认数据目录 | `<项目目录>/data`，必须带 `.javaclaw-format` 且内容为 `3` |
| 旧数据 | 不自动迁移 2.x 数据库、配置、知识库、记忆或插件 |
| Plugin API | Host API 3.0；`plugin.json.apiVersion` 必填 |
| UI | 所有生产页面、弹窗、复用控件和 Cell 使用 FXML 静态结构 |
| 后台任务 | 显式选择 IO、CPU、BROWSER 或 PROCESS workload，统一取消和回收 |

## 升级步骤

### 从 2.x 升级

1. 完全退出 JavaClaw，确认没有 JavaClaw 进程持有数据库或单实例锁。
2. 备份整个旧 `data/` 和外部 `plugins/`；备份应放在项目目录之外。
3. 将旧 `data/` 移出默认路径。3.0 不会把非空旧格式目录自动改写为新格式。
4. 切换到 3.0 代码并执行 `mvn clean -Pui-test verify`。
5. 执行 `mvn javafx:run`。首次启动会创建新的 `data/`、`.javaclaw-format` 和初始 schema。
6. 重新配置模型、工作区、知识、记忆和插件；不要直接复制旧数据库或旧向量索引。

如果非空 `data/` 缺少正确标记，应用会在 H2、JavaFX 和后台服务启动前拒绝运行。这是格式保护，
不是启动故障；应先确认目录来源，而不是手工补标记绕过检查。

### 从早期 3.0 开发构建升级

早期开发构建曾使用 `data-v3/`。如果该目录的 `.javaclaw-format` 内容已经是 `3`，可在应用完全
退出后将整个目录重命名为 `data/`；数据库、知识、记忆、技能和日志无需分别搬运。不要只移动
`javaclaw.mv.db`，否则文件资产与数据库中的工作区引用会失配。

## 插件升级

所有插件都必须更新描述符：

```json
{
  "id": "example-plugin",
  "name": "示例插件",
  "version": "1.0.0",
  "apiVersion": "3.0",
  "main": "com.example.ExamplePlugin",
  "capabilities": ["CHAT"]
}
```

主要代码变化：

- 缺失、空白或主版本不是 3 的 `apiVersion` 会在创建类加载器前被拒绝；
- `ManagedTask` 与 `ServiceHandle` 统一为 `TaskHandle<T>`；
- 任务接收只读 `TaskContext`，通过任务 ID、取消信号和线程中断协作结束；
- 周期接口改为 `scheduleAtFixedRate(name, initialDelay, period, task)`；
- 插件不得创建线程池，底层复用宿主执行器，同时保留每插件独立配额；
- 停用和卸载会拒绝新任务、取消并等待在途任务，然后释放插件与类加载器。

仓库不提供 Plugin API 2.x 兼容适配器。完整接口见 [Plugin API 3.0](architecture/plugin-api-3.md)。

## 架构变化

| 2.x/旧实现 | 3.0 当前实现 | 影响 |
|---|---|---|
| JavaFX Controller 同时构建界面和执行业务 | FXML View + Controller + ViewModel | Controller 只协调事件，页面状态可独立测试 |
| Java 布局和零散弹窗工厂 | 全量生产 FXML 与统一加载器 | CSS、注入、复用控件和生命周期有一致契约 |
| Manager 与静态单例跨层访问 | Application Service/UseCase + Port | JavaFX、Shell、Agent Tool、Schedule 共享业务入口 |
| 手工创建全局对象 | Spring 根 Context + 工作区子 Context | 依赖、作用域、关闭顺序和工作区切换由组合根管理 |
| 原始 JDBC 分散访问 | DataSource + JdbcTemplate + 事务管理器 | schema 初始化一次，事务回滚和连接策略统一 |
| 零散线程池、回调和调度器 | `ManagedTaskExecutor`、`TaskScope`、`TaskHandle` | 配额、超时、取消、上下文传播和关闭回收统一 |
| Controller 直接 `Platform.runLater` | `FxDispatcher` + `UiAsyncAction` | busy、失败、取消和迟到结果处理一致 |
| 进程调用各自处理输出与终止 | `ProcessRunner` | 输出限制、超时、中断和进程树清理统一 |
| 工具各自授权和格式化 | `ToolInvocationPipeline` | 来源、授权、审计、异常和结果格式统一 |
| 对话逻辑集中在单个服务 | 有序 `TurnPipeline`/`TurnStage` | 视觉、路由、RAG、上下文、流式、记忆和技能可组合 |

架构全景和专项约束见 [3.0 架构](architecture/README.md)。

## 兼容与不兼容范围

保持不变：

- 主窗口默认 1200×700，现有布局、CSS class、主要文案和快捷键；
- 多会话、五种工作模式、主题、托盘与窗口关闭语义；
- H2 `AUTO_SERVER` 不可用时回退 embedded 的行为；
- macOS 托盘退出死锁规避、五秒 watchdog 和最终强制退出兜底。

不提供兼容：

- 2.x 数据目录和 schema 的自动迁移；
- Plugin API 2.x 描述符或任务接口；
- 静态 `AppDatabase`、`DataManager`、`WorkspaceManager` 单例访问；
- 生产 Controller 中的 Java 布局、直接线程创建和直接 FX 调度。

## 验证与回滚

升级后的权威验证命令：

```bash
mvn clean -Pui-test verify
```

当前验收基线为 780 个测试通过、0 失败、0 错误、0 跳过，并通过 ArchUnit、FXML、源码卫生、
规模和三组 JaCoCo 门禁。

需要回滚时，先退出 3.0 并备份当前格式 3 的 `data/`，再恢复升级前备份的旧目录和旧应用版本。
旧版本不能直接打开 3.0 数据，3.0 也不会自动降级 schema。
