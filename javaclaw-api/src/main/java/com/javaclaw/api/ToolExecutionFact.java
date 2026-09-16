package com.javaclaw.api;

import java.util.Objects;

/**
 * 由平台副作用边界产生并随工具回执提交的执行事实。
 *
 * @param payload 命令、文件或目录变更事实，不接受任意扩展 Item
 */
public record ToolExecutionFact(ItemPayload payload) {
    /** 校验事实只包含平台批准的种类。 */
    public ToolExecutionFact {
        Objects.requireNonNull(payload, "payload");
        if (!(payload instanceof CorePayloads.Command)
                && !(payload instanceof CorePayloads.FileChange)
                && !(payload instanceof DirectoryChange)) {
            throw new IllegalArgumentException("工具执行事实只接受 Command、FileChange 或 DirectoryChange");
        }
    }

    /** @return 对应 Core 展示类别 */
    public String kind() {
        return payload instanceof CorePayloads.Command
                ? "command"
                : payload instanceof DirectoryChange ? "directory-change" : "file-change";
    }

    /** @return 对应的现有 Core Schema */
    public String schemaId() {
        return payload instanceof CorePayloads.Command
                ? CoreSchemas.COMMAND
                : payload instanceof DirectoryChange ? CoreSchemas.DIRECTORY_CHANGE : CoreSchemas.FILE_CHANGE;
    }
}
