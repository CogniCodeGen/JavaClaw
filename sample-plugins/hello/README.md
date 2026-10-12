# Hello v2

此示例声明工具 `hello_shout`、技能 `hello_style`、上下文 `hello_context`、工作流节点 `hello_workflow` 和约束钩子 `hello_policy`。钩子仅删除 `.delete` 权限并把工具调用预算限制到最多 8 次。

先在项目根目录运行 `mvn -pl plugin-runner-java -am install`，准备协议、SDK 和独立 Runner，再运行 `mvn -f sample-plugins/hello/pom.xml package`。也可在本示例目录运行 `mvn package`，工件仍是 `sample-plugins/hello/target/plugin.jar`，不依赖宿主 `javaclaw-desktop/target/`。随后使用 [SDK 打包 CLI](../../plugin-sdk-java/README.md) 生成 ZIP，在插件中心添加、配置 `greeting` 后启用。

工作流节点输入为 `{configuration:{text:"世界"},state:{...}}`，返回 `{setValues:{"hello.result":"你好，世界"},output:"你好，世界"}`。宿主验证状态路径并应用数据补丁。
