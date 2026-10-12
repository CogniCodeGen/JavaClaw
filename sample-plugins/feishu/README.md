# 飞书插件 v2

飞书 SDK、WebSocket 连接与 API 请求均留在独立插件进程。`App Secret` 在清单中声明为秘密；`host.agent.chat` 只允许插件调用宿主登记的 Agent 对话入口。后台消息并发最多为 16。

先在项目根目录运行 `mvn -pl plugin-runner-java -am install`，准备协议、SDK 和独立 Runner，再运行 `mvn -f sample-plugins/feishu/pom.xml package`。也可在本示例目录运行 `mvn package`。使用 [SDK 打包 CLI](../../plugin-sdk-java/README.md) 时，根目录相对路径为 `sample-plugins/feishu/plugin.json` 与 `sample-plugins/feishu/target/plugin.jar`，无需宿主构建产物。在插件中心添加，填写 App ID、App Secret 并授权 `host.agent.chat` 后启用。

插件声明 `feishu_send`、`feishu_messages` 工具，以及 `feishu_reply_style` 技能。接收飞书文本后，插件发送 `agent/chat` 请求 `{prompt,conversationId,source:"feishu"}`，使用宿主返回的 `{text}` 回复原会话。会话由宿主按工作区与插件身份隔离。
