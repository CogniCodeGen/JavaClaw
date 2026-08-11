# FXML MVC 约定

每个生产页面由 `feature-view.fxml`、`FeatureController`、`FeatureViewModel` 组成。

- FXML 只描述稳定结构、CSS class、`fx:id`、可访问性和事件入口；
- Controller 使用构造注入，只协调用户事件与 Application UseCase；只有 `@FXML` 字段可以字段注入；
- ViewModel 只保存 JavaFX Property 页面状态，不引用 Service、Repository 或 Spring Context；
- Markdown、Canvas、图形等算法可在 Java 中生成 Node，但只能填充 FXML 声明的容器；
- Controller 不直接访问数据库、静态 Manager、`Platform.runLater` 或线程 API。

页面必须由 `SpringFxmlLoader` 加载。一次加载返回的 `ViewHandle` 记录主 Controller 及嵌套
FXML Controller；关闭时按创建顺序的反序执行 `destroyBean`，并调用实现了 `AutoCloseable`
的 Controller。ListCell 等高频对象只在构造期加载一次 FXML，复用时只替换 ViewModel 数据。
