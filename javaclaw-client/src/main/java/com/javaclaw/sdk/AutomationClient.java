package com.javaclaw.sdk;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.javaclaw.sdk.model.AutomationInfo;
import com.javaclaw.sdk.model.ScheduleInfo;
import com.javaclaw.sdk.model.ScheduleTriggerInfo;
import com.javaclaw.sdk.model.TurnInfo;

/** Loop、Workflow、SDD 和 Schedule 的领域客户端；所有执行仍创建统一 Turn，远程失败通过 Future 异常返回。 */
public final class AutomationClient {
    private final ProtocolClient protocol;
    private final SdkProtocolMapper mapper;

    AutomationClient(ProtocolClient protocol, SdkProtocolMapper mapper) {
        this.protocol = protocol;
        this.mapper = mapper;
    }

    /** 异步列出持久自动化定义及执行绑定，不触发任务。 */
    public CompletableFuture<List<AutomationInfo>> list() {
        return protocol.listAutomations()
                .thenApply(values -> values.stream().map(mapper::automation).toList());
    }

    /** 异步读取指定自动化；不存在时 Future 以 RPC 错误完成。 */
    public CompletableFuture<AutomationInfo> read(String id) {
        return protocol.readAutomation(id).thenApply(mapper::automation);
    }

    /** 读取类型化的预算、步骤和验收定义，不触发执行，也不在客户端复制编排内核。 */
    public CompletableFuture<com.javaclaw.sdk.model.AutomationDefinitionInfo> readDefinition(String id) {
        return read(id).thenApply(value -> AutomationDocuments.read(value.definition()));
    }

    /** 从用户选择的 ZIP 读取 OpenSpec 预览；不保存定义、不执行流程，也不自动采用勾选状态。 */
    public CompletableFuture<com.javaclaw.sdk.model.OpenSpecDraftInfo> importOpenSpec(java.nio.file.Path file) {
        return fileTask(() -> OpenSpecBundleCodec.read(file));
    }

    /** 用户确认后将导入文档保存到指定 SDD 的新定义版本；修订变化时拒绝覆盖，不启动执行。 */
    public CompletableFuture<AutomationInfo> saveOpenSpecDraft(
            String id, com.javaclaw.sdk.model.OpenSpecDraftInfo draft, long expectedRevision, String key) {
        return read(id).thenCompose(value -> {
            if (!"SDD".equals(value.kind()) || value.revision() != expectedRevision) {
                return CompletableFuture.failedFuture(new IllegalStateException("SDD definition revision changed"));
            }
            var previous = AutomationDocuments.read(value.definition());
            var updated = new com.javaclaw.sdk.model.AutomationDefinitionInfo(
                    previous.maxIterations(),
                    previous.maxModelCalls(),
                    previous.maxTokens(),
                    previous.maxDurationSeconds(),
                    previous.noProgressLimit(),
                    previous.specification(),
                    previous.criteria(),
                    previous.nodes(),
                    draft.documents());
            return putDefinition(value, updated, key);
        });
    }

