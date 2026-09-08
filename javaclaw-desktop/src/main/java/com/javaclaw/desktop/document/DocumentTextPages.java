package com.javaclaw.desktop.document;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.javaclaw.api.DocumentChunk;
import com.javaclaw.api.DocumentPreview;

/** 有界的顺序文本解码器；保留跨块字符、真实行号和长行片段，末块校验完整摘要。单个后台读取任务拥有本实例。 */
final class DocumentTextPages {
    private final DocumentPreview preview;
    private final DocumentPreviewGateway gateway;
    private final MessageDigest digest;
    private final StringBuilder remaining = new StringBuilder();
    private CharsetDecoder decoder;
    private byte[] tail = new byte[0];
    private long offset;
    private long line = 1;
    private boolean continued;
    private boolean ended;

    DocumentTextPages(DocumentPreview preview, DocumentPreviewGateway gateway) {
        this.preview = preview;
        this.gateway = gateway;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    Page next() throws Exception {
        ArrayList<Line> lines = new ArrayList<>();
        while (lines.size() < 500 && (!ended || !remaining.isEmpty())) {
            int newline = remaining.indexOf("\n");
            if (newline < 0 && remaining.length() < 16_384 && !ended) {
                read();
            } else {
                takeLine(lines, newline);
            }
        }
        return new Page(List.copyOf(lines), ended && remaining.isEmpty());
    }

    private void takeLine(List<Line> lines, int newline) {
        int length = segmentLength(newline < 0 ? remaining.length() : newline);
        if (length > 0 && Character.isHighSurrogate(remaining.charAt(length - 1)) && length < remaining.length()) {
            length--;
        }
        String text = remaining.substring(0, length);
        boolean endOfLine = newline == length || ended && length == remaining.length();
        lines.add(new Line(
                line, endOfLine && text.endsWith("\r") ? text.substring(0, text.length() - 1) : text, continued));
        remaining.delete(0, length + (newline == length ? 1 : 0));
        continued = !endOfLine;
        if (endOfLine) {
            line++;
        }
    }

    private int segmentLength(int end) {
        int bytes = 0;
        int index = 0;
        while (index < end) {
            int point = Character.codePointAt(remaining, index);
            int width = point < 0x80 ? 1 : point < 0x800 ? 2 : point < 0x10000 ? 3 : 4;
            if (bytes + width > 16_384) {
                break;
            }
            bytes += width;
            index += Character.charCount(point);
        }
        return index;
    }

    private void read() throws Exception {
        DocumentChunk chunk =
                gateway.read(preview.handleId(), offset).toCompletableFuture().get();
        if (chunk.offsetBytes() != offset
                || !chunk.digest().equals(preview.digest())
                || chunk.nextOffsetBytes() > preview.sizeBytes()) {
            throw new IllegalStateException("文档分块版本或游标不一致");
        }
        byte[] bytes = chunk.content();
        if (chunk.nextOffsetBytes() != offset + bytes.length || bytes.length == 0 && !chunk.complete()) {
            throw new IllegalStateException("文档分块未推进");
        }
        digest.update(bytes);
        offset = chunk.nextOffsetBytes();
        decode(bytes, chunk.complete());
        ended = chunk.complete();
        if (ended
                && (offset != preview.sizeBytes()
                        || !HexFormat.of().formatHex(digest.digest()).equals(preview.digest()))) {
            throw new IllegalStateException("文档内容校验失败");
        }
    }

    private void decode(byte[] bytes, boolean end) throws CharacterCodingException {
        ByteBuffer input = ByteBuffer.allocate(tail.length + bytes.length);
        input.put(tail).put(bytes).flip();
        if (decoder == null && input.remaining() < 3 && !end) {
            tail = new byte[input.remaining()];
            input.get(tail);
            return;
        }
        if (decoder == null) {
            decoder = encoding(input)
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
        }
        CharBuffer output = CharBuffer.allocate(input.remaining() + 2);
        var result = decoder.decode(input, output, end);
        if (result.isError()) {
            result.throwException();
        }
        output.flip();
        remaining.append(output);
        tail = new byte[input.remaining()];
        input.get(tail);
    }

    private static java.nio.charset.Charset encoding(ByteBuffer bytes) {
        if (bytes.remaining() >= 2 && bytes.get(0) == (byte) 0xff && bytes.get(1) == (byte) 0xfe) {
            bytes.position(2);
            return StandardCharsets.UTF_16LE;
        }
        if (bytes.remaining() >= 2 && bytes.get(0) == (byte) 0xfe && bytes.get(1) == (byte) 0xff) {
            bytes.position(2);
            return StandardCharsets.UTF_16BE;
        }
        if (bytes.remaining() >= 3
                && bytes.get(0) == (byte) 0xef
                && bytes.get(1) == (byte) 0xbb
                && bytes.get(2) == (byte) 0xbf) {
            bytes.position(3);
        }
        return StandardCharsets.UTF_8;
    }

    record Line(long number, String text, boolean continued) {}

    record Page(List<Line> lines, boolean complete) {
        String text() {
            StringBuilder text = new StringBuilder();
            for (Line line : lines) {
                if (!line.continued() && !text.isEmpty()) {
                    text.append('\n');
                }
                text.append(line.text());
            }
            return text.toString();
        }
    }
}
