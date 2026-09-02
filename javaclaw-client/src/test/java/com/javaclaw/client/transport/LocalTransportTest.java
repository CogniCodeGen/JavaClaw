package com.javaclaw.client.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.StreamRpcConnection;
import com.javaclaw.protocol.TransportKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTransportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void stdioValidatesCommandAndStartsWithoutShell() throws Exception {
        assertThrows(NullPointerException.class, () -> new StdioProcessTransport(null));
        assertThrows(IllegalArgumentException.class, () -> new StdioProcessTransport(List.of()));
        assertThrows(
                IllegalArgumentException.class, () -> new StdioProcessTransport(java.util.Arrays.asList("java", null)));
        assertThrows(IllegalArgumentException.class, () -> new StdioProcessTransport(List.of("java", "")));

        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        StdioProcessTransport inherited = new StdioProcessTransport(List.of(java.toString(), "-version"));
        assertEquals(TransportKind.STDIO, inherited.kind());
        inherited.connect().close();

        StdioProcessTransport located =
                new StdioProcessTransport(List.of(java.toString(), "-version"), Optional.of(temporaryDirectory));
        located.connect().close();
        assertThrows(
                IOException.class,
                () -> new StdioProcessTransport(
                                List.of(temporaryDirectory.resolve("missing").toString()))
                        .connect());
    }

    @Test
    void processConnectionDelegatesFramesAndClosesGracefully() throws Exception {
        RecordingRpcConnection delegate = new RecordingRpcConnection();
        ControlledProcess process = new ControlledProcess(true);
        StdioProcessTransport.ProcessConnection connection =
                new StdioProcessTransport.ProcessConnection(delegate, process);
        JsonRpcNotification notification = new JsonRpcNotification("extension/event", new CanonicalPayload("{}"));

        connection.send(notification);
        assertSame(notification, connection.receive());
        connection.close();

        assertSame(notification, delegate.sent);
        assertTrue(delegate.closed);
        assertFalse(process.destroyed);
        assertFalse(process.forciblyDestroyed);
    }

    @Test
    void processConnectionEscalatesTerminationAndPreservesProtocolFailure() {
        RecordingRpcConnection delegate = new RecordingRpcConnection();
        IOException closeFailure = new IOException("close failed");
        delegate.closeFailure = closeFailure;
        ControlledProcess process = new ControlledProcess(false, false);
        StdioProcessTransport.ProcessConnection connection =
                new StdioProcessTransport.ProcessConnection(delegate, process);

        IOException thrown = assertThrows(IOException.class, connection::close);

        assertSame(closeFailure, thrown);
        assertTrue(process.destroyed);
        assertTrue(process.forciblyDestroyed);
    }

    @Test
    void processConnectionReturnsAfterSoftDestroyAndRestoresInterrupt() throws Exception {
        ControlledProcess soft = new ControlledProcess(false, true);
        new StdioProcessTransport.ProcessConnection(new RecordingRpcConnection(), soft).close();
        assertTrue(soft.destroyed);
        assertFalse(soft.forciblyDestroyed);

        ControlledProcess interrupted = new ControlledProcess(new InterruptedException("interrupted"));
        new StdioProcessTransport.ProcessConnection(new RecordingRpcConnection(), interrupted).close();
        assertTrue(Thread.interrupted());
        assertTrue(interrupted.forciblyDestroyed);
    }

    @Test
    void unixDomainSocketTransportsAProtocolFrame() throws Exception {
        Path socket = temporaryDirectory.resolve("client.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            bindFixtureOrSkip(server, socket);
            Thread worker = Thread.startVirtualThread(() -> serveOneFrame(server));
            UnixDomainSocketTransport transport = new UnixDomainSocketTransport(socket);
            assertEquals(TransportKind.UDS, transport.kind());

            try (RpcConnection connection = transport.connect()) {
                JsonRpcRequest request =
                        new JsonRpcRequest(new RpcId("uds"), "workspace/list", new CanonicalPayload("{}"));
                connection.send(request);
                assertEquals(JsonRpcResponse.success(request.id(), new CanonicalPayload("{}")), connection.receive());
            }
            worker.join();
        }
    }

    @Test
    void unixDomainSocketClosesChannelAfterConnectFailure() {
        UnixDomainSocketTransport transport = new UnixDomainSocketTransport(temporaryDirectory.resolve("missing.sock"));
        assertThrows(IOException.class, transport::connect);
    }

    private static void serveOneFrame(ServerSocketChannel server) {
        try (SocketChannel channel = server.accept();
                RpcConnection connection = new StreamRpcConnection(
                        Channels.newInputStream(channel), Channels.newOutputStream(channel), new JsonRpcCodec())) {
            JsonRpcRequest request = (JsonRpcRequest) connection.receive();
            connection.send(JsonRpcResponse.success(request.id(), new CanonicalPayload("{}")));
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void bindFixtureOrSkip(ServerSocketChannel server, Path socket) throws IOException {
        try {
            server.bind(UnixDomainSocketAddress.of(socket));
        } catch (IOException unavailable) {
            String reason = String.valueOf(unavailable.getMessage()).toLowerCase(Locale.ROOT);
            if (reason.contains("operation not permitted") || reason.contains("permission denied")) {
                Assumptions.assumeTrue(false, () -> "当前执行沙箱不允许创建 UDS 测试端点: " + unavailable.getMessage());
            }
            throw unavailable;
        }
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? name + ".exe" : name;
    }

    private static final class RecordingRpcConnection implements RpcConnection {
        private JsonRpcMessage sent;
        private IOException closeFailure;
        private boolean closed;

        @Override
        public void send(JsonRpcMessage message) {
            sent = message;
        }

        @Override
        public JsonRpcMessage receive() {
            return sent;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class ControlledProcess extends Process {
        private final ArrayDeque<Object> waits = new ArrayDeque<>();
        private boolean destroyed;
        private boolean forciblyDestroyed;

        private ControlledProcess(Object... waits) {
            this.waits.addAll(List.of(waits));
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            Object outcome = waits.removeFirst();
            if (outcome instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            return (boolean) outcome;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            forciblyDestroyed = true;
            return this;
        }
    }
}
