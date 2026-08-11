# Plugin API 3.0

JavaClaw 3.0 只加载 `plugin.json` 中显式声明 `"apiVersion": "3.x"` 的插件。
字段缺失、空白或主版本不匹配都会在创建插件类加载器之前拒绝；不提供 2.x 适配器。

## 执行模型

插件不得创建线程、线程池或调度器。所有工作通过 `PluginContext.exec()` 提交，底层复用
宿主的全局虚拟线程执行引擎，并叠加每插件 32 个并发任务的独立配额。插件卸载时只关闭
自己的 `TaskScope`，不会影响其他插件或工作区。

每个 `PluginTask` 和 `PluginCallable` 都收到只读 `TaskContext`：

- `taskId()` 是句柄生命周期内稳定的审计 ID；
- `cancellation()` 是协作取消信号；长循环还必须响应线程中断；
- 上下文不得缓存到任务结束之后。

所有提交方式统一返回 `TaskHandle<T>`。状态只按
`QUEUED → RUNNING → SUCCEEDED/FAILED/CANCELLED` 前进，`completion()` 提供完成结果，
`cancel()` 与 `close()` 都会停止后续触发并中断当前承载线程。

## API 示例

```java
TaskHandle<Void> listener = ctx.exec().background("message-listener", task -> {
    while (!task.cancellation().isCancelled()) {
        receiveOneMessage();
    }
});

TaskHandle<Void> heartbeat = ctx.exec().scheduleAtFixedRate(
        "heartbeat", Duration.ZERO, Duration.ofSeconds(30),
        task -> sendHeartbeat());
```

同一个固定频率任务不会重叠执行；前一轮尚未结束时跳过该次触发。插件 `stop()` 可以主动
关闭保存的句柄，宿主也会在停用/卸载时兜底取消全部句柄。

## 描述符

```json
{
  "id": "example-plugin",
  "name": "示例插件",
  "version": "1.0.0",
  "apiVersion": "3.0",
  "main": "com.example.ExamplePlugin",
  "capabilities": ["CHAT"]
}
```

仓库中的 `sample-plugins/hello` 与 `sample-plugins/feishu` 是 3.0 参考实现。

## 能力、调用与卸载边界

描述符中的能力声明先由宿主授权，再由 `ToolInvocationPipeline` 绑定调用来源、完成风险确认、
审计、执行和 `ToolResult` 映射。插件不能绕过该管线直接复用交互式工具实例。

停用或卸载按以下顺序执行：拒绝新的插件任务，关闭该插件的 `TaskScope`，取消并等待在途句柄，
调用插件 `stop()`，最后释放类加载器。某个关闭步骤失败不会跳过后续资源释放；失败会被记录并
附加到最终卸载结果。
