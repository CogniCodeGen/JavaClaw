package com.javaclaw.desktop.settings;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.ToolCatalogQueryResult;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.UnattendedToolGrant;
import com.javaclaw.api.UnattendedToolGrantDraft;
import com.javaclaw.api.UnattendedToolGrantStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.CanonicalJson;

/** 协调 Schedule、精确工具目录、授权余额、审计与实时撤销。 */
public final class UnattendedToolGrantSettingsPresenter {
    private static final int TOOL_LIMIT = 100;

    private final CoreSettingsGateway gateway;
    private final ScheduleCatalogGateway schedules;
    private final CanonicalJson json = new CanonicalJson();
    private Consumer<UnattendedToolGrantSettingsState> listener = ignored -> {};
    private UnattendedToolGrantSettingsState state = UnattendedToolGrantSettingsState.initial();

    /**
     * 创建无人值守授权状态机。
     *
     * @param gateway Core SDK 设置边界
     * @param schedules Schedule 强类型目录边界
     */
    public UnattendedToolGrantSettingsPresenter(CoreSettingsGateway gateway, ScheduleCatalogGateway schedules) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.schedules = Objects.requireNonNull(schedules, "schedules");
    }

    /** @param value 页面状态监听器 */
    public void subscribe(Consumer<UnattendedToolGrantSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** 重新读取 Workspace，并选择首个 Workspace。 */
    public void reload() {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.LOADING, "正在读取工作区…", epoch));
        gateway.workspaces().whenComplete((workspaces, failure) -> applyWorkspaces(epoch, workspaces, failure));
    }

    /**
     * 切换设置中心已经确认的固定 Workspace，并使旧目录请求失效。
     *
     * @param workspace 要查看的 Workspace
     */
    public void selectWorkspace(Workspace workspace) {
        Workspace checked = Objects.requireNonNull(workspace, "workspace");
        long epoch = nextEpoch();
        publish(new UnattendedToolGrantSettingsState(
                SettingsLoadState.LOADING,
                state.workspaces(),
                Optional.of(checked),
                List.of(),
                Optional.empty(),
                UnattendedGrantBindingState.empty(),
                UnattendedToolGrantForm.empty(),
                List.of(),
                "正在读取定时任务和无人值守授权…",
                epoch));
        loadWorkspace(checked).whenComplete((catalog, failure) -> applyCatalog(epoch, checked, catalog, failure));
    }

    /** 标记固定 Workspace 当前不可用；保留草稿，但立即使所有在途读取失效并阻止写入。 */
    public void scopeUnavailable() {
        long epoch = nextEpoch();
        publish(new UnattendedToolGrantSettingsState(
                SettingsLoadState.ERROR,
                state.workspaces(),
                Optional.empty(),
                state.grants(),
                state.selected(),
                state.binding(),
                state.draft(),
                state.decisions(),
                "当前工作区不可用，已保留草稿且禁止提交",
                epoch));
    }

    /**
     * 选择精确 Schedule，并沿其执行配置读取实际可执行工具目录。
     *
     * @param schedule 当前目录中的定义
     */
    public void selectSchedule(ScheduleContracts.Definition schedule) {
        ScheduleContracts.Definition checked = requireSchedule(schedule);
        if (state.dirty()) {
            publish(copy(SettingsLoadState.READY, "请先创建或丢弃当前授权草稿", state.epoch()));
            return;
        }
        Optional<ExecutionOverrides> profile = execution(checked);
        if (profile.isEmpty()) {
            publish(copy(SettingsLoadState.ERROR, "该定时任务不启动 Agent Turn，不能创建工具授权", state.epoch()));
            return;
        }
        WorkspaceId workspaceId = state.workspace().orElseThrow().id();
        long epoch = nextEpoch();
        UnattendedGrantBindingState binding = new UnattendedGrantBindingState(
                state.binding().schedules(),
                Optional.of(checked),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        publish(withBinding(
                SettingsLoadState.LOADING, binding, UnattendedToolGrantForm.empty(), "正在解析定时任务的精确权限与工具目录…", epoch));
        resolve(workspaceId, profile.orElseThrow())
                .whenComplete((resolved, failure) -> applyResolved(epoch, checked, resolved, failure));
    }

    /** @param query 名称、说明或标签关键词 */
    public void searchTools(String query) {
        Workspace workspace = state.workspace().orElseThrow();
        AgentRole profile = state.binding().agentRole().orElseThrow();
        PermissionProfile permission = state.binding().permissionProfile().orElseThrow();
        String normalized = Objects.requireNonNullElse(query, "").strip();
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.LOADING, "正在筛选实际可执行工具…", epoch));
        gateway.toolCatalog(
                        workspace.id(),
                        new PermissionProfileRef(permission.id(), permission.version()),
                        Optional.of(new AgentRoleRef(profile.id(), profile.revision())),
                        normalized,
                        TOOL_LIMIT)
                .whenComplete((catalog, failure) -> applyToolSearch(epoch, permission, catalog, failure));
    }

    /** @param tool 从当前权威目录选择的精确工具 */
    public void selectTool(ToolDescriptor tool) {
        ToolDescriptor checked = requireCurrentTool(tool);
        UnattendedGrantBindingState binding = state.binding();
        ToolCatalogQueryResult catalog = binding.catalog().orElseThrow();
        UnattendedGrantBindingState selected = new UnattendedGrantBindingState(
                binding.schedules(),
                binding.schedule(),
                binding.agentRole(),
                binding.permissionProfile(),
                binding.catalog(),
                Optional.of(checked));
        UnattendedToolGrantForm draft = state.draft().bind(binding.schedule().orElseThrow(), catalog, checked);
        publish(withBinding(SettingsLoadState.READY, selected, draft, "授权草稿尚未提交", state.epoch()));
    }

    /**
     * 只编辑非技术字段；Schedule、工具身份、目录版本和 Schema 指纹只能来自权威选择。
     *
     * @param fixedArguments 固定参数 JSON
     * @param variableFields 允许变化的字符串字段
     * @param maximumUses 最大次数
     * @param validityDays 有效天数
     */
    public void editArguments(String fixedArguments, String variableFields, String maximumUses, String validityDays) {
        UnattendedToolGrantForm draft = state.draft().edit(fixedArguments, variableFields, maximumUses, validityDays);
        publish(withBinding(state.phase(), state.binding(), draft, "授权草稿尚未提交", state.epoch()));
    }

    /** @param selected 选中的授权与余额 */
    public void selectGrant(UnattendedToolGrantStatus selected) {
        publish(new UnattendedToolGrantSettingsState(
                state.phase(),
                state.workspaces(),
                state.workspace(),
                state.grants(),
                Optional.ofNullable(selected),
                state.binding(),
                state.draft(),
                state.decisions(),
                state.message(),
                state.epoch()));
    }

    /** 校验并创建严格绑定 Schedule、目录、Schema 与参数模板的授权。 */
    public void create() {
        Workspace workspace = state.workspace().orElseThrow();
        try {
            UnattendedToolGrantDraft draft = state.draft().toDraft(workspace.id(), json);
            execute(gateway.createUnattendedToolGrant(draft, CommandOptions.create(0)), "无人值守授权已创建");
        } catch (RuntimeException failure) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), state.epoch()));
        }
    }

    /** 不可逆撤销选中的授权。 */
    public void revoke() {
        UnattendedToolGrant grant = state.selected().orElseThrow().grant();
        execute(gateway.revokeUnattendedToolGrant(grant, CommandOptions.create(grant.revision())), "无人值守授权已撤销");
    }

    /** 丢弃尚未提交的授权草稿，但保留已加载的 Schedule 和目录。 */
    public void discardDraft() {
        UnattendedGrantBindingState binding = state.binding();
        UnattendedGrantBindingState cleared = new UnattendedGrantBindingState(
                binding.schedules(),
                binding.schedule(),
                binding.agentRole(),
                binding.permissionProfile(),
                binding.catalog(),
                Optional.empty());
        publish(withBinding(SettingsLoadState.READY, cleared, UnattendedToolGrantForm.empty(), "", state.epoch()));
    }

    /** @return 当前不可变状态 */
    public UnattendedToolGrantSettingsState state() {
        return state;
    }

    private CompletionStage<WorkspaceCatalog> loadWorkspace(Workspace workspace) {
        CompletionStage<GrantCatalog> grants = gateway.unattendedToolGrants(workspace.id())
                .thenCombine(
                        gateway.permissionDecisions(
                                workspace.id(), SecurityGrantKind.UNATTENDED_TOOL, Optional.empty(), 100),
                        GrantCatalog::new);
        return grants.thenCombine(schedules.schedules(workspace.id()), WorkspaceCatalog::new);
    }

    private CompletionStage<ResolvedCatalog> resolve(WorkspaceId workspaceId, ExecutionOverrides execution) {
        return gateway.previewExecution(workspaceId, execution)
                .thenCompose(preview -> gateway.role(preview.role())
                        .thenCompose(role -> gateway.permissionProfile(preview.permissionProfile())
                                .thenCompose(permission -> gateway.toolCatalog(
                                                workspaceId,
                                                preview.permissionProfile(),
                                                Optional.of(preview.role()),
                                                "",
                                                TOOL_LIMIT)
                                        .thenApply(catalog -> new ResolvedCatalog(role, permission, catalog)))));
    }

    private void execute(CompletionStage<?> operation, String success) {
        long epoch = nextEpoch();
        publish(copy(SettingsLoadState.SAVING, "正在提交…", epoch));
        operation.whenComplete((ignored, failure) -> {
            if (epoch != state.epoch()) {
                return;
            }
            if (failure != null) {
                publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
                return;
            }
            Workspace workspace = state.workspace().orElseThrow();
            discardDraft();
            publish(copy(SettingsLoadState.READY, success, state.epoch()));
            selectWorkspace(workspace);
        });
    }

    private void applyWorkspaces(long epoch, List<Workspace> workspaces, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            return;
        }
        List<Workspace> sorted = workspaces.stream()
                .sorted(Comparator.comparing(Workspace::name))
                .toList();
        publish(new UnattendedToolGrantSettingsState(
                SettingsLoadState.READY,
                sorted,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                UnattendedGrantBindingState.empty(),
                UnattendedToolGrantForm.empty(),
                List.of(),
                sorted.isEmpty() ? "暂无工作区" : "",
                epoch));
        sorted.stream().findFirst().ifPresent(this::selectWorkspace);
    }

    private void applyCatalog(long epoch, Workspace workspace, WorkspaceCatalog catalog, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            return;
        }
        List<UnattendedToolGrantStatus> grants = catalog.grants().grants().stream()
                .sorted(Comparator.comparing((UnattendedToolGrantStatus value) ->
                                value.grant().updatedAt())
                        .reversed())
                .toList();
        List<ScheduleContracts.Definition> definitions = catalog.schedules().stream()
                .sorted(Comparator.comparing(ScheduleContracts.Definition::id))
                .toList();
        UnattendedGrantBindingState binding = new UnattendedGrantBindingState(
                definitions, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        publish(new UnattendedToolGrantSettingsState(
                SettingsLoadState.READY,
                state.workspaces(),
                Optional.of(workspace),
                grants,
                grants.stream().findFirst(),
                binding,
                UnattendedToolGrantForm.empty(),
                catalog.grants().decisions(),
                definitions.isEmpty() ? "当前工作区没有可绑定的定时任务" : "",
                epoch));
    }

    private void applyResolved(
            long epoch, ScheduleContracts.Definition schedule, ResolvedCatalog resolved, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            return;
        }
        UnattendedGrantBindingState binding = new UnattendedGrantBindingState(
                state.binding().schedules(),
                Optional.of(schedule),
                Optional.of(resolved.profile()),
                Optional.of(resolved.permission()),
                Optional.of(resolved.catalog()),
                Optional.empty());
        String message = resolved.catalog().tools().isEmpty() ? "该定时任务的精确执行配置 当前没有可执行工具" : "请选择要授权的精确工具";
        publish(withBinding(SettingsLoadState.READY, binding, UnattendedToolGrantForm.empty(), message, epoch));
    }

    private void applyToolSearch(
            long epoch, PermissionProfile permission, ToolCatalogQueryResult catalog, Throwable failure) {
        if (epoch != state.epoch()) {
            return;
        }
        if (failure != null) {
            publish(copy(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch));
            return;
        }
        UnattendedGrantBindingState previous = state.binding();
        boolean sameRevision = previous.catalog()
                .map(current -> current.catalogRevision() == catalog.catalogRevision())
                .orElse(false);
        Optional<ToolDescriptor> selected = sameRevision ? previous.tool() : Optional.empty();
        UnattendedGrantBindingState binding = new UnattendedGrantBindingState(
                previous.schedules(),
                previous.schedule(),
                previous.agentRole(),
                Optional.of(permission),
                Optional.of(catalog),
                selected);
        UnattendedToolGrantForm draft = selected.map(
                        tool -> state.draft().bind(previous.schedule().orElseThrow(), catalog, tool))
                .orElseGet(UnattendedToolGrantForm::empty);
        publish(withBinding(SettingsLoadState.READY, binding, draft, "工具目录已更新", epoch));
    }

    private ToolDescriptor requireCurrentTool(ToolDescriptor tool) {
        ToolDescriptor checked = Objects.requireNonNull(tool, "tool");
        return state.binding().catalog().orElseThrow().tools().stream()
                .filter(candidate -> candidate.equals(checked))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("工具不在当前权威目录切片中"));
    }

    private ScheduleContracts.Definition requireSchedule(ScheduleContracts.Definition schedule) {
        ScheduleContracts.Definition checked = Objects.requireNonNull(schedule, "schedule");
        return state.binding().schedules().stream()
                .filter(candidate -> candidate.id().equals(checked.id()) && candidate.revision() == checked.revision())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("定时任务不在当前工作区目录中"));
    }

    private static Optional<ExecutionOverrides> execution(ScheduleContracts.Definition schedule) {
        return switch (schedule.target().kind()) {
            case DEFINITION -> schedule.target().definition().map(ScheduleContracts.DefinitionTarget::execution);
            case TURN_TEMPLATE -> schedule.target().turnTemplate().map(ScheduleContracts.TurnTemplate::execution);
            case ACTION -> Optional.empty();
        };
    }

    private UnattendedToolGrantSettingsState withBinding(
            SettingsLoadState phase,
            UnattendedGrantBindingState binding,
            UnattendedToolGrantForm draft,
            String message,
            long epoch) {
        return new UnattendedToolGrantSettingsState(
                phase,
                state.workspaces(),
                state.workspace(),
                state.grants(),
                state.selected(),
                binding,
                draft,
                state.decisions(),
                message,
                epoch);
    }

    private UnattendedToolGrantSettingsState copy(SettingsLoadState phase, String message, long epoch) {
        return withBinding(phase, state.binding(), state.draft(), message, epoch);
    }

    private void publish(UnattendedToolGrantSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(next);
    }

    private long nextEpoch() {
        return state.epoch() + 1;
    }

    private record GrantCatalog(List<UnattendedToolGrantStatus> grants, List<PermissionDecisionTrace> decisions) {
        private GrantCatalog {
            grants = List.copyOf(grants);
            decisions = List.copyOf(decisions);
        }
    }

    private record WorkspaceCatalog(GrantCatalog grants, List<ScheduleContracts.Definition> schedules) {
        private WorkspaceCatalog {
            Objects.requireNonNull(grants, "grants");
            schedules = List.copyOf(schedules);
        }
    }

    private record ResolvedCatalog(AgentRole profile, PermissionProfile permission, ToolCatalogQueryResult catalog) {
        private ResolvedCatalog {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(permission, "permission");
            Objects.requireNonNull(catalog, "catalog");
        }
    }
}
