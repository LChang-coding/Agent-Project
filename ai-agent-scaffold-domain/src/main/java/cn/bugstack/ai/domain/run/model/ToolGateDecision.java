package cn.bugstack.ai.domain.run.model;

/**
 * 工具执行闸门决策。
 */
public enum ToolGateDecision {
    /** 运行状态与上下文版本仍有效，可以产生工具副作用。 */
    ALLOW,
    /** 工具前发生压缩，丢弃旧模型工具请求并用新上下文重新推理。 */
    RETRY_MODEL
}
