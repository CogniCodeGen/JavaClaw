package com.javaclaw.server.coding;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 字节窗口在完整 UTF-8 字符边界推进，不能将分页切断的中文或 emoji 判为二进制。 */
record CodingUtf8Window(String text, int start, int consumed, boolean binary) {
    static CodingUtf8Window decode(byte[] bytes, long offset, boolean endOfFile) {
        int start = 0;
        if (offset > 0) {
            while (start < Math.min(3, bytes.length) && (bytes[start] & 0xc0) == 0x80) {
                start++;
            }
        }
        for (byte value : bytes) {
            if (value == 0) {
                return new CodingUtf8Window("", 0, bytes.length, true);
            }
        }
        var input = ByteBuffer.wrap(bytes);
        input.position(start);
        var output = CharBuffer.allocate(bytes.length);
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var result = decoder.decode(input, output, endOfFile);
        if (result.isError()) {
            return new CodingUtf8Window("", 0, bytes.length, true);
        }
        if (!endOfFile && input.position() == 0 && bytes.length > 0) {
            throw new IllegalArgumentException("UTF8_WINDOW_TOO_SMALL: 请使用至少四字节的文件读取窗口");
        }
        output.flip();
        return new CodingUtf8Window(output.toString(), start, input.position(), false);
    }
}
