# 执行与取消模型

后台工作必须经 `ManagedTaskExecutor` 或其 `TaskScope` 提交，并通过 `TaskSpec` 显式声明
负载类型、名称、超时和可选串行键。业务代码不使用默认 `@Async`，也不自行创建线程池。

## 全局资源池

| Workload | 承载线程 | 默认配额 | 典型用途 |
|---|---|---:|---|
| `IO` | virtual-thread-per-task | 256 | 模型、HTTP、MCP、JDBC、文件、RAG |
| `CPU` | 平台线程池 | `max(1, CPU-1)`，队列 256 | embedding、解析、计算 |
| `BROWSER` | virtual-thread-per-task | 4 | Playwright；相同 `serializationKey` 额外串行 |
| `PROCESS` | virtual-thread-per-task | 8 | `ProcessRunner`、JShell 和短进程 |

单 daemon 平台调度线程只处理超时和轻量触发；触发器必须把正文重新提交到合适的
workload。插件在全局资源池之上再使用独立 `TaskScope`，默认每插件最多 32 个并发任务。

## 任务状态与取消

`TaskHandle<T>` 的状态只按下列方向前进：

```text
QUEUED -> RUNNING -> SUCCEEDED
                  -> FAILED
                  -> CANCELLED
```

- `completion()` 暴露终态结果；失败保留原始异常；
- `cancel()` 是幂等请求，会设置协作取消信号并中断承载线程；
- 任务应同时检查 `TaskContext.cancellation()` 并正确响应 `InterruptedException`；
- 超时使用同一取消路径，不使用 `Thread.stop`；
- 提交时的 MDC 与任务 ID 会传播到承载线程，任务结束后恢复原上下文；
- 句柄只有在承载线程真正退出后才从作用域登记表移除。

`TaskScope.close()` 先拒绝新任务，再取消已登记任务，最多等待五秒。工作区切换和插件
卸载只关闭自己的作用域，不关闭根 Context 持有的全局执行器。

## UI 异步边界

Controller 通过 `UiAsyncAction<T>` 启动作业。该对象统一管理 busy、成功、失败、取消和
世代号；页面关闭或新请求取代旧请求后，旧任务的迟到结果会被丢弃。所有通用 FX 切换
由 `FxDispatcher` 完成，Controller 不直接调用 `Platform.runLater`。

## 外部 I/O

- `ProcessRunner` 同时排空 stdout/stderr，限制输出大小；超时、取消或中断时终止完整进程树。
- `HttpGateway` 使用共享 `HttpClient`；只有 GET、HEAD 等幂等请求可以自动重试。
- Playwright Page/Context 使用串行键保证同一浏览器对象并发为 1，同时受全局浏览器配额保护。
- `ToolInvocationPipeline` 在执行前完成来源绑定、风险授权和审计，执行后统一映射 `ToolResult`。

除 `SingleInstanceCoordinator`、退出清理 worker 和强制退出 watchdog 外，生产源码出现
`new Thread(...)` 会触发 `SourceHygieneTest` 构建失败。
