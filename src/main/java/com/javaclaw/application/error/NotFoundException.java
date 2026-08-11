package com.javaclaw.application.error;

/** 请求的领域对象不存在或已被删除。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
