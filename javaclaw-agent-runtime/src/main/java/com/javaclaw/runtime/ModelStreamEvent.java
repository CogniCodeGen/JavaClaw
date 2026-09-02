package com.javaclaw.runtime;

import java.util.Objects;

/** 模型流事件；ToolCall 只在参数完整后发布。 */
public sealed interface ModelStreamEvent
        permits ModelStreamEvent.TextDelta,
                ModelStreamEvent.ReasoningSummaryDelta,
                ModelStreamEvent.ToolCallReady,
                ModelStreamEvent.Usage {
    /**
     * 可见文本增量。
     *
     * @param text 非空片段
     */
    record TextDelta(String text) implements ModelStreamEvent {
        /** 校验片段。 */
        public TextDelta {
            text = required(text, "text");
        }
    }

    /**
     * reasoning summary 增量，不包含隐藏推理链。
     *
     * @param text 非空片段
     */
    record ReasoningSummaryDelta(String text) implements ModelStreamEvent {
        /** 校验片段。 */
        public ReasoningSummaryDelta {
            text = required(text, "text");
        }
    }

    /**
     * 完整工具调用事件。
     *
     * @param call 调用
     */
    record ToolCallReady(ModelToolCall call) implements ModelStreamEvent {
        /** 校验调用。 */
        public ToolCallReady {
            Objects.requireNonNull(call, "call");
        }
    }

    /**
     * 最新 usage 快照。
     *
     * @param value usage
     */
    record Usage(ModelUsage value) implements ModelStreamEvent {
        /** 校验 usage。 */
        public Usage {
            Objects.requireNonNull(value, "value");
        }
    }

    private static String required(String value, String name) {
        String normalized = Objects.requireNonNull(value, name);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return normalized;
    }
}
