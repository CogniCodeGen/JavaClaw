package com.javaclaw.platform.execution;

/**
 * 托管任务提交边界。
 *
 * <p>实现必须传播 {@link TaskContext}、返回可取消句柄，并在自身生命周期关闭后拒绝新任务。
 * 根执行引擎和工作区/插件作用域都实现此接口，使 UI 异步动作可以绑定正确的回收边界。</p>
 */
public interface TaskSubmitter {

    <T> TaskHandle<T> submit(TaskSpec spec, ManagedTask<T> task);
}
