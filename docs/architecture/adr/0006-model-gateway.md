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

## 结果

通用映射可复用，Provider 差异保持局部。新增原生能力必须先扩展 capability 与契约测试，不能通过类型判断或字符串
Provider 名称进入 Harness。
