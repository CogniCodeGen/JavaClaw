# FXML MVC 约定

每个生产页面由 `feature-view.fxml`、`FeatureController`、`FeatureViewModel` 三部分组成。
拆分粒度以独立状态和生命周期为依据；主聊天页进一步拆为 Composer、Session、Stream、
Mode、Sidebar 与 Workspace 等协作 Controller。

## 职责边界

| 部件 | 允许 | 禁止 |
|---|---|---|
| FXML | 静态结构、CSS class、`fx:id`、可访问性、事件入口 | 业务规则、动态查询、隐藏依赖 |
| Controller | 构造注入 Application Service、绑定 ViewModel、协调事件 | JDBC、Repository、静态 Manager、线程创建、`Platform.runLater`、大段布局构造 |
| ViewModel | JavaFX Property、选择、loading、校验、错误和页面数据 | Service、Repository、Spring Context、I/O |
| Renderer | 生成 Markdown、Canvas、图形等动态 Node | 绕过 FXML 重建页面骨架或拥有业务生命周期 |

Controller 的依赖使用构造注入；只有 FXMLLoader 负责的 `@FXML` 成员使用字段注入。可预期
失败由 Application 异常映射为页面状态，意外异常进入统一错误映射，不在 Controller 中复制
业务判断。

## 加载与销毁

完整页面和弹窗必须由 `SpringFxmlLoader` 加载：

1. `AutowireCapableBeanFactory.createBean()` 创建主 FXML 和所有 `fx:include` Controller；
2. FXMLLoader 注入 `@FXML` 字段并调用初始化回调；
3. `ViewHandle` 持有根 Node、主 Controller 和本次加载创建的全部 Controller；
4. 加载失败时立即反序释放已创建对象；
5. `ViewHandle.close()` 幂等地按创建反序调用 `AutoCloseable.close()` 和 `destroyBean()`，并聚合释放异常。

自定义 Cell 和复用控件使用 `EmbeddedFxmlLoader`：控件实例在构造期只加载一次静态模板，
复用时仅更新 ViewModel。此类 Controller 不由 Spring 管理，因此不得持有 Application Service，
也不得承担需要显式关闭的资源。

## 动态内容

Markdown、Thinking Panel、图形和 Canvas 等算法仍由 Java 实现，但只能向 FXML 声明的语义
容器增删 Node。不得以动态渲染为理由在 Controller 中重新构造工具栏、表单、列表骨架或弹窗。
CSS 文件、CSS class、快捷键、可访问性属性、窗口尺寸与交互文案属于兼容契约。

## 异步页面动作

Controller 使用 `UiAsyncAction<T>` 提交后台工作，并由 `FxDispatcher` 回到 FX 线程。
`UiAsyncAction` 统一维护 busy、成功、失败、取消与请求世代；页面关闭或新请求替代旧请求后，
迟到回调不得更新已经失效的 ViewModel。

## 测试要求

- `FxmlIntegrityTest` 枚举全部生产 FXML，检查 XML 安全解析、Controller、事件方法、`fx:id`、
  `fx:include` 和重复 ID；
- 页面加载测试使用 Spring 或明确的嵌入式加载器，验证字段注入、CSS class、主要可见性与尺寸；
- Controller 行为测试使用 fake Application Service 覆盖 loading、成功、校验失败、取消、异常和迟到结果；
- 生命周期测试必须关闭 `ViewHandle`，验证嵌套 Controller 的反序销毁；
- Cell 测试验证 FXML 只加载一次，后续更新不替换模板根节点。
