package com.javaclaw.protocol;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.PromptOptimizationId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;
import com.javaclaw.extension.spi.ExtensionId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalJsonTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 对象键数字数组与嵌套对象得到稳定规范形式() {
        CanonicalPayload payload = json.parse("""
                {"z":1.2300,"array":[{"b":2,"a":1}],"a":{"d":4,"c":3}}
                """);

        assertEquals("{\"a\":{\"c\":3,\"d\":4},\"array\":[{\"a\":1,\"b\":2}],\"z\":1.23}", payload.json());
        assertFalse(json.containsAnyField(payload, Set.of("secret")));
        assertTrue(json.containsAnyField(payload, Set.of("D")));
        assertTrue(json.containsAnyField(payload, Set.of("B")));
    }

    @Test
    void PathOptional与嵌套CanonicalPayload按契约往返() {
        CodecValue value = new CodecValue(
                Path.of("folder/file.txt"), new BigDecimal("10.500"), Optional.of("yes"), json.parse("{\"b\":2}"));

        CanonicalPayload encoded = json.encode(value);
        CodecValue decoded = json.decode(encoded, CodecValue.class);

        assertEquals(value.path(), decoded.path());
        assertEquals(0, value.amount().compareTo(decoded.amount()));
        assertEquals(value.optional(), decoded.optional());
        assertEquals(value.nested(), decoded.nested());
        assertEquals("folder/file.txt", json.textField(encoded, "path").orElseThrow());
    }

    @Test
    void Set按元素规范形式排序且不受插入顺序影响() {
        SetValue first =
                new SetValue(new LinkedHashSet<>(List.of("beta", "alpha")), new LinkedHashSet<>(List.of(20, 3, 1)));
        SetValue second =
                new SetValue(new LinkedHashSet<>(List.of("alpha", "beta")), new LinkedHashSet<>(List.of(1, 3, 20)));

        CanonicalPayload encoded = json.encode(first);

        assertEquals(encoded, json.encode(second));
        assertEquals("{\"numbers\":[1,20,3],\"values\":[\"alpha\",\"beta\"]}", encoded.json());
        assertEquals(first, json.decode(encoded, SetValue.class));
    }

    @Test
    void 强类型标识统一使用标量Wire文本且拒绝Record包装() {
        UUID uuid = UUID.fromString("114fdfd7-d4e7-42f4-9563-dd552b61f97d");
        IdentifierValue value = new IdentifierValue(
                new WorkspaceId(uuid),
                new ThreadId(uuid),
                new TurnId(uuid),
                new ItemId(uuid),
                new WorktreeId(uuid),
                new PromptOptimizationId(uuid),
                new ExtensionId("com.javaclaw.plan"));

        CanonicalPayload encoded = json.encode(value);

        assertEquals(value, json.decode(encoded, IdentifierValue.class));
        assertTrue(encoded.json().contains("\"workspaceId\":\"" + uuid + "\""));
        assertTrue(encoded.json().contains("\"extensionId\":\"com.javaclaw.plan\""));
        assertFalse(encoded.json().contains("\"value\""));
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS,
                assertThrows(
                                ProtocolException.class,
                                () -> json.decode(
                                        new CanonicalPayload("{\"workspaceId\":{\"value\":\"" + uuid
                                                + "\"},\"threadId\":\"" + uuid
                                                + "\",\"turnId\":\"" + uuid
                                                + "\",\"itemId\":\"" + uuid
                                                + "\",\"worktreeId\":\"" + uuid
                                                + "\",\"optimizationId\":\"" + uuid
                                                + "\",\"extensionId\":\"com.javaclaw.plan\"}"),
                                        IdentifierValue.class))
                        .code());
    }

    @Test
    void 时间与时长统一使用Iso8601Wire文本() {
        TemporalValue value = new TemporalValue(Instant.parse("2026-09-02T00:01:02.345Z"), Duration.ofMillis(1500));

        CanonicalPayload encoded = json.encode(value);

        assertEquals(value, json.decode(encoded, TemporalValue.class));
        assertEquals(
                "2026-09-02T00:01:02.345Z", json.textField(encoded, "instant").orElseThrow());
        assertEquals("PT1.5S", json.textField(encoded, "duration").orElseThrow());
    }

    @Test
    void 字符串字段区分缺失Null字符串和其他类型() {
        CanonicalPayload payload = json.parse("{\"missingByName\":null,\"number\":1,\"text\":\" value \"}");

        assertTrue(json.textField(payload, "absent").isEmpty());
        assertTrue(json.textField(payload, "missingByName").isEmpty());
        assertEquals(" value ", json.textField(payload, "text").orElseThrow());
        ProtocolException failure = assertThrows(ProtocolException.class, () -> json.textField(payload, "number"));
        assertEquals(ProtocolErrorCode.INVALID_PARAMS, failure.code());
        assertThrows(NullPointerException.class, () -> json.textField(payload, null));
    }

    @Test
    void 固定参数模板只允许已批准顶层字符串变化() {
        CanonicalPayload template = json.parse("{\"count\":1,\"message\":\"fixed\",\"nested\":{\"x\":1}}");
        CanonicalPayload allowed = json.parse("{\"count\":1,\"message\":\"changed\",\"nested\":{\"x\":1}}");
        CanonicalPayload changedFixed = json.parse("{\"count\":2,\"message\":\"changed\",\"nested\":{\"x\":1}}");
        CanonicalPayload added =
                json.parse("{\"count\":1,\"extra\":\"x\",\"message\":\"changed\",\"nested\":{\"x\":1}}");
        CanonicalPayload wrongType = json.parse("{\"count\":1,\"message\":2,\"nested\":{\"x\":1}}");

        json.requireTopLevelStringFields(template, Set.of("message"));
        assertTrue(json.matchesExceptTopLevelStrings(template, allowed, Set.of("message")));
        assertFalse(json.matchesExceptTopLevelStrings(template, changedFixed, Set.of("message")));
        assertFalse(json.matchesExceptTopLevelStrings(template, added, Set.of("message")));
        assertFalse(json.matchesExceptTopLevelStrings(template, wrongType, Set.of("message")));
        assertThrows(ProtocolException.class, () -> json.requireTopLevelStringFields(template, Set.of("count")));
        assertThrows(ProtocolException.class, () -> json.requireTopLevelStringFields(template, Set.of("missing")));
    }

    @Test
    void 非对象重复键损坏JSON与不匹配契约均被拒绝() {
        assertEquals(
                ProtocolErrorCode.PARSE_ERROR,
                assertThrows(ProtocolException.class, () -> json.parse("[]")).code());
        assertEquals(
                ProtocolErrorCode.PARSE_ERROR,
                assertThrows(ProtocolException.class, () -> json.parse("{\"x\":1,\"x\":2}"))
                        .code());
        assertEquals(
                ProtocolErrorCode.PARSE_ERROR,
                assertThrows(ProtocolException.class, () -> json.parse("{")).code());
        assertThrows(IllegalArgumentException.class, () -> json.encode("not-an-object"));
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS,
                assertThrows(
                                ProtocolException.class,
                                () -> json.decode(new CanonicalPayload("{\"path\":{}}"), PathValue.class))
                        .code());
    }

    @Test
    void CanonicalPayload字段拒绝数组且损坏的规范文本不能进入树() {
        ProtocolException invalidNested = assertThrows(
                ProtocolException.class,
                () -> json.decode(new CanonicalPayload("{\"nested\":[]}"), PayloadValue.class));
        ProtocolException unreadable =
                assertThrows(ProtocolException.class, () -> json.tree(new CanonicalPayload("{not-json}")));

        assertEquals(ProtocolErrorCode.INVALID_PARAMS, invalidNested.code());
        assertEquals(ProtocolErrorCode.PARSE_ERROR, unreadable.code());
    }

    @Test
    void 契约解码拒绝标量和Path的隐式类型转换() {
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS,
                assertThrows(
                                ProtocolException.class,
                                () -> json.decode(new CanonicalPayload("{\"value\":1}"), StringValue.class))
                        .code());
        assertEquals(
                ProtocolErrorCode.INVALID_PARAMS,
                assertThrows(
                                ProtocolException.class,
                                () -> json.decode(new CanonicalPayload("{\"path\":1}"), PathValue.class))
                        .code());
    }

    private record CodecValue(Path path, BigDecimal amount, Optional<String> optional, CanonicalPayload nested) {}

    private record PathValue(Path path) {}

    private record StringValue(String value) {}

    private record PayloadValue(CanonicalPayload nested) {}

    private record IdentifierValue(
            WorkspaceId workspaceId,
            ThreadId threadId,
            TurnId turnId,
            ItemId itemId,
            WorktreeId worktreeId,
            PromptOptimizationId optimizationId,
            ExtensionId extensionId) {}

    private record TemporalValue(Instant instant, Duration duration) {}

    private record SetValue(Set<String> values, Set<Integer> numbers) {}
}
