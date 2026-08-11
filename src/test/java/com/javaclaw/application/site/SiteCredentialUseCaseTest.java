package com.javaclaw.application.site;

import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.ValidationException;
import com.javaclaw.application.site.SiteCredentialApplicationService.SaveCommand;
import com.javaclaw.application.site.SiteCredentialPort.Entry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteCredentialUseCaseTest {

    @Test
    void validatesNormalizesAndReturnsImmutableSnapshots() {
        FakePort port = new FakePort();
        SiteCredentialUseCase useCase = new SiteCredentialUseCase(port);

        var created = useCase.save(new SaveCommand("", " GitHub ", "GITHUB.COM",
                "https://github.com/login", "octo", "secret", " work "));

        assertEquals(1, created.credentials().size());
        assertEquals("github.com", created.credentials().getFirst().hostPattern());
        assertEquals("work", created.credentials().getFirst().notes());
        assertThrows(UnsupportedOperationException.class,
                () -> created.credentials().add(created.credentials().getFirst()));
        assertThrows(ValidationException.class, () -> useCase.save(
                new SaveCommand("", "", "github.com", "", "", "", "")));
        assertEquals("github.com", useCase.save(new SaveCommand(
                "", "GitHub 2", "https://github.com/path", "", "", "", ""))
                .credentials().getLast().hostPattern());
        assertThrows(ValidationException.class, () -> useCase.save(
                new SaveCommand("", "GitHub", "github.com", "ftp://github.com", "", "", "")));
    }

    @Test
    void updateResetAndDeleteRequireExistingCredential() {
        FakePort port = new FakePort();
        port.entries.add(new Entry("one", "站点", "example.com", null,
                "", "", null, 10, 20, true));
        SiteCredentialUseCase useCase = new SiteCredentialUseCase(port);

        var updated = useCase.save(new SaveCommand("one", "新名称", "*.example.com",
                "", "", "", ""));
        assertEquals("新名称", updated.require("one").name());
        assertTrue(updated.require("one").hasSession());

        assertFalse(useCase.clearSession("one").require("one").hasSession());
        assertTrue(useCase.delete("one").credentials().isEmpty());
        assertThrows(NotFoundException.class, () -> useCase.delete("missing"));
        assertThrows(NotFoundException.class, () -> useCase.clearSession("missing"));
        assertThrows(NotFoundException.class, () -> useCase.save(new SaveCommand(
                "missing", "名称", "example.com", "", "", "", "")));
    }

    private static final class FakePort implements SiteCredentialPort {
        private final List<Entry> entries = new ArrayList<>();
        private int sequence;

        @Override public synchronized List<Entry> list() { return List.copyOf(entries); }

        @Override
        public synchronized Entry save(Entry entry) {
            String id = entry.id() == null || entry.id().isBlank()
                    ? "site-" + (++sequence) : entry.id();
            Entry saved = new Entry(id, entry.name(), entry.hostPattern(), entry.loginUrl(),
                    entry.username(), entry.password(), entry.notes(),
                    entry.createdAt() == 0 ? 100 : entry.createdAt(), entry.lastUsedAt(),
                    entry.hasSession());
            entries.removeIf(item -> item.id().equals(id));
            entries.add(saved);
            return saved;
        }

        @Override public synchronized boolean delete(String id) {
            return entries.removeIf(entry -> entry.id().equals(id));
        }

        @Override public synchronized boolean clearSession(String id) {
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                if (!entry.id().equals(id)) continue;
                entries.set(index, new Entry(entry.id(), entry.name(), entry.hostPattern(),
                        entry.loginUrl(), entry.username(), entry.password(), entry.notes(),
                        entry.createdAt(), entry.lastUsedAt(), false));
                return true;
            }
            return false;
        }

        @Override public String storageDescription() { return "fake-h2"; }
    }
}
