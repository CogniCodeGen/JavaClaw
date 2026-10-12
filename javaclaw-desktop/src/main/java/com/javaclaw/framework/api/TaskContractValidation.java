package com.javaclaw.framework.api;

/** 本地固定契约验收的引用键；引用仍须由宿主独立校验来源、范围和摘要。 */
public final class TaskContractValidation {
    public static final String REFERENCE_ATTRIBUTE = "framework.interaction.validation.contractReference";
    public static final String SOURCE_PROPERTY = "javaclaw.interaction.validation.contract-source-run";
    public static final String SEQUENCE_PROPERTY = "javaclaw.interaction.validation.contract-sequence";
    public static final String SHA256_PROPERTY = "javaclaw.interaction.validation.contract-sha256";

    private TaskContractValidation() { }
}
