package com.javaclaw.ui.javafx.settings;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Reusable navigation contract for profile parameter flows opened from different workbenches. */
final class InferenceProfileLoadFlow {
    private final Runnable cancel;
    private final Consumer<Result> complete;

    private InferenceProfileLoadFlow(Runnable cancel, Consumer<Result> complete) {
        this.cancel = Objects.requireNonNull(cancel, "cancel");
        this.complete = Objects.requireNonNull(complete, "complete");
    }

    static InferenceProfileLoadFlow of(Runnable cancel, Consumer<Result> complete) {
        return new InferenceProfileLoadFlow(cancel, complete);
    }

    static InferenceProfileLoadFlow none() {
        return of(() -> { }, ignored -> { });
    }

    void cancel() { cancel.run(); }
    void complete(Result result) { complete.accept(Objects.requireNonNull(result, "result")); }

    record Result(UUID profileId, boolean loaded, String failure) {
        Result {
            Objects.requireNonNull(profileId, "profileId");
            failure = failure == null ? "" : failure.strip();
            if (loaded && !failure.isBlank()) {
                throw new IllegalArgumentException("已加载结果不能包含失败信息");
            }
        }
    }
}
