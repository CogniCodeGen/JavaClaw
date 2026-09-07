package com.javaclaw.server.coding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

/** 在测试临时目录生成真实归档；畸形头只修改明确字段，其余 framing 保持可解析。 */
final class ToolchainArchiveFixtures {
    private ToolchainArchiveFixtures() {}

    static Entry file(String name, String content) {
        return new Entry(name, TarConstants.LF_NORMAL, content, "", 0644);
    }

    static Entry directory(String name) {
        return new Entry(name, TarConstants.LF_DIR, "", "", 0755);
    }

    static Entry link(String name, String target, boolean hard) {
        return new Entry(name, hard ? TarConstants.LF_LINK : TarConstants.LF_SYMLINK, "", target, 0777);
    }

    static Path zip(Path parent, List<Entry> entries) throws Exception {
        Path archive = Files.createTempFile(parent, "fixture-", ".zip");
        try (var output = new ZipArchiveOutputStream(archive)) {
            for (Entry entry : entries) {
                var item = new ZipArchiveEntry(entry.name());
                int type = entry.kind() == TarConstants.LF_SYMLINK
                        ? 0120000
                        : entry.kind() == TarConstants.LF_DIR
                                ? 0040000
                                : entry.kind() == TarConstants.LF_NORMAL ? 0100000 : 0010000;
                item.setUnixMode(type | entry.mode());
                output.putArchiveEntry(item);
                output.write((entry.kind() == TarConstants.LF_SYMLINK ? entry.target() : entry.content())
                        .getBytes(StandardCharsets.UTF_8));
                output.closeArchiveEntry();
            }
        }
        return archive;
    }

    static Path tar(Path parent, List<Entry> entries, boolean xz) throws Exception {
        Path archive = Files.createTempFile(parent, "fixture-", xz ? ".tar.xz" : ".tar.gz");
        try (var compressed = xz
                        ? new XZOutputStream(Files.newOutputStream(archive), new LZMA2Options())
                        : new GZIPOutputStream(Files.newOutputStream(archive));
                var output = new TarArchiveOutputStream(compressed)) {
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Entry entry : entries) {
                var item = new TarArchiveEntry(entry.name(), entry.kind());
                item.setMode(entry.mode());
                item.setLinkName(entry.target());
                byte[] bytes = entry.content().getBytes(StandardCharsets.UTF_8);
                item.setSize(bytes.length);
                output.putArchiveEntry(item);
                output.write(bytes);
                output.closeArchiveEntry();
            }
        }
        return archive;
    }

    static void centralField(Path archive, int relativeOffset, int value, boolean word) throws IOException {
        byte[] bytes = Files.readAllBytes(archive);
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset < bytes.length - 46; offset++) {
            if (buffer.getInt(offset) == 0x02014b50) {
                if (word) {
                    buffer.putShort(offset + relativeOffset, (short) value);
                } else {
                    buffer.putInt(offset + relativeOffset, value);
                }
                Files.write(archive, bytes);
                return;
            }
        }
        throw new IllegalArgumentException("fixture ZIP has no central directory");
    }

    static Path truncatedTar(Path parent) throws Exception {
        byte[] header = new byte[512];
        var entry = new TarArchiveEntry("bin/node");
        entry.setSize(1024);
        entry.writeEntryHeader(header);
        var raw = new ByteArrayOutputStream();
        raw.write(header);
        raw.write(new byte[7]);
        Path archive = Files.createTempFile(parent, "truncated-", ".tar.gz");
        try (var output = new GZIPOutputStream(Files.newOutputStream(archive))) {
            output.write(raw.toByteArray());
        }
        return archive;
    }

    record Entry(String name, byte kind, String content, String target, int mode) {}
}
