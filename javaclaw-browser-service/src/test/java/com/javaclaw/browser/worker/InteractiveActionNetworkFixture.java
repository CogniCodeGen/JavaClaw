package com.javaclaw.browser.worker;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.browser.protocol.InteractiveBrowserProtocol;
import com.javaclaw.builtin.contracts.BrowserContracts;
import com.javaclaw.protocol.CanonicalJson;

/** 通过真实私有帧响应一次 Broker 请求，不连接任何网络或浏览器。 */
final class InteractiveActionNetworkFixture implements AutoCloseable {
    private final CanonicalJson json = new CanonicalJson();
    private final PipedInputStream workerInput = new PipedInputStream();
    private final PipedInputStream hostInput = new PipedInputStream();
    private final PipedOutputStream hostOutput;
    private final PipedOutputStream workerOutput;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final CompletableFuture<BrowserContracts.NetworkRequest> request = new CompletableFuture<>();
    final InteractiveWorkerConnection connection;

    InteractiveActionNetworkFixture(int status, Map<String, List<String>> headers, byte[] body, boolean truncated)
            throws IOException {
        hostOutput = new PipedOutputStream(workerInput);
        workerOutput = new PipedOutputStream(hostInput);
        connection = new InteractiveWorkerConnection(workerInput, workerOutput, json);
        Thread.ofVirtual().start(() -> respond(status, headers, body, truncated));
    }

    BrowserContracts.NetworkRequest request() throws Exception {
        return request.get(5, TimeUnit.SECONDS);
    }

    private void respond(int status, Map<String, List<String>> headers, byte[] body, boolean truncated) {
        try {
            var frame = BrowserFrameIo.readJson(hostInput, json, InteractiveBrowserProtocol.Frame.class);
            if (frame.kind() != InteractiveBrowserProtocol.Kind.NETWORK
                    || !frame.operation().equals("1")) {
                throw new IOException("expected current Browser network generation");
            }
            byte[] input = BrowserFrameIo.readBinary(
                    hostInput, frame.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            if (input.length != 0) {
                throw new IOException("download GET cannot carry a request body");
            }
            request.complete(json.decode(frame.payload(), BrowserContracts.NetworkRequest.class));
            var metadata = new BrowserWorkerProtocol.NetworkResponse(status, headers, truncated);
            BrowserFrameIo.writeJson(
                    hostOutput,
                    json,
                    new InteractiveBrowserProtocol.Frame(
                            InteractiveBrowserProtocol.Kind.NETWORK_REPLY,
                            frame.id(),
                            frame.operation(),
                            json.encode(metadata),
                            body.length,
                            Optional.empty()));
            BrowserFrameIo.writeBinary(hostOutput, body, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            // 保持管道写端拥有者存活，直到测试显式关闭，避免读端把正常回复后的空闲误认为断连。
            closed.await();
        } catch (Exception failure) {
            request.completeExceptionally(failure);
            connection.close();
        }
    }

    @Override
    public void close() throws IOException {
        closed.countDown();
        connection.close();
        workerInput.close();
        hostInput.close();
        hostOutput.close();
        workerOutput.close();
    }
}
