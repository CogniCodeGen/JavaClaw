package com.javaclaw.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AtomicFileWriter} 原子写入回归测试 */
class AtomicFileWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 首次写入创建文件且无tmp残留(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("out.json");
        AtomicFileWriter.writeString(target, "{\"a\":1}");
        assertEquals("{\"a\":1}", Files.readString(target));
        assertFalse(Files.exists(dir.resolve("out.json.tmp")), "写入完成后不应残留 tmp 文件");
    }

    @Test
    void 覆盖写入替换旧内容(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("out.json");
        AtomicFileWriter.writeString(target, "old");
        AtomicFileWriter.writeString(target, "new");
        assertEquals("new", Files.readString(target));
    }

    @Test
    void 自动创建父目录(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("sub/deeper/out.json");
        AtomicFileWriter.writeString(target, "x");
        assertEquals("x", Files.readString(target));
    }

    @Test
    void writeJson序列化往返一致(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("data.json");
        Map<String, Object> value = Map.of("name", "测试", "list", List.of(1, 2, 3));
        AtomicFileWriter.writeJson(mapper, target.toFile(), value);
        @SuppressWarnings("unchecked")
        Map<String, Object> back = mapper.readValue(target.toFile(), Map.class);
        assertEquals("测试", back.get("name"));
        assertEquals(List.of(1, 2, 3), back.get("list"));
    }

    @Test
    void writeJson支持prettyPrinter(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("pretty.json");
        AtomicFileWriter.writeJson(mapper.writerWithDefaultPrettyPrinter(), target.toFile(), Map.of("k", "v"));
        assertTrue(Files.readString(target).contains("\n"), "pretty 输出应有换行");
    }
}
