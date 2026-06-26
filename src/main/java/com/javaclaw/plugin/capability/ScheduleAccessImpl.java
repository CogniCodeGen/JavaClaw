package com.javaclaw.plugin.capability;

import com.javaclaw.plugin.CapabilityGuard;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.capability.ScheduleAccess;
import com.javaclaw.schedule.ScheduledTask;
import com.javaclaw.schedule.ScheduleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SCHEDULE 能力实现 —— 背靠宿主 {@link ScheduleManager}。插件创建的任务带插件标签并被记账，
 * <b>随插件停用一并清理</b>（见 {@link #cleanup()}），与插件生命周期绑定，避免残留。
 *
 * @author JavaClaw
 */
public final class ScheduleAccessImpl implements ScheduleAccess {

    private static final Logger log = LoggerFactory.getLogger(ScheduleAccessImpl.class);

    private final String pluginId;
    /** 本插件创建的任务 id，用于停用时统一清理 */
    private final Set<String> createdIds = ConcurrentHashMap.newKeySet();

    public ScheduleAccessImpl(String pluginId) {
        this.pluginId = pluginId;
    }

    @Override
    public String createInterval(String name, int minutes, String prompt) {
        CapabilityGuard.require(Capability.SCHEDULE);
        ScheduledTask task = newTask(name, prompt);
        task.setTriggerType("interval");
        task.setIntervalMinutes(Math.max(1, minutes));
        return commit(task);
    }

    @Override
    public String createDaily(String name, String hhmm, String prompt) {
        CapabilityGuard.require(Capability.SCHEDULE);
        ScheduledTask task = newTask(name, prompt);
        task.setTriggerType("daily");
        task.setDailyTime(hhmm);
        return commit(task);
    }

    @Override
    public String createCron(String name, String cron, String prompt) {
        CapabilityGuard.require(Capability.SCHEDULE);
        ScheduledTask task = newTask(name, prompt);
        task.setTriggerType("cron");
        task.setCronExpression(cron);
        return commit(task);
    }

    @Override
    public void cancel(String taskId) {
        CapabilityGuard.require(Capability.SCHEDULE);
        ScheduleManager.getInstance().deleteTask(taskId);
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
                ScheduleManager.getInstance().deleteTask(id);
            } catch (Exception e) {
                log.debug("插件[{}]清理定时任务 {} 忽略异常：{}", pluginId, id, e.toString());
            }
        }
        log.info("插件[{}]已清理 {} 个定时任务", pluginId, createdIds.size());
        createdIds.clear();
    }

    private ScheduledTask newTask(String name, String prompt) {
        ScheduledTask task = ScheduleManager.getInstance().createTask(name);
        task.setPrompt(prompt);
        task.setEnabled(true);
        task.setDescription("由插件[" + pluginId + "]创建");
        return task;
    }

    private String commit(ScheduledTask task) {
        ScheduleManager.getInstance().updateTask(task);   // 触发实际调度
        createdIds.add(task.getId());
        log.info("插件[{}]创建定时任务：{}（{}，{}）", pluginId, task.getName(), task.getId(), task.getTriggerType());
        return task.getId();
    }
}