    /** 将 H2 当前定义及匹配定义指纹的最近执行产物导出为 OpenSpec ZIP；旧规格产物不覆盖新定义。既有目标仅在 replaceExisting 为 true 时替换。 */
    public CompletableFuture<java.nio.file.Path> exportOpenSpec(
            String id, java.nio.file.Path file, boolean replaceExisting) {
        return read(id).thenCompose(definition -> {
            if (!"SDD".equals(definition.kind())) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("OpenSpec export requires SDD"));
            }
            return items(id)
                    .thenCompose(items -> fileTask(() -> {
                        OpenSpecBundleCodec.write(
                                AutomationDocuments.read(definition.definition()),
                                OpenSpecBundleCodec.currentExecutionArtifacts(definition, items),
                                file,
                                replaceExisting);
                        return file;
                    }));
        });
    }

    private static <T> CompletableFuture<T> fileTask(java.util.concurrent.Callable<T> operation) {
        var future = new CompletableFuture<T>();
        Thread worker = Thread.ofVirtual().name("javaclaw-sdk-sdd-files").start(() -> {
            try {
                if (!future.isCancelled()) {
                    future.complete(operation.call());
                }
            } catch (Exception failure) {
                future.completeExceptionally(failure);
            }
        });
        future.whenComplete((value, failure) -> {
            if (future.isCancelled()) {
                worker.interrupt();
            }
        });
        return future;
    }

    /** 将编辑器定义与原修订一同提交；定义校验、权限及并发冲突由 App Server 决定。 */
    public CompletableFuture<AutomationInfo> putDefinition(
            AutomationInfo value, com.javaclaw.sdk.model.AutomationDefinitionInfo definition, String key) {
        return put(
                new AutomationInfo(
                        value.id(),
                        value.kind(),
                        value.name(),
                        value.workspaceId(),
                        value.profileId(),
                        value.prompt(),
                        AutomationDocuments.write(definition),
                        value.status(),
                        value.threadId(),
                        value.activeTurnId(),
                        value.revision(),
                        value.createdAt(),
                        value.updatedAt()),
                value.revision(),
                key);
    }

    /** 按 expectedRevision 保存自动化定义；idempotencyKey 用于去重，返回新版本。 */
    public CompletableFuture<AutomationInfo> put(AutomationInfo value, long expectedRevision, String idempotencyKey) {
        return protocol.putAutomation(mapper.automation(value), expectedRevision, idempotencyKey)
                .thenApply(mapper::automation);
    }

    /** 按版本删除自动化定义，返回删除结果；不会隐式删除稳定 Thread 的历史。 */
    public CompletableFuture<Boolean> delete(String id, long expectedRevision, String key) {
        return protocol.deleteAutomation(id, expectedRevision, key);
    }

    /** 在自动化绑定的 Thread 创建一次 Turn；key 支持去重，返回启动状态而非最终执行结果。 */
    public CompletableFuture<TurnInfo> start(String id, String key) {
        return protocol.startAutomation(id, key).thenApply(mapper::turn);
    }

    /** 请求中断自动化当前绑定的 Turn；没有可取消执行时返回 false。 */
    public CompletableFuture<Boolean> interrupt(String id) {
        return protocol.interruptAutomation(id);
    }

    /** 从原定义的持久检查点恢复新 Turn；服务端扣除旧预算并阻止未知副作用重发。 */
    public CompletableFuture<TurnInfo> resume(String id, String idempotencyKey) {
        return protocol.resumeAutomation(id, idempotencyKey).thenApply(mapper::turn);
    }

    /** 读取自动化的步骤、检查点、规格、评估与执行结果，不触发模型。 */
    public CompletableFuture<List<com.javaclaw.sdk.model.ItemInfo>> items(String id) {
        return protocol.automationItems(id)
                .thenApply(values -> values.stream().map(mapper::item).toList());
    }

    /** 读取权威 Schedule 定义和触发状态，不直接访问 Quartz。 */
    public CompletableFuture<List<ScheduleInfo>> listSchedules() {
        return protocol.listSchedules()
                .thenApply(values -> values.stream().map(mapper::schedule).toList());
    }

    /** 读取指定 Schedule；不存在时 Future 以 RPC 错误完成。 */
    public CompletableFuture<ScheduleInfo> readSchedule(String id) {
        return protocol.readSchedule(id).thenApply(mapper::schedule);
    }

    /** 校验未保存的 Quartz Cron 和时区并返回未来触发时间；count 必须为 1–20，调用不保存状态。 */
    public CompletableFuture<com.javaclaw.sdk.model.SchedulePreviewInfo> previewSchedule(
            String cronExpression, String zoneId, int count) {
        return protocol.previewSchedule(cronExpression, zoneId, count).thenApply(mapper::schedulePreview);
    }

    /** 按版本保存 Schedule；服务端校验 cron、时区和无人值守权限，返回新版本。 */
    public CompletableFuture<ScheduleInfo> putSchedule(ScheduleInfo value, long expectedRevision, String key) {
        return protocol.putSchedule(mapper.schedule(value), expectedRevision, key)
                .thenApply(mapper::schedule);
    }

    /** 按版本启用或禁用 Schedule；不等同于立即手动触发一次执行。 */
    public CompletableFuture<ScheduleInfo> setScheduleEnabled(
            String id, boolean enabled, long expectedRevision, String key) {
        return protocol.setScheduleEnabled(id, enabled, expectedRevision, key).thenApply(mapper::schedule);
    }

    /** 按版本删除 Schedule 并停止后续触发；不删除已产生的 Thread 历史。 */
    public CompletableFuture<Boolean> deleteSchedule(String id, long expectedRevision, String key) {
        return protocol.deleteSchedule(id, expectedRevision, key);
    }

    /** 手动请求一次触发；重叠采用 SKIP，返回 skipped/reason 或新 Turn。 */
    public CompletableFuture<ScheduleTriggerInfo> triggerSchedule(String id, String key) {
        return protocol.triggerSchedule(id, key)
                .thenApply(
                        value -> new ScheduleTriggerInfo(value.skipped(), value.reason(), mapper.turn(value.turn())));
    }
}
