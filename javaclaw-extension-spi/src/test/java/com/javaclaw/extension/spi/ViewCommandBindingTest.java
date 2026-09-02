package com.javaclaw.extension.spi;

import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViewCommandBindingTest {
    @Test
    void preservesBoundedAuthoritativeBindings() {
        ViewAction action = new ViewAction(
                "启动",
                "execution/start",
                Map.of("mode", "safe"),
                Map.of(),
                new ExpectedRevisionBinding.None(),
                false,
                binding("profileId", "profile", "id"),
                binding("profileRevision", "profile", "revision"));

        assertEquals(2, action.commandBindings().size());
        assertEquals("profileId", action.commandBindings().getFirst().argumentName());
        assertEquals(
                new ViewBinding("profile", "revision"),
                action.commandBindings().getLast().binding());
    }

    @Test
    void rejectsDuplicateAndOverlappingAuthoritativeArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewAction(
                        "启动",
                        "execution/start",
                        Map.of(),
                        Map.of(),
                        new ExpectedRevisionBinding.None(),
                        false,
                        binding("profileId", "profile", "id"),
                        binding("profileId", "profile", "otherId")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewAction(
                        "启动",
                        "execution/start",
                        Map.of("profileId", "attacker"),
                        Map.of(),
                        new ExpectedRevisionBinding.None(),
                        false,
                        binding("profileId", "profile", "id")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewAction(
                        "启动",
                        "execution/start",
                        Map.of(),
                        Map.of("profileId", "id"),
                        new ExpectedRevisionBinding.None(),
                        false,
                        binding("profileId", "profile", "id")));
    }

    @Test
    void rejectsMoreThanMaximumBindings() {
        ViewCommandBinding[] bindings = IntStream.rangeClosed(0, ViewAction.MAX_COMMAND_BINDINGS)
                .mapToObj(index -> binding("argument" + index, "profile", "field" + index))
                .toArray(ViewCommandBinding[]::new);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewAction(
                        "启动",
                        "execution/start",
                        Map.of(),
                        Map.of(),
                        new ExpectedRevisionBinding.None(),
                        false,
                        bindings));
    }

    private static ViewCommandBinding binding(String argument, String source, String field) {
        return new ViewCommandBinding(argument, new ViewBinding(source, field));
    }
}
