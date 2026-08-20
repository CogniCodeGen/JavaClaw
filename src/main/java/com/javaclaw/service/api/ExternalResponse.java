package com.javaclaw.service.api;

import java.util.Map;

public interface ExternalResponse {
    default boolean committed() { return false; }
    void send(int status, String contentType, Map<String, String> headers, byte[] body);
    void startStream(int status, String contentType, Map<String, String> headers);
    void stream(byte[] chunk);
    void closeStream();
}
