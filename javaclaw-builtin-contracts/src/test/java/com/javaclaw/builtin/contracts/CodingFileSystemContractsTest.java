package com.javaclaw.builtin.contracts;

import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.CodingFileSystemContracts.Encoding;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts.FileReadBinaryResult;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts.FileTransfer;
import com.javaclaw.builtin.contracts.CodingFileSystemContracts.FileWrite;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodingFileSystemContractsTest {
    @Test
    void 二进制零字节和非法UTF8字节往返不被文本解码改变() {
        byte[] bytes = {0, (byte) 255, (byte) 128, 13, 10};
        String encoded = Base64.getEncoder().encodeToString(bytes);
        FileWrite write = new FileWrite("数据.bin", encoded, Encoding.BASE64, Optional.empty());
        var json = new CanonicalJson();
        assertEquals(write, json.decode(json.encode(write), FileWrite.class));
        assertArrayEquals(bytes, write.bytes());
        var result = new FileReadBinaryResult("数据.bin", encoded, "a".repeat(64), 10, 2, 7, true);
        assertEquals(result, json.decode(json.encode(result), FileReadBinaryResult.class));
    }

    @Test
    void 拒绝非规范Base64和按原始字节超限的中文内容() {
        assertThrows(IllegalArgumentException.class, () -> new FileWrite("a", "AA", Encoding.BASE64, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FileWrite("a", "中".repeat(400_000), Encoding.UTF8, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new FileWrite("a", "?", Encoding.BASE64, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class, () -> new FileWrite("a", "\ud800", Encoding.UTF8, Optional.empty()));
    }

    @Test
    void 禁止覆盖源路径逃逸根及不一致的二进制页游标() {
        assertThrows(IllegalArgumentException.class, () -> new FileTransfer("a", "a", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new FileTransfer("a", "../b", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new FileWrite(".", "", Encoding.UTF8, Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FileReadBinaryResult("a", "AA==", "a".repeat(64), 10, 0, 2, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FileReadBinaryResult("a", "AA==", "a".repeat(64), 1, 0, 1, true));
    }
}
