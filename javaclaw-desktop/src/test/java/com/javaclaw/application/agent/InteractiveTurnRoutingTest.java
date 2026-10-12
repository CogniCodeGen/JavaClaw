package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InteractiveTurnRoutingTest {
    @Test
    void newTasksAreIndependentOfApplicationNamesAndLanguage() {
        for (String input : List.of("打开飞书查看联系人", "Open the calendar and inspect tomorrow",
                "Continuer avec un autre document", "继续打开另一个应用")) {
            assertEquals(InteractiveTurnRouting.Intent.NEW_TASK,
                    InteractiveTurnRouting.intent(RunState.PAUSED, request(input), request("old task")));
        }
    }

    @Test
    void onlyWholeContinuationCommandsResumeThePausedTask() {
        for (String input : List.of("继续", "重试本轮。", "Continue!", "resume")) {
            assertEquals(InteractiveTurnRouting.Intent.CONTINUE,
                    InteractiveTurnRouting.intent(RunState.PAUSED, request(input), request("old task")));
        }
    }

    @Test
    void anAttachmentCannotDisappearIntoAContinuationCommand() {
        RunRequest plain = request("继续");
        RunRequest attachment = new RunRequest(plain.agent(), plain.profile(), plain.source(), plain.scope(),
                List.of(InputBlock.text("继续"), new InputBlock("core.file",
                        JsonNodeFactory.instance.objectNode().put("uri", "project-file:new.txt"))),
                plain.linkage(), plain.permissionCeiling(), plain.budget(), null, plain.attributes());
        assertEquals(InteractiveTurnRouting.Intent.NEW_TASK,
                InteractiveTurnRouting.intent(RunState.PAUSED, attachment, request("old task")));
    }

    @Test
    void repliesAndExactRetriesPreserveTheOriginalRun() {
        assertEquals(InteractiveTurnRouting.Intent.CONTINUE,
                InteractiveTurnRouting.intent(RunState.WAITING_INPUT, request("tomorrow"), request("Which date?")));
        assertEquals(InteractiveTurnRouting.Intent.CONTINUE,
                InteractiveTurnRouting.intent(RunState.PAUSED, request("same task"), request("same task")));
    }

    private static RunRequest request(String input) {
        return RunRequest.builder().agent(AgentDefinitionRef.latest("system.default"))
                .profile(RunProfileRef.latest("chat")).source(InvocationSource.chat())
                .scope(new RunScope("workspace", "user", "session"))
                .input(InputBlock.text(input)).build();
    }
}
