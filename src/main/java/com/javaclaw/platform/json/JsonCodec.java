package com.javaclaw.platform.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * 共享 JSON 边界，避免业务代码各自创建和配置 ObjectMapper。
 *
 * <p>实例线程安全；编码和解码不持有输入流。格式错误以 Jackson 的受检异常向调用方报告，
 * 不静默返回空对象。</p>
 */
public final class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
    }

    public String encode(Object value) throws JsonProcessingException {
        return mapper.writeValueAsString(value);
    }

    public String encodePretty(Object value) throws JsonProcessingException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    public <T> T decode(String json, Class<T> type) throws JsonProcessingException {
        return mapper.readValue(json, type);
    }

    public <T> T decode(String json, TypeReference<T> type) throws JsonProcessingException {
        return mapper.readValue(json, type);
    }

    public <T> T decode(InputStream input, Class<T> type) throws IOException {
        return mapper.readValue(input, type);
    }

    public JsonNode tree(String json) throws JsonProcessingException {
        return mapper.readTree(json);
    }

    /** 供需要 Jackson reader/writer 高级能力的基础设施适配器使用。 */
    public ObjectMapper mapper() {
        return mapper;
    }
}
