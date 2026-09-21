# ADR 0006：Spring AI 与 Provider 原生能力

- 状态：Accepted
- 日期：2026-09-01

## 背景

多 Provider 具有共同的 prompt、stream、tool call 与 usage 语义，也存在 Responses opaque state 和 native compaction
等无法压平的能力。直接维护多套 HTTP/SSE 会重复协议实现，完全取最小公分母又会丢失关键能力。

## 决策

`ModelGateway` 由 Spring AI 通用 Adapter 和小型 Provider 原生扩展组成。`ModelCapabilities` 显式声明能力；
`NativeConversationSupport`、`NativeCompactionSupport` 与 `ProviderStateCodec` 只存在于 model-adapters 边界。
Harness 自己掌握工具循环和治理。

### OpenAI 兼容流的工具分片

Spring AI 2.0.0 的 `ChunkMerger` 以续片是否携带 ID 判断是否创建工具调用。
兼容服务在续片中重复 ID 或发送空 ID、稍后才发送函数名时，会产生残缺调用并触发 `Optional.get()` 异常。
相关上游记录见 [spring-ai#6591](https://github.com/spring-projects/spring-ai/issues/6591)。

`OpenAiStreamingClient` 在 model-adapters 内装饰官方 SDK 的公开异步接口，按 choice/index 合并工具参数，
仅在 `tool_calls` 正常结束且身份完整时交给 Spring AI。重复身份保持幂等，迟到身份可以补齐；身份冲突、
残缺调用和未结束的流必须失败，不能猜测工具名或提交部分工具。正文与 usage 仍逐块传递。
请求和结果继续由 Spring AI 映射，HTTP、鉴权及重试继续由原 SDK 管理，不新增 HTTP/SSE 解析器或私有反射访问。

合并状态和完成 future 按响应隔离，结束校验失败不能被 SDK 的正常完成覆盖。显式关闭仅释放当前响应，
丢弃未完成工具。该兼容层不改变 Spring AI 现有的 Reactor 取消行为，也不接管 Harness 的工具执行。
本地 SSE 回归必须经过真实 SDK 和 Spring AI，覆盖参数分片、并行工具、迟到字段、连续调用和失败路径；
仅使用假的 `ChatResponse` 不能验证此边界。

## 结果

通用映射可复用，Provider 差异保持局部。新增原生能力必须先扩展 capability 与契约测试，不能通过类型判断或字符串
Provider 名称进入 Harness。
