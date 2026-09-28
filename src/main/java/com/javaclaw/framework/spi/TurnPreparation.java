package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunRequest;

/** Once-per-Turn preparation whose result is journaled before on-demand Step planning. */
public interface TurnPreparation {
    String id();

    String prepare(RunRequest request, ExtensionStateView state);
}
