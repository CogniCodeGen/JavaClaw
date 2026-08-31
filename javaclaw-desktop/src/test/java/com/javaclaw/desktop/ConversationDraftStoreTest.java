package com.javaclaw.desktop;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationDraftStoreTest {
    @Test
    void keepsNewEditsWhenAnOlderSubmissionCompletes() {
        var store = new ConversationDraftStore();
        var key = new ConversationDraftStore.Key("workspace", "thread");
        var submitted = new ConversationDraftStore.Draft("旧草稿", List.of(Path.of("a.md")), "profile_chat");
        var edited = new ConversationDraftStore.Draft("发送期间的新草稿", List.of(Path.of("b.md")), "profile_chat");
        store.save(key, submitted);
        store.save(key, edited);

        assertFalse(store.accepted(key, submitted));
        assertEquals(edited, store.read(key));
        assertEquals("旧草稿", store.lastSubmitted(key));
    }

    @Test
    void clearsOnlyTheAcceptedThreadDraftAndRemembersInputHistory() {
        var store = new ConversationDraftStore();
        var first = new ConversationDraftStore.Key("workspace", "first");
        var second = new ConversationDraftStore.Key("workspace", "second");
        var submitted = new ConversationDraftStore.Draft("已发送", List.of(), "profile_chat");
        store.save(first, submitted);
        store.save(second, new ConversationDraftStore.Draft("另一个会话", List.of(), "profile_plan"));

        assertTrue(store.accepted(first, submitted));
        assertEquals(ConversationDraftStore.Draft.empty(), store.read(first));
        assertEquals("另一个会话", store.read(second).text());
        assertEquals("已发送", store.lastSubmitted(first));
    }

    @Test
    void steeringClearsOnlyMatchingTextAndKeepsAttachments() {
        var store = new ConversationDraftStore();
        var key = new ConversationDraftStore.Key("workspace", "thread");
        Path attachment = Path.of("diagram.png");
        store.save(key, new ConversationDraftStore.Draft("继续", List.of(attachment), "profile"));

        assertTrue(store.acceptedText(key, "继续"));
        assertEquals(new ConversationDraftStore.Draft("", List.of(attachment), "profile"), store.read(key));
    }
}
