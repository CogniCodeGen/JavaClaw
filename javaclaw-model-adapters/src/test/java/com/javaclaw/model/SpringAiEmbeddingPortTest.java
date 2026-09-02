package com.javaclaw.model;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPurpose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiEmbeddingPortTest {
    @Test
    void batchesRequestsValidatesDimensionsAndClosesOwnedClient() throws Exception {
        RecordingModel model = new RecordingModel();
        AtomicBoolean closed = new AtomicBoolean();
        SpringAiEmbeddingPort port =
                new SpringAiEmbeddingPort(model.proxy(), "a".repeat(64), OptionalInt.of(2), () -> closed.set(true));
        List<String> texts = java.util.stream.IntStream.range(0, 129)
                .mapToObj(index -> "text-" + index)
                .toList();

        EmbeddingBatch result = port.embed(texts, EmbeddingPurpose.DOCUMENT, new CancellationSource());
        port.close();

        assertEquals(List.of(128, 1), model.batchSizes);
        assertEquals(129, result.vectors().size());
        assertEquals(2, result.dimensions());
        assertTrue(closed.get());
    }

    @Test
    void rejectsConfiguredDimensionMismatchAndHonorsPreCallCancellation() {
        SpringAiEmbeddingPort mismatch =
                new SpringAiEmbeddingPort(new RecordingModel().proxy(), "b".repeat(64), OptionalInt.of(3), () -> {});
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel("test cancelled");

        assertThrows(
                IllegalStateException.class,
                () -> mismatch.embed(List.of("text"), EmbeddingPurpose.QUERY, new CancellationSource()));
        assertThrows(
                TurnCancelledException.class, () -> mismatch.embed(List.of("text"), EmbeddingPurpose.QUERY, cancelled));
    }

    private static final class RecordingModel implements InvocationHandler {
        private final List<Integer> batchSizes = new java.util.ArrayList<>();

        private EmbeddingModel proxy() {
            return (EmbeddingModel) Proxy.newProxyInstance(
                    EmbeddingModel.class.getClassLoader(), new Class<?>[] {EmbeddingModel.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getName().equals("embed")
                    && arguments != null
                    && arguments.length == 1
                    && arguments[0] instanceof List<?> texts) {
                batchSizes.add(texts.size());
                return texts.stream().map(ignored -> new float[] {1, 0}).toList();
            }
            throw new AssertionError("SpringAiEmbeddingPort must use the batch embed API: " + method);
        }
    }
}
