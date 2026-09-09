package com.javaclaw.desktop.shell;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;

/** 输入草稿按工作区和对话保存；启动回执只清理提交时的版本，不能清除后来输入的消息。 */
final class ComposerDrafts {
    private final Map<Scope, Submission> drafts = new HashMap<>();
    private final Map<Scope, Attempt> attempts = new HashMap<>();
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
        return current.equals(submitted.scope());
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
}
