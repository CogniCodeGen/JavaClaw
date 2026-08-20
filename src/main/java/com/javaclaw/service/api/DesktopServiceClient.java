package com.javaclaw.service.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Byte-oriented reverse RPC to explicitly registered Desktop services. Implementations must use
 * the authenticated control Socket; this contract never exposes Desktop objects to a plugin.
 */
public interface DesktopServiceClient {
    Call invoke(String serviceId, String operation, String contentType, byte[] payload,
                Duration timeout, Consumer<Event> events);

    record Event(long sequence, String contentType, byte[] payload) {
        public Event { payload = payload == null ? new byte[0] : payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    record Response(String contentType, byte[] payload) {
        public Response { payload = payload == null ? new byte[0] : payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    interface Call {
        String requestId();
        CompletableFuture<Response> completion();
        boolean cancel();
    }

    final class DesktopServiceException extends RuntimeException {
        private final String code;
        private final byte[] payload;

        public DesktopServiceException(String code, String message, byte[] payload, Throwable cause) {
            super(message, cause);
            this.code = code == null ? "host_service_failure" : code;
            this.payload = payload == null ? new byte[0] : payload.clone();
        }

        public String code() { return code; }
        public byte[] payload() { return payload.clone(); }
    }
}
