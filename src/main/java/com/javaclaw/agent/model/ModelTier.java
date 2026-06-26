package com.javaclaw.agent.model;

/**
 * 模型分级 — 按任务复杂度划分三档模型。
 *
 * <ul>
 *   <li>{@link #LIGHT}  — 轻量模型：意图识别、工具路由、记忆蒸馏、视觉描述、过程评估等
 *       一次性分类/分析调用。要求快、便宜、不开思考。</li>
 *   <li>{@link #NORMAL} — 普通模型：子专家智能体、知识专家、单步执行体、记忆压缩等
 *       常规任务。质量适中、性价比高。</li>
 *   <li>{@link #HIGH}   — 高性能模型：主编排器、规划智能体、ChallengerAgent、PlanEvolver、
 *       规划模式协调者等复杂推理与规划任务。要求最高质量。</li>
 * </ul>
 *
 * <p>配置层面：HIGH 复用现有 {@code api.*} 配置；NORMAL / LIGHT 使用各自前缀的配置项，
 * 任一字段缺失时回落到 HIGH，保证向后兼容。</p>
 *
 * @author JavaClaw
 */
public enum ModelTier {
    LIGHT,
    NORMAL,
    HIGH
}
