package com.javaclaw.framework.springai;

/** A selected tool set cannot fit in the provider's configured schema budget. */
final class ToolSchemaBudgetExceededException extends IllegalStateException {
    private final long requiredCharacters;
    private final int allowedCharacters;

    ToolSchemaBudgetExceededException(long requiredCharacters, int allowedCharacters,
                                      String tool) {
        super("planned tool schemas require " + requiredCharacters
                + " characters, above maxToolSchemaCharacters=" + allowedCharacters
                + " when adding " + tool);
        this.requiredCharacters = requiredCharacters;
        this.allowedCharacters = allowedCharacters;
    }

    long requiredCharacters() { return requiredCharacters; }
    int allowedCharacters() { return allowedCharacters; }
}
