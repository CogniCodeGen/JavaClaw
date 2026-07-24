package com.javaclaw.workflow.model;

/** 异常中断后重跑当前节点前是否必须再次取得用户确认。 */
public enum ResumeSafety {
    SAFE,
    CONFIRM_RETRY
}
