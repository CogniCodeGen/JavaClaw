package com.javaclaw.server.persistence;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import com.javaclaw.api.TurnId;
import com.javaclaw.runtime.BudgetAccount;
import com.javaclaw.runtime.ReservedChildBudget;

/**
 * 单个 App Server 实例的活动预算注册表；所有者通过组合根显式注入，不使用全局状态。
 *
 * <p>只对已进入 Harness 的父 Turn 预留，持久事务与账户锁共同防止重复额度；离开 Harness 后立即拒绝新 spawn。
 */
public final class LiveTurnBudgets {
    private final ConcurrentHashMap<TurnId, BudgetAccount> accounts = new ConcurrentHashMap<>();

    /** 创建尚未绑定任何 Turn 的注册表。 */
    public LiveTurnBudgets() {}

    void activate(TurnId turnId, BudgetAccount budget, List<ReservedChildBudget> reservations) {
        reservations.forEach(budget::reserveChild);
        if (accounts.putIfAbsent(turnId, budget) != null) {
            throw new IllegalStateException("Turn 已有活动预算账户");
        }
    }

    void deactivate(TurnId turnId, BudgetAccount budget) {
        synchronized (budget) {
            accounts.remove(turnId, budget);
        }
    }

    <T> T reserve(TurnId parent, ReservedChildBudget reservation, Callable<T> commit) {
        BudgetAccount account = accounts.get(parent);
        if (account == null) {
            throw PersistenceException.invalidRequest("父 Turn 尚未运行或已结束，不能创建子智能体");
        }
        try {
            synchronized (account) {
                if (accounts.get(parent) != account) {
                    throw PersistenceException.invalidRequest("父 Turn 已结束");
                }
                return account.reserveChild(reservation, commit);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("子任务预算预留事务失败", failure);
        }
    }
}
