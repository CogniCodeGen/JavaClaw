package com.javaclaw.browser.worker;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrowserRequestHandlerTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void snapshotMapsStrongContractAndPassesSensitiveStateOutOfJson() {
        BrowserWorkerProtocol.SnapshotTask task = task("https://docs.example.com");
        SiteContracts.PageSnapshot expected = new SiteContracts.PageSnapshot(
                task.uri(), "Docs", "Readable content", Instant.parse("2026-09-01T00:00:00Z"));
        FixedSession session = new FixedSession(expected);
        byte[] storageState = new byte[] {1, 2, 3};
        try (BrowserRequestHandler handler = new BrowserRequestHandler(session, json)) {
            BrowserWorkerProtocol.WorkerMessage response;
            try (BrowserWorkerReply reply = handler.handle(
                    new BrowserWorkerProtocol.Command(2, 1, "snapshot", json.encode(task), storageState.length),
                    storageState,
                    waitingControl(),
                    ignored -> {})) {
                response = reply.message();
            }

            SiteContracts.PageSnapshot actual =
                    json.decode(response.payload().orElseThrow(), SiteContracts.PageSnapshot.class);
            assertEquals(expected, actual);
            assertEquals(3, session.stateBytes);
        }
    }

    @Test
    void workerFailureReturnsOnlyStableErrorCode() {
        try (BrowserRequestHandler handler = new BrowserRequestHandler(new FailingSession(), json)) {
            BrowserWorkerProtocol.SnapshotTask task = task("https://docs.example.com/private?token=secret");
            BrowserWorkerProtocol.WorkerMessage response;
            try (BrowserWorkerReply reply = handler.handle(
                    new BrowserWorkerProtocol.Command(2, 2, "snapshot", json.encode(task), 0),
                    new byte[0],
                    waitingControl(),
                    ignored -> {})) {
                response = reply.message();
            }

            assertEquals("BROWSER_REQUEST_FAILED", response.error().orElseThrow());
        }
    }

    @Test
    void constructorRejectsNullDependencies() {
        assertThrows(NullPointerException.class, () -> new BrowserRequestHandler(null, json));
        assertThrows(NullPointerException.class, () -> new BrowserRequestHandler(new FailingSession(), null));
    }

    private static BrowserWorkerProtocol.SnapshotTask task(String uri) {
        return new BrowserWorkerProtocol.SnapshotTask(
                URI.create(uri), Set.of(URI.create("https://docs.example.com")), 1_000, Duration.ofSeconds(5));
    }

    private static BrowserLoginControl waitingControl() {
        return new BrowserLoginControl() {
            @Override
            public Decision decision() {
                return Decision.WAIT;
            }

            @Override
            public void close() {}
        };
    }

    private static final class FixedSession implements BrowserSession {
        private final SiteContracts.PageSnapshot result;
        private int stateBytes;

        private FixedSession(SiteContracts.PageSnapshot result) {
            this.result = result;
        }

        @Override
        public SiteContracts.PageSnapshot snapshot(BrowserWorkerProtocol.SnapshotTask task, byte[] storageState) {
            stateBytes = storageState.length;
            return result;
        }

        @Override
        public byte[] login(
                BrowserWorkerProtocol.LoginTask task,
                byte[] storageState,
                BrowserLoginControl control,
                Runnable ready) {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI oauth(BrowserWorkerProtocol.OAuthTask task, BrowserLoginControl control, Runnable ready) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }

    private static final class FailingSession implements BrowserSession {
        @Override
        public SiteContracts.PageSnapshot snapshot(BrowserWorkerProtocol.SnapshotTask task, byte[] storageState) {
            throw new IllegalStateException("token=secret at " + task.uri());
        }

        @Override
        public byte[] login(
                BrowserWorkerProtocol.LoginTask task,
                byte[] storageState,
                BrowserLoginControl control,
                Runnable ready) {
            throw new IllegalStateException("secret");
        }

        @Override
        public URI oauth(BrowserWorkerProtocol.OAuthTask task, BrowserLoginControl control, Runnable ready) {
            throw new IllegalStateException("secret");
        }

        @Override
        public void close() {}
    }
}
