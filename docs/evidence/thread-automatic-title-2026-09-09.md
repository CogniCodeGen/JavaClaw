# 新对话自动标题（2026-09-09）

## 行为

点击“新建对话”直接创建当前工作区的空对话，临时标题为“新对话”，不再要求填写标题。
首次用户消息写入时，服务端从正文提取最多 32 个完整字符的简短标题，清理常见 Markdown 标记并合并空白；
长文本显示省略号，中文和组合 Emoji 不从中间拆开。不调用模型、不改写原消息。

自动标题与首条消息、Turn、幂等回执在同一事务保存。已有自定义标题和子任务标题不覆盖；后续消息和
重复提交不重新命名。空白或没有可用正文时保留默认标题。

Desktop 在发送确认后单独读取保存的标题，仅更新侧栏和顶部的会话快照，保留正文、回显、活动任务和输入状态。
辅助读取失败不改变发送成功结果；导航后丢弃迟到结果，返回默认标题且已有历史的对话时补读。

## 验证

Spotless、Checkstyle 和 37 项相关测试通过，无失败或跳过：

- Desktop 24 项：新建对话无标题弹窗、保留工作区命名、焦点、标题刷新、发送确认、查询失败、导航迟到隔离。
- App Server 13 项：标题提取、组合 Emoji、持久化、首消息/自定义/子任务边界、幂等回放、失败回滚。

```text
mvn -pl javaclaw-desktop spotless:apply spotless:check checkstyle:check test -Dtest=DesktopThreadTitleTest,DesktopShellNewThreadTest,DesktopSendResultTest,DesktopConversationNavigationTest
mvn -pl javaclaw-app-server spotless:apply spotless:check checkstyle:check test -Dtest=ThreadTitleTest,ThreadAutomaticTitleTest,TurnStartIdempotencyRpcTest
```

只测试受影响功能，没有运行全面 UI 测试或完整 verify。使用临时 H2、内存 RPC 和隔离 JavaFX 窗口，
不访问用户数据库、不调用付费模型。验证平台为 macOS arm64；Windows/Linux 未验证。
App Server 和 Desktop 需要重启加载新代码。
