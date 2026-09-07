package com.javaclaw.nativehost.ffm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsFileAclPolicyTest {
    @Test
    void writableRootCannotBeReplacedButItsChildrenCanStillBeDeletedWhenApproved() {
        var entries = WindowsFileAclPolicy.entries(true, 0x001301bf, 0);
        int rootAllow = entries.stream()
                .filter(entry -> entry.mode() == 1 && (entry.inheritance() & 8) == 0)
                .mapToInt(WindowsFileAclPolicy.Entry::permissions)
                .reduce(0, (left, right) -> left | right);
        int rootDeny = entries.stream()
                .filter(entry -> entry.mode() == 3 && entry.inheritance() == 0)
                .mapToInt(WindowsFileAclPolicy.Entry::permissions)
                .reduce(0, (left, right) -> left | right);

        assertEquals(0, rootAllow & 0x000d0000);
        assertEquals(0x000d0000, rootDeny & 0x000d0000);
        assertTrue(entries.stream()
                .anyMatch(
                        entry -> entry.mode() == 1 && entry.inheritance() == 11 && entry.permissions() == 0x00010000));
    }

    @Test
    void readOnlyRootsDoNotAcquireWriteOrDeletionEntries() {
        var entries = WindowsFileAclPolicy.entries(true, 0x001200a9, 0);

        assertEquals(1, entries.size());
        assertEquals(0x001200a9, entries.getFirst().permissions());
    }
}
