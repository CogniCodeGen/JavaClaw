package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.api.TurnId;
import com.javaclaw.builtin.contracts.WorkflowContracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowInputIdentityTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String PRODUCER = "javaclaw.workflow";

    @Test
    void continue要求Checkpoint的Request和Turn都匹配权威InputRequest() {
        TurnId turn = TurnId.random();
        WorkflowContracts.Checkpoint checkpoint = checkpoint("input-one", turn);
        InputRequestRecord authoritative = resolved("input-one", turn);

        assertDoesNotThrow(() -> WorkflowExtension.requireInputIdentity(checkpoint, authoritative));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowExtension.requireInputIdentity(checkpoint("input-other", turn), authoritative));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowExtension.requireInputIdentity(checkpoint("input-one", TurnId.random()), authoritative));
    }

    @Test
    void expiredInput即使身份匹配也不能继续Workflow() {
        TurnId turn = TurnId.random();
        InputRequest request = request("input-expired", turn);
        InputRequestRecord expired = new InputRequestRecord(
                request,
                InputRequestState.EXPIRED,
                2,
                Optional.empty(),
                Optional.of("输入请求已超过有效期"),
                NOW.plusSeconds(10));

        assertDoesNotThrow(() -> WorkflowExtension.requireInputIdentity(checkpoint(request.id(), turn), expired));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowExtension.requireResolved(expired, PRODUCER, Optional.of(turn)));
    }

    private static WorkflowContracts.Checkpoint checkpoint(String requestId, TurnId turnId) {
        return new WorkflowContracts.Checkpoint(
                "input-node",
                Map.of(),
                Map.of("input-node", 1),
                Optional.of(requestId),
                Optional.of(turnId),
                List.of());
    }

    private static InputRequestRecord resolved(String requestId, TurnId turnId) {
        InputRequest request = request(requestId, turnId);
        return new InputRequestRecord(
                request,
                InputRequestState.RESOLVED,
                2,
                Optional.of(new CanonicalPayload("{\"choice\":\"continue\"}")),
                Optional.empty(),
                NOW.plusSeconds(1));
    }

    private static InputRequest request(String requestId, TurnId turnId) {
        return new InputRequest(
                requestId,
                turnId,
                PRODUCER,
                "请选择下一步",
                new CanonicalPayload("{\"type\":\"object\"}"),
                NOW,
                NOW.plus(Duration.ofMinutes(1)));
    }
}
