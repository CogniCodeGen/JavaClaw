package com.javaclaw.client.cli;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.protocol.CoreRpcContracts;

/** CLI 将独立选择直接映射为 SDK 请求，不自行解析权限、Role 或默认值。 */
final class CliTurnRequest {
    private final CoreRpcContracts.TurnStartPayload payload;
    private final CommandOptions options;
    private final boolean nonInteractive;

    private CliTurnRequest(CoreRpcContracts.TurnStartPayload payload, CommandOptions options, boolean nonInteractive) {
        this.payload = payload;
        this.options = options;
        this.nonInteractive = nonInteractive;
    }

    static CliTurnRequest parse(List<String> arguments) {
        if (arguments.size() < 3) {
            throw new IllegalArgumentException("turn-start requires <thread-id> <message>");
        }
        Selection selection = new Selection();
        int index = 3;
        while (index < arguments.size()) {
            index = selection.option(arguments, index);
        }
        return new CliTurnRequest(
                new CoreRpcContracts.TurnStartPayload(
                        ThreadId.parse(arguments.get(1)), selection.execution(), arguments.get(2)),
                selection.options,
                selection.nonInteractive);
    }

    CoreRpcContracts.TurnStartPayload payload() {
        return payload;
    }

    CommandOptions options() {
        return options;
    }

    boolean nonInteractive() {
        return nonInteractive;
    }

    private static final class Selection {
        private Optional<AgentRoleRef> role = Optional.empty();
        private Optional<ProviderRef> provider = Optional.empty();
        private Optional<PermissionProfileRef> permission = Optional.empty();
        private Optional<ApprovalPolicy> approval = Optional.empty();
        private Optional<ReasoningPreference> reasoning = Optional.empty();
        private Optional<TurnBudget> budget = Optional.empty();
        private Optional<Set<String>> capabilities = Optional.empty();
        private CommandOptions options = CommandOptions.create(0);
        private boolean nonInteractive;

        private int option(List<String> values, int index) {
            String option = values.get(index);
            return switch (option) {
                case "--non-interactive" -> {
                    nonInteractive = true;
                    yield index + 1;
                }
                case "--role" -> {
                    role = Optional.of(new AgentRoleRef(value(values, index + 1), revision(values, index + 2)));
                    yield index + 3;
                }
                case "--provider" -> {
                    provider = Optional.of(new ProviderRef(
                            value(values, index + 1), revision(values, index + 2), value(values, index + 3)));
                    yield index + 4;
                }
                case "--permission" -> {
                    permission = Optional.of(
                            new PermissionProfileRef(value(values, index + 1), revision(values, index + 2)));
                    yield index + 3;
                }
                case "--approval" -> {
                    approval = Optional.of(ApprovalPolicy.valueOf(value(values, index + 1)));
                    yield index + 2;
                }
                case "--reasoning" -> {
                    reasoning = Optional.of(ReasoningPreference.valueOf(value(values, index + 1)));
                    yield index + 2;
                }
                case "--budget" -> {
                    budget = Optional.of(new TurnBudget(
                            revision(values, index + 1),
                            revision(values, index + 2),
                            Integer.parseInt(value(values, index + 3)),
                            Integer.parseInt(value(values, index + 4)),
                            Duration.ofSeconds(revision(values, index + 5))));
                    yield index + 6;
                }
                case "--capabilities" -> {
                    String selected = value(values, index + 1);
                    capabilities = Optional.of(
                            selected.isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(selected.split(",", -1))));
                    yield index + 2;
                }
                case "--idempotency-key" -> {
                    options = new CommandOptions(value(values, index + 1), 0);
                    yield index + 2;
                }
                default -> throw new IllegalArgumentException("unknown turn-start option: " + option);
            };
        }

        private ExecutionOverrides execution() {
            return new ExecutionOverrides(role, provider, permission, approval, budget, capabilities, reasoning);
        }
    }

    private static String value(List<String> values, int index) {
        if (index >= values.size()) {
            throw new IllegalArgumentException("missing turn-start option value");
        }
        return values.get(index);
    }

    private static long revision(List<String> values, int index) {
        return Long.parseLong(value(values, index));
    }
}
