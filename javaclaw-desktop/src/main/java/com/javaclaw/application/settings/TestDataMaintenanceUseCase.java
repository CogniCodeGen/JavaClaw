package com.javaclaw.application.settings;

import com.javaclaw.application.error.RejectedException;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.Candidate;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.CleanupResult;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.ScanResult;

import java.util.List;
import java.util.Objects;

/** 历史测试数据维护用例。 */
public final class TestDataMaintenanceUseCase
        implements TestDataMaintenanceApplicationService {

    private final TestDataMaintenancePort maintenance;

    public TestDataMaintenanceUseCase(TestDataMaintenancePort maintenance) {
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    @Override
    public ScanResult scan() {
        List<Candidate> candidates = List.copyOf(maintenance.scanDefaultLocations());
        long bytes = candidates.stream().mapToLong(Candidate::bytes).sum();
        return new ScanResult(candidates, bytes);
    }

    @Override
    public CleanupResult cleanup(List<Candidate> candidates) {
        List<Candidate> confirmed = List.copyOf(candidates == null ? List.of() : candidates);
        if (confirmed.isEmpty()) {
            throw new RejectedException("没有可清理的历史测试目录");
        }
        try {
            return new CleanupResult(maintenance.deleteConfirmed(confirmed));
        } catch (SecurityException rejected) {
            throw new RejectedException("候选目录已变化，已拒绝清理", rejected);
        }
    }
}
