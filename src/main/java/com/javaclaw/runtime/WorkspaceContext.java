package com.javaclaw.runtime;

import com.javaclaw.config.DataManager;
import com.javaclaw.config.WorkspaceManager;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一次工作区运行期所绑定的不可变路径快照。
 *
 * <p>运行期对象不得在执行中反复查询可变的 {@link WorkspaceManager} 单例；切换工作区时
 * 由应用内核重新捕获快照并整体替换运行时，避免一个服务同时看到新旧两套路径。</p>
 */
public record WorkspaceContext(
        String workspaceId,
        Path globalDataRoot,
        Path dataRoot,
        Path browserDir,
        Path screenshotsDir,
        Path logDir) {

    public WorkspaceContext {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        globalDataRoot = normalize(globalDataRoot, "globalDataRoot");
        dataRoot = normalize(dataRoot, "dataRoot");
        browserDir = normalize(browserDir, "browserDir");
        screenshotsDir = normalize(screenshotsDir, "screenshotsDir");
        logDir = normalize(logDir, "logDir");
    }

    /** 从已经完成 reload 的全局路径管理器捕获一致快照。 */
    public static WorkspaceContext captureCurrent() {
        WorkspaceManager workspaces = WorkspaceManager.getInstance();
        DataManager data = DataManager.getInstance();
        return new WorkspaceContext(
                workspaces.getCurrentWorkspaceId(),
                workspaces.getGlobalDataPath(),
                data.getDataRoot(),
                workspaces.getCurrentBrowserDir(),
                data.getScreenshotsDir(),
                workspaces.getCurrentLogDir());
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name + " 不能为空").toAbsolutePath().normalize();
    }
}
