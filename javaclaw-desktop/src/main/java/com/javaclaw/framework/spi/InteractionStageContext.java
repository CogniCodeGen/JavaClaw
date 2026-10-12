package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.InteractionSurfaceEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/** Same-thread host contract binding; model arguments never supply stage predicates. */
public final class InteractionStageContext {
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private InteractionStageContext() { }

    public static Optional<Binding> current() { return Optional.ofNullable(CURRENT.get()); }

    public static Scope begin(long sequence, JsonNode contract, boolean browserInput) {
        Binding previous = CURRENT.get();
        CURRENT.set(new Binding(sequence, contract, browserInput));
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    public static String predicateSha256(JsonNode criterion) { return sha256(criterion.toString()); }

    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Binding {
        private final long sequence;
        private final JsonNode contract;
        private final String hash;
        private final boolean browserInput;
        private InteractionSurfaceEvent browserBefore;
        private JsonNode evidence;

        private Binding(long sequence, JsonNode contract, boolean browserInput) {
            if (sequence < 1 || contract == null || !contract.isObject()) throw new IllegalArgumentException("missing host contract");
            this.sequence = sequence;
            this.contract = contract.deepCopy();
            this.hash = sha256(contract.toString());
            this.browserInput = browserInput;
        }
        public long contractSequence() { return sequence; }
        public String contractSha256() { return hash; }
        public JsonNode contract() { return contract.deepCopy(); }
        public void browserBefore(InteractionSurfaceEvent identity) {
            if (browserInput && browserBefore == null) browserBefore = identity;
        }
        public Optional<InteractionSurfaceEvent> browserBefore() { return Optional.ofNullable(browserBefore); }
        public void observation(JsonNode value) { evidence = value == null ? null : value.deepCopy(); }
        public JsonNode evidence() { return evidence == null ? null : evidence.deepCopy(); }
    }

    @FunctionalInterface public interface Scope extends AutoCloseable { @Override void close(); }
}
