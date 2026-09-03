package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.client.extension.ScheduleClient;
import com.javaclaw.desktop.DesktopPresenter;

/** 仅通过 Java SDK 分页读取 Schedule 目录的生产实现。 */
public final class SdkScheduleCatalogGateway implements ScheduleCatalogGateway {
    private static final int PAGE_SIZE = 100;
    private static final int MAXIMUM_SCHEDULES = 1_000;

    private final DesktopPresenter desktop;

    /**
     * 创建目录网关。
     *
     * @param desktop 拥有 SDK 会话和后台执行器的 Presenter
     */
    public SdkScheduleCatalogGateway(DesktopPresenter desktop) {
        this.desktop = Objects.requireNonNull(desktop, "desktop");
    }

    @Override
    public CompletionStage<List<ScheduleContracts.Definition>> schedules(WorkspaceId workspaceId) {
        WorkspaceId checked = Objects.requireNonNull(workspaceId, "workspaceId");
        return desktop.submitSettingsRequest(client -> readAll(client.builtins().schedules(), checked));
    }

    private static List<ScheduleContracts.Definition> readAll(ScheduleClient client, WorkspaceId workspaceId) {
        ArrayList<ScheduleContracts.Definition> result = new ArrayList<>();
        String cursor = "";
        while (result.size() < MAXIMUM_SCHEDULES) {
            var page = client.list(workspaceId, cursor, PAGE_SIZE);
            result.addAll(page.documents());
            if (page.nextKey().isBlank()) {
                return sorted(result);
            }
            cursor = page.nextKey();
        }
        throw new IllegalStateException("定时任务目录超过设置中心的一千条安全上限");
    }

    private static List<ScheduleContracts.Definition> sorted(List<ScheduleContracts.Definition> schedules) {
        return schedules.stream()
                .sorted(Comparator.comparing(ScheduleContracts.Definition::id))
                .toList();
    }
}
