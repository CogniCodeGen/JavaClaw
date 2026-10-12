package com.javaclaw.plugins.deliverance.internal;


public interface ServiceResponseChannel {
    void event(String contentType, byte[] payload);
    void complete(String contentType, byte[] payload);
    void fail(String errorCode, String message, String contentType, byte[] payload);
    void cancelled(String contentType, byte[] payload);
}
