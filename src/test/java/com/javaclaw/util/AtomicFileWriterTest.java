package com.javaclaw.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

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
    void 支持短文件名(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("a");
        AtomicFileWriter.writeString(target, "short-name");
        assertEquals("short-name", Files.readString(target));
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

    @Test
    void 同一目标并发写入不会临时文件冲突或产生半截内容(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("shared.txt");
        List<String> payloads = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> "payload-" + i + "-" + "x".repeat(2_000))
                .toList();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> writes = payloads.stream()
                    .<Callable<Void>>map(payload -> () -> {
                        AtomicFileWriter.writeString(target, payload);
                        return null;
                    })
                    .toList();
            for (var future : pool.invokeAll(writes)) future.get();
        }

        assertTrue(payloads.contains(Files.readString(target)), "最终文件必须等于某次完整写入");
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "成功后不应残留临时文件");
        }
    }
}
