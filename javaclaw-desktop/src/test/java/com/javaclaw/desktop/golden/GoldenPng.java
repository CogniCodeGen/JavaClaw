package com.javaclaw.desktop.golden;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

final class GoldenPng {
    private static final byte[] SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};

    private GoldenPng() {}

    static void write(WritableImage image, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(target))) {
            output.write(SIGNATURE);
            writeChunk(output, "IHDR", header(image));
            writeChunk(output, "IDAT", pixels(image));
            writeChunk(output, "IEND", new byte[0]);
        }
    }

    static Dimensions readDimensions(Path source) throws IOException {
        byte[] header = Files.readAllBytes(source);
        if (header.length < 24 || !Arrays.equals(SIGNATURE, Arrays.copyOf(header, SIGNATURE.length))) {
            throw new IOException("不是有效的 PNG 文件: " + source);
        }
        ByteBuffer bytes = ByteBuffer.wrap(header);
        return new Dimensions(bytes.getInt(16), bytes.getInt(20));
    }

    private static byte[] header(WritableImage image) {
        return ByteBuffer.allocate(13)
                .putInt((int) image.getWidth())
                .putInt((int) image.getHeight())
                .put((byte) 8)
                .put((byte) 6)
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array();
    }

    private static byte[] pixels(WritableImage image) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        PixelReader reader = image.getPixelReader();
        byte[] bgra = new byte[width * 4];
        byte[] row = new byte[1 + width * 4];
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            for (int y = 0; y < height; y++) {
                reader.getPixels(0, y, width, 1, PixelFormat.getByteBgraInstance(), bgra, 0, width * 4);
                convertRow(bgra, row);
                deflater.write(row);
            }
        }
        return compressed.toByteArray();
    }

    private static void convertRow(byte[] bgra, byte[] rgba) {
        rgba[0] = 0;
        for (int source = 0, target = 1; source < bgra.length; source += 4, target += 4) {
            rgba[target] = bgra[source + 2];
            rgba[target + 1] = bgra[source + 1];
            rgba[target + 2] = bgra[source];
            rgba[target + 3] = bgra[source + 3];
        }
    }

    private static void writeChunk(DataOutputStream output, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 checksum = new CRC32();
        checksum.update(typeBytes);
        checksum.update(data);
        output.writeInt(data.length);
        output.write(typeBytes);
        output.write(data);
        output.writeInt((int) checksum.getValue());
    }

    record Dimensions(int width, int height) {}
}
