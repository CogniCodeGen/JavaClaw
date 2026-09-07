package com.javaclaw.server.transport;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.InitializeResult;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.StreamRpcConnection;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.AppServerBootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnixDomainSocketRpcServerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bindRequiresAnUnusedAbsolutePathAndDeletesOnlyItsSocket() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> UnixDomainSocketRpcServer.bind(Path.of("relative.sock")));
        Path socket = temporaryDirectory.resolve("server.sock");

        try (UnixDomainSocketRpcServer ignored = UnixDomainSocketRpcServer.bind(socket)) {
            assertTrue(Files.exists(socket));
            assertEquals(
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(socket));
            assertThrows(java.io.IOException.class, () -> UnixDomainSocketRpcServer.bind(socket));
        }

        assertFalse(Files.exists(socket));
    }

    @Test
    void bindReclaimsAnOwnedStaleSocket() throws Exception {
        Path socket = temporaryDirectory.resolve("stale.sock");
        try (ServerSocketChannel stale = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            stale.bind(UnixDomainSocketAddress.of(socket));
        }
        assertTrue(Files.exists(socket));

        try (UnixDomainSocketRpcServer ignored = UnixDomainSocketRpcServer.bind(socket)) {
            assertTrue(Files.exists(socket));
        }

        assertFalse(Files.exists(socket));
    }

    @Test
    void bindRejectsUnsafeParentAndNonSocketTargets() throws Exception {
        assertThrows(NullPointerException.class, () -> UnixDomainSocketRpcServer.bind(null));
        assertThrows(IllegalArgumentException.class, () -> UnixDomainSocketRpcServer.bind(Path.of("/")));

        Path regular = temporaryDirectory.resolve("regular.sock");
        Files.writeString(regular, "not a socket");
        assertThrows(java.io.IOException.class, () -> UnixDomainSocketRpcServer.bind(regular));

        Path writableParent = temporaryDirectory.resolve("writable");
        Files.createDirectory(writableParent);
        Files.setPosixFilePermissions(
                writableParent,
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_WRITE));
        assertThrows(
                java.io.IOException.class, () -> UnixDomainSocketRpcServer.bind(writableParent.resolve("server.sock")));

        Path realParent = temporaryDirectory.resolve("real-parent");
        Files.createDirectory(realParent);
        Path linkedParent = temporaryDirectory.resolve("linked-parent");
        Files.createSymbolicLink(linkedParent, realParent);
        assertThrows(
                java.io.IOException.class, () -> UnixDomainSocketRpcServer.bind(linkedParent.resolve("server.sock")));
    }

    @Test
    void closeIsIdempotentAndRefusesToDeleteReplacedPath() throws Exception {
        Path socket = temporaryDirectory.resolve("replace.sock");
        UnixDomainSocketRpcServer server = UnixDomainSocketRpcServer.bind(socket);
        Files.delete(socket);
        Files.writeString(socket, "replacement");

        assertThrows(java.io.IOException.class, server::close);
        assertTrue(Files.isRegularFile(socket));
        server.close();
    }

    @Test
    void serveRequiresComponentsAndCreatedParentIsPrivate() throws Exception {
        Path socket = temporaryDirectory.resolve("private-parent/server.sock");
        try (UnixDomainSocketRpcServer server = UnixDomainSocketRpcServer.bind(socket)) {
            assertEquals(
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(socket.getParent()));
            assertThrows(NullPointerException.class, () -> server.serve(null, connection -> {}));
            assertThrows(
                    NullPointerException.class, () -> server.serve(new com.javaclaw.protocol.CanonicalJson(), null));
        }
    }

    @Test
    void eachSocketConnectionRunsAProtocolV2Session() throws Exception {
        Path socket = temporaryDirectory.resolve("rpc.sock");
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (AppServerBootstrap.Components components = AppServerBootstrap.create(
                        temporaryDirectory.resolve("data-v6"), Clock.systemUTC(), new NoopModel());
                UnixDomainSocketRpcServer server = UnixDomainSocketRpcServer.bind(socket)) {
            Thread serving = Thread.ofVirtual().start(() -> {
                try {
                    server.serve(
                            components.json(),
                            connection -> components.newSession().serve(connection));
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });

            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(socket));
                try (StreamRpcConnection connection = new StreamRpcConnection(
                        Channels.newInputStream(channel),
                        Channels.newOutputStream(channel),
                        new com.javaclaw.protocol.JsonRpcCodec(components.json()))) {
                    InitializeParams params = new InitializeParams(
                            ProtocolVersion.CURRENT,
                            new ClientInfo("uds-test", "5"),
                            new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
                    connection.send(new JsonRpcRequest(
                            new RpcId("initialize"),
                            "initialize/session",
                            components.json().encode(params)));
                    JsonRpcResponse response = assertInstanceOf(JsonRpcResponse.class, connection.receive());
                    InitializeResult result =
                            components.json().decode(response.result().orElseThrow(), InitializeResult.class);
                    assertEquals(ProtocolVersion.CURRENT, result.appProtocolVersion());
                }
            }

            server.close();
            serving.join();
            assertEquals(null, serverFailure.get());
        }
    }

    private static final class NoopModel implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                com.javaclaw.api.TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            return new ModelInvocationResult(
                    "",
                    java.util.List.of(),
                    ModelUsage.zero(),
                    java.util.Optional.empty(),
                    java.util.Optional.empty(),
                    ModelFinishReason.COMPLETE);
        }
    }
}
