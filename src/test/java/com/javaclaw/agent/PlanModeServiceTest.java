package com.javaclaw.agent;

import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanModeServiceTest {

    @Test
    void 最终方案先于Critic发布且评审失败只降级为提示() {
        List<ConversationEvent> events = new ArrayList<>();
        AtomicBoolean draftVisibleToCritic = new AtomicBoolean();
        ConversationCallbacks callbacks = new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
                events.add(event);
            }

            @Override
            public void onTerminal(ConversationOutcome outcome) {
                throw new AssertionError("最终化辅助方法不应自行发送终态");
            }
        };

        assertDoesNotThrow(() -> PlanModeService.publishFinalDraftAndReview(
                "最终方案正文\n[PLAN_COMPLETE]", callbacks, true, () -> false, () -> {
                    draftVisibleToCritic.set(events.stream().anyMatch(event ->
                            event instanceof ConversationEvent.Custom custom
                                    && "plan_final".equals(custom.kind())));
                    throw new IllegalStateException("critic offline");
                }));

        assertTrue(draftVisibleToCritic.get());
        ConversationEvent.Custom finalEvent =
                assertInstanceOf(ConversationEvent.Custom.class, events.getFirst());
        assertEquals("plan_final", finalEvent.kind());
        assertEquals("最终方案正文", finalEvent.payload());
        assertTrue(events.stream().anyMatch(event ->
                event instanceof ConversationEvent.Hint hint
                        && hint.text().contains("已保留协调者最终方案")));
    }

    @Test
    void 已取消时不发布方案也不启动Critic() {
        List<ConversationEvent> events = new ArrayList<>();
        AtomicBoolean criticCalled = new AtomicBoolean();
        ConversationCallbacks callbacks = new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
                events.add(event);
            }

            @Override
            public void onTerminal(ConversationOutcome outcome) {
            }
        };

        PlanModeService.publishFinalDraftAndReview(
                "不会发布", callbacks, true, () -> true,
                () -> criticCalled.set(true));

        assertTrue(events.isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(criticCalled.get());
    }

    @Test
    void 档位解析只接受唯一枚举() {
        assertEquals(com.javaclaw.api.conversation.PlanProfile.QUICK,
                PlanModeService.parsePlanProfile("```quick```").orElseThrow());
        assertEquals(com.javaclaw.api.conversation.PlanProfile.STANDARD,
                PlanModeService.parsePlanProfile("建议选择 standard 档位").orElseThrow());
        assertEquals(com.javaclaw.api.conversation.PlanProfile.DEEP,
                PlanModeService.parsePlanProfile(" DEEP ").orElseThrow());
        assertTrue(PlanModeService.parsePlanProfile("STANDARD, not DEEP").isEmpty());
        assertTrue(PlanModeService.parsePlanProfile("AUTO").isEmpty());
        assertTrue(PlanModeService.parsePlanProfile(" ").isEmpty());
    }
}
