# ADR 0002：Thin Turn Harness

- 状态：Accepted
- 日期：2026-09-01

## 背景

模型循环、工具治理与 Plan、Workflow、Schedule 等领域编排具有不同变化原因。将它们放在同一 Kernel 会形成条件
分支、隐式状态和无法独立验证的副作用恢复。

## 决策

`TurnHarness` 只执行单个 Turn，负责上下文、模型、工具、Item/Event、预算、取消、背压、子 Thread 和
EffectReceipt。每次模型/工具外部调用前提交 intent，结果后原子提交 usage、ToolResult、EffectReceipt 与 checkpoint；
冻结 ToolCatalog、可见工具和累计预算均可从 H2 重建。领域扩展通过 `TurnOrchestrationPort` 创建和观察 Turn，Harness
不识别领域阶段。

## 结果

安全与资源治理只实现一次，领域状态机可独立测试。跨 Turn 流程必须显式持久化扩展状态，不能依赖 Harness 内存或
提示词暗示。重启时只续跑已有安全 checkpoint；外部调用中的 orphan Turn 以 `UNKNOWN_OUTCOME` 失败关闭。
