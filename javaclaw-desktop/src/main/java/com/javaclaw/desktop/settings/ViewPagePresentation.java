package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import javafx.scene.Node;

import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewRenderLayout;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 平台布局与外部草稿的本地协调状态；不改变 ViewSchema 查询或命令声明。 */
final class ViewPagePresentation {
    private ViewRenderLayout layout;
    private BooleanSupplier dirty = () -> false;
    private BooleanSupplier pending = () -> false;
    private Runnable discard = () -> {};
    private Runnable changed = () -> {};
    private BiConsumer<ViewCommandInvocation, ExtensionRpcContracts.CallResult> succeeded = (invocation, result) -> {};
    private boolean notifying;
    private boolean completing;

    void configure(
            ViewRenderLayout value,
            BooleanSupplier draft,
            BooleanSupplier operation,
            Runnable clear,
            boolean unloaded) {
        if (!unloaded) {
            throw new IllegalStateException("布局必须在页面加载前配置");
        }
        layout = Objects.requireNonNull(value, "layout");
        dirty = Objects.requireNonNull(draft, "externalDirty");
        pending = Objects.requireNonNull(operation, "externalPending");
        discard = Objects.requireNonNull(clear, "discardExternal");
    }

    ViewRenderLayout layout() {
        return layout;
    }

    boolean dirty() {
        return dirty.getAsBoolean();
    }

    boolean pending() {
        return pending.getAsBoolean();
    }

    void discard() {
        discard.run();
    }

    void onChanged(Runnable listener) {
        changed = Objects.requireNonNull(listener, "listener");
    }

    void changed() {
        if (!notifying) {
            notifying = true;
            try {
                changed.run();
            } finally {
                notifying = false;
            }
        }
    }

    void onSucceeded(BiConsumer<ViewCommandInvocation, ExtensionRpcContracts.CallResult> listener) {
        succeeded = Objects.requireNonNull(listener, "listener");
    }

    void succeeded(ViewCommandInvocation invocation, ExtensionRpcContracts.CallResult result) {
        completing = true;
        try {
            succeeded.accept(invocation, result);
        } finally {
            completing = false;
        }
    }

    boolean completing() {
        return completing;
    }

    boolean confirm(Node root, boolean blocked, boolean localDirty, Runnable discardLocal) {
        if (blocked || pending()) {
            return false;
        }
        if (!localDirty && !dirty()) {
            return true;
        }
        if (!ViewSchemaConfirmation.discard(root)) {
            return false;
        }
        discard();
        discardLocal.run();
        changed();
        return true;
    }

    boolean commandAllowed(
            ViewSchema schema, Set<String> dirtyForms, Optional<String> formId, ViewCommandInvocation invocation) {
        if (dirty()) {
            return false;
        }
        if (dirtyForms.isEmpty()) {
            return true;
        }
        return formId.filter(id -> dirtyForms.equals(Set.of(id))).isPresent()
                && schema.nodes().stream()
                        .filter(ViewSchema.Form.class::isInstance)
                        .map(ViewSchema.Form.class::cast)
                        .anyMatch(form -> form.id().equals(formId.orElseThrow())
                                && form.submit().command().equals(invocation.operation()));
    }
}
