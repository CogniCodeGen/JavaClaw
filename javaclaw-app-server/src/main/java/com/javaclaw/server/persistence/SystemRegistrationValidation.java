package com.javaclaw.server.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.javaclaw.builtin.contracts.CodingSystemContracts.Registration;

/** 在写入前应用宿主名称规则；Windows 不允许通过大小写绕过保留身份或登记唯一性。 */
final class SystemRegistrationValidation {
    private SystemRegistrationValidation() {}

    static void requirePlatform(List<Registration> registrations, String platform) {
        boolean insensitive = platform.equalsIgnoreCase("windows");
        Set<String> names = new HashSet<>();
        for (Registration registration : registrations) {
            String name = insensitive ? registration.id().toLowerCase(Locale.ROOT) : registration.id();
            if (name.startsWith("system.") || !names.add(name)) {
                throw PersistenceException.invalidRequest("系统程序登记名称与保留身份或现有名称冲突");
            }
        }
    }
}
