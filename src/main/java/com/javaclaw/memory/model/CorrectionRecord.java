package com.javaclaw.memory.model;

/**
 * 用户显式纠错记录。
 *
 * <p>纠错与普通蒸馏事实分开持久化：它既记录被用户否定的旧主张，也记录用户给出的替代主张，
 * 供召回阶段高优先级注入、回复阶段做重复错误拦截。公共知识纠错先进入
 * {@link Status#DISPUTED}，避免把未经核验的用户说法直接升级为客观事实。</p>
 */
public class CorrectionRecord {

    public enum Type {
        /** 用户明确给出“不是 X，而是 Y”一类事实替换。 */
        FACT_REPLACEMENT,
        /** 用户只否定旧答案，尚未给出可靠替代结论。 */
        RETRACTION,
        /** 用户纠正工具、步骤、流程或实现方法。 */
        METHOD_CORRECTION
    }

    public enum Scope {
        /** 用户本人、偏好和个人约定，用户是权威来源。 */
        USER,
        /** 当前项目、仓库、代码或工作区约定，用户是主要来源。 */
        PROJECT,
        /** 外部公共知识，需要工具或可靠来源核验。 */
        GENERAL
    }

    public enum Status {
        /** 已确认按用户/项目约定生效。 */
        ACTIVE,
        /** 旧说法已被质疑；新说法尚需外部核验。 */
        DISPUTED,
        /** 已被后续更正撤销，仅保留审计。 */
        REVOKED
    }

    public String id;
    public long entityId;

    public Type type;
    public Scope scope;
    public Status status;

    /** 被用户否定的主张；可能为空（用户只说“刚才回答错了”）。 */
    public String wrongClaim;

    /** 用户给出的替代主张；可能为空。 */
    public String correctClaim;

    /** 用户原始纠错文本，供审计和相关性判断。 */
    public String sourceInput;

    /** 被纠正的上一轮助手回复摘要，不直接作为有效事实召回。 */
    public String targetExcerpt;

    /** 被本次纠错直接废弃或标记争议的事实 id；可为空。 */
    public String targetFactId;

    public long createdAt;
    public long updatedAt;

    public CorrectionRecord() {}

    public boolean isEffective() {
        return status == Status.ACTIVE || status == Status.DISPUTED;
    }

    public boolean hasWrongClaim() {
        return wrongClaim != null && !wrongClaim.isBlank();
    }

    public boolean hasCorrectClaim() {
        return correctClaim != null && !correctClaim.isBlank();
    }
}
