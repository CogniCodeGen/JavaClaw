# ADR-001：Spring 生命周期与分层边界

状态：已接受

JavaClaw 使用 Spring Framework 的显式 Java 配置，不使用 Spring Boot、WebMVC
或宽泛组件扫描。根 Context 拥有进程级对象，工作区子 Context 拥有工作区资源。
切换工作区时先构建候选 Context，成功后替换并关闭旧 Context；构建失败保留旧实例。

Domain 不依赖 Spring。Controller 只能调用 Application 用例，基础设施通过端口接入。
禁止静态 ApplicationContext、业务代码中的 `getBean()` 以及把每个会话注册成 Bean。
