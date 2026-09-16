package com.javaclaw.builtin.contracts;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;

/** 使用固定的所有者、代次和图片元数据，使 wire 变化能通过真实 codec 往返暴露。 */
final class BrowserContractFixtures {
    static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    static final URI ORIGIN = URI.create("https://example.com");
    static final String SESSION = "65b83f86-72c2-43c4-a980-154481e43020";
    static final WorkspaceId WORKSPACE = WorkspaceId.parse("a8b59c6f-c222-4617-8998-2257303be83e");
    static final ThreadId THREAD = ThreadId.parse("32007044-0f3b-442f-be6e-dcb21c34c3c0");

    private BrowserContractFixtures() {}

    static BrowserContracts.Owner owner() {
        return new BrowserContracts.Owner(
                WORKSPACE, THREAD, Optional.of(new BrowserContracts.AccountBinding("work", 2)));
    }

    static BrowserContracts.AccessLease lease() {
        return new BrowserContracts.AccessLease(
                BrowserContracts.ControlMode.ASSISTANT, "lease", 3, NOW.plusSeconds(60), Set.of(ORIGIN));
    }

    static BrowserContracts.SessionView session() {
        return new BrowserContracts.SessionView(
                SESSION,
                owner(),
                BrowserContracts.SessionState.OPEN,
                lease(),
                List.of(new BrowserContracts.Tab("page", ORIGIN.resolve("/docs"), "文档", true)));
    }

    static BrowserContracts.PageSnapshot page() {
        return new BrowserContracts.PageSnapshot(
                "page",
                ORIGIN.resolve("/docs"),
                "文档",
                "可见正文",
                List.of(new BrowserContracts.Element("r1", "checkbox", "同意", "input", true, Optional.of(false))),
                List.of(new BrowserContracts.DownloadInfo("download", "report.pdf")));
    }

    static BrowserContracts.Frame frame() {
        return new BrowserContracts.Frame(
                "frame", "page", 7, 3, new BrowserContracts.Viewport(1280, 900, 0, 100, 2), 2560, 1800);
    }

    static BrowserContracts.Observation observation(boolean frame, boolean artifact) {
        return new BrowserContracts.Observation(
                session(),
                page(),
                frame ? Optional.of(frame()) : Optional.empty(),
                artifact
                        ? Optional.of(new BrowserContracts.Artifact(
                                new BrowserContracts.FileSpec("browser.png", "image/png"), 128))
                        : Optional.empty());
    }

    static AttachmentRef attachment() {
        return new AttachmentRef("a".repeat(64), "image/png", "browser.png", 128);
    }
}
