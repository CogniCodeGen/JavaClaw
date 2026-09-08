package com.javaclaw.server.preview;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewTextTest {
    @Test
    void 相对资源链接在UTF8及带BOM的UTF16中保持一致() throws Exception {
        String markdown = "![图片](图片.png)";
        for (var charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            byte[] encoded = ("\ufeff" + markdown).getBytes(charset);
            assertEquals(markdown, PreviewText.decode(encoded));
            assertEquals(List.of("图片.png"), PreviewMarkdown.links(PreviewText.decode(encoded)));
        }
        assertEquals(markdown, PreviewText.decode(markdown.getBytes(StandardCharsets.UTF_8)));
        assertEquals("", PreviewText.decode(new byte[0]));
    }

    @Test
    void 拒绝不完整或非法编码且不把部分BOM解释成文本() {
        for (byte[] bytes : List.of(
                new byte[] {(byte) 0xef, (byte) 0xbb},
                new byte[] {(byte) 0xff, (byte) 0xfe, 0x41},
                new byte[] {(byte) 0xfe, (byte) 0xff, 0x41},
                new byte[] {(byte) 0xc3, 0x28})) {
            assertThrows(CharacterCodingException.class, () -> PreviewText.decode(bytes));
        }
    }
}
