# 质量门禁

提交前的权威验证命令是：

```bash
mvn clean -Pui-test verify
```

`clean` 是必要条件，避免历史 class 或覆盖率数据掩盖删除、过滤器和资源问题。`ui-test`
使用 `target/ui-test-data` 隔离数据，启用需要 JavaFX runtime 的测试，并跳过首启向导和托盘安装。

## 自动门禁

| 门禁 | 规则 |
|---|---|
| ArchUnit | Application 不依赖 JavaFX/Spring/Infrastructure；Infrastructure 不依赖桌面 Presentation；Controller 不直达 JDBC、Infrastructure 或旧 WorkspaceManager |
| FXML 完整性 | 枚举所有生产 FXML，验证 Controller、事件入口、`fx:id` 注入、`fx:include`、资源存在和 ID 唯一性 |
| 源码规模 | Controller 不超过 350 个非空行；其他 Java 类不超过 800；FXML 不超过 600 |
| 源码卫生 | 禁止未授权 `new Thread`、直接 `Platform.runLater`、静态 `getInstance()`、私建 ObjectMapper、空 catch、损坏字符和历史阶段注释 |
| 行为与生命周期 | 根/工作区 Context、事务、任务取消、并发配额、FXML 销毁、Plugin API 3.0 与主要 Controller 使用单元或 JavaFX 测试覆盖 |

直接线程只允许出现在单实例协调、退出清理 worker 和强制退出 watchdog 等启动/退出边界；
其余后台工作必须进入托管执行模型。

## JaCoCo

JaCoCo 在 `verify` 阶段生成报告并执行三个聚合检查：

| 范围 | include | 行覆盖率 | 分支覆盖率 |
|---|---|---:|---:|
| 整体 | 全部生产 class | ≥55% | ≥45% |
| 核心 | `application/**`、`platform/execution/**`、`runtime/**` | ≥80% | ≥70% |
| Controller | `**/*Controller.class` | ≥70% | ≥60% |

HTML 报告位于 `target/site/jacoco/index.html`。门禁使用聚合覆盖率；新增关键分支仍应在对应
行为测试中直接表达，不能只依赖整体数字。

## JavaFX 与截图基线

JavaFX 测试在真实 FX runtime 中装载页面，主聊天场景固定为 1200×700，并检查窗口尺寸、
FXML 注入、CSS class、控件可见性、交互路径和关闭行为。参考截图保存在
`docs/images/screenshots/01-main-chat.png` 至 `09-plugin-center.png`，用于主要窗口的显式视觉审查。

截图目前是显式审查基线，不做脆弱的逐像素自动比较；抗锯齿差异可以接受，但布局、CSS class、
控件可见性、窗口尺寸和交互路径变化必须由审查者确认并同步更新基线。

## 3.0 验收记录

2026-08-12 在 JDK 25 上执行全新 `mvn clean -Pui-test verify`：780 个测试通过，0 失败、
0 错误、0 跳过。对应覆盖率为：

| 范围 | 行覆盖率 | 分支覆盖率 |
|---|---:|---:|
| 整体 | 61.33% | 45.26% |
| 核心 | 90.03% | 71.73% |
| Controller | 81.28% | 60.20% |
