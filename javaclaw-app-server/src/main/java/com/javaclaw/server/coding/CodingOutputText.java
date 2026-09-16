package com.javaclaw.server.coding;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 按原始字节分页解码；字符仅在最后一个字节所在页输出。非 UTF-8 编码使用完整有界前缀恢复解码状态。 */
final class CodingOutputText {
    private CodingOutputText() {}

    static String decode(byte[] prefix, byte[] content) {
        return decode(prefix, content, StandardCharsets.UTF_8);
    }

    static String decode(byte[] prefix, byte[] content, Charset encoding) {
        if (content.length == 0) {
            return "";
        }
        byte[] combined = Arrays.copyOf(prefix, prefix.length + content.length);
        System.arraycopy(content, 0, combined, prefix.length, content.length);
        var input = ByteBuffer.wrap(combined);
        var decoder = encoding.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        input.limit(prefix.length);
        // 丢弃已在此前页面完整输出的字符；不完整序列仍留在 ByteBuffer，随后与本页字节一起解码。
        decoder.decode(input, CharBuffer.allocate(prefix.length), false);
        input.limit(combined.length);
        var output = CharBuffer.allocate(combined.length);
        decoder.decode(input, output, false);
        output.flip();
        return output.toString();
    }

    static String slice(byte[] content, long start, long end) {
        return slice(content, start, end, StandardCharsets.UTF_8);
    }

    static String slice(byte[] content, long start, long end, Charset encoding) {
        int from = (int) Math.min(content.length, start);
        int to = (int) Math.min(content.length, end);
        int prefixStart = encoding.equals(StandardCharsets.UTF_8) ? Math.max(0, from - 3) : 0;
        return decode(Arrays.copyOfRange(content, prefixStart, from), Arrays.copyOfRange(content, from, to), encoding);
    }
}
