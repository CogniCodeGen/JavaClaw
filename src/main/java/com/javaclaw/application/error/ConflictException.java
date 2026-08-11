package com.javaclaw.application.error;

/** 操作与当前领域状态或并发版本冲突。 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
