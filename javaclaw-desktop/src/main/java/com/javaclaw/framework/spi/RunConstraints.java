package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;

import java.util.Objects;

/** Permission and budget ceilings; callers always intersect/restrict the returned values. */
public record RunConstraints(PermissionSet permissions, RunBudget budget) {
    public RunConstraints {
        permissions = Objects.requireNonNull(permissions, "permissions");
        budget = Objects.requireNonNull(budget, "budget");
    }
}
