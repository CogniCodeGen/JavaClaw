package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunRequest;

@FunctionalInterface
public interface BudgetPolicy {
    RunBudget restrict(RunBudget current, JsonNode configuration, RunRequest request);
}
