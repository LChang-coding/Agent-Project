package cn.bugstack.ai.domain.context.model;

/**
 * 上下文压缩异步命令。
 * <p>Kafka 事件用于即时通知，最终执行范围仍以 MySQL 任务账本为准。</p>
 */
public record ContextCompactionCommand(String taskId,
                                       String tenantId,
                                       String userId,
                                       String sessionId,
                                       Integer fromSequence,
                                       Integer toSequence,
                                       Integer expectedMemoryVersion,
                                       String policyVersion) {
}
