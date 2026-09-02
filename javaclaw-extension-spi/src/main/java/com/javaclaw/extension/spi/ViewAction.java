package com.javaclaw.extension.spi;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 平台执行的声明式 command。
 *
 * <p>权威 command binding 是独立参数来源。其参数名不得与固定参数或行参数重复，执行端必须最后应用绑定值，防止表单输入覆盖资源 ID、revision 等授权边界。
 */
public final class ViewAction {
    /** 单个操作允许的最大权威绑定数。 */
    public static final int MAX_COMMAND_BINDINGS = 32;

    private final String label;
    private final String command;
    private final Map<String, String> arguments;
    private final Map<String, String> rowArguments;
    private final ExpectedRevisionBinding expectedRevision;
    private final boolean dangerous;
    private final List<ViewCommandBinding> commandBindings;

    /**
     * 创建声明式 command。
     *
     * @param label 按钮标签
     * @param command 扩展 operation
     * @param arguments 固定字符串参数
     * @param rowArguments command 参数名到直接行字段名的映射
     * @param expectedRevision expected revision 的显式安全绑定
     * @param dangerous 是否要求平台危险操作确认
     * @param commandBindings 权威 command 参数绑定，最多 32 项
     */
    public ViewAction(
            String label,
            String command,
            Map<String, String> arguments,
            Map<String, String> rowArguments,
            ExpectedRevisionBinding expectedRevision,
            boolean dangerous,
            ViewCommandBinding... commandBindings) {
        this.label = ViewSchemaText.required(label, "label");
        this.command = ViewSchemaText.required(command, "command");
        this.arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
        this.rowArguments = Map.copyOf(Objects.requireNonNull(rowArguments, "rowArguments"));
        this.expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision");
        this.dangerous = dangerous;
        this.commandBindings = immutableBindings(commandBindings);
        requireDistinctArguments();
    }

    /** @return 按钮标签 */
    public String label() {
        return label;
    }

    /** @return 扩展 operation */
    public String command() {
        return command;
    }

    /** @return 不可变固定字符串参数 */
    public Map<String, String> arguments() {
        return arguments;
    }

    /** @return command 参数名到直接行字段名的不可变映射 */
    public Map<String, String> rowArguments() {
        return rowArguments;
    }

    /** @return expected revision 的显式安全绑定 */
    public ExpectedRevisionBinding expectedRevision() {
        return expectedRevision;
    }

    /** @return 是否要求平台危险操作确认 */
    public boolean dangerous() {
        return dangerous;
    }

    /** @return 不可变权威 command 参数绑定 */
    public List<ViewCommandBinding> commandBindings() {
        return commandBindings;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof ViewAction other)) {
            return false;
        }
        return dangerous == other.dangerous
                && label.equals(other.label)
                && command.equals(other.command)
                && arguments.equals(other.arguments)
                && rowArguments.equals(other.rowArguments)
                && expectedRevision.equals(other.expectedRevision)
                && commandBindings.equals(other.commandBindings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label, command, arguments, rowArguments, expectedRevision, dangerous, commandBindings);
    }

    @Override
    public String toString() {
        return "ViewAction[label=" + label + ", command=" + command + ", arguments=" + arguments
                + ", rowArguments=" + rowArguments + ", expectedRevision=" + expectedRevision + ", dangerous="
                + dangerous + ", commandBindings=" + commandBindings + "]";
    }

    private static List<ViewCommandBinding> immutableBindings(ViewCommandBinding[] bindings) {
        Objects.requireNonNull(bindings, "commandBindings");
        if (bindings.length > MAX_COMMAND_BINDINGS) {
            throw new IllegalArgumentException(
                    "commandBindings must contain at most " + MAX_COMMAND_BINDINGS + " items");
        }
        return Arrays.stream(bindings)
                .map(binding -> Objects.requireNonNull(binding, "commandBinding"))
                .toList();
    }

    private void requireDistinctArguments() {
        Set<String> names = new HashSet<>();
        for (ViewCommandBinding binding : commandBindings) {
            if (!names.add(binding.argumentName())) {
                throw new IllegalArgumentException("command binding argument is duplicated: " + binding.argumentName());
            }
            if (arguments.containsKey(binding.argumentName()) || rowArguments.containsKey(binding.argumentName())) {
                throw new IllegalArgumentException(
                        "authoritative command binding overlaps another argument: " + binding.argumentName());
            }
        }
    }
}
