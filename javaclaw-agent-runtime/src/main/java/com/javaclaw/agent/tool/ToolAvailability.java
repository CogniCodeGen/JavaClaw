package com.javaclaw.agent.tool;

/** Revalidates mutable external authority immediately before a snapshotted tool executes. */
@FunctionalInterface
public interface ToolAvailability {
    ToolAvailability ALWAYS = () -> {};

    /**
     * 复核工具来源是否仍启用、版本和权限是否仍有效；不合法时抛出异常阻止本次执行。
     *
     * @throws Exception 工具被禁用、版本变化或权限被撤销
     */
    void verify() throws Exception;

    /** 返回可绑定预授权的来源版本；无固定来源身份的工具不能取得无人值守授权。 */
    default java.util.Optional<Identity> identity() {
        return java.util.Optional.empty();
    }

    /**
     * 固定快照来源，不代表执行许可；每次执行仍须 verify。
     *
     * @param sourceId MCP 连接的持久标识
     * @param revision 连接配置版本，必须为正数
     */
    record Identity(String sourceId, long revision) {
        /** 拒绝没有持久来源或无效版本的授权身份。 */
        public Identity {
            if (sourceId == null || sourceId.isBlank() || revision < 1) {
                throw new IllegalArgumentException("invalid tool authority identity");
            }
        }
    }
}
