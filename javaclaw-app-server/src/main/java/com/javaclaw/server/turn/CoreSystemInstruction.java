package com.javaclaw.server.turn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 读取随发行版审阅、版本化的 Core system instruction。 */
public final class CoreSystemInstruction {
    /** Prompt manifest 使用的稳定 Core instruction revision。 */
    public static final String REVISION = "core-system-v6";

    private static final String RESOURCE = "/prompts/core-system-v6.txt";

    private CoreSystemInstruction() {}

    /**
     * 读取内置说明。
     *
     * @return 非空 UTF-8 文本
     */
    public static String load() {
        try (InputStream input = CoreSystemInstruction.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("missing system instruction: " + RESOURCE);
            }
            String value = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (value.isEmpty()) {
                throw new IllegalStateException("system instruction must not be blank");
            }
            return value;
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read system instruction", failure);
        }
    }
}
