package com.javaclaw.desktop;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 进程内的 Thread 草稿仓库；切换会话时保留文本、附件与 Profile，但不把本地路径发送到服务端或写入磁盘。
 *
 * <p>发送成功只清除与已提交快照完全一致的草稿。用户在上传期间继续编辑时，新内容必须保留，不能被迟到的成功回调覆盖。
 */
final class ConversationDraftStore {
    private final Map<Key, Draft> drafts = new LinkedHashMap<>();
    private final Map<Key, String> lastSubmitted = new LinkedHashMap<>();

    Draft read(Key key) {
        return drafts.getOrDefault(Objects.requireNonNull(key, "key"), Draft.empty());
    }

    void save(Key key, Draft draft) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(draft, "draft");
        if (draft.isEmpty()) {
            drafts.remove(key);
        } else {
            drafts.put(key, draft);
        }
    }

    boolean accepted(Key key, Draft submitted) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(submitted, "submitted");
        if (!submitted.text().isBlank()) {
            lastSubmitted.put(key, submitted.text());
        }
        if (!submitted.equals(drafts.get(key))) {
            return false;
        }
        drafts.remove(key);
        return true;
    }

    /** 活动 Turn 接受追加指令后只清除相同的文本快照；尚未发送的附件和该 Thread 的 Profile 选择必须保留。 */
    boolean acceptedText(Key key, String submittedText) {
        Objects.requireNonNull(key, "key");
        String text = Objects.toString(submittedText, "");
        if (!text.isBlank()) {
            lastSubmitted.put(key, text);
        }
        Draft current = drafts.get(key);
        if (current == null || !current.text().equals(text)) {
            return false;
        }
        save(key, new Draft("", current.attachments(), current.profileId()));
        return true;
    }

    String lastSubmitted(Key key) {
        return lastSubmitted.getOrDefault(Objects.requireNonNull(key, "key"), "");
    }

    /** 草稿归属；threadId 为空时表示工作区尚未创建 Thread 的新对话草稿。 */
    record Key(String workspaceId, String threadId) {
        Key {
            workspaceId = Objects.toString(workspaceId, "");
            threadId = Objects.toString(threadId, "");
        }
    }

    /**
     * 不可变草稿快照。
     *
     * @param text 用户尚未提交的文本
     * @param attachments 本地附件路径；仅在当前进程使用
     * @param profileId 当前选择的 Profile；可为空
     */
    record Draft(String text, List<Path> attachments, String profileId) {
        Draft {
            text = Objects.toString(text, "");
            attachments = List.copyOf(attachments == null ? List.of() : attachments);
            profileId = Objects.toString(profileId, "");
        }

        static Draft empty() {
            return new Draft("", List.of(), "");
        }

        boolean isEmpty() {
            return text.isEmpty() && attachments.isEmpty() && profileId.isEmpty();
        }
    }
}
