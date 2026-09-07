package com.javaclaw.server.coding;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.DependencyEvidence;
import com.javaclaw.server.persistence.CodingNetworkGrantRepository;
import com.javaclaw.server.persistence.DependencyEvidenceRepository;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.security.CommandNetworkGrant;
import com.javaclaw.server.security.CommandProxyBroker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingPreparationLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void finishCancelsTheRealGrantAndWaitsUntilTheLastEvidenceWrite() throws Exception {
        try (var fixture = new CodingLifecycleFixture(directory, new ControlledCodingSandbox());
                var tasks = Executors.newVirtualThreadPerTaskExecutor();
                var broker = new CommandProxyBroker(fixture.base.clock);
                var scope = new CodingCallScope(
                        DependencyEvidenceTest.dependencies(fixture.base).authority())) {
            var input = new CodingContracts.DependenciesPrepare(CodingContracts.PackageManager.NPM, ".", List.of());
            var invocation = fixture.invocation("prepare-owner", "dependencies_prepare", input);
            var evidence = evidence(invocation.id());
            var repository = new DependencyEvidenceRepository(fixture.base.database, fixture.base.json);
            repository.record(fixture.base.workspace.id(), evidence);
            var grants = new CodingNetworkGrantRepository(fixture.base.database, fixture.base.json, fixture.base.clock);
            var gates = new Gates();
            var run = tasks.submit(() -> scope.run(
                    invocation, bound -> runUntilCancelled(fixture, broker, grants, repository, bound, gates)));
            assertTrue(gates.started.await(5, TimeUnit.SECONDS));
            var finish = tasks.submit(() -> {
                scope.finish(fixture.base.turn.id());
                return null;
            });
            try {
                assertTrue(gates.evidencePending.await(5, TimeUnit.SECONDS));
                assertFalse(gates.lease.get().active());
                assertThrows(IOException.class, () -> connect(gates.endpoint.get()));
                assertThrows(TimeoutException.class, () -> finish.get(100, TimeUnit.MILLISECONDS));
                var late = fixture.invocation("late-owner", "dependencies_prepare", input);
                assertThrows(IllegalStateException.class, () -> scope.run(late, ignored -> null));
            } finally {
                gates.writeEvidence.countDown();
            }
            assertThrows(ExecutionException.class, () -> run.get(5, TimeUnit.SECONDS));
            finish.get(5, TimeUnit.SECONDS);
            assertTrue(repository
                    .read(fixture.base.workspace.id(), invocation.id())
                    .after()
                    .isPresent());
            assertEquals("REVOKED", grantState(fixture, "grant-prepare-owner"));
        }
    }

    @Test
    void terminalTurnRejectsLateEvidenceEvenIfAnOperationNeverReachedFinished() throws Exception {
        try (var fixture = new CodingLifecycleFixture(directory, new ControlledCodingSandbox())) {
            var input = new CodingContracts.DependenciesPrepare(CodingContracts.PackageManager.NPM, ".", List.of());
            var invocation = fixture.invocation("late-evidence", "dependencies_prepare", input);
            var repository = new DependencyEvidenceRepository(fixture.base.database, fixture.base.json);
            repository.record(fixture.base.workspace.id(), evidence(invocation.id()));
            fixture.base.journal.transition(
                    fixture.base.turn.id(), TurnStatus.RUNNING, TurnStatus.CANCELLED, Optional.empty());
            assertThrows(
                    SecurityException.class,
                    () -> repository.record(fixture.base.workspace.id(), evidence(invocation.id())));
        }
    }

    private static CodingToolResult runUntilCancelled(
            CodingLifecycleFixture fixture,
            CommandProxyBroker broker,
            CodingNetworkGrantRepository grants,
            DependencyEvidenceRepository repository,
            CodingInvocation invocation,
            Gates gates)
            throws Exception {
        var grant = new CommandNetworkGrant(
                "grant-" + invocation.id(),
                invocation.turn().id(),
                invocation.id(),
                "a".repeat(64),
                new NetworkPermission(Set.of("repo.example"), Set.of(443), true),
                new CommandNetworkGrant.Limits(1024, 1, Duration.ofSeconds(5)),
                fixture.base.clock.instant().plusSeconds(30));
        grants.open(grant);
        try (var lease = broker.open(grant, invocation.cancellation(), () -> {})) {
            gates.endpoint.set(lease.endpoint());
            gates.lease.set(lease);
            gates.started.countDown();
            while (!invocation.cancellation().isCancelled()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            invocation.cancellation().throwIfCancelled();
            throw new AssertionError("scope cancellation was not observed");
        } finally {
            grants.close(grant.id(), 0);
            gates.evidencePending.countDown();
            if (!gates.writeEvidence.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release evidence writer");
            }
            var initial = evidence(invocation.id());
            repository.record(
                    invocation.workspaceId(),
                    new DependencyEvidence(
                            invocation.id(),
                            initial.manager(),
                            initial.before(),
                            Optional.of(initial.before()),
                            List.of(),
                            List.of(),
                            false));
        }
    }

    private static String grantState(CodingLifecycleFixture fixture, String id) throws Exception {
        return new H2Transactions(fixture.base.database).execute(connection -> {
            try (var statement =
                    connection.prepareStatement("SELECT STATE FROM CORE.CODING_NETWORK_GRANT WHERE ID=?")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    return rows.getString(1);
                }
            }
        });
    }

    private static DependencyEvidence evidence(String id) {
        return new DependencyEvidence(
                id,
                CodingContracts.PackageManager.NPM,
                new DependencyEvidence.Inventory(List.of(), 0, false, List.of("cancelled")),
                Optional.empty(),
                List.of(),
                List.of(),
                false);
    }

    private static void connect(InetSocketAddress endpoint) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(endpoint, 200);
        }
    }

    private static final class Gates {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch evidencePending = new CountDownLatch(1);
        private final CountDownLatch writeEvidence = new CountDownLatch(1);
        private final AtomicReference<InetSocketAddress> endpoint = new AtomicReference<>();
        private final AtomicReference<com.javaclaw.server.security.CommandProxyLease> lease = new AtomicReference<>();
    }
}
