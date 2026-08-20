package com.javaclaw.application.serviceplugin;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Binary-safe internal service route. External client traffic never enters this port. */
public interface ServicePluginInvocationPort {
    Invocation invoke(String pluginId, String serviceId, String operation,
                      String contentType, byte[] payload, Duration timeout,
                      Consumer<Event> events);
    boolean cancel(String pluginId, String requestId);

    record Event(long sequence, String contentType, byte[] payload) {
        public Event { payload = payload == null ? new byte[0] : payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    record Response(String contentType, byte[] payload) {
        public Response { payload = payload == null ? new byte[0] : payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    interface Invocation {
        String requestId();
        CompletableFuture<Response> completion();
        boolean cancel();
    }

    final class ServicePluginException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        private final byte[] payload;

        public ServicePluginException(String code, String message, boolean retryable) {
            this(code, message, retryable, new byte[0], null);
        }

        public ServicePluginException(String code, String message, boolean retryable,
                                      byte[] payload, Throwable cause) {
            super(message, cause);
            this.code = code == null || code.isBlank() ? "service_plugin_error" : code;
            this.retryable = retryable;
            this.payload = payload == null ? new byte[0] : payload.clone();
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public byte[] payload() { return payload.clone(); }
    }
}
