package com.javaclaw.infrastructure.settings;

import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.Candidate;
import com.javaclaw.application.settings.TestDataMaintenancePort;
import com.javaclaw.config.LegacyTestDataManager;

import java.io.UncheckedIOException;
import java.util.List;

/** 将受限的历史测试目录清理器适配到应用端口。 */
public final class LegacyTestDataMaintenanceAdapter implements TestDataMaintenancePort {

    @Override
    public List<Candidate> scanDefaultLocations() {
        return LegacyTestDataManager.scanDefaultLocations().stream()
                .map(value -> new Candidate(value.root(), value.path(), value.bytes()))
                .toList();
    }

    @Override
    public int deleteConfirmed(List<Candidate> candidates) {
        var legacy = candidates.stream()
                .map(value -> new LegacyTestDataManager.Candidate(
                        value.root(), value.path(), value.bytes()))
                .toList();
        try {
            return LegacyTestDataManager.deleteConfirmed(legacy);
        } catch (java.io.IOException failure) {
            throw new UncheckedIOException("清理历史测试目录失败", failure);
        }
    }
}
