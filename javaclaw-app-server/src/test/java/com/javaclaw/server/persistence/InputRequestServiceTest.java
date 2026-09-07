package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.api.InputRequestState;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputRequestServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private MutableClock clock;
    private CanonicalJson json;
    private CoreCommandService core;
    private H2TurnJournal journal;
    private InputRequestService inputs;

    @BeforeEach
    void initializeDataV6() {
        clock = new MutableClock(NOW);
        json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        inputs = new InputRequestService(database, json, clock);
    }

    @Test
    void input请求Item与Turn状态原子往返并支持幂等决议() {
        AgentTurn turn = runningTurn("round-trip");
        InputRequest request = request("input-one", turn, NOW.plusSeconds(60));
        InputJobRpcContracts.InputResolvePayload payload =
                new InputJobRpcContracts.InputResolvePayload(request.id(), json.parse("{\"choice\":\"continue\"}"));
        CommandIdentity identity = identity("turn/input/resolve", "input-key", 1, payload);

        InputRequestRecord opened = inputs.open(request);
        InputRequestRecord openedAgain = inputs.open(request);
        InputRequestRecord resolved = inputs.resolve(identity, payload);
        InputRequestRecord retried = inputs.resolve(identity, payload);

        assertEquals(opened, openedAgain);
        assertEquals(InputRequestState.PENDING, opened.state());
        assertEquals(InputRequestState.RESOLVED, resolved.state());
        assertEquals(resolved, retried);
        assertEquals(TurnStatus.RUNNING, core.findTurn(turn.id()).orElseThrow().status());
        assertEquals(List.of(resolved), inputs.list(Optional.of(turn.id()), true));
        assertTrue(inputs.list(Optional.of(turn.id()), false).isEmpty());
        assertEquals(
                2,
                core.listItems(turn.threadId()).stream()
                        .filter(item -> item.kind().equals("input"))
                        .count());
    }

    @Test
    void input决议拒绝错误revision与幂等键复用且不破坏草稿() {
        AgentTurn turn = runningTurn("conflict");
        InputRequest request = request("input-conflict", turn, NOW.plusSeconds(60));
        inputs.open(request);
        InputJobRpcContracts.InputResolvePayload payload =
                new InputJobRpcContracts.InputResolvePayload(request.id(), json.parse("{\"value\":1}"));

        assertThrows(
                PersistenceException.class,
                () -> inputs.resolve(identity("turn/input/resolve", "bad-revision", 2, payload), payload));
        InputJobRpcContracts.InputResolvePayload secret = new InputJobRpcContracts.InputResolvePayload(
                request.id(), json.parse("{\"password\":\"must-not-enter-input\"}"));
        assertThrows(
                PersistenceException.class,
                () -> inputs.resolve(identity("turn/input/resolve", "secret", 1, secret), secret));
        InputRequestRecord resolved = inputs.resolve(identity("turn/input/resolve", "stable-key", 1, payload), payload);
        InputJobRpcContracts.InputResolvePayload changed =
                new InputJobRpcContracts.InputResolvePayload(request.id(), json.parse("{\"value\":2}"));
        assertThrows(
                PersistenceException.class,
                () -> inputs.resolve(identity("turn/input/resolve", "stable-key", 1, changed), changed));
        assertEquals(resolved, inputs.find(request.id()).orElseThrow());
    }

    @Test
    void 过期请求FailClosed并在重启后保持终态() {
        AgentTurn turn = runningTurn("expired");
        InputRequest request = request("input-expired", turn, NOW.plusSeconds(5));
        inputs.open(request);
        clock.advance(Duration.ofSeconds(10));
        InputJobRpcContracts.InputResolvePayload payload =
                new InputJobRpcContracts.InputResolvePayload(request.id(), json.parse("{\"late\":true}"));

        InputRequestRecord expired = inputs.resolve(identity("turn/input/resolve", "expired-key", 1, payload), payload);

        assertEquals(InputRequestState.EXPIRED, expired.state());
        assertTrue(expired.response().isEmpty());
        assertEquals(TurnStatus.RUNNING, core.findTurn(turn.id()).orElseThrow().status());
        assertThrows(PersistenceException.class, () -> inputs.open(request("late-open", turn, NOW.plusSeconds(1))));
    }

    @Test
    void expireDue只关闭到期请求并通知提交后监听器() {
        AgentTurn turn = runningTurn("sweep");
        InputRequest request = request("input-sweep", turn, NOW.plusSeconds(5));
        java.util.ArrayList<InputRequestRecord> terminal = new java.util.ArrayList<>();
        inputs.onChanged(terminal::add);
        inputs.open(request);
        clock.advance(Duration.ofSeconds(10));

        assertEquals(1, inputs.expireDue());
        assertEquals(0, inputs.expireDue());
        assertEquals(
                List.of(InputRequestState.PENDING, InputRequestState.EXPIRED),
                terminal.stream().map(InputRequestRecord::state).toList());
    }

    private AgentTurn runningTurn(String suffix) {
        Workspace workspace = core.listWorkspaces().stream().findFirst().orElseGet(() -> {
            CoreRpcContracts.WorkspaceCreatePayload payload =
                    new CoreRpcContracts.WorkspaceCreatePayload("输入测试", temporaryDirectory.resolve("workspace"));
            return core.createWorkspace(
                    identity("workspace/create", "workspace", 0, payload), payload.name(), payload.root());
        });
        CoreRpcContracts.ThreadCreatePayload threadPayload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), com.javaclaw.api.ThreadExecutionIntent.WORKSPACE, suffix);
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread-" + suffix, 0, threadPayload),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                suffix);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        CoreRpcContracts.TurnStartPayload turnPayload =
                com.javaclaw.server.TurnContractFixtures.payload(thread.id(), suffix);
        AgentTurn turn = core.startTurn(
                identity("turn/start", "turn-" + suffix, 0, turnPayload),
                com.javaclaw.server.TurnContractFixtures.request(thread.id(), budget(), message));
        journal.transition(turn.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        return core.findTurn(turn.id()).orElseThrow();
    }

    private InputRequest request(String id, AgentTurn turn, Instant expiresAt) {
        return new InputRequest(
                id,
                turn.id(),
                "workflow",
                "请选择下一步",
                json.parse("{\"properties\":{\"choice\":{\"type\":\"string\"}},\"type\":\"object\"}"),
                NOW,
                expiresAt);
    }

    private CommandIdentity identity(String method, String key, long revision, Object payload) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(payload)), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
