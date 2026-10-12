package com.javaclaw.framework.springai;

/** A selected tool set cannot fit beside the provider's required control schemas. */
final class ToolCountBudgetExceededException extends IllegalStateException {
    ToolCountBudgetExceededException(int requiredTools, int allowedTools, String tool) {
        super("planned business tool count requires " + requiredTools
                + " slots, above the available business capacity=" + allowedTools
                + " after reserving control tools, when adding " + tool);
    }
}
