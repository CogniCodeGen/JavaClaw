# ADR-0009：父子 Thread 与 Git worktree 写隔离

- 状态：Accepted
- 日期：2026-08-27

## 决策

子智能体是独立 Thread。Git 写任务在平台 cache 创建 detached linked worktree，使用临时
`GIT_INDEX_FILE` 合成父工作区当前 tracked 与 non-ignored untracked 基线，不修改用户 index。
子结果相对合成基线生成有界 binary patch；父 Turn 审批后通过临时 index 三方应用。冲突时保留
父文件备份、冲突信息与子 worktree。非 Git Workspace 只允许一个写者。

配额固定为每父 Thread 最多 4 个子智能体、每 Workspace 最多 8 个活动 Turn。取消向子 Thread
传播；子 Thread 不能直接修改父状态。

## 结果

并行读取和隔离写入共享统一 Runtime；Workflow/Subagent 不再创建第二套 Agent Engine。
