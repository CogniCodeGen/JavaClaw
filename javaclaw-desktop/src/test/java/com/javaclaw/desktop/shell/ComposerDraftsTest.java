package com.javaclaw.desktop.shell;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComposerDraftsTest {
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("87e26312-d9c3-491b-baa7-91613bfc2dd0");
    private static final ThreadId FIRST = ThreadId.parse("a6d178a4-09b5-4bdf-bc4b-acf87f120be8");
    private static final ThreadId SECOND = ThreadId.parse("19920aa7-818f-4ec7-ae68-3f3f5a213bc4");

    @Test
    void 首次配置完成创建对话后带回原输入草稿() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.edited("配置之前输入的问题");
        assertEquals("配置之前输入的问题", drafts.bind(scope(FIRST), "配置之前输入的问题"));
        var submitted = drafts.submission("配置之前输入的问题");
        assertTrue(drafts.acknowledged(submitted));
    }

    @Test
    void 迟到成功回执不清空用户后来输入的内容() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.bind(scope(FIRST), "");
        drafts.edited("原问题");
        var submitted = drafts.submission("原问题");
        drafts.edited("新的问题");
        assertFalse(drafts.acknowledged(submitted));
        assertEquals("新的问题", drafts.submission("新的问题").text());
    }

    @Test
    void 切换对话后只清除已被接受的原对话草稿() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.bind(scope(FIRST), "");
        drafts.edited("已发送的问题");
        var submitted = drafts.submission("已发送的问题");
        assertEquals("", drafts.bind(scope(SECOND), "已发送的问题"));
        drafts.edited("另一对话草稿");
        assertFalse(drafts.acknowledged(submitted));
        assertEquals("", drafts.bind(scope(FIRST), "另一对话草稿"));
        assertEquals("另一对话草稿", drafts.bind(scope(SECOND), ""));
    }

    @Test
    void 未确认的发送在切换回来后仍复用相同幂等身份() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.bind(scope(FIRST), "");
        drafts.edited("回执丢失的输入");
        var submission = drafts.submission("回执丢失的输入");
        var options = drafts.options(submission, ExecutionOverrides.empty());
        drafts.bind(scope(SECOND), "回执丢失的输入");
        drafts.edited("另一个草稿");
        drafts.bind(scope(FIRST), "另一个草稿");
        assertEquals(options, drafts.options(drafts.submission("回执丢失的输入"), ExecutionOverrides.empty()));
        assertTrue(drafts.acknowledged(submission));
        drafts.edited("回执丢失的输入");
        assertNotEquals(options, drafts.options(drafts.submission("回执丢失的输入"), ExecutionOverrides.empty()));
    }

    @Test
    void 改变执行配置或编辑后重新发送创建新身份且旧回执不清新尝试() {
        ComposerDrafts drafts = new ComposerDrafts();
        drafts.bind(scope(FIRST), "");
        drafts.edited("输入");
        var original = drafts.submission("输入");
        var options = drafts.options(original, ExecutionOverrides.empty());
        var reasoning = new ExecutionOverrides(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(ReasoningPreference.HIGH));
        assertNotEquals(options, drafts.options(original, reasoning));
        drafts.edited("输入");
        var current = drafts.submission("输入");
        var changed = drafts.options(current, ExecutionOverrides.empty());
        assertNotEquals(options, changed);
        assertFalse(drafts.acknowledged(original));
        assertEquals(changed, drafts.options(current, ExecutionOverrides.empty()));
    }

    private static ComposerDrafts.Scope scope(ThreadId thread) {
        return new ComposerDrafts.Scope(Optional.of(WORKSPACE), Optional.of(thread));
    }
}
