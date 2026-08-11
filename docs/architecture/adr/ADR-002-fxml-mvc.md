# ADR-002：JavaFX FXML MVC

状态：已接受

所有生产页面、弹窗和复用控件的静态结构由 FXML 描述。Controller 只协调事件和
Application 用例，页面状态保存在不持有服务的 ViewModel 中。Markdown、图形和
Canvas 等算法可以在 Java 中生成节点，但节点必须填充到 FXML 声明的容器。

Controller 采用构造注入；只有 `@FXML` 成员允许字段注入。Controller 不直接访问
数据库、Manager，不创建线程，也不直接调用 `Platform.runLater`。
