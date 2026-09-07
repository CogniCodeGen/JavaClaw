package com.javaclaw.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewCommandBindingWireCodecTest {
    @Test
    void roundTripsRequiredExplicitCommandBindings() {
        ViewAction start = new ViewAction(
                "启动",
                "execution/start",
                Map.of("mode", "safe"),
                Map.of(),
                new ExpectedRevisionBinding.None(),
                false,
                binding("roleId", "roles", "id"),
                binding("roleRevision", "roles", "revision"));
        ViewSchema schema = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "profiles.start",
                "启动",
                List.of(new ViewDataSource("roles", "agent/role/list", Map.of(), List.of(), 20)),
                List.of(new ViewSchema.Card("start", "启动", "使用选中的 Role", List.of(start))));
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(new CanonicalJson());

        CanonicalPayload encoded = codec.encode(schema);

        assertEquals(schema, codec.decode(encoded));
        assertTrue(encoded.json().contains("\"commandBindings\""));
        assertTrue(encoded.json().contains("\"argumentName\":\"roleRevision\""));
    }

    @Test
    void rejectsMissingCommandBindingsAndUnknownBindingProperty() {
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(new CanonicalJson());
        Map<String, Object> missing = action();
        Map<String, Object> unknownBinding = action();
        unknownBinding.put(
                "commandBindings",
                List.of(Map.of(
                        "argumentName",
                        "roleId",
                        "binding",
                        Map.of("sourceId", "roles", "field", "id"),
                        "script",
                        "run()")));

        assertThrows(ProtocolException.class, () -> codec.decode(payload(missing)));
        assertThrows(ProtocolException.class, () -> codec.decode(payload(unknownBinding)));
    }

    @Test
    void rejectsTooManyAndDuplicateAuthoritativeArgumentsDuringDecode() {
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(new CanonicalJson());
        Map<String, Object> tooMany = action();
        tooMany.put(
                "commandBindings",
                IntStream.rangeClosed(0, ViewAction.MAX_COMMAND_BINDINGS)
                        .mapToObj(index -> wireBinding("argument" + index, "field" + index))
                        .toList());
        Map<String, Object> duplicate = action();
        duplicate.put("commandBindings", List.of(wireBinding("roleId", "id"), wireBinding("roleId", "otherId")));

        assertThrows(ProtocolException.class, () -> codec.decode(payload(tooMany)));
        assertThrows(ProtocolException.class, () -> codec.decode(payload(duplicate)));
    }

    private static Map<String, Object> action() {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("label", "启动");
        action.put("command", "execution/start");
        action.put("arguments", Map.of());
        action.put("rowArguments", Map.of());
        Map<String, Object> expectedRevision = new LinkedHashMap<>();
        expectedRevision.put("type", "none");
        expectedRevision.put("sourceId", null);
        expectedRevision.put("field", null);
        action.put("expectedRevision", expectedRevision);
        action.put("dangerous", false);
        return action;
    }

    private static CanonicalPayload payload(Map<String, Object> action) {
        return new CanonicalJson()
                .encode(Map.of(
                        "schemaVersion",
                        2,
                        "viewId",
                        "profiles.start",
                        "title",
                        "启动",
                        "dataSources",
                        List.of(new ViewDataSource("roles", "agent/role/list", Map.of(), List.of(), 20)),
                        "nodes",
                        List.of(Map.of(
                                "type", "card",
                                "id", "start",
                                "title", "启动",
                                "body", "使用选中的 Role",
                                "actions", List.of(action)))));
    }

    private static ViewCommandBinding binding(String argument, String source, String field) {
        return new ViewCommandBinding(argument, new ViewBinding(source, field));
    }

    private static Map<String, Object> wireBinding(String argument, String field) {
        return Map.of("argumentName", argument, "binding", Map.of("sourceId", "roles", "field", field));
    }
}
