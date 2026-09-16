package com.javaclaw.server.coding;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodingOutputTextTest {
    @Test
    void UTF8和系统代码页逐字节分页只在完整字符末尾输出一次() {
        for (Charset encoding : List.of(StandardCharsets.UTF_8, Charset.forName("GBK"), Charset.forName("IBM437"))) {
            String original = encoding.equals(Charset.forName("IBM437")) ? "café £\r\n" : "中文 café\r\n";
            byte[] bytes = original.getBytes(encoding);
            var result = new StringBuilder();
            for (int offset = 0; offset < bytes.length; offset++) {
                result.append(CodingOutputText.slice(bytes, offset, offset + 1, encoding));
            }
            assertEquals(original, result.toString(), encoding.name());
        }
    }

    @Test
    void 有界输出末尾不猜测尚未完成的多字节字符() {
        byte[] bytes = "中".getBytes(StandardCharsets.UTF_8);
        assertEquals("", CodingOutputText.slice(bytes, 0, 2));
        assertEquals("中", CodingOutputText.slice(bytes, 2, 3));
        assertEquals("", CodingOutputText.slice(bytes, 3, 3));
    }
}
