package com.javaclaw.framework.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable permission ceiling with explicit intersection semantics. */
public record PermissionSet(Set<Permission> values) {
    public static final PermissionSet NONE = new PermissionSet(Set.of());
    public static final PermissionSet UNRESTRICTED = PermissionSet.of("*");

    public PermissionSet {
        values = Collections.unmodifiableSet(new LinkedHashSet<>(values == null ? Set.of() : values));
    }

    public static PermissionSet of(String... permissions) {
        LinkedHashSet<Permission> values = new LinkedHashSet<>();
        for (String permission : permissions) {
            values.add(new Permission(permission));
        }
        return new PermissionSet(values);
    }

    public boolean contains(Permission permission) {
        return values.contains(new Permission("*")) || values.contains(permission);
    }

    public boolean contains(String permission) {
        return contains(new Permission(permission));
    }

    public boolean containsAll(PermissionSet required) {
        return values.contains(new Permission("*")) || values.containsAll(required.values);
    }

    public PermissionSet intersect(PermissionSet other) {
        if (values.contains(new Permission("*"))) return other;
        if (other.values.contains(new Permission("*"))) return this;
        LinkedHashSet<Permission> intersection = new LinkedHashSet<>(values);
        intersection.retainAll(other.values);
        return new PermissionSet(intersection);
    }

    public PermissionSet intersect(Collection<Permission> other) {
        return intersect(new PermissionSet(new LinkedHashSet<>(other)));
    }
}
