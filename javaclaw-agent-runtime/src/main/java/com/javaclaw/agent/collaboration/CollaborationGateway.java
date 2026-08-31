package com.javaclaw.agent.collaboration;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.javaclaw.core.api.AgentThread;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.ThreadSnapshot;
import com.javaclaw.core.api.TurnId;
import com.javaclaw.core.api.TurnInput;

/** Runtime-owned parent/child Thread and managed-worktree boundary. */
public interface CollaborationGateway {
    /** 为父 Thread 创建独立子 Thread 并启动任务；写任务必须经过受控 worktree 或单写者规则，不共享父可变状态。 */
    AgentThread spawn(SpawnRequest request);

    /** 给仍活动的子 Turn 追加输入；目标不可接收时返回 false。 */
    boolean steer(TurnId turnId, TurnInput input);

    /** 读取子 Thread 的持久快照；不存在时返回 Optional.empty。 */
    Optional<ThreadSnapshot> read(ThreadId childThreadId);

    /** 在 timeout 内等待子 Thread 达到终态并返回最近快照；调用方须检查状态以区分超时与完成。 */
    Optional<ThreadSnapshot> waitForTerminal(ThreadId childThreadId, Duration timeout);

    /** 取消子 Thread 的活动 Turn 并传播取消；没有可取消执行时返回 false。 */
    boolean cancel(ThreadId childThreadId);

    /** 列出父 Thread 的直属子 Thread，不授予对子 Thread 内部状态的直接写入权。 */
    List<AgentThread> children(ThreadId parentThreadId);

    /** 相对受控合成基线生成有界补丁，并以附件摘要返回；不修改父工作区。 */
    PatchResult diff(ThreadId childThreadId);

    /** 将子补丁显式应用到父工作区；调用方必须先完成正常审批，冲突时保留恢复信息且不得修改用户真实 index。 */
    PatchResult apply(ThreadId parentThreadId, ThreadId childThreadId, String idempotencyKey);

    /** 清理受控子 worktree；未合并内容仅在 discardUnmerged 显式为 true 时可放弃。 */
    boolean cleanup(ThreadId childThreadId, boolean discardUnmerged);

    /**
     * 子智能体创建请求；权限和目录仍由服务端解析，不由任务文本决定。
     *
     * @param parentThreadId 父 Thread 标识，执行时必须存在
     * @param task 非空白子任务说明
     * @param writable 是否请求隔离写能力；不等同于已经授权
     * @param profileId 执行使用的 Profile 标识，保存或解析时要求有效
     * @param idempotencyKey 可选幂等键，防止重复创建子任务
     */
    record SpawnRequest(
            ThreadId parentThreadId, String task, boolean writable, String profileId, String idempotencyKey) {
        /** 校验任务和 Profile 名称非空；工作区权限及配额由协作服务继续验证。 */
        public SpawnRequest {
            task = ThreadId.required(task, "task");
            profileId = ThreadId.required(profileId, "profileId");
        }
    }

    /**
     * 工作树 diff/apply 的结果摘要；补丁正文通过附件引用传递。
     *
     * @param status 非空白结果状态
     * @param patchAttachmentSha256 补丁附件摘要；没有补丁时可为 null
     * @param conflicts 冲突路径列表；null 归一为空列表
     * @param message 展示说明；null 归一为空字符串，不应包含凭据
     */
    record PatchResult(String status, String patchAttachmentSha256, List<String> conflicts, String message) {
        /** 固定冲突列表并归一说明，保留补丁为空与有补丁的区别。 */
        public PatchResult {
            status = ThreadId.required(status, "status");
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            message = message == null ? "" : message;
        }
    }
}
