# 工作区创建修复（2026-09-09）

## 已确认原因

真实 IDEA App Server 日志在 18:35:45 记录 `workspace/create` 失败：H2 `23505`，违反
`CORE.WORKSPACE(ROOT_PATH)` 唯一约束。用户选择了已经登记的目录，原实现将数据库异常统一显示为
`internal persistence error`。这是目录重复登记，与用户输入的工作区名称是否传给服务端是两件事。

## 调整

- 创建流程利用主窗口已有的工作区目录快照检查所选目录，提供“打开已有工作区”或“选择其他目录”；
  换目录时保留已输入名称，不新增配置读取。归档目录不自动恢复。
- 服务端按规范化路径查重，并返回包含已有工作区名称的中文说明；保留数据库唯一约束。
  并发插入的唯一冲突在原事务回滚后核验，避免旧 SERIALIZABLE 快照无法看到已提交工作区。
  Workspace、项目指令设置、执行默认值和幂等回执继续在同一事务提交。
- 名称和路径超出数据库长度限制时提前返回可操作提示。
- 创建入口改为固定 32×32 的按钮和 14×14 的矢量加号；选择器可收缩，长名称不再挤压按钮。
  复用现有绿色主题、圆角、悬停和按下状态，补充用途提示及无障碍名称。

## 验证范围

仅检查 App Server 和 Desktop 受影响模块的 Spotless、Checkstyle 与工作区相关测试，不运行全面 UI 回归或完整 verify。

- 服务端：重复/规范化目录、归档目录、名称及路径上限、并发创建、旧快照、幂等回放、失败原子性、无关 SQL 错误分类。
- Desktop：自定义中文名称经 SDK 提交、重复目录打开/改选/取消、归档限制，以及局部工作区操作行在窄宽度、长名称、最大字号下的布局和反馈。

测试使用隔离数据库和局部 JavaFX Scene，未修改用户真实数据库。验证平台为 macOS arm64；Windows/Linux 未验证。
正在运行的 App Server 和 Desktop 需要重新启动以加载新代码。

结果：Spotless 与 Checkstyle 通过，相关测试共 16 项通过（服务端 9 项、Desktop 7 项），无失败或跳过。
最后一次服务端收窄错误分类后，仅复跑 `WorkspaceCreationConflictTest` 的 7 项，全部通过。
另用实际 FXML 操作行生成并检查默认/悬停状态局部截图，确认加号清晰、居中、无省略号。

执行记录：

```text
mvn -pl javaclaw-app-server,javaclaw-desktop spotless:apply
mvn -pl javaclaw-app-server spotless:check checkstyle:check test -Dtest=WorkspaceCreationConflictTest,WorkspaceLifecycleServiceTest
mvn -pl javaclaw-desktop spotless:check checkstyle:check test -Dtest=WorkspaceCreationButtonTest,ShellWorkspaceCreationTest,DesktopWorkspaceCreationTest
mvn -pl javaclaw-app-server spotless:apply spotless:check checkstyle:check test -Dtest=WorkspaceCreationConflictTest
```
