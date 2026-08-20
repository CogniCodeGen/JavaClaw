package com.javaclaw.application.serviceplugin;

import java.time.Instant;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Permission-checked byte boundary for service-plugin calls back into Desktop capabilities. */
public interface ServicePluginHostServiceRouter {
    Response invoke(Request request, BooleanSupplier cancellation, Consumer<Event> events)
            throws Exception;

    record Request(String pluginId, Set<String> grantedPermissions, String requestId,
                   String serviceId, String operation, String contentType, byte[] payload,
                   Instant deadline) {
        public Request {
            grantedPermissions = grantedPermissions == null ? Set.of() : Set.copyOf(grantedPermissions);
            payload = payload == null ? new byte[0] : payload.clone();
            deadline = deadline == null ? Instant.MAX : deadline;
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    record Event(String contentType, byte[] payload) {
        public Event { payload = payload == null ? new byte[0] : payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    record Response(String contentType, byte[] payload) {
        public Response { payload = payload == null ? new byte[0] : payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    final class HostServiceException extends RuntimeException {
        private final String code;
        private final byte[] payload;

        public HostServiceException(String code, String message) {
            this(code, message, new byte[0], null);
        }

        public HostServiceException(String code, String message, byte[] payload, Throwable cause) {
            super(message, cause);
            this.code = code == null ? "host_service_failure" : code;
            this.payload = payload == null ? new byte[0] : payload.clone();
        }

        public String code() { return code; }
        public byte[] payload() { return payload.clone(); }
    }
}
