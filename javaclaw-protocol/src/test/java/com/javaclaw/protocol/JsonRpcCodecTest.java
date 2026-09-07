package com.javaclaw.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonRpcCodecTest {
    private final CanonicalJson json = new CanonicalJson();
    private final JsonRpcCodec codec = new JsonRpcCodec(json);

    @Test
    void requestNotification成功和失败响应完整往返() {
        JsonRpcRequest request = new JsonRpcRequest(new RpcId("request"), "thread/read", json.parse("{\"id\":1}"));
        JsonRpcNotification notification = new JsonRpcNotification("extension/event", json.parse("{\"revision\":2}"));
        JsonRpcResponse success = JsonRpcResponse.success(new RpcId("success"), json.parse("{\"ok\":true}"));
        JsonRpcResponse failure = JsonRpcResponse.failure(
                new RpcId("failure"),
                new JsonRpcError(
                        ProtocolErrorCode.INVALID_PARAMS,
                        "bad input",
                        Optional.of(json.parse("{\"field\":\"name\"}"))));

        assertEquals(request, codec.decode(codec.encode(request)));
        assertEquals(notification, codec.decode(codec.encode(notification)));
        assertEquals(success, codec.decode(codec.encode(success)));
        assertEquals(failure, codec.decode(codec.encode(failure)));
        assertEquals(ProtocolVersion.JSON_RPC, request.jsonrpc());
        assertTrue(codec.encode(notification).contains("\"method\":\"extension/event\""));
    }

    @Test
    void 缺失params默认为空对象且无data错误可解码() {
        JsonRpcRequest request = assertInstanceOf(
                JsonRpcRequest.class, codec.decode("{\"jsonrpc\":\"2.0\",\"id\":\"one\",\"method\":\"thread/read\"}"));
        JsonRpcResponse response = assertInstanceOf(
                JsonRpcResponse.class,
                codec.decode("{\"jsonrpc\":\"2.0\",\"id\":\"one\",\"error\":{\"code\":-32602,\"message\":\"bad\"}}"));

        assertEquals("{}", request.params().json());
        assertTrue(response.error().orElseThrow().data().isEmpty());
    }

    @Test
    void 非法请求信封全部在handler之前拒绝() {
        assertCode(ProtocolErrorCode.PARSE_ERROR, "{");
        assertCode(ProtocolErrorCode.INVALID_REQUEST, "[]");
        assertCode(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION, "{}");
        assertCode(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION, "{\"jsonrpc\":2}");
        assertCode(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION, "{\"jsonrpc\":\"1.0\"}");
        assertCode(ProtocolErrorCode.INVALID_REQUEST, "{\"jsonrpc\":\"2.0\",\"method\":\"thread/read\",\"extra\":1}");
        assertCode(ProtocolErrorCode.INVALID_REQUEST, "{\"jsonrpc\":\"2.0\",\"method\":\" \"}");
        assertCode(ProtocolErrorCode.INVALID_REQUEST, "{\"jsonrpc\":\"2.0\",\"method\":\"thread/read\",\"params\":[]}");
        assertCode(ProtocolErrorCode.INVALID_REQUEST, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"thread/read\"}");
    }

    @Test
    void 非法响应和错误对象全部拒绝() {
        assertCode(ProtocolErrorCode.INVALID_REQUEST, "{\"jsonrpc\":\"2.0\",\"result\":{}}");
        assertCode(ProtocolErrorCode.INVALID_REQUEST, "{\"jsonrpc\":\"2.0\",\"id\":\"one\"}");
        assertCode(
                ProtocolErrorCode.INVALID_REQUEST, "{\"jsonrpc\":\"2.0\",\"id\":\"one\",\"result\":{},\"error\":{}}");
        assertCode(ProtocolErrorCode.INVALID_REQUEST, "{\"jsonrpc\":\"2.0\",\"id\":\"one\",\"result\":[],\"extra\":1}");
        assertCode(ProtocolErrorCode.INVALID_REQUEST, "{\"jsonrpc\":\"2.0\",\"id\":\"one\",\"result\":[]}");
        assertCode(ProtocolErrorCode.INVALID_REQUEST, "{\"jsonrpc\":\"2.0\",\"id\":\"one\",\"error\":[]}");
        assertCode(
                ProtocolErrorCode.INVALID_REQUEST,
                "{\"jsonrpc\":\"2.0\",\"id\":\"one\",\"error\":{\"code\":-1,\"message\":\"x\",\"extra\":1}}");
        assertCode(
                ProtocolErrorCode.INVALID_REQUEST,
                "{\"jsonrpc\":\"2.0\",\"id\":\"one\",\"error\":{\"code\":\"x\",\"message\":\"x\"}}");
        assertCode(
                ProtocolErrorCode.INVALID_REQUEST,
                "{\"jsonrpc\":\"2.0\",\"id\":\"one\",\"error\":{\"code\":-1,\"message\":\" \"}}");
        assertCode(
                ProtocolErrorCode.INVALID_REQUEST,
                "{\"jsonrpc\":\"2.0\",\"id\":\"one\",\"error\":{\"code\":-1,\"message\":\"x\",\"data\":[]}}");
    }

    @Test
    void message值对象拒绝歧义和空标识() {
        CanonicalPayload empty = json.parse("{}");
        assertThrows(NullPointerException.class, () -> new RpcId(null));
        assertThrows(IllegalArgumentException.class, () -> new RpcId(" "));
        assertThrows(IllegalArgumentException.class, () -> new RpcId("x".repeat(129)));
        assertThrows(NullPointerException.class, () -> new JsonRpcRequest(null, "thread/read", empty));
        assertThrows(IllegalArgumentException.class, () -> new JsonRpcRequest(new RpcId("x"), "thread", empty));
        assertThrows(NullPointerException.class, () -> new JsonRpcNotification("extension/event", null));
        assertThrows(IllegalArgumentException.class, () -> new JsonRpcError(-1, " ", Optional.empty()));
        assertThrows(NullPointerException.class, () -> new JsonRpcError(-1, "error", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JsonRpcResponse(new RpcId("x"), Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JsonRpcResponse(
                        new RpcId("x"),
                        Optional.of(empty),
                        Optional.of(new JsonRpcError(-1, "error", Optional.empty()))));
    }

    @Test
    void framing拒绝空帧越界长度和截断输入() throws Exception {
        ByteArrayOutputStream validWire = new ByteArrayOutputStream();
        LengthPrefixedFraming.write(validWire, "ok".getBytes(StandardCharsets.UTF_8), 2);
        assertEquals(
                "ok", new String(LengthPrefixedFraming.read(new ByteArrayInputStream(validWire.toByteArray()), 2)));

        assertThrows(
                IllegalArgumentException.class,
                () -> LengthPrefixedFraming.read(new ByteArrayInputStream(new byte[0]), 0));
        assertThrows(EOFException.class, () -> LengthPrefixedFraming.read(new ByteArrayInputStream(new byte[0]), 4));
        assertThrows(IOException.class, () -> LengthPrefixedFraming.read(integerWire(0), 4));
        assertThrows(IOException.class, () -> LengthPrefixedFraming.read(integerWire(5), 4));
        assertThrows(EOFException.class, () -> LengthPrefixedFraming.read(lengthAndBytes(3, new byte[] {1}), 4));
        assertThrows(IllegalArgumentException.class, () -> LengthPrefixedFraming.write(validWire, new byte[] {1}, 0));
        assertThrows(IOException.class, () -> LengthPrefixedFraming.write(validWire, new byte[0], 4));
        assertThrows(IOException.class, () -> LengthPrefixedFraming.write(validWire, new byte[5], 4));
    }

    @Test
    void stream连接校验帧上限并保留双流关闭异常() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StreamRpcConnection(
                        new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), codec, 0));
        try (StreamRpcConnection connection =
                new StreamRpcConnection(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), codec, 8)) {
            assertThrows(NullPointerException.class, () -> connection.send(null));
            assertThrows(EOFException.class, connection::receive);
            assertThrows(
                    IOException.class,
                    () -> connection.send(new JsonRpcNotification("extension/event", json.parse("{\"large\":true}"))));
        }

        StreamRpcConnection bothFail = new StreamRpcConnection(new FailingInput(), new FailingOutput(), codec);
        IOException combined = assertThrows(IOException.class, bothFail::close);
        assertEquals("input close", combined.getMessage());
        assertEquals(1, combined.getSuppressed().length);

        StreamRpcConnection outputFails =
                new StreamRpcConnection(new ByteArrayInputStream(new byte[0]), new FailingOutput(), codec);
        assertEquals(
                "output close",
                assertThrows(IOException.class, outputFails::close).getMessage());
    }

    private void assertCode(int expected, String wire) {
        assertEquals(
                expected,
                assertThrows(ProtocolException.class, () -> codec.decode(wire)).code());
    }

    private static ByteArrayInputStream integerWire(int value) {
        return new ByteArrayInputStream(
                ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static ByteArrayInputStream lengthAndBytes(int length, byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
        output.write(bytes);
        return new ByteArrayInputStream(output.toByteArray());
    }

    private static final class FailingInput extends ByteArrayInputStream {
        private FailingInput() {
            super(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            throw new IOException("input close");
        }
    }

    private static final class FailingOutput extends ByteArrayOutputStream {
        @Override
        public void close() throws IOException {
            throw new IOException("output close");
        }
    }
}
