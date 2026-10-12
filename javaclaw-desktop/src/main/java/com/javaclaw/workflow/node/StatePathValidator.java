package com.javaclaw.workflow.node;

/** 公共节点配置中的状态路径校验。 */
public final class StatePathValidator {
    private StatePathValidator() {}

    public static boolean isValid(String path) {
        return path != null && path.matches(
                "[A-Za-z_][A-Za-z0-9_-]*(?:\\.[A-Za-z_][A-Za-z0-9_-]*)*");
    }

    public static void validate(String path, String field, java.util.List<String> errors) {
        if (!isValid(path)) errors.add(field + " 不是合法状态路径");
    }
}
