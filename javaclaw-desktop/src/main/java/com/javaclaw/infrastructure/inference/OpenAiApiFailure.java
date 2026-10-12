package com.javaclaw.infrastructure.inference;

/** OpenAI 兼容边界内部使用的结构化请求错误。 */
final class OpenAiApiFailure extends RuntimeException {
    final int status;
    final String type;
    final String code;
    final String parameter;

    OpenAiApiFailure(int status, String type, String code, String message, String parameter) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.parameter = parameter;
    }
}
