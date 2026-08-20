package com.javaclaw.service.api;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

public interface ManagedPluginExecutor {
    CompletionStage<Void> run(String name, Runnable task);
    <T> CompletionStage<T> call(String name, Callable<T> task);
    Registration scheduleAtFixedRate(String name, Duration initialDelay, Duration interval, Runnable task);
}
