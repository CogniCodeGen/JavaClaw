package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.client.CommandOptions;

/**
 * Agent Studio 异步状态机；所有保存均通过 SDK，内置角色不能进入更新或归档路径。
 *
 * <p>草稿始终独立于目录刷新；写入和比较绑定请求代次，旧响应不能替换之后的选择或草稿。
 */
public final class AgentRoleSettingsPresenter {
    private final CoreSettingsGateway gateway;
    private Consumer<AgentRoleSettingsState> listener = ignored -> {};
    private AgentRoleSettingsState state = AgentRoleSettingsState.initial();
    private boolean externalPending;
    private boolean disposed;

    /** @param gateway SDK 异步边界 */
    public AgentRoleSettingsPresenter(CoreSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /** @param value 状态订阅者，立即收到当前快照 */
    public void subscribe(Consumer<AgentRoleSettingsState> value) {
        listener = Objects.requireNonNull(value, "value");
        listener.accept(state);
    }

    /** 并行读取角色和可选模型目录；未保存草稿及进行中的操作不会被刷新覆盖。 */
    public void reload() {
        if (!canReplaceDraft()) {
            return;
        }
        long epoch = state.epoch() + 1;
        status(SettingsLoadState.LOADING, "正在读取 Agent…", false, epoch);
        gateway.roles()
                .thenCombine(gateway.providers(), Catalog::new)
                .whenComplete((catalog, failure) -> completeReload(epoch, catalog, failure));
    }

    /** 配置失效只重验脏表单的目录，保留原角色版本及完整草稿。 */
    CompletionStage<Boolean> refreshForConfigurationChange(boolean preserveDraft) {
        if (operationPending()) {
            return CompletableFuture.completedFuture(false);
        }
        AgentRoleSettingsState before = state;
        long epoch = before.epoch() + 1;
        status(SettingsLoadState.LOADING, "正在更新角色与模型目录；草稿保持不变…", before.revisionConflict(), epoch);
        return gateway.roles().thenCombine(gateway.providers(), Catalog::new).handle((catalog, error) -> {
            if (!current(epoch)) {
                return false;
            }
            if (!preserveDraft && !state.dirty() && !state.revisionConflict()) {
                completeReload(epoch, catalog, error);
                return error == null;
            }
            if (error != null) {
                status(
                        SettingsLoadState.ERROR,
                        "目录读取失败；草稿仍保留：" + SettingsFailures.message(error),
                        state.revisionConflict(),
                        epoch);
                return false;
            }
            publish(new AgentRoleSettingsState(
                    SettingsLoadState.READY,
                    catalog.roles(),
                    catalog.providers(),
                    state.selected(),
                    state.creating(),
                    state.baseline(),
                    state.draft(),
                    "角色或模型已更新；目录已刷新，当前草稿和保存版本保持不变。",
                    state.revisionConflict(),
                    epoch));
            return true;
        });
    }

    /** @param role 要读取的权威角色，存在草稿时拒绝切换 */
    public void select(AgentRole role) {
        if (!canReplaceDraft()) {
            return;
        }
        selected(Objects.requireNonNull(role, "role"), state.roles(), "");
    }

    /** 开始创建可继承执行配置的角色。 */
    public void createDraft() {
        if (!canReplaceDraft()) {
            return;
        }
        AgentRoleDraft empty = AgentRoleDraft.empty();
        publish(new AgentRoleSettingsState(
                SettingsLoadState.READY,
                state.roles(),
                state.providers(),
                Optional.empty(),
                true,
                empty,
                empty,
                "填写新 Agent",
                false,
                state.epoch() + 1));
    }

    /** @param draft 替换表单草稿，内置角色始终拒绝修改 */
    public void updateDraft(AgentRoleDraft draft) {
        if (state.readOnly() || operationPending()) {
            return;
        }
        publish(new AgentRoleSettingsState(
                SettingsLoadState.READY,
                state.roles(),
                state.providers(),
                state.selected(),
                state.creating(),
                state.baseline(),
                draft,
                "",
                false,
                state.epoch()));
    }

    /** 保存一个用户角色的新 revision；不会修改活动 Turn。 */
    public void save() {
        if (state.readOnly() || operationPending() || !state.dirty()) {
            return;
        }
        AgentRoleSpec spec;
        try {
            requireId(state.draft().id());
            spec = state.draft().toSpec();
        } catch (RuntimeException invalid) {
            failure(invalid);
            return;
        }
        long request = beginWrite("正在保存 Agent…");
        String target = state.draft().id();
        if (state.selected().isEmpty()) {
            gateway.createRole(target, spec, CommandOptions.create(0))
                    .whenComplete((role, error) -> completeWrite(request, target, role, error));
        } else {
            AgentRole role = state.selected().orElseThrow();
            gateway.updateRole(role.id(), spec, state.draft().lifecycle(), CommandOptions.create(role.revision()))
                    .whenComplete((saved, error) -> completeWrite(request, target, saved, error));
        }
    }

    /**
     * 复制当前精确版本的角色。
     *
     * @param id 新角色标识
     * @param name 新角色名称
     */
    public void cloneSelected(String id, String name) {
        if (!canReplaceDraft()) {
            return;
        }
        AgentRole role = state.selected().orElseThrow();
        try {
            requireId(id);
        } catch (RuntimeException invalid) {
            failure(invalid);
            return;
        }
        long request = beginWrite("正在复制 Agent…");
        gateway.cloneRole(new AgentRoleRef(role.id(), role.revision()), id, name, CommandOptions.create(0))
                .whenComplete((saved, error) -> completeWrite(request, id, saved, error));
    }

    /** 归档用户角色；内置版本始终只读。 */
    public void archive() {
        if (state.readOnly() || !canReplaceDraft()) {
            return;
        }
        AgentRole role = state.selected().orElseThrow();
        long request = beginWrite("正在归档 Agent…");
        gateway.archiveRole(role.id(), CommandOptions.create(role.revision()))
                .whenComplete((saved, error) -> completeWrite(request, role.id(), saved, error));
    }

    /** 恢复当前权威角色。 */
    public void discardDraft() {
        if (operationPending()) {
            return;
        }
        publish(new AgentRoleSettingsState(
                SettingsLoadState.READY,
                state.roles(),
                state.providers(),
                state.selected(),
                false,
                state.baseline(),
                state.baseline(),
                "本地草稿已丢弃",
                false,
                state.epoch() + 1));
    }

    /** 保留草稿并提示先保存或放弃。 */
    public void warnUnsavedChanges() {
        status(state.phase(), "请先保存或放弃 Agent 草稿", state.revisionConflict(), state.epoch());
    }

    /**
     * 合并导入返回的权威角色；其他角色的草稿和保存所用 revision 保持不变。
     *
     * @param role SDK 成功提交的角色版本
     */
    public void acceptImported(AgentRole role) {
        AgentRole imported = Objects.requireNonNull(role, "role");
        if (disposed
                || state.roles().stream()
                        .anyMatch(existing ->
                                existing.id().equals(imported.id()) && existing.revision() > imported.revision())) {
            return;
        }
        List<AgentRole> roles = replace(imported);
        boolean sameRole =
                state.selected().map(value -> value.id().equals(imported.id())).orElse(false);
        if (sameRole && !state.dirty() && !state.pending()) {
            selected(imported, roles, "已导入 Agent 版本 " + imported.revision());
            return;
        }
        publish(new AgentRoleSettingsState(
                state.phase(),
                roles,
                state.providers(),
                state.selected(),
                state.creating(),
                state.baseline(),
                state.draft(),
                "已导入 " + imported.id() + "；当前草稿保持不变",
                state.revisionConflict(),
                state.epoch()));
    }

    /** 停止接收页面关闭后的异步响应，不撤销已经提交到服务端的操作。 */
    public void dispose() {
        disposed = true;
        listener = ignored -> {};
    }

    void externalPending(boolean pending) {
        externalPending = pending;
    }

    void compare(Consumer<AgentRoleComparison> compared) {
        Objects.requireNonNull(compared, "compared");
        if (operationPending() || state.selected().isEmpty()) {
            return;
        }
        AgentRole selected = state.selected().orElseThrow();
        AgentRoleDraft draft = state.draft();
        boolean conflict = state.revisionConflict();
        long request = state.epoch() + 1;
        status(SettingsLoadState.LOADING, "正在读取服务端角色以比较…", conflict, request);
        gateway.roles().whenComplete((roles, error) -> {
            if (!current(request)) {
                return;
            }
            if (error != null) {
                status(SettingsLoadState.ERROR, SettingsFailures.message(error), conflict, request);
                return;
            }
            Optional<AgentRole> latest = roles.stream()
                    .filter(role -> role.id().equals(selected.id()))
                    .findFirst();
            status(SettingsLoadState.READY, "已读取比较快照；本地草稿和保存版本均未改变", conflict, request);
            compared.accept(new AgentRoleComparison(selected.revision(), draft, latest));
        });
    }

    /** @return 当前不可变状态 */
    public AgentRoleSettingsState state() {
        return state;
    }

    private void completeReload(long epoch, Catalog catalog, Throwable error) {
        if (!current(epoch)) {
            return;
        }
        if (error != null) {
            failure(error);
            return;
        }
        List<AgentRole> roles = catalog.roles().stream()
                .sorted(Comparator.comparing(AgentRole::id))
                .toList();
        Optional<AgentRole> role = state.selected()
                .flatMap(previous -> roles.stream()
                        .filter(candidate -> candidate.id().equals(previous.id()))
                        .findFirst())
                .or(() -> roles.stream()
                        .filter(candidate -> candidate.id().equals("default"))
                        .findFirst())
                .or(() -> roles.stream().findFirst());
        AgentRoleDraft draft = role.map(AgentRoleDraft::from).orElseGet(AgentRoleDraft::empty);
        publish(new AgentRoleSettingsState(
                SettingsLoadState.READY, roles, catalog.providers(), role, false, draft, draft, "", false, epoch));
    }

    private void completeWrite(long request, String target, AgentRole role, Throwable error) {
        if (!current(request)) {
            return;
        }
        if (error != null) {
            failure(error);
            return;
        }
        if (!target.equals(role.id())) {
            failure(new IllegalStateException("服务端返回的 Agent 与请求目标不一致"));
            return;
        }
        selected(role, replace(role), "Agent 已保存为版本 " + role.revision());
    }

    private List<AgentRole> replace(AgentRole role) {
        List<AgentRole> roles = new ArrayList<>(state.roles());
        roles.removeIf(candidate -> candidate.id().equals(role.id()));
        roles.add(role);
        roles.sort(Comparator.comparing(AgentRole::id));
        return List.copyOf(roles);
    }

    private boolean current(long request) {
        return !disposed && request == state.epoch();
    }

    private boolean operationPending() {
        return disposed || externalPending || state.pending();
    }

    private boolean canReplaceDraft() {
        if (operationPending()) {
            return false;
        }
        if (state.dirty()) {
            warnUnsavedChanges();
            return false;
        }
        return true;
    }

    private long beginWrite(String message) {
        long request = state.epoch() + 1;
        status(SettingsLoadState.SAVING, message, false, request);
        return request;
    }

    private void selected(AgentRole role, List<AgentRole> roles, String message) {
        AgentRoleDraft draft = AgentRoleDraft.from(role);
        publish(new AgentRoleSettingsState(
                SettingsLoadState.READY,
                roles,
                state.providers(),
                Optional.of(role),
                false,
                draft,
                draft,
                message,
                false,
                state.epoch() + 1));
    }

    private void failure(Throwable error) {
        status(
                SettingsLoadState.ERROR,
                SettingsFailures.message(error),
                SettingsFailures.revisionConflict(error),
                state.epoch());
    }

    private void status(SettingsLoadState phase, String message, boolean conflict, long epoch) {
        publish(new AgentRoleSettingsState(
                phase,
                state.roles(),
                state.providers(),
                state.selected(),
                state.creating(),
                state.baseline(),
                state.draft(),
                message,
                conflict,
                epoch));
    }

    private static void requireId(String value) {
        if (!Objects.requireNonNullElse(value, "").matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Agent 标识只能包含字母、数字、点、下划线和连字符");
        }
    }

    private void publish(AgentRoleSettingsState next) {
        state = next;
        listener.accept(next);
    }

    private record Catalog(List<AgentRole> roles, List<ProviderEndpoint> providers) {}
}
