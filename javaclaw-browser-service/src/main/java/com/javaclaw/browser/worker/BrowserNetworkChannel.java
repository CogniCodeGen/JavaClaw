package com.javaclaw.browser.worker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.javaclaw.browser.protocol.BrowserFrameIo;
import com.javaclaw.browser.protocol.BrowserWorkerProtocol;
import com.javaclaw.protocol.CanonicalJson;

/** Worker 通过私有 stdin/stdout 向宿主请求单跳 HTTPS 的同步通道。 */
final class BrowserNetworkChannel implements BrowserNetworkPort {
    private final long commandId;
    private final InputStream input;
    private final OutputStream output;
    private final CanonicalJson json;
    private long sequence;

    BrowserNetworkChannel(long commandId, InputStream input, OutputStream output, CanonicalJson json) {
        if (commandId < 1) {
            throw new IllegalArgumentException("commandId must be positive");
        }
        this.commandId = commandId;
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 提交一个 Browser 产生的请求并等待宿主 Broker 答复。
     *
     * @param request 请求元数据
     * @param body 原始请求 body
     * @return 原始响应与仅供 Browser 使用的 header
     */
    @Override
    public synchronized NetworkResult exchange(BrowserWorkerProtocol.NetworkRequest request, byte[] body) {
        byte[] requestBody = Objects.requireNonNull(body, "body").clone();
        if (requestBody.length > BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES) {
            Arrays.fill(requestBody, (byte) 0);
            throw new IllegalArgumentException("Browser request body exceeds the frame limit");
        }
        long requestSequence = Math.addExact(sequence, 1);
        sequence = requestSequence;
        try {
            BrowserWorkerProtocol.WorkerMessage message = BrowserWorkerProtocol.WorkerMessage.network(
                    commandId, requestSequence, json.encode(request), requestBody.length);
            BrowserFrameIo.writeJson(output, json, message);
            BrowserFrameIo.writeBinary(output, requestBody, BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            BrowserWorkerProtocol.HostMessage response =
                    BrowserFrameIo.readJson(input, json, BrowserWorkerProtocol.HostMessage.class);
            requireIdentity(response, requestSequence);
            if (response.error().isPresent()) {
                throw new IllegalStateException("host Network Broker denied the Browser request");
            }
            byte[] responseBody = BrowserFrameIo.readBinary(
                    input, response.binaryBytes(), BrowserWorkerProtocol.MAXIMUM_NETWORK_BYTES);
            BrowserWorkerProtocol.NetworkResponse metadata =
                    json.decode(response.payload().orElseThrow(), BrowserWorkerProtocol.NetworkResponse.class);
            return new NetworkResult(metadata.statusCode(), metadata.headers(), responseBody, metadata.truncated());
        } catch (IOException failure) {
            throw new IllegalStateException("Browser Network Broker channel failed", failure);
        } finally {
            Arrays.fill(requestBody, (byte) 0);
        }
    }

    private void requireIdentity(BrowserWorkerProtocol.HostMessage response, long requestSequence) {
        if (response.commandId() != commandId || response.sequence() != requestSequence) {
            throw new IllegalStateException("host Network Broker response identity mismatch");
        }
    }

    /** 仅在隔离 Worker 内存中存在的敏感网络响应。 */
    record NetworkResult(int statusCode, Map<String, List<String>> headers, byte[] body, boolean truncated) {
        NetworkResult {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
