package com.javaclaw.plugin.capability;

import com.javaclaw.plugin.CapabilityGuard;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.capability.ScheduleAccess;
import com.javaclaw.application.schedule.ScheduleApplicationService;
import com.javaclaw.application.schedule.ScheduleApplicationService.SaveCommand;
import com.javaclaw.application.schedule.ScheduleApplicationService.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SCHEDULE 能力实现 —— 仅通过宿主 {@link ScheduleApplicationService}。插件创建的任务带插件标签并被记账，
 * <b>随插件停用一并清理</b>（见 {@link #cleanup()}），与插件生命周期绑定，避免残留。
 *
 * @author JavaClaw
 */
public final class ScheduleAccessImpl implements ScheduleAccess {

    private static final Logger log = LoggerFactory.getLogger(ScheduleAccessImpl.class);

    private final String pluginId;
    private final ScheduleApplicationService schedules;
    /** 本插件创建的任务 id，用于停用时统一清理 */
    private final Set<String> createdIds = ConcurrentHashMap.newKeySet();

    public ScheduleAccessImpl(String pluginId, ScheduleApplicationService schedules) {
        this.pluginId = pluginId;
        this.schedules = java.util.Objects.requireNonNull(schedules, "schedules");
    }

    @Override
    public String createInterval(String name, int minutes, String prompt) {
        CapabilityGuard.require(Capability.SCHEDULE);
        return commit(name, prompt, "interval", Math.max(1, minutes), "", "");
    }

    @Override
    public String createDaily(String name, String hhmm, String prompt) {
        CapabilityGuard.require(Capability.SCHEDULE);
        return commit(name, prompt, "daily", 1, hhmm, "");
    }

    @Override
    public String createCron(String name, String cron, String prompt) {
        CapabilityGuard.require(Capability.SCHEDULE);
        return commit(name, prompt, "cron", 1, "", cron);
    }

    @Override
    public void cancel(String taskId) {
        CapabilityGuard.require(Capability.SCHEDULE);
        schedules.delete(taskId);
        createdIds.remove(taskId);
        log.info("插件[{}]取消定时任务 {}", pluginId, taskId);
    }

    /** 停用插件时清理其全部定时任务（由 PluginRuntime 调用）。 */
    public void cleanup() {
        if (createdIds.isEmpty()) {
            return;
        }
        for (String id : createdIds) {
            try {
                schedules.delete(id);
            } catch (Exception e) {
                log.debug("插件[{}]清理定时任务 {} 忽略异常：{}", pluginId, id, e.toString());
            }
        }
        log.info("插件[{}]已清理 {} 个定时任务", pluginId, createdIds.size());
        createdIds.clear();
    }

    private String commit(String name, String prompt, String triggerType,
                          int intervalValue, String dailyTime, String cronExpression) {
        Task draft = schedules.createDraft(name);
        Task saved = schedules.save(new SaveCommand(
                draft.id(), name, "由插件[" + pluginId + "]创建", triggerType,
                intervalValue, "minute", dailyTime, cronExpression, "", prompt,
                true, draft.version(), false, "none", false, true))
                .snapshot().require(draft.id());
        createdIds.add(saved.id());
        log.info("插件[{}]创建定时任务：{}（{}，{}）", pluginId,
                saved.name(), saved.id(), saved.triggerType());
        return saved.id();
    }
}
