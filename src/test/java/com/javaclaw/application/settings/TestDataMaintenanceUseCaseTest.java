package com.javaclaw.application.settings;

import com.javaclaw.application.error.RejectedException;
import com.javaclaw.application.settings.TestDataMaintenanceApplicationService.Candidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestDataMaintenanceUseCaseTest {

    @TempDir Path temp;

    @Test
    void scanReturnsImmutableAggregateAndCleanupUsesExactCandidates() {
        Candidate first = new Candidate(temp, temp.resolve("junit-one"), 1024);
        Candidate second = new Candidate(temp, temp.resolve("junit-two"), 2048);
        FakePort port = new FakePort(List.of(first, second));
        TestDataMaintenanceUseCase useCase = new TestDataMaintenanceUseCase(port);

        var scan = useCase.scan();

        assertEquals(2, scan.candidates().size());
        assertEquals(3072, scan.totalBytes());
        assertThrows(UnsupportedOperationException.class,
                () -> scan.candidates().add(first));
        assertEquals(2, useCase.cleanup(scan.candidates()).deleted());
        assertEquals(scan.candidates(), port.deleted);
    }

    @Test
    void emptyAndRevalidatedCandidatesAreRejected() {
        FakePort port = new FakePort(List.of());
        TestDataMaintenanceUseCase useCase = new TestDataMaintenanceUseCase(port);
        assertThrows(RejectedException.class, () -> useCase.cleanup(List.of()));

        Candidate forged = new Candidate(temp, temp.resolve("junit-forged"), 0);
        port.reject = true;
        assertThrows(RejectedException.class, () -> useCase.cleanup(List.of(forged)));
    }

    private static final class FakePort implements TestDataMaintenancePort {
        private final List<Candidate> scanned;
        private List<Candidate> deleted = List.of();
        private boolean reject;

        private FakePort(List<Candidate> scanned) { this.scanned = scanned; }
        @Override public List<Candidate> scanDefaultLocations() { return scanned; }
        @Override public int deleteConfirmed(List<Candidate> candidates) {
            if (reject) throw new SecurityException("changed");
            deleted = List.copyOf(candidates);
            return deleted.size();
        }
    }
}
