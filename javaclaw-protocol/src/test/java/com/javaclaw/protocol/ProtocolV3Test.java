package com.javaclaw.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewAttachmentPolicy;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolV3Test {
    @Test
    void codecRequiresJsonRpcTwoAndRejectsUnknownEnvelopeFields() {
        JsonRpcCodec codec = new JsonRpcCodec();

        ProtocolException oldVersion = assertThrows(
                ProtocolException.class,
                () -> codec.decode("{\"jsonrpc\":\"1.0\",\"id\":\"1\",\"method\":\"thread/read\",\"params\":{}}"));
        assertEquals(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION, oldVersion.code());
        assertThrows(
                ProtocolException.class,
                () -> codec.decode(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"thread/read\",\"params\":{},\"extra\":true}"));
        assertThrows(
                ProtocolException.class,
                () -> codec.decode("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"id\":\"2\",\"method\":\"thread/read\"}"));
    }

    @Test
    void streamConnectionPreservesOneCompleteLengthPrefixedMessage() throws Exception {
        JsonRpcCodec codec = new JsonRpcCodec();
        JsonRpcRequest request = new JsonRpcRequest(new RpcId("request-1"), "thread/read", new CanonicalPayload("{}"));
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        try (StreamRpcConnection sender = new StreamRpcConnection(new ByteArrayInputStream(new byte[0]), wire, codec)) {
            sender.send(request);
        }

        byte[] frame = wire.toByteArray();
        try (StreamRpcConnection receiver =
                new StreamRpcConnection(new ByteArrayInputStream(frame), new ByteArrayOutputStream(), codec)) {
            JsonRpcRequest decoded = assertInstanceOf(JsonRpcRequest.class, receiver.receive());
            assertEquals(request, decoded);
        }
        assertTrue(new String(frame, StandardCharsets.UTF_8).contains("thread/read"));
    }

    @Test
    void canonicalPayloadIsEmbeddedAsJsonObjectInsteadOfQuotedWrapper() {
        CanonicalJson json = new CanonicalJson();
        WriteCommand command = new WriteCommand("key-1", 0, json.parse("{\"name\":\"demo\"}"));

        CanonicalPayload encoded = json.encode(command);
        WriteCommand decoded = json.decode(encoded, WriteCommand.class);

        assertEquals(command, decoded);
        assertTrue(encoded.json().contains("\"payload\":{\"name\":\"demo\"}"));
        assertEquals(Optional.of("key-1"), json.textField(encoded, "idempotencyKey"));
    }

    @Test
    void viewSchemaWireCodecRoundTripsEveryWhitelistedNodeWithExplicitTypes() {
        ViewDataSource content = new ViewDataSource("content", "view.read", Map.of(), List.of(), 1);
        ViewDataSource rows = new ViewDataSource("rows", "view.list", Map.of(), List.of(), 20);
        ViewAction run =
                new ViewAction("执行", "run", Map.of("id", "one"), Map.of(), new ExpectedRevisionBinding.None(), false);
        ViewSchema schema = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "test.view",
                "测试页面",
                List.of(content, rows),
                List.of(
                        testForm(),
                        new ViewSchema.ListView(
                                "list", "列表", "rows", "id", "title", "detail", ViewSelectionMode.SINGLE, List.of()),
                        new ViewSchema.Table(
                                "table",
                                "表格",
                                "rows",
                                "id",
                                List.of(new ViewSchema.Column("id", "标识", Optional.of(120))),
                                ViewSelectionMode.NONE,
                                List.of()),
                        new ViewSchema.Card("card", "标题", "正文", List.of(run)),
                        new ViewSchema.Progress("progress", "进度", "rows", "ratio", "label"),
                        new ViewSchema.Timeline("timeline", "历史", "rows", "time", "content"),
                        new ViewSchema.Markdown("markdown", "说明", new ViewBinding("content", "body")),
                        new ViewSchema.Code(
                                "code",
                                "代码",
                                new ViewBinding("content", "source"),
                                Optional.of(new ViewBinding("content", "language"))),
                        new ViewSchema.Artifact(
                                "artifact",
                                "产物",
                                new ViewBinding("content", "ref"),
                                new ViewBinding("content", "mediaType")),
                        new ViewSchema.Graph("graph", "流程", "rows", "rows", "id", "title", "kind", "from", "to")));
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(new CanonicalJson());

        CanonicalPayload encoded = codec.encode(schema);

        assertEquals(schema, codec.decode(encoded));
        assertTrue(encoded.json().contains("\"type\":\"table\""));
        assertTrue(encoded.json().contains("\"type\":\"artifact\""));
        assertTrue(encoded.json().contains("\"type\":\"graph\""));
    }

    private static ViewSchema.Form testForm() {
        ViewField name = new ViewField(
                "name",
                "名称",
                ViewFieldType.TEXT,
                new ViewBinding("content", "name"),
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewField attachment = new ViewField(
                "attachment",
                "文件",
                ViewFieldType.ATTACHMENT,
                new ViewBinding("content", "attachment"),
                Optional.empty(),
                ViewFieldValidation.attachment(true, new ViewAttachmentPolicy(java.util.Set.of("text/*"), 1024)),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewAction save = new ViewAction(
                "保存", "put", Map.of(), Map.of(), new ExpectedRevisionBinding.SourceRevision("content"), false);
        return new ViewSchema.Form("form", "编辑", List.of(name, attachment), save);
    }

    @Test
    void viewSchemaWireCodecRejectsUnknownNodeType() {
        CanonicalJson json = new CanonicalJson();
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(json);
        CanonicalPayload payload = json.parse("""
                {"nodes":[{"id":"unsafe","type":"webview"}],
                 "dataSources":[],"schemaVersion":2,"title":"Unsafe","viewId":"unsafe.view"}
                """);

        ProtocolException failure = assertThrows(ProtocolException.class, () -> codec.decode(payload));

        assertEquals(ProtocolErrorCode.INVALID_PARAMS, failure.code());
    }

    @Test
    void viewSchemaWireCodecRejectsSecretInputType() {
        CanonicalJson json = new CanonicalJson();
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(json);
        CanonicalPayload payload = json.parse("""
                {"nodes":[{"fields":[{
                    "binding":{"field":"password","sourceId":"editor"},
                    "initialValue":null,"label":"密码","name":"password",
                    "optionSource":null,"options":[],"type":"SECRET",
                    "validation":{"attachment":null,"maxLength":null,"maximum":null,
                        "minLength":null,"minimum":null,"required":true},
                    "visibleWhen":null
                }],"id":"credentials","submit":{"arguments":{},"command":"credentials.put","commandBindings":[],
                    "dangerous":false,"expectedRevision":{"field":null,"sourceId":null,"type":"none"},
                    "label":"保存","rowArguments":{}},
                    "title":"凭据","type":"form"}],
                 "dataSources":[{"argumentBindings":[],"arguments":{},"id":"editor",
                    "pageSize":1,"query":"view.read"}],
                 "schemaVersion":2,"title":"Unsafe","viewId":"unsafe.credentials"}
                """);

        ProtocolException failure = assertThrows(ProtocolException.class, () -> codec.decode(payload));

        assertEquals(ProtocolErrorCode.INVALID_PARAMS, failure.code());
    }
}
