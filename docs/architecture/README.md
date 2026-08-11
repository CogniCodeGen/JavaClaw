# JavaClaw 3.0 架构

JavaClaw 3.0 采用分层桌面架构：FXML View 与 Controller 属于 Presentation，
Application 用例承载业务入口，Domain 保存规则，Infrastructure 实现数据库、文件、
HTTP、进程和外部模型端口。依赖只允许从外层指向内层。

根 Spring Context 管理进程级基础设施；每个工作区使用一个可关闭的子 Context。
页面 Controller 由 `SpringFxmlLoader` 创建并随 `ViewHandle` 销毁，不使用静态
ApplicationContext 或全局 `getBean()`。

## 决策记录

- [ADR-001：Spring 生命周期与分层边界](adr/ADR-001-spring-lifecycle.md)
- [ADR-002：JavaFX FXML MVC](adr/ADR-002-fxml-mvc.md)
- [ADR-003：执行与取消模型](adr/ADR-003-execution-model.md)
- [ADR-004：3.0 数据格式](adr/ADR-004-data-format-v3.md)
