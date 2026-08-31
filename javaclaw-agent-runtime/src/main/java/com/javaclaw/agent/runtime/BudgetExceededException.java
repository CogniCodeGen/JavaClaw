package com.javaclaw.agent.runtime;

/** 有限预算耗尽，必须停止新步骤并保留检查点；此异常不表示任务成功完成。 */
public final class BudgetExceededException extends IllegalStateException {
    /** 创建不包含私有输入的预算错误；message 只能描述预算类别及调用用途。 */
    public BudgetExceededException(String message) {
        super(message);
    }
}
