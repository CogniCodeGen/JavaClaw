package com.javaclaw.client.cli;

import java.io.Reader;
import java.io.StringReader;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliInputReaderTest {
    @Test
    void 有界读取丢弃超长行且保留下一行归属() throws Exception {
        try (var reader = new CliInputReader(new StringReader("中".repeat(30_000) + "\napprove\n"))) {
            reader.request(7);
            assertThrows(IllegalStateException.class, () -> reader.request(8));
            var oversized = await(reader);
            assertEquals(CliInputReader.Kind.TOO_LONG, oversized.kind());
            assertEquals(7, oversized.epoch());
            reader.request(8);
            var next = await(reader);
            assertEquals("approve", next.text());
            assertEquals(8, next.epoch());
        }
    }

    @Test
    void EOF与读取故障有独立结果() throws Exception {
        try (var reader = new CliInputReader(Reader.nullReader())) {
            reader.request(1);
            assertEquals(CliInputReader.Kind.EOF, await(reader).kind());
        }
        StringReader closed = new StringReader("");
        closed.close();
        try (var reader = new CliInputReader(closed)) {
            reader.request(2);
            assertEquals(CliInputReader.Kind.ERROR, await(reader).kind());
        }
    }

    private static CliInputReader.Answer await(CliInputReader reader) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        CliInputReader.Answer result;
        while ((result = reader.poll()) == null && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(result != null, "终端 reader 未完成");
        return result;
    }
}
