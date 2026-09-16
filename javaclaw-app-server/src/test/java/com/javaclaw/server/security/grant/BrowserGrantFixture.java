package com.javaclaw.server.security.grant;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;

/** 本地 H2 夹具；通过真实 Core 命令创建身份，不执行模型或网络。 */
final class BrowserGrantFixture {
    final CanonicalJson json = new CanonicalJson();
    final MutableClock clock = new MutableClock();
    final H2Database database;
    final CoreCommandService core;
    final WorkspaceId workspace;
    final ThreadId thread;
    final BrowserGrantService service;

    BrowserGrantFixture(Path directory) {
        database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, clock);
        workspace = core.createWorkspace(
                        identity("workspace/create", 0, "fixture"), "Browser", directory.resolve("workspace"))
                .id();
        thread = thread();
        service = new BrowserGrantService(database, json, clock);
    }

    ThreadId thread() {
        return core.createThread(
                        identity("thread/create", 0, "fixture"),
                        workspace,
                        Optional.empty(),
                        ThreadExecutionIntent.WORKSPACE,
                        "Browser")
                .id();
    }

    TurnId turn() {
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, "浏览", List.of(), Optional.empty());
        return core.startTurn(
                        identity("turn/start", 0, "fixture"),
                        TurnContractFixtures.request(
                                thread, new TurnBudget(1000, 1000, 2, 0, Duration.ofMinutes(1)), message))
                .id();
    }

    void finish(TurnId turn) {
        H2TurnJournal journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, clock);
        journal.transition(turn, TurnStatus.QUEUED, TurnStatus.CANCELLED, Optional.empty());
    }

    CommandIdentity identity(String method, long revision, Object payload) {
        return CommandIdentity.from(
                method,
                new WriteCommand(UUID.randomUUID().toString(), revision, json.encode(Map.of("value", payload))),
                json);
    }

    static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-09-14T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
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
            return instant;
        }
    }
}
