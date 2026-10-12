package com.javaclaw.memory.embed;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;

import java.time.Duration;

/** Test-only factory that keeps the package-private embedding transport seam encapsulated. */
public final class TestEmbeddingGatewayFactory {

    private TestEmbeddingGatewayFactory() {}

    @FunctionalInterface
    public interface Invoker {
        double[] embed(String text, Duration timeout) throws Exception;
    }

    public static Fixture create(int dimensions, Invoker invoker) {
        ManagedTaskExecutor executor = new ManagedTaskExecutor();
        TaskScope tasks = executor.openScope("knowledge-expert-test", 4);
        EmbeddingGateway gateway = new EmbeddingGateway(
                dimensions, EmbeddingHealthStatus.HEALTHY,
                invoker::embed, tasks);
        return new Fixture(gateway, tasks, executor);
    }

    public record Fixture(
            EmbeddingGateway gateway, TaskScope tasks,
            ManagedTaskExecutor executor) implements AutoCloseable {
        @Override
        public void close() {
            tasks.close();
            executor.close();
        }
    }
}
