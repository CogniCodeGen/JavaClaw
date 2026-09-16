package com.javaclaw.server.persistence;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.CodingSystemContracts.Registration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemRegistrationValidationTest {
    @Test
    void Windows登记拒绝保留前缀的任意大小写() {
        for (String id : List.of("System.echo", "SYSTEM.custom", "sYsTeM.tool")) {
            assertThrows(
                    PersistenceException.class,
                    () -> SystemRegistrationValidation.requirePlatform(List.of(registration(id)), "windows"));
        }
    }

    @Test
    void Windows拒绝大小写不同但名称相同的双登记() {
        for (String platform : List.of("windows", "Windows", "WINDOWS")) {
            assertThrows(
                    PersistenceException.class,
                    () -> SystemRegistrationValidation.requirePlatform(
                            List.of(registration("Tool"), registration("tool")), platform));
        }
    }

    @Test
    void POSIX登记保留区分大小写的身份语义() {
        for (String platform : List.of("linux", "macos")) {
            assertDoesNotThrow(() -> SystemRegistrationValidation.requirePlatform(
                    List.of(registration("Tool"), registration("tool"), registration("SYSTEM.custom")), platform));
        }
    }

    private Registration registration(String id) {
        return new Registration(id, "/explicit/program", List.of(), "UTF-8");
    }
}
