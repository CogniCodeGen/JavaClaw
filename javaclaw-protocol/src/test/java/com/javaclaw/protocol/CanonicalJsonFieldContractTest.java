package com.javaclaw.protocol;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalJsonFieldContractTest {
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void 对象字段区分缺失Null对象与错误类型() {
        CanonicalPayload payload = json.parse("""
                {"missing":null,"object":{"value":1},"array":[]}
                """);

        assertTrue(json.objectField(payload, "absent").isEmpty());
        assertTrue(json.objectField(payload, "missing").isEmpty());
        assertEquals(
                "{\"value\":1}",
                json.objectField(payload, "object").orElseThrow().json());
        assertInvalidParams(() -> json.objectField(payload, "array"));
        assertThrows(NullPointerException.class, () -> json.objectField(payload, null));
    }

    @Test
    void 对象数组要求正上限有界且元素全为对象() {
        CanonicalPayload payload = json.parse("""
                {"missing":null,"objects":[{"b":2,"a":1},{}],"scalar":1,"mixed":[{},1]}
                """);

        assertEquals(List.of(), json.objectArrayField(payload, "absent", 2));
        assertEquals(List.of(), json.objectArrayField(payload, "missing", 2));
        assertEquals(
                List.of(new CanonicalPayload("{\"a\":1,\"b\":2}"), new CanonicalPayload("{}")),
                json.objectArrayField(payload, "objects", 2));
        assertThrows(IllegalArgumentException.class, () -> json.objectArrayField(payload, "objects", 0));
        assertThrows(NullPointerException.class, () -> json.objectArrayField(payload, null, 2));
        assertInvalidParams(() -> json.objectArrayField(payload, "scalar", 2));
        assertInvalidParams(() -> json.objectArrayField(payload, "objects", 1));
        assertInvalidParams(() -> json.objectArrayField(payload, "mixed", 2));
    }

    @Test
    void 字符串数组要求正上限有界且元素非空() {
        CanonicalPayload payload = json.parse("""
                {"missing":null,"values":["alpha","beta"],"scalar":1,
                 "blank":[" "],"mixed":["alpha",1]}
                """);

        assertEquals(List.of(), json.textArrayField(payload, "absent", 2));
        assertEquals(List.of(), json.textArrayField(payload, "missing", 2));
        assertEquals(List.of("alpha", "beta"), json.textArrayField(payload, "values", 2));
        assertThrows(IllegalArgumentException.class, () -> json.textArrayField(payload, "values", 0));
        assertThrows(NullPointerException.class, () -> json.textArrayField(payload, null, 2));
        assertInvalidParams(() -> json.textArrayField(payload, "scalar", 2));
        assertInvalidParams(() -> json.textArrayField(payload, "values", 1));
        assertInvalidParams(() -> json.textArrayField(payload, "blank", 1));
        assertInvalidParams(() -> json.textArrayField(payload, "mixed", 2));
    }

    @Test
    void 布尔与整数字段拒绝隐式转换和越界整数() {
        CanonicalPayload payload = json.parse("""
                {"missing":null,"enabled":true,"count":7,"fraction":1.5,
                 "huge":999999999999999999999999999999999999,"text":"1"}
                """);

        assertTrue(json.booleanField(payload, "absent").isEmpty());
        assertTrue(json.booleanField(payload, "missing").isEmpty());
        assertEquals(true, json.booleanField(payload, "enabled").orElseThrow());
        assertInvalidParams(() -> json.booleanField(payload, "count"));
        assertThrows(NullPointerException.class, () -> json.booleanField(payload, null));

        assertTrue(json.integerField(payload, "absent").isEmpty());
        assertTrue(json.integerField(payload, "missing").isEmpty());
        assertEquals(7L, json.integerField(payload, "count").orElseThrow());
        assertInvalidParams(() -> json.integerField(payload, "fraction"));
        assertInvalidParams(() -> json.integerField(payload, "huge"));
        assertInvalidParams(() -> json.integerField(payload, "text"));
        assertThrows(NullPointerException.class, () -> json.integerField(payload, null));
    }

    @Test
    void 字段名和递归敏感字段检查保持精确语义() {
        CanonicalPayload payload = json.parse("""
                {"top":1,"nested":{"Token":"x"},"entries":[{"password":"x"}],"value":"safe"}
                """);

        assertEquals(Set.of("top", "nested", "entries", "value"), json.fieldNames(payload));
        assertTrue(json.containsAnyField(payload, Set.of("token")));
        assertTrue(json.containsAnyField(payload, Set.of("PASSWORD")));
        assertThrows(NullPointerException.class, () -> json.fieldNames(null));
        assertThrows(NullPointerException.class, () -> json.containsAnyField(payload, Set.of((String) null)));
    }

    @Test
    void 受限输入Schema接受四类Primitive并校验响应() {
        CanonicalPayload schema = json.parse("""
                {
                  "type":"object",
                  "additionalProperties":false,
                  "properties":{
                    "name":{"type":"string"},
                    "score":{"type":"number"},
                    "count":{"type":"integer"},
                    "enabled":{"type":"boolean"}
                  },
                  "required":["name","count"]
                }
                """);
        CanonicalPayload value = json.parse("""
                {"name":"alpha","score":1.5,"count":2,"enabled":true}
                """);

        json.requireFlatPrimitiveObjectSchema(schema, 4);
        json.requireFlatPrimitiveObjectValue(schema, value);
        json.requireFlatPrimitiveObjectValue(json.parse("""
                        {"type":"object","additionalProperties":false,
                         "properties":{"optional":{"type":"string"}}}
                        """), json.parse("{}"));
    }

    @Test
    void 受限输入Schema拒绝开放对象错误属性集和组合能力() {
        assertThrows(
                IllegalArgumentException.class,
                () -> json.requireFlatPrimitiveObjectSchema(closedSchema("{\"name\":{\"type\":\"string\"}}"), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> json.requireFlatPrimitiveObjectSchema(closedSchema("{\"name\":{\"type\":\"string\"}}"), 101));
        assertInvalidSchema(
                "{\"type\":\"array\",\"additionalProperties\":false,\"properties\":{\"x\":{\"type\":\"string\"}}}");
        assertInvalidSchema("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}");
        assertInvalidSchema(
                "{\"type\":\"object\",\"additionalProperties\":true,\"properties\":{\"x\":{\"type\":\"string\"}}}");
        assertInvalidSchema("{\"type\":\"object\",\"additionalProperties\":false}");
        assertInvalidSchema("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":[]}");
        assertInvalidSchema("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}");

        String tooMany = IntStream.rangeClosed(1, 3)
                .mapToObj(index -> "\"field" + index + "\":{\"type\":\"string\"}")
                .collect(java.util.stream.Collectors.joining(","));
        assertInvalidParams(() -> json.requireFlatPrimitiveObjectSchema(closedSchema("{" + tooMany + "}"), 2));
        assertInvalidSchema("{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{\"x\":{\"type\":\"string\",\"oneOf\":[]}}}");
    }

    @Test
    void 受限输入Schema拒绝凭据暗示嵌套类型与未知Primitive() {
        for (String name :
                List.of("secret_value", "password", "access-token", "Cookie", "credential.id", "private_key")) {
            CanonicalPayload schema = closedSchema("{\"" + name + "\":{\"type\":\"string\"}}");
            assertInvalidParams(() -> json.requireFlatPrimitiveObjectSchema(schema, 1));
        }
        assertInvalidSchema("{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{\"nested\":{\"type\":\"object\"}}}");
        assertInvalidSchema("{\"type\":\"object\",\"additionalProperties\":false," + "\"properties\":{\"items\":[]}}");
    }

    @Test
    void 受限输入响应拒绝额外缺失错误类型和错误Required() {
        CanonicalPayload schema = json.parse("""
                {"type":"object","additionalProperties":false,
                 "properties":{"name":{"type":"string"},"count":{"type":"integer"}},
                 "required":["name"]}
                """);

        assertInvalidParams(() -> json.requireFlatPrimitiveObjectValue(schema, json.parse("{\"extra\":1}")));
        assertInvalidParams(() -> json.requireFlatPrimitiveObjectValue(schema, json.parse("{\"count\":1}")));
        assertInvalidParams(() -> json.requireFlatPrimitiveObjectValue(schema, json.parse("{\"name\":1,\"count\":1}")));
        assertInvalidValueSchema("\"required\":1");
        assertInvalidValueSchema("\"required\":[\"name\",\"name\"]");
        assertInvalidValueSchema("\"required\":[1]");
        assertInvalidValueSchema("\"required\":[\"name\",\"count\"]");
    }

    private CanonicalPayload closedSchema(String properties) {
        return json.parse("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":" + properties + "}");
    }

    private void assertInvalidSchema(String schema) {
        assertInvalidParams(() -> json.requireFlatPrimitiveObjectSchema(json.parse(schema), 10));
    }

    private void assertInvalidValueSchema(String requiredField) {
        CanonicalPayload schema = json.parse("{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{\"name\":{\"type\":\"string\"}}," + requiredField + "}");
        assertInvalidParams(() -> json.requireFlatPrimitiveObjectValue(schema, json.parse("{\"name\":\"ok\"}")));
    }

    private static void assertInvalidParams(org.junit.jupiter.api.function.Executable executable) {
        ProtocolException failure = assertThrows(ProtocolException.class, executable);
        assertEquals(ProtocolErrorCode.INVALID_PARAMS, failure.code());
    }
}
