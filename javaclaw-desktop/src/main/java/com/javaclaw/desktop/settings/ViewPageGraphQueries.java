package com.javaclaw.desktop.settings;

import java.util.function.Consumer;

import javafx.scene.Node;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.view.ViewGraphAction;
import com.javaclaw.desktop.view.ViewRenderSession;
import com.javaclaw.desktop.view.ViewRequestEpoch;
import com.javaclaw.protocol.ExtensionRpcContracts;

/**
 * 图谱邻居查询的页面生命周期；与普通加载共享待处理状态和 epoch，自动对账不能覆盖用户展开。
 *
 * <p>FX 线程拥有状态；离页取消先使 epoch 失效，迟到结果不能修改图谱窗口或解除新请求的待处理状态。
 */
final class ViewPageGraphQueries {
    private final ExtensionSettingsGateway gateway;
    private final ViewRequestEpoch requests;
    private final ViewPageLoadState loads;
    private final ViewSchemaFeedbackPane body;
    private final ViewPageCacheState cache;

    ViewPageGraphQueries(ExtensionSettingsGateway gateway, ViewRequestEpoch requests, ViewPageLoadState loads) {
        this(gateway, requests, loads, null, null);
    }

    ViewPageGraphQueries(
            ExtensionSettingsGateway gateway,
            ViewRequestEpoch requests,
            ViewPageLoadState loads,
            ViewSchemaFeedbackPane body,
            ViewPageCacheState cache) {
        this.gateway = gateway;
        this.requests = requests;
        this.loads = loads;
        this.body = body;
        this.cache = cache;
    }

    void browse(
            WorkspaceId workspace,
            String extension,
            ViewRenderSession owner,
            ViewGraphAction action,
            Node rendered,
            Runnable reload,
            Consumer<Throwable> failed) {
        try {
            if (action.filter().isPresent()) {
                owner.filterGraph(action);
                reload.run();
                return;
            }
            cache.loading();
            body.showLoading(rendered, "正在读取所选节点的邻居");
            load(workspace, extension, owner, action, failure -> {
                if (failure == null) {
                    reload.run();
                } else {
                    failed.accept(failure);
                }
            });
        } catch (RuntimeException failure) {
            failed.accept(failure);
        }
    }

    void load(
            WorkspaceId workspace,
            String extension,
            ViewRenderSession owner,
            ViewGraphAction action,
            Consumer<Throwable> completed) {
        var arguments = owner.neighbors(action);
        String operation = owner.browsing(action.graphId()).neighborsQuery();
        var request = loads.begin(requests.begin(), false);
        try {
            gateway.query(workspace, extension, operation, arguments)
                    .whenComplete((result, failure) -> FxStateDispatcher.dispatch(
                            () -> complete(request, owner, action, result, failure, completed)));
        } catch (RuntimeException failure) {
            complete(request, owner, action, null, failure, completed);
        }
    }

    private void complete(
            ViewPageLoadState.Request request,
            ViewRenderSession owner,
            ViewGraphAction action,
            ExtensionRpcContracts.CallResult result,
            Throwable failure,
            Consumer<Throwable> completed) {
        if (!requests.isCurrent(request.epoch()) || !loads.complete(request, false)) {
            return;
        }
        Throwable outcome = failure;
        if (outcome == null) {
            try {
                owner.acceptNeighbors(action, result.payload());
            } catch (RuntimeException invalid) {
                outcome = invalid;
            }
        }
        // 先释放待处理状态，再由页面读取包含新增节点的权威窗口；失败时也允许之后的显式或定时刷新。
        completed.accept(outcome);
    }
}
