package com.javaclaw.server.transport;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.RpcMethods;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedJsonlSenderTest {
    @Test
    void slowClientDropsDeltasEmitsOneResyncAndNeverBlocksPublisher() throws Exception {
        BlockingWriter output = new BlockingWriter();
        AtomicBoolean aborted = new AtomicBoolean();
        long started = System.nanoTime();
        try (BoundedJsonlSender sender = new BoundedJsonlSender(output, new JsonRpcCodec(), () -> aborted.set(true))) {
            for (int sequence = 1; sequence <= BoundedJsonlSender.MAX_NOTIFICATIONS + 200; sequence++) {
                ObjectNode params = JsonNodeFactory.instance.objectNode();
                params.put("threadId", "thr_slow");
                params.put("itemId", "itm_slow");
                params.put("deltaSequence", sequence);
                params.put("delta", "x");
                sender.sendNotification(new JsonRpcNotification("2.0", RpcMethods.ITEM_DELTA, params));
            }
            assertTrue(
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000,
                    "publishing deltas must not wait for the transport writer");
            assertFalse(aborted.get(), "delta pressure must resync before closing the client");
            output.release();
        }
        String frames = output.value();
        assertTrue(frames.contains("\"method\":\"resyncRequired\""));
        assertTrue(
                frames.indexOf("\"method\":\"resyncRequired\"") == frames.lastIndexOf("\"method\":\"resyncRequired\""));
    }

    private static final class BlockingWriter extends Writer {
        private final CountDownLatch release = new CountDownLatch(1);
        private final StringBuilder output = new StringBuilder();

        @Override
        public void write(char[] value, int offset, int length) throws IOException {
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test writer was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            synchronized (output) {
                output.append(value, offset, length);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {
            release.countDown();
        }

        void release() {
            release.countDown();
        }

        String value() {
            synchronized (output) {
                return output.toString();
            }
        }
    }
}
