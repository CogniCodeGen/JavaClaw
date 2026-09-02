package com.javaclaw.server.instructions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

import com.javaclaw.api.InstructionScope;
import com.javaclaw.api.InstructionSourceResolution;

/** 以 UTF-8 边界和有界内存读取单个约定文件。 */
final class InstructionFileReader {
    private static final int MAX_UTF8_BYTES = 4;

    ReadResult read(InstructionScope scope, Path file, String relativePath, int remainingBytes) {
        if (remainingBytes < 0) {
            throw new IllegalArgumentException("remainingBytes must not be negative");
        }
        long observedBytes = 0;
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            observedBytes = attributes.size();
            if (attributes.isSymbolicLink()) {
                return failed(scope, relativePath, observedBytes, "SYMLINK_REJECTED");
            }
            if (!attributes.isRegularFile()) {
                return failed(scope, relativePath, observedBytes, "NOT_REGULAR_FILE");
            }
            return readRegular(scope, file, relativePath, remainingBytes, observedBytes);
        } catch (CharacterCodingException failure) {
            return failed(scope, relativePath, observedBytes, "INVALID_UTF8");
        } catch (IOException | SecurityException failure) {
            return failed(scope, relativePath, observedBytes, "READ_FAILED");
        }
    }

    private ReadResult readRegular(
            InstructionScope scope, Path file, String relativePath, int remainingBytes, long observedBytes)
            throws IOException {
        int readLimit = Math.addExact(remainingBytes, MAX_UTF8_BYTES);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(readLimit);
        }
        long byteCount = Math.max(observedBytes, bytes.length);
        boolean truncated = byteCount > remainingBytes || bytes.length > remainingBytes;
        int candidateLength = Math.min(bytes.length, remainingBytes);
        try {
            Decoded decoded = decode(bytes, candidateLength, truncated);
            InstructionSourceResolution source = new InstructionSourceResolution(
                    scope,
                    relativePath,
                    Optional.of(digest(bytes, decoded.byteLength())),
                    byteCount,
                    decoded.byteLength(),
                    truncated,
                    Optional.empty());
            return new ReadResult(source, decoded.content());
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static Decoded decode(byte[] bytes, int candidateLength, boolean truncated)
            throws CharacterCodingException {
        int attempts = truncated ? Math.min(MAX_UTF8_BYTES, candidateLength + 1) : 1;
        CharacterCodingException lastFailure = null;
        for (int backoff = 0; backoff < attempts; backoff++) {
            int length = candidateLength - backoff;
            try {
                var decoder = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                String content =
                        decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
                return new Decoded(content, length);
            } catch (CharacterCodingException failure) {
                lastFailure = failure;
            }
        }
        throw lastFailure == null ? new CharacterCodingException() : lastFailure;
    }

    private static String digest(byte[] bytes, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, 0, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static ReadResult failed(InstructionScope scope, String relativePath, long byteCount, String errorCode) {
        InstructionSourceResolution source = new InstructionSourceResolution(
                scope, relativePath, Optional.empty(), Math.max(byteCount, 0), 0, false, Optional.of(errorCode));
        return new ReadResult(source, "");
    }

    record ReadResult(InstructionSourceResolution source, String content) {
        ReadResult {
            java.util.Objects.requireNonNull(source, "source");
            java.util.Objects.requireNonNull(content, "content");
        }
    }

    private record Decoded(String content, int byteLength) {}
}
