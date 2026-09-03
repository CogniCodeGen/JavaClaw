package com.javaclaw.server.persistence;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;

/** 将调用方和服务内部取消信号合并为只读视图。 */
record CombinedCancellationToken(CancellationToken external, CancellationToken local) implements CancellationToken {
    CombinedCancellationToken {
        Objects.requireNonNull(external, "external");
        Objects.requireNonNull(local, "local");
    }

    @Override
    public boolean isCancelled() {
        return external.isCancelled() || local.isCancelled();
    }

    @Override
    public Optional<String> reason() {
        return external.reason().or(local::reason);
    }
}
