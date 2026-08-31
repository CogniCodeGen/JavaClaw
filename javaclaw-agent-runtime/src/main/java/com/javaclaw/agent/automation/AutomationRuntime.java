package com.javaclaw.agent.automation;

import java.time.Instant;
import java.util.List;

import com.javaclaw.agent.runtime.ThreadUseCases;
import com.javaclaw.agent.runtime.TurnUseCases;
import com.javaclaw.agent.runtime.WorkspaceUseCases;
import com.javaclaw.core.api.AgentTurn;

/** Process-level facade that keeps H2 authoritative and Quartz disposable. */
public final class AutomationRuntime implements AutomationUseCases, AutoCloseable {
    private final AutomationRepository repository;
    private final AutomationService service;
    private final AutomationScheduler scheduler;

    /** 装配统一 Turn 用例及 Quartz 内存投影；构造不触发 Schedule，调用 start 后启用定时调度。 */
    public AutomationRuntime(
            AutomationRepository repository,
            WorkspaceUseCases workspaces,
            ThreadUseCases threads,
            TurnUseCases turns,
            AutomationTurnResolver resolver) {
        this.repository = repository;
        this.service = new AutomationService(repository, workspaces, threads, turns, resolver);
        this.scheduler = new AutomationScheduler(repository, this::scheduledTrigger);
    }

    /** 从 H2 重建 Schedule 投影并启动触发器；错过的触发不补跑。 */
    public void start() {
        scheduler.start();
    }

    @Override
    public List<AutomationRepository.AutomationDefinition> listAutomations() {
        return service.listAutomations();
    }

    @Override
    public AutomationRepository.AutomationDefinition readAutomation(String id) {
        return service.readAutomation(id);
    }

    @Override
    public AutomationRepository.AutomationDefinition putAutomation(
            AutomationRepository.AutomationDraft draft, long revision, String key) {
        return service.putAutomation(draft, revision, key);
    }

    @Override
    public boolean deleteAutomation(String id, long revision, String key) {
        return service.deleteAutomation(id, revision, key);
    }

    @Override
    public AgentTurn startAutomation(String id, String key) {
        return service.startAutomation(id, key);
    }

    @Override
    public AgentTurn resumeAutomation(String id, String key) {
        return service.resumeAutomation(id, key);
    }

    @Override
    public List<com.javaclaw.core.api.StoredItem> executionItems(String id) {
        return service.executionItems(id);
    }

    @Override
    public boolean interruptAutomation(String id) {
        return service.interruptAutomation(id);
    }

    @Override
    public List<AutomationRepository.ScheduleDefinition> listSchedules() {
        return service.listSchedules();
    }

    @Override
    public AutomationRepository.ScheduleDefinition readSchedule(String id) {
        return service.readSchedule(id);
    }

    @Override
    public AutomationRepository.ScheduleDefinition putSchedule(
            AutomationRepository.ScheduleDraft draft, long revision, String key) {
        var result = service.putSchedule(draft, revision, key);
        scheduler.reconcile(result);
        return result;
    }

    @Override
    public AutomationRepository.ScheduleDefinition setScheduleEnabled(
            String id, boolean enabled, long revision, String key) {
        var result = service.setScheduleEnabled(id, enabled, revision, key);
        scheduler.reconcile(result);
        return result;
    }

    @Override
    public boolean deleteSchedule(String id, long revision, String key) {
        boolean removed = service.deleteSchedule(id, revision, key);
        if (removed) {
            scheduler.remove(id);
        }
        return removed;
    }

    @Override
    public AutomationService.TriggerResult triggerSchedule(String id, String key) {
        return service.triggerSchedule(id, false, key);
    }

    private String scheduledTrigger(String id, Instant scheduledAt) {
        String key = "scheduled:" + id + ":" + scheduledAt;
        return service.triggerSchedule(id, true, key).reason();
    }

    @Override
    public void close() {
        scheduler.close();
    }
}
