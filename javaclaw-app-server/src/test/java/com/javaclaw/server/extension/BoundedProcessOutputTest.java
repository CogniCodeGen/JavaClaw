package com.javaclaw.server.extension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedProcessOutputTest {
    @Test
    void 输出未超过上限时完整保留() throws Exception {
        byte[] content = "知识解析完成".getBytes(StandardCharsets.UTF_8);

        BoundedProcessOutput.Capture capture =
                BoundedProcessOutput.read(new ByteArrayInputStream(content), content.length);

        assertEquals("知识解析完成", capture.text());
        assertFalse(capture.truncated());
    }

    @Test
    void 超出上限时继续排空输入但只保留允许字节() throws Exception {
        CountingInputStream input = new CountingInputStream("0123456789".getBytes(StandardCharsets.UTF_8));

        BoundedProcessOutput.Capture capture = BoundedProcessOutput.read(input, 4);

        assertEquals("0123", capture.text());
        assertTrue(capture.truncated());
        assertEquals(10, input.observedBytes);
    }

    @Test
    void 零长度读取不会结束排空循环() throws Exception {
        InputStream input = new InputStream() {
            private boolean returnedZero;
            private boolean returnedValue;

            @Override
            public int read() {
                throw new AssertionError("批量读取路径必须被使用");
            }

            @Override
            public int read(byte[] buffer) {
                if (!returnedZero) {
                    returnedZero = true;
                    return 0;
                }
                if (!returnedValue) {
                    returnedValue = true;
                    buffer[0] = 'x';
                    return 1;
                }
                return -1;
            }
        };

        assertEquals("x", BoundedProcessOutput.read(input, 1).text());
    }

    @Test
    void 非法上限空输入和空投影立即拒绝() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[0]);

        assertThrows(NullPointerException.class, () -> BoundedProcessOutput.read(null, 1));
        assertThrows(IllegalArgumentException.class, () -> BoundedProcessOutput.read(input, 0));
        assertThrows(
                IllegalArgumentException.class, () -> BoundedProcessOutput.read(input, (long) Integer.MAX_VALUE + 1));
        assertThrows(NullPointerException.class, () -> new BoundedProcessOutput.Capture(null, false));
    }

    private static final class CountingInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private int observedBytes;

        private CountingInputStream(byte[] content) {
            delegate = new ByteArrayInputStream(content);
        }

        @Override
        public int read() {
            int value = delegate.read();
            if (value >= 0) {
                observedBytes++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                observedBytes += count;
            }
            return count;
        }
    }
}
