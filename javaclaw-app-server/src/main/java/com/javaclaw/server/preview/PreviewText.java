package com.javaclaw.server.preview;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 与桌面 Markdown 预览一致的编码边界；只承认 UTF-8 或带 BOM 的 UTF-16，不猜测机器默认编码。 */
final class PreviewText {
    private PreviewText() {}

    static String decode(byte[] bytes) throws CharacterCodingException {
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (startsWith(bytes, 0xff, 0xfe)) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (startsWith(bytes, 0xfe, 0xff)) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        } else if (startsWith(bytes, 0xef, 0xbb) && bytes.length >= 3 && bytes[2] == (byte) 0xbf) {
            skip = 3;
        }
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip))
                .toString();
    }

    private static boolean startsWith(byte[] bytes, int first, int second) {
        return bytes.length >= 2 && bytes[0] == (byte) first && bytes[1] == (byte) second;
    }
}
