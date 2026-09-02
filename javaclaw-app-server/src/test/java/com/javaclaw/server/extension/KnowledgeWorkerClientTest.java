package com.javaclaw.server.extension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentContent;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.builtin.contracts.KnowledgeWorkerProtocol;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.LengthPrefixedFraming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeWorkerClientTest {
    private static final String DIGEST = "0".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    private final CanonicalJson json = new CanonicalJson();

    @Test
    void successfulExchangeFramesRequestAndReturnsWorkerResult() throws Exception {
        KnowledgeContracts.ExtractionResult expected =
                new KnowledgeContracts.ExtractionResult(DIGEST, "plain-v1", "extracted text");
        StaticProcess process = new StaticProcess(frame(KnowledgeWorkerProtocol.Response.success(expected)));
        KnowledgeWorkerClient client = new KnowledgeWorkerClient(() -> process, Duration.ofSeconds(2));

        KnowledgeContracts.ExtractionResult result = client.extract(attachment(), 100, new CancellationSource());

        assertEquals(expected, result);
        assertTrue(process.destroyed.get());
        ByteArrayInputStream requestBytes = new ByteArrayInputStream(process.request.toByteArray());
        byte[] requestFrame = LengthPrefixedFraming.read(requestBytes, KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
        KnowledgeWorkerProtocol.Request request = json.decode(
                json.parse(new String(requestFrame, StandardCharsets.UTF_8)), KnowledgeWorkerProtocol.Request.class);
        assertEquals("text/plain", request.mediaType());
        assertEquals(100, request.maxCharacters());
        assertArrayEquals(
                "hello".getBytes(StandardCharsets.UTF_8),
                LengthPrefixedFraming.read(requestBytes, KnowledgeWorkerProtocol.MAXIMUM_CONTENT_BYTES));

        client.close();
        client.close();
        assertThrows(IllegalStateException.class, () -> client.extract(attachment(), 100, new CancellationSource()));
    }

    @Test
    void workerRejectionAndLaunchFailureAreReportedWithoutLeakingProcess() throws Exception {
        StaticProcess rejected =
                new StaticProcess(frame(KnowledgeWorkerProtocol.Response.failure("UNSUPPORTED_MEDIA")));
        try (KnowledgeWorkerClient client = new KnowledgeWorkerClient(() -> rejected, Duration.ofSeconds(2))) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> client.extract(attachment(), 100, new CancellationSource()));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("UNSUPPORTED_MEDIA"));
            assertTrue(rejected.destroyed.get());
        }

        try (KnowledgeWorkerClient client = new KnowledgeWorkerClient(
                () -> {
                    throw new IOException("launch failed");
                },
                Duration.ofSeconds(2))) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> client.extract(attachment(), 100, new CancellationSource()));
            assertInstanceOf(IOException.class, failure.getCause());
        }
    }

    @Test
    void cancellationStopsActiveWorkerAndPreservesCancellationFailure() throws Exception {
        BlockingProcess process = new BlockingProcess();
        CancellationSource cancellation = new CancellationSource();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (KnowledgeWorkerClient client = new KnowledgeWorkerClient(() -> process, Duration.ofSeconds(2))) {
            Thread invocation = Thread.ofVirtual().start(() -> extract(client, cancellation, failure));
            assertTrue(process.readStarted.await(2, TimeUnit.SECONDS));

            cancellation.cancel("test cancellation");
            invocation.join(TimeUnit.SECONDS.toMillis(2));

            assertFalse(invocation.isAlive());
            assertInstanceOf(TurnCancelledException.class, failure.get());
            assertTrue(process.wasDestroyed());
        } finally {
            process.release.countDown();
        }
    }

    @Test
    void interruptedCallerStopsWorkerAndTimeoutBoundsAreValidated() throws Exception {
        BlockingProcess process = new BlockingProcess();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (KnowledgeWorkerClient client = new KnowledgeWorkerClient(() -> process, Duration.ofSeconds(2))) {
            Thread invocation = Thread.ofVirtual().start(() -> extract(client, new CancellationSource(), failure));
            assertTrue(process.readStarted.await(2, TimeUnit.SECONDS));

            invocation.interrupt();
            invocation.join(TimeUnit.SECONDS.toMillis(2));

            assertFalse(invocation.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertInstanceOf(InterruptedException.class, failure.get().getCause());
            assertTrue(process.wasDestroyed());
        } finally {
            process.release.countDown();
        }

        KnowledgeWorkerClient.WorkerLauncher launcher = StaticProcess::empty;
        assertThrows(NullPointerException.class, () -> new KnowledgeWorkerClient(launcher, null));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeWorkerClient(launcher, Duration.ofMillis(999)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeWorkerClient(launcher, Duration.ofMinutes(2).plusNanos(1)));
    }

    private void extract(
            KnowledgeWorkerClient client, CancellationSource cancellation, AtomicReference<Throwable> failure) {
        try {
            client.extract(attachment(), 100, cancellation);
        } catch (Throwable thrown) {
            failure.set(thrown);
        }
    }

    private AttachmentContent attachment() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        return new AttachmentContent(new AttachmentMetadata(DIGEST, "text/plain", content.length, NOW), content);
    }

    private byte[] frame(KnowledgeWorkerProtocol.Response response) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LengthPrefixedFraming.write(
                output,
                json.encode(response).json().getBytes(StandardCharsets.UTF_8),
                KnowledgeWorkerProtocol.MAXIMUM_JSON_BYTES);
        return output.toByteArray();
    }

    private static class StaticProcess extends Process {
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private final InputStream response;
        private final AtomicBoolean destroyed = new AtomicBoolean();

        private StaticProcess(byte[] response) {
            this.response = new ByteArrayInputStream(response);
        }

        private static StaticProcess empty() {
            return new StaticProcess(new byte[0]);
        }

        final boolean wasDestroyed() {
            return destroyed.get();
        }

        @Override
        public OutputStream getOutputStream() {
            return request;
        }

        @Override
        public InputStream getInputStream() {
            return response;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed.set(true);
        }

        @Override
        public Process destroyForcibly() {
            destroyed.set(true);
            return this;
        }

        @Override
        public boolean isAlive() {
            return !destroyed.get();
        }
    }

    private static final class BlockingProcess extends StaticProcess {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingProcess() {
            super(new byte[0]);
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() {
                    readStarted.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    }
                    return -1;
                }
            };
        }

        @Override
        public Process destroyForcibly() {
            release.countDown();
            return super.destroyForcibly();
        }
    }
}
