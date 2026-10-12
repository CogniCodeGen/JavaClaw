package com.javaclaw.application.settings;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 历史测试数据维护用例入口。
 *
 * <p>调用会执行文件 I/O，必须由托管 I/O 执行器调用。扫描只读；清理仅接受最近扫描得到的
 * 不可变候选，并在基础设施层再次校验路径边界与数据库标记。</p>
 */
public interface TestDataMaintenanceApplicationService {

    ScanResult scan();

    CleanupResult cleanup(List<Candidate> candidates);

    record Candidate(Path root, Path path, long bytes) {
        public Candidate {
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            bytes = Math.max(0, bytes);
        }
    }

    record ScanResult(List<Candidate> candidates, long totalBytes) {
        public ScanResult {
            candidates = List.copyOf(candidates == null ? List.of() : candidates);
            totalBytes = Math.max(0, totalBytes);
        }
    }

    record CleanupResult(int deleted) {
        public CleanupResult {
            deleted = Math.max(0, deleted);
        }
    }
}
