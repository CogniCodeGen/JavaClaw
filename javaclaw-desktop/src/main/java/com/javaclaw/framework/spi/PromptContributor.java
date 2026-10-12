package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunRequest;

@FunctionalInterface
public interface PromptContributor {
    String contribute(RunRequest request, ExtensionStateView state);
}
