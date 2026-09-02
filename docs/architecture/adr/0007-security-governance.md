# ADR 0007：冻结目录、PermissionProfile 与 EffectReceipt

- 状态：Accepted
- 日期：2026-09-01

## 背景

渐进发现能减少模型上下文，但若搜索同时改变工具来源或权限，就会成为动态提权通道。副作用在响应丢失后重试也
可能造成重复执行。

## 决策

Turn 启动时冻结工具 ID、来源、revision、schema hash 和权限 ceiling，搜索只能展开该集合。执行前重新检查实时
enabled/revision、撤权、风险和 PermissionProfile。最终权限取所有层级交集。模型和工具外部调用在开始前持久化
intent；外部副作用通过幂等键与 EffectReceipt 记录结果后再回填模型。结果无法确认的 intent 进入
`UNKNOWN_OUTCOME`，不得自动重试。

## 结果

目录快照提供可复现上下文，实时检查保证撤权立即生效。执行器必须区分“未执行”“已执行未回填”和“已回填”，
恢复代码不得把未知状态当作安全重试。
