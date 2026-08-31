package com.javaclaw.desktop;

import java.util.Objects;
import java.util.function.Consumer;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

/** 为领域页提供统一 dirty、保存、选择切换和释放行为的 SDK-only JavaFX 基类。 */
abstract class ManagedManagementPage extends BorderPane implements ManagementPageLifecycle {
    protected final ManagementViewModel model;
    private final BooleanProperty dirty = new SimpleBooleanProperty();
    private final BooleanProperty canSave = new SimpleBooleanProperty();
    private final ObjectProperty<LoadState> loadState = new SimpleObjectProperty<>(LoadState.INITIAL_LOADING);
    private ManagementEditSession editSession;
    private ManagementPageLifecycle delegate;

    ManagedManagementPage(ManagementViewModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    @Override
    public final ReadOnlyBooleanProperty dirtyProperty() {
        return dirty;
    }

    @Override
    public final ReadOnlyBooleanProperty canSaveProperty() {
        return canSave;
    }

    @Override
    public final ReadOnlyObjectProperty<LoadState> loadStateProperty() {
        return loadState;
    }

    @Override
    public String currentResource() {
        if (delegate != null) {
            return delegate.currentResource();
        }
        return editSession == null ? "当前页面" : editSession.resource();
    }

    @Override
    public final void requestSave(Consumer<Boolean> completion) {
        if (delegate != null) {
            delegate.requestSave(completion);
        } else if (editSession == null) {
            completion.accept(true);
        } else {
            editSession.requestSave(completion);
        }
    }

    @Override
    public final void discard() {
        if (delegate != null) {
            delegate.discard();
        } else if (editSession != null) {
            editSession.discard();
        }
    }

    @Override
    public void cancelReads() {
        // 页面代次在 Controller 中统一推进；旧读取结果不会覆盖新页面。
    }

    @Override
    public void dispose() {
        clearEditor();
        if (delegate != null) {
            delegate.dispose();
            delegate = null;
        }
    }

    /** 创建并激活一个资源编辑会话；正文控件和结构化列表会自动纳入 dirty 快照。 */
    protected final ManagementEditSession editSession(
            String resource,
            Node fields,
            Button saveButton,
            Runnable reload,
            javafx.beans.value.ObservableValue<?>... extra) {
        clearEditor();
        var session = new ManagementEditSession(model, resource, saveButton, reload);
        session.install(fields);
        session.watchTree(fields);
        for (var value : extra) {
            session.watch(value);
        }
        editSession = session;
        dirty.bind(session.dirtyProperty());
        canSave.bind(session.canSaveProperty());
        loadState.set(LoadState.READY);
        return session;
    }

    /** 将当前资源的草稿基线推进到服务端已确认版本，并唤醒等待离开的导航动作。 */
    protected final void saveSucceeded() {
        if (editSession != null) {
            editSession.saveSucceeded();
        }
    }

    /** 保留当前草稿并结束保存等待；revision 冲突会被会话单独记录。 */
    protected final void saveFailed(Throwable failure) {
        if (editSession != null) {
            editSession.saveFailed(failure);
        }
    }

    /** 将嵌套领域页作为当前页面生命周期来源，例如设置页中的 Provider 编辑器。 */
    protected final void delegateTo(ManagementPageLifecycle page) {
        clearEditor();
        if (delegate != null) {
            delegate.dispose();
        }
        delegate = page;
        if (page == null) {
            dirty.set(false);
            canSave.set(false);
            return;
        }
        dirty.bind(page.dirtyProperty());
        canSave.bind(page.canSaveProperty());
        loadState.bind(page.loadStateProperty());
    }

    /** 在列表选择变化前执行统一未保存检查；取消时恢复原选择，保存成功后再切换。 */
    protected final <T> void guardSelection(ListView<T> list, Consumer<T> open) {
        var internal = new boolean[1];
        list.getSelectionModel().selectedItemProperty().addListener((ignored, old, value) -> {
            if (internal[0] || value == null) {
                return;
            }
            if (!dirty.get()) {
                open.accept(value);
                return;
            }
            internal[0] = true;
            list.getSelectionModel().select(old);
            internal[0] = false;
            requestNavigation(() -> {
                internal[0] = true;
                list.getSelectionModel().select(value);
                internal[0] = false;
                open.accept(value);
            });
        });
    }

    /** 在创建新对象、切换页签等离开动作前执行统一未保存检查。 */
    protected final void requestNavigation(Runnable continuation) {
        if (!dirty.get()) {
            continuation.run();
            return;
        }
        switch (model.dialogs().resolveUnsavedChanges(this, currentResource())) {
            case SAVE ->
                requestSave(success -> {
                    if (success) {
                        continuation.run();
                    }
                });
            case DISCARD -> {
                discard();
                continuation.run();
            }
            case CANCEL -> {
                // 用户保留当前编辑上下文。
            }
        }
    }

    /** 标记首次内容已加载。 */
    protected final void ready() {
        if (!loadState.isBound()) {
            loadState.set(LoadState.READY);
        }
    }

    /** 标记刷新保留现有内容；该状态不锁定无关资源。 */
    protected final void refreshing() {
        if (!loadState.isBound() && loadState.get() != LoadState.INITIAL_LOADING) {
            loadState.set(LoadState.REFRESHING);
        }
    }

    /** 标记读取失败；本地编辑草稿不会因此清空。 */
    protected final void loadFailed() {
        if (!loadState.isBound()) {
            loadState.set(LoadState.ERROR);
        }
    }

    private void clearEditor() {
        if (dirty.isBound()) {
            dirty.unbind();
        }
        if (canSave.isBound()) {
            canSave.unbind();
        }
        if (loadState.isBound()) {
            loadState.unbind();
        }
        if (editSession != null) {
            editSession.dispose();
            editSession = null;
        }
        dirty.set(false);
        canSave.set(false);
    }
}
