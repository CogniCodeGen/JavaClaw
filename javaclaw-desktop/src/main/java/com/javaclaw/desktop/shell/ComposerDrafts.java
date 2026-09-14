package com.javaclaw.desktop.shell;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.state.OutgoingMessage;

/** 草稿与已提交版本分别保存；本地接纳只迁出对应版本，回执不参与清空后来编辑的草稿。 */
final class ComposerDrafts {
    private final Map<Scope, Submission> drafts = new HashMap<>();
    private final Map<Scope, Attempt> attempts = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<Scope, Restored> restored = new HashMap<>();
    private Scope current = new Scope(Optional.empty(), Optional.empty());
    private long revision;

    String bind(Scope next, String text) {
        if (next.equals(current)) {
            return text;
        }
        Submission previous = drafts.computeIfAbsent(current, ignored -> new Submission(current, ++revision, text));
        boolean initialConversation = current.thread().isEmpty()
                && (current.workspace().isEmpty() || current.workspace().equals(next.workspace()));
        if (initialConversation && !drafts.containsKey(next)) {
            drafts.put(next, new Submission(next, ++revision, previous.text()));
            drafts.remove(current);
        }
        current = next;
        return drafts.computeIfAbsent(next, ignored -> new Submission(next, ++revision, ""))
                .text();
    }

    void edited(String text) {
        drafts.put(current, new Submission(current, ++revision, text));
        attempts.remove(current);
        restored.remove(current);
    }

    Submission submission(String text) {
        return drafts.computeIfAbsent(current, ignored -> new Submission(current, ++revision, text));
    }

    /** 回执丢失后的同一草稿重试复用幂等身份；编辑或改变执行覆盖才代表新的发送意图。 */
    CommandOptions options(Submission submitted, ExecutionOverrides execution) {
        Attempt previous = attempts.get(submitted.scope());
        if (previous != null
                && previous.submitted().equals(submitted)
                && previous.execution().equals(execution)) {
            return previous.options();
        }
        CommandOptions options = CommandOptions.create(0);
        attempts.put(submitted.scope(), new Attempt(submitted, execution, options));
        return options;
    }

    boolean acknowledged(Submission submitted) {
        if (!submitted.equals(drafts.get(submitted.scope()))) {
            return false;
        }
        drafts.put(submitted.scope(), new Submission(submitted.scope(), ++revision, ""));
        attempts.remove(submitted.scope());
        restored.remove(submitted.scope());
        return current.equals(submitted.scope());
    }

    void stage(Submission submitted, String sendId, long previousAttempt) {
        pending.put(sendId, new Pending(submitted, previousAttempt));
    }

    /** 合并后的状态也可能已失败或已确认；只有新的尝试号才证明本次提交曾被本地接纳。 */
    boolean accepted(OutgoingMessage outgoing) {
        Pending value = pending.get(outgoing.id());
        if (value == null || outgoing.attempt() <= value.previousAttempt()) {
            return false;
        }
        pending.remove(outgoing.id());
        return acknowledged(value.submitted());
    }

    void finished(String sendId) {
        pending.remove(sendId);
    }

    boolean restore(String sendId, String text) {
        if (!submission("").text().isEmpty()) {
            return false;
        }
        edited(text);
        restored.put(current, new Restored(submission(text), sendId));
        return true;
    }

    Optional<String> retrySource(Submission submitted) {
        return Optional.ofNullable(restored.get(submitted.scope()))
                .filter(value -> value.submitted().equals(submitted))
                .map(Restored::sendId);
    }

    /**
     * 草稿所属页面，组件均不可为 null。
     *
     * @param workspace 工作区，可缺省
     * @param thread 对话，可缺省
     */
    record Scope(Optional<WorkspaceId> workspace, Optional<ThreadId> thread) {}

    /**
     * 用于确认启动结果的不可变草稿版本。
     *
     * @param scope 提交作用域，不可空
     * @param revision 本地编辑版本，无单位
     * @param text 提交内容，不可为 null，但可为空文本
     */
    record Submission(Scope scope, long revision, String text) {}

    /**
     * 每个现有草稿最多保留一次待确认发送；切换页面不撤销原请求的重试身份。
     *
     * @param submitted 待确认的草稿及版本，不可空
     * @param execution 提交时的执行覆盖，不可空
     * @param options 同一次发送及其重试共用的幂等身份，不可空
     */
    private record Attempt(Submission submitted, ExecutionOverrides execution, CommandOptions options) {}

    /**
     * @param submitted 接纳前的草稿版本
     * @param previousAttempt 此身份上次尝试号，首次发送为 -1
     */
    private record Pending(Submission submitted, long previousAttempt) {}

    /**
     * @param submitted 恢复后尚未编辑的草稿
     * @param sendId 原消息的本地发送身份
     */
    private record Restored(Submission submitted, String sendId) {}
}
