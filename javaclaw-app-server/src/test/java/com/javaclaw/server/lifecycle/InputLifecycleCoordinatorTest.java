package com.javaclaw.server.lifecycle;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.InputRequest;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.InputRequestService;
import com.javaclaw.server.persistence.LifecycleLeaseRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InputLifecycleCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void 等待输入在重启后重建Lease并在决议后立即释放() {
        MutableClock clock = new MutableClock(NOW);
        CanonicalJson json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        CoreCommandService core = new CoreCommandService(database, json, clock);
        InputRequestService inputs = new InputRequestService(database, json, clock);
        LifecycleCoordinator lifecycle = lifecycle(database, clock);
        InputRequest request = request(core, database, json, clock);

        try (InputLifecycleCoordinator coordinator = new InputLifecycleCoordinator(inputs, lifecycle, clock)) {
            inputs.open(request);
            inputs.open(request);
            assertEquals(1, lifecycle.status().activeLeases());
        }
        assertEquals(0, lifecycle.status().activeLeases());

        LifecycleCoordinator restartedLifecycle = lifecycle(database, clock);
        InputRequestService restartedInputs = new InputRequestService(database, json, clock);
        try (InputLifecycleCoordinator coordinator =
                new InputLifecycleCoordinator(restartedInputs, restartedLifecycle, clock)) {
            assertEquals(1, restartedLifecycle.status().activeLeases());
            InputJobRpcContracts.InputResolvePayload payload =
                    new InputJobRpcContracts.InputResolvePayload(request.id(), json.parse("{\"choice\":\"continue\"}"));
            CommandIdentity identity = CommandIdentity.from(
                    "turn/input/resolve", new WriteCommand("resolve-input", 1, json.encode(payload)), json);

            restartedInputs.resolve(identity, payload);

            assertEquals(0, restartedLifecycle.status().activeLeases());
        } finally {
            restartedLifecycle.close();
            lifecycle.close();
        }
    }

    private InputRequest request(CoreCommandService core, H2Database database, CanonicalJson json, Clock clock) {
        Workspace workspace = core.createWorkspace(
                identity("workspace/create", "workspace", 0, json, "workspace"),
                "Input lifecycle",
                temporaryDirectory.resolve("workspace"));
        ConversationThread thread = core.createThread(
                identity("thread/create", "thread", 0, json, "thread"),
                workspace.id(),
                Optional.empty(),
                com.javaclaw.api.ThreadExecutionIntent.WORKSPACE,
                "Input lifecycle");
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, "input", List.of(), Optional.empty());
        AgentTurn turn = core.startTurn(
                identity("turn/start", "turn", 0, json, "turn"),
                TurnContractFixtures.request(thread.id(), budget(), message));
        H2TurnJournal journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        journal.transition(turn.id(), TurnStatus.QUEUED, TurnStatus.RUNNING, Optional.empty());
        return new InputRequest(
                "workflow-input",
                turn.id(),
                "workflow",
                "请选择下一步",
                json.parse("{\"properties\":{\"choice\":{\"type\":\"string\"}},\"type\":\"object\"}"),
                NOW,
                NOW.plus(Duration.ofMinutes(5)));
    }

    private static LifecycleCoordinator lifecycle(H2Database database, Clock clock) {
        return new LifecycleCoordinator(new LifecycleLeaseRepository(database, clock), Duration.ofSeconds(60));
    }

    private static CommandIdentity identity(
            String method, String key, long revision, CanonicalJson json, String value) {
        return CommandIdentity.from(method, new WriteCommand(key, revision, json.encode(Map.of("value", value))), json);
    }

    private static TurnBudget budget() {
        return new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1));
    }

    private static final class MutableClock extends Clock {
        private final Instant current;

        private MutableClock(Instant current) {
            this.current = current;
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
