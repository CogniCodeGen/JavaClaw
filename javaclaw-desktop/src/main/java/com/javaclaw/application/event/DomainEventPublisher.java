package com.javaclaw.application.event;

/**
 * Application/Domain 向外发布事实事件的最小端口。
 *
 * <p>发布为同步语义：监听器异常会返回给调用方，事务性用例可据此回滚。事件对象应不可变，
 * 发布后不得由监听器修改。</p>
 */
@FunctionalInterface
public interface DomainEventPublisher {

    void publish(Object event);
}
