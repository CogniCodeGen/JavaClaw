package com.javaclaw.model;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnId;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.NativeCompactionRequest;
import com.javaclaw.runtime.NativeCompactionResult;
import com.javaclaw.runtime.NativeCompactionSupport;
import com.javaclaw.runtime.NativeConversationSupport;
import com.javaclaw.runtime.ProviderState;

import static com.javaclaw.model.ModelAdapterTestFixtures.ENDPOINT_ID;
import static com.javaclaw.model.ModelAdapterTestFixtures.result;
import static com.javaclaw.model.ModelAdapterTestFixtures.simpleInvocation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelGatewayRouterTest {
    private static final ModelCapabilities CAPABILITIES =
            new ModelCapabilities(true, true, false, false, true, true, true);
    private static final ProviderState STATE =
            new ProviderState("provider", "v1", new CanonicalPayload("{\"state\":1}"));

    @Test
    void routesStandardConversationAndCompactionCalls() throws Exception {
        NativeGateway gateway = new NativeGateway();
        ModelGatewayRouter router =
                ModelGatewayRouter.builder().register(ENDPOINT_ID, gateway).build();
        CancellationSource cancellation = new CancellationSource();

        assertEquals(CAPABILITIES, router.capabilities(ENDPOINT_ID));
        assertSame(
                gateway.standard,
                router.invoke(TurnId.random(), simpleInvocation(), (turnId, event, token) -> {}, cancellation));
        assertSame(
                gateway.continued,
                router.invokeContinuing(
                        TurnId.random(), simpleInvocation(), STATE, (turnId, event, token) -> {}, cancellation));
        NativeCompactionResult compacted =
                router.compact(new NativeCompactionRequest(TurnId.random(), ENDPOINT_ID, STATE), cancellation);
        assertSame(gateway.compacted, compacted);
        assertEquals(3, gateway.invocations);
    }

    @Test
    void rejectsUnknownEndpointAndUnsupportedNativeCapabilities() {
        BasicGateway gateway = new BasicGateway();
        ModelGatewayRouter router =
                ModelGatewayRouter.builder().register(ENDPOINT_ID, gateway).build();
        CancellationSource cancellation = new CancellationSource();
        var unknown = new ModelInvocation("missing", "", List.of(), List.of(), 1);

        assertThrows(IllegalArgumentException.class, () -> router.capabilities("missing"));
        assertThrows(
                IllegalArgumentException.class,
                () -> router.invoke(TurnId.random(), unknown, (turnId, event, token) -> {}, cancellation));
        assertThrows(
                IllegalStateException.class,
                () -> router.invokeContinuing(
                        TurnId.random(), simpleInvocation(), STATE, (turnId, event, token) -> {}, cancellation));
        assertThrows(
                IllegalStateException.class,
                () -> router.compact(new NativeCompactionRequest(TurnId.random(), ENDPOINT_ID, STATE), cancellation));
    }

    @Test
    void validatesBuilderAndDoesNotReplaceDuplicateRoute() {
        BasicGateway gateway = new BasicGateway();
        assertThrows(
                IllegalStateException.class, () -> ModelGatewayRouter.builder().build());
        assertThrows(
                NullPointerException.class, () -> ModelGatewayRouter.builder().register(null, gateway));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModelGatewayRouter.builder().register(" ", gateway));
        assertThrows(
                NullPointerException.class, () -> ModelGatewayRouter.builder().register("id", null));

        ModelGatewayRouter.Builder builder = ModelGatewayRouter.builder().register(ENDPOINT_ID, gateway);
        assertThrows(IllegalArgumentException.class, () -> builder.register(ENDPOINT_ID, new BasicGateway()));
    }

    @Test
    void closesSharedGatewayOnceAndAggregatesDistinctFailures() throws Exception {
        BasicGateway shared = new BasicGateway();
        ModelGatewayRouter sharedRouter = ModelGatewayRouter.builder()
                .register("first", shared)
                .register("second", shared)
                .build();
        sharedRouter.close();
        assertEquals(1, shared.closeCount);

        BasicGateway first = new BasicGateway("first");
        BasicGateway second = new BasicGateway("second");
        ModelGatewayRouter failing = ModelGatewayRouter.builder()
                .register("first", first)
                .register("second", second)
                .build();
        Exception failure = assertThrows(Exception.class, failing::close);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(1, first.closeCount);
        assertEquals(1, second.closeCount);
    }

    private static class BasicGateway implements ModelGateway, AutoCloseable {
        private final Optional<String> closeFailure;
        private int closeCount;

        private BasicGateway() {
            this.closeFailure = Optional.empty();
        }

        private BasicGateway(String closeFailure) {
            this.closeFailure = Optional.of(closeFailure);
        }

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return CAPABILITIES;
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            return result();
        }

        @Override
        public void close() throws Exception {
            closeCount++;
            if (closeFailure.isPresent()) {
                throw new Exception(closeFailure.orElseThrow());
            }
        }
    }

    private static final class NativeGateway
            implements ModelGateway, NativeConversationSupport, NativeCompactionSupport {
        private final ModelInvocationResult standard = result();
        private final ModelInvocationResult continued = result();
        private final NativeCompactionResult compacted = new NativeCompactionResult(STATE, 7);
        private int invocations;

        @Override
        public ModelCapabilities capabilities(String modelId) {
            return CAPABILITIES;
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            invocations++;
            return standard;
        }

        @Override
        public ModelInvocationResult invokeContinuing(
                TurnId turnId,
                ModelInvocation invocation,
                ProviderState state,
                ModelEventSink events,
                CancellationToken cancellation) {
            invocations++;
            return continued;
        }

        @Override
        public NativeCompactionResult compact(NativeCompactionRequest request, CancellationToken cancellation) {
            invocations++;
            return compacted;
        }
    }
}
