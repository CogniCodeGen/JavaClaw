package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJobCursor;
import com.javaclaw.protocol.InputJobRpcContracts;

/** 协调全局 Extension Job 过滤、keyset 分页、详情和生命周期动作。 */
public final class AutomationJobSettingsPresenter {
    private static final int PAGE_SIZE = 40;

    private final AutomationJobSettingsGateway gateway;
    private Consumer<AutomationJobSettingsState> listener = ignored -> {};
    private AutomationJobSettingsState state = AutomationJobSettingsState.initial();

    /**
     * 创建 Job Presenter。
     *
     * @param gateway 只通过 Java SDK 实现的异步边界
     */
    public AutomationJobSettingsPresenter(AutomationJobSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    /**
     * 订阅不可变页面状态。
     *
     * @param value 状态监听器
     */
    public void subscribe(Consumer<AutomationJobSettingsState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    /** @return 当前不可变快照 */
    public AutomationJobSettingsState state() {
        return state;
    }

    /** 重新读取 Workspace，并从当前过滤条件的第一页读取 Job。 */
    public void reload() {
        long epoch = nextEpoch();
        publish(withFeedback(SettingsLoadState.LOADING, "正在读取工作区与可恢复后台任务…", epoch));
        gateway.workspaces().whenComplete((workspaces, failure) -> completeWorkspaces(epoch, workspaces, failure));
    }

    /**
     * 应用过滤条件并返回第一页；较早查询会按 epoch 丢弃。
     *
     * @param workspaceId 可选 Workspace
     * @param extensionId 可选精确 Extension 标识
     * @param states 状态集合
     */
    public void filter(Optional<WorkspaceId> workspaceId, Optional<String> extensionId, Set<ExecutionState> states) {
        AutomationJobFilter next = new AutomationJobFilter(workspaceId, extensionId, states);
        requestPage(next, List.of(Optional.empty()), 0, Optional.empty());
    }

    /** 读取下一页；没有下一页时不执行请求。 */
    public void nextPage() {
        state.page().nextCursor().ifPresent(cursor -> {
            List<Optional<ExtensionJobCursor>> starts = new ArrayList<>(
                    state.page().starts().subList(0, state.page().index() + 1));
            starts.add(Optional.of(cursor));
            requestPage(state.filter(), starts, state.page().index() + 1, Optional.empty());
        });
    }

    /** 读取上一页；第一页时不执行请求。 */
    public void previousPage() {
        if (state.page().index() > 0) {
            requestPage(state.filter(), state.page().starts(), state.page().index() - 1, Optional.empty());
        }
    }

    /** 重新读取当前过滤页，并尽量保留当前 Job 选择。 */
    public void refreshPage() {
        Optional<String> selectedId = state.page().selected().map(ExtensionExecutionReceipt::id);
        requestPage(state.filter(), state.page().starts(), state.page().index(), selectedId);
    }

    /**
     * 读取选中 Job 的权威详情。
     *
     * @param job 当前页摘要
     */
    public void select(ExtensionExecutionReceipt job) {
        ExtensionExecutionReceipt selected = requireCurrent(job);
        long epoch = nextEpoch();
        AutomationJobPage page = selectedPage(selected);
        publish(new AutomationJobSettingsState(
                state.workspaces(),
                state.filter(),
                page,
                AutomationJobDetail.empty(),
                feedback(SettingsLoadState.LOADING, "正在读取后台任务工作单元与 检查点…", epoch)));
        readDetail(epoch, selected);
    }

    /** 重新读取冲突 Job 的权威 revision 和时间线。 */
    public void refreshSelected() {
        select(state.page().selected().orElseThrow());
    }

    /** 暂停当前可推进 Job。 */
    public void pause() {
        mutate(JobAction.PAUSE);
    }

    /** 恢复当前暂停或等待中的 Job。 */
    public void resume() {
        mutate(JobAction.RESUME);
    }

    /** 取消当前非终态 Job。 */
    public void cancel() {
        mutate(JobAction.CANCEL);
    }

    /** 页面离开时使所有未完成响应失效，不取消服务端 Job。 */
    public void deactivate() {
        long epoch = nextEpoch();
        publish(withFeedback(SettingsLoadState.READY, "", epoch));
    }

    private void completeWorkspaces(long epoch, List<Workspace> workspaces, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure, false);
            return;
        }
        List<Workspace> sorted = Objects.requireNonNull(workspaces, "workspaces").stream()
                .sorted(Comparator.comparing(Workspace::name))
                .toList();
        AutomationJobFilter filter = availableFilter(state.filter(), sorted);
        requestPage(epoch, sorted, filter, List.of(Optional.empty()), 0, Optional.empty());
    }

