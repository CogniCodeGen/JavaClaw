package com.javaclaw.client.extension;

import java.util.Objects;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.builtin.contracts.ScheduleManagementContracts;
import com.javaclaw.client.CommandOptions;

/** Schedule Definition、未来触发预览和权威 Occurrence 的强类型 SDK facade。 */
public final class ScheduleClient {
    private final DocumentExtensionClient<ScheduleContracts.Definition> definitions;
    private final BuiltinClientCalls calls;

    ScheduleClient(ExtensionClient extensions) {
        definitions = new DocumentExtensionClient<>(
                extensions, BuiltinExtensionIds.SCHEDULE, ScheduleContracts.Definition.class);
        calls = new BuiltinClientCalls(extensions, BuiltinExtensionIds.SCHEDULE);
    }

    /** @return 指定 Schedule Definition */
    public ScheduleContracts.Definition read(WorkspaceId workspaceId, String id) {
        return definitions.read(workspaceId, id);
    }

    /** @return 稳定键分页的 Schedule Definition */
    public TypedDocumentPage<ScheduleContracts.Definition> list(WorkspaceId workspaceId, String afterKey, int limit) {
        return definitions.list(workspaceId, afterKey, limit);
    }

    /** @return 由服务端确认 Profile、目标目录并生成 revision 的 Schedule */
    public ScheduleContracts.Definition create(
            WorkspaceId workspaceId, ScheduleManagementContracts.SaveRequest request, CommandOptions options) {
        return save(workspaceId, "definition/create", request, requireRevision(options, true));
    }

    /** @return 按 expected revision 更新并重建投影后的 Schedule */
    public ScheduleContracts.Definition update(
            WorkspaceId workspaceId, ScheduleManagementContracts.SaveRequest request, CommandOptions options) {
        return save(workspaceId, "definition/update", request, requireRevision(options, false));
    }

    /** @return 删除确认 */
    public DocumentContracts.Deleted delete(WorkspaceId workspaceId, String id, CommandOptions options) {
        return definitions.delete(workspaceId, id, options);
    }

    /** @return 指定 revision 之后严格递增的五次触发时间 */
    public ScheduleContracts.Preview preview(WorkspaceId workspaceId, ScheduleContracts.PreviewRequest request) {
        return calls.queryAtRevision(workspaceId, "preview", request, ScheduleContracts.Preview.class, 0);
    }

    /** @return H2 权威 Occurrence 分页 */
    public ScheduleContracts.OccurrencePage occurrences(
            WorkspaceId workspaceId, ScheduleContracts.OccurrenceQuery query) {
        ScheduleContracts.OccurrenceQuery checked = Objects.requireNonNull(query, "query");
        ScheduleContracts.OccurrencePage page = calls.queryAtRevision(
                workspaceId, "occurrence/list", checked, ScheduleContracts.OccurrencePage.class, 0);
        checked.scheduleId().ifPresent(scheduleId -> {
            if (page.occurrences().stream()
                    .anyMatch(occurrence -> !occurrence.identity().scheduleId().equals(scheduleId))) {
                throw new IllegalStateException("occurrence page contains another Schedule");
            }
        });
        return page;
    }

    /** @return 已记录并提交投递的独立 Occurrence */
    public ScheduleContracts.Occurrence run(
            WorkspaceId workspaceId, ScheduleContracts.ManualRun request, CommandOptions options) {
        ScheduleContracts.ManualRun checkedRequest = Objects.requireNonNull(request, "request");
        CommandOptions checkedOptions = requirePositiveRevision(options, "Schedule manual run");
        ScheduleContracts.Occurrence occurrence = calls.commandAtRevision(
                workspaceId, "occurrence/run", checkedRequest, checkedOptions, ScheduleContracts.Occurrence.class, 1);
        if (!occurrence.identity().scheduleId().equals(checkedRequest.scheduleId())
                || occurrence.identity().scheduleRevision() != checkedOptions.expectedRevision()) {
            throw new IllegalStateException("manual occurrence identity mismatch");
        }
        return occurrence;
    }

    private ScheduleContracts.Definition save(
            WorkspaceId workspaceId,
            String operation,
            ScheduleManagementContracts.SaveRequest request,
            CommandOptions options) {
        ScheduleManagementContracts.SaveRequest checkedRequest = Objects.requireNonNull(request, "request");
        ScheduleContracts.Definition definition = calls.commandAtRevision(
                workspaceId,
                operation,
                checkedRequest,
                options,
                ScheduleContracts.Definition.class,
                Math.addExact(options.expectedRevision(), 1));
        if (!definition.id().equals(checkedRequest.id())) {
            throw new IllegalStateException("saved Schedule identity mismatch");
        }
        return definition;
    }

    private static CommandOptions requireRevision(CommandOptions options, boolean creating) {
        CommandOptions checked = Objects.requireNonNull(options, "options");
        if (creating && checked.expectedRevision() != 0) {
            throw new IllegalArgumentException("Schedule create requires expected revision zero");
        }
        if (!creating && checked.expectedRevision() < 1) {
            throw new IllegalArgumentException("Schedule update requires a positive expected revision");
        }
        return checked;
    }

    private static CommandOptions requirePositiveRevision(CommandOptions options, String operation) {
        CommandOptions checked = Objects.requireNonNull(options, "options");
        if (checked.expectedRevision() < 1) {
            throw new IllegalArgumentException(operation + " requires a positive expected revision");
        }
        return checked;
    }
}
