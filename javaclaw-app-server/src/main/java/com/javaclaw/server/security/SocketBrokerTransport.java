package com.javaclaw.server.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.javaclaw.api.CancellationToken;

/** 使用显式解析地址直连，并为 HTTPS 保留原主机 SNI 与证书主机校验。 */
final class SocketBrokerTransport implements HttpWireExchange.Transport {
    private static final int CONNECT_SLICE_MILLIS = 3_000;
    private static final int HANDSHAKE_SLICE_MILLIS = 500;

    @Override
    public HttpWireExchange.Connection open(
            BrokerTarget target,
            BrokerDeadline deadline,
            CancellationToken cancellation,
            Consumer<AutoCloseable> pending)
            throws IOException {
        ArrayList<IOException> failures = new ArrayList<>();
        for (InetAddress address : target.addresses()) {
            cancellation.throwIfCancelled();
            try {
                return open(target, address, deadline, cancellation, pending);
            } catch (IOException failure) {
                failures.add(failure);
            }
        }
        IOException failure = new IOException("Network Broker could not connect to any pinned address");
        failures.forEach(failure::addSuppressed);
        throw failure;
    }

    private static HttpWireExchange.Connection open(
            BrokerTarget target,
            InetAddress address,
            BrokerDeadline deadline,
            CancellationToken cancellation,
            Consumer<AutoCloseable> pending)
            throws IOException {
        Socket raw = new Socket(Proxy.NO_PROXY);
        pending.accept(raw);
        boolean ready = false;
        try {
            raw.setTcpNoDelay(true);
            raw.connect(
                    new InetSocketAddress(address, target.port()),
                    deadline.timeoutMillis(cancellation, CONNECT_SLICE_MILLIS));
            Socket connected =
                    target.tls() ? tls(raw, target.host(), target.port(), deadline, cancellation, pending) : raw;
            connected.setSoTimeout(deadline.timeoutMillis(cancellation, Integer.MAX_VALUE));
            ready = true;
            return new SocketConnection(connected);
        } finally {
            if (!ready) {
                raw.close();
            }
        }
    }

    private static SSLSocket tls(
            Socket raw,
            String host,
            int port,
            BrokerDeadline deadline,
            CancellationToken cancellation,
            Consumer<AutoCloseable> pending)
            throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket socket = (SSLSocket) factory.createSocket(raw, host, port, true);
        pending.accept(socket);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!ipLiteral(host)) {
            parameters.setServerNames(List.of(new SNIHostName(host)));
        }
        socket.setSSLParameters(parameters);
        while (true) {
            cancellation.throwIfCancelled();
            socket.setSoTimeout(deadline.timeoutMillis(cancellation, HANDSHAKE_SLICE_MILLIS));
            try {
                socket.startHandshake();
                return socket;
            } catch (SocketTimeoutException pendingHandshake) {
                deadline.remaining(cancellation);
            }
        }
    }

    private static boolean ipLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    private record SocketConnection(Socket socket) implements HttpWireExchange.Connection {
        @Override
        public InputStream input() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public OutputStream output() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