    private void requestPage(
            AutomationJobFilter filter,
            List<Optional<ExtensionJobCursor>> starts,
            int index,
            Optional<String> selectedId) {
        requestPage(nextEpoch(), state.workspaces(), filter, starts, index, selectedId);
    }

    private void requestPage(
            long epoch,
            List<Workspace> workspaces,
            AutomationJobFilter filter,
            List<Optional<ExtensionJobCursor>> starts,
            int index,
            Optional<String> selectedId) {
        AutomationJobPage loading =
                new AutomationJobPage(List.of(), List.copyOf(starts), index, Optional.empty(), Optional.empty());
        publish(new AutomationJobSettingsState(
                workspaces,
                filter,
                loading,
                AutomationJobDetail.empty(),
                feedback(SettingsLoadState.LOADING, "正在读取第 " + (index + 1) + " 页后台任务…", epoch)));
        gateway.jobs(filter.workspaceId(), filter.extensionId(), filter.states(), loading.currentStart(), PAGE_SIZE)
                .whenComplete((page, failure) -> completePage(epoch, selectedId, page, failure));
    }

    private void completePage(
            long epoch, Optional<String> selectedId, InputJobRpcContracts.JobListResult result, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure, false);
            return;
        }
        List<ExtensionExecutionReceipt> jobs =
                Objects.requireNonNull(result, "result").jobs();
        Optional<ExtensionExecutionReceipt> selected = selectedId
                .flatMap(id -> jobs.stream().filter(job -> job.id().equals(id)).findFirst())
                .or(() -> jobs.stream().findFirst());
        AutomationJobPage page =
                new AutomationJobPage(jobs, state.page().starts(), state.page().index(), result.nextCursor(), selected);
        String message = jobs.isEmpty() ? "当前过滤条件下暂无可恢复后台任务" : "";
        SettingsLoadState phase = selected.isPresent() ? SettingsLoadState.LOADING : SettingsLoadState.READY;
        publish(new AutomationJobSettingsState(
                state.workspaces(),
                state.filter(),
                page,
                AutomationJobDetail.empty(),
                feedback(phase, selected.isPresent() ? "正在读取后台任务详情…" : message, epoch)));
        selected.ifPresent(job -> readDetail(epoch, job));
    }

    private void readDetail(long epoch, ExtensionExecutionReceipt selected) {
        readDetail(epoch, selected, "");
    }

    private void readDetail(long epoch, ExtensionExecutionReceipt selected, String successMessage) {
        gateway.job(selected.id())
                .whenComplete(
                        (detail, failure) -> completeDetail(epoch, selected.id(), successMessage, detail, failure));
    }

    private void completeDetail(
            long epoch,
            String selectedId,
            String successMessage,
            InputJobRpcContracts.JobReadResult detail,
            Throwable failure) {
        if (stale(epoch) || !selected(selectedId)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure, false);
            return;
        }
        InputJobRpcContracts.JobReadResult checked = Objects.requireNonNull(detail, "detail");
        if (!checked.job().id().equals(selectedId)) {
            fail(epoch, new IllegalStateException("后台任务详情与选择不一致"), false);
            return;
        }
        AutomationJobPage page = replaceReceipt(state.page(), checked.job());
        publish(new AutomationJobSettingsState(
                state.workspaces(),
                state.filter(),
                page,
                new AutomationJobDetail(Optional.of(checked), false),
                feedback(SettingsLoadState.READY, successMessage, epoch)));
    }

    private void mutate(JobAction action) {
        ExtensionExecutionReceipt job = authoritativeSelection();
        if (!action.allowed(job.state())) {
            throw new IllegalStateException("后台任务当前状态不允许" + action.label());
        }
        long epoch = nextEpoch();
        publish(new AutomationJobSettingsState(
                state.workspaces(),
                state.filter(),
                state.page(),
                new AutomationJobDetail(state.detail().value(), false),
                feedback(SettingsLoadState.SAVING, "正在" + action.label() + " 后台任务…", epoch)));
        CommandOptions options = CommandOptions.create(job.revision());
        execute(action, job, options)
                .whenComplete((updated, failure) -> completeMutation(epoch, action, updated, failure));
    }

    private CompletionStage<ExtensionExecutionReceipt> execute(
            JobAction action, ExtensionExecutionReceipt job, CommandOptions options) {
        return switch (action) {
            case PAUSE -> gateway.pause(job, options);
            case RESUME -> gateway.resume(job, options);
            case CANCEL -> gateway.cancel(job, options);
        };
    }

    private void completeMutation(long epoch, JobAction action, ExtensionExecutionReceipt updated, Throwable failure) {
        if (stale(epoch)) {
            return;
        }
        if (failure != null) {
            fail(epoch, failure, SettingsFailures.revisionConflict(failure));
            return;
        }
        ExtensionExecutionReceipt checked = Objects.requireNonNull(updated, "updated");
        if (!selected(checked.id())) {
            fail(epoch, new IllegalStateException("后台任务动作结果与选择不一致"), false);
            return;
        }
        AutomationJobPage page = replaceReceipt(state.page(), checked);
        AutomationJobDetail detail = replaceDetailReceipt(state.detail(), checked);
        publish(new AutomationJobSettingsState(
                state.workspaces(),
                state.filter(),
                page,
                detail,
                feedback(SettingsLoadState.LOADING, action.label() + "成功，正在刷新工作单元…", epoch)));
        readDetail(epoch, checked, action.label() + " 后台任务已提交");
    }

    private void fail(long epoch, Throwable failure, boolean conflict) {
        publish(new AutomationJobSettingsState(
                state.workspaces(),
                state.filter(),
                state.page(),
                new AutomationJobDetail(state.detail().value(), conflict),
                feedback(SettingsLoadState.ERROR, SettingsFailures.message(failure), epoch)));
    }

    private AutomationJobSettingsState withFeedback(SettingsLoadState phase, String message, long epoch) {
        return new AutomationJobSettingsState(
                state.workspaces(), state.filter(), state.page(), state.detail(), feedback(phase, message, epoch));
    }

    private ExtensionExecutionReceipt authoritativeSelection() {
        return state.detail()
                .value()
                .map(InputJobRpcContracts.JobReadResult::job)
                .or(() -> state.page().selected())
                .orElseThrow();
    }

    private ExtensionExecutionReceipt requireCurrent(ExtensionExecutionReceipt requested) {
        String id = Objects.requireNonNull(requested, "requested").id();
        return state.page().jobs().stream()
                .filter(job -> job.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("后台任务已不在当前页"));
    }

    private AutomationJobPage selectedPage(ExtensionExecutionReceipt selected) {
        return new AutomationJobPage(
                state.page().jobs(),
                state.page().starts(),
                state.page().index(),
                state.page().nextCursor(),
                Optional.of(selected));
    }

    private static AutomationJobPage replaceReceipt(AutomationJobPage page, ExtensionExecutionReceipt updated) {
        List<ExtensionExecutionReceipt> jobs = page.jobs().stream()
                .map(job -> job.id().equals(updated.id()) ? updated : job)
                .toList();
        return new AutomationJobPage(jobs, page.starts(), page.index(), page.nextCursor(), Optional.of(updated));
    }

    private static AutomationJobDetail replaceDetailReceipt(
            AutomationJobDetail detail, ExtensionExecutionReceipt updated) {
        Optional<InputJobRpcContracts.JobReadResult> value =
                detail.value().map(current -> new InputJobRpcContracts.JobReadResult(updated, current.units()));
        return new AutomationJobDetail(value, false);
    }

    private static AutomationJobFilter availableFilter(AutomationJobFilter filter, List<Workspace> workspaces) {
        Optional<WorkspaceId> workspaceId = filter.workspaceId()
                .filter(id ->
                        workspaces.stream().anyMatch(workspace -> workspace.id().equals(id)));
        return new AutomationJobFilter(workspaceId, filter.extensionId(), filter.states());
    }

    private boolean selected(String id) {
        return state.page().selected().map(job -> job.id().equals(id)).orElse(false);
    }

    private void publish(AutomationJobSettingsState next) {
        state = Objects.requireNonNull(next, "next");
        listener.accept(next);
    }

    private long nextEpoch() {
        return Math.incrementExact(state.feedback().epoch());
    }

    private boolean stale(long epoch) {
        return epoch != state.feedback().epoch();
    }

    private static AutomationJobFeedback feedback(SettingsLoadState phase, String message, long epoch) {
        return new AutomationJobFeedback(phase, message, epoch);
    }

    private enum JobAction {
        PAUSE("暂停"),
        RESUME("恢复"),
        CANCEL("取消");

        private final String label;

        JobAction(String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }

        private boolean allowed(ExecutionState state) {
            return switch (this) {
                case PAUSE ->
                    state == ExecutionState.QUEUED
                            || state == ExecutionState.RUNNING
                            || state == ExecutionState.WAITING_APPROVAL
                            || state == ExecutionState.WAITING_INPUT;
                case RESUME ->
                    state == ExecutionState.PAUSED
                            || state == ExecutionState.WAITING_APPROVAL
                            || state == ExecutionState.WAITING_INPUT;
                case CANCEL -> !state.terminal();
            };
        }
    }
}
