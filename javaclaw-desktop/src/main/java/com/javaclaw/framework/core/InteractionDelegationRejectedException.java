package com.javaclaw.framework.core;

/** 仅表示宿主在交互子任务派发前，确认冻结合同不满足委派条件。 */
public final class InteractionDelegationRejectedException extends RuntimeException {
    public static final String CONTRACT_REQUIRED = "INTERACTION_CONTRACT_REQUIRED";
    public static final String NO_CRITERIA = "NO_INTERACTION_CRITERIA";
    private final String errorCode;

    public InteractionDelegationRejectedException(String errorCode) {
        super(message(errorCode));
        this.errorCode = errorCode;
    }

    public String errorCode() { return errorCode; }

    private static String message(String errorCode) {
        String reason = switch (errorCode) {
            case CONTRACT_REQUIRED -> "当前缺少宿主冻结的任务合同";
            case NO_CRITERIA -> "当前冻结合同没有浏览器或桌面交互验收条件";
            default -> throw new IllegalArgumentException("不支持的交互委派拒绝原因: " + errorCode);
        };
        return reason + "，交互子任务未派发。请遵循原任务合同继续执行；如确实需要交互，请向用户澄清，"
                + "由宿主更新合同，不能自行重写或补造验收条件。";
    }
}
