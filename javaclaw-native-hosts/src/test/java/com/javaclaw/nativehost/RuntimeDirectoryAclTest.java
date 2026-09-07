package com.javaclaw.nativehost;

import java.io.IOException;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDirectoryAclTest {
    private static final Principal OWNER = new Principal("user-sid", "machine\\owner");
    private static final Principal SYSTEM = new Principal("S-1-5-18", "NT AUTHORITY\\SYSTEM");
    private static final Principal ADMINISTRATORS = new Principal("S-1-5-32-544", "BUILTIN\\Administrators");
    private static final Principal EVERYONE = new Principal("S-1-1-0", "Everyone");

    @Test
    void initialAclGrantsOnlyOwnerAndInheritsToFilesAndDirectories() {
        var attribute = RuntimeDirectoryAcl.initialAttribute(OWNER);
        assertEquals("acl:acl", attribute.name());
        assertEquals(1, attribute.value().size());
        AclEntry entry = attribute.value().getFirst();
        assertEquals(OWNER, entry.principal());
        assertEquals(AclEntryType.ALLOW, entry.type());
        assertEquals(EnumSet.allOf(AclEntryPermission.class), entry.permissions());
        assertEquals(Set.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT), entry.flags());
    }

    @Test
    void creationReplacesInheritedGrantsAndReadsBackTheOwnerOnlyAcl() throws Exception {
        MemoryAcl view = new MemoryAcl(List.of(allow(EVERYONE, AclEntryPermission.WRITE_DATA)));

        RuntimeDirectoryAcl.initialize(view, OWNER);

        assertEquals(RuntimeDirectoryAcl.initialAttribute(OWNER).value(), view.getAcl());
        assertTrue(view.updated);
        assertDoesNotThrow(() -> RuntimeDirectoryAcl.validate(view, OWNER, new Lookup()));
    }

    @Test
    void creationFailsWhenProviderRetainsInheritedPermissionOrCannotWriteAcl() {
        MemoryAcl retained = new MemoryAcl(List.of());
        retained.retainEveryone = true;
        assertThrows(IOException.class, () -> RuntimeDirectoryAcl.initialize(retained, OWNER));
        MemoryAcl failed = new MemoryAcl(List.of());
        failed.writeFailure = true;
        assertThrows(IOException.class, () -> RuntimeDirectoryAcl.initialize(failed, OWNER));
    }

    @Test
    void existingAclAcceptsOnlyResolvedOwnerSystemAndLocalAdministratorsForMutation() {
        MemoryAcl view = new MemoryAcl(List.of(
                allow(OWNER, AclEntryPermission.WRITE_DATA),
                allow(SYSTEM, AclEntryPermission.DELETE),
                allow(ADMINISTRATORS, AclEntryPermission.WRITE_ACL),
                allow(EVERYONE, AclEntryPermission.READ_ATTRIBUTES)));

        assertDoesNotThrow(() -> RuntimeDirectoryAcl.validate(view, OWNER, new Lookup()));
        assertFalse(view.updated);
    }

    @Test
    void everyMutationGrantToUntrustedPrincipalIsRejectedWithoutRepair() {
        Set<AclEntryPermission> mutations = EnumSet.of(
                AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.DELETE, AclEntryPermission.DELETE_CHILD,
                AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER);
        for (AclEntryPermission permission : mutations) {
            MemoryAcl view = new MemoryAcl(List.of(allow(EVERYONE, permission)));
            assertThrows(IOException.class, () -> RuntimeDirectoryAcl.validate(view, OWNER, new Lookup()));
            assertFalse(view.updated);
        }
    }

    @Test
    void denyEntryDoesNotHideDangerousInheritedGrant() {
        AclEntry deny = AclEntry.newBuilder(allow(EVERYONE, AclEntryPermission.WRITE_DATA))
                .setType(AclEntryType.DENY)
                .build();
        AclEntry inherited = AclEntry.newBuilder(allow(EVERYONE, AclEntryPermission.WRITE_DATA))
                .setFlags(AclEntryFlag.INHERIT_ONLY, AclEntryFlag.DIRECTORY_INHERIT)
                .build();
        MemoryAcl view = new MemoryAcl(List.of(deny, inherited));

        assertThrows(IOException.class, () -> RuntimeDirectoryAcl.validate(view, OWNER, new Lookup()));
    }

    @Test
    void sameDisplayNameCannotImpersonateSystemAndUnknownTrustLookupDoesNotBroadenAccess() {
        Principal impostor = new Principal("untrusted-sid", SYSTEM.getName());
        MemoryAcl spoofed = new MemoryAcl(List.of(allow(impostor, AclEntryPermission.WRITE_OWNER)));
        assertThrows(IOException.class, () -> RuntimeDirectoryAcl.validate(spoofed, OWNER, new Lookup()));
        MemoryAcl system = new MemoryAcl(List.of(allow(SYSTEM, AclEntryPermission.DELETE_CHILD)));
        Lookup unavailable = new Lookup();
        unavailable.available = false;
        assertThrows(IOException.class, () -> RuntimeDirectoryAcl.validate(system, OWNER, unavailable));
        MemoryAcl owner = new MemoryAcl(List.of(allow(OWNER, AclEntryPermission.WRITE_DATA)));
        assertDoesNotThrow(() -> RuntimeDirectoryAcl.validate(owner, OWNER, unavailable));
    }

    @Test
    void missingOrUnreadableAclAndWrongOwnerFailClosed() {
        Lookup lookup = new Lookup();
        assertThrows(IOException.class, () -> RuntimeDirectoryAcl.validate(null, OWNER, lookup));
        assertThrows(IOException.class, () -> RuntimeDirectoryAcl.validate(new MemoryAcl(List.of()), OWNER, lookup));
        MemoryAcl wrongOwner = new MemoryAcl(List.of(allow(OWNER, AclEntryPermission.WRITE_DATA)));
        wrongOwner.owner = SYSTEM;
        assertThrows(IOException.class, () -> RuntimeDirectoryAcl.validate(wrongOwner, OWNER, lookup));
        MemoryAcl unreadable = new MemoryAcl(List.of(allow(OWNER, AclEntryPermission.WRITE_DATA)));
        unreadable.readFailure = true;
        assertThrows(IOException.class, () -> RuntimeDirectoryAcl.validate(unreadable, OWNER, lookup));
    }

    private static AclEntry allow(UserPrincipal principal, AclEntryPermission permission) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(permission)
                .build();
    }

    private record Principal(String identity, String name) implements GroupPrincipal {
        @Override
        public String getName() {
            return name;
        }
    }

    private static final class Lookup extends UserPrincipalLookupService {
        private boolean available = true;

        @Override
        public UserPrincipal lookupPrincipalByName(String name) throws IOException {
            if (available && name.equals("NT AUTHORITY\\SYSTEM")) {
                return SYSTEM;
            }
            throw new UserPrincipalNotFoundException(name);
        }

        @Override
        public GroupPrincipal lookupPrincipalByGroupName(String name) throws IOException {
            if (available && name.equals("BUILTIN\\Administrators")) {
                return ADMINISTRATORS;
            }
            throw new UserPrincipalNotFoundException(name);
        }
    }

    private static final class MemoryAcl implements AclFileAttributeView {
        private List<AclEntry> entries;
        private UserPrincipal owner = OWNER;
        private boolean updated;
        private boolean retainEveryone;
        private boolean writeFailure;
        private boolean readFailure;

        private MemoryAcl(List<AclEntry> entries) {
            this.entries = List.copyOf(entries);
        }

        @Override
        public String name() {
            return "acl";
        }

        @Override
        public List<AclEntry> getAcl() throws IOException {
            if (readFailure) {
                throw new IOException("ACL 无法读取");
            }
            return List.copyOf(entries);
        }

        @Override
        public void setAcl(List<AclEntry> acl) throws IOException {
            if (writeFailure) {
                throw new IOException("ACL 无法设置");
            }
            entries = new ArrayList<>(acl);
            if (retainEveryone) {
                entries.add(allow(EVERYONE, AclEntryPermission.WRITE_DATA));
            }
            updated = true;
        }

        @Override
        public UserPrincipal getOwner() {
            return owner;
        }

        @Override
        public void setOwner(UserPrincipal value) {
            owner = value;
        }
    }
}
