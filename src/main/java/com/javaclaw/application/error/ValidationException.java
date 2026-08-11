package com.javaclaw.application.error;

/** 输入不满足业务约束；调用方应展示可修正的校验信息。 */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
